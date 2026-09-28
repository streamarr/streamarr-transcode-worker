package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.RebuiltSegment.SampleOf;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.stream.Stream;

/**
 * Holds each audio sample a job attempt delivers until the media segment whose interval holds the
 * sample's start closes, whichever fragment carried it, and tracks how far the audio has certainly
 * arrived. A sample before the attempt's first segment is preroll and is dropped, except that an
 * attempt from segment zero keeps the AAC priming frame, which starts before zero, in segment zero.
 */
final class AudioSampleQueue {

  private final int periodSeconds;
  private final int startSequenceNumber;
  // Every held sample and its segment number, in arrival order, which is decode order.
  private final Deque<Queued> held = new ArrayDeque<>();
  private long firstOpenNumber;
  private long reachedNumber = Long.MIN_VALUE;

  AudioSampleQueue(int periodSeconds, int startSequenceNumber) {
    this.periodSeconds = periodSeconds;
    this.startSequenceNumber = startSequenceNumber;
    this.firstOpenNumber = startSequenceNumber;
  }

  /**
   * Holds the fragment's audio samples for their segments. FFmpeg interleaves its packets in decode
   * order, so once an audio sample starts in segment N, or a fragment without audio starts its
   * video in segment N, no audio sample of an earlier segment follows.
   *
   * @throws FragmentedMp4Exception when an audio sample belongs to a segment that already closed
   */
  void add(Fragment fragment) {
    fragment.audioSamples().forEach(sample -> hold(fragment, sample));
    if (!fragment.audioSamples().isEmpty()) {
      reach(
          numberOf(
              fragment.audioSamples().getLast().presentationTime(), audioTimescaleOf(fragment)));
      return;
    }

    fragment
        .videoStart()
        .ifPresent(start -> reach(numberOf(start.presentationTime(), start.timescale())));
  }

  /** Whether no audio sample of segment {@code number} or before it can still arrive. */
  boolean hasPassed(long number) {
    return reachedNumber > number;
  }

  /**
   * Removes the samples held for this segment and every one before it, in order; no later sample
   * may belong to them.
   */
  List<SampleOf> close(long number) {
    var samples = new ArrayList<SampleOf>();
    while (!held.isEmpty() && held.peekFirst().number() <= number) {
      samples.add(held.removeFirst().sample());
    }

    firstOpenNumber = number + 1;
    return samples;
  }

  /** Removes every sample still held, which the end of the output leaves to the last segment. */
  List<SampleOf> closeAll() {
    var samples = held.stream().map(Queued::sample).toList();
    held.clear();
    return samples;
  }

  Stream<Fragment> heldFragments() {
    return held.stream().map(queued -> queued.sample().fragment());
  }

  void discard() {
    held.clear();
  }

  private void hold(Fragment fragment, Sample sample) {
    var number = numberOf(sample.presentationTime(), audioTimescaleOf(fragment));
    if (number < 0 && startSequenceNumber == 0) {
      number = 0;
    }

    if (number < startSequenceNumber) {
      return;
    }

    if (number < firstOpenNumber) {
      throw new FragmentedMp4Exception(
          Reason.LATE_AUDIO_SAMPLE,
          "an audio sample at "
              + sample.presentationTime()
              + " belongs to segment "
              + number
              + ", which already closed");
    }

    held.addLast(new Queued(number, new SampleOf(fragment, sample)));
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

  private record Queued(long number, SampleOf sample) {}
}
