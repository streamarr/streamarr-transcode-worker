package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import build.buf.gen.streamarr.transcode.v1.CompatibleBase;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionAbsent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionConfiguration;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionPresent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.VideoDynamicRange;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.IntConsumer;
import tools.jackson.databind.JsonNode;

/**
 * The dynamic range of a video stream, as ADR 0036 defines it. The stream's headers and
 * configuration records give the Dolby Vision claim and the signaled transfer. For the chosen video
 * stream, the first frames that the probe read also show HDR10+ and Dolby Vision RPUs, when the
 * stream's decoder exports them. A feature that the probe cannot see stays unspecified.
 */
final class DynamicRangeReader {

  private static final String DOLBY_VISION_RECORD = "DOVI configuration record";
  private static final String HDR10_PLUS = "HDR Dynamic Metadata SMPTE2094-40 (HDR10+)";
  private static final String RPU_DATA = "Dolby Vision RPU Data";
  private static final String RPU_METADATA = "Dolby Vision Metadata";
  private static final int PROFILE_WITHOUT_COMPATIBLE_BASE = 5;

  private static final Set<String> DOLBY_VISION_SAMPLE_ENTRIES =
      Set.of("dvh1", "dvhe", "dav1", "dva1", "dvav");

  // The decoders whose frames carry HDR10+ metadata and Dolby Vision RPUs. FFmpeg 8.1's H.264
  // decoder drops both, so the probe finds H.264 RPUs in the packets alone.
  private static final Set<String> DECODERS_SHOWING_FRAME_METADATA = Set.of("hevc", "av1");

  private static final List<String> RPU_HEADER_FIELDS =
      List.of(
          "vdr_rpu_profile",
          "bl_video_full_range_flag",
          "el_spatial_resampling_filter_flag",
          "disable_residual_flag",
          "vdr_bit_depth");

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
          "iec61966-2-4",
          "iec61966-2-1",
          "bt1361e",
          "bt2020-10",
          "bt2020-12");

  private DynamicRangeReader() {}

  static VideoDynamicRange read(JsonNode stream) {
    var codec = text(stream, "codec_name").orElse("");
    var frames =
        FirstFrames.of(stream).filter(_ -> DECODERS_SHOWING_FRAME_METADATA.contains(codec));
    var rpus = rpus(stream, codec, frames);

    var record = VideoStreamFacts.sideData(stream, DOLBY_VISION_RECORD);
    var dynamicRange =
        VideoDynamicRange.newBuilder()
            .setHdr10Plus(
                frames
                    .map(observed -> presence(observed.carries(HDR10_PLUS)))
                    .orElse(Presence.PRESENCE_UNSPECIFIED));
    var hasSampleEntry =
        text(stream, "codec_tag_string").filter(DOLBY_VISION_SAMPLE_ENTRIES::contains).isPresent();
    var observedRpus = rpus.filter(observed -> observed.presence() == Presence.PRESENCE_PRESENT);
    var isDolbyVision = record.isPresent() || hasSampleEntry || observedRpus.isPresent();
    if (isDolbyVision) {
      dynamicRange.setDolbyVisionPresent(present(record, rpus));
    }

    if (!isDolbyVision && rpus.isPresent()) {
      dynamicRange.setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance());
    }

    var profile =
        record
            .flatMap(claim -> integer(claim, "dv_profile"))
            .or(
                () ->
                    observedRpus
                        .flatMap(RpuObservation::firstHeader)
                        .flatMap(header -> guess(codec, header)));
    if (profile.filter(value -> value == PROFILE_WITHOUT_COMPATIBLE_BASE).isPresent()) {
      return dynamicRange.setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_NONE).build();
    }

    return dynamicRange.setCompatibleBase(compatibleBase(stream, isDolbyVision)).build();
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

  private static DolbyVisionPresent present(
      Optional<JsonNode> record, Optional<RpuObservation> rpus) {
    var present =
        DolbyVisionPresent.newBuilder()
            .setObservedRpu(
                rpus.map(RpuObservation::presence).orElse(Presence.PRESENCE_UNSPECIFIED))
            .setRpuStartsSample(
                rpus.map(RpuObservation::startsSample).orElse(Presence.PRESENCE_UNSPECIFIED));
    record.ifPresent(claim -> present.setDeclared(configuration(claim)));
    rpus.flatMap(RpuObservation::firstHeader).ifPresent(present::setFirstRpuHeader);
    return present.build();
  }

  private static Optional<Integer> guess(String codec, DolbyVisionRpuHeader header) {
    var profile = DolbyVisionProfileGuess.of(codec, header);
    return profile.isPresent() ? Optional.of(profile.getAsInt()) : Optional.empty();
  }

  /**
   * What the frames and, for a codec of NAL units, the packets that hold them show of the RPUs. A
   * decoder can ignore or drop an RPU, so no RPU in the frames rules out none: only packets whose
   * every NAL unit the probe delimited do. The header comes from the frames, or from the packets
   * when the frames show no RPU.
   */
  private static Optional<RpuObservation> rpus(
      JsonNode stream, String codec, Optional<FirstFrames> frames) {
    var fromFrames = frames.map(DynamicRangeReader::rpusOf);
    var syntax = NalSyntax.of(codec);
    if (syntax.isEmpty() || FirstFrames.of(stream).isEmpty()) {
      return fromFrames;
    }

    var fromPackets = PacketRpuReader.read(stream, syntax.get());
    var isInFrames =
        fromFrames.filter(observed -> observed.presence() == Presence.PRESENCE_PRESENT).isPresent();
    if (!isInFrames) {
      return fromPackets;
    }

    return Optional.of(
        new RpuObservation(
            Presence.PRESENCE_PRESENT,
            fromFrames.get().firstHeader(),
            fromPackets.map(RpuObservation::startsSample).orElse(Presence.PRESENCE_UNSPECIFIED)));
  }

  // A decoder hides where an RPU sits in its sample.
  private static RpuObservation rpusOf(FirstFrames frames) {
    var observed = frames.carries(RPU_DATA) || frames.carries(RPU_METADATA);
    return new RpuObservation(
        presence(observed),
        frames.earliestSideData(RPU_METADATA).flatMap(DynamicRangeReader::header),
        Presence.PRESENCE_UNSPECIFIED);
  }

  /** The header that ffprobe parsed; empty when ffprobe printed only part of it. */
  private static Optional<DolbyVisionRpuHeader> header(JsonNode metadata) {
    var values = RPU_HEADER_FIELDS.stream().map(name -> integer(metadata, name)).toList();
    if (values.stream().anyMatch(Optional::isEmpty)) {
      return Optional.empty();
    }

    return Optional.of(
        DolbyVisionRpuHeader.newBuilder()
            .setVdrRpuProfile(values.get(0).orElseThrow())
            .setBaseLayerFullRange(values.get(1).orElseThrow() == 1)
            .setEnhancementLayerSpatialResamplingFilter(values.get(2).orElseThrow() == 1)
            .setDisableResidual(values.get(3).orElseThrow() == 1)
            .setVdrBitDepth(values.get(4).orElseThrow())
            .build());
  }

  private static Presence presence(boolean present) {
    return present ? Presence.PRESENCE_PRESENT : Presence.PRESENCE_ABSENT;
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
    integer(claim, name).ifPresent(setter::accept);
  }

  private static Optional<Integer> integer(JsonNode parent, String name) {
    var value = parent.path(name);
    if (value.isIntegralNumber() && value.canConvertToInt() && value.intValue() >= 0) {
      return Optional.of(value.intValue());
    }

    return Optional.empty();
  }
}
