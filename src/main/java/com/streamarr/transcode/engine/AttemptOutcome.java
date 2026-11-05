package com.streamarr.transcode.engine;

import lombok.NonNull;

/** How a job attempt ended; the producer settles exactly one, after FFmpeg has exited. */
public sealed interface AttemptOutcome {

  /**
   * FFmpeg exited cleanly after the producer read all of its output and the sink accepted every
   * segment the producer closed.
   *
   * @param droppedLateAudioSampleCount the audio samples the producer dropped because they arrived
   *     after it closed the media segment whose interval holds their start
   * @param sourceEnd where the chosen source streams ended, as FFmpeg read them
   */
  record Completed(long droppedLateAudioSampleCount, @NonNull SourceEnd sourceEnd)
      implements AttemptOutcome {}

  /** The attempt failed for the named reason; the detail describes it for the operator. */
  record Failed(@NonNull ProducerFailure reason, @NonNull String detail)
      implements AttemptOutcome {}

  /** The worker stopped the attempt, and FFmpeg has exited. */
  record Stopped() implements AttemptOutcome {}
}
