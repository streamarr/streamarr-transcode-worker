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

    record Present(
        @NonNull Optional<DolbyVisionConfiguration> declared, @NonNull Presence observedRpu)
        implements DolbyVision {}
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
