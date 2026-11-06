package com.streamarr.transcode.worker;

import com.streamarr.transcode.engine.Producer;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.NonNull;

/**
 * The job attempts that a worker session holds back because they run far ahead of their viewers
 * (ADR 0044, hold-back). Each hold-back replaces the previous one's attempts, and each held attempt
 * resumes at its release time unless a later hold-back that lists it sets a new one.
 */
final class HoldBacks implements AutoCloseable {

  // Its own thread, so that no task that waits for the worker's monitor delays a release.
  private final ScheduledExecutorService releases =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofVirtual().name("hold-back-releases").factory());

  // Guarded by this monitor.
  private final Map<UUID, HeldAttempt> held = new HashMap<>();
  private long holds;

  /**
   * Holds back each listed attempt that runs until its release time, counted from now, and resumes
   * each held attempt that is not listed. A release time that is not positive holds nothing.
   */
  synchronized void holdBack(
      @NonNull Map<UUID, Duration> releaseAfter,
      @NonNull Function<UUID, Optional<Producer>> runningProducers) {
    var released = held.entrySet().iterator();
    while (released.hasNext()) {
      var entry = released.next();
      if (!isPositive(releaseAfter.get(entry.getKey()))) {
        entry.getValue().release().cancel(false);
        entry.getValue().producer().releaseHeldFfmpeg();
        released.remove();
      }
    }

    releaseAfter.forEach(
        (attemptId, after) -> {
          if (isPositive(after)) {
            hold(attemptId, after, runningProducers);
          }
        });
  }

  private static boolean isPositive(Duration releaseAfter) {
    return releaseAfter != null && releaseAfter.isPositive();
  }

  // Guarded by this monitor. Listing a held attempt again only moves its release.
  private void hold(
      UUID attemptId,
      Duration releaseAfter,
      Function<UUID, Optional<Producer>> runningProducers) {
    var current = held.get(attemptId);
    if (current != null) {
      current.release().cancel(false);
      schedule(attemptId, current.producer(), releaseAfter);
      return;
    }

    runningProducers
        .apply(attemptId)
        .filter(Producer::holdBackFfmpeg)
        .ifPresent(producer -> schedule(attemptId, producer, releaseAfter));
  }

  // Guarded by this monitor.
  private void schedule(UUID attemptId, Producer producer, Duration releaseAfter) {
    var hold = ++holds;
    var release =
        releases.schedule(
            () -> release(attemptId, hold), releaseAfter.toNanos(), TimeUnit.NANOSECONDS);
    held.put(attemptId, new HeldAttempt(producer, hold, release));
  }

  // A release that a later hold-back moved finds the later hold and leaves it.
  private synchronized void release(UUID attemptId, long hold) {
    var current = held.get(attemptId);
    if (current == null || current.hold() != hold) {
      return;
    }

    held.remove(attemptId);
    current.producer().releaseHeldFfmpeg();
  }

  /** Cancels every pending release; the worker stops the attempts that the session ran. */
  @Override
  public void close() {
    releases.shutdownNow();
  }

  private record HeldAttempt(Producer producer, long hold, ScheduledFuture<?> release) {}
}
