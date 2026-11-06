package com.streamarr.transcode.engine;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * A time on the attempt's zero-based timeline, held exactly as a whole number of a time base's
 * ticks. Two media times are equal when they name the same instant, whatever their time bases.
 */
record MediaTime(long ticks, TimeBase timeBase) implements Comparable<MediaTime> {

  private static final BigInteger MICROSECONDS_PER_SECOND = BigInteger.valueOf(1_000_000);
  private static final BigInteger NANOSECONDS_PER_SECOND = BigInteger.valueOf(1_000_000_000);
  private static final TimeBase NANOSECONDS = new TimeBase(1, 1_000_000_000);

  /** The time in ticks of a nanosecond; throws ArithmeticException when it does not fit. */
  static MediaTime of(Duration time) {
    var nanoseconds =
        Math.addExact(
            Math.multiplyExact(time.getSeconds(), NANOSECONDS_PER_SECOND.longValue()),
            time.getNano());
    return new MediaTime(nanoseconds, NANOSECONDS);
  }

  /** This time in whole microseconds; empty when it falls between two microseconds. */
  OptionalLong wholeMicroseconds() {
    var parts = inUnits(MICROSECONDS_PER_SECOND).divideAndRemainder(denominator());
    if (parts[1].signum() != 0) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(parts[0].longValueExact());
  }

  /**
   * This time truncated to whole nanoseconds, toward zero; throws ArithmeticException when it does
   * not fit.
   */
  Duration truncatedToNanoseconds() {
    var nanoseconds = inUnits(NANOSECONDS_PER_SECOND).divide(denominator());
    return Duration.ofNanos(nanoseconds.longValueExact());
  }

  /**
   * This non-negative time split into its whole seconds and what lies past them, in ticks of the
   * time base's denominator.
   */
  WholeSeconds wholeSeconds() {
    var parts = seconds().divideAndRemainder(denominator());
    return new WholeSeconds(
        parts[0].longValueExact(), parts[1].longValueExact(), timeBase.denominator());
  }

  /** Whole seconds plus {@code remainder / denominator} of a second. */
  record WholeSeconds(long seconds, long remainder, long denominator) {}

  /** This time in ticks of the other time base, rounded down. */
  long floorIn(TimeBase other) {
    var parts = dividedBy(other);
    if (parts[1].signum() < 0) {
      return parts[0].subtract(BigInteger.ONE).longValueExact();
    }

    return parts[0].longValueExact();
  }

  /** This time in ticks of the other time base, rounded up. */
  long ceilIn(TimeBase other) {
    var parts = dividedBy(other);
    if (parts[1].signum() > 0) {
      return parts[0].add(BigInteger.ONE).longValueExact();
    }

    return parts[0].longValueExact();
  }

  // The quotient, truncated toward zero, and the remainder of this time over the other's tick.
  private BigInteger[] dividedBy(TimeBase other) {
    return seconds()
        .multiply(BigInteger.valueOf(other.denominator()))
        .divideAndRemainder(denominator().multiply(BigInteger.valueOf(other.numerator())));
  }

  @Override
  public int compareTo(MediaTime other) {
    return seconds()
        .multiply(other.denominator())
        .compareTo(other.seconds().multiply(denominator()));
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof MediaTime time && compareTo(time) == 0;
  }

  // Hashes the time's lowest terms, which every time base that names the same instant shares; the
  // denominator is positive, so the divisor is never zero.
  @Override
  public int hashCode() {
    var divisor = seconds().gcd(denominator());
    return Objects.hash(seconds().divide(divisor), denominator().divide(divisor));
  }

  // The time times the time base's denominator.
  private BigInteger seconds() {
    return inUnits(BigInteger.ONE);
  }

  // The time in these units, times the time base's denominator.
  private BigInteger inUnits(BigInteger unitsPerSecond) {
    return BigInteger.valueOf(ticks)
        .multiply(BigInteger.valueOf(timeBase.numerator()))
        .multiply(unitsPerSecond);
  }

  private BigInteger denominator() {
    return BigInteger.valueOf(timeBase.denominator());
  }
}
