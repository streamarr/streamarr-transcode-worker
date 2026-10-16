package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.ChromaticityCoordinates;
import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.MasteringDisplayColorVolume;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The HDR10 static metadata that the container declares, in the units of the HEVC SEI messages: the
 * mastering display color volume (payload type 137) and the content light level (payload type 144).
 * A value that does not fit its SEI field leaves the whole message absent.
 */
final class HdrStaticMetadata {

  private static final Pattern FRACTION = Pattern.compile("(-?\\d+)/(-?\\d+)");

  /** A chromaticity coordinate counts increments of 0.00002. */
  private static final long COORDINATE_UNITS = 50_000;

  /** A luminance counts increments of 0.0001 cd/m^2. */
  private static final long LUMINANCE_UNITS = 10_000;

  private static final long MAXIMUM_LUMINANCE = 0xFFFF_FFFFL;
  private static final long MAXIMUM_LIGHT_LEVEL = 0xFFFF;

  private HdrStaticMetadata() {}

  static Optional<MasteringDisplayColorVolume> masteringDisplay(JsonNode stream) {
    return VideoStreamFacts.sideData(stream, "Mastering display metadata")
        .flatMap(HdrStaticMetadata::masteringDisplayOf);
  }

  static Optional<ContentLightLevel> contentLightLevel(JsonNode stream) {
    return VideoStreamFacts.sideData(stream, "Content light level metadata")
        .flatMap(
            side -> {
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
            });
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
