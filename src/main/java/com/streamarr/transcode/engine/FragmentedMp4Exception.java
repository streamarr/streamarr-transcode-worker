package com.streamarr.transcode.engine;

import lombok.Getter;
import lombok.NonNull;

/** FFmpeg's fragmented MP4 output cannot be delivered as media segments, for a named reason. */
@Getter
final class FragmentedMp4Exception extends RuntimeException {

  enum Reason {
    EXCEEDS_SEGMENT_CAP,
    UNSIZED_BOX,
    MALFORMED_BOX,
    END_OF_FILE_IN_BOX_HEADER,
    END_OF_FILE_IN_BOX_BODY,
    END_OF_FILE_AFTER_MOVIE_FRAGMENT,
    MISSING_INITIALIZATION_SEGMENT,
    MISPLACED_INITIALIZATION_SEGMENT,
    UNEXPECTED_BOX
  }

  private final Reason reason;

  FragmentedMp4Exception(@NonNull Reason reason, @NonNull String detail) {
    super(reason + ": " + detail);
    this.reason = reason;
  }
}
