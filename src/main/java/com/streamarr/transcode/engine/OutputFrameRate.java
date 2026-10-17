package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.math.BigInteger;

/** The exact fraction an encode forces on its output and uses to count its GOP. */
public record OutputFrameRate(long numerator, long denominator) {

  String ffmpegArgument() {
    return numerator + "/" + denominator;
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
