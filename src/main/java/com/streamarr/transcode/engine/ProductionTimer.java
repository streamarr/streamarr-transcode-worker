package com.streamarr.transcode.engine;

import java.time.Duration;
import lombok.NonNull;

/**
 * Measures how long a producer takes to produce each media segment. The first lap starts when
 * FFmpeg starts, and each lap ends when the producer writes a media segment. The timer leaves out
 * each pause: the producer holds FFmpeg back until an earlier segment's delivery is accepted or
 * until the worker's segment memory, which other job attempts' uploads also hold, has room, and the
 * worker pauses FFmpeg for another job attempt's seek. Pauses may overlap, and the timer leaves out
 * the time in which any pause lasts once. The producer laps only from its reader, which may lap
 * during the worker's pause on output that FFmpeg wrote before it.
 */
final class ProductionTimer {

  private final MonotonicClock clock;

  // Guarded by this monitor.
  private long lapStartNanos;
  private int pauses;
  private long pausedAtNanos;
  private Duration pausedInLap = Duration.ZERO;

  ProductionTimer(@NonNull MonotonicClock clock) {
    this.clock = clock;
    this.lapStartNanos = clock.nanoTime();
  }

  synchronized void pause() {
    pauses++;
    if (pauses == 1) {
      pausedAtNanos = clock.nanoTime();
    }
  }

  synchronized void resume() {
    pauses--;
    if (pauses == 0) {
      pausedInLap = pausedInLap.plus(clock.since(pausedAtNanos));
    }
  }

  /** The production time since the previous lap, which starts the next lap. */
  synchronized Duration lap() {
    var now = clock.nanoTime();
    var paused = pausedInLap;
    if (pauses > 0) {
      paused = paused.plusNanos(now - pausedAtNanos);
      pausedAtNanos = now;
    }

    var production = Duration.ofNanos(now - lapStartNanos).minus(paused);
    lapStartNanos = now;
    pausedInLap = Duration.ZERO;
    return production;
  }
}
