package com.streamarr.transcode.fixtures;

/**
 * The start times, in audio ticks, of the audio samples a delivered media segment carries: the
 * interval of the segment on the grid, from before zero for segment zero, which keeps the AAC
 * priming frame, and without end for the last segment of a completed attempt, which keeps the audio
 * after its interval.
 */
public record AudioInterval(long start, long end) {

  /**
   * @param periodTicks the period in the audio timescale
   * @param lastOfACompletedAttempt whether the segment is the last one a completed attempt
   *     delivered
   */
  public static AudioInterval ofDeliveredSegment(
      long number, long periodTicks, boolean lastOfACompletedAttempt) {
    var start = number * periodTicks;
    var end = (number + 1) * periodTicks;
    if (number == 0) {
      start = Long.MIN_VALUE;
    }

    if (lastOfACompletedAttempt) {
      end = Long.MAX_VALUE;
    }

    return new AudioInterval(start, end);
  }

  public boolean holds(long time) {
    return time >= start && time < end;
  }
}
