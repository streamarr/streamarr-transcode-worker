package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Compares the video that an initialization segment declares with the job's video decision: the
 * codec string always, for an encode the 8-bit 4:2:0 picture format and progressive video too, and
 * for a copy that strips Dolby Vision the absence of a Dolby Vision configuration record. It also
 * requires the decoder configuration record to hold the parameter sets that its sample entry
 * promises. Audio codec strings are not compared, because the job carries none.
 */
final class VideoOutputCheck {

  // The Dolby Vision configuration record boxes (ETSI TS 103 572 / Dolby's ISO BMFF spec).
  private static final Set<String> DOLBY_VISION_RECORDS = Set.of("dvcC", "dvvC", "dvwC");

  private VideoOutputCheck() {}

  /**
   * @throws FragmentedMp4Exception with {@link Reason#OUTPUT_MISMATCH} when the segment declares
   *     other video than the decision, an encode that declares interlaced video, or video whose
   *     decoder configuration record cannot be read or lacks a parameter set that its sample entry
   *     promises, or a Dolby Vision configuration record that the copy strips; with {@link
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
