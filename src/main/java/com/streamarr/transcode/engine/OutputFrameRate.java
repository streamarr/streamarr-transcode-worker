package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.math.BigInteger;
import java.time.Duration;

/** The exact fraction an encode forces on its output and uses to count its GOP. */
public record OutputFrameRate(long numerator, long denominator) {

  String ffmpegArgument() {
    return numerator + "/" + denominator;
  }

  /**
   * The number of frames on this rate's grid from zero whose start lies before the time, which is
   * also the presentation time, in frames, of the first frame at or after it.
   */
  long framesStartingBefore(Duration time) {
    var numerator =
        BigInteger.valueOf(time.getSeconds())
            .multiply(BigInteger.valueOf(1_000_000_000L))
            .add(BigInteger.valueOf(time.getNano()))
            .multiply(BigInteger.valueOf(this.numerator));
    var denominator =
        BigInteger.valueOf(this.denominator).multiply(BigInteger.valueOf(1_000_000_000L));
    var quotient = numerator.divideAndRemainder(denominator);
    var frames = quotient[0];
    if (quotient[1].signum() > 0) {
      frames = frames.add(BigInteger.ONE);
    }

    return frames.longValueExact();
  }

  /**
   * The presentation time, rounded down to whole microseconds, of the first frame on this rate's
   * grid from zero that presents at or after the start.
   */
  long firstFrameAtOrAfterInMicroseconds(MediaTime start) {
    var startNumerator =
        BigInteger.valueOf(start.ticks())
            .multiply(BigInteger.valueOf(start.timeBase().numerator()))
            .multiply(BigInteger.valueOf(numerator));
    var startDenominator =
        BigInteger.valueOf(start.timeBase().denominator())
            .multiply(BigInteger.valueOf(denominator));
    var frame = ceilDivide(startNumerator, startDenominator);
    return frame
        .multiply(BigInteger.valueOf(denominator))
        .multiply(BigInteger.valueOf(1_000_000L))
        .divide(BigInteger.valueOf(numerator))
        .longValueExact();
  }

  private static BigInteger ceilDivide(BigInteger dividend, BigInteger divisor) {
    var quotient = dividend.divideAndRemainder(divisor);
    if (quotient[1].signum() > 0) {
      return quotient[0].add(BigInteger.ONE);
    }

    return quotient[0];
  }

  int wholeFramesIn(int seconds) {
    return frameNumerator(seconds).divide(BigInteger.valueOf(denominator)).intValueExact();
  }

  int framesCovering(int seconds) {
    return frameNumerator(seconds)
        .add(BigInteger.valueOf(denominator - 1))
        .divide(BigInteger.valueOf(denominator))
        .intValueExact();
  }

  private BigInteger frameNumerator(int seconds) {
    return BigInteger.valueOf(numerator).multiply(BigInteger.valueOf(seconds));
  }

  // SVT-AV1 can emit packets out of decode order; FFmpeg then rewrites their timestamps into
  // samples of a tick or so, which fail to decode even when FFmpeg exits cleanly (upstream #2385).
  Fragment requireNoShortVideoSample(Fragment fragment) {
    var timescale = fragment.videoStart().map(VideoStart::timescale);
    var shortest = fragment.shortestVideoSampleDuration();
    if (timescale.isEmpty() || shortest.isEmpty()) {
      return fragment;
    }

    var twiceSample =
        BigInteger.valueOf(shortest.getAsLong())
            .multiply(BigInteger.TWO)
            .multiply(BigInteger.valueOf(numerator));
    var frame =
        BigInteger.valueOf(timescale.orElseThrow()).multiply(BigInteger.valueOf(denominator));
    if (twiceSample.compareTo(frame) >= 0) {
      return fragment;
    }

    throw new FragmentedMp4Exception(
        Reason.SHORT_VIDEO_SAMPLE,
        "a video sample of "
            + shortest.getAsLong()
            + " ticks lasts less than half a frame at "
            + ffmpegArgument()
            + " frames per second and a timescale of "
            + timescale.orElseThrow());
  }
}
