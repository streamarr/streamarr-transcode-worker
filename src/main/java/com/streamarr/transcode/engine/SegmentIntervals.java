package com.streamarr.transcode.engine;

/**
 * The media segments' intervals on ADR 0037's plain zero-based grid: segment N's interval runs in
 * media time from N periods up to N + 1 periods.
 */
final class SegmentIntervals {

  private final int periodSeconds;

  private SegmentIntervals(int periodSeconds) {
    this.periodSeconds = periodSeconds;
  }

  /** The intervals of a plain grid whose every segment lasts this many seconds. */
  static SegmentIntervals onPlainGrid(int periodSeconds) {
    if (periodSeconds <= 0) {
      throw new IllegalArgumentException("Period must be positive, got: " + periodSeconds);
    }

    return new SegmentIntervals(periodSeconds);
  }

  /**
   * The number of the segment whose interval holds this presentation time, negative before the
   * first.
   */
  long numberAt(long presentationTime, long timescale) {
    return Math.floorDiv(presentationTime, periodSeconds * timescale);
  }

  /** Where segment {@code number}'s interval ends, in ticks of this timescale. */
  long endOf(long number, long timescale) {
    return (number + 1) * periodSeconds * timescale;
  }
}
