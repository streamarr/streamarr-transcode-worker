package com.streamarr.transcode.worker;

import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.cancel;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.BitrateScanChunk;
import build.buf.gen.streamarr.transcode.v1.ProbePriority;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.Uuid;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime.Connection;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime.ReportCall;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

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

  @ParameterizedTest(name = "a limit of {0} lets the other reports hold {1}")
  @CsvSource({"1, 1", "2, 1", "3, 1", "24, 22"})
  @DisplayName(
      "Should let the reports of other probes than playback probes hold at most the limit less the"
          + " playback probe capacity of report calls at once, and at least one, when more of them"
          + " are ready")
  void shouldLetTheOtherReportsHoldAtMostTheLimitLessThePlaybackProbeCapacityWhenMoreAreReady(
      int limit, int most) throws Exception {
    var runtime = acceptingReportCalls(limit);
    var requests =
        Stream.generate(TranscodeWorkerProbeReportLimitTest::background).limit(most + 2L).toList();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportAnswers();
      for (var request : requests) {
        probe(runtime, request);
      }

      assertThat(attemptsOf(connection.reportCallsAwaitingAnswers()))
          .containsExactlyInAnyOrderElementsOf(idsOf(requests.subList(0, most)));
      connection.releaseReportAnswers();
      assertThat(connection.reports()).hasSameSizeAs(requests);
    }
  }

  @ParameterizedTest(name = "a limit of {0}")
  @ValueSource(ints = {1, 2, 3, 24})
  @DisplayName(
      "Should keep at most the report calls open that the server accepts at once, let the reports"
          + " of playback probes take every one of them and make the others wait, when more"
          + " playback probes' reports are ready than the limit")
  void shouldKeepAtMostTheReportCallsOpenThatTheServerAcceptsWhenMorePlaybackReportsAreReady(
      int limit) throws Exception {
    var runtime = acceptingReportCalls(limit);
    var playback =
        Stream.generate(TranscodeWorkerProbeReportLimitTest::playback).limit(limit + 1L).toList();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportAnswers();
      for (var request : playback) {
        probe(runtime, request);
      }

      assertThat(attemptsOf(connection.reportCallsAwaitingAnswers()))
          .as("one report waits for a call")
          .containsExactlyInAnyOrderElementsOf(idsOf(playback.subList(0, limit)));
      connection.releaseReportAnswers();
      assertThat(connection.reports()).hasSameSizeAs(playback);
      assertThat(connection.mostReportCallsOpenAtOnce()).isEqualTo(limit);
    }
  }

  @ParameterizedTest(name = "a limit of {0} keeps {2} beside the {1} of the other reports")
  @CsvSource({"1, 1, 0", "2, 1, 1", "3, 1, 2", "24, 22, 2"})
  @DisplayName(
      "Should keep the report calls beyond the other reports' most for the reports of playback"
          + " probes when reports of both kinds are ready")
  void shouldKeepTheCallsBeyondTheOtherReportsMostForPlaybackProbesWhenBothKindsAreReady(
      int limit, int otherMost, int kept) throws Exception {
    var runtime = acceptingReportCalls(limit);
    var others =
        Stream.generate(TranscodeWorkerProbeReportLimitTest::background)
            .limit(otherMost + 1L)
            .toList();
    var playback =
        Stream.generate(TranscodeWorkerProbeReportLimitTest::playback).limit(kept + 1L).toList();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportAnswers();
      for (var request : others) {
        probe(runtime, request);
      }

      for (var request : playback) {
        probe(runtime, request);
      }

      var open = new ArrayList<>(idsOf(others.subList(0, otherMost)));
      open.addAll(idsOf(playback.subList(0, kept)));
      assertThat(attemptsOf(connection.reportCallsAwaitingAnswers()))
          .containsExactlyInAnyOrderElementsOf(open);
      assertThat(connection.mostReportCallsOpenAtOnce()).isEqualTo(otherMost + kept);
    }
  }

  @Test
  @DisplayName(
      "Should keep a freed report call for the reports of playback probes when only other reports"
          + " wait")
  void shouldKeepAFreedReportCallForTheReportsOfPlaybackProbesWhenOnlyOtherReportsWait()
      throws Exception {
    var runtime = acceptingReportCalls(3);
    var open = background();
    var waiting = background();
    var playback = playback();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportAnswers();
      probe(runtime, open);
      probe(runtime, waiting);
      probe(runtime, playback);
      assertThat(attemptsOf(connection.reportCallsAwaitingAnswers()))
          .containsExactlyInAnyOrderElementsOf(idsOf(List.of(open, playback)));

      answer(connection, playback);

      assertThat(attemptsOf(connection.reportCallsAwaitingAnswers()))
          .as("the other report waits although two report calls are free")
          .containsExactlyElementsOf(idsOf(List.of(open)));
      answer(connection, open);
      assertThat(attemptsOf(connection.reportCallsAwaitingAnswers()))
          .containsExactlyElementsOf(idsOf(List.of(waiting)));
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Scan.class)
  @DisplayName(
      "Should make the report of a scan with playback priority wait and leave the kept report"
          + " calls free when the other reports hold their report calls")
  void shouldMakeTheReportOfAScanWithPlaybackPriorityWaitWhenTheOtherReportsHoldTheirReportCalls(
      Scan scan) throws Exception {
    var runtime = acceptingReportCalls(3);
    var open = background();
    var scanning = scan.withPlaybackPriority();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportAnswers();
      probe(runtime, open);
      probe(runtime, scanning);

      assertThat(attemptsOf(connection.reportCallsAwaitingAnswers()))
          .as("the scan's report waits although two report calls are free")
          .containsExactlyElementsOf(idsOf(List.of(open)));
      answer(connection, open);
      assertThat(attemptsOf(connection.reportCallsAwaitingAnswers()))
          .containsExactlyElementsOf(idsOf(List.of(scanning)));
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Scan.class)
  @DisplayName(
      "Should make the report of a refused scan with playback priority wait and leave the kept"
          + " report calls free when the other reports hold their report calls")
  void shouldMakeTheReportOfARefusedScanWithPlaybackPriorityWaitWhenTheOtherReportsHoldTheirCalls(
      Scan scan) throws Exception {
    var runtime = acceptingReportCalls(3);
    var open = background();
    // The first scan holds the one unit of the keyframe packet scan capacity until it runs.
    var holding = scan.withPlaybackPriority();
    var refused = scan.withPlaybackPriority();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportAnswers();
      probe(runtime, open);
      start(connection, holding);
      start(connection, refused);

      assertThat(attemptsOf(connection.reportCallsAwaitingAnswers()))
          .as("the refused scan's report waits although two report calls are free")
          .containsExactlyElementsOf(idsOf(List.of(open)));
      answer(connection, open);
      assertThat(attemptsOf(connection.reportCallsAwaitingAnswers()))
          .containsExactlyElementsOf(idsOf(List.of(refused)));
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Scan.class)
  @DisplayName(
      "Should send the report of a playback probe first when the report of a scan with playback"
          + " priority that ended earlier waits")
  void shouldSendALaterPlaybackProbesReportFirstWhenTheReportOfAScanWithPlaybackPriorityWaits(
      Scan scan) throws Exception {
    var runtime = acceptingReportCalls(1);
    var open = background();
    var scanning = scan.withPlaybackPriority();
    var playback = playback();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      holdTheOneReportCall(runtime, open);
      probe(runtime, scanning);
      probe(runtime, playback);

      connection.releaseReportAnswers();

      assertThat(reportedAttempts(connection)).containsExactly(idsOf(open, playback, scanning));
    }
  }

  // A keyframe packet scan and a bitrate scan chunk are never playback probes, whatever their
  // priority.
  private enum Scan {
    A_KEYFRAME_PACKET_SCAN,
    A_BITRATE_SCAN_CHUNK;

    private ProbeRequest withPlaybackPriority() {
      var request = requestBuilder().setPriority(ProbePriority.PROBE_PRIORITY_PLAYBACK);
      return switch (this) {
        case A_KEYFRAME_PACKET_SCAN -> request.setScanKeyframePackets(true).build();
        case A_BITRATE_SCAN_CHUNK ->
            request.setBitrateScanChunk(BitrateScanChunk.getDefaultInstance()).build();
      };
    }
  }

  @Test
  @DisplayName(
      "Should open every report call at once and keep none when the server states no limit")
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
  @DisplayName(
      "Should send the reports of playback probes before the other reports when both wait for a"
          + " report call")
  void shouldSendTheReportsOfPlaybackProbesBeforeTheOtherReportsWhenBothWait() throws Exception {
    var runtime = acceptingReportCalls(1);
    var open = background();
    var other = background();
    var playback = playback();

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

  private static ProbeRequest playback() {
    return requestBuilder().setPriority(ProbePriority.PROBE_PRIORITY_PLAYBACK).build();
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
    return idsOf(List.of(requests)).toArray(Uuid[]::new);
  }

  private static List<Uuid> idsOf(List<ProbeRequest> requests) {
    return requests.stream().map(ProbeRequest::getProbeAttemptId).toList();
  }

  private static List<Uuid> attemptsOf(List<ReportCall> calls) {
    return calls.stream().map(call -> call.metadata().getProbeAttemptId()).toList();
  }

  // Answers the report call of this probe attempt, which awaits its answer, as the server does
  // when it accepts the whole report.
  private static void answer(Connection connection, ProbeRequest request) {
    connection.reportCallsAwaitingAnswers().stream()
        .filter(call -> call.metadata().getProbeAttemptId().equals(request.getProbeAttemptId()))
        .forEach(call -> call.answerWithAcceptedLength(call.content().length));
  }
}
