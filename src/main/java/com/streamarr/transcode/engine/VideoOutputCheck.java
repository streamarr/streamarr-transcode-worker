package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.TrackCodec.Video;
import java.util.Optional;

/**
 * Compares the video that an initialization segment declares with the job's video decision: the
 * codec string always, and for an encode the 8-bit 4:2:0 picture format too. Audio codec strings
 * are not compared, because the job carries none.
 */
final class VideoOutputCheck {

  private VideoOutputCheck() {}

  /**
   * @throws FragmentedMp4Exception with {@link Reason#OUTPUT_MISMATCH} when the segment declares
   *     other video than the decision, or video whose codec configuration cannot be read
   */
  static void requireMatch(VideoDecision decision, InitializationSegment segment) {
    var video = videoOf(segment);
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
  }

  private static Video videoOf(InitializationSegment segment) {
    try {
      return CodecConfigurationReader.firstVideoOf(segment)
          .orElseThrow(() -> mismatch("the initialization segment declares no video track"));
    } catch (FragmentedMp4Exception e) {
      if (e.getReason() == Reason.OUTPUT_MISMATCH) {
        throw e;
      }

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
