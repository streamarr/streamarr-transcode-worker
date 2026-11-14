package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.FieldOrder;
import build.buf.gen.streamarr.transcode.v1.Fraction;
import build.buf.gen.streamarr.transcode.v1.OutputFrameRateBasis;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Optional;
import java.util.Set;

/**
 * The highest rate sustained by 24 consecutive picture intervals anywhere in the chosen video
 * stream, which probe version 9 reports. The probe adds no read for it: a constant-rate stream
 * reports its base rate, and a variable-rate stream reports what the presentation times of every
 * frame show when a pass that the probe runs anyway lists them. Otherwise, and for an interlaced
 * stream, the maximum stays unknown.
 */
final class MaximumFrameRate {

  /** The number of consecutive picture intervals that must each support a measured rate. */
  static final int WINDOW_INTERVALS = 24;

  private static final BigInteger LARGEST_TERM = BigInteger.valueOf(Integer.MAX_VALUE);

  private static final Set<FieldOrder> INTERLACED =
      Set.of(
          FieldOrder.FIELD_ORDER_TT,
          FieldOrder.FIELD_ORDER_BB,
          FieldOrder.FIELD_ORDER_TB,
          FieldOrder.FIELD_ORDER_BT);

  private MaximumFrameRate() {}

  /**
   * The result with the chosen stream's base rate as its maximum, when the frame-rate rule found
   * the declared rates agreeing on a listed rate, which makes the stream constant-rate.
   */
  static ProbeAttemptResult withDeclaredRate(ProbeAttemptResult result) {
    var rule = result.getMedia().getFrameRateRule();
    if (rule.getBasis() != OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_DECLARED_RATES
        || !rule.getInputs().hasBaseRate()) {
      return result;
    }

    var base = rule.getInputs().getBaseRate();
    return withMaximum(result, ExactFraction.of(base.getNumerator(), base.getDenominator()));
  }

  /**
   * The result with the measured maximum, unless the chosen stream's declared base rate is at least
   * the rate that the presentation times prove.
   */
  static ProbeAttemptResult withMeasuredRate(
      ProbeAttemptResult result, Optional<MeasuredFrameRate> measured) {
    if (measured.isEmpty() || isAtLeastDeclared(result.getMedia(), measured.get().proven())) {
      return result;
    }

    return withMaximum(result, measured.get().reported());
  }

  private static boolean isAtLeastDeclared(ProbeMediaInfo media, ExactFraction proven) {
    var stream = chosenStream(media);
    if (!stream.hasMaximumFrameRate()) {
      return false;
    }

    var declared = stream.getMaximumFrameRate();
    return !proven.isGreaterThan(
        ExactFraction.of(declared.getNumerator(), declared.getDenominator()));
  }

  /**
   * The highest rate supported by every interval in a run of 24 consecutive picture intervals. Each
   * timestamp has one tick of precision. Fewer than 25 pictures supply no measured rate.
   */
  static Optional<MeasuredFrameRate> measured(long[] presentationTimes, long timescale) {
    var sorted = presentationTimes.clone();
    Arrays.sort(sorted);
    return fastestSustainedRun(sorted)
        .map(
            interval ->
                new MeasuredFrameRate(interval.rate(timescale), interval.lowestRate(timescale)));
  }

  /**
   * The rate that the measurement reports, and the lowest rate that the presentation times prove,
   * which a declared base rate must reach to stand.
   */
  record MeasuredFrameRate(ExactFraction reported, ExactFraction proven) {}

  private static Optional<Interval> fastestSustainedRun(long[] sorted) {
    var shortestMaximum = Long.MAX_VALUE;
    for (var first = 0; first + WINDOW_INTERVALS < sorted.length; first++) {
      var longest = longestInterval(sorted, first);
      if (longest > 0) {
        shortestMaximum = Math.min(shortestMaximum, longest);
      }
    }

    if (shortestMaximum == Long.MAX_VALUE) {
      return Optional.empty();
    }

    return Optional.of(new Interval(shortestMaximum));
  }

  private static long longestInterval(long[] sorted, int first) {
    var longest = 0L;
    for (var index = first; index < first + WINDOW_INTERVALS; index++) {
      longest = Math.max(longest, sorted[index + 1] - sorted[index]);
    }

    return longest;
  }

  private record Interval(long ticks) {

    ExactFraction rate(long timescale) {
      var estimate = new ExactFraction(BigInteger.valueOf(timescale), BigInteger.valueOf(ticks));
      return ListedFrameRates.RATES.stream()
          .filter(listed -> isWithinPrecision(listed, timescale))
          .min(Comparator.comparing(listed -> listed.minus(estimate).abs()))
          .orElse(estimate);
    }

    private boolean isWithinPrecision(ExactFraction rate, long timescale) {
      if (lowestRate(timescale).isGreaterThan(rate)) {
        return false;
      }

      return ticks == 1 || !rate.isGreaterThan(highestRate(timescale));
    }

    private ExactFraction lowestRate(long timescale) {
      return new ExactFraction(
          BigInteger.valueOf(timescale), BigInteger.valueOf(ticks).add(BigInteger.ONE));
    }

    private ExactFraction highestRate(long timescale) {
      return new ExactFraction(BigInteger.valueOf(timescale), BigInteger.valueOf(ticks - 1));
    }
  }

  private static ProbeAttemptResult withMaximum(ProbeAttemptResult result, ExactFraction rate) {
    var media = result.getMedia();
    if (isInterlaced(media)) {
      return result;
    }

    var fraction = contractFraction(rate);
    if (fraction.isEmpty()) {
      return result;
    }

    var updated = media.toBuilder();
    for (var stream : updated.getStreamsBuilderList()) {
      if (stream.getIndex() == media.getChosenVideoStreamIndex()) {
        stream.setMaximumFrameRate(fraction.get());
      }
    }

    return result.toBuilder().setMedia(updated).build();
  }

  private static boolean isInterlaced(ProbeMediaInfo media) {
    return INTERLACED.contains(media.getFrameRateRule().getInputs().getFieldOrder());
  }

  // The server reads each term as a signed 32-bit integer.
  private static Optional<Fraction> contractFraction(ExactFraction rate) {
    if (rate.numerator().signum() <= 0
        || rate.numerator().compareTo(LARGEST_TERM) > 0
        || rate.denominator().compareTo(LARGEST_TERM) > 0) {
      return Optional.empty();
    }

    return rate.toProto();
  }

  private static ProbeStreamInfo chosenStream(ProbeMediaInfo media) {
    return media.getStreamsList().stream()
        .filter(stream -> stream.getIndex() == media.getChosenVideoStreamIndex())
        .findFirst()
        .orElse(ProbeStreamInfo.getDefaultInstance());
  }
}
