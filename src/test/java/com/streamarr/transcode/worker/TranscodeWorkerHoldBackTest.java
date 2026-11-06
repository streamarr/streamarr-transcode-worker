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
import build.buf.gen.streamarr.transcode.v1.HoldBackCommand;
import build.buf.gen.streamarr.transcode.v1.YieldToSeekCommand;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Transcode Worker Hold-Back Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerHoldBackTest {

  private static final Duration EVENT_LIMIT = Duration.ofSeconds(10);
  private static final Duration LONG_HOLD = Duration.ofMinutes(5);

  @TempDir Path tempDir;

  private final ScriptedWorkerRuntime runtime = new ScriptedWorkerRuntime();
  private final VariantJob ahead = variantJobBuilder().build();
  private final VariantJob fartherAhead = variantJobBuilder().build();
  private final VariantJob behind = variantJobBuilder().build();
  private final ScriptedProcessLauncher launcher = ScriptedProcessLauncher.running();

  @BeforeEach
  void setUp() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
  }

  @Test
  @DisplayName(
      "Should hold back only the listed job attempts and resume each one that a later hold-back"
          + " leaves out")
  void shouldHoldBackOnlyTheListedJobAttemptsAndResumeEachOneThatALaterHoldBackLeavesOut()
      throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);

      holdBack(connection, Map.of(ahead, LONG_HOLD, fartherAhead, LONG_HOLD));
      promptly().until(() -> heldAttempts().equals(List.of(ahead, fartherAhead)));
      holdBack(connection, Map.of(fartherAhead, LONG_HOLD));

      promptly().until(() -> heldAttempts().equals(List.of(fartherAhead)));
      holdBack(connection, Map.of());
      promptly().until(() -> heldAttempts().isEmpty());
      exitAll();
    }
  }

  @Test
  @DisplayName("Should resume a held job attempt at its release time when no later hold-back arrives")
  void shouldResumeAHeldJobAttemptAtItsReleaseTimeWhenNoLaterHoldBackArrives() throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);

      holdBack(connection, Map.of(ahead, Duration.ofMillis(300)));

      promptly().until(() -> heldAttempts().equals(List.of(ahead)));
      promptly().until(() -> heldAttempts().isEmpty());
      exitAll();
    }
  }

  @Test
  @DisplayName(
      "Should move a held job attempt's release to the time that a later hold-back listing it sets")
  void shouldMoveAHeldJobAttemptsReleaseToTheTimeThatALaterHoldBackListingItSets()
      throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);
      holdBack(connection, Map.of(ahead, LONG_HOLD));
      promptly().until(() -> heldAttempts().equals(List.of(ahead)));

      holdBack(connection, Map.of(ahead, Duration.ofMillis(300)));

      promptly().until(() -> heldAttempts().isEmpty());
      exitAll();
    }
  }

  @Test
  @DisplayName(
      "Should hold a job attempt back again right after its release, with none of a seek's rest")
  void shouldHoldAJobAttemptBackAgainRightAfterItsReleaseWithNoneOfASeeksRest() throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);
      holdBack(connection, Map.of(ahead, Duration.ofMillis(300)));
      promptly().until(() -> heldAttempts().equals(List.of(ahead)));
      promptly().until(() -> heldAttempts().isEmpty());

      holdBack(connection, Map.of(ahead, LONG_HOLD));

      promptly().until(() -> heldAttempts().equals(List.of(ahead)));
      exitAll();
    }
  }

  @Test
  @DisplayName(
      "Should keep a job attempt paused while either a seek's yield or a hold-back lists it")
  void shouldKeepAJobAttemptPausedWhileEitherASeeksYieldOrAHoldBackListsIt() throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);
      yieldToSeek(connection, ahead);
      promptly().until(() -> heldAttempts().equals(List.of(ahead)));
      holdBack(connection, Map.of(ahead, LONG_HOLD));

      // The yield's own pause of the other attempt shows that the worker applied it.
      yieldToSeek(connection, behind);
      promptly().until(() -> heldAttempts().equals(List.of(ahead, behind)));
      holdBack(connection, Map.of());

      promptly().until(() -> heldAttempts().equals(List.of(behind)));
      exitAll();
    }
  }

  @Test
  @DisplayName(
      "Should hold nothing back for a job attempt without a positive release time, one the worker"
          + " does not run, or a hold-back for another worker")
  void shouldHoldNothingBackWithoutAPositiveReleaseTimeAnUnknownJobAttemptOrAnotherWorker()
      throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);
      var unknown = variantJobBuilder().build();
      connection.deliver(
          holdBackCommand(anotherWorker(connection), Map.of(behind, LONG_HOLD)));

      holdBack(connection, Map.of(ahead, Duration.ZERO, unknown, LONG_HOLD, fartherAhead, LONG_HOLD));

      promptly().until(() -> heldAttempts().equals(List.of(fartherAhead)));
      assertThat(eventsOf(connection)).containsOnly(EventCase.JOB_ATTEMPT_STARTED).hasSize(3);
      exitAll();
    }
  }

  @Test
  @DisplayName(
      "Should leave the held job attempts alone when a hold-back arrives on an earlier session's"
          + " connection")
  void shouldLeaveTheHeldJobAttemptsAloneWhenAHoldBackArrivesOnAnEarlierSessionsConnection()
      throws Exception {
    try (var worker = worker()) {
      worker.start("localhost", 1);
      var earlier = runtime.connection();
      worker.close();
      var connection = startThreeAttempts(worker);
      holdBack(connection, Map.of(ahead, LONG_HOLD));
      promptly().until(() -> heldAttempts().equals(List.of(ahead)));

      holdBack(earlier, Map.of());

      assertThat(heldAttempts()).containsExactly(ahead);
      exitAll();
    }
  }

  @Test
  @DisplayName(
      "Should resume a held job attempt before asking its FFmpeg to quit when the server stops it")
  void shouldResumeAHeldJobAttemptBeforeAskingItsFfmpegToQuitWhenTheServerStopsIt()
      throws Exception {
    try (var worker = worker()) {
      var connection = startThreeAttempts(worker);
      holdBack(connection, Map.of(ahead, LONG_HOLD));
      promptly().until(() -> heldAttempts().equals(List.of(ahead)));

      stopVariant(connection, ahead);

      promptly().until(() -> eventsOf(connection).contains(EventCase.JOB_ATTEMPT_STOPPED));
      var stopped = processOf(ahead);
      assertThat(stopped.stdinText()).isEqualTo("q");
      assertThat(stopped.quitArrivedWhilePausedBySignal()).isFalse();
      assertThat(stopped.isPausedBySignal()).isFalse();
      exitAll();
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
    startVariant(connection, ahead);
    startVariant(connection, fartherAhead);
    startVariant(connection, behind);
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

  private static void holdBack(
      ScriptedWorkerRuntime.Connection connection, Map<VariantJob, Duration> releaseAfter) {
    connection.deliver(holdBackCommand(connection.registration().getWorker(), releaseAfter));
  }

  private static EstablishWorkerSessionResponse holdBackCommand(
      WorkerIdentity target, Map<VariantJob, Duration> releaseAfter) {
    var command = HoldBackCommand.newBuilder().setTarget(target);
    releaseAfter.forEach(
        (job, after) ->
            command
                .addJobAttemptsBuilder()
                .setJobAttemptId(job.getJobAttemptId())
                .getReleaseAfterBuilder()
                .setSeconds(after.getSeconds())
                .setNanos(after.getNano()));
    return EstablishWorkerSessionResponse.newBuilder().setHoldBack(command).build();
  }

  private static void yieldToSeek(
      ScriptedWorkerRuntime.Connection connection, VariantJob... paused) {
    var command =
        YieldToSeekCommand.newBuilder()
            .setTarget(connection.registration().getWorker())
            .addAllJobAttemptIds(List.of(paused).stream().map(VariantJob::getJobAttemptId).toList());
    command.getHoldLimitBuilder().setSeconds(LONG_HOLD.getSeconds());
    connection.deliver(EstablishWorkerSessionResponse.newBuilder().setYieldToSeek(command).build());
  }

  private static WorkerIdentity anotherWorker(ScriptedWorkerRuntime.Connection connection) {
    return connection.registration().getWorker().toBuilder()
        .setBootId(toProto(UUID.randomUUID()))
        .build();
  }

  private List<VariantJob> heldAttempts() {
    return List.of(ahead, fartherAhead, behind).stream()
        .filter(job -> processOf(job).isPausedBySignal())
        .toList();
  }

  private ScriptedProcess processOf(VariantJob job) {
    return launcher.process(fromProto(job.getJobAttemptId()));
  }

  private void exitAll() {
    List.of(ahead, fartherAhead, behind).forEach(job -> processOf(job).exit());
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
