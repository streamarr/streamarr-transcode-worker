package com.streamarr.transcode.engine;

/**
 * Receives one job attempt's segments in delivery order: the initialization segment, then each
 * media segment.
 */
@FunctionalInterface
public interface SegmentSink {

  /**
   * Returns once the server has accepted the segment, and throws when it has not. The producer
   * reads no further output meanwhile, so a slow sink holds FFmpeg back through the pipe.
   */
  void deliver(ProducedSegment segment);
}
