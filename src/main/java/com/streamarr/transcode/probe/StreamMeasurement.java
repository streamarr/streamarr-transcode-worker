package com.streamarr.transcode.probe;

import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import com.streamarr.transcode.probe.MaximumFrameRate.MeasuredFrameRate;
import java.util.Optional;
import lombok.Builder;

/**
 * One stream's measurement (ADR 0054). {@code measuredDuration} counts ticks of {@code timeBase}.
 * {@code intervalBytes} and {@code intervalPacketCounts} each hold one unsigned 32-bit count for
 * each keyframe interval: the stream's bytes and its packets in that interval.
 */
final class StreamMeasurement {
  private final int streamIndex;
  private final long measuredBytes;
  private final long packetCount;
  private final TimeBase timeBase;
  private final long measuredDuration;
  private final int[] intervalBytes;
  private final int[] intervalPacketCounts;
  private final MeasuredFrameRate maximumFrameRate;

  @Builder
  private StreamMeasurement(
      int streamIndex,
      long measuredBytes,
      long packetCount,
      TimeBase timeBase,
      long measuredDuration,
      int[] intervalBytes,
      int[] intervalPacketCounts,
      MeasuredFrameRate maximumFrameRate) {
    this.streamIndex = streamIndex;
    this.measuredBytes = measuredBytes;
    this.packetCount = packetCount;
    this.timeBase = timeBase;
    this.measuredDuration = measuredDuration;
    this.intervalBytes = intervalBytes;
    this.intervalPacketCounts = intervalPacketCounts;
    this.maximumFrameRate = maximumFrameRate;
  }

  int streamIndex() {
    return streamIndex;
  }

  long measuredBytes() {
    return measuredBytes;
  }

  long packetCount() {
    return packetCount;
  }

  TimeBase timeBase() {
    return timeBase;
  }

  long measuredDuration() {
    return measuredDuration;
  }

  /** The unsigned 32-bit byte counts, in interval order. The array is a copy. */
  int[] intervalBytes() {
    return intervalBytes.clone();
  }

  /**
   * The chosen video track's {@link MaximumFrameRate#measured} rate over the samples that a stream
   * copy keeps; empty for every other stream and when the times give none.
   */
  Optional<MeasuredFrameRate> maximumFrameRate() {
    return Optional.ofNullable(maximumFrameRate);
  }

  /** The unsigned 32-bit packet counts, in interval order. The array is a copy. */
  int[] intervalPacketCounts() {
    return intervalPacketCounts.clone();
  }

  @Override
  public String toString() {
    return "StreamMeasurement[streamIndex=%d, measuredBytes=%d, intervals=%d]"
        .formatted(streamIndex, measuredBytes, intervalBytes.length);
  }
}
