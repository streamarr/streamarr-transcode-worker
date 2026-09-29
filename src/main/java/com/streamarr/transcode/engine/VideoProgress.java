package com.streamarr.transcode.engine;

/**
 * Tracks how far a job attempt's video has arrived, by the decode end of its latest video sample.
 * FFmpeg writes no sign that a stream has ended before the whole input ends, so once the audio ends
 * before the video, only the video shows how far the audio has arrived: the muxer interleaves each
 * audio sample with the video around it, unless the input's audio trails its video by more than the
 * muxer's interleave bound or an audio encoder flushes its last frames once the input ends.
 */
final class VideoProgress {

  // In the recorded outputs an audio sample trails the video written before it by at most 0.162 s,
  // apart from an audio encoder's last frames, which FFmpeg flushes once the whole input has ended.
  private static final long MARGIN_SECONDS = 1;

  private final int periodSeconds;
  // Before any video sample, no segment's end has passed.
  private long endTicks = Long.MIN_VALUE;
  private long timescale = 1;

  VideoProgress(int periodSeconds) {
    this.periodSeconds = periodSeconds;
  }

  /** Advances to the decode end of the fragment's last video sample, if it carries one. */
  void add(Fragment fragment) {
    if (fragment.videoSamples().isEmpty()) {
      return;
    }

    var last = fragment.videoSamples().getLast();
    endTicks = last.decodeTime() + last.duration();
    timescale = fragment.tracks().video().orElseThrow().timescale();
  }

  /**
   * Whether the video has run a margin past the end of segment {@code number}, after which the
   * segment closes without waiting for an audio sample of a later one.
   */
  boolean hasPassed(long number) {
    return endTicks >= ((number + 1) * periodSeconds + MARGIN_SECONDS) * timescale;
  }
}
