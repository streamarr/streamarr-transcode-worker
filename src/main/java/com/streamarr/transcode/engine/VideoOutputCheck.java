package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Compares the video that an initialization segment declares with the job's video decision: the
 * codec string always, and for an encode the 8-bit 4:2:0 picture format too. It also requires the
 * decoder configuration record to hold the parameter sets that its sample entry promises. Audio
 * codec strings are not compared, because the job carries none.
 */
final class VideoOutputCheck {

  private VideoOutputCheck() {}

  /**
   * @throws FragmentedMp4Exception with {@link Reason#OUTPUT_MISMATCH} when the segment declares
   *     other video than the decision, or video whose decoder configuration record cannot be read
   *     or lacks a parameter set that its sample entry promises; with {@link Reason#MALFORMED_BOX}
   *     when a box that leads to the video sample entry is missing or too short
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

    if (!readable(() -> CodecConfigurationReader.holdsPromisedParameterSets(sampleEntry))) {
      throw mismatch(
          "the "
              + sampleEntry.type()
              + " sample entry promises parameter sets that its decoder configuration record does"
              + " not hold");
    }
  }

  // A missing box on the way to the sample entry stays a malformed box; only a decoder
  // configuration record that cannot be read is a mismatch.
  private static <T> T readable(Supplier<T> record) {
    try {
      return record.get();
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
