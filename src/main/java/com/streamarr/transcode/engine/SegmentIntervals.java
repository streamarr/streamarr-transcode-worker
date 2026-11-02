package com.streamarr.transcode.engine;

import java.util.Arrays;
import java.util.List;
import java.util.OptionalLong;
import lombok.NonNull;

/**
 * The media segments' intervals on ADR 0037's zero-based timeline of slots, each one period long.
 * On the plain grid segment N's interval is slot N. On a segment list, segment N's interval runs
 * from its listed start slot up to the next segment's, so one segment can span several slots, and
 * the last segment's interval runs to the end of the media.
 */
final class SegmentIntervals {

  private final int periodSeconds;
  // Each segment's start slot, strictly increasing from 0; empty on the plain grid.
  private final long[] startSlots;

  private SegmentIntervals(int periodSeconds, long[] startSlots) {
    this.periodSeconds = periodSeconds;
    this.startSlots = startSlots;
  }

  /** The intervals of a plain grid whose every segment lasts this many seconds. */
  static SegmentIntervals onPlainGrid(int periodSeconds) {
    requirePositive(periodSeconds);
    return new SegmentIntervals(periodSeconds, new long[0]);
  }

  /**
   * The intervals of a segment list whose slots last this many seconds: entry N is the slot where
   * segment N starts. An empty list is the plain grid. The job's mapping has checked that a
   * non-empty list starts at 0 and strictly increases.
   */
  static SegmentIntervals onSegmentList(int periodSeconds, @NonNull List<Long> startSlots) {
    requirePositive(periodSeconds);
    return new SegmentIntervals(
        periodSeconds, startSlots.stream().mapToLong(Long::longValue).toArray());
  }

  private static void requirePositive(int periodSeconds) {
    if (periodSeconds <= 0) {
      throw new IllegalArgumentException("Period must be positive, got: " + periodSeconds);
    }
  }

  /**
   * The number of the segment whose interval holds this presentation time, negative before the
   * first.
   */
  long numberAt(long presentationTime, long timescale) {
    var slot = Math.floorDiv(presentationTime, periodSeconds * timescale);
    if (slot < 0 || isPlainGrid()) {
      return slot;
    }

    // The segment whose start slot is the last at or before the slot.
    var found = Arrays.binarySearch(startSlots, slot);
    if (found >= 0) {
      return found;
    }

    return -found - 2;
  }

  /** Where segment {@code number}'s interval starts, in ticks of this timescale. */
  long startOf(long number, long timescale) {
    var slot = number;
    if (!isPlainGrid()) {
      slot = startSlots[Math.toIntExact(number)];
    }

    return Math.multiplyExact(slot, periodSeconds * timescale);
  }

  /**
   * Where segment {@code number}'s interval ends, in ticks of this timescale; empty for the last
   * segment of a list, whose interval runs to the end of the media.
   */
  OptionalLong endOf(long number, long timescale) {
    if (!isPlainGrid() && number + 1 >= startSlots.length) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(startOf(number + 1, timescale));
  }

  private boolean isPlainGrid() {
    return startSlots.length == 0;
  }
}
