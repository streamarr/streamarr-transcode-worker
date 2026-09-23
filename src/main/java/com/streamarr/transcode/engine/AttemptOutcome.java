package com.streamarr.transcode.engine;

import lombok.NonNull;

/** How a job attempt ended; the producer settles exactly one, after FFmpeg has exited. */
public sealed interface AttemptOutcome {

  /**
   * FFmpeg exited cleanly after the producer read all of its output and the sink accepted every
   * segment the producer closed.
   */
  record Completed() implements AttemptOutcome {}

  /** The attempt failed for the named reason; the detail describes it for the operator. */
  record Failed(@NonNull ProducerFailure reason, @NonNull String detail)
      implements AttemptOutcome {}
}
