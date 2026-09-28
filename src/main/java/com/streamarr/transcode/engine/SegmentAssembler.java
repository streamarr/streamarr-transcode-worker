package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.GroupingOutcome.NothingClosed;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentClosed;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentNumberSkipped;
import com.streamarr.transcode.engine.RebuiltSegment.SampleOf;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

/**
 * Assembles the units a producer reads, in arrival order, into the segments it delivers: the
 * initialization segment as it arrives, without the SEI messages of its HEVC records, and each
 * media segment, rebuilt as one {@code moof} and one {@code mdat}. A media segment holds the video
 * of the fragments its grouper groups and the audio samples that start inside its interval,
 * whichever fragment carried them. It closes once its grouper has closed it and no audio sample of
 * its interval can still arrive, or the output ends.
 */
final class SegmentAssembler {

  private final SegmentGrouper grouper;
  private final AudioSampleQueue audio;
  // Segments whose video the grouper closed while audio of their interval may still arrive.
  private final Deque<MediaSegment> awaitingAudio = new ArrayDeque<>();
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
    this.audio = new AudioSampleQueue(periodSeconds, startSequenceNumber);
    this.maximumSegmentBytes = maximumSegmentBytes;
    this.encodedFrameRate = EncodedFrameRate.of(encodedFrameRate);
    this.videoDecision = videoDecision;
  }

  /**
   * Reports the segments the unit closes, in order. A keyframe that skips a segment number closes
   * the open segment and every segment awaiting audio, and the assembler reports them with the
   * failure that ends the output once they are delivered.
   *
   * @throws FragmentedMp4Exception when the initialization segment declares other video than the
   *     decision, when the grouper refuses the fragment, when it holds a short video sample or an
   *     audio sample whose segment already closed, or when a segment it closes is larger than the
   *     segment cap once rebuilt, after which nothing is delivered, not even the segments it would
   *     close
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
      case Fragment fragment -> closingOf(checkedForShortSamples(fragment));
    };
  }

  /**
   * The segments the end of the output closes: every one awaiting audio, and the last, which also
   * takes the audio after its interval, since no later segment opened.
   *
   * @throws FragmentedMp4Exception when one is larger than the segment cap once rebuilt
   */
  List<ClosedSegment> finish() {
    grouper.finish().ifPresent(awaitingAudio::addLast);
    var closed = new ArrayList<ClosedSegment>();
    while (awaitingAudio.size() > 1) {
      closed.add(closeFirstAwaiting(List.of()));
    }

    if (!awaitingAudio.isEmpty()) {
      closed.add(closeFirstAwaiting(audio.closeAll()));
    }

    audio.discard();
    return closed;
  }

  /**
   * The bytes of the fragments the assembler holds: for the open segment, or for the first one, for
   * the segments awaiting audio, and for the audio samples it holds for later segments.
   */
  long heldBytes() {
    return heldFragments().stream().mapToLong(Fragment::byteLength).sum();
  }

  /** Drops every fragment the assembler holds, which the producer then never delivers. */
  void discardOpenFragments() {
    grouper.discardOpenFragments();
    awaitingAudio.clear();
    audio.discard();
  }

  private Fragment checkedForShortSamples(Fragment fragment) {
    return encodedFrameRate
        .map(frameRate -> frameRate.requireNoShortVideoSample(fragment))
        .orElse(fragment);
  }

  private Closing closingOf(Fragment fragment) {
    audio.add(fragment);
    return switch (grouper.accept(fragment)) {
      case NothingClosed _ -> Closing.of(closeEveryCompleted());
      case SegmentClosed(var segment) -> {
        awaitingAudio.addLast(segment);
        yield Closing.of(closeEveryCompleted());
      }
      case SegmentNumberSkipped skipped -> {
        skipped.closedSegment().ifPresent(awaitingAudio::addLast);
        yield new Closing(closeEveryAwaiting(), Optional.of(skipped.failure()));
      }
    };
  }

  // Closes each segment awaiting audio, in order, once no audio of its interval can still arrive.
  private List<ClosedSegment> closeEveryCompleted() {
    var closed = new ArrayList<ClosedSegment>();
    while (!awaitingAudio.isEmpty() && audio.hasPassed(awaitingAudio.getFirst().sequenceNumber())) {
      closed.add(closeFirstAwaiting(List.of()));
    }

    return closed;
  }

  private List<ClosedSegment> closeEveryAwaiting() {
    var closed = new ArrayList<ClosedSegment>();
    while (!awaitingAudio.isEmpty()) {
      closed.add(closeFirstAwaiting(List.of()));
    }

    return closed;
  }

  // Closes the first segment awaiting audio with the audio samples of its interval and any
  // further samples given.
  private ClosedSegment closeFirstAwaiting(List<SampleOf> furtherAudio) {
    var segment = awaitingAudio.removeFirst();
    var audioSamples = new ArrayList<>(audio.close(segment.sequenceNumber()));
    audioSamples.addAll(furtherAudio);
    var rebuilt =
        RebuiltSegment.builder()
            .sequenceNumber(segment.sequenceNumber())
            .videoSamples(segment.fragments().stream().flatMap(SampleOf::videoOf).toList())
            .audioSamples(audioSamples)
            .stillHeld(heldFragments())
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

  private Set<Fragment> heldFragments() {
    var held = Collections.newSetFromMap(new IdentityHashMap<Fragment, Boolean>());
    held.addAll(grouper.heldFragments());
    awaitingAudio.forEach(segment -> held.addAll(segment.fragments()));
    audio.heldFragments().forEach(held::add);
    return held;
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
