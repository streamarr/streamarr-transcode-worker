package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.VideoDynamicRange.CompatibleBase;
import java.util.Arrays;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Compares the video that an initialization segment declares with the job's video decision: the
 * codec string always, for an encode the job's picture format and progressive video too, and for a
 * PQ or HLG encode its BT.2020 colour description and an {@code hvc1} record that holds exactly a
 * PQ encode's static HDR metadata messages, for a copy that strips Dolby Vision the absence of a
 * Dolby Vision configuration record, and for a copy with a planned output a colour description that
 * does not contradict the planned range and an {@code hvc1} record that holds exactly the static
 * HDR metadata messages that it plans. It also requires the decoder configuration record to hold
 * the parameter sets that its sample entry promises. Audio codec strings are not compared, because
 * the job carries none.
 */
final class VideoOutputCheck {

  // The transfer characteristics of BT.2100 PQ and HLG (ITU-T H.273 table 3).
  private static final int PQ_TRANSFER = 16;
  private static final int HLG_TRANSFER = 18;
  // BT.2020 colour primaries and its non-constant luminance matrix (ITU-T H.273 tables 2 and 4).
  private static final int BT2020_PRIMARIES = 9;
  private static final int BT2020_NON_CONSTANT_MATRIX = 9;

  private VideoOutputCheck() {}

  /**
   * @throws FragmentedMp4Exception with {@link Reason#OUTPUT_MISMATCH} when the segment declares
   *     other video than the decision, an encode that declares interlaced video, or video whose
   *     decoder configuration record cannot be read or lacks a parameter set that its sample entry
   *     promises, a Dolby Vision configuration record that the copy strips, or, for a copy with a
   *     planned output, a colr box that names another transfer than the planned range or an hvc1
   *     record whose SEI messages are not exactly the static HDR metadata that it plans, or a
   *     sample entry or Dolby Vision record other than the planned Dolby Vision output; with {@link
   *     Reason#MALFORMED_BOX} when a box that leads to the video sample entry is missing or too
   *     short
   */
  static void requireMatch(VideoDecision decision, InitializationSegment segment) {
    var sampleEntry =
        CodecConfigurationReader.firstVideoSampleEntryOf(segment)
            .orElseThrow(() -> mismatch("the initialization segment declares no video track"));
    var video = readable(() -> CodecConfigurationReader.videoOf(sampleEntry));
    if (!video.codecString().equals(decision.codecString())) {
      throw mismatch(
          "the initialization segment declares "
              + video.codecString()
              + " where the job advertises "
              + decision.codecString());
    }

    if (decision instanceof VideoDecision.Encode encode) {
      requireEncodedOutput(encode, video, sampleEntry);
    }

    if (encodeDeclaresInterlacedVideo(decision, sampleEntry)) {
      throw mismatch("the encode declares interlaced video where every encode is progressive");
    }

    if (decision.lists(BitstreamRewrite.STRIP_DOLBY_VISION)
        && holdsDolbyVisionRecord(sampleEntry)) {
      throw mismatch(
          "the copy strips Dolby Vision, but its "
              + sampleEntry.type()
              + " sample entry holds a Dolby Vision configuration record");
    }

    if (decision instanceof VideoDecision.Copy copy && copy.plannedOutput().isPresent()) {
      requirePlannedOutput(copy, copy.plannedOutput().get(), sampleEntry);
    }

    boolean holdsParameterSets =
        readable(() -> CodecConfigurationReader.holdsPromisedParameterSets(sampleEntry));
    if (!holdsParameterSets) {
      throw mismatch(
          "the "
              + sampleEntry.type()
              + " sample entry promises parameter sets that its decoder configuration record does"
              + " not hold");
    }
  }

  // An encode writes the job's picture format, and a PQ or HLG encode the BT.2020 colour
  // description of its range, which the filters put on every frame so that the muxer writes it.
  // An hvc1 record of a PQ encode holds exactly the job's static HDR metadata messages.
  private static void requireEncodedOutput(
      VideoDecision.Encode encode, TrackCodec.Video video, BoxView sampleEntry) {
    if (!video.pictureFormat().equals(Optional.of(encode.outputPictureFormat()))) {
      throw mismatch(
          "the encode declares "
              + video.pictureFormat().map(VideoOutputCheck::describe).orElse("no picture format")
              + " where the job asks for "
              + describe(encode.outputPictureFormat()));
    }

    var transfer = HdrTransfer.of(encode.outputDynamicRange());
    if (transfer.isEmpty()) {
      return;
    }

    var expected =
        new CodecConfigurationReader.ColourDescription(
            BT2020_PRIMARIES, transfer.get().transferCharacteristics(), BT2020_NON_CONSTANT_MATRIX);
    var declared = readable(() -> CodecConfigurationReader.colourDescriptionOf(sampleEntry));
    if (!declared.equals(Optional.of(expected))) {
      throw mismatch(
          "the encode declares "
              + declared.map(Object::toString).orElse("no nclx colr box")
              + " where its "
              + transfer.get()
              + " output takes "
              + expected);
    }

    if (sampleEntry.type().equals("hvc1") && encode.staticHdrMetadata().isPresent()) {
      requirePlannedStaticMetadata(encode, sampleEntry);
    }
  }

  private static void requirePlannedOutput(
      VideoDecision.Copy copy, PlannedCopyOutput planned, BoxView sampleEntry) {
    requirePlannedTransfer(planned.dynamicRange().compatibleBase(), sampleEntry);
    if (sampleEntry.type().equals("hvc1")) {
      requirePlannedStaticMetadata(copy, sampleEntry);
    }

    planned.dolbyVision().ifPresent(output -> requirePlannedDolbyVision(copy, output, sampleEntry));
  }

  // The playlist's codec string names the sample entry, and a player reads the Dolby Vision
  // profile, level and compatibility from the one record.
  private static void requirePlannedDolbyVision(
      VideoDecision.Copy copy, PlannedCopyOutput.DolbyVisionOutput output, BoxView sampleEntry) {
    if (!sampleEntry.type().equals(output.sampleEntry().type())) {
      throw mismatch(
          "the copy plans a "
              + output.sampleEntry().type()
              + " sample entry where the segment declares "
              + sampleEntry.type());
    }

    var records =
        readable(
            () ->
                DolbyVisionRecord.recordsAmong(
                    sampleEntry
                        .after(CodecConfigurationReader.VISUAL_SAMPLE_ENTRY_BYTES)
                        .children()));
    if (records.size() != 1) {
      throw mismatch(
          "the "
              + sampleEntry.type()
              + " sample entry holds "
              + records.size()
              + " Dolby Vision configuration records where the copy plans one");
    }

    var configuration = records.getFirst();
    if (configuration.profile() != output.profile()
        || configuration.level() != output.level()
        || configuration.compatibilityId() != output.baseLayerCompatibilityId()) {
      throw mismatch(
          "the Dolby Vision configuration record declares "
              + configuration
              + " where the copy plans "
              + output);
    }

    if (copy.lists(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD)
        && !configuration.equals(DolbyVisionRecord.normalizedFor(output))) {
      throw mismatch(
          "the Dolby Vision configuration record declares "
              + configuration
              + " where the copy normalizes it to "
              + DolbyVisionRecord.normalizedFor(output));
    }
  }

  // A copy keeps the source's colour description, which FFmpeg writes into the colr box when the
  // source signals one. A box that names another transfer than the planned range would play the
  // copy in the wrong range; without the box, the player reads the SPS that the copy keeps.
  private static void requirePlannedTransfer(CompatibleBase base, BoxView sampleEntry) {
    var declared = readable(() -> CodecConfigurationReader.transferCharacteristicsOf(sampleEntry));
    if (declared.isEmpty()) {
      return;
    }

    var transfer = declared.getAsInt();
    var matches =
        switch (base) {
          case PQ -> transfer == PQ_TRANSFER;
          case HLG -> transfer == HLG_TRANSFER;
          case SDR -> transfer != PQ_TRANSFER && transfer != HLG_TRANSFER;
          case NONE, UNSPECIFIED -> true;
        };
    if (!matches) {
      throw mismatch(
          "the colr box declares transfer "
              + transfer
              + " where the copy plans a "
              + base
              + " output");
    }
  }

  // The rewrite writes exactly the planned messages, each on its own, so the record must hold those
  // NAL units and no other SEI message.
  private static void requirePlannedStaticMetadata(VideoDecision decision, BoxView sampleEntry) {
    var held = readable(() -> CodecConfigurationReader.seiNalUnitsOf(sampleEntry));
    var planned = InitializationSegmentRewrite.plannedSeiUnitsOf(decision);
    if (held.size() != planned.size()
        || IntStream.range(0, held.size())
            .anyMatch(index -> !Arrays.equals(held.get(index), planned.get(index)))) {
      throw mismatch(
          "the hvcC holds "
              + held.size()
              + " SEI NAL units where the job plans exactly "
              + planned.size()
              + " static HDR metadata messages");
    }
  }

  private static boolean holdsDolbyVisionRecord(BoxView sampleEntry) {
    return readable(
        () ->
            sampleEntry
                .after(CodecConfigurationReader.VISUAL_SAMPLE_ENTRY_BYTES)
                .children()
                .stream()
                .anyMatch(child -> DolbyVisionRecord.BOX_TYPES.contains(child.type())));
  }

  private static boolean encodeDeclaresInterlacedVideo(
      VideoDecision decision, BoxView sampleEntry) {
    if (!(decision instanceof VideoDecision.Encode)) {
      return false;
    }

    return readable(() -> InterlacedVideoSignals.declaresInterlacedVideo(sampleEntry));
  }

  // A missing box on the way to the sample entry stays a malformed box; only a decoder
  // configuration record that cannot be read is a mismatch.
  private static <T> T readable(Supplier<T> reading) {
    try {
      return reading.get();
    } catch (FragmentedMp4Exception e) {
      throw mismatch("the video codec configuration cannot be read: " + e.getMessage());
    }
  }

  private static String describe(PictureFormat format) {
    var chroma =
        switch (format.chromaSubsampling()) {
          case MONOCHROME -> "4:0:0";
          case CHROMA_420 -> "4:2:0";
          case CHROMA_422 -> "4:2:2";
          case CHROMA_444 -> "4:4:4";
        };
    return format.bitDepth() + "-bit " + chroma;
  }

  private static FragmentedMp4Exception mismatch(String detail) {
    return new FragmentedMp4Exception(Reason.OUTPUT_MISMATCH, detail);
  }
}
