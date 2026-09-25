package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.GroupingOutcome.NothingClosed;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentClosed;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentNumberSkipped;
import java.util.Optional;
import lombok.NonNull;

/**
 * Assembles the units a producer reads, in arrival order, into the segments it delivers: the
 * initialization segment as it arrives, and each media segment once its grouper closes it.
 */
final class SegmentAssembler {

  private final SegmentGrouper grouper;

  SegmentAssembler(@NonNull SegmentGrouper grouper) {
    this.grouper = grouper;
  }

  /**
   * Reports the segment the unit closes, if any. A keyframe that skips a segment number closes the
   * open segment too, and the assembler reports that segment with the failure that ends the output
   * once it is delivered.
   *
   * @throws FragmentedMp4Exception when the grouper refuses the fragment
   */
  Closing accept(@NonNull Mp4Unit unit) {
    return switch (unit) {
      case InitializationSegment initializationSegment ->
          Closing.of(ProducedSegment.of(initializationSegment));
      case Fragment fragment -> closingOf(grouper.accept(fragment));
    };
  }

  /** The last media segment, which the end of the output closes. */
  Optional<ProducedSegment> finish() {
    return grouper.finish().map(ProducedSegment::of);
  }

  private static Closing closingOf(GroupingOutcome grouping) {
    return switch (grouping) {
      case NothingClosed _ -> new Closing(Optional.empty(), Optional.empty());
      case SegmentClosed(var segment) -> Closing.of(ProducedSegment.of(segment));
      case SegmentNumberSkipped skipped ->
          new Closing(
              skipped.closedSegment().map(ProducedSegment::of), Optional.of(skipped.failure()));
    };
  }

  /**
   * What one unit closes.
   *
   * @param closedSegment the segment to deliver next; empty when the unit closed none
   * @param failure why the output fails once the closed segment is delivered; empty unless the unit
   *     skipped a segment number
   */
  record Closing(
      @NonNull Optional<ProducedSegment> closedSegment,
      @NonNull Optional<FragmentedMp4Exception> failure) {

    private static Closing of(ProducedSegment closedSegment) {
      return new Closing(Optional.of(closedSegment), Optional.empty());
    }
  }
}
