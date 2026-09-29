package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.GroupingOutcome.NothingClosed;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentNumberSkipped;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentsClosed;
import com.streamarr.transcode.engine.RebuiltSegment.SampleOf;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.NonNull;

/**
 * Groups fragments, in arrival order, into the media segments of ADR 0037's zero-based interval
 * grid. A keyframe that opens the next segment closes the open one, even inside a fragment, and the
 * end of the stream closes the last; segments below the start sequence number are preroll and are
 * never returned. A keyframe that skips a segment number closes the open segment and ends the
 * grouping.
 */
final class SegmentGrouper {

  private final int periodSeconds;
  private final int startSequenceNumber;
  private final long maximumSegmentBytes;
  private final List<Fragment> openFragments = new ArrayList<>();
  private final List<SampleOf> openVideoSamples = new ArrayList<>();
  private long openBytes;
  private OptionalLong openSequenceNumber = OptionalLong.empty();
  private OptionalLong lastKeyframeTime = OptionalLong.empty();
  private boolean ended;

  /**
   * @param startSequenceNumber the first segment this job attempt delivers; segments below it are
   *     preroll and are discarded
   * @param maximumSegmentBytes the most bytes of FFmpeg's fragments the grouper holds for one media
   *     segment, as read, which bounds what it holds rather than the segment rebuilt from them
   */
  SegmentGrouper(int periodSeconds, int startSequenceNumber, long maximumSegmentBytes) {
    if (periodSeconds <= 0 || startSequenceNumber < 0) {
      throw new IllegalArgumentException(
          "Period must be positive and start sequence number non-negative, got: "
              + periodSeconds
              + " and "
              + startSequenceNumber);
    }

    if (maximumSegmentBytes <= 0) {
      throw new IllegalArgumentException(
          "Segment cap must be positive, got: " + maximumSegmentBytes);
    }

    this.periodSeconds = periodSeconds;
    this.startSequenceNumber = startSequenceNumber;
    this.maximumSegmentBytes = maximumSegmentBytes;
  }

  /**
   * Adds a fragment in arrival order and reports the media segments its keyframes closed, if any. A
   * keyframe opens its segment wherever it lies in the fragment: the video samples before it belong
   * to the segment it closes, so both segments hold the fragment. A closed preroll segment is
   * discarded rather than reported. A keyframe that skips a segment number closes the open segment
   * too, and the grouper reports the segments closed so far with the skip and takes no further
   * fragment.
   *
   * @throws FragmentedMp4Exception when one of the fragment's keyframes starts before the previous
   *     keyframe, or when the fragment would make a segment it joins larger than the segment cap
   * @throws IllegalStateException after a keyframe skipped a segment number
   */
  GroupingOutcome accept(@NonNull Fragment fragment) {
    requireNotEnded();
    var samples = SampleOf.videoOf(fragment).toList();
    var closed = new ArrayList<MediaSegment>();
    var firstOpenSample = 0;
    for (var index = 0; index < samples.size(); index++) {
      var opened = segmentNumberOpenedBy(samples.get(index));
      if (opened.isEmpty()) {
        continue;
      }

      var sequenceNumber = opened.getAsLong();
      firstOpenSample = joinSamplesBefore(samples, firstOpenSample, index);
      var nextDeliverable = nextDeliverableNumber();
      close().ifPresent(closed::add);
      if (sequenceNumber > nextDeliverable) {
        ended = true;
        discardOpenFragments();
        return new SegmentNumberSkipped(closed, nextDeliverable, sequenceNumber);
      }

      openSequenceNumber = OptionalLong.of(sequenceNumber);
      if (isPrerollOpen()) {
        discardOpenFragments();
      }
    }

    join(fragment, samples.subList(firstOpenSample, samples.size()));
    if (closed.isEmpty()) {
      return new NothingClosed();
    }

    return new SegmentsClosed(closed);
  }

  /**
   * Closes the open segment at the end of the stream; empty when it is preroll or none opened.
   *
   * @throws IllegalStateException after a keyframe skipped a segment number
   */
  Optional<MediaSegment> finish() {
    requireNotEnded();
    return close();
  }

  /** The bytes of the fragments the grouper holds for the open segment, or for the first one. */
  long heldBytes() {
    return openBytes;
  }

  /** The fragments the grouper holds for the open segment, or for the first one. */
  List<Fragment> heldFragments() {
    return List.copyOf(openFragments);
  }

  private void requireNotEnded() {
    if (ended) {
      throw new IllegalStateException("grouping ended at a skipped segment number");
    }
  }

  /** The number of the segment a keyframe opens; empty for a keyframe of the open segment. */
  private OptionalLong segmentNumberOpenedBy(SampleOf sample) {
    var keyframe =
        sample
            .fragment()
            .tracks()
            .video()
            .map(track -> track.startOf(sample.sample()))
            .filter(VideoStart::syncSample);
    if (keyframe.isEmpty()) {
      return OptionalLong.empty();
    }

    requireNoRegression(keyframe.orElseThrow());
    lastKeyframeTime = OptionalLong.of(keyframe.orElseThrow().presentationTime());
    var sequenceNumber = sequenceNumberOf(keyframe.orElseThrow());
    if (openSequenceNumber.equals(OptionalLong.of(sequenceNumber))) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(sequenceNumber);
  }

  /**
   * Joins the fragment's video samples before a keyframe that opens a segment to the open segment,
   * and returns the position of the first sample left for the segment the keyframe opens. Before
   * the first keyframe no segment is open, and those samples belong to the one it opens.
   */
  private int joinSamplesBefore(List<SampleOf> samples, int firstOpenSample, int keyframe) {
    if (openSequenceNumber.isEmpty()) {
      return firstOpenSample;
    }

    if (keyframe > firstOpenSample) {
      join(samples.get(keyframe).fragment(), samples.subList(firstOpenSample, keyframe));
    }

    return keyframe;
  }

  /**
   * Holds a fragment and those of its video samples that belong to the open segment, or to the
   * first one; drops them when that is preroll.
   */
  private void join(Fragment fragment, List<SampleOf> videoSamples) {
    if (isPrerollOpen()) {
      return;
    }

    var bytes = fragment.byteLength();
    if (bytes > maximumSegmentBytes - openBytes) {
      throw new FragmentedMp4Exception(
          Reason.EXCEEDS_SEGMENT_CAP,
          "a fragment of "
              + bytes
              + " bytes after "
              + openBytes
              + " exceeds the segment cap of "
              + maximumSegmentBytes);
    }

    openFragments.add(fragment);
    openVideoSamples.addAll(videoSamples);
    openBytes += bytes;
  }

  private boolean isPrerollOpen() {
    return openSequenceNumber.isPresent() && openSequenceNumber.getAsLong() < startSequenceNumber;
  }

  /** Drops the fragments held for the open segment, which the producer then never delivers. */
  void discardOpenFragments() {
    openFragments.clear();
    openVideoSamples.clear();
    openBytes = 0;
  }

  private void requireNoRegression(VideoStart keyframe) {
    var presentationTime = keyframe.presentationTime();
    if (lastKeyframeTime.isPresent() && presentationTime < lastKeyframeTime.getAsLong()) {
      throw new FragmentedMp4Exception(
          Reason.PRESENTATION_TIME_REGRESSED,
          "keyframe at "
              + presentationTime
              + " follows a keyframe at "
              + lastKeyframeTime.getAsLong());
    }
  }

  private long sequenceNumberOf(VideoStart keyframe) {
    return Math.floorDiv(keyframe.presentationTime(), periodSeconds * keyframe.timescale());
  }

  private long nextDeliverableNumber() {
    if (openSequenceNumber.isEmpty()) {
      return startSequenceNumber;
    }

    return Math.max(startSequenceNumber, openSequenceNumber.getAsLong() + 1);
  }

  private Optional<MediaSegment> close() {
    if (openSequenceNumber.isEmpty()) {
      return Optional.empty();
    }

    var sequenceNumber = openSequenceNumber.getAsLong();
    var segment =
        new MediaSegment(Math.toIntExact(sequenceNumber), openFragments, openVideoSamples);
    discardOpenFragments();
    openSequenceNumber = OptionalLong.empty();
    if (sequenceNumber < startSequenceNumber) {
      return Optional.empty();
    }

    return Optional.of(segment);
  }
}
