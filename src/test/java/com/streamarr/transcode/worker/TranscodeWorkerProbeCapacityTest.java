package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.failure;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.processBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.startVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.worker.support.ControlledProbeProcess;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Transcode Worker Probe Capacity Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerProbeCapacityTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should advertise the probe capacity apart from the playback slots when registering")
  void shouldAdvertiseTheProbeCapacityApartFromThePlaybackSlotsWhenRegistering() throws Exception {
    var runtime = new ScriptedWorkerRuntime();

    try (var worker =
        workerBuilder(tempDir)
            .configuration(configurationBuilder().availableSlots(1).probeCapacity(5).build())
            .runtime(runtime)
            .build()) {
      worker.start("localhost", 1);

      var registration = runtime.connection().registration();
      assertThat(registration.getAvailableSlots()).isEqualTo(1);
      assertThat(registration.getProbeCapacity()).isEqualTo(5);
    }
  }

  @Test
  @DisplayName(
      "Should fail a probe before ffprobe starts when every unit of the probe capacity is in use")
  void shouldFailAProbeBeforeFfprobeStartsWhenEveryUnitOfTheProbeCapacityIsInUse()
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var held = processBuilder().running(true).build();
    var launches = new AtomicInteger();
    var running = requestBuilder().build();
    var beyondCapacity = requestBuilder().build();

    try (var worker =
        workerBuilder(tempDir)
            .configuration(configurationBuilder().availableSlots(1).probeCapacity(1).build())
            .runtime(runtime)
            .ffprobe(launchCounting(launches, held))
            .build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, running);
        held.awaitStarted(runtime.probes().runNext());

        start(connection, beyondCapacity);

        assertThat(connection.results())
            .as("The refusal arrives at once, before any queued probe task runs")
            .containsExactly(failure(beyondCapacity, ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED));
        held.finish();
        runtime.probes().drain();
        assertThat(launches).as("ffprobe ran for the probe inside the capacity only").hasValue(1);
      } finally {
        held.finish();
      }
    }
  }

  @Test
  @DisplayName("Should start a job attempt when every unit of the probe capacity is in use")
  void shouldStartAJobAttemptWhenEveryUnitOfTheProbeCapacityIsInUse() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var held = processBuilder().running(true).build();
    var job = variantJobBuilder().build();

    try (var worker =
        workerBuilder(tempDir)
            .configuration(configurationBuilder().availableSlots(1).probeCapacity(1).build())
            .runtime(runtime)
            .ffprobe(launchCounting(new AtomicInteger(), held))
            .build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, requestBuilder().build());
        held.awaitStarted(runtime.probes().runNext());

        startVariant(connection, job);

        assertThat(connection.events())
            .filteredOn(EstablishWorkerSessionRequest::hasJobAttemptStarted)
            .singleElement()
            .satisfies(
                started ->
                    assertThat(started.getJobAttemptStarted().getJobAttemptId())
                        .isEqualTo(job.getJobAttemptId()));
      } finally {
        held.finish();
      }
    }
  }

  @Test
  @DisplayName("Should start ffprobe for the next probe when a running probe frees its capacity")
  void shouldStartFfprobeForTheNextProbeWhenARunningProbeFreesItsCapacity() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var first = processBuilder().running(true).build();
    var next = processBuilder().running(true).build();
    var processes = List.of(first, next);
    var launches = new AtomicInteger();
    var producer =
        new FfprobeExecutor(new ObjectMapper(), _ -> processes.get(launches.getAndIncrement()));

    try (var worker =
        workerBuilder(tempDir)
            .configuration(configurationBuilder().availableSlots(1).probeCapacity(1).build())
            .runtime(runtime)
            .ffprobe(producer)
            .build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, requestBuilder().build());
        var firstTask = runtime.probes().runNext();
        first.awaitStarted(firstTask);
        first.finish();
        firstTask.get();

        start(connection, requestBuilder().build());
        next.awaitStarted(runtime.probes().runNext());

        assertThat(connection.results())
            .singleElement()
            .satisfies(result -> assertThat(result.hasMedia()).isTrue());
      } finally {
        first.finish();
        next.finish();
      }
    }
  }

  @Test
  @DisplayName("Should count started, finished and failed probes when the worker runs probes")
  void shouldCountStartedFinishedAndFailedProbesWhenTheWorkerRunsProbes() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var held = processBuilder().running(true).build();
    var summary = new ProbeSummary(Clock.systemUTC());

    try (var worker =
        workerBuilder(tempDir)
            .configuration(configurationBuilder().availableSlots(1).probeCapacity(1).build())
            .runtime(runtime)
            .ffprobe(launchCounting(new AtomicInteger(), held))
            .probeSummary(summary)
            .build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, requestBuilder().build());
        var task = runtime.probes().runNext();
        held.awaitStarted(task);
        start(connection, requestBuilder().build());
        held.finish();
        task.get();

        assertThat(summary.takeInterval())
            .hasValueSatisfying(
                interval -> {
                  assertThat(interval.started()).isEqualTo(1);
                  assertThat(interval.finished()).isEqualTo(2);
                  assertThat(interval.failures())
                      .containsExactly(Map.entry(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED, 1));
                  assertThat(interval.running()).isZero();
                });
      } finally {
        held.finish();
      }
    }
  }

  private TranscodeWorkerConfiguration.TranscodeWorkerConfigurationBuilder configurationBuilder() {
    return workerConfigurationBuilder().sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, tempDir));
  }

  private static FfprobeExecutor launchCounting(
      AtomicInteger launches, ControlledProbeProcess process) {
    return new FfprobeExecutor(
        new ObjectMapper(),
        _ -> {
          launches.incrementAndGet();
          return process;
        });
  }
}
