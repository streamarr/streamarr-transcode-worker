package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.AttemptOutcome.Failed;

/** Why a producer stopped reading FFmpeg's output. */
sealed interface ReadingEnding {

  /**
   * The ending of an output refused for the exception's reason: the output ends where it cannot be
   * delivered, and a truncated output has already ended.
   */
  static ReadingEnding refused(FragmentedMp4Exception exception) {
    var failure = new Failed(ProducerFailure.of(exception.getReason()), exception.getMessage());
    if (failure.reason() == ProducerFailure.TRUNCATED_OUTPUT) {
      return new TruncatedOutput(failure);
    }

    return new Abandoned(failure);
  }

  /** The output ended on a box boundary. */
  record EndOfOutput() implements ReadingEnding {}

  /** The output ended inside a box or after a moof with no mdat. */
  record TruncatedOutput(Failed failure) implements ReadingEnding {}

  /** The attempt failed while FFmpeg may still be writing. */
  record Abandoned(Failed failure) implements ReadingEnding {}

  /** The attempt's outcome was decided elsewhere, by a stop or a failed delivery. */
  record Decided() implements ReadingEnding {}
}
