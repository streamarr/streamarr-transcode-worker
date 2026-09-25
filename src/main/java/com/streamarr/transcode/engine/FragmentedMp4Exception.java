package com.streamarr.transcode.engine;

import lombok.Getter;
import lombok.NonNull;

/** FFmpeg's fragmented MP4 output cannot be delivered as media segments, for a named reason. */
@Getter
final class FragmentedMp4Exception extends RuntimeException {

  enum Reason {
    /**
     * A box would make the initialization segment or a fragment larger than the segment cap, or a
     * fragment would make a media segment larger than it.
     */
    EXCEEDS_SEGMENT_CAP,
    /** A top-level box declares size zero, which on a pipe means it never ends. */
    UNSIZED_BOX,
    /** A box's declared size or fields do not fit the bytes that hold it. */
    MALFORMED_BOX,
    /**
     * A run's samples, where its {@code moof} places them, do not lie inside the body of the {@code
     * mdat} that follows that {@code moof}.
     */
    SAMPLE_DATA_OUTSIDE_MDAT,
    END_OF_FILE_IN_BOX_HEADER,
    END_OF_FILE_IN_BOX_BODY,
    END_OF_FILE_AFTER_MOVIE_FRAGMENT,
    MISSING_INITIALIZATION_SEGMENT,
    MISPLACED_INITIALIZATION_SEGMENT,
    /** A top-level box other than the {@code moof} and {@code mdat} pair of the next fragment. */
    UNEXPECTED_BOX,
    MULTIPLE_VIDEO_TRACKS,
    SKIPPED_SEGMENT_NUMBER,
    PRESENTATION_TIME_REGRESSED
  }

  private final Reason reason;

  FragmentedMp4Exception(@NonNull Reason reason, @NonNull String detail) {
    super(reason + ": " + detail);
    this.reason = reason;
  }

  static FragmentedMp4Exception malformed(String detail) {
    return new FragmentedMp4Exception(Reason.MALFORMED_BOX, detail);
  }
}
