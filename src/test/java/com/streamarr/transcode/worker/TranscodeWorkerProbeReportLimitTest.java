package com.streamarr.transcode.worker;

import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.cancel;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbePriority;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.Uuid;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime.Connection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Transcode Worker Probe Report Limit Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerProbeReportLimitTest {

  @TempDir Path tempDir;

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
      "Should keep at most the report calls open that the server accepts at once and make the"
          + " other reports wait")
  void shouldKeepAtMostTheReportCallsOpenThatTheServerAcceptsAtOnce() throws Exception {
    var runtime = acceptingReportCalls(2);
    var requests = List.of(background(), background(), background(), background());

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportAnswers();
      for (var request : requests) {
        probe(runtime, request);
      }

      for (var call : connection.awaitReportCalls(2)) {
        call.awaitSentWhole();
      }

      assertThat(connection.reportCalls()).as("two reports wait for a call").hasSize(2);
      connection.releaseReportAnswers();
      assertThat(connection.reports()).hasSize(4);
      assertThat(connection.mostReportCallsOpenAtOnce()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName("Should open every report call at once when the server states no limit")
  void shouldOpenEveryReportCallAtOnceWhenTheServerStatesNoLimit() throws Exception {
    var runtime = acceptingReportCalls(0);

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportAnswers();
      for (var request : List.of(background(), background(), background())) {
        probe(runtime, request);
      }

      assertThat(connection.awaitReportCalls(3)).hasSize(3);
      assertThat(connection.mostReportCallsOpenAtOnce()).isEqualTo(3);
    }
  }

  @Test
  @DisplayName("Should send waiting reports first in, first out when they share a priority")
  void shouldSendWaitingReportsFirstInFirstOutWhenTheyShareAPriority() throws Exception {
    var runtime = acceptingReportCalls(1);
    var open = background();
    var first = background();
    var second = background();
    var third = background();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      holdTheOneReportCall(runtime, open);
      probe(runtime, first);
      probe(runtime, second);
      probe(runtime, third);

      connection.releaseReportAnswers();

      assertThat(reportedAttempts(connection)).containsExactly(idsOf(open, first, second, third));
      assertThat(connection.mostReportCallsOpenAtOnce()).isEqualTo(1);
    }
  }

  @Test
  @DisplayName("Should send the reports of probes with playback priority before the other reports")
  void shouldSendTheReportsOfProbesWithPlaybackPriorityBeforeTheOtherReports() throws Exception {
    var runtime = acceptingReportCalls(1);
    var open = background();
    var other = background();
    var playback = requestBuilder().setPriority(ProbePriority.PROBE_PRIORITY_PLAYBACK).build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      holdTheOneReportCall(runtime, open);
      probe(runtime, other);
      probe(runtime, playback);

      connection.releaseReportAnswers();

      assertThat(reportedAttempts(connection)).containsExactly(idsOf(open, playback, other));
    }
  }

  @Test
  @DisplayName(
      "Should discard a waiting report, send one event at once and open no report call for the"
          + " report when the server cancels its probe attempt")
  void shouldDiscardAWaitingReportAndSendOneEventAtOnceWhenTheServerCancelsItsProbeAttempt()
      throws Exception {
    var runtime = acceptingReportCalls(1);
    var open = background();
    var canceled = background();
    var later = background();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      holdTheOneReportCall(runtime, open);
      probe(runtime, canceled);
      probe(runtime, later);

      cancel(connection, canceled);

      assertThat(connection.attemptsEndedWithoutReport())
          .containsExactly(canceled.getProbeAttemptId());
      connection.releaseReportAnswers();
      assertThat(reportedAttempts(connection)).containsExactly(idsOf(open, later));
      assertThat(connection.attemptsEndedWithoutReport())
          .containsExactly(canceled.getProbeAttemptId());
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(SessionEnd.class)
  @DisplayName(
      "Should discard the waiting reports, open no further call and send no event when the session"
          + " ends")
  void shouldDiscardTheWaitingReportsOpenNoFurtherCallAndSendNoEventWhenTheSessionEnds(
      SessionEnd end) throws Exception {
    var runtime = acceptingReportCalls(1);
    var open = background();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      holdTheOneReportCall(runtime, open);
      probe(runtime, background());
      probe(runtime, background());

      end.end(connection, worker);
      connection.awaitReportThreads();
      connection.releaseReportAnswers();

      assertThat(connection.reportCalls())
          .singleElement()
          .satisfies(call -> assertThat(call.wasCancelled()).isTrue());
      assertThat(connection.attemptsEndedWithoutReport()).isEmpty();
    }
  }

  private enum SessionEnd {
    THE_CONNECTION_FAILS,
    THE_SERVER_COMPLETES_THE_SESSION,
    THE_WORKER_CLOSES;

    private void end(Connection connection, TranscodeWorker worker) {
      Runnable ending =
          switch (this) {
            case THE_CONNECTION_FAILS -> () -> connection.fail("connection lost");
            case THE_SERVER_COMPLETES_THE_SESSION -> connection::complete;
            case THE_WORKER_CLOSES -> worker::close;
          };
      ending.run();
    }
  }

  private static ScriptedWorkerRuntime acceptingReportCalls(int probeReportLimit) {
    return ScriptedWorkerRuntime.accepting(
        WorkerSessionAccepted.newBuilder().setProbeReportLimit(probeReportLimit));
  }

  private static ProbeRequest background() {
    return requestBuilder().setPriority(ProbePriority.PROBE_PRIORITY_BACKGROUND).build();
  }

  // Runs the probe to its end, which hands its report over.
  private static void probe(ScriptedWorkerRuntime runtime, ProbeRequest request) throws Exception {
    start(runtime.connection(), request);
    runtime.probes().drain();
  }

  // Sends this probe's report whole and leaves its call, the one that the server accepts at once,
  // waiting for its answer, so that every later report waits.
  private static void holdTheOneReportCall(ScriptedWorkerRuntime runtime, ProbeRequest request)
      throws Exception {
    var connection = runtime.connection();
    connection.withholdReportAnswers();
    probe(runtime, request);
    connection.awaitReportCalls(1).getFirst().awaitSentWhole();
  }

  // The probe attempts in the order in which the worker opened their report calls, once it has sent
  // every report.
  private static List<Uuid> reportedAttempts(Connection connection) {
    connection.awaitReportThreads();
    return connection.reportCalls().stream()
        .map(call -> call.metadata().getProbeAttemptId())
        .toList();
  }

  private static Uuid[] idsOf(ProbeRequest... requests) {
    return List.of(requests).stream().map(ProbeRequest::getProbeAttemptId).toArray(Uuid[]::new);
  }
}
