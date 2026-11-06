package com.streamarr.transcode.worker;

import com.streamarr.transcode.engine.Producer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.NonNull;

/**
 * The job attempts that a worker session pauses while a seek's first media segment is pending on
 * the worker (ADR 0044, seek priority). Each yield replaces the previous one's attempts, and each
 * paused attempt resumes at the latest once the hold limit of the yield that paused it has passed.
 * A resumed attempt's producer decides when it may pause again ({@link Producer#pauseFfmpeg}).
 */
final class SeekYields implements AutoCloseable {

  // Its own thread, so that no task that waits for the worker's monitor delays a release.
  private final ScheduledExecutorService releases =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofVirtual().name("seek-yield-releases").factory());

  // Guarded by this monitor.
  private final Map<UUID, PausedAttempt> paused = new HashMap<>();
  private long holds;

  /**
   * Pauses each listed attempt that runs, and resumes each paused attempt that is not listed. A
   * listed attempt resumes once the hold limit has passed since the yield that paused it; listing
   * an attempt that is still paused keeps that deadline. A hold limit that is not positive pauses
   * nothing.
   */
  synchronized void yieldTo(
      @NonNull Set<UUID> attemptIds,
      @NonNull Duration holdLimit,
      @NonNull Function<UUID, Optional<Producer>> runningProducers) {
    var listed = holdLimit.isPositive() ? attemptIds : Set.<UUID>of();
    var resumed = paused.entrySet().iterator();
    while (resumed.hasNext()) {
      var entry = resumed.next();
      if (!listed.contains(entry.getKey())) {
        entry.getValue().release().cancel(false);
        entry.getValue().producer().resumeFfmpeg();
        resumed.remove();
      }
    }

    listed.stream()
        .filter(attemptId -> !paused.containsKey(attemptId))
        .forEach(
            attemptId ->
                runningProducers
                    .apply(attemptId)
                    .ifPresent(producer -> hold(attemptId, producer, holdLimit)));
  }

  // Guarded by this monitor.
  private void hold(UUID attemptId, Producer producer, Duration holdLimit) {
    if (!producer.pauseFfmpeg()) {
      return;
    }

    var hold = ++holds;
    var release =
        releases.schedule(
            () -> release(attemptId, hold), holdLimit.toNanos(), TimeUnit.NANOSECONDS);
    paused.put(attemptId, new PausedAttempt(producer, hold, release));
  }

  // A release of an earlier pause, which a yield ended, finds the later hold and leaves it.
  private synchronized void release(UUID attemptId, long hold) {
    var held = paused.get(attemptId);
    if (held == null || held.hold() != hold) {
      return;
    }

    paused.remove(attemptId);
    held.producer().resumeFfmpeg();
  }

  /** Cancels every pending release; the worker stops the attempts that the session ran. */
  @Override
  public void close() {
    releases.shutdownNow();
  }

  private record PausedAttempt(Producer producer, long hold, ScheduledFuture<?> release) {}
}
