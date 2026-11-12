package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.cancel;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.processBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.startCommand;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.ProbeCancelReason;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.fixtures.PausingClock;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime.Connection;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime.ReportCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Transcode Worker Probe Attempt Ended Without Report Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerProbeAttemptEndedWithoutReportTest {

  @TempDir Path tempDir;

  private final ScriptedWorkerRuntime runtime = new ScriptedWorkerRuntime();

  @BeforeEach
  void writeSource() throws Exception {
    Files.writeString(
        tempDir.resolve("movie.mkv"),
        """
        {"streams":[{"index":0,"codec_type":"video","codec_name":"h264"}]}
        """);
  }

  @Test
  @DisplayName(
      "Should stop the probe, send one event once the probe has ended and open no report call when"
          + " the server cancels a probe that still runs")
  void shouldSendOneEventOnceTheProbeHasEndedAndOpenNoReportCallWhenTheServerCancelsARunningProbe()
      throws Exception {
    var process = processBuilder().running(true).build();
    var ffprobe = new FfprobeExecutor(new ObjectMapper(), _ -> process);
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(ffprobe).build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, request);
        var running = runtime.probes().runNext();
        process.awaitStarted(running);

        cancel(connection, request);
        running.get();

        assertThat(process.isAlive()).isFalse();
        assertThat(connection.attemptsEndedWithoutReport())
            .containsExactly(request.getProbeAttemptId());
        assertThat(reportCallsOnceNothingIsLeftToSend(connection)).isEmpty();
      } finally {
        process.finish();
      }
    }
  }

  @Test
  @DisplayName(
      "Should send one event and hand no report over when the server cancels the probe attempt"
          + " while the ended probe has yet to hand its report over")
  void shouldSendOneEventAndHandNoReportOverWhenTheCancelArrivesBeforeTheEndedProbeHandsItOver()
      throws Exception {
    var clock = new PausingClock();
    var request = requestBuilder().build();

    try (var worker =
        workerBuilder(tempDir).runtime(runtime).probeSummary(new ProbeSummary(clock)).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      start(connection, request);
      // The probe summary reads the clock once the probe has ended and its unit is free, before
      // the report is handed over.
      clock.pauseAtTheNextReading();
      var ending = runtime.probes().runNext();
      clock.awaitPaused();

      cancel(connection, request, ProbeCancelReason.PROBE_CANCEL_REASON_TIMEOUT);
      clock.resume();
      ending.get();

      assertThat(connection.attemptsEndedWithoutReport())
          .containsExactly(request.getProbeAttemptId());
      assertThat(reportCallsOnceNothingIsLeftToSend(connection)).isEmpty();
      assertThat(connection.events())
          .as("a probe that has ended applies no brake at the probe timeout")
          .noneMatch(EstablishWorkerSessionRequest::hasProbeCapacityChanged);
    }
  }

  @ParameterizedTest(name = "the call waits for the server''s {0}")
  @ValueSource(strings = {"readiness", "answer"})
  @DisplayName(
      "Should end the open report call and send one event when the server cancels its probe"
          + " attempt")
  void shouldEndTheOpenReportCallAndSendOneEventWhenTheServerCancelsItsProbeAttempt(String awaited)
      throws Exception {
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      var call = openReportCallThatAwaits(awaited, request);
      var sent = call.messages().size();

      cancel(connection, request);

      assertThat(call.wasCancelled()).isTrue();
      assertThat(connection.attemptsEndedWithoutReport())
          .containsExactly(request.getProbeAttemptId());
      connection.grantReportMessage();
      connection.releaseReportAnswers();
      assertThat(reportCallsOnceNothingIsLeftToSend(connection)).containsExactly(call);
      assertThat(call.messages()).hasSize(sent);
      assertThat(connection.attemptsEndedWithoutReport())
          .as("the call that the cancel command ended sends no event of its own")
          .containsExactly(request.getProbeAttemptId());
    }
  }

  @Test
  @DisplayName(
      "Should open no report call and send one event when the server cancels the probe attempt"
          + " before the report's thread opens the call")
  void shouldOpenNoReportCallAndSendOneEventWhenTheCancelArrivesBeforeTheReportsThreadOpensTheCall()
      throws Exception {
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.holdReportThreads();
      try {
        start(connection, request);
        runtime.probes().drain();

        cancel(connection, request);

        assertThat(connection.attemptsEndedWithoutReport())
            .containsExactly(request.getProbeAttemptId());
        connection.releaseReportThreads();
        assertThat(reportCallsOnceNothingIsLeftToSend(connection)).isEmpty();
        assertThat(connection.attemptsEndedWithoutReport())
            .containsExactly(request.getProbeAttemptId());
      } finally {
        connection.releaseReportThreads();
      }
    }
  }

  @ParameterizedTest(name = "the server has answered the attempt''s report call: {0}")
  @ValueSource(booleans = {false, true})
  @DisplayName(
      "Should send one event at once when the server cancels a probe attempt for which the worker"
          + " has no probe, no waiting report and no open report call")
  void shouldSendOneEventAtOnceWhenTheWorkerHasNothingForTheCanceledProbeAttempt(boolean answered)
      throws Exception {
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      if (answered) {
        start(connection, request);
        runtime.probes().drain();
        connection.awaitReportThreads();
      }

      var opened = connection.reportCalls();

      cancel(connection, request);

      assertThat(connection.attemptsEndedWithoutReport())
          .containsExactly(request.getProbeAttemptId());
      assertThat(reportCallsOnceNothingIsLeftToSend(connection)).isEqualTo(opened);
      assertThat(opened).noneMatch(ReportCall::wasCancelled);
    }
  }

  @Test
  @DisplayName(
      "Should free the probe's unit before the event leaves when the server cancels the probe, so"
          + " that the worker starts a probe that the server sends for the unit as soon as the"
          + " event arrives")
  void shouldFreeTheProbesUnitBeforeTheEventLeavesWhenTheServerCancelsTheProbe() throws Exception {
    var canceled = requestBuilder().build();
    var next = requestBuilder().build();
    var oneUnit =
        workerConfigurationBuilder()
            .availableSlots(1)
            .backgroundProbeCapacity(new BackgroundProbeCapacity.Fixed(1))
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, tempDir))
            .build();

    try (var worker = workerBuilder(tempDir).configuration(oneUnit).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      start(connection, canceled);
      cancel(connection, canceled);
      connection.deliverWhenTheWorkerSends(
          event ->
              event
                  .getProbeAttemptEndedWithoutReport()
                  .getProbeAttemptId()
                  .equals(canceled.getProbeAttemptId()),
          startCommand(connection, next));

      runtime.probes().drain();

      assertThat(connection.results())
          .as("the worker refuses a probe that finds no free unit")
          .singleElement()
          .satisfies(
              result -> {
                assertThat(result.getProbeAttemptId()).isEqualTo(next.getProbeAttemptId());
                assertThat(result.hasMedia()).isTrue();
              });
    }
  }

  @Test
  @DisplayName(
      "Should send no event on a later worker session when a canceled probe ends after its worker"
          + " session was replaced")
  void shouldSendNoEventOnALaterWorkerSessionWhenACanceledProbeEndsAfterItsSessionWasReplaced()
      throws Exception {
    var process = processBuilder().running(true).deferredTermination(true).build();
    var ffprobe = new FfprobeExecutor(new ObjectMapper(), _ -> process);
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(ffprobe).build();
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        worker.start("localhost", 1);
        var old = runtime.connection();
        var oldProbes = runtime.probes();
        start(old, request);
        var running = oldProbes.runNext();
        process.awaitStarted(running);
        cancel(old, request);
        process.awaitExitWait(running);
        var closing = CompletableFuture.runAsync(worker::close, tasks);
        oldProbes.awaitClosing(closing);

        worker.start("localhost", 1);
        var replacement = runtime.connection();
        process.finish();
        running.get();
        closing.get();

        assertThat(old.attemptsEndedWithoutReport()).isEmpty();
        assertThat(replacement.attemptsEndedWithoutReport()).isEmpty();
      } finally {
        process.finish();
      }
    }
  }

  @ParameterizedTest(name = "the report call {0}")
  @EnumSource(EndWithoutAnswer.class)
  @DisplayName(
      "Should discard the report, send one event and open no second report call when the report"
          + " call ends without the server's answer")
  void shouldDiscardTheReportAndSendOneEventWhenTheReportCallEndsWithoutTheServersAnswer(
      EndWithoutAnswer end) throws Exception {
    var request = requestBuilder().build();
    var configuration =
        end.bounds
            .apply(
                workerConfigurationBuilder()
                    .availableSlots(2)
                    .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, tempDir)))
            .build();

    try (var worker =
        workerBuilder(tempDir).configuration(configuration).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      end.preparation.accept(connection);
      start(connection, request);
      runtime.probes().drain();
      end.ending.end(connection.awaitReportCalls(1).getFirst());

      assertThat(reportCallsOnceNothingIsLeftToSend(connection))
          .as("the worker sends no report twice")
          .hasSize(1);
      assertThat(connection.attemptsEndedWithoutReport())
          .containsExactly(request.getProbeAttemptId());
      assertThat(worker.hasAcceptedSession()).isTrue();
    }
  }

  private enum EndWithoutAnswer {
    IS_REFUSED_WITH_A_STATUS(bounds -> bounds, Connection::refuseNextReport, _ -> {}),
    LOSES_ITS_TRANSPORT(
        bounds -> bounds,
        Connection::withholdReportAnswers,
        call -> {
          call.awaitSentWhole();
          call.failTransport();
        }),
    IS_NOT_READY_WITHIN_THE_UPLOAD_READINESS_TIMEOUT(
        bounds -> bounds.uploadReadinessTimeout(Duration.ofMillis(200)),
        Connection::withholdReportReadiness,
        _ -> {}),
    IS_NOT_ANSWERED_WITHIN_THE_UPLOAD_ACKNOWLEDGEMENT_TIMEOUT(
        bounds -> bounds.uploadAcknowledgementTimeout(Duration.ofMillis(200)),
        Connection::withholdReportAnswers,
        _ -> {}),
    IS_ANSWERED_WITH_ANOTHER_LENGTH(
        bounds -> bounds,
        Connection::withholdReportAnswers,
        call -> {
          call.awaitSentWhole();
          call.answerWithAcceptedLength(call.content().length - 1L);
        });

    private final UnaryOperator<TranscodeWorkerConfiguration.TranscodeWorkerConfigurationBuilder>
        bounds;
    private final Consumer<Connection> preparation;
    private final Ending ending;

    EndWithoutAnswer(
        UnaryOperator<TranscodeWorkerConfiguration.TranscodeWorkerConfigurationBuilder> bounds,
        Consumer<Connection> preparation,
        Ending ending) {
      this.bounds = bounds;
      this.preparation = preparation;
      this.ending = ending;
    }

    @Override
    public String toString() {
      return name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }
  }

  // What the scripted server does to the open report call so that it ends without an answer;
  // nothing when a refusal or one of the worker's bounds ends the call.
  @FunctionalInterface
  private interface Ending {
    void end(ReportCall call) throws Exception;
  }

  @Test
  @DisplayName("Should send no event when the server answers the report call")
  void shouldSendNoEventWhenTheServerAnswersTheReportCall() throws Exception {
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      start(connection, request);
      runtime.probes().drain();

      assertThat(connection.reports()).hasSize(1);
      assertThat(connection.attemptsEndedWithoutReport()).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "Should answer a cancel command with an event of its own when the attempt's report call has"
          + " already ended without the server's answer")
  void shouldAnswerACancelCommandWithAnEventOfItsOwnWhenTheReportCallHasEndedWithoutAnAnswer()
      throws Exception {
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.refuseNextReport();
      start(connection, request);
      runtime.probes().drain();
      connection.awaitReportThreads();

      cancel(connection, request);

      assertThat(connection.attemptsEndedWithoutReport())
          .containsExactly(request.getProbeAttemptId(), request.getProbeAttemptId());
      assertThat(reportCallsOnceNothingIsLeftToSend(connection)).hasSize(1);
    }
  }

  @Test
  @DisplayName(
      "Should send no event on either worker session when a report call is open as its worker"
          + " session closes and another worker session follows")
  void shouldSendNoEventOnEitherWorkerSessionWhenAReportCallIsOpenAsItsWorkerSessionCloses()
      throws Exception {
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var old = runtime.connection();
      var call = openReportCallThatAwaits("answer", request);

      worker.close();
      worker.start("localhost", 1);
      var replacement = runtime.connection();
      call.failTransport();

      assertThat(call.wasCancelled()).isTrue();
      assertThat(old.attemptsEndedWithoutReport()).isEmpty();
      assertThat(replacement.attemptsEndedWithoutReport()).isEmpty();
    }
  }

  // Runs the probe to its end and returns its report call, which then waits for the server to be
  // ready for the call's first message, or for the server's answer to the whole report.
  private ReportCall openReportCallThatAwaits(String awaited, ProbeRequest request)
      throws Exception {
    var connection = runtime.connection();
    if (awaited.equals("readiness")) {
      connection.withholdReportReadiness();
    } else {
      connection.withholdReportAnswers();
    }

    start(connection, request);
    runtime.probes().drain();
    var call = connection.awaitReportCalls(1).getFirst();
    if (awaited.equals("answer")) {
      call.awaitSentWhole();
    }

    return call;
  }

  private static List<ReportCall> reportCallsOnceNothingIsLeftToSend(Connection connection) {
    connection.awaitReportThreads();
    return connection.reportCalls();
  }
}
