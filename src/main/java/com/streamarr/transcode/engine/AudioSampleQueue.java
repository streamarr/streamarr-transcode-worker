package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
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
 * VideoProgress}).
 */
final class AudioSampleQueue {

  private final int periodSeconds;
  private final int startSequenceNumber;
  // Every held sample and its segment number, in arrival order, which is decode order.
  private final Deque<Queued> held = new ArrayDeque<>();
  private long firstOpenNumber;
  private long reachedNumber = Long.MIN_VALUE;
  private boolean withoutSoundTrack;
  // Before any audio sample, none has run backwards.
  private long lastPresentationTime = Long.MIN_VALUE;
  private long droppedLateSampleCount;

  AudioSampleQueue(int periodSeconds, int startSequenceNumber) {
    this.periodSeconds = periodSeconds;
    this.startSequenceNumber = startSequenceNumber;
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

  /** Removes every sample still held, which the end of the output leaves to the last segment. */
  List<CarriedSample> closeAll() {
    var samples = held.stream().map(Queued::sample).toList();
    held.clear();
    return samples;
  }

  /** The audio samples dropped because they arrived after their segment closed. */
  long droppedLateSampleCount() {
    return droppedLateSampleCount;
  }

  Stream<Fragment> heldFragments() {
    return held.stream().map(queued -> queued.sample().fragment());
  }

  void discard() {
    held.clear();
  }

  // Holds the sample for its segment unless it is preroll or late; false when it drops it.
  private boolean tryHold(Fragment fragment, Sample sample) {
    requireNoRegression(sample);
    var number = numberOf(sample.presentationTime(), audioTimescaleOf(fragment));
    if (number < 0 && startSequenceNumber == 0) {
      number = 0;
    }

    if (number < startSequenceNumber) {
      return false;
    }

    if (number < firstOpenNumber) {
      droppedLateSampleCount++;
      return false;
    }

    held.addLast(new Queued(number, new CarriedSample(fragment, sample)));
    return true;
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
    return Math.floorDiv(presentationTime, periodSeconds * timescale);
  }

  private static long audioTimescaleOf(Fragment fragment) {
    return fragment.tracks().audio().orElseThrow().timescale();
  }

  private record Queued(long number, CarriedSample sample) {}
}
