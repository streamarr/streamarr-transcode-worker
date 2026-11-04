package com.streamarr.transcode.engine;

import java.util.Optional;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

/** Whether the job copies or encodes the video, and the codec string the variant advertises. */
public sealed interface VideoDecision {

  /** The chosen video stream's absolute index in the source container. */
  int streamIndex();

  /** The codec string that every initialization segment of the variant declares for its video. */
  String codecString();

  /** Whether the job lists the bitstream rewrite, which only a stream copy can. */
  default boolean lists(BitstreamRewrite rewrite) {
    return false;
  }

  /**
   * A stream copy, whose codec string the probe computed for the source stream.
   *
   * @param keepsPacketBytes whether the copy delivers every packet of the source stream with its
   *     bytes unchanged, which the probe decides from the source's decoder configuration record;
   *     false when the job does not say so
   * @param bitstreamRewrites the changes that the copy makes to the source's video; empty for a
   *     copy that passes it through unchanged
   */
  @Builder
  record Copy(
      int streamIndex,
      @NonNull String codecString,
      boolean keepsPacketBytes,
      @NonNull Set<BitstreamRewrite> bitstreamRewrites)
      implements VideoDecision {

    public Copy {
      bitstreamRewrites = Set.copyOf(bitstreamRewrites);
    }

    @Override
    public boolean lists(BitstreamRewrite rewrite) {
      return bitstreamRewrites.contains(rewrite);
    }

    // Lombok builder default — its generated setter assigns it
    @SuppressWarnings({"java:S1068", "java:S9438"})
    public static class CopyBuilder {
      private Set<BitstreamRewrite> bitstreamRewrites = Set.of();
    }
  }

  /**
   * An encode, which forces the codec profile, the tier and the level.
   *
   * @param sourceCodecFamily the codec family of the chosen source stream; empty for a codec of no
   *     family the worker encodes
   * @param tier empty for H.264, which has no tier
   * @param level the level as major × 10 + minor, for example 41 for level 4.1
   * @param sourcePacketsCarryPresentationTimes whether the probe saw a presentation time on every
   *     packet of the source stream that it read; false also when it could not tell
   * @param deinterlace how the encode deinterlaces an interlaced source; empty for every other
   *     source
   */
  @Builder
  record Encode(
      int streamIndex,
      @NonNull Optional<CodecFamily> sourceCodecFamily,
      @NonNull VideoCodecProfile codecProfile,
      @NonNull Optional<VideoCodecTier> tier,
      int level,
      @NonNull String codecString,
      @NonNull VideoDynamicRange sourceDynamicRange,
      @NonNull VideoDynamicRange outputDynamicRange,
      boolean sourcePacketsCarryPresentationTimes,
      @NonNull Optional<Deinterlace> deinterlace)
      implements VideoDecision {

    // Lombok builder default — its generated setter assigns it
    @SuppressWarnings({"java:S1068", "java:S9438"})
    public static class EncodeBuilder {
      private Optional<Deinterlace> deinterlace = Optional.empty();
    }
  }
}
