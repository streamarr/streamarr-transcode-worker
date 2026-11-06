package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.startVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.stopVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest.EventCase;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import build.buf.gen.streamarr.transcode.v1.WorkerIdentity;
import build.buf.gen.streamarr.transcode.v1.YieldToSeekCommand;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Transcode Worker Seek Yield Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerSeekYieldTest {

  private static final Duration EVENT_LIMIT = Duration.ofSeconds(10);
  private static final Duration LONG_HOLD = Duration.ofMinutes(5);

  @TempDir Path tempDir;

  private final ScriptedWorkerRuntime runtime = new ScriptedWorkerRuntime();
  private final VariantJob steady = variantJobBuilder().build();
  private final VariantJob otherSteady = variantJobBuilder().build();
  private final VariantJob seek = variantJobBuilder().build();
  private final VariantJob slowStart = variantJobBuilder().build();
  private final CountDownLatch slowLaunchEntered = new CountDownLatch(1);
  private final CountDownLatch slowLaunchReleased = new CountDownLatch(1);
  private final ScriptedProcessLauncher launcher = new ScriptedProcessLauncher(this::launch);

  @BeforeEach
  void setUp() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
  }

  @Test
  @DisplayName(
      "Should pause only the listed job attempts while the seek's first segment is pending and"
          + " resume them when the yield ends")
  void
      shouldPauseOnlyTheListedJobAttemptsWhileTheSeeksFirstSegmentIsPendingAndResumeThemWhenTheYieldEnds()
          throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);

      yieldToSeek(connection, LONG_HOLD, steady, otherSteady);

      promptly().until(() -> pausedAttempts().equals(List.of(steady, otherSteady)));
      yieldToSeek(connection, LONG_HOLD);
      promptly().until(() -> pausedAttempts().isEmpty());
      exitAll();
    }
  }

  @Test
  @DisplayName(
      "Should resume a job attempt that a later yield leaves out and keep the one it lists again")
  void shouldResumeAJobAttemptThatALaterYieldLeavesOutAndKeepTheOneItListsAgain() throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);
      yieldToSeek(connection, LONG_HOLD, steady, otherSteady);
      promptly().until(() -> pausedAttempts().equals(List.of(steady, otherSteady)));

      yieldToSeek(connection, LONG_HOLD, otherSteady);

      promptly().until(() -> pausedAttempts().equals(List.of(otherSteady)));
      exitAll();
    }
  }

  @Test
  @DisplayName(
      "Should resume a paused job attempt once the hold limit passes when no later yield arrives")
  void shouldResumeAPausedJobAttemptOnceTheHoldLimitPassesWhenNoLaterYieldArrives()
      throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);

      yieldToSeek(connection, Duration.ofMillis(300), steady);

      promptly().until(() -> pausedAttempts().equals(List.of(steady)));
      promptly().until(() -> pausedAttempts().isEmpty());
      exitAll();
    }
  }

  @Test
  @DisplayName(
      "Should resume a job attempt at its first hold limit when a later yield lists it again while"
          + " it is paused")
  void shouldResumeAJobAttemptAtItsFirstHoldLimitWhenALaterYieldListsItAgainWhileItIsPaused()
      throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);
      yieldToSeek(connection, Duration.ofMillis(300), steady, otherSteady);
      promptly().until(() -> pausedAttempts().equals(List.of(steady, otherSteady)));

      yieldToSeek(connection, LONG_HOLD, steady, otherSteady);

      promptly().until(() -> pausedAttempts().isEmpty());
      exitAll();
    }
  }

  @Test
  @DisplayName(
      "Should keep a job attempt that a yield resumed running when a later yield lists it before it"
          + " delivers another media segment")
  void shouldKeepAJobAttemptThatAYieldResumedRunningWhenALaterYieldListsItBeforeItsNextSegment()
      throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);
      yieldToSeek(connection, LONG_HOLD, steady);
      promptly().until(() -> pausedAttempts().equals(List.of(steady)));
      yieldToSeek(connection, LONG_HOLD);
      promptly().until(() -> pausedAttempts().isEmpty());

      yieldToSeek(connection, LONG_HOLD, steady, otherSteady);

      promptly().until(() -> pausedAttempts().equals(List.of(otherSteady)));
      exitAll();
    }
  }

  @Test
  @DisplayName(
      "Should keep a job attempt whose hold limit passed running when a later yield lists it before"
          + " it delivers another media segment")
  void shouldKeepAJobAttemptWhoseHoldLimitPassedRunningWhenALaterYieldListsItBeforeItsNextSegment()
      throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);
      yieldToSeek(connection, Duration.ofMillis(300), steady);
      promptly().until(() -> pausedAttempts().equals(List.of(steady)));
      promptly().until(() -> pausedAttempts().isEmpty());

      yieldToSeek(connection, LONG_HOLD, steady, otherSteady);

      promptly().until(() -> pausedAttempts().equals(List.of(otherSteady)));
      exitAll();
    }
  }

  @Test
  @DisplayName(
      "Should leave the paused job attempts alone when a yield arrives on an earlier session's"
          + " connection")
  void shouldLeaveThePausedJobAttemptsAloneWhenAYieldArrivesOnAnEarlierSessionsConnection()
      throws Exception {
    try (var worker = worker()) {
      worker.start("localhost", 1);
      var earlier = runtime.connection();
      worker.close();
      var connection = startThreeAttempts(worker);
      yieldToSeek(connection, LONG_HOLD, steady);
      promptly().until(() -> pausedAttempts().equals(List.of(steady)));

      yieldToSeek(earlier, LONG_HOLD);

      assertThat(pausedAttempts()).containsExactly(steady);
      exitAll();
    }
  }

  @Test
  @DisplayName("Should pause nothing when the yield carries no positive hold limit")
  void shouldPauseNothingWhenTheYieldCarriesNoPositiveHoldLimit() throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);
      yieldToSeek(connection, LONG_HOLD, otherSteady);
      promptly().until(() -> pausedAttempts().equals(List.of(otherSteady)));

      yieldToSeek(connection, Duration.ZERO, steady, otherSteady);

      promptly().until(() -> pausedAttempts().isEmpty());
      exitAll();
    }
  }

  @Test
  @DisplayName(
      "Should ignore a listed job attempt that the worker does not run and a yield for another"
          + " worker")
  void shouldIgnoreAListedJobAttemptThatTheWorkerDoesNotRunAndAYieldForAnotherWorker()
      throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);
      var unknown = variantJobBuilder().build();
      connection.deliver(
          yieldCommand(anotherWorker(connection), LONG_HOLD, List.of(steady, otherSteady, seek)));

      yieldToSeek(connection, LONG_HOLD, unknown, steady);

      promptly().until(() -> pausedAttempts().equals(List.of(steady)));
      assertThat(eventsOf(connection)).containsOnly(EventCase.JOB_ATTEMPT_STARTED).hasSize(3);
      exitAll();
    }
  }

  @Test
  @DisplayName(
      "Should resume a paused job attempt before asking its FFmpeg to quit when the server stops it")
  void shouldResumeAPausedJobAttemptBeforeAskingItsFfmpegToQuitWhenTheServerStopsIt()
      throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);
      yieldToSeek(connection, LONG_HOLD, steady);
      promptly().until(() -> pausedAttempts().equals(List.of(steady)));

      stopVariant(connection, steady);

      promptly().until(() -> eventsOf(connection).contains(EventCase.JOB_ATTEMPT_STOPPED));
      var stopped = processOf(steady);
      assertThat(stopped.stdinText()).isEqualTo("q");
      assertThat(stopped.quitArrivedWhilePausedBySignal()).isFalse();
      assertThat(stopped.wasTerminated()).isFalse();
      assertThat(stopped.isPausedBySignal()).isFalse();
      exitAll();
    }
  }

  @Test
  @DisplayName(
      "Should resume a paused job attempt once the hold limit passes when another job attempt's"
          + " start holds the worker")
  void
      shouldResumeAPausedJobAttemptOnceTheHoldLimitPassesWhenAnotherJobAttemptsStartHoldsTheWorker()
          throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);
      yieldToSeek(connection, Duration.ofSeconds(2), steady);
      promptly().until(() -> pausedAttempts().equals(List.of(steady)));
      var starting = Thread.ofVirtual().start(() -> startVariant(connection, slowStart));
      try {
        assertThat(slowLaunchEntered.await(EVENT_LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        promptly().until(() -> pausedAttempts().isEmpty());

        assertThat(starting.isAlive()).as("the slow start still holds the worker").isTrue();
      } finally {
        slowLaunchReleased.countDown();
        starting.join();
      }

      processOf(slowStart).exit();
      exitAll();
    }
  }

  private ScriptedProcess launch(UUID jobAttemptId) throws IOException {
    if (jobAttemptId.equals(fromProto(slowStart.getJobAttemptId()))) {
      slowLaunchEntered.countDown();
      awaitSlowLaunchRelease();
    }

    return ScriptedProcessLauncher.runningProcessBuilder().build();
  }

  private void awaitSlowLaunchRelease() throws InterruptedIOException {
    try {
      slowLaunchReleased.await();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      throw new InterruptedIOException("the slow launch was interrupted");
    }
  }

  private TranscodeWorker worker() {
    return workerBuilder(tempDir)
        .configuration(
            workerConfigurationBuilder()
                .availableSlots(4)
                .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, tempDir))
                .build())
        .runtime(runtime)
        .engine(engine(launcher))
        .build();
  }

  private ScriptedWorkerRuntime.Connection startThreeAttempts(TranscodeWorker worker)
      throws Exception {
    worker.start("localhost", 1);
    var connection = runtime.connection();
    startVariant(connection, steady);
    startVariant(connection, otherSteady);
    startVariant(connection, seek);
    promptly()
        .until(
            () ->
                eventsOf(connection)
                    .equals(
                        List.of(
                            EventCase.JOB_ATTEMPT_STARTED,
                            EventCase.JOB_ATTEMPT_STARTED,
                            EventCase.JOB_ATTEMPT_STARTED)));
    return connection;
  }

  private static void yieldToSeek(
      ScriptedWorkerRuntime.Connection connection, Duration holdLimit, VariantJob... paused) {
    connection.deliver(
        yieldCommand(connection.registration().getWorker(), holdLimit, List.of(paused)));
  }

  private static EstablishWorkerSessionResponse yieldCommand(
      WorkerIdentity target, Duration holdLimit, List<VariantJob> paused) {
    var command =
        YieldToSeekCommand.newBuilder()
            .setTarget(target)
            .addAllJobAttemptIds(paused.stream().map(VariantJob::getJobAttemptId).toList());
    command.getHoldLimitBuilder().setSeconds(holdLimit.getSeconds()).setNanos(holdLimit.getNano());
    return EstablishWorkerSessionResponse.newBuilder().setYieldToSeek(command).build();
  }

  private static WorkerIdentity anotherWorker(ScriptedWorkerRuntime.Connection connection) {
    return connection.registration().getWorker().toBuilder()
        .setBootId(toProto(UUID.randomUUID()))
        .build();
  }

  private List<VariantJob> pausedAttempts() {
    return List.of(steady, otherSteady, seek).stream()
        .filter(job -> processOf(job).isPausedBySignal())
        .toList();
  }

  private ScriptedProcess processOf(VariantJob job) {
    return launcher.process(fromProto(job.getJobAttemptId()));
  }

  private void exitAll() {
    List.of(steady, otherSteady, seek).forEach(job -> processOf(job).exit());
  }

  private static List<EventCase> eventsOf(ScriptedWorkerRuntime.Connection connection) {
    return connection.events().stream()
        .map(EstablishWorkerSessionRequest::getEventCase)
        .filter(
            eventCase ->
                eventCase != EventCase.REGISTRATION
                    && eventCase != EventCase.PROBE_CAPACITY_CHANGED
                    && eventCase != EventCase.JOB_ATTEMPT_PROGRESS)
        .toList();
  }

  private static ConditionFactory promptly() {
    return await().atMost(EVENT_LIMIT).pollInterval(Duration.ofMillis(5));
  }
}
