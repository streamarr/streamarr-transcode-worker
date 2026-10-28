package com.streamarr.transcode.engine;

import java.math.BigInteger;
import java.util.OptionalLong;

/** A time base in which each tick lasts numerator / denominator seconds. */
record TimeBase(long numerator, long denominator) {

  TimeBase {
    if (numerator <= 0 || denominator <= 0) {
      throw new IllegalArgumentException(
          "a time base needs a positive numerator and denominator: "
              + numerator
              + "/"
              + denominator);
    }
  }

  /**
   * The value in ticks of the timescale, the ticks per second of an MP4 track; empty when it does
   * not convert exactly into a signed 64-bit count.
   */
  OptionalLong ticksIn(long timescale, long value) {
    var parts =
        BigInteger.valueOf(value)
            .multiply(BigInteger.valueOf(numerator))
            .multiply(BigInteger.valueOf(timescale))
            .divideAndRemainder(BigInteger.valueOf(denominator));
    if (parts[1].signum() != 0 || parts[0].bitLength() > Long.SIZE - 1) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(parts[0].longValue());
  }
}
