package com.streamarr.transcode.worker;

import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.cancel;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.failure;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.processBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.startVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.BackgroundProbeCapacityControl;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.ProbeCancelReason;
import build.buf.gen.streamarr.transcode.v1.ProbeCapacityChanged;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbePriority;
import com.streamarr.transcode.engine.MonotonicClock;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.fixtures.SteppedClock;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.worker.support.ControlledProbeProcess;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Transcode Worker Probe Capacity Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerProbeCapacityTest {

  private static final Duration EVENT_LIMIT = Duration.ofSeconds(10);

  @TempDir Path tempDir;

  private final ScriptedWorkerRuntime runtime = new ScriptedWorkerRuntime();

  @BeforeEach
  void setUp() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
  }

  @Test
  @DisplayName(
      "Should grant the starting probe capacities when registering without a fixed capacity")
  void shouldGrantTheStartingProbeCapacitiesWhenRegisteringWithoutAFixedCapacity()
      throws Exception {
    try (var worker =
        workerBuilder(tempDir)
            .configuration(
                configurationBuilder()
                    .availableSlots(1)
                    .backgroundProbeCapacity(new BackgroundProbeCapacity.Adaptive(16))
                    .build())
            .runtime(runtime)
            .build()) {
      worker.start("localhost", 1);

      var registration = runtime.connection().registration();
      assertThat(registration.getAvailableSlots()).isEqualTo(1);
      assertThat(registration.getBackgroundProbeCapacity()).isEqualTo(4);
      assertThat(registration.getPlaybackProbeCapacity()).isEqualTo(2);
      assertThat(registration.getKeyframePacketScanCapacity()).isEqualTo(1);
      assertThat(registration.getBackgroundProbeCapacityControl())
          .isEqualTo(BackgroundProbeCapacityControl.BACKGROUND_PROBE_CAPACITY_CONTROL_ADAPTIVE);
    }
  }

  @Test
  @DisplayName("Should grant the fixed background probe capacity when a worker setting fixes it")
  void shouldGrantTheFixedBackgroundProbeCapacityWhenAWorkerSettingFixesIt() throws Exception {
    try (var worker =
        workerBuilder(tempDir).configuration(fixedCapacity(12)).runtime(runtime).build()) {
      worker.start("localhost", 1);

      var registration = runtime.connection().registration();
      assertThat(registration.getBackgroundProbeCapacity()).isEqualTo(12);
      assertThat(registration.getBackgroundProbeCapacityControl())
          .isEqualTo(BackgroundProbeCapacityControl.BACKGROUND_PROBE_CAPACITY_CONTROL_FIXED);
    }
  }

  @Test
  @DisplayName(
      "Should refuse a probe before ffprobe starts when every unit of its capacity is in use")
  void shouldRefuseAProbeBeforeFfprobeStartsWhenEveryUnitOfItsCapacityIsInUse() throws Exception {
    var held = processBuilder().running(true).build();
    var launches = new AtomicInteger();
    var running = requestBuilder().build();
    var beyondCapacity = requestBuilder().build();

    try (var worker =
        workerBuilder(tempDir)
            .configuration(fixedCapacity(1))
            .runtime(runtime)
            .ffprobe(launchCounting(launches, held))
            .build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, running);
        var runningTask = runtime.probes().runNext();
        held.awaitStarted(runningTask);

        start(connection, beyondCapacity);

        assertThat(connection.results())
            .as("The refusal arrives at once, before any queued probe task runs")
            .containsExactly(failure(beyondCapacity, ProbeFailure.PROBE_FAILURE_NO_FREE_CAPACITY));
        held.finish();
        runningTask.get();
        runtime.probes().drain();
        assertThat(launches).as("ffprobe ran for the probe inside the capacity only").hasValue(1);
        assertThat(connection.results()).hasSize(2);
      } finally {
        held.finish();
      }
    }
  }

  @Test
  @DisplayName("Should start a playback probe when every background probe unit is in use")
  void shouldStartAPlaybackProbeWhenEveryBackgroundProbeUnitIsInUse() throws Exception {
    var background = processBuilder().running(true).build();
    var playback = processBuilder().running(true).build();
    var ffprobe = probesStarting(List.of(background, playback), new AtomicInteger());

    try (var worker =
        workerBuilder(tempDir)
            .configuration(fixedCapacity(1))
            .runtime(runtime)
            .ffprobe(ffprobe)
            .build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, requestBuilder().build());
        background.awaitStarted(runtime.probes().runNext());

        start(
            connection,
            requestBuilder().setPriority(ProbePriority.PROBE_PRIORITY_PLAYBACK).build());

        playback.awaitStarted(runtime.probes().runNext());
        assertThat(connection.results()).isEmpty();
      } finally {
        background.finish();
        playback.finish();
      }
    }
  }

  @Test
  @DisplayName("Should start a job attempt when every unit of the probe capacities is in use")
  void shouldStartAJobAttemptWhenEveryUnitOfTheProbeCapacitiesIsInUse() throws Exception {
    var held = processBuilder().running(true).build();
    var job = variantJobBuilder().build();

    try (var worker =
        workerBuilder(tempDir)
            .configuration(fixedCapacity(1))
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
    var first = processBuilder().running(true).build();
    var next = processBuilder().running(true).build();
    var ffprobe = probesStarting(List.of(first, next), new AtomicInteger());

    try (var worker =
        workerBuilder(tempDir)
            .configuration(fixedCapacity(1))
            .runtime(runtime)
            .ffprobe(ffprobe)
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
    var held = processBuilder().running(true).build();
    var summary = new ProbeSummary(MonotonicClock.system());

    try (var worker =
        workerBuilder(tempDir)
            .configuration(fixedCapacity(1))
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
                      .containsExactly(Map.entry(ProbeFailure.PROBE_FAILURE_NO_FREE_CAPACITY, 1));
                  assertThat(interval.running()).isZero();
                });
      } finally {
        held.finish();
      }
    }
  }

  @Test
  @DisplayName(
      "Should register the grown background probe capacity when it grew while the session was down")
  void shouldRegisterTheGrownBackgroundProbeCapacityWhenItGrewWhileTheSessionWasDown()
      throws Exception {
    var processes =
        List.of(
            processBuilder().running(true).build(),
            processBuilder().running(true).build(),
            processBuilder().running(true).build(),
            processBuilder().running(true).build());
    var ffprobe = probesStarting(processes, new AtomicInteger());

    try (var worker =
        workerBuilder(tempDir)
            .configuration(configurationBuilder().availableSlots(1).build())
            .runtime(runtime)
            .ffprobe(ffprobe)
            .build()) {
      try {
        worker.start("localhost", 1);
        var lost = runtime.connection();
        var running = new ArrayList<Future<?>>();
        for (var process : processes) {
          start(lost, requestBuilder().build());
          var task = runtime.probes().runNext();
          process.awaitStarted(task);
          running.add(task);
        }

        lost.fail("connection lost");
        for (var task : running) {
          task.get();
        }

        worker.close();
        worker.start("localhost", 1);

        assertThat(capacityChanges(lost)).isEmpty();
        assertThat(runtime.connection().registration().getBackgroundProbeCapacity())
            .as("4 grows to 5 when a probe ends with 4 in use, and to 6 with 3 of 5 in use")
            .isEqualTo(6);
      } finally {
        processes.forEach(ControlledProbeProcess::finish);
      }
    }
  }

  @Test
  @DisplayName(
      "Should send both capacities once when a job attempt starts and again once scans reopen")
  void shouldSendBothCapacitiesOnceWhenAJobAttemptStartsAndAgainOnceScansReopen() throws Exception {
    var clock = new SteppedClock();
    var job = variantJobBuilder().build();

    try (var worker =
        workerBuilder(tempDir)
            .configuration(configurationBuilder().availableSlots(1).build())
            .runtime(runtime)
            .engine(engine(ScriptedProcessLauncher.writing(ENCODED_RECORDING)))
            .clock(clock)
            .build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();

      startVariant(connection, job);

      awaitJobAttemptCompleted(connection);
      assertThat(capacityChanges(connection)).containsExactly(changed(2, 0));
      clock.advance(Duration.ofSeconds(30));
      await()
          .atMost(EVENT_LIMIT)
          .untilAsserted(
              () ->
                  assertThat(capacityChanges(connection))
                      .containsExactly(changed(2, 0), changed(2, 1)));
    }
  }

  @Test
  @DisplayName(
      "Should halve the background probe capacity and hold its growth for 30 s when the server"
          + " cancels a probe at the probe timeout")
  void
      shouldHalveTheBackgroundProbeCapacityAndHoldItsGrowthFor30SWhenTheServerCancelsAProbeAtTheProbeTimeout()
          throws Exception {
    var clock = new SteppedClock();
    var timedOut = processBuilder().running(true).build();
    var first = processBuilder().running(true).build();
    var second = processBuilder().running(true).build();
    var ffprobe = probesStarting(List.of(timedOut, first, second), new AtomicInteger());
    var request = requestBuilder().build();

    try (var worker =
        workerBuilder(tempDir)
            .configuration(configurationBuilder().availableSlots(1).build())
            .runtime(runtime)
            .ffprobe(ffprobe)
            .clock(clock)
            .build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, request);
        var timedOutTask = runtime.probes().runNext();
        timedOut.awaitStarted(timedOutTask);

        cancel(connection, request, ProbeCancelReason.PROBE_CANCEL_REASON_TIMEOUT);
        timedOutTask.get();

        assertThat(connection.results())
            .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_CANCELLED));
        assertThat(capacityChanges(connection)).containsExactly(changed(2, 1));
        clock.advance(Duration.ofSeconds(29));
        var firstTask = startHeld(connection, first);
        var secondTask = startHeld(connection, second);
        first.finish();
        firstTask.get();
        assertThat(capacityChanges(connection))
            .as("A probe that ends with every unit in use grows nothing during the hold")
            .containsExactly(changed(2, 1));
        clock.advance(Duration.ofSeconds(1));
        second.finish();
        secondTask.get();
        assertThat(capacityChanges(connection)).containsExactly(changed(2, 1), changed(3, 1));
      } finally {
        timedOut.finish();
        first.finish();
        second.finish();
      }
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = ProbeCancelReason.class,
      names = {"PROBE_CANCEL_REASON_TIMEOUT", "UNRECOGNIZED"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName(
      "Should keep the background probe capacity when the server cancels a probe for a reason"
          + " other than the probe timeout")
  void
      shouldKeepTheBackgroundProbeCapacityWhenTheServerCancelsAProbeForAReasonOtherThanTheProbeTimeout(
          ProbeCancelReason reason) throws Exception {
    var cancelled = processBuilder().running(true).build();
    var request = requestBuilder().build();

    try (var worker =
        workerBuilder(tempDir)
            .configuration(configurationBuilder().availableSlots(1).build())
            .runtime(runtime)
            .ffprobe(launchCounting(new AtomicInteger(), cancelled))
            .clock(new SteppedClock())
            .build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, request);
        var task = runtime.probes().runNext();
        cancelled.awaitStarted(task);

        cancel(connection, request, reason);
        task.get();

        assertThat(connection.results())
            .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_CANCELLED));
        assertThat(capacityChanges(connection)).isEmpty();
      } finally {
        cancelled.finish();
      }
    }
  }

  @Test
  @DisplayName(
      "Should send each media segment's timing with its upload and none with the initialization"
          + " segment")
  void shouldSendEachMediaSegmentsTimingWithItsUploadAndNoneWithTheInitializationSegment()
      throws Exception {
    var recording = recording(ENCODED_RECORDING);
    var job = variantJobBuilder().build();

    try (var worker =
        workerBuilder(tempDir)
            .configuration(configurationBuilder().availableSlots(1).build())
            .runtime(runtime)
            .engine(engine(ScriptedProcessLauncher.writing(ENCODED_RECORDING)))
            .build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();

      startVariant(connection, job);

      awaitJobAttemptCompleted(connection);
      var uploads = connection.uploads();
      assertThat(uploads.getFirst().metadata().hasMediaDuration()).isFalse();
      assertThat(uploads.getFirst().metadata().hasProductionTime()).isFalse();
      var media = uploads.subList(1, uploads.size());
      assertThat(media).hasSize(recording.segments().size());
      assertThat(media)
          .allSatisfy(upload -> assertThat(upload.metadata().hasProductionTime()).isTrue());
      assertThat(media.subList(0, media.size() - 1))
          .allSatisfy(
              upload -> {
                var duration = upload.metadata().getMediaDuration();
                assertThat(duration.getSeconds() + duration.getNanos() / 1e9)
                    .as("A whole grid interval at the recording's frame rate")
                    .isCloseTo(recording.period(), within(0.1));
              });
    }
  }

  private TranscodeWorkerConfiguration fixedCapacity(int capacity) {
    return configurationBuilder()
        .availableSlots(1)
        .backgroundProbeCapacity(new BackgroundProbeCapacity.Fixed(capacity))
        .build();
  }

  private TranscodeWorkerConfiguration.TranscodeWorkerConfigurationBuilder configurationBuilder() {
    return workerConfigurationBuilder().sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, tempDir));
  }

  private static void awaitJobAttemptCompleted(ScriptedWorkerRuntime.Connection connection) {
    await()
        .atMost(EVENT_LIMIT)
        .until(
            () ->
                connection.events().stream()
                    .anyMatch(EstablishWorkerSessionRequest::hasJobAttemptCompleted));
  }

  private CompletableFuture<Void> startHeld(
      ScriptedWorkerRuntime.Connection connection, ControlledProbeProcess process)
      throws Exception {
    start(connection, requestBuilder().build());
    var task = runtime.probes().runNext();
    process.awaitStarted(task);
    return task;
  }

  private static List<ProbeCapacityChanged> capacityChanges(
      ScriptedWorkerRuntime.Connection connection) {
    return connection.events().stream()
        .filter(EstablishWorkerSessionRequest::hasProbeCapacityChanged)
        .map(EstablishWorkerSessionRequest::getProbeCapacityChanged)
        .toList();
  }

  private static ProbeCapacityChanged changed(int background, int keyframePacketScans) {
    return ProbeCapacityChanged.newBuilder()
        .setBackgroundProbeCapacity(background)
        .setKeyframePacketScanCapacity(keyframePacketScans)
        .build();
  }

  private static FfprobeExecutor launchCounting(
      AtomicInteger launches, ControlledProbeProcess process) {
    return probesStarting(List.of(process, process, process), launches);
  }

  // Each probe's first ffprobe takes the next process and counts as a launch. The probe's later
  // commands, which read stream facts beyond ffprobe's streams, finish at once with no output.
  private static FfprobeExecutor probesStarting(
      List<ControlledProbeProcess> processes, AtomicInteger launches) {
    return new FfprobeExecutor(
        new ObjectMapper(),
        command -> {
          if (!command.arguments().contains("-show_streams")) {
            return processBuilder().stdout("{}").build();
          }

          return processes.get(launches.getAndIncrement());
        });
  }
}
