package com.streamarr.transcode.worker;

import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import com.streamarr.transcode.engine.ProductionTiming;
import com.streamarr.transcode.fixtures.SteppedClock;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Probe Capacities Required Argument Tests")
class ProbeCapacitiesRequiredArgumentTest {

  private static final SteppedClock CLOCK = new SteppedClock();
  private static final ProductionTiming TIMING =
      new ProductionTiming(Duration.ofSeconds(3), Duration.ofSeconds(1));

  @ParameterizedTest(name = "{0}")
  @MethodSource("callsLackingARequiredArgument")
  @DisplayName("Should refuse a probe capacity call when it lacks a required argument")
  void shouldRefuseAProbeCapacityCallWhenItLacksARequiredArgument(
      String argument, ThrowingCallable call) {
    assertThatNullPointerException().isThrownBy(call).withMessageContaining(argument);
  }

  static Stream<Arguments> callsLackingARequiredArgument() {
    return Stream.of(
        Arguments.of(
            "clock",
            (ThrowingCallable)
                () ->
                    ProbeCapacities.builder()
                        .backgroundProbeCapacity(new BackgroundProbeCapacity.Adaptive(4))
                        .onChange(() -> {})
                        .build()),
        Arguments.of(
            "backgroundProbeCapacity",
            (ThrowingCallable)
                () -> ProbeCapacities.builder().clock(CLOCK).onChange(() -> {}).build()),
        Arguments.of(
            "onChange",
            (ThrowingCallable)
                () ->
                    ProbeCapacities.builder()
                        .clock(CLOCK)
                        .backgroundProbeCapacity(new BackgroundProbeCapacity.Adaptive(4))
                        .build()),
        Arguments.of("clock", (ThrowingCallable) () -> new ProbeSummary(null)),
        Arguments.of("attemptId", (ThrowingCallable) () -> new ProbeSummary(CLOCK).started(null)),
        Arguments.of(
            "attemptId",
            (ThrowingCallable)
                () ->
                    new ProbeSummary(CLOCK)
                        .finished(null, ProbeAttemptResult.getDefaultInstance())),
        Arguments.of(
            "result",
            (ThrowingCallable) () -> new ProbeSummary(CLOCK).finished(UUID.randomUUID(), null)),
        Arguments.of(
            "failures",
            (ThrowingCallable)
                () -> ProbeSummary.Interval.builder().averageDuration(Duration.ZERO).build()),
        Arguments.of(
            "averageDuration",
            (ThrowingCallable) () -> ProbeSummary.Interval.builder().failures(Map.of()).build()),
        Arguments.of(
            "runtime",
            (ThrowingCallable) () -> workerBuilder(Path.of("/media")).runtime(null).build()),
        Arguments.of(
            "clock",
            (ThrowingCallable)
                () ->
                    workerBuilder(Path.of("/media"))
                        .runtime(new ScriptedWorkerRuntime())
                        .clock(null)
                        .build()),
        Arguments.of("kind", (ThrowingCallable) () -> capacities().tryAcquire(null)),
        Arguments.of("kind", (ThrowingCallable) () -> capacities().release(null)),
        Arguments.of("jobAttemptId", (ThrowingCallable) () -> capacities().jobAttemptStarted(null)),
        Arguments.of("jobAttemptId", (ThrowingCallable) () -> capacities().jobAttemptEnded(null)),
        Arguments.of(
            "jobAttemptId",
            (ThrowingCallable) () -> capacities().mediaSegmentProduced(null, TIMING)),
        Arguments.of(
            "timing",
            (ThrowingCallable) () -> capacities().mediaSegmentProduced(UUID.randomUUID(), null)),
        Arguments.of("clock", (ThrowingCallable) () -> new ProductionSpeedWindow(null)),
        Arguments.of(
            "timing", (ThrowingCallable) () -> new ProductionSpeedWindow(CLOCK).add(null)));
  }

  private static ProbeCapacities capacities() {
    return ProbeCapacities.builder()
        .clock(CLOCK)
        .backgroundProbeCapacity(new BackgroundProbeCapacity.Adaptive(4))
        .onChange(() -> {})
        .build();
  }
}
