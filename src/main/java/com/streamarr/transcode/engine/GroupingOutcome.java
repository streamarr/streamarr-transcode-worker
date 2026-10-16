package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.util.List;
import lombok.NonNull;

/** What adding one fragment to a {@link SegmentGrouper} produced, in arrival order. */
sealed interface GroupingOutcome {

  /** The fragment joined the open segment, waits for the first one, or belongs to the preroll. */
  record NothingClosed() implements GroupingOutcome {}

  /** The fragment's keyframes closed these media segments, in order, and opened the next one. */
  record SegmentsClosed(@NonNull List<MediaSegment> segments) implements GroupingOutcome {

    public SegmentsClosed {
      segments = List.copyOf(segments);
    }
  }

  /**
   * The fragment's keyframe opens a segment beyond the next one the attempt must deliver, so no
   * keyframe starts the skipped interval. The keyframe still marks the end of the open segment: the
   * producer delivers the segments closed before it, when there are any to deliver, and then fails
   * the job attempt with {@link #failure()}. Neither the skipping fragment nor any later one
   * belongs to a segment.
   *
   * @param closedSegments the complete segments the fragment's keyframes closed, in order; empty
   *     when none was open or the open one was preroll
   */
  record SegmentNumberSkipped(
      @NonNull List<MediaSegment> closedSegments, long expectedNumber, long actualNumber)
      implements GroupingOutcome {

    public SegmentNumberSkipped {
      closedSegments = List.copyOf(closedSegments);
    }

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
