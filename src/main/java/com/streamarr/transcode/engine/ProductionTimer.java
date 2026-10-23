package com.streamarr.transcode.engine;

import java.time.Duration;
import lombok.NonNull;

/**
 * Measures how long a producer takes to produce each media segment. The first lap starts when
 * FFmpeg starts, and each lap ends when the producer writes a media segment. The timer leaves out
 * each pause, in which the producer holds FFmpeg back until an earlier segment's delivery is
 * accepted or until the worker's segment memory, which other job attempts' uploads also hold, has
 * room. The producer pauses and laps only from its reader, and never laps during a pause.
 */
final class ProductionTimer {

  private final MonotonicClock clock;

  // Guarded by this monitor.
  private long lapStartNanos;
  private long pausedAtNanos;
  private Duration pausedInLap = Duration.ZERO;

  ProductionTimer(@NonNull MonotonicClock clock) {
    this.clock = clock;
    this.lapStartNanos = clock.nanoTime();
  }

  synchronized void pause() {
    pausedAtNanos = clock.nanoTime();
  }

  synchronized void resume() {
    pausedInLap = pausedInLap.plus(clock.since(pausedAtNanos));
  }

  /** The production time since the previous lap, which starts the next lap. */
  synchronized Duration lap() {
    var production = clock.since(lapStartNanos).minus(pausedInLap);
    lapStartNanos = clock.nanoTime();
    pausedInLap = Duration.ZERO;
    return production;
  }
}
