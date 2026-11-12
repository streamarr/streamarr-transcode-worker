package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.OptionalLong;
import lombok.NonNull;

/** What a container index says about the keyframes of the chosen video stream. */
sealed interface KeyframeIndex {

  /**
   * The keyframes the index lists, in decode-time order. Times count ticks of the time base on the
   * demuxer's timeline, before the container start time is subtracted.
   */
  record Listed(@NonNull TimeBase timeBase, @NonNull List<KeyframeTime> keyframes)
      implements KeyframeIndex {

    public Listed {
      keyframes = List.copyOf(keyframes);
    }
  }

  /** The index cannot give every keyframe, so only a packet scan can. */
  record Unusable(@NonNull KeyframePacketScanReason reason) implements KeyframeIndex {}

  record KeyframeTime(long presentationTime, long decodeTime) {

    /**
     * Whether both of this keyframe's times are greater than the earlier keyframe's. A keyframe
     * that is not after the one before it would start a segment of no length or a negative one.
     */
    boolean isAfter(KeyframeTime earlier) {
      return presentationTime > earlier.presentationTime() && decodeTime > earlier.decodeTime();
    }
  }

  record TimeBase(long numerator, long denominator) {

    /**
     * The time in the other time base when it is a whole tick of it. The products can exceed a long
     * even when the result fits, as at a time base of 1/1,000,000,000.
     */
    OptionalLong rescaleExactly(long ticks, TimeBase to) {
      var quotient = scaledTo(ticks, to).divideAndRemainder(divisorTo(to));
      if (quotient[1].signum() != 0) {
        return OptionalLong.empty();
      }

      return OptionalLong.of(quotient[0].longValueExact());
    }

    /**
     * The first tick of the other time base at or after the time; an {@link ArithmeticException}
     * when that tick is beyond the long range.
     */
    long rescaleUp(long ticks, TimeBase to) {
      return new BigDecimal(scaledTo(ticks, to))
          .divide(new BigDecimal(divisorTo(to)), 0, RoundingMode.CEILING)
          .longValueExact();
    }

    private BigInteger scaledTo(long ticks, TimeBase to) {
      return BigInteger.valueOf(ticks)
          .multiply(BigInteger.valueOf(numerator))
          .multiply(BigInteger.valueOf(to.denominator()));
    }

    private BigInteger divisorTo(TimeBase to) {
      return BigInteger.valueOf(denominator).multiply(BigInteger.valueOf(to.numerator()));
    }
  }
}
