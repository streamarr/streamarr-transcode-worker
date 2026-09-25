package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The frame rate an attempt that encodes video forces on its output, which bounds how short a video
 * sample of that output can be.
 */
record EncodedFrameRate(double framesPerSecond) {

  /** The rate an attempt forces on its output; empty when the attempt copies the video. */
  static Optional<EncodedFrameRate> of(OptionalDouble framesPerSecond) {
    if (framesPerSecond.isEmpty()) {
      return Optional.empty();
    }

    return Optional.of(new EncodedFrameRate(framesPerSecond.getAsDouble()));
  }

  /**
   * Returns the fragment unless it holds a video sample shorter than half a frame. An encoder that
   * emits packets out of decode order, as SVT-AV1 4.x can (upstream issue 2385), leaves FFmpeg to
   * rewrite their timestamps into samples of a tick or so; the output then fails to decode or skips
   * segment numbers, even when FFmpeg exits cleanly.
   *
   * @throws FragmentedMp4Exception with {@link Reason#SHORT_VIDEO_SAMPLE} otherwise
   */
  Fragment requireNoShortVideoSample(Fragment fragment) {
    var timescale = fragment.videoStart().map(VideoStart::timescale);
    var shortest = fragment.shortestVideoSampleDuration();
    if (timescale.isEmpty() || shortest.isEmpty()) {
      return fragment;
    }

    var frameTicks = timescale.orElseThrow() / framesPerSecond;
    if (shortest.getAsLong() >= frameTicks / 2) {
      return fragment;
    }

    throw new FragmentedMp4Exception(
        Reason.SHORT_VIDEO_SAMPLE,
        "a video sample of "
            + shortest.getAsLong()
            + " ticks lasts less than half a frame of "
            + frameTicks
            + " ticks at "
            + framesPerSecond
            + " frames per second");
  }
}
