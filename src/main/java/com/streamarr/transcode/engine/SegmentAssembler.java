package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.GroupingOutcome.NothingClosed;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentNumberSkipped;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentsClosed;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

/**
 * Assembles the units a producer reads, in arrival order, into the segments it delivers: the
 * initialization segment, its HEVC records holding only the static HDR metadata that the copy
 * plans, and each media segment, rebuilt as one {@code moof} and one {@code mdat}, its video
 * samples rewritten as the copy's bitstream rewrites list. A media segment holds the video of the
 * fragments its grouper groups and the audio samples that start inside its interval, whichever
 * fragment carried them. It closes once its grouper has closed it and an audio sample of a later
 * segment has arrived or the video has run a margin past its end, at once when the movie has no
 * sound track, or when the output ends. No segment takes audio past its interval, not even the last
 * when the audio outlasts the video.
 */
final class SegmentAssembler {

  private final SegmentGrouper grouper;
  private final AudioSampleQueue audio;
  private final VideoProgress video;
  // Segments whose video the grouper closed while audio of their interval may still arrive.
  private final Deque<AwaitingSegment> awaitingAudio = new ArrayDeque<>();
  private final long maximumSegmentBytes;
  private final Optional<OutputFrameRate> encodedFrameRate;
  private final VideoDecision videoDecision;
  // Set by the initialization segment, which the reader returns before any fragment.
  private Optional<VideoSampleRewrite> sampleRewrite = Optional.empty();

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
      @NonNull Optional<OutputFrameRate> encodedFrameRate,
      @NonNull VideoDecision videoDecision) {
    var intervals = SegmentIntervals.onPlainGrid(periodSeconds);
    this.grouper = new SegmentGrouper(intervals, startSequenceNumber, maximumSegmentBytes);
    this.audio = new AudioSampleQueue(intervals, startSequenceNumber);
    this.video = new VideoProgress(intervals);
    this.maximumSegmentBytes = maximumSegmentBytes;
    this.encodedFrameRate = encodedFrameRate;
    this.videoDecision = videoDecision;
  }

  /**
   * Reports the segments the unit closes, in order. A keyframe that skips a segment number closes
   * the open segment and every segment awaiting audio, and the assembler reports them with the
   * failure that ends the output once they are delivered.
   *
   * @throws FragmentedMp4Exception when the initialization segment it delivers declares other video
   *     than the decision, when a video sample that the copy rewrites cannot be read, when the
   *     grouper refuses the fragment, when it holds a short video sample or an audio sample that
   *     starts before the one before it, or when a segment it closes is larger than the segment cap
   *     once rebuilt, after which nothing is delivered, not even the segments it would close
   */
  Closing accept(@NonNull Mp4Unit unit) {
    return switch (unit) {
      case InitializationSegment initializationSegment -> {
        var delivered = InitializationSegmentRewrite.rewrite(videoDecision, initializationSegment);
        VideoOutputCheck.requireMatch(videoDecision, delivered);
        sampleRewrite = VideoSampleRewrite.of(videoDecision, delivered);
        yield Closing.of(List.of(new ClosedSegment.Initialization(delivered)));
      }
      case Fragment fragment -> closingOf(checkedForShortSamples(rewritten(fragment)));
    };
  }

  /**
   * Ends the grouping at the end of the output and drops the audio samples after the last segment's
   * interval, which belong to segments that no keyframe opened because the video ended first. The
   * assembler then holds only what the segments that {@link #finish()} closes need.
   */
  void dropAudioAfterTheLastSegment() {
    grouper.finish().map(AwaitingSegment::of).ifPresent(awaitingAudio::addLast);
    if (!awaitingAudio.isEmpty()) {
      audio.discardAfter(awaitingAudio.getLast().sequenceNumber());
    }
  }

  /**
   * The segments the end of the output closes: every one awaiting audio, and the last, each with
   * only the audio of its own interval; the audio after the last segment's interval is dropped
   * first.
   *
   * @throws FragmentedMp4Exception when one is larger than the segment cap once rebuilt
   */
  List<ClosedSegment> finish() {
    dropAudioAfterTheLastSegment();
    var closed = closeEveryAwaiting();
    audio.discard();
    return closed;
  }

  /**
   * The memory of the fragments the assembler holds, their bytes and their samples' heap: for the
   * open segment, or for the first one, for the segments awaiting audio, and for the audio samples
   * it holds for later segments.
   */
  long heldBytes() {
    return heldFragments().stream().mapToLong(Fragment::heldBytes).sum();
  }

  /** The audio samples dropped because they arrived after their segment closed. */
  long droppedLateAudioSampleCount() {
    return audio.droppedLateSampleCount();
  }

  /**
   * Drops every fragment the assembler holds, which the producer then never delivers: those of the
   * open segment, those of the segments awaiting audio, and the audio samples held for later ones.
   */
  void discardHeldFragments() {
    grouper.discardOpenFragments();
    awaitingAudio.clear();
    audio.discard();
  }

  private Fragment rewritten(Fragment fragment) {
    return sampleRewrite.map(rewrite -> rewrite.rewrite(fragment)).orElse(fragment);
  }

  private Fragment checkedForShortSamples(Fragment fragment) {
    return encodedFrameRate
        .map(frameRate -> frameRate.requireNoShortVideoSample(fragment))
        .orElse(fragment);
  }

  private Closing closingOf(Fragment fragment) {
    audio
        .add(fragment)
        .ifPresent(
            sample ->
                video.addAudioArrival(
                    sample.presentationTime(),
                    fragment.tracks().audio().orElseThrow().timescale()));
    video.add(fragment);
    return switch (grouper.accept(fragment)) {
      case NothingClosed _ -> Closing.of(closeEveryCompleted());
      case SegmentsClosed(var segments) -> {
        awaitAudio(segments);
        yield Closing.of(closeEveryCompleted());
      }
      case SegmentNumberSkipped skipped -> {
        awaitAudio(skipped.closedSegments());
        yield new Closing(closeEveryAwaiting(), Optional.of(skipped.failure()));
      }
    };
  }

  private void awaitAudio(List<MediaSegment> segments) {
    segments.stream().map(AwaitingSegment::of).forEach(awaitingAudio::addLast);
  }

  // Closes each segment awaiting audio, in order, once no audio of its interval can still arrive.
  private List<ClosedSegment> closeEveryCompleted() {
    var closed = new ArrayList<ClosedSegment>();
    while (!awaitingAudio.isEmpty() && hasPassed(awaitingAudio.getFirst().sequenceNumber())) {
      closed.add(closeFirstAwaiting(List.of()));
    }

    return closed;
  }

  private boolean hasPassed(long sequenceNumber) {
    return audio.hasPassed(sequenceNumber) || video.hasPassed(sequenceNumber);
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
  private ClosedSegment closeFirstAwaiting(List<CarriedSample> furtherAudio) {
    var segment = awaitingAudio.removeFirst();
    var audioSamples = new ArrayList<>(audio.close(segment.sequenceNumber()));
    audioSamples.addAll(furtherAudio);
    var rebuilt =
        RebuiltSegment.builder()
            .sequenceNumber(segment.sequenceNumber())
            .videoSamples(segment.videoSamples())
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
    awaitingAudio.forEach(
        segment -> segment.videoSamples().forEach(sample -> held.add(sample.fragment())));
    audio.heldFragments().forEach(held::add);
    return held;
  }

  // Keeps only the video samples, which reference the fragments that carried them, so that the
  // assembler references exactly the fragments heldFragments() counts. The audio queue holds the
  // fragments of the audio the segment still takes; the grouper's other fragments of it, such as
  // an audio-only fragment of an earlier segment's audio, hold nothing it delivers.
  private record AwaitingSegment(int sequenceNumber, List<CarriedSample> videoSamples) {

    static AwaitingSegment of(MediaSegment segment) {
      return new AwaitingSegment(segment.sequenceNumber(), segment.videoSamples());
    }
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
