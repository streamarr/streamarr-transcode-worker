package com.streamarr.transcode.engine;

/**
 * Receives one job attempt's segments in delivery order: the initialization segment, then each
 * media segment.
 */
@FunctionalInterface
public interface SegmentSink {

  /**
   * Returns once the server has accepted the segment, and throws when it has not. The producer
   * reads no further output meanwhile, so a slow sink holds FFmpeg back through the pipe. When the
   * attempt stops, the producer cancels the delivery; the sink then abandons it promptly, sends
   * nothing further for it, and throws.
   */
  void deliver(ProducedSegment segment, DeliveryCancellation cancellation);
}
