package com.streamarr.transcode.worker;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.engine.MonotonicClock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

/**
 * Counts the worker's probes for its periodic probe summary. Each {@link #takeInterval()} reports
 * the probes since the previous one and starts a new interval.
 */
final class ProbeSummary {

  private final MonotonicClock clock;
  private final Map<UUID, Long> running = new HashMap<>();
  private final Map<ProbeFailure, Integer> failures = new EnumMap<>(ProbeFailure.class);
  private int started;
  private int finished;
  private int timedProbes;
  private Duration timedTotal = Duration.ZERO;

  ProbeSummary(@NonNull MonotonicClock clock) {
    this.clock = clock;
  }

  synchronized void started(@NonNull UUID attemptId) {
    started++;
    running.put(attemptId, clock.nanoTime());
  }

  /** Counts a sent result. A probe that the worker refused before it started has no start. */
  synchronized void finished(@NonNull UUID attemptId, @NonNull ProbeAttemptResult result) {
    finished++;
    if (result.hasFailure()) {
      failures.merge(result.getFailure(), 1, Integer::sum);
    }

    var startedAt = running.remove(attemptId);
    if (startedAt == null) {
      return;
    }

    timedProbes++;
    timedTotal = timedTotal.plus(clock.since(startedAt));
  }

  /** Empty when no probe started, finished or ran during the interval. */
  synchronized Optional<Interval> takeInterval() {
    if (started == 0 && finished == 0 && running.isEmpty()) {
      return Optional.empty();
    }

    var interval =
        Interval.builder()
            .started(started)
            .finished(finished)
            .failures(failures)
            .running(running.size())
            .averageDuration(averageDuration())
            .build();
    started = 0;
    finished = 0;
    failures.clear();
    timedProbes = 0;
    timedTotal = Duration.ZERO;
    return Optional.of(interval);
  }

  private Duration averageDuration() {
    if (timedProbes == 0) {
      return Duration.ZERO;
    }

    return timedTotal.dividedBy(timedProbes);
  }

  @Builder
  record Interval(
      int started,
      int finished,
      @NonNull Map<ProbeFailure, Integer> failures,
      int running,
      @NonNull Duration averageDuration) {

    Interval {
      failures = Map.copyOf(failures);
    }
  }
}
