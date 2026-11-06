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
import java.util.stream.IntStream;

/**
 * The highest rate at which the chosen video stream presents pictures anywhere in the file, which
 * probe version 8 reports. The probe adds no read for it: a constant-rate stream reports its base
 * rate, and a variable-rate stream reports what the presentation times of every frame show when a
 * pass that the probe runs anyway lists them. Otherwise, and for an interlaced stream, the maximum
 * stays unknown.
 */
final class MaximumFrameRate {

  /**
   * The measurement looks at runs of 1 to this many consecutive frame intervals, so that a burst of
   * a few fast frames counts, while a longer run outweighs the timestamp rounding of each frame.
   */
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
   * The result with the measured maximum, unless the chosen stream already has one from its
   * declared rates.
   */
  static ProbeAttemptResult withMeasuredRate(
      ProbeAttemptResult result, Optional<ExactFraction> measured) {
    if (measured.isEmpty() || chosenStream(result.getMedia()).hasMaximumFrameRate()) {
      return result;
    }

    return withMaximum(result, measured.get());
  }

  /**
   * The highest rate that the presentation times, in ticks of 1 / {@code timescale} seconds, show
   * over 1 to {@link #WINDOW_INTERVALS} consecutive intervals. Each time can be up to one tick off,
   * so a run of k intervals that spans s ticks proves at least k frames per s + 1 ticks. The run
   * that proves the highest rate decides: when a listed rate lies between k / (s + 1) and k / (s -
   * 1) frames per tick, the listed rate nearest k / s is the maximum, else k / s is. Empty for
   * fewer than two frames or frames that all present at once.
   */
  static Optional<ExactFraction> measured(long[] presentationTimes, long timescale) {
    var sorted = presentationTimes.clone();
    Arrays.sort(sorted);
    return IntStream.rangeClosed(1, Math.min(WINDOW_INTERVALS, sorted.length - 1))
        .mapToObj(intervals -> shortestRun(sorted, intervals))
        .flatMap(Optional::stream)
        .max(Comparator.comparing(Run::provenRate))
        .map(run -> run.rate(timescale));
  }

  private static Optional<Run> shortestRun(long[] sorted, int intervals) {
    var shortestSpan = Long.MAX_VALUE;
    for (var first = 0; first + intervals < sorted.length; first++) {
      var span = sorted[first + intervals] - sorted[first];
      if (span > 0) {
        shortestSpan = Math.min(shortestSpan, span);
      }
    }

    if (shortestSpan == Long.MAX_VALUE) {
      return Optional.empty();
    }

    return Optional.of(new Run(intervals, shortestSpan));
  }

  /** {@code intervals} consecutive frame intervals that span {@code span} ticks. */
  private record Run(long intervals, long span) {

    /** The lowest rate, in frames per tick, that the run's times allow. */
    ExactFraction provenRate() {
      return lowestRate(1);
    }

    ExactFraction rate(long timescale) {
      var estimate = new ExactFraction(ticks(timescale), BigInteger.valueOf(span));
      return ListedFrameRates.RATES.stream()
          .filter(listed -> isWithinPrecision(listed, timescale))
          .min(Comparator.comparing(listed -> listed.minus(estimate).abs()))
          .orElse(estimate);
    }

    // A run that spans one tick allows every rate from k frames per 2 ticks up.
    private boolean isWithinPrecision(ExactFraction rate, long timescale) {
      if (lowestRate(timescale).isGreaterThan(rate)) {
        return false;
      }

      return span == 1 || !rate.isGreaterThan(highestRate(timescale));
    }

    private ExactFraction lowestRate(long timescale) {
      return new ExactFraction(ticks(timescale), BigInteger.valueOf(span).add(BigInteger.ONE));
    }

    private ExactFraction highestRate(long timescale) {
      return new ExactFraction(ticks(timescale), BigInteger.valueOf(span - 1));
    }

    private BigInteger ticks(long timescale) {
      return BigInteger.valueOf(intervals).multiply(BigInteger.valueOf(timescale));
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
