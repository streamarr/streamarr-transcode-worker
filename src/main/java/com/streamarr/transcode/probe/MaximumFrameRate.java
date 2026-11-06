package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.FieldOrder;
import build.buf.gen.streamarr.transcode.v1.Fraction;
import build.buf.gen.streamarr.transcode.v1.OutputFrameRateBasis;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * The highest rate at which the chosen video stream presents pictures anywhere in the file, which
 * probe version 8 reports. The probe adds no read for it: a constant-rate stream reports its base
 * rate, and a variable-rate stream reports the rate that the presentation times of every frame give
 * when a pass that the probe runs anyway lists them. Otherwise, and for an interlaced stream, the
 * maximum stays unknown.
 */
final class MaximumFrameRate {

  /**
   * The measured maximum is the fastest average rate over this many consecutive frame intervals,
   * about a second of film, so that the timestamp jitter of a single frame does not raise it.
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
   * The fastest average rate over {@link #WINDOW_INTERVALS} consecutive intervals between the
   * presentation times, in ticks of 1 / {@code timescale} seconds, or over every interval when the
   * stream has fewer. Empty for fewer than two frames or two frames that present at once.
   */
  static Optional<ExactFraction> measured(long[] presentationTimes, long timescale) {
    if (presentationTimes.length < 2) {
      return Optional.empty();
    }

    var sorted = presentationTimes.clone();
    Arrays.sort(sorted);
    var window = Math.min(WINDOW_INTERVALS, sorted.length - 1);
    var shortestSpan = Long.MAX_VALUE;
    for (var first = 0; first + window < sorted.length; first++) {
      shortestSpan = Math.min(shortestSpan, sorted[first + window] - sorted[first]);
    }

    if (shortestSpan <= 0) {
      return Optional.empty();
    }

    return Optional.of(ExactFraction.of(Math.multiplyExact(window, timescale), shortestSpan));
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
