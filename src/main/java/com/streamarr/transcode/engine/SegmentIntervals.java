package com.streamarr.transcode.engine;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.NonNull;

/**
 * The media segments' intervals on ADR 0037's zero-based timeline of slots, each one period long.
 * On the plain grid segment N's interval is slot N. On a segment list, segment N's interval runs
 * from its listed start slot up to the next segment's, so one segment can span several slots. The
 * last listed segment's audio ends at the job's advertised end, and runs to the end of the media
 * only in a job that carries none; its video runs to the end of the media either way, so a keyframe
 * past the advertised end opens no segment that the playlist does not advertise.
 */
final class SegmentIntervals {

  private final int periodSeconds;
  // Each segment's start slot, strictly increasing from 0; empty on the plain grid.
  private final long[] startSlots;
  // Where the last listed segment's audio ends; empty when it runs to the end of the media.
  private final Optional<Duration> listEnd;

  private SegmentIntervals(int periodSeconds, long[] startSlots, Optional<Duration> listEnd) {
    this.periodSeconds = periodSeconds;
    this.startSlots = startSlots;
    this.listEnd = listEnd;
  }

  /** The intervals of a plain grid whose every segment lasts this many seconds. */
  static SegmentIntervals onPlainGrid(int periodSeconds) {
    requirePositive(periodSeconds);
    return new SegmentIntervals(periodSeconds, new long[0], Optional.empty());
  }

  /**
   * The intervals of a segment list whose slots last this many seconds: entry N is the slot where
   * segment N starts. An empty list is the plain grid. The job's mapping has checked that a
   * non-empty list starts at 0 and strictly increases.
   */
  static SegmentIntervals onSegmentList(int periodSeconds, @NonNull List<Long> startSlots) {
    requirePositive(periodSeconds);
    return new SegmentIntervals(
        periodSeconds, startSlots.stream().mapToLong(Long::longValue).toArray(), Optional.empty());
  }

  /**
   * These intervals with the last listed segment's audio ending at the job's advertised end, so
   * that no audio past that end joins it. The plain grid's segments each keep one period. The job's
   * mapping has checked that the end lies after the last listed segment's start.
   */
  SegmentIntervals withLastListedSegmentEndingAt(@NonNull Duration advertisedEnd) {
    if (isPlainGrid()) {
      return this;
    }

    return new SegmentIntervals(periodSeconds, startSlots, Optional.of(advertisedEnd));
  }

  private static void requirePositive(int periodSeconds) {
    if (periodSeconds <= 0) {
      throw new IllegalArgumentException("Period must be positive, got: " + periodSeconds);
    }
  }

  /**
   * The number of the segment whose interval holds this presentation time, negative before the
   * first; on a list, every time from the last listed start on is in the last listed segment.
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
   * Where segment {@code number}'s interval ends, in ticks of this timescale: for the last listed
   * segment, the first tick at or after the advertised end, where its audio ends; empty when that
   * segment runs to the end of the media, and for a number past the list.
   */
  OptionalLong endOf(long number, long timescale) {
    if (isPlainGrid() || number + 1 < startSlots.length) {
      return OptionalLong.of(startOf(number + 1, timescale));
    }

    if (number + 1 > startSlots.length || listEnd.isEmpty()) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(MediaTicks.firstTickAtOrAfter(listEnd.get(), timescale));
  }

  /**
   * The number of the segment whose audio takes a sample that starts at this presentation time: as
   * {@link #numberAt}, except that a sample at or after the last listed segment's end belongs past
   * the list.
   */
  long numberOfAudioSampleAt(long presentationTime, long timescale) {
    if (startsAtOrAfterTheListsEnd(presentationTime, timescale)) {
      return startSlots.length;
    }

    return numberAt(presentationTime, timescale);
  }

  private boolean startsAtOrAfterTheListsEnd(long presentationTime, long timescale) {
    return listEnd
        .filter(end -> presentationTime >= MediaTicks.firstTickAtOrAfter(end, timescale))
        .isPresent();
  }

  private boolean isPlainGrid() {
    return startSlots.length == 0;
  }
}
