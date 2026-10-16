package com.streamarr.transcode.probe;

import com.google.protobuf.Duration;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The timeline of the keyframe positions: the demuxer time minus the container's start time, as
 * FFmpeg's -copyts -start_at_zero produces. A time on it rounds to the nearest nanosecond, and a
 * half rounds away from zero.
 */
final class ProbeTimeline {

  private static final BigInteger NANOS_PER_SECOND = BigInteger.valueOf(1_000_000_000);

  private final BigDecimal containerStart;

  private ProbeTimeline(BigDecimal containerStart) {
    this.containerStart = containerStart;
  }

  /** The timeline of the container that ffprobe describes. No start time counts as 0. */
  static ProbeTimeline of(JsonNode format) {
    return new ProbeTimeline(
        FfprobeJson.number(format, "start_time", BigDecimal::new).orElse(BigDecimal.ZERO));
  }

  /**
   * The time base that ffprobe prints as a fraction, such as 1/90000, when both terms are positive.
   */
  static Optional<TimeBase> timeBase(JsonNode parent, String name) {
    var parts = FfprobeJson.text(parent, name).orElse("").split("/");
    if (parts.length != 2) {
      return Optional.empty();
    }

    try {
      var timeBase = new TimeBase(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
      return timeBase.numerator() > 0 && timeBase.denominator() > 0
          ? Optional.of(timeBase)
          : Optional.empty();
    } catch (NumberFormatException _) {
      return Optional.empty();
    }
  }

  /** A field that ffprobe prints as a whole number of ticks, such as start_pts or duration_ts. */
  static Optional<BigInteger> ticks(JsonNode parent, String name) {
    var value = parent.path(name);
    return value.isIntegralNumber() ? Optional.of(value.bigIntegerValue()) : Optional.empty();
  }

  /** The demuxer time of {@code ticks} of the time base on this timeline. */
  Duration at(BigInteger ticks, TimeBase timeBase) {
    return at(ticks, timeBase, BigDecimal.ZERO);
  }

  /** The demuxer time of {@code ticks} of the time base, plus {@code seconds}, on this timeline. */
  Duration at(BigInteger ticks, TimeBase timeBase, BigDecimal seconds) {
    var denominator = BigDecimal.valueOf(timeBase.denominator());
    var nanosTimesDenominator =
        new BigDecimal(ticks.multiply(BigInteger.valueOf(timeBase.numerator())))
            .add(seconds.subtract(containerStart).multiply(denominator))
            .multiply(new BigDecimal(NANOS_PER_SECOND));
    var nanos =
        nanosTimesDenominator.divide(denominator, 0, RoundingMode.HALF_UP).toBigIntegerExact();
    var parts = nanos.divideAndRemainder(NANOS_PER_SECOND);
    return Duration.newBuilder()
        .setSeconds(parts[0].longValueExact())
        .setNanos(parts[1].intValueExact())
        .build();
  }
}
