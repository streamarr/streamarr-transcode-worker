package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.GroupingOutcome.NothingClosed;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentNumberSkipped;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentsClosed;
import java.nio.ByteBuffer;
import java.time.Duration;
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
 * when the audio outlasts the video. An AV1 encode's {@code av1C} takes the sequence header of the
 * first video sample, so the assembler holds that initialization segment back until the first
 * fragment that carries video.
 */
final class SegmentAssembler {

  private final SegmentIntervals intervals;
  private final SegmentGrouper grouper;
  private final AudioSampleQueue audio;
  private final VideoProgress video;
  // Segments whose video the grouper closed while audio of their interval may still arrive.
  private final Deque<AwaitingSegment> awaitingAudio = new ArrayDeque<>();
  private final long maximumSegmentBytes;
  private final Optional<OutputFrameRate> encodedFrameRate;
  private final VideoDecision videoDecision;
  private final boolean requiresSoundTrack;
  private final boolean copiesAudio;
  private final MediaTime minimumCopyDuration;
  private Optional<AudioTail> copiedAudioTail = Optional.empty();
  // Set by the initialization segment, which the reader returns before any fragment.
  private Optional<VideoSampleRewrite> sampleRewrite = Optional.empty();
  // An AV1 encode's initialization segment, held until the first fragment that carries video.
  private Optional<InitializationSegment> heldInitializationSegment = Optional.empty();

  /**
   * @param intervals the media segments' intervals, which decide the segment of each keyframe and
   *     audio sample
   * @param startSequenceNumber the first segment this job attempt delivers; segments below it are
   *     preroll and are discarded
   * @param maximumSegmentBytes the largest media segment the assembler delivers, and the most bytes
   *     of fragments it holds for one
   * @param encodedFrameRate the frame rate an attempt that encodes video forces on its output, so
   *     that the assembler refuses a fragment holding a video sample shorter than half a frame;
   *     empty when the attempt copies the video, whose sample durations follow the source
   * @param videoDecision the video that the initialization segment must declare
   * @param requiresSoundTrack whether the initialization segment must declare a sound track
   * @param copiesAudio whether the attempt copies the source's audio packets, so that the assembler
   *     refuses a media segment whose video outlasts that audio when it requires a sound track
   * @param paddedAudioEnd the advertised end of an attempt that pads its audio with silence, at or
   *     after which the assembler drops every audio sample; empty for any other attempt
   */
  @Builder
  private SegmentAssembler(
      @NonNull SegmentIntervals intervals,
      int startSequenceNumber,
      long maximumSegmentBytes,
      @NonNull Optional<OutputFrameRate> encodedFrameRate,
      @NonNull VideoDecision videoDecision,
      boolean requiresSoundTrack,
      boolean copiesAudio,
      @NonNull Optional<Duration> paddedAudioEnd) {
    this.intervals = intervals;
    this.grouper = new SegmentGrouper(intervals, startSequenceNumber, maximumSegmentBytes);
    this.audio = new AudioSampleQueue(intervals, startSequenceNumber, paddedAudioEnd);
    this.video = new VideoProgress(intervals);
    this.maximumSegmentBytes = maximumSegmentBytes;
    this.encodedFrameRate = encodedFrameRate;
    this.videoDecision = videoDecision;
    this.requiresSoundTrack = requiresSoundTrack;
    this.copiesAudio = copiesAudio;
    this.minimumCopyDuration = intervals.shortestInterval();
  }

  /**
   * Reports the segments the unit closes, in order. A keyframe that skips a segment number closes
   * the open segment and every segment awaiting audio, and the assembler reports them with the
   * failure that ends the output once they are delivered.
   *
   * @throws FragmentedMp4Exception when the initialization segment it delivers declares no sound
   *     track that it requires or other video than the decision, when the first video sample of an
   *     AV1 encode holds no sequence header that matches its {@code av1C}, when a video sample that
   *     the copy rewrites cannot be read, when the grouper refuses the fragment, when it holds a
   *     short video sample or an audio sample that starts before the one before it, when the
   *     rewritten initialization segment or a segment it closes is larger than the segment cap once
   *     rebuilt, or when a segment it closes has video beyond the copied audio it requires, after
   *     which nothing is delivered, not even the segments it would close
   */
  Closing accept(@NonNull Mp4Unit unit) {
    return switch (unit) {
      case InitializationSegment initializationSegment -> {
        var rewritten = InitializationSegmentRewrite.rewrite(videoDecision, initializationSegment);
        var checked = delivered(initializationSegment, rewritten);
        if (InitializationSegmentRewrite.awaitsFirstVideoSample(
            videoDecision, initializationSegment)) {
          // The checks have refused at once what the first video sample cannot change.
          heldInitializationSegment = Optional.of(initializationSegment);
          yield Closing.of(List.of());
        }

        yield Closing.of(List.of(checked));
      }
      case Fragment fragment -> closingOf(fragment);
    };
  }

  private ClosedSegment.Initialization delivered(
      InitializationSegment read, InitializationSegment delivered) {
    requireWithinTheCap(delivered);
    requireSoundTrack(delivered);
    VideoOutputCheck.requireMatch(videoDecision, delivered);
    sampleRewrite = VideoSampleRewrite.of(videoDecision, delivered, maximumSegmentBytes);
    return new ClosedSegment.Initialization(delivered, read.bytes().length);
  }

  // The held initialization segment goes out ahead of whatever the first fragment with video
  // closes.
  private Closing closingOf(Fragment fragment) {
    var initialization = initializationReleasedBy(fragment);
    var closing = closingOfRewritten(checkedForShortSamples(rewritten(fragment)));
    if (initialization.isEmpty()) {
      return closing;
    }

    var closed = new ArrayList<ClosedSegment>();
    closed.add(initialization.get());
    closed.addAll(closing.closedSegments());
    return new Closing(closed, closing.failure());
  }

  private Optional<ClosedSegment.Initialization> initializationReleasedBy(Fragment fragment) {
    if (heldInitializationSegment.isEmpty() || fragment.videoSamples().isEmpty()) {
      return Optional.empty();
    }

    var held = heldInitializationSegment.get();
    heldInitializationSegment = Optional.empty();
    var firstVideoSample = fragment.videoSamples().getFirst();
    var sample =
        ByteBuffer.wrap(
                fragment.mediaDataBox(),
                firstVideoSample.mediaDataPosition(),
                Math.toIntExact(firstVideoSample.size()))
            .slice();
    return Optional.of(
        delivered(held, InitializationSegmentRewrite.rewrite(videoDecision, held, sample)));
  }

  private void requireSoundTrack(InitializationSegment segment) {
    if (requiresSoundTrack && !CodecConfigurationReader.declaresSoundTrack(segment)) {
      throw new FragmentedMp4Exception(
          Reason.NO_SOUND_TRACK, "the initialization segment declares no sound track");
    }
  }

  /**
   * Ends the grouping at the end of the output and drops the audio samples after the last segment's
   * interval, which belong to segments that no keyframe opened because the video ended first, and
   * an AV1 encode's initialization segment held for a video sample that never came. The assembler
   * then holds only what the segments that {@link #finish()} closes need.
   */
  void dropAudioAfterTheLastSegment() {
    heldInitializationSegment = Optional.empty();
    grouper.finish().map(AwaitingSegment::lastOf).ifPresent(awaitingAudio::addLast);
    if (!awaitingAudio.isEmpty()) {
      audio.discardAfter(awaitingAudio.getLast().sequenceNumber());
    }
  }

  /**
   * The segments the end of the output closes: every one awaiting audio, and the last, each with
   * only the audio of its own interval; the audio after the last segment's interval is dropped
   * first.
   *
   * @throws FragmentedMp4Exception when one is larger than the segment cap once rebuilt, or has
   *     video beyond the copied audio it requires
   */
  List<ClosedSegment> finish() {
    dropAudioAfterTheLastSegment();
    var closed = closeEveryAwaiting();
    audio.discard();
    return closed;
  }

  /**
   * The memory of the units the assembler holds: the bytes of an initialization segment it holds
   * back, and the fragments' bytes and their samples' heap: for the open segment, or for the first
   * one, for the segments awaiting audio, and for the audio samples it holds for later segments.
   */
  long heldBytes() {
    return heldInitializationSegment.map(segment -> (long) segment.bytes().length).orElse(0L)
        + heldFragments().stream().mapToLong(Fragment::heldBytes).sum();
  }

  /** The audio samples dropped because they arrived after their segment closed. */
  long droppedLateAudioSampleCount() {
    return audio.droppedLateSampleCount();
  }

  /**
   * Drops every unit the assembler holds, which the producer then never delivers: an initialization
   * segment it holds back, the fragments of the open segment, those of the segments awaiting audio,
   * and the audio samples held for later ones.
   */
  void discardHeldFragments() {
    heldInitializationSegment = Optional.empty();
    grouper.discardOpenFragments();
    awaitingAudio.clear();
    audio.discard();
  }

  // The reader admitted the segment as FFmpeg wrote it, before the rewrite could lengthen it.
  private void requireWithinTheCap(InitializationSegment delivered) {
    if (delivered.bytes().length > maximumSegmentBytes) {
      throw new FragmentedMp4Exception(
          Reason.EXCEEDS_SEGMENT_CAP,
          "the rewritten initialization segment takes "
              + delivered.bytes().length
              + " bytes, over the segment cap of "
              + maximumSegmentBytes);
    }
  }

  private Fragment rewritten(Fragment fragment) {
    return sampleRewrite.map(rewrite -> rewrite.rewrite(fragment)).orElse(fragment);
  }

  private Fragment checkedForShortSamples(Fragment fragment) {
    return encodedFrameRate
        .map(frameRate -> frameRate.requireNoShortVideoSample(fragment))
        .orElse(fragment);
  }

  private Closing closingOfRewritten(Fragment fragment) {
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
    requireCopiedAudio(segment, audioSamples);

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

  private void requireCopiedAudio(AwaitingSegment segment, List<CarriedSample> samples) {
    if (!copiesAudio || !requiresSoundTrack) {
      return;
    }

    if (!samples.isEmpty()) {
      copiedAudioTail = Optional.of(audio.tailOf(samples.getLast()));
    }

    if (videoDecision instanceof VideoDecision.Copy && !samples.isEmpty()) {
      return;
    }

    var videoSamples = segment.videoSamples();
    var timescale = videoSamples.getFirst().fragment().tracks().video().orElseThrow().timescale();
    var lastPictureEnd =
        videoSamples.stream()
            .mapToLong(
                carried ->
                    Math.addExact(carried.sample().presentationTime(), carried.sample().duration()))
            .max()
            .orElseThrow();
    // The segment takes only the audio that starts inside its interval, so the audio beside video
    // that a late keyframe leaves past the interval's end belongs to the next segment. The output's
    // last segment has no next segment to carry it.
    var end = lastPictureEnd;
    if (!segment.lastOfTheOutput()) {
      end = Math.min(end, intervals.endOf(segment.sequenceNumber(), timescale).orElse(end));
    }

    var videoEnd = new MediaTime(end, new TimeBase(1, timescale));
    // The audio after the output's last segment's interval was dropped, because no keyframe opened
    // the interval it belongs to; it still shows how far the copied audio reaches beside the
    // segment's pictures past its interval.
    var audioTail = audio.discardedTail().or(() -> copiedAudioTail.or(audio::prerollTail));
    if (audioTail.filter(tail -> tail.end().compareTo(videoEnd) >= 0).isPresent()) {
      return;
    }

    var frameDuration =
        videoSamples.stream().mapToLong(carried -> carried.sample().duration()).max().orElseThrow();
    var beforeLastFrame = new MediaTime(end - frameDuration, new TimeBase(1, timescale));
    if (videoDecision instanceof VideoDecision.Encode
        && audioTail
            .filter(tail -> tail.coversToWithinOneFrame(videoEnd, beforeLastFrame))
            .isPresent()) {
      return;
    }

    // A short copy may end with one final picture after its last audio packet. That still takes
    // the normal short-copy path; a complete interval without audio needs an audio encode, and so
    // does any segment that a later one follows. The interval counts as complete when its video
    // ends within one video frame of its end, the frame tolerance of encoded video, so that times
    // rounded to the millisecond do not make a full interval look short.
    if (videoDecision instanceof VideoDecision.Copy
        && segment.lastOfTheOutput()
        && endsBeforeItsIntervalsEnd(
            segment, Math.addExact(lastPictureEnd, frameDuration), timescale)) {
      return;
    }

    throw new FragmentedMp4Exception(
        Reason.COPIED_AUDIO_RAN_SHORT, "a media segment has video beyond its copied audio");
  }

  // Whether video that ends at this tick ends before the segment's own interval does, wherever its
  // first picture starts: a keyframe can come late, and a picture can be presented before the
  // keyframe it follows. The last listed segment of a job without an advertised end runs to the end
  // of the media and has no interval end, so its video is measured against the job's shortest
  // interval.
  private boolean endsBeforeItsIntervalsEnd(
      AwaitingSegment segment, long videoEnd, long timescale) {
    var intervalEnd = intervals.endOf(segment.sequenceNumber(), timescale);
    if (intervalEnd.isPresent()) {
      return videoEnd < intervalEnd.getAsLong();
    }

    var start =
        segment.videoSamples().stream()
            .mapToLong(carried -> carried.sample().presentationTime())
            .min()
            .orElseThrow();
    return new MediaTime(videoEnd - start, new TimeBase(1, timescale))
            .compareTo(minimumCopyDuration)
        < 0;
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
  private record AwaitingSegment(
      int sequenceNumber, List<CarriedSample> videoSamples, boolean lastOfTheOutput) {

    static AwaitingSegment of(MediaSegment segment) {
      return new AwaitingSegment(segment.sequenceNumber(), segment.videoSamples(), false);
    }

    static AwaitingSegment lastOf(MediaSegment segment) {
      return new AwaitingSegment(segment.sequenceNumber(), segment.videoSamples(), true);
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
