package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import build.buf.gen.streamarr.transcode.v1.FieldOrder;
import build.buf.gen.streamarr.transcode.v1.FrameRateRuleInputs;
import build.buf.gen.streamarr.transcode.v1.MatroskaStatistics;
import com.google.protobuf.Duration;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The frame timing that the chosen video stream declares: ffprobe's r_frame_rate as the base rate,
 * avg_frame_rate as the average rate, the field order, the time base and Matroska's statistics.
 */
record FrameTiming(
    Optional<ExactFraction> baseRate,
    Optional<ExactFraction> averageRate,
    FieldOrder fieldOrder,
    Optional<ExactFraction> timeBase,
    Optional<Long> numberOfFrames,
    Optional<BigDecimal> matroskaSeconds) {

  private static final ExactFraction FASTEST_USABLE_RATE = ExactFraction.of(1000);

  static FrameTiming of(JsonNode stream) {
    return new FrameTiming(
        rate(stream, "r_frame_rate"),
        rate(stream, "avg_frame_rate"),
        fieldOrder(text(stream, "field_order").orElse("")),
        rate(stream, "time_base"),
        StreamTags.matroskaStatistic(stream, "NUMBER_OF_FRAMES").flatMap(FrameTiming::count),
        StreamTags.matroskaDuration(stream));
  }

  /** A rate that is present, above 0 and below 1000. */
  static boolean isUsable(ExactFraction rate) {
    return rate.signum() > 0 && rate.isLessThan(FASTEST_USABLE_RATE);
  }

  Optional<ExactFraction> usableBaseRate() {
    return baseRate.filter(FrameTiming::isUsable);
  }

  Optional<ExactFraction> usableAverageRate() {
    return averageRate.filter(FrameTiming::isUsable);
  }

  boolean isInterlaced() {
    return switch (fieldOrder) {
      case FIELD_ORDER_TT, FIELD_ORDER_BB, FIELD_ORDER_TB, FIELD_ORDER_BT -> true;
      default -> false;
    };
  }

  /** The rate of Matroska's statistics: NUMBER_OF_FRAMES over DURATION, when both are present. */
  Optional<ExactFraction> matroskaRate() {
    if (numberOfFrames.isEmpty() || matroskaSeconds.isEmpty()) {
      return Optional.empty();
    }

    var seconds = matroskaSeconds.get();
    if (seconds.signum() <= 0) {
      return Optional.empty();
    }

    var duration = new ExactFraction(seconds.unscaledValue(), BigInteger.TEN.pow(seconds.scale()));
    return Optional.of(ExactFraction.of(numberOfFrames.get()).dividedBy(duration));
  }

  /** Half a frame of the average rate, in ticks, on an interlaced stream with a usable average. */
  Optional<ExactFraction> halfFrameTicks(ExactFraction streamTimeBase) {
    if (!isInterlaced()) {
      return Optional.empty();
    }

    return usableAverageRate()
        .map(rate -> rate.times(ExactFraction.of(2)).times(streamTimeBase).reciprocal());
  }

  FrameRateRuleInputs.Builder inputs() {
    var inputs = FrameRateRuleInputs.newBuilder().setFieldOrder(fieldOrder);
    baseRate.flatMap(ExactFraction::toProto).ifPresent(inputs::setBaseRate);
    averageRate.flatMap(ExactFraction::toProto).ifPresent(inputs::setAverageRate);
    timeBase.flatMap(ExactFraction::toProto).ifPresent(inputs::setTimeBase);
    if (numberOfFrames.isPresent() || matroskaSeconds.isPresent()) {
      var statistics = MatroskaStatistics.newBuilder();
      numberOfFrames.ifPresent(statistics::setNumberOfFrames);
      matroskaSeconds.map(FrameTiming::duration).ifPresent(statistics::setDuration);
      inputs.setMatroskaStatistics(statistics);
    }

    return inputs;
  }

  static Duration duration(BigDecimal seconds) {
    var nanos = seconds.setScale(9, RoundingMode.HALF_UP);
    var whole = nanos.setScale(0, RoundingMode.FLOOR);
    return Duration.newBuilder()
        .setSeconds(whole.longValueExact())
        .setNanos(nanos.subtract(whole).movePointRight(9).intValueExact())
        .build();
  }

  private static Optional<ExactFraction> rate(JsonNode stream, String name) {
    return text(stream, name).flatMap(ExactFraction::parse).filter(rate -> rate.signum() > 0);
  }

  private static Optional<Long> count(String text) {
    try {
      return Optional.of(Long.parseLong(text.strip())).filter(count -> count >= 0);
    } catch (NumberFormatException _) {
      return Optional.empty();
    }
  }

  private static FieldOrder fieldOrder(String value) {
    return switch (value) {
      case "progressive" -> FieldOrder.FIELD_ORDER_PROGRESSIVE;
      case "tt" -> FieldOrder.FIELD_ORDER_TT;
      case "bb" -> FieldOrder.FIELD_ORDER_BB;
      case "tb" -> FieldOrder.FIELD_ORDER_TB;
      case "bt" -> FieldOrder.FIELD_ORDER_BT;
      default -> FieldOrder.FIELD_ORDER_UNSPECIFIED;
    };
  }
}
