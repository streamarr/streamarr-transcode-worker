package com.streamarr.transcode.engine;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import lombok.NonNull;

/**
 * Measures how long a producer takes to produce each media segment. The first lap starts when
 * FFmpeg starts, and each lap ends when the producer writes a media segment. The timer leaves out
 * each pause, in which the producer holds FFmpeg back until an earlier segment's delivery is
 * accepted or until the worker's segment memory, which other job attempts' uploads also hold, has
 * room. The producer pauses and laps only from its reader, and never laps during a pause.
 */
final class ProductionTimer {

  private final InstantSource clock;

  // Guarded by this monitor.
  private Instant lapStart;
  private Instant pausedAt;
  private Duration pausedInLap = Duration.ZERO;

  ProductionTimer(@NonNull InstantSource clock) {
    this.clock = clock;
    this.lapStart = clock.instant();
  }

  synchronized void pause() {
    pausedAt = clock.instant();
  }

  synchronized void resume() {
    pausedInLap = pausedInLap.plus(Duration.between(pausedAt, clock.instant()));
  }

  /** The production time since the previous lap, which starts the next lap. */
  synchronized Duration lap() {
    var now = clock.instant();
    var production = Duration.between(lapStart, now).minus(pausedInLap);
    lapStart = now;
    pausedInLap = Duration.ZERO;
    return production;
  }
}
