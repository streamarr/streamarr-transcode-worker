package com.streamarr.transcode.engine;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.IntPredicate;
import lombok.NonNull;

/**
 * The media segments' intervals on ADR 0037's zero-based timeline of slots, each one period long.
 * On the plain grid segment N's interval is slot N. On a segment list, segment N's interval runs
 * from its listed start up to the next segment's, so one segment can span several slots; a list
 * gives each start as an exact presentation time in ticks of a time base. The job's advertised end
 * bounds the last segment's video and audio on either timeline; without one, the final listed
 * segment runs to the end of the media.
 */
final class SegmentIntervals {

  private final int periodSeconds;
  // Each segment's start in ticks of the start time base, strictly increasing from 0; empty on the
  // plain grid.
  private final long[] starts;
  private final TimeBase startTimeBase;
  // The job's advertised end, when present, on either timeline.
  private final Optional<Duration> advertisedEnd;

  private SegmentIntervals(
      int periodSeconds, long[] starts, TimeBase startTimeBase, Optional<Duration> advertisedEnd) {
    this.periodSeconds = periodSeconds;
    this.starts = starts;
    this.startTimeBase = startTimeBase;
    this.advertisedEnd = advertisedEnd;
  }

  /** The intervals of a plain grid whose every segment lasts this many seconds. */
  static SegmentIntervals onPlainGrid(int periodSeconds) {
    requirePositive(periodSeconds);
    return new SegmentIntervals(
        periodSeconds, new long[0], slotsOf(periodSeconds), Optional.empty());
  }

  /**
   * The intervals of a list of exact segment starts on slots that last this many seconds, the plain
   * grid's period, which a replacement attempt's seek still counts in.
   */
  static SegmentIntervals onExactStarts(int periodSeconds, @NonNull ExactSegmentStarts starts) {
    requirePositive(periodSeconds);
    return new SegmentIntervals(
        periodSeconds,
        starts.ticks().stream().mapToLong(Long::longValue).toArray(),
        starts.timeBase(),
        Optional.empty());
  }

  // A slot is one tick of a time base whose ticks each last one period.
  private static TimeBase slotsOf(int periodSeconds) {
    return new TimeBase(periodSeconds, 1);
  }

  /**
   * These intervals bounded by the job's advertised end. The plain grid's last segment may be
   * shorter than one period. For a list, the job's mapping has checked that the end lies after its
   * last start.
   */
  SegmentIntervals withAdvertisedEnd(@NonNull Duration advertisedEnd) {
    return new SegmentIntervals(periodSeconds, starts, startTimeBase, Optional.of(advertisedEnd));
  }

  Optional<Duration> advertisedEnd() {
    return advertisedEnd;
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

    return lastSegmentWhoseStart(
        index -> startsAtOrBefore(starts[index], presentationTime, timescale));
  }

  /**
   * The number of the segment whose interval holds this time, negative before the first; on a list,
   * every time from the last listed start on is in the last listed segment.
   */
  long numberAt(MediaTime time) {
    if (time.ticks() < 0 || isPlainGrid()) {
      return time.floorIn(slotsOf(periodSeconds));
    }

    return lastSegmentWhoseStart(index -> startTimeOf(index).compareTo(time) <= 0);
  }

  /**
   * The number of the segment whose interval holds the instants just before this time, as the last
   * instant that a sample ending at this time covers.
   */
  long numberJustBefore(MediaTime time) {
    if (time.ticks() <= 0 || isPlainGrid()) {
      return time.ceilIn(slotsOf(periodSeconds)) - 1;
    }

    return lastSegmentWhoseStart(index -> startTimeOf(index).compareTo(time) < 0);
  }

  // The last listed segment whose start passes the test, which segment 0's start, 0, passes.
  private long lastSegmentWhoseStart(IntPredicate test) {
    var low = 0;
    var high = starts.length - 1;
    while (low < high) {
      var middle = (low + high + 1) >>> 1;
      if (test.test(middle)) {
        low = middle;
        continue;
      }

      high = middle - 1;
    }

    return low;
  }

  // Compares the start, in ticks of the start time base, with the presentation time, in ticks of
  // the timescale, exactly, so that neither is rounded.
  private boolean startsAtOrBefore(long start, long presentationTime, long timescale) {
    return new MediaTime(start, startTimeBase)
            .compareTo(new MediaTime(presentationTime, new TimeBase(1, timescale)))
        <= 0;
  }

  /** Where segment {@code number}'s interval starts, at its first tick of this timescale. */
  long startOf(long number, long timescale) {
    var start = startTimeOf(number);
    return firstTickAtOrAfter(start.ticks(), start.timeBase(), timescale);
  }

  /** Where segment {@code number}'s interval starts on the zero-based timeline, exactly. */
  MediaTime startTimeOf(long number) {
    if (isPlainGrid()) {
      return new MediaTime(number, slotsOf(periodSeconds));
    }

    return new MediaTime(starts[Math.toIntExact(number)], startTimeBase);
  }

  /**
   * The shortest interval between two consecutive listed starts, or one period when that is shorter
   * or the grid is plain.
   */
  MediaTime shortestInterval() {
    var shortest = new MediaTime(1, slotsOf(periodSeconds));
    for (var index = 1; index < starts.length; index++) {
      var interval = new MediaTime(starts[index] - starts[index - 1], startTimeBase);
      if (interval.compareTo(shortest) < 0) {
        shortest = interval;
      }
    }

    return shortest;
  }

  // Throws ArithmeticException rather than wrap when the tick does not fit a long.
  private static long firstTickAtOrAfter(long start, TimeBase timeBase, long timescale) {
    return new MediaTime(start, timeBase).ceilIn(new TimeBase(1, timescale));
  }

  /**
   * Where segment {@code number}'s interval ends, in ticks of this timescale: for the last listed
   * segment, the first tick at or after the advertised end, where its media ends; empty when that
   * segment runs to the end of the media, and for a number past the list.
   */
  OptionalLong endOf(long number, long timescale) {
    if (isPlainGrid() || number + 1 < starts.length) {
      return OptionalLong.of(
          limitToAdvertisedEnd(startTimeOf(number + 1)).ceilIn(new TimeBase(1, timescale)));
    }

    if (number + 1 > starts.length || advertisedEnd.isEmpty()) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(MediaTicks.firstTickAtOrAfter(advertisedEnd.get(), timescale));
  }

  /**
   * Where segment {@code number}'s interval ends on the zero-based timeline, exactly: for the last
   * listed segment, the advertised end, where its media ends; empty when that segment runs to the
   * end of the media, and for a number past the list.
   */
  Optional<MediaTime> endTimeOf(long number) {
    if (isPlainGrid() || number + 1 < starts.length) {
      return Optional.of(limitToAdvertisedEnd(startTimeOf(number + 1)));
    }

    if (number + 1 > starts.length) {
      return Optional.empty();
    }

    return advertisedEnd.map(MediaTime::of);
  }

  private MediaTime limitToAdvertisedEnd(MediaTime end) {
    return advertisedEnd.map(MediaTime::of).filter(limit -> limit.compareTo(end) < 0).orElse(end);
  }

  /**
   * The number of the segment whose audio takes a sample that starts at this presentation time: as
   * {@link #numberAt}, except that a sample at or after the advertised end belongs past the final
   * segment.
   */
  long numberOfAudioSampleAt(long presentationTime, long timescale) {
    if (startsAtOrAfterAdvertisedEnd(presentationTime, timescale)) {
      return isPlainGrid()
          ? MediaTime.of(advertisedEnd.orElseThrow()).ceilIn(slotsOf(periodSeconds))
          : starts.length;
    }

    return numberAt(presentationTime, timescale);
  }

  private boolean startsAtOrAfterAdvertisedEnd(long presentationTime, long timescale) {
    return advertisedEnd
        .filter(end -> presentationTime >= MediaTicks.firstTickAtOrAfter(end, timescale))
        .isPresent();
  }

  private boolean isPlainGrid() {
    return starts.length == 0;
  }
}
