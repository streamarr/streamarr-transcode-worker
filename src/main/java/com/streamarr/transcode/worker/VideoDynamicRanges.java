package com.streamarr.transcode.worker;

import com.streamarr.transcode.engine.VideoDynamicRange;
import com.streamarr.transcode.engine.VideoDynamicRange.CompatibleBase;
import com.streamarr.transcode.engine.VideoDynamicRange.DolbyVision;
import com.streamarr.transcode.engine.VideoDynamicRange.DolbyVisionConfiguration;
import com.streamarr.transcode.engine.VideoDynamicRange.DolbyVisionRpuPlacement;
import com.streamarr.transcode.engine.VideoDynamicRange.Presence;
import java.util.Optional;
import java.util.OptionalInt;

final class VideoDynamicRanges {

  private VideoDynamicRanges() {}

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static VideoDynamicRange of(build.buf.gen.streamarr.transcode.v1.VideoDynamicRange value) {
    return new VideoDynamicRange(
        compatibleBase(value.getCompatibleBase()),
        presence(value.getHdr10Plus()),
        dolbyVision(value));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static CompatibleBase compatibleBase(
      build.buf.gen.streamarr.transcode.v1.CompatibleBase value) {
    return switch (value) {
      case COMPATIBLE_BASE_UNSPECIFIED -> CompatibleBase.UNSPECIFIED;
      case COMPATIBLE_BASE_NONE -> CompatibleBase.NONE;
      case COMPATIBLE_BASE_SDR -> CompatibleBase.SDR;
      case COMPATIBLE_BASE_PQ -> CompatibleBase.PQ;
      case COMPATIBLE_BASE_HLG -> CompatibleBase.HLG;
      case UNRECOGNIZED -> throw new InvalidJobSpecificationException("Unknown compatible base");
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Presence presence(build.buf.gen.streamarr.transcode.v1.Presence value) {
    return switch (value) {
      case PRESENCE_UNSPECIFIED -> Presence.UNSPECIFIED;
      case PRESENCE_ABSENT -> Presence.ABSENT;
      case PRESENCE_PRESENT -> Presence.PRESENT;
      case UNRECOGNIZED -> throw new InvalidJobSpecificationException("Unknown HDR presence");
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static DolbyVision dolbyVision(
      build.buf.gen.streamarr.transcode.v1.VideoDynamicRange value) {
    return switch (value.getDolbyVisionCase()) {
      case DOLBY_VISION_ABSENT -> new DolbyVision.Absent();
      case DOLBY_VISION_PRESENT -> {
        var present = value.getDolbyVisionPresent();
        var declared =
            present.hasDeclared()
                ? Optional.of(configuration(present.getDeclared()))
                : Optional.<DolbyVisionConfiguration>empty();
        yield new DolbyVision.Present(
            declared, presence(present.getObservedRpu()), rpuPlacement(present.getRpuPlacement()));
      }
      case DOLBYVISION_NOT_SET -> new DolbyVision.Unspecified();
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static DolbyVisionRpuPlacement rpuPlacement(
      build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuPlacement value) {
    return switch (value) {
      case DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED -> DolbyVisionRpuPlacement.UNSPECIFIED;
      case DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE ->
          DolbyVisionRpuPlacement.AFTER_OWN_PICTURE;
      case DOLBY_VISION_RPU_PLACEMENT_BEFORE_NEXT_PICTURE ->
          DolbyVisionRpuPlacement.BEFORE_NEXT_PICTURE;
      case UNRECOGNIZED -> throw new InvalidJobSpecificationException("Unknown RPU placement");
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static DolbyVisionConfiguration configuration(
      build.buf.gen.streamarr.transcode.v1.DolbyVisionConfiguration value) {
    return DolbyVisionConfiguration.builder()
        .profile(value.hasProfile() ? OptionalInt.of(value.getProfile()) : OptionalInt.empty())
        .level(value.hasLevel() ? OptionalInt.of(value.getLevel()) : OptionalInt.empty())
        .baseLayerCompatibilityId(
            value.hasBaseLayerCompatibilityId()
                ? OptionalInt.of(value.getBaseLayerCompatibilityId())
                : OptionalInt.empty())
        .baseLayer(value.getBaseLayer())
        .enhancementLayer(value.getEnhancementLayer())
        .rpu(value.getRpu())
        .build();
  }
}
