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
  private final Optional<EncodedFrameRate> encodedFrameRate;

  /**
   * @param encodedFrameRate the frame rate an attempt that encodes video forces on its output, so
   *     that the assembler refuses a fragment holding a video sample shorter than half a frame;
   *     empty when the attempt copies the video, whose sample durations follow the source
   */
  SegmentAssembler(
      @NonNull SegmentGrouper grouper, @NonNull Optional<EncodedFrameRate> encodedFrameRate) {
    this.grouper = grouper;
    this.encodedFrameRate = encodedFrameRate;
  }

  /**
   * Reports the segment the unit closes, if any. A keyframe that skips a segment number closes the
   * open segment too, and the assembler reports that segment with the failure that ends the output
   * once it is delivered.
   *
   * @throws FragmentedMp4Exception when the grouper refuses the fragment, or when it holds a short
   *     video sample, after which nothing is delivered, not even the segment it would close
   */
  Closing accept(@NonNull Mp4Unit unit) {
    return switch (unit) {
      case InitializationSegment initializationSegment ->
          Closing.of(ProducedSegment.of(initializationSegment));
      case Fragment fragment -> closingOf(grouper.accept(checkedForShortSamples(fragment)));
    };
  }

  /** The last media segment, which the end of the output closes. */
  Optional<ProducedSegment> finish() {
    return grouper.finish().map(ProducedSegment::of);
  }

  /** The bytes of the fragments the assembler holds for the open segment, or for the first one. */
  long heldBytes() {
    return grouper.heldBytes();
  }

  /** Drops the fragments held for the open segment, which the producer then never delivers. */
  void discardOpenFragments() {
    grouper.discardOpenFragments();
  }

  private Fragment checkedForShortSamples(Fragment fragment) {
    return encodedFrameRate
        .map(frameRate -> frameRate.requireNoShortVideoSample(fragment))
        .orElse(fragment);
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
