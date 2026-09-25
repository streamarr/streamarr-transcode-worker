package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.util.Optional;
import lombok.NonNull;

/** What adding one fragment to a {@link SegmentGrouper} produced, in arrival order. */
sealed interface GroupingOutcome {

  /** The fragment joined the open segment, waits for the first one, or belongs to the preroll. */
  record NothingClosed() implements GroupingOutcome {}

  /** The fragment's keyframe closed this media segment and opened the next one. */
  record SegmentClosed(@NonNull MediaSegment segment) implements GroupingOutcome {}

  /**
   * The fragment's keyframe opens a segment beyond the next one the attempt must deliver, so no
   * keyframe starts the skipped interval. The keyframe still marks the end of the open segment: the
   * producer delivers that segment, when there is one to deliver, and then fails the job attempt
   * with {@link #failure()}. Neither the skipping fragment nor any later one belongs to a segment.
   *
   * @param closedSegment the complete segment the keyframe closed; empty when none was open or the
   *     open one was preroll
   */
  record SegmentNumberSkipped(
      @NonNull Optional<MediaSegment> closedSegment, long expectedNumber, long actualNumber)
      implements GroupingOutcome {

    FragmentedMp4Exception failure() {
      return new FragmentedMp4Exception(
          Reason.SKIPPED_SEGMENT_NUMBER,
          "a keyframe opens segment "
              + actualNumber
              + " while segment "
              + expectedNumber
              + " has none");
    }
  }
}
