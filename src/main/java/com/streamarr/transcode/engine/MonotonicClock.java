package com.streamarr.transcode.engine;

import java.time.Duration;

/**
 * Elapsed time that host clock corrections cannot move, read as {@link System#nanoTime()} reads it.
 * Only the difference between two readings means anything.
 */
@FunctionalInterface
public interface MonotonicClock {

  long nanoTime();

  static MonotonicClock system() {
    return System::nanoTime;
  }

  default Duration since(long startNanos) {
    return Duration.ofNanos(nanoTime() - startNanos);
  }
}
