package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import build.buf.gen.streamarr.transcode.v1.CompatibleBase;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionConfiguration;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionPresent;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.VideoDynamicRange;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntConsumer;
import tools.jackson.databind.JsonNode;

/**
 * The dynamic range of a video stream, as ADR 0036 defines it for probe version 4. It comes from
 * the stream's headers and configuration records, never from its samples. So Dolby Vision is never
 * absent, and the RPUs and HDR10+ stay unspecified.
 */
final class DynamicRangeReader {

  private static final String DOLBY_VISION_RECORD = "DOVI configuration record";

  private static final Set<String> DOLBY_VISION_SAMPLE_ENTRIES =
      Set.of("dvh1", "dvhe", "dav1", "dva1", "dvav");

  /** FFmpeg's names for the transfers of standard dynamic range. */
  private static final Set<String> SDR_TRANSFERS =
      Set.of(
          "bt709",
          "smpte170m",
          "smpte240m",
          "gamma22",
          "gamma28",
          "bt470m",
          "bt470bg",
          "iec61966-2-1",
          "iec61966-2-4",
          "bt1361e",
          "bt2020-10",
          "bt2020-12");

  private DynamicRangeReader() {}

  static VideoDynamicRange read(JsonNode stream) {
    var dolbyVision = dolbyVision(stream);
    var dynamicRange =
        VideoDynamicRange.newBuilder()
            .setCompatibleBase(compatibleBase(stream, dolbyVision.isPresent()))
            .setHdr10Plus(Presence.PRESENCE_UNSPECIFIED);
    dolbyVision.ifPresent(dynamicRange::setDolbyVisionPresent);
    return dynamicRange.build();
  }

  /** The signaled transfer decides, even when a Dolby Vision record claims another base. */
  private static CompatibleBase compatibleBase(JsonNode stream, boolean dolbyVision) {
    var transfer = text(stream, "color_transfer").filter(value -> !"unknown".equals(value));
    if (transfer.isEmpty()) {
      return dolbyVision
          ? CompatibleBase.COMPATIBLE_BASE_NONE
          : CompatibleBase.COMPATIBLE_BASE_UNSPECIFIED;
    }

    return switch (transfer.get()) {
      case "smpte2084" -> CompatibleBase.COMPATIBLE_BASE_PQ;
      case "arib-std-b67" -> CompatibleBase.COMPATIBLE_BASE_HLG;
      case String name when SDR_TRANSFERS.contains(name) -> CompatibleBase.COMPATIBLE_BASE_SDR;
      default -> CompatibleBase.COMPATIBLE_BASE_UNSPECIFIED;
    };
  }

  private static Optional<DolbyVisionPresent> dolbyVision(JsonNode stream) {
    var present = DolbyVisionPresent.newBuilder().setObservedRpu(Presence.PRESENCE_UNSPECIFIED);
    var claim = VideoStreamFacts.sideData(stream, DOLBY_VISION_RECORD);
    if (claim.isPresent()) {
      return Optional.of(present.setDeclared(configuration(claim.get())).build());
    }

    return text(stream, "codec_tag_string")
        .filter(DOLBY_VISION_SAMPLE_ENTRIES::contains)
        .map(_ -> present.build());
  }

  private static DolbyVisionConfiguration configuration(JsonNode claim) {
    var configuration =
        DolbyVisionConfiguration.newBuilder()
            .setBaseLayer(isSet(claim, "bl_present_flag"))
            .setEnhancementLayer(isSet(claim, "el_present_flag"))
            .setRpu(isSet(claim, "rpu_present_flag"));
    setIfPresent(claim, "dv_profile", configuration::setProfile);
    setIfPresent(claim, "dv_level", configuration::setLevel);
    setIfPresent(
        claim, "dv_bl_signal_compatibility_id", configuration::setBaseLayerCompatibilityId);
    return configuration.build();
  }

  private static boolean isSet(JsonNode claim, String flag) {
    return claim.path(flag).asInt() == 1;
  }

  /** A record value that ffprobe does not print stays absent, because 0 is a real value. */
  private static void setIfPresent(JsonNode claim, String name, IntConsumer setter) {
    var value = claim.path(name);
    if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0) {
      setter.accept(value.intValue());
    }
  }
}
