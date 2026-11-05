package com.streamarr.transcode.engine;

import java.math.BigInteger;
import java.time.Duration;
import java.util.OptionalLong;

/**
 * Where a media segment starts on the zero-based timeline: a whole number of a time base's ticks.
 */
record SegmentStart(long ticks, TimeBase timeBase) {

  private static final BigInteger MICROSECONDS_PER_SECOND = BigInteger.valueOf(1_000_000);
  private static final BigInteger NANOSECONDS_PER_SECOND = BigInteger.valueOf(1_000_000_000);

  /** This start in whole microseconds; empty when it falls between two microseconds. */
  OptionalLong wholeMicroseconds() {
    var parts = inUnits(MICROSECONDS_PER_SECOND).divideAndRemainder(denominator());
    if (parts[1].signum() != 0) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(parts[0].longValueExact());
  }

  /** This start rounded down to whole nanoseconds. */
  Duration roundedDownToNanoseconds() {
    var nanoseconds = inUnits(NANOSECONDS_PER_SECOND).divide(denominator());
    var parts = nanoseconds.divideAndRemainder(NANOSECONDS_PER_SECOND);
    return Duration.ofSeconds(parts[0].longValueExact(), parts[1].longValueExact());
  }

  // The start times the units per second, times the time base's denominator.
  private BigInteger inUnits(BigInteger unitsPerSecond) {
    return BigInteger.valueOf(ticks)
        .multiply(BigInteger.valueOf(timeBase.numerator()))
        .multiply(unitsPerSecond);
  }

  private BigInteger denominator() {
    return BigInteger.valueOf(timeBase.denominator());
  }
}
