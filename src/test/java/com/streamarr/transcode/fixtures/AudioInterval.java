package com.streamarr.transcode.fixtures;

/**
 * The start times, in audio ticks, of the audio samples a delivered media segment carries: the
 * interval of the segment on the grid, from before zero for segment zero, which keeps the AAC
 * priming frame. The last segment ends at its interval's end too, whatever audio follows it.
 */
public record AudioInterval(long start, long end) {

  /**
   * @param periodTicks the period in the audio timescale
   */
  public static AudioInterval ofDeliveredSegment(long number, long periodTicks) {
    var start = number * periodTicks;
    var end = (number + 1) * periodTicks;
    if (number == 0) {
      start = Long.MIN_VALUE;
    }

    return new AudioInterval(start, end);
  }

  public boolean holds(long time) {
    return time >= start && time < end;
  }
}
