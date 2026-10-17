package com.streamarr.transcode.probe;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * The output frame rates that ADR 0037's frame-rate rule chooses from, each as an exact fraction,
 * and the rule's snap to them.
 */
final class ListedFrameRates {

  static final ExactFraction RATE_23_976 = ExactFraction.of(24_000, 1001);
  static final ExactFraction RATE_60 = ExactFraction.of(60);

  static final List<ExactFraction> RATES =
      List.of(
          ExactFraction.of(10),
          ExactFraction.of(12),
          ExactFraction.of(25, 2),
          ExactFraction.of(15),
          RATE_23_976,
          ExactFraction.of(24),
          ExactFraction.of(25),
          ExactFraction.of(30_000, 1001),
          ExactFraction.of(30),
          ExactFraction.of(48_000, 1001),
          ExactFraction.of(48),
          ExactFraction.of(50),
          ExactFraction.of(60_000, 1001),
          RATE_60);

  private static final ExactFraction LOWEST = ExactFraction.of(10);
  private static final ExactFraction HIGHEST_COUNTED_AS_60 = ExactFraction.of(603, 10);
  private static final ExactFraction DECIMATION_TOLERANCE = ExactFraction.of(5, 1000);
  // The decimation compares errors in steps of 0.05 %.
  private static final ExactFraction ERROR_STEPS_PER_UNIT = ExactFraction.of(2000);
  private static final BigInteger SMALLEST_DIVISOR = BigInteger.TWO;
  private static final BigInteger LARGEST_DIVISOR = BigInteger.valueOf(4);
  // A rate agrees with a listed rate within 0.01 %.
  private static final ExactFraction AGREEMENT = ExactFraction.of(1, 10_000);

  private ListedFrameRates() {}

  /** The listed rate within 0.01 % of the rate, if any. */
  static Optional<ExactFraction> agreeingWith(ExactFraction rate) {
    return RATES.stream().filter(listed -> agree(rate, listed)).findFirst();
  }

  /** Whether the rate lies within 0.01 % of the listed rate. */
  static boolean agree(ExactFraction rate, ExactFraction listed) {
    return !rate.dividedBy(listed).minus(ExactFraction.of(1)).abs().isGreaterThan(AGREEMENT);
  }

  /**
   * The listed rate for a measured or declared rate. A rate below 10 becomes 10. A rate from 60 to
   * 60.3 counts as 60. A faster rate divides evenly by 2, 3 or 4 into a listed rate from 23.976 to
   * 60, or becomes 60. Any other rate takes the nearest listed rate, and a tie takes the higher
   * one.
   */
  static ExactFraction snap(ExactFraction rate) {
    if (rate.isLessThan(LOWEST)) {
      return LOWEST;
    }

    if (rate.isGreaterThan(HIGHEST_COUNTED_AS_60)) {
      return decimate(rate);
    }

    if (rate.isGreaterThan(RATE_60)) {
      return RATE_60;
    }

    return RATES.stream()
        .min(
            Comparator.comparing((ExactFraction listed) -> rate.minus(listed).abs())
                .thenComparing(Comparator.reverseOrder()))
        .orElseThrow();
  }

  /**
   * Among the listed rates from 23.976 to 60 that the rate divides into within 0.5 % of a whole
   * number from 2 to 4, the one with the smallest error in 0.05 % steps, then the higher one; 60
   * when there is none.
   */
  private static ExactFraction decimate(ExactFraction rate) {
    record Candidate(ExactFraction listed, BigInteger errorSteps) {}

    return RATES.stream()
        .filter(listed -> !listed.isLessThan(RATE_23_976))
        .flatMap(
            listed -> {
              var quotient = rate.dividedBy(listed);
              var divisor = quotient.roundHalfEven();
              if (divisor.compareTo(SMALLEST_DIVISOR) < 0
                  || divisor.compareTo(LARGEST_DIVISOR) > 0) {
                return Stream.<Candidate>empty();
              }

              var whole = new ExactFraction(divisor, BigInteger.ONE);
              var error = quotient.minus(whole).abs().dividedBy(whole);
              if (error.isGreaterThan(DECIMATION_TOLERANCE)) {
                return Stream.<Candidate>empty();
              }

              return Stream.of(
                  new Candidate(listed, error.times(ERROR_STEPS_PER_UNIT).roundHalfEven()));
            })
        .min(
            Comparator.comparing(Candidate::errorSteps)
                .thenComparing(Candidate::listed, Comparator.reverseOrder()))
        .map(Candidate::listed)
        .orElse(RATE_60);
  }
}
