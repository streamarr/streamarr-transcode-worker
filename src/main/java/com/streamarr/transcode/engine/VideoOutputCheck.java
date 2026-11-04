package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.VideoDynamicRange.CompatibleBase;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.IntStream;

/**
 * Compares the video that an initialization segment declares with the job's video decision: the
 * codec string always, for an encode the 8-bit 4:2:0 picture format and progressive video too, for
 * a copy that strips Dolby Vision the absence of a Dolby Vision configuration record, and for a
 * copy with a planned output a colour description that does not contradict the planned range and an
 * {@code hvc1} record that holds exactly the static HDR metadata messages that it plans. It also
 * requires the decoder configuration record to hold the parameter sets that its sample entry
 * promises. Audio codec strings are not compared, because the job carries none.
 */
final class VideoOutputCheck {

  // The Dolby Vision configuration record boxes (ETSI TS 103 572 / Dolby's ISO BMFF spec).
  private static final Set<String> DOLBY_VISION_RECORDS = Set.of("dvcC", "dvvC", "dvwC");
  // The transfer characteristics of BT.2100 PQ and HLG (ITU-T H.273 table 3).
  private static final int PQ_TRANSFER = 16;
  private static final int HLG_TRANSFER = 18;

  private VideoOutputCheck() {}

  /**
   * @throws FragmentedMp4Exception with {@link Reason#OUTPUT_MISMATCH} when the segment declares
   *     other video than the decision, an encode that declares interlaced video, or video whose
   *     decoder configuration record cannot be read or lacks a parameter set that its sample entry
   *     promises, a Dolby Vision configuration record that the copy strips, or, for a copy with a
   *     planned output, a colr box that names another transfer than the planned range or an hvc1
   *     record whose SEI messages are not exactly the static HDR metadata that it plans; with
   *     {@link Reason#MALFORMED_BOX} when a box that leads to the video sample entry is missing or
   *     too short
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

    if (decision instanceof VideoDecision.Encode
        && !video.pictureFormat().equals(Optional.of(PictureFormat.EIGHT_BIT_420))) {
      throw mismatch(
          "the encode declares "
              + video.pictureFormat().map(VideoOutputCheck::describe).orElse("no picture format")
              + " where every encode is 8-bit 4:2:0");
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

  private static void requirePlannedOutput(
      VideoDecision.Copy copy, PlannedCopyOutput planned, BoxView sampleEntry) {
    requirePlannedTransfer(planned.dynamicRange().compatibleBase(), sampleEntry);
    if (sampleEntry.type().equals("hvc1")) {
      requirePlannedStaticMetadata(copy, sampleEntry);
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
  private static void requirePlannedStaticMetadata(
      VideoDecision.Copy decision, BoxView sampleEntry) {
    var held = readable(() -> CodecConfigurationReader.seiNalUnitsOf(sampleEntry));
    var planned = InitializationSegmentRewrite.plannedSeiUnitsOf(decision);
    if (held.size() != planned.size()
        || IntStream.range(0, held.size())
            .anyMatch(index -> !Arrays.equals(held.get(index), planned.get(index)))) {
      throw mismatch(
          "the hvcC holds "
              + held.size()
              + " SEI NAL units where the copy plans exactly "
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
                .anyMatch(child -> DOLBY_VISION_RECORDS.contains(child.type())));
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
