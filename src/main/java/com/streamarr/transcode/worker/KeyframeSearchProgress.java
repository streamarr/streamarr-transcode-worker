package com.streamarr.transcode.worker;

import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The source bytes that a held encode's search for the video's last keyframe has read, which the
 * worker reports as the attempt's progress whenever they advance (ADR 0037, early ends).
 */
final class KeyframeSearchProgress {

  private final AtomicLong bytesRead = new AtomicLong();
  private long reportedBytes;

  void add(long bytes) {
    bytesRead.addAndGet(bytes);
  }

  /** The bytes read, once they have advanced since the last time this returned them. */
  synchronized OptionalLong takeAdvancedBytesRead() {
    var bytes = bytesRead.get();
    if (bytes == reportedBytes) {
      return OptionalLong.empty();
    }

    reportedBytes = bytes;
    return OptionalLong.of(bytes);
  }
}
