package com.streamarr.transcode.engine;

import java.time.Duration;
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
  // Each segment's start in ticks of the start time base, strictly increasing from 0; empty on the
  // plain grid.
  private final long[] starts;
  private final TimeBase startTimeBase;
  // Where the last listed segment's audio ends; empty when it runs to the end of the media.
  private final Optional<Duration> listEnd;

  private SegmentIntervals(
      int periodSeconds, long[] starts, TimeBase startTimeBase, Optional<Duration> listEnd) {
    this.periodSeconds = periodSeconds;
    this.starts = starts;
    this.startTimeBase = startTimeBase;
    this.listEnd = listEnd;
  }

  /** The intervals of a plain grid whose every segment lasts this many seconds. */
  static SegmentIntervals onPlainGrid(int periodSeconds) {
    requirePositive(periodSeconds);
    return new SegmentIntervals(
        periodSeconds, new long[0], slotsOf(periodSeconds), Optional.empty());
  }

  /**
   * The intervals of a segment list whose slots last this many seconds: entry N is the slot where
   * segment N starts. An empty list is the plain grid. The job's mapping has checked that a
   * non-empty list starts at 0 and strictly increases.
   */
  static SegmentIntervals onSegmentList(int periodSeconds, @NonNull List<Long> startSlots) {
    requirePositive(periodSeconds);
    return new SegmentIntervals(
        periodSeconds,
        startSlots.stream().mapToLong(Long::longValue).toArray(),
        slotsOf(periodSeconds),
        Optional.empty());
  }

  // A slot is one tick of a time base whose ticks each last one period.
  private static TimeBase slotsOf(int periodSeconds) {
    return new TimeBase(periodSeconds, 1);
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

    return new SegmentIntervals(periodSeconds, starts, startTimeBase, Optional.of(advertisedEnd));
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
    if (presentationTime < 0 || isPlainGrid()) {
      return Math.floorDiv(presentationTime, periodSeconds * timescale);
    }

    // The last listed segment whose start lies at or before the time; segment 0 starts at 0.
    var low = 0;
    var high = starts.length - 1;
    while (low < high) {
      var middle = (low + high + 1) >>> 1;
      if (startsAtOrBefore(starts[middle], presentationTime, timescale)) {
        low = middle;
        continue;
      }

      high = middle - 1;
    }

    return low;
  }

  // Compares the start, in ticks of the start time base, with the presentation time, in ticks of
  // the timescale, by cross-multiplication, so that neither is rounded; it fails rather than wraps.
  private boolean startsAtOrBefore(long start, long presentationTime, long timescale) {
    var startInBoth =
        Math.multiplyExact(Math.multiplyExact(start, startTimeBase.numerator()), timescale);
    return startInBoth <= Math.multiplyExact(presentationTime, startTimeBase.denominator());
  }

  /** Where segment {@code number}'s interval starts, at its first tick of this timescale. */
  long startOf(long number, long timescale) {
    var start = number;
    var timeBase = slotsOf(periodSeconds);
    if (!isPlainGrid()) {
      start = starts[Math.toIntExact(number)];
      timeBase = startTimeBase;
    }

    return firstTickAtOrAfter(start, timeBase, timescale);
  }

  private static long firstTickAtOrAfter(long start, TimeBase timeBase, long timescale) {
    return Math.ceilDiv(
        Math.multiplyExact(Math.multiplyExact(start, timeBase.numerator()), timescale),
        timeBase.denominator());
  }

  /**
   * Where segment {@code number}'s interval ends, in ticks of this timescale: for the last listed
   * segment, the first tick at or after the advertised end, where its audio ends; empty when that
   * segment runs to the end of the media, and for a number past the list.
   */
  OptionalLong endOf(long number, long timescale) {
    if (isPlainGrid() || number + 1 < starts.length) {
      return OptionalLong.of(startOf(number + 1, timescale));
    }

    if (number + 1 > starts.length || listEnd.isEmpty()) {
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
      return starts.length;
    }

    return numberAt(presentationTime, timescale);
  }

  private boolean startsAtOrAfterTheListsEnd(long presentationTime, long timescale) {
    return listEnd
        .filter(end -> presentationTime >= MediaTicks.firstTickAtOrAfter(end, timescale))
        .isPresent();
  }

  private boolean isPlainGrid() {
    return starts.length == 0;
  }
}
