package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.GroupingOutcome.NothingClosed;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentClosed;
import com.streamarr.transcode.engine.GroupingOutcome.SegmentNumberSkipped;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.NonNull;

/**
 * Groups fragments, in arrival order, into the media segments of ADR 0037's zero-based interval
 * grid. A keyframe that opens the next segment closes the open one, and the end of the stream
 * closes the last; segments below the start sequence number are preroll and are never returned. A
 * keyframe that skips a segment number closes the open segment and ends the grouping.
 */
final class SegmentGrouper {

  private final int periodSeconds;
  private final int startSequenceNumber;
  private final long maximumSegmentBytes;
  private final List<Fragment> openFragments = new ArrayList<>();
  private long openBytes;
  private OptionalLong openSequenceNumber = OptionalLong.empty();
  private OptionalLong lastKeyframeTime = OptionalLong.empty();
  private boolean ended;

  /**
   * @param startSequenceNumber the first segment this job attempt delivers; segments below it are
   *     preroll and are discarded
   * @param maximumSegmentBytes the largest media segment the grouper assembles
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
   * Adds a fragment in arrival order and reports the media segment its keyframe closed, if any. A
   * closed preroll segment is discarded rather than reported. A keyframe that skips a segment
   * number closes the open segment too, and the grouper reports that segment with the skip and
   * takes no further fragment.
   *
   * @throws FragmentedMp4Exception when the fragment's keyframe starts before the previous
   *     keyframe, or when the fragment would make the segment it joins larger than the segment cap
   * @throws IllegalStateException after a keyframe skipped a segment number
   */
  GroupingOutcome accept(@NonNull Fragment fragment) {
    requireNotEnded();
    var keyframeStart = fragment.videoStart().filter(VideoStart::syncSample);
    if (keyframeStart.isEmpty()) {
      join(fragment);
      return new NothingClosed();
    }

    var keyframe = keyframeStart.orElseThrow();
    requireNoRegression(keyframe);
    lastKeyframeTime = OptionalLong.of(keyframe.presentationTime());
    var sequenceNumber = sequenceNumberOf(keyframe);
    if (openSequenceNumber.equals(OptionalLong.of(sequenceNumber))) {
      join(fragment);
      return new NothingClosed();
    }

    var nextDeliverable = nextDeliverableNumber();
    var closed = close();
    if (sequenceNumber > nextDeliverable) {
      ended = true;
      discardOpenFragments();
      return new SegmentNumberSkipped(closed, nextDeliverable, sequenceNumber);
    }

    openSequenceNumber = OptionalLong.of(sequenceNumber);
    if (isPrerollOpen()) {
      discardOpenFragments();
    }

    join(fragment);
    return closed.<GroupingOutcome>map(SegmentClosed::new).orElseGet(NothingClosed::new);
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

  private void requireNotEnded() {
    if (ended) {
      throw new IllegalStateException("grouping ended at a skipped segment number");
    }
  }

  /** Holds a fragment for the open segment, or for the first one; drops it when that is preroll. */
  private void join(Fragment fragment) {
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
    openBytes += bytes;
  }

  private boolean isPrerollOpen() {
    return openSequenceNumber.isPresent() && openSequenceNumber.getAsLong() < startSequenceNumber;
  }

  private void discardOpenFragments() {
    openFragments.clear();
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
    var fragments = List.copyOf(openFragments);
    discardOpenFragments();
    openSequenceNumber = OptionalLong.empty();
    if (sequenceNumber < startSequenceNumber) {
      return Optional.empty();
    }

    return Optional.of(new MediaSegment(Math.toIntExact(sequenceNumber), fragments));
  }
}
