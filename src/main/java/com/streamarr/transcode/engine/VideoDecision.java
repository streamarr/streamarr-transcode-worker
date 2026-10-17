package com.streamarr.transcode.engine;

import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/** Whether the job copies or encodes the video, and the codec string the variant advertises. */
public sealed interface VideoDecision {

  /** The codec string that every initialization segment of the variant declares for its video. */
  String codecString();

  /** A stream copy, whose codec string the probe computed for the source stream. */
  record Copy(@NonNull String codecString) implements VideoDecision {}

  /**
   * An encode, which forces the codec profile, the tier and the level.
   *
   * @param tier empty for H.264, which has no tier
   * @param level the level as major × 10 + minor, for example 41 for level 4.1
   */
  @Builder
  record Encode(
      @NonNull VideoCodecProfile codecProfile,
      @NonNull Optional<VideoCodecTier> tier,
      int level,
      @NonNull String codecString,
      @NonNull VideoDynamicRange sourceDynamicRange,
      @NonNull VideoDynamicRange outputDynamicRange)
      implements VideoDecision {}
}
