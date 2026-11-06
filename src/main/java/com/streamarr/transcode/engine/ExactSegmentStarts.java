package com.streamarr.transcode.engine;

import java.util.List;
import lombok.NonNull;

/**
 * Where each media segment starts, in ticks of the source video stream's time base, whose ticks
 * each last {@code timeBaseNumerator / timeBaseDenominator} seconds: segment 0 at 0, and every
 * later segment at the presentation time of the source keyframe that opens the copied segment (ADR
 * 0037, segment list). The job's mapping has checked that the list starts at 0 and strictly
 * increases.
 */
public record ExactSegmentStarts(
    @NonNull List<Long> ticks, long timeBaseNumerator, long timeBaseDenominator) {

  /**
   * @throws IllegalArgumentException when a part of the time base is not positive
   */
  public ExactSegmentStarts {
    ticks = List.copyOf(ticks);
    new TimeBase(timeBaseNumerator, timeBaseDenominator);
  }

  TimeBase timeBase() {
    return new TimeBase(timeBaseNumerator, timeBaseDenominator);
  }
}
