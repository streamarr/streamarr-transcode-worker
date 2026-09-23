package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import lombok.NonNull;

/**
 * Groups fragments into media segments on the zero-based interval grid: segment N holds the
 * fragments whose first video sample is a keyframe inside media time [N × period, (N + 1) ×
 * period), with the fragments that follow them before the next such keyframe.
 */
final class SegmentGrouper {

  private final int periodSeconds;
  private final int startSequenceNumber;
  private final List<Fragment> openFragments = new ArrayList<>();
  private OptionalLong openSequenceNumber = OptionalLong.empty();
  private OptionalLong lastKeyframeTime = OptionalLong.empty();

  /**
   * @param startSequenceNumber the first segment this job attempt delivers; segments below it are
   *     preroll and are discarded
   */
  SegmentGrouper(int periodSeconds, int startSequenceNumber) {
    if (periodSeconds <= 0 || startSequenceNumber < 0) {
      throw new IllegalArgumentException(
          "Period must be positive and start sequence number non-negative, got: "
              + periodSeconds
              + " and "
              + startSequenceNumber);
    }

    this.periodSeconds = periodSeconds;
    this.startSequenceNumber = startSequenceNumber;
  }

  /**
   * Adds a fragment in arrival order and returns the media segment it closed, if any. A closed
   * preroll segment is discarded rather than returned.
   *
   * @throws FragmentedMp4Exception when the fragment's keyframe skips a segment number or starts
   *     before the previous keyframe
   */
  Optional<MediaSegment> accept(@NonNull Fragment fragment) {
    var keyframeStart = fragment.videoStart().filter(VideoStart::syncSample);
    if (keyframeStart.isEmpty()) {
      openFragments.add(fragment);
      return Optional.empty();
    }

    var sequenceNumber = sequenceNumberOf(keyframeStart.orElseThrow());
    if (openSequenceNumber.equals(OptionalLong.of(sequenceNumber))) {
      openFragments.add(fragment);
      return Optional.empty();
    }

    requireNoSkippedNumber(sequenceNumber);
    var closed = close();
    openSequenceNumber = OptionalLong.of(sequenceNumber);
    openFragments.add(fragment);
    return closed;
  }

  /** Closes the open segment at the end of the stream; empty when it is preroll or none opened. */
  Optional<MediaSegment> finish() {
    return close();
  }

  private long sequenceNumberOf(VideoStart start) {
    var presentationTime = start.presentationTime();
    if (lastKeyframeTime.isPresent() && presentationTime < lastKeyframeTime.getAsLong()) {
      throw new FragmentedMp4Exception(
          Reason.PRESENTATION_TIME_REGRESSED,
          "keyframe at "
              + presentationTime
              + " follows a keyframe at "
              + lastKeyframeTime.getAsLong());
    }

    lastKeyframeTime = OptionalLong.of(presentationTime);
    return Math.floorDiv(presentationTime, periodSeconds * start.timescale());
  }

  private void requireNoSkippedNumber(long sequenceNumber) {
    var nextDeliverable = (long) startSequenceNumber;
    if (openSequenceNumber.isPresent()) {
      nextDeliverable = Math.max(startSequenceNumber, openSequenceNumber.getAsLong() + 1);
    }

    if (sequenceNumber > nextDeliverable) {
      throw new FragmentedMp4Exception(
          Reason.SKIPPED_SEGMENT_NUMBER,
          "a keyframe opens segment "
              + sequenceNumber
              + " while segment "
              + nextDeliverable
              + " has none");
    }
  }

  private Optional<MediaSegment> close() {
    if (openSequenceNumber.isEmpty()) {
      return Optional.empty();
    }

    var sequenceNumber = openSequenceNumber.getAsLong();
    var fragments = List.copyOf(openFragments);
    openFragments.clear();
    openSequenceNumber = OptionalLong.empty();
    if (sequenceNumber < startSequenceNumber) {
      return Optional.empty();
    }

    return Optional.of(new MediaSegment(Math.toIntExact(sequenceNumber), fragments));
  }
}
