package com.streamarr.transcode.engine;

import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

public record VideoDynamicRange(
    @NonNull CompatibleBase compatibleBase,
    @NonNull Presence hdr10Plus,
    @NonNull DolbyVision dolbyVision) {

  public enum CompatibleBase {
    UNSPECIFIED,
    NONE,
    SDR,
    PQ,
    HLG
  }

  public enum Presence {
    UNSPECIFIED,
    ABSENT,
    PRESENT
  }

  public sealed interface DolbyVision {
    record Unspecified() implements DolbyVision {}

    record Absent() implements DolbyVision {}

    /**
     * @param rpuPlacement where the samples that the probe read place each picture's RPU
     */
    record Present(
        @NonNull Optional<DolbyVisionConfiguration> declared,
        @NonNull Presence observedRpu,
        @NonNull DolbyVisionRpuPlacement rpuPlacement)
        implements DolbyVision {}
  }

  /**
   * Where the samples of an H.264 or HEVC Dolby Vision stream place each picture's RPU, which
   * belongs after the slices of that picture, as the last NAL unit of the same sample.
   */
  public enum DolbyVisionRpuPlacement {
    /** The probe could not decide, the stream is AV1, or the samples match no other value. */
    UNSPECIFIED,
    /** Each RPU is the last NAL unit of its own picture's sample. */
    AFTER_OWN_PICTURE,
    /** Each picture's RPU starts the sample of the next picture. */
    BEFORE_NEXT_PICTURE
  }

  @Builder
  public record DolbyVisionConfiguration(
      @NonNull OptionalInt profile,
      @NonNull OptionalInt level,
      @NonNull OptionalInt baseLayerCompatibilityId,
      boolean baseLayer,
      boolean enhancementLayer,
      boolean rpu) {}

  public static VideoDynamicRange unspecified() {
    return new VideoDynamicRange(
        CompatibleBase.UNSPECIFIED, Presence.UNSPECIFIED, new DolbyVision.Unspecified());
  }

  public static VideoDynamicRange sdr() {
    return new VideoDynamicRange(CompatibleBase.SDR, Presence.ABSENT, new DolbyVision.Absent());
  }
}
