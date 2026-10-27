package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.ChromaticityCoordinates;
import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.MasteringDisplayColorVolume;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The HDR10 static metadata that the container, the first frame or an SEI message declares, in the
 * units of the HEVC SEI messages: the mastering display color volume (payload type 137) and the
 * content light level (payload type 144). A value that does not fit its SEI field leaves the whole
 * message absent.
 */
final class HdrStaticMetadata {

  private static final Pattern FRACTION = Pattern.compile("(-?\\d+)/(-?\\d+)");

  /** A chromaticity coordinate counts increments of 0.00002. */
  private static final long COORDINATE_UNITS = 50_000;

  /** A luminance counts increments of 0.0001 cd/m^2. */
  private static final long LUMINANCE_UNITS = 10_000;

  private static final long MAXIMUM_LUMINANCE = 0xFFFF_FFFFL;
  private static final long MAXIMUM_LIGHT_LEVEL = 0xFFFF;

  private static final int MASTERING_DISPLAY_PAYLOAD_BYTES = 24;
  private static final int LIGHT_LEVEL_PAYLOAD_BYTES = 4;

  private static final String MASTERING_DISPLAY = "Mastering display metadata";
  private static final String CONTENT_LIGHT_LEVEL = "Content light level metadata";

  private HdrStaticMetadata() {}

  /**
   * Sets the mastering display and the content light level from the stream's side data, or from the
   * side data of its first frame when the stream has none, or for H.264 and HEVC from the first
   * {@link SeiMessages} message of the type that is not nested. A Matroska track's own light level
   * elements come before the first frame's. A value that the worker cannot convert leaves its value
   * absent and sets its omitted flag. So does a Matroska MasteringMetadata element that ffprobe
   * dropped, and an SEI walk that cannot rule the value out, when no source gives the value.
   */
  static void add(JsonNode stream, Optional<SeiMessages> sei, ProbeStreamInfo.Builder builder) {
    declared(stream, MASTERING_DISPLAY)
        .map(HdrStaticMetadata::masteringDisplayOf)
        .or(
            () ->
                sei.flatMap(walked -> walked.firstPayload(SeiMessages.MASTERING_DISPLAY))
                    .map(HdrStaticMetadata::masteringDisplayOf))
        .ifPresentOrElse(
            value ->
                value.ifPresentOrElse(
                    builder::setMasteringDisplay, () -> builder.setMasteringDisplayOmitted(true)),
            () ->
                builder.setMasteringDisplayOmitted(
                    stream.path(MatroskaColourProbe.CONTAINER_MASTERING_METADATA).asBoolean()
                        || mayCarry(sei, SeiMessages.MASTERING_DISPLAY)));
    VideoStreamFacts.sideData(stream, CONTENT_LIGHT_LEVEL)
        .or(() -> Optional.ofNullable(stream.get(MatroskaColourProbe.CONTAINER_LIGHT_LEVEL)))
        .or(() -> firstFrameSideData(stream, CONTENT_LIGHT_LEVEL))
        .map(HdrStaticMetadata::contentLightLevelOf)
        .or(
            () ->
                sei.flatMap(walked -> walked.firstPayload(SeiMessages.CONTENT_LIGHT_LEVEL))
                    .map(HdrStaticMetadata::contentLightLevelOf))
        .ifPresentOrElse(
            value ->
                value.ifPresentOrElse(
                    builder::setContentLightLevel, () -> builder.setContentLightLevelOmitted(true)),
            () ->
                builder.setContentLightLevelOmitted(
                    mayCarry(sei, SeiMessages.CONTENT_LIGHT_LEVEL)));
  }

  /** Whether the stream may carry the value in SEI that the probe could not read. */
  private static boolean mayCarry(Optional<SeiMessages> sei, int payloadType) {
    return sei.filter(walked -> !walked.rulesOut(payloadType)).isPresent();
  }

  private static Optional<JsonNode> declared(JsonNode stream, String type) {
    return VideoStreamFacts.sideData(stream, type).or(() -> firstFrameSideData(stream, type));
  }

  private static Optional<JsonNode> firstFrameSideData(JsonNode stream, String type) {
    return FirstFrames.of(stream).flatMap(frames -> frames.firstFrameSideData(type));
  }

  /** MaxCLL, then MaxFALL, each in 2 bytes; empty when the payload ends first. */
  private static Optional<ContentLightLevel> contentLightLevelOf(ByteBuffer payload) {
    if (payload.remaining() < LIGHT_LEVEL_PAYLOAD_BYTES) {
      return Optional.empty();
    }

    return Optional.of(
        ContentLightLevel.newBuilder()
            .setMaxContentLightLevel(unsignedShort(payload, 0))
            .setMaxFrameAverageLightLevel(unsignedShort(payload, 2))
            .build());
  }

  /**
   * The green, blue and red primaries and the white point, each an x and a y in 2 bytes, then the
   * maximum and the minimum luminance, each in 4 bytes. Empty when the payload ends first or a
   * chromaticity lies beyond 50000.
   */
  private static Optional<MasteringDisplayColorVolume> masteringDisplayOf(ByteBuffer payload) {
    if (payload.remaining() < MASTERING_DISPLAY_PAYLOAD_BYTES) {
      return Optional.empty();
    }

    var green = coordinates(payload, 0);
    var blue = coordinates(payload, 4);
    var red = coordinates(payload, 8);
    var whitePoint = coordinates(payload, 12);
    if (green.isEmpty() || blue.isEmpty() || red.isEmpty() || whitePoint.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        MasteringDisplayColorVolume.newBuilder()
            .setRed(red.get())
            .setGreen(green.get())
            .setBlue(blue.get())
            .setWhitePoint(whitePoint.get())
            .setMaxLuminance(payload.getInt(payload.position() + 16))
            .setMinLuminance(payload.getInt(payload.position() + 20))
            .build());
  }

  private static Optional<ChromaticityCoordinates> coordinates(ByteBuffer payload, int offset) {
    var x = unsignedShort(payload, offset);
    var y = unsignedShort(payload, offset + 2);
    if (x > COORDINATE_UNITS || y > COORDINATE_UNITS) {
      return Optional.empty();
    }

    return Optional.of(ChromaticityCoordinates.newBuilder().setX(x).setY(y).build());
  }

  private static int unsignedShort(ByteBuffer payload, int offset) {
    return Short.toUnsignedInt(payload.getShort(payload.position() + offset));
  }

  private static Optional<ContentLightLevel> contentLightLevelOf(JsonNode side) {
    var maxContent = lightLevel(side.path("max_content"));
    var maxAverage = lightLevel(side.path("max_average"));
    if (maxContent.isEmpty() || maxAverage.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        ContentLightLevel.newBuilder()
            .setMaxContentLightLevel(maxContent.get())
            .setMaxFrameAverageLightLevel(maxAverage.get())
            .build());
  }

  private static Optional<MasteringDisplayColorVolume> masteringDisplayOf(JsonNode side) {
    var red = coordinates(side, "red");
    var green = coordinates(side, "green");
    var blue = coordinates(side, "blue");
    var whitePoint = coordinates(side, "white_point");
    var maxLuminance = units(side, "max_luminance", LUMINANCE_UNITS, MAXIMUM_LUMINANCE);
    var minLuminance = units(side, "min_luminance", LUMINANCE_UNITS, MAXIMUM_LUMINANCE);
    if (red.isEmpty()
        || green.isEmpty()
        || blue.isEmpty()
        || whitePoint.isEmpty()
        || maxLuminance.isEmpty()
        || minLuminance.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        MasteringDisplayColorVolume.newBuilder()
            .setRed(red.get())
            .setGreen(green.get())
            .setBlue(blue.get())
            .setWhitePoint(whitePoint.get())
            .setMaxLuminance((int) maxLuminance.getAsLong())
            .setMinLuminance((int) minLuminance.getAsLong())
            .build());
  }

  private static Optional<ChromaticityCoordinates> coordinates(JsonNode side, String name) {
    var x = units(side, name + "_x", COORDINATE_UNITS, COORDINATE_UNITS);
    var y = units(side, name + "_y", COORDINATE_UNITS, COORDINATE_UNITS);
    if (x.isEmpty() || y.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(
        ChromaticityCoordinates.newBuilder()
            .setX((int) x.getAsLong())
            .setY((int) y.getAsLong())
            .build());
  }

  /**
   * The fraction that ffprobe prints, times the units in one whole, rounded to the nearest unit
   * with a half rounding up. Empty when the field is missing, is no fraction, or does not fit from
   * 0 to {@code maximum}.
   */
  private static OptionalLong units(JsonNode side, String name, long unitsPerWhole, long maximum) {
    var matcher = FRACTION.matcher(side.path(name).asString(""));
    if (!matcher.matches()) {
      return OptionalLong.empty();
    }

    var denominator = new BigInteger(matcher.group(2));
    if (denominator.signum() <= 0) {
      return OptionalLong.empty();
    }

    var units =
        new BigDecimal(new BigInteger(matcher.group(1)).multiply(BigInteger.valueOf(unitsPerWhole)))
            .divide(new BigDecimal(denominator), 0, RoundingMode.HALF_UP);
    if (units.signum() < 0 || units.compareTo(BigDecimal.valueOf(maximum)) > 0) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(units.longValueExact());
  }

  private static Optional<Integer> lightLevel(JsonNode value) {
    if (!value.isIntegralNumber() || !value.canConvertToLong()) {
      return Optional.empty();
    }

    var level = value.longValue();
    if (level < 0 || level > MAXIMUM_LIGHT_LEVEL) {
      return Optional.empty();
    }

    return Optional.of((int) level);
  }
}
