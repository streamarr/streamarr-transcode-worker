package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe Summary Tests")
class ProbeSummaryTest {

  private final SteppedClock clock = new SteppedClock();
  private final ProbeSummary summary = new ProbeSummary(clock);

  @Test
  @DisplayName("Should report no interval when no probe started, finished or ran")
  void shouldReportNoIntervalWhenNoProbeStartedFinishedOrRan() {
    assertThat(summary.takeInterval()).isEmpty();
  }

  @Test
  @DisplayName("Should average the time of the probes that finished when an interval ends")
  void shouldAverageTheTimeOfTheProbesThatFinishedWhenAnIntervalEnds() {
    var quick = UUID.randomUUID();
    var slow = UUID.randomUUID();
    summary.started(quick);
    summary.started(slow);
    clock.advance(Duration.ofSeconds(1));
    summary.finished(quick, media());
    clock.advance(Duration.ofSeconds(2));
    summary.finished(slow, failed(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA));

    assertThat(summary.takeInterval())
        .hasValueSatisfying(
            interval -> {
              assertThat(interval.started()).isEqualTo(2);
              assertThat(interval.finished()).isEqualTo(2);
              assertThat(interval.failures())
                  .containsExactly(Map.entry(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA, 1));
              assertThat(interval.averageDuration()).isEqualTo(Duration.ofMillis(2_000));
              assertThat(interval.running()).isZero();
            });
  }

  @Test
  @DisplayName("Should start a fresh interval when the previous interval is taken")
  void shouldStartAFreshIntervalWhenThePreviousIntervalIsTaken() {
    var attempt = UUID.randomUUID();
    summary.started(attempt);
    summary.finished(attempt, media());
    summary.takeInterval();

    assertThat(summary.takeInterval()).isEmpty();
  }

  @Test
  @DisplayName("Should report a running probe in every interval until the probe finishes")
  void shouldReportARunningProbeInEveryIntervalUntilTheProbeFinishes() {
    var attempt = UUID.randomUUID();
    summary.started(attempt);
    summary.takeInterval();

    assertThat(summary.takeInterval())
        .hasValueSatisfying(
            interval -> {
              assertThat(interval.started()).isZero();
              assertThat(interval.finished()).isZero();
              assertThat(interval.running()).isEqualTo(1);
              assertThat(interval.averageDuration()).isEqualTo(Duration.ZERO);
            });
  }

  @Test
  @DisplayName("Should count a refused probe as finished and failed when the probe never started")
  void shouldCountARefusedProbeAsFinishedAndFailedWhenTheProbeNeverStarted() {
    summary.finished(UUID.randomUUID(), failed(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED));

    assertThat(summary.takeInterval())
        .hasValueSatisfying(
            interval -> {
              assertThat(interval.started()).isZero();
              assertThat(interval.finished()).isEqualTo(1);
              assertThat(interval.failures())
                  .containsExactly(Map.entry(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED, 1));
              assertThat(interval.averageDuration()).isEqualTo(Duration.ZERO);
            });
  }

  private static ProbeAttemptResult media() {
    return ProbeAttemptResult.newBuilder()
        .setProbeVersion(1)
        .setMedia(ProbeMediaInfo.getDefaultInstance())
        .build();
  }

  private static ProbeAttemptResult failed(ProbeFailure failure) {
    return ProbeAttemptResult.newBuilder().setProbeVersion(1).setFailure(failure).build();
  }

  private static final class SteppedClock extends Clock {

    private Instant now = Instant.parse("2026-10-16T12:00:00Z");

    private void advance(Duration duration) {
      now = now.plus(duration);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
