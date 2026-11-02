package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.fixtures.SteppedClock;
import com.streamarr.transcode.probe.ProbeKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Runs a scan with more files than the maximum background probe capacity against simulated storage,
 * and a server that cancels every probe at its default probe timeout of 1 minute. Most probes read
 * cached headers and end within milliseconds. A slow probe reads far into its file and shares the
 * storage: beyond the storage's knee, each slow probe progresses at {@code knee / concurrent
 * probes} of its own speed, so its duration is its work times {@code max(1, concurrent probes /
 * knee)}.
 */
@Tag("UnitTest")
@DisplayName("Probe Capacities Simulated Storage Tests")
class ProbeCapacitiesSimulatedStorageTest {

  private static final int MAXIMUM = 32;
  private static final Duration PROBE_TIMEOUT = Duration.ofMinutes(1);
  private static final Duration SETTLING = Duration.ofMinutes(20);
  private static final Duration SETTLED = Duration.ofMinutes(40);

  // Slow probes time out beyond about 20 concurrent probes.
  private static final SimulatedStorage NETWORK_SHARE =
      SimulatedStorage.builder()
          .name("network share")
          .slowProbeShare(0.1)
          .slowProbeWork(Duration.ofSeconds(12))
          .knee(4)
          .fastProbeDuration(Duration.ofMillis(20))
          .build();

  // Slow probes time out beyond about 22 concurrent probes.
  private static final SimulatedStorage MIXED_LIBRARY =
      SimulatedStorage.builder()
          .name("mixed library on a network share")
          .slowProbeShare(0.4)
          .slowProbeWork(Duration.ofSeconds(8))
          .knee(3)
          .fastProbeDuration(Duration.ofMillis(50))
          .build();

  // Slow probes never come near the timeout, even at the maximum.
  private static final SimulatedStorage LOCAL_DISK =
      SimulatedStorage.builder()
          .name("local disk")
          .slowProbeShare(0.3)
          .slowProbeWork(Duration.ofSeconds(3))
          .knee(16)
          .fastProbeDuration(Duration.ofMillis(30))
          .build();

  static Stream<SimulatedStorage> congestedStorage() {
    return Stream.of(NETWORK_SHARE, MIXED_LIBRARY);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("congestedStorage")
  @DisplayName(
      "Should time out no probe and apply no brake once the capacity settles when slow probes"
          + " congest the storage")
  void shouldTimeOutNoProbeAndApplyNoBrakeOnceTheCapacitySettlesWhenSlowProbesCongestTheStorage(
      SimulatedStorage storage) {
    var scan = new SimulatedScan(storage, 1);

    scan.run(SETTLING);
    var settlingTimeouts = scan.timeouts();
    scan.resetCounts();
    scan.run(SETTLED);

    assertThat(settlingTimeouts).as("the maximum capacity overloads this storage").isPositive();
    assertThat(scan.timeouts()).as("probes that timed out after the capacity settled").isZero();
    assertThat(scan.brakes()).as("brakes after the capacity settled").isZero();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("congestedStorage")
  @DisplayName(
      "Should finish slow probes at nine tenths of the storage's throughput once the capacity"
          + " settles when slow probes congest the storage")
  void
      shouldFinishSlowProbesAtNineTenthsOfTheStoragesThroughputOnceTheCapacitySettlesWhenSlowProbesCongestTheStorage(
          SimulatedStorage storage) {
    var scan = new SimulatedScan(storage, 2);

    scan.run(SETTLING);
    scan.resetCounts();
    scan.run(SETTLED);

    var slowProbesPerMinute = scan.finishedSlowProbes() / (double) SETTLED.toMinutes();
    assertThat(slowProbesPerMinute).isGreaterThanOrEqualTo(0.9 * storage.slowProbesPerMinute());
  }

  @Test
  @DisplayName(
      "Should grow the background probe capacity to the maximum within 30 s when the storage"
          + " keeps up")
  void shouldGrowTheBackgroundProbeCapacityToTheMaximumWithin30SWhenTheStorageKeepsUp() {
    var scan = new SimulatedScan(LOCAL_DISK, 3);

    scan.run(Duration.ofSeconds(30));

    assertThat(scan.capacity()).isEqualTo(MAXIMUM);
    assertThat(scan.timeouts()).isZero();
  }

  @Builder
  record SimulatedStorage(
      @NonNull String name,
      double slowProbeShare,
      @NonNull Duration slowProbeWork,
      int knee,
      @NonNull Duration fastProbeDuration) {

    // The storage serves the knee's worth of slow probes at full speed whatever the concurrency.
    double slowProbesPerMinute() {
      return knee * 60.0 / slowProbeWork.toMillis() * 1000;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  private static final class SimulatedScan {

    private static final Duration STEP = Duration.ofMillis(10);

    private final SimulatedStorage storage;
    private final Random random;
    private final SteppedClock clock = new SteppedClock();
    private final ProbeCapacities capacities =
        ProbeCapacities.builder()
            .clock(clock)
            .backgroundProbeCapacity(new BackgroundProbeCapacity.Adaptive(MAXIMUM))
            .onChange(() -> {})
            .build();
    private final List<RunningProbe> running = new ArrayList<>();
    private long elapsedNanos;
    private int timeouts;
    private int finishedSlowProbes;

    private SimulatedScan(SimulatedStorage storage, long seed) {
      this.storage = storage;
      this.random = new Random(seed);
    }

    // The server sends a probe from its backlog whenever the worker grants a free unit.
    private void run(Duration duration) {
      var end = elapsedNanos + duration.toNanos();
      while (elapsedNanos < end) {
        startProbesOnFreeUnits();
        clock.advance(STEP);
        elapsedNanos += STEP.toNanos();
        progressProbes();
      }
    }

    private void startProbesOnFreeUnits() {
      var counted = capacities.tryAcquire(ProbeKind.BACKGROUND);
      while (counted.isPresent()) {
        running.add(nextProbe(counted.get()));
        counted = capacities.tryAcquire(ProbeKind.BACKGROUND);
      }
    }

    private RunningProbe nextProbe(ProbeCapacities.CountedProbe counted) {
      var slow = random.nextDouble() < storage.slowProbeShare();
      var typical = slow ? storage.slowProbeWork() : storage.fastProbeDuration();
      var work = typical.toNanos() * (0.5 + random.nextDouble());
      return RunningProbe.builder()
          .counted(counted)
          .slow(slow)
          .startNanos(elapsedNanos)
          .remainingNanos(work)
          .build();
    }

    private void progressProbes() {
      var slowProbeSpeed = Math.min(1.0, storage.knee() / (double) running.size());
      var ended = new ArrayList<RunningProbe>();
      for (var probe : running) {
        probe.remainingNanos -= STEP.toNanos() * (probe.slow ? slowProbeSpeed : 1.0);
        if (probe.remainingNanos <= 0
            || elapsedNanos - probe.startNanos >= PROBE_TIMEOUT.toNanos()) {
          ended.add(probe);
        }
      }

      running.removeAll(ended);
      ended.forEach(this::end);
    }

    private void end(RunningProbe probe) {
      if (probe.remainingNanos <= 0) {
        capacities.release(probe.counted);
        finishedSlowProbes += probe.slow ? 1 : 0;
        return;
      }

      capacities.probeTimedOut(probe.counted);
      capacities.releaseTimedOut(probe.counted);
      timeouts++;
    }

    private void resetCounts() {
      timeouts = 0;
      finishedSlowProbes = 0;
      capacities.takeBrakeCount();
    }

    private int timeouts() {
      return timeouts;
    }

    private int brakes() {
      return capacities.takeBrakeCount();
    }

    private int finishedSlowProbes() {
      return finishedSlowProbes;
    }

    private int capacity() {
      return capacities.granted().backgroundProbeCapacity();
    }
  }

  private static final class RunningProbe {

    private final ProbeCapacities.CountedProbe counted;
    private final boolean slow;
    private final long startNanos;
    private double remainingNanos;

    @Builder
    private RunningProbe(
        @NonNull ProbeCapacities.CountedProbe counted,
        boolean slow,
        long startNanos,
        double remainingNanos) {
      this.counted = counted;
      this.slow = slow;
      this.startNanos = startNanos;
      this.remainingNanos = remainingNanos;
    }
  }
}
