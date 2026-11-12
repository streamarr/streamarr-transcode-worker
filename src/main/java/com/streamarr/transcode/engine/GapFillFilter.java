package com.streamarr.transcode.engine;

import java.time.Duration;

/**
 * The agapfill audio filter of Streamarr's FFmpeg fork (ADR 0057). It writes digital silence for
 * every step longer than 2 ms between the end of one audio frame and the next frame's timestamp,
 * and, from a start, before the first frame. A step of 2 ms or less, forward or backward, passes
 * through, and the frame keeps its own timestamp, as it does without the filter. A longer overlap
 * passes whole at the end of the frame before it. An encoder, which cuts its frames by sample
 * count, then meets no gap longer than 2 ms, and no sample is dropped.
 */
final class GapFillFilter {

  static final String NAME = "agapfill";
  // The option that the worker passes, which an FFmpeg must offer to run the worker's commands.
  static final String START_OPTION = "start";

  private GapFillFilter() {}

  /** The filter that fills the gaps between frames only. */
  static String inGaps() {
    return NAME;
  }

  /** The filter that also fills the time from the start, in whole seconds, to the first frame. */
  static String from(Duration start) {
    return NAME + "=" + START_OPTION + "=" + start.toSeconds();
  }
}
