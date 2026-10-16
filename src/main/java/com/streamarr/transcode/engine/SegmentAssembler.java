package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.GroupingOutcome.NothingClosed;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentClosed;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentNumberSkipped;
import com.streamarr.transcode.engine.RebuiltSegment.SampleOf;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import lombok.Builder;
import lombok.NonNull;

/**
 * Assembles the units a producer reads, in arrival order, into the segments it delivers: the
 * initialization segment as it arrives, without the SEI messages of its HEVC records, and each
 * media segment, rebuilt as one {@code moof} and one {@code mdat}, once its grouper closes it.
 */
final class SegmentAssembler {

  private final SegmentGrouper grouper;
  private final long maximumSegmentBytes;
  private final Optional<EncodedFrameRate> encodedFrameRate;
  private final VideoDecision videoDecision;

  /**
   * @param startSequenceNumber the first segment this job attempt delivers; segments below it are
   *     preroll and are discarded
   * @param maximumSegmentBytes the largest media segment the assembler delivers, and the most bytes
   *     of fragments it holds for one
   * @param encodedFrameRate the frame rate an attempt that encodes video forces on its output, so
   *     that the assembler refuses a fragment holding a video sample shorter than half a frame;
   *     empty when the attempt copies the video, whose sample durations follow the source
   * @param videoDecision the video that the initialization segment must declare
   */
  @Builder
  private SegmentAssembler(
      int periodSeconds,
      int startSequenceNumber,
      long maximumSegmentBytes,
      @NonNull OptionalDouble encodedFrameRate,
      @NonNull VideoDecision videoDecision) {
    this.grouper = new SegmentGrouper(periodSeconds, startSequenceNumber, maximumSegmentBytes);
    this.maximumSegmentBytes = maximumSegmentBytes;
    this.encodedFrameRate = EncodedFrameRate.of(encodedFrameRate);
    this.videoDecision = videoDecision;
  }

  /**
   * Reports the segments the unit closes, if any. A keyframe that skips a segment number closes the
   * open segment too, and the assembler reports that segment with the failure that ends the output
   * once it is delivered.
   *
   * @throws FragmentedMp4Exception when the initialization segment declares other video than the
   *     decision, when the grouper refuses the fragment, when it holds a short video sample, or
   *     when the segment it closes is larger than the segment cap once rebuilt, after which nothing
   *     is delivered, not even the segment it would close
   */
  Closing accept(@NonNull Mp4Unit unit) {
    return switch (unit) {
      case InitializationSegment initializationSegment -> {
        VideoOutputCheck.requireMatch(videoDecision, initializationSegment);
        yield Closing.of(
            List.of(
                new ClosedSegment.Initialization(
                    HevcRecordSeiRemoval.removeSeiMessages(initializationSegment))));
      }
      case Fragment fragment -> closingOf(grouper.accept(checkedForShortSamples(fragment)));
    };
  }

  /**
   * The last media segment, which the end of the output closes.
   *
   * @throws FragmentedMp4Exception when that segment is larger than the segment cap once rebuilt
   */
  List<ClosedSegment> finish() {
    return grouper.finish().map(this::rebuilt).stream().toList();
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

  private Closing closingOf(GroupingOutcome grouping) {
    return switch (grouping) {
      case NothingClosed _ -> Closing.of(List.of());
      case SegmentClosed(var segment) -> Closing.of(List.of(rebuilt(segment)));
      case SegmentNumberSkipped skipped ->
          new Closing(
              skipped.closedSegment().map(this::rebuilt).stream().toList(),
              Optional.of(skipped.failure()));
    };
  }

  private ClosedSegment rebuilt(MediaSegment segment) {
    var rebuilt =
        RebuiltSegment.builder()
            .sequenceNumber(segment.sequenceNumber())
            .videoSamples(segment.fragments().stream().flatMap(SampleOf::videoOf).toList())
            .audioSamples(segment.fragments().stream().flatMap(SampleOf::audioOf).toList())
            .build();
    if (rebuilt.byteLength() > maximumSegmentBytes) {
      throw new FragmentedMp4Exception(
          Reason.EXCEEDS_SEGMENT_CAP,
          "media segment "
              + segment.sequenceNumber()
              + " takes "
              + rebuilt.byteLength()
              + " bytes as one moof and mdat, over the segment cap of "
              + maximumSegmentBytes);
    }

    return rebuilt;
  }

  /**
   * What one unit closes.
   *
   * @param closedSegments the segments to deliver next, in order; empty when the unit closed none
   * @param failure why the output fails once the closed segments are delivered; empty unless the
   *     unit skipped a segment number
   */
  record Closing(
      @NonNull List<ClosedSegment> closedSegments,
      @NonNull Optional<FragmentedMp4Exception> failure) {

    Closing {
      closedSegments = List.copyOf(closedSegments);
    }

    private static Closing of(List<ClosedSegment> closedSegments) {
      return new Closing(closedSegments, Optional.empty());
    }
  }
}
