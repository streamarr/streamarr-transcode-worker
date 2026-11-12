package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;

/**
 * Holds each audio sample a job attempt delivers until the media segment whose interval holds the
 * sample's start closes, whichever fragment carried it, and tracks how far the audio has certainly
 * arrived. A sample before the attempt's first segment is preroll and is dropped, except that an
 * attempt from segment zero keeps the AAC priming frame, which starts before zero, in segment zero.
 * A sample that arrives after its segment closed is dropped and counted: when encoded audio ends
 * long before the video, the frames still inside FFmpeg's encoding pipeline, and the encoder's last
 * frames, which come out only once the whole input has ended, arrive after the video has run past
 * their segment. A keyframe fragment does not show how far the audio has arrived: FFmpeg can write
 * one without audio past a boundary and the audio before that boundary in the fragment after it.
 * While the audio lasts, an audio sample shows it; once the audio ends, only the video does ({@link
 * VideoProgress}). An attempt that pads its audio with silence drops each sample that starts at or
 * after the advertised end: FFmpeg runs the padding past that end, so that the encoder writes the
 * frame that holds it whole.
 */
final class AudioSampleQueue {

  private final SegmentIntervals intervals;
  private final int startSequenceNumber;
  private final Optional<Duration> paddedAudioEnd;
  // Every held sample and its segment number, in arrival order, which is decode order.
  private final Deque<Queued> held = new ArrayDeque<>();
  private long firstOpenNumber;
  private long reachedNumber = Long.MIN_VALUE;
  private boolean withoutSoundTrack;
  // Before any audio sample, none has run backwards.
  private long lastPresentationTime = Long.MIN_VALUE;
  private long droppedLateSampleCount;
  // How many of the samples that arrived before the latest last each duration. The most common
  // duration is one full audio frame: an encoder shortens only the audio's last frame, which
  // arrives last, the sample before a gap in the audio lasts longer than a frame, and a source's
  // times rounded to the millisecond shorten or lengthen only a few copied frames.
  private final Map<Long, Long> earlierSampleDurationCounts = new HashMap<>();
  private OptionalLong latestSampleDuration = OptionalLong.empty();
  private Optional<AudioTail> prerollTail = Optional.empty();
  private Optional<AudioTail> discardedTail = Optional.empty();

  /**
   * @param paddedAudioEnd the advertised end of an attempt that pads its audio with silence; empty
   *     for any other attempt
   */
  AudioSampleQueue(
      SegmentIntervals intervals, int startSequenceNumber, Optional<Duration> paddedAudioEnd) {
    this.intervals = intervals;
    this.startSequenceNumber = startSequenceNumber;
    this.paddedAudioEnd = paddedAudioEnd;
    this.firstOpenNumber = startSequenceNumber;
  }

  /**
   * Holds the fragment's audio samples for their segments and returns the earliest it holds. The
   * sound track's samples arrive in decode order, which for audio is presentation order, so once an
   * audio sample starts in segment N, no audio sample of an earlier segment follows.
   *
   * @throws FragmentedMp4Exception when an audio sample starts before the one that arrived before
   *     it
   */
  Optional<Sample> add(Fragment fragment) {
    withoutSoundTrack = fragment.tracks().audio().isEmpty();
    var earliestHeld = Optional.<Sample>empty();
    for (var sample : fragment.audioSamples()) {
      if (tryHold(fragment, sample) && earliestHeld.isEmpty()) {
        earliestHeld = Optional.of(sample);
      }
    }

    if (fragment.audioSamples().isEmpty()) {
      return earliestHeld;
    }

    reach(
        numberOf(fragment.audioSamples().getLast().presentationTime(), audioTimescaleOf(fragment)));
    return earliestHeld;
  }

  /**
   * Whether no audio sample of segment {@code number} or before it can still arrive: an audio
   * sample of a later segment has arrived, or the movie has no sound track.
   */
  boolean hasPassed(long number) {
    return withoutSoundTrack || reachedNumber > number;
  }

  /**
   * Removes the samples held for this segment and every one before it, in order; no later sample
   * may belong to them.
   */
  List<CarriedSample> close(long number) {
    var samples = new ArrayList<CarriedSample>();
    while (!held.isEmpty() && held.peekFirst().number() <= number) {
      samples.add(held.removeFirst().sample());
    }

    firstOpenNumber = number + 1;
    return samples;
  }

  /** Drops the samples held for segments after this one. */
  void discardAfter(long number) {
    while (!held.isEmpty() && held.peekLast().number() > number) {
      var dropped = held.removeLast().sample();
      discardedTail = discardedTail.or(() -> Optional.of(tailOf(dropped)));
    }
  }

  /**
   * Where the latest sample that {@link #discardAfter} dropped ends; empty when it dropped none.
   */
  Optional<AudioTail> discardedTail() {
    return discardedTail;
  }

  /** The audio samples dropped because they arrived after their segment closed. */
  long droppedLateSampleCount() {
    return droppedLateSampleCount;
  }

  /**
   * Where the last preroll sample ends, which the segment before the attempt's first carries; empty
   * when no preroll sample arrived.
   */
  Optional<AudioTail> prerollTail() {
    return prerollTail;
  }

  Stream<Fragment> heldFragments() {
    return held.stream().map(queued -> queued.sample().fragment());
  }

  void discard() {
    held.clear();
  }

  // Holds the sample for its segment unless it is preroll, late or padding past the advertised end;
  // false when it drops it.
  private boolean tryHold(Fragment fragment, Sample sample) {
    requireNoRegression(sample);
    latestSampleDuration.ifPresent(
        latest -> earlierSampleDurationCounts.merge(latest, 1L, Long::sum));
    latestSampleDuration = OptionalLong.of(sample.duration());
    var timescale = audioTimescaleOf(fragment);
    if (startsAtOrAfterThePaddedEnd(sample.presentationTime(), timescale)) {
      return false;
    }

    var number = numberOf(sample.presentationTime(), timescale);
    if (number < 0 && startSequenceNumber == 0) {
      number = 0;
    }

    if (number < startSequenceNumber) {
      prerollTail = Optional.of(tailOf(sample, timescale));
      return false;
    }

    if (number < firstOpenNumber) {
      droppedLateSampleCount++;
      return false;
    }

    held.addLast(new Queued(number, new CarriedSample(fragment, sample)));
    return true;
  }

  private boolean startsAtOrAfterThePaddedEnd(long presentationTime, long timescale) {
    return paddedAudioEnd
        .filter(end -> presentationTime >= MediaTicks.firstTickAtOrAfter(end, timescale))
        .isPresent();
  }

  private void requireNoRegression(Sample sample) {
    var presentationTime = sample.presentationTime();
    if (presentationTime < lastPresentationTime) {
      throw new FragmentedMp4Exception(
          Reason.PRESENTATION_TIME_REGRESSED,
          "audio sample at " + presentationTime + " follows one at " + lastPresentationTime);
    }

    lastPresentationTime = presentationTime;
  }

  private void reach(long number) {
    reachedNumber = Math.max(reachedNumber, number);
  }

  private long numberOf(long presentationTime, long timescale) {
    return intervals.numberOfAudioSampleAt(presentationTime, timescale);
  }

  private static long audioTimescaleOf(Fragment fragment) {
    return fragment.tracks().audio().orElseThrow().timescale();
  }

  /**
   * Where the audio sample ends, and where one more full audio frame would end after it: a frame at
   * least as long as the sample and as the duration most samples that arrived before the latest
   * share, the shorter of two as common.
   */
  AudioTail tailOf(CarriedSample carried) {
    return tailOf(carried.sample(), audioTimescaleOf(carried.fragment()));
  }

  private AudioTail tailOf(Sample sample, long timescale) {
    var duration = sample.duration();
    return AudioTail.of(
        sample, timescale, Math.max(duration, mostCommonEarlierSampleDuration().orElse(duration)));
  }

  private Optional<Long> mostCommonEarlierSampleDuration() {
    return earlierSampleDurationCounts.entrySet().stream()
        .max(
            Entry.<Long, Long>comparingByValue()
                .thenComparing(Entry.comparingByKey(Comparator.reverseOrder())))
        .map(Entry::getKey);
  }

  private record Queued(long number, CarriedSample sample) {}
}
