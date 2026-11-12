package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.cancel;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.failure;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.fileContentsProducer;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.sourceBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest.EventCase;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReportMetadata;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.ReportProbeAttemptRequest;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime.Connection;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime.ReportCall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Transcode Worker Probe Report Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerProbeReportTest {

  // The most bytes of a probe report that one message of its report call carries.
  private static final int MESSAGE_BYTES = 65_536;
  private static final Set<Integer> PROBE_REPORT_SESSION_EVENT_NUMBERS = Set.of(6, 7, 9);

  private static final String ONE_VIDEO_STREAM =
      """
      {"streams":[{"index":0,"codec_type":"video","codec_name":"h264"}]}
      """;

  @TempDir Path tempDir;

  private final ScriptedWorkerRuntime runtime = new ScriptedWorkerRuntime();

  @Test
  @DisplayName(
      "Should end a probe with one report call that names the worker session, the worker and the"
          + " probe attempt when the probe succeeds")
  void shouldEndAProbeWithOneReportCallThatNamesTheSessionTheWorkerAndTheAttemptWhenItSucceeds()
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), ONE_VIDEO_STREAM);
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      start(connection, request);
      runtime.probes().drain();

      assertThat(connection.reports())
          .singleElement()
          .satisfies(report -> assertThat(report.getResult().hasMedia()).isTrue());
      assertThat(connection.reportCalls())
          .singleElement()
          .satisfies(
              call -> {
                assertThat(call.metadata())
                    .isEqualTo(
                        ProbeAttemptReportMetadata.newBuilder()
                            .setWorkerSessionId(connection.workerSessionId())
                            .setWorker(connection.registration().getWorker())
                            .setProbeAttemptId(request.getProbeAttemptId())
                            .setReportLengthBytes(call.report().getSerializedSize())
                            .build());
                assertThat(call.content()).hasSize(call.report().getSerializedSize());
              });
      assertThat(probeReportEventsOnTheSession(connection)).isEmpty();
    }
  }

  @Test
  @DisplayName("Should end a probe with one report call when the probe fails")
  void shouldEndAProbeWithOneReportCallWhenTheProbeFails() throws Exception {
    var request = requestBuilder().setSource(sourceBuilder().setRelativeKey("../outside")).build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      start(connection, request);
      runtime.probes().drain();

      assertThat(connection.results())
          .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE));
      assertThat(connection.reportCalls()).hasSize(1);
      assertThat(probeReportEventsOnTheSession(connection)).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "Should end a probe with one report call, without waiting for the server on the control"
          + " stream's thread, when the worker refuses the probe for no free capacity")
  void shouldEndAProbeWithOneReportCallWithoutWaitingWhenTheWorkerRefusesItForNoFreeCapacity()
      throws Exception {
    // The first keyframe packet scan holds the one unit of its capacity until it runs.
    var holding = requestBuilder().setScanKeyframePackets(true).build();
    var refused = requestBuilder().setScanKeyframePackets(true).build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportReadiness();
      start(connection, holding);

      // This thread delivers the command as the control stream's thread does.
      start(connection, refused);

      var call = connection.awaitReportCalls(1).getFirst();
      assertThat(call.messages()).as("the call is not ready for a message").isEmpty();
      connection.grantReportMessage();
      connection.grantReportMessage();
      assertThat(connection.results())
          .containsExactly(failure(refused, ProbeFailure.PROBE_FAILURE_NO_FREE_CAPACITY));
      assertThat(connection.reportCalls()).hasSize(1);
      assertThat(probeReportEventsOnTheSession(connection)).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "Should send a probe's report once when the server cancels the probe after it has ended")
  void shouldSendAProbesReportOnceWhenTheServerCancelsTheProbeAfterItHasEnded() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), ONE_VIDEO_STREAM);
    var ended = requestBuilder().build();
    var next = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      start(connection, ended);
      runtime.probes().drain();
      connection.awaitReportThreads();

      cancel(connection, ended);
      start(connection, next);
      runtime.probes().drain();

      assertThat(connection.reports())
          .extracting(report -> report.getResult().getProbeAttemptId())
          .containsExactly(ended.getProbeAttemptId(), next.getProbeAttemptId());
      assertThat(connection.reports())
          .allSatisfy(report -> assertThat(report.getResult().hasMedia()).isTrue());
      assertThat(connection.reportCalls()).hasSize(2);
    }
  }

  @ParameterizedTest(name = "a report of {0} bytes")
  @ValueSource(
      ints = {MESSAGE_BYTES - 1, MESSAGE_BYTES, MESSAGE_BYTES + 1, 3 * MESSAGE_BYTES, 200_000})
  @DisplayName(
      "Should send the encoded report's consecutive bytes after the first message, at most 65,536"
          + " in each message and none empty, when the report's length lies near a multiple of"
          + " 65,536 bytes")
  void shouldSendTheReportsBytesInMessagesOfAtMost65536WhenItsLengthLiesNearAMultipleOfThat(
      int reportBytes) throws Exception {
    var request = requestBuilder().build();
    var expected = writeSourceWhoseReportTakes(request, reportBytes);

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      start(connection, request);
      runtime.probes().drain();

      assertThat(connection.reports()).containsExactly(expected);
      var call = connection.reportCalls().getFirst();
      assertThat(call.messages())
          .extracting(ReportProbeAttemptRequest::hasMetadata)
          .startsWith(true)
          .containsOnlyOnce(true);
      assertThat(call.metadata().getReportLengthBytes()).isEqualTo(reportBytes);
      assertThat(call.dataMessageLengths()).isEqualTo(messageLengthsOf(reportBytes));
      assertThat(call.content()).isEqualTo(expected.toByteArray());
    }
  }

  @Test
  @DisplayName("Should send a message only when the report call is ready for it")
  void shouldSendAMessageOnlyWhenTheReportCallIsReadyForIt() throws Exception {
    var request = requestBuilder().build();
    var expected = writeSourceWhoseReportTakes(request, 3 * MESSAGE_BYTES);

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportReadiness();
      start(connection, request);
      runtime.probes().drain();
      var call = connection.awaitReportCalls(1).getFirst();

      // The first message and three of data.
      for (var granted = 1; granted <= 4; granted++) {
        connection.grantReportMessage();
        assertThat(call.awaitMessages(granted)).hasSize(granted);
      }

      assertThat(connection.reports()).containsExactly(expected);
      assertThat(connection.reportMessagesSentWhileNotReady()).isZero();
    }
  }

  @Test
  @DisplayName(
      "Should open another report call when an earlier one still waits for the server to be ready")
  void shouldOpenAnotherReportCallWhenAnEarlierOneStillWaitsForTheServerToBeReady()
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), ONE_VIDEO_STREAM);
    var waiting = requestBuilder().build();
    var next = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportReadiness();
      start(connection, waiting);
      runtime.probes().drain();
      connection.awaitReportCalls(1);

      // Opening a call takes the worker monitor, which a call that waits must not hold.
      start(connection, next);
      runtime.probes().drain();

      assertThat(connection.awaitReportCalls(2)).hasSize(2);
      assertThat(connection.mostReportCallsOpenAtOnce()).isEqualTo(2);
    }
  }

  @Test
  @DisplayName(
      "Should cancel the report call and keep the session when the call is not ready for a message"
          + " within the upload readiness timeout")
  void shouldCancelTheReportCallAndKeepTheSessionWhenTheCallIsNotReadyWithinTheReadinessTimeout()
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), ONE_VIDEO_STREAM);
    var unready = requestBuilder().build();
    var later = requestBuilder().build();
    var configuration =
        configurationBuilder().uploadReadinessTimeout(Duration.ofMillis(200)).build();

    try (var worker =
        workerBuilder(tempDir).configuration(configuration).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportReadiness();
      start(connection, unready);
      runtime.probes().drain();
      connection.awaitReportThreads();

      assertThat(connection.reportCalls())
          .singleElement()
          .satisfies(
              call -> {
                assertThat(call.wasCancelled()).isTrue();
                assertThat(call.messages()).isEmpty();
              });

      // A report of one small result takes its first message and one of data.
      connection.grantReportMessage();
      connection.grantReportMessage();
      start(connection, later);
      runtime.probes().drain();

      assertThat(worker.hasAcceptedSession()).isTrue();
      assertThat(connection.reports())
          .extracting(report -> report.getResult().getProbeAttemptId())
          .containsExactly(later.getProbeAttemptId());
      assertThat(connection.reportCalls()).as("the worker sends no report twice").hasSize(2);
    }
  }

  @Test
  @DisplayName(
      "Should cancel the report call and keep the session when the server does not answer within"
          + " the upload acknowledgement timeout of the last message")
  void shouldCancelTheReportCallAndKeepTheSessionWhenTheServerDoesNotAnswerWithinTheTimeout()
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), ONE_VIDEO_STREAM);
    var unanswered = requestBuilder().build();
    var later = requestBuilder().build();
    var configuration =
        configurationBuilder().uploadAcknowledgementTimeout(Duration.ofMillis(200)).build();

    try (var worker =
        workerBuilder(tempDir).configuration(configuration).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportAnswers();
      start(connection, unanswered);
      runtime.probes().drain();
      connection.awaitReportThreads();

      assertThat(connection.reportCalls())
          .singleElement()
          .satisfies(
              call -> {
                assertThat(call.wasSentWhole()).isTrue();
                assertThat(call.wasCancelled()).isTrue();
              });

      connection.releaseReportAnswers();
      start(connection, later);
      runtime.probes().drain();
      connection.awaitReportThreads();

      assertThat(worker.hasAcceptedSession()).isTrue();
      assertThat(connection.reportCalls())
          .as("the worker sends no report twice")
          .extracting(call -> call.metadata().getProbeAttemptId())
          .containsExactly(unanswered.getProbeAttemptId(), later.getProbeAttemptId());
      assertThat(connection.reportCalls().getLast().wasCancelled()).isFalse();
    }
  }

  @Test
  @DisplayName(
      "Should send the whole report when it keeps advancing for longer than either upload timeout")
  void shouldSendTheWholeReportWhenItKeepsAdvancingForLongerThanEitherUploadTimeout()
      throws Exception {
    var request = requestBuilder().build();
    var expected = writeSourceWhoseReportTakes(request, 15 * MESSAGE_BYTES);
    var configuration =
        configurationBuilder()
            .uploadReadinessTimeout(Duration.ofSeconds(1))
            .uploadAcknowledgementTimeout(Duration.ofSeconds(1))
            .build();

    try (var worker = workerBuilder(tempDir).configuration(configuration).runtime(runtime).build();
        var receiver = Executors.newSingleThreadScheduledExecutor()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportReadiness();
      start(connection, request);
      runtime.probes().drain();
      connection.awaitReportCalls(1);

      // Each of the sixteen messages waits about 100 ms for readiness, so the report takes about
      // 1.5 s in all: a call whose readiness bound or acknowledgement bound covered the whole
      // report, as a segment upload's acknowledgement bound does, fails.
      receiver.scheduleAtFixedRate(connection::grantReportMessage, 0, 100, TimeUnit.MILLISECONDS);

      assertThat(connection.reports()).containsExactly(expected);
      assertThat(connection.reportCalls())
          .singleElement()
          .satisfies(call -> assertThat(call.wasCancelled()).isFalse());
    }
  }

  @Test
  @DisplayName(
      "Should discard the report and keep the session when the server refuses the report call")
  void shouldDiscardTheReportAndKeepTheSessionWhenTheServerRefusesTheReportCall() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), ONE_VIDEO_STREAM);
    var refused = requestBuilder().build();
    var later = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.refuseNextReport();
      start(connection, refused);
      runtime.probes().drain();
      connection.awaitReportThreads();
      start(connection, later);
      runtime.probes().drain();

      assertThat(worker.hasAcceptedSession()).isTrue();
      assertThat(connection.reports())
          .extracting(report -> report.getResult().getProbeAttemptId())
          .containsExactly(later.getProbeAttemptId());
      assertThat(connection.reportCalls())
          .as("the worker sends no report twice")
          .extracting(ReportCall::wasRefused)
          .containsExactly(true, false);
    }
  }

  @Test
  @DisplayName(
      "Should not send the report again when the server's answer names another length than the"
          + " report's")
  void shouldNotSendTheReportAgainWhenTheServersAnswerNamesAnotherLengthThanTheReports()
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), ONE_VIDEO_STREAM);
    var shortened = requestBuilder().build();
    var later = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportAnswers();
      start(connection, shortened);
      runtime.probes().drain();
      var call = connection.awaitReportCalls(1).getFirst();
      call.awaitSentWhole();
      call.answerWithAcceptedLength(call.content().length - 1L);
      connection.awaitReportThreads();

      connection.releaseReportAnswers();
      start(connection, later);
      runtime.probes().drain();
      connection.awaitReportThreads();

      assertThat(worker.hasAcceptedSession()).isTrue();
      assertThat(connection.reportCalls())
          .extracting(sent -> sent.metadata().getProbeAttemptId())
          .containsExactly(shortened.getProbeAttemptId(), later.getProbeAttemptId());
    }
  }

  @ParameterizedTest(name = "the session {0}")
  @ValueSource(strings = {"fails", "completes"})
  @DisplayName("Should cancel an open report call and send nothing more when the session ends")
  void shouldCancelAnOpenReportCallAndSendNothingMoreWhenTheSessionEnds(String ending)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), ONE_VIDEO_STREAM);
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportReadiness();
      start(connection, request);
      runtime.probes().drain();
      var call = connection.awaitReportCalls(1).getFirst();

      if (ending.equals("fails")) {
        connection.fail("connection lost");
      } else {
        connection.complete();
      }

      connection.awaitReportThreads();
      connection.grantReportMessage();

      assertThat(call.wasCancelled()).isTrue();
      assertThat(call.messages()).isEmpty();
    }
  }

  @Test
  @DisplayName("Should cancel an open report call and wait for its thread when the worker closes")
  void shouldCancelAnOpenReportCallAndWaitForItsThreadWhenTheWorkerCloses() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), ONE_VIDEO_STREAM);
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.withholdReportAnswers();
      start(connection, request);
      runtime.probes().drain();
      var call = connection.awaitReportCalls(1).getFirst();
      call.awaitSentWhole();

      worker.close();

      assertThat(call.wasCancelled()).isTrue();
      assertThat(connection.hasJoinedReportThreads()).isTrue();
    }
  }

  @Test
  @DisplayName(
      "Should open no report call when the worker session ends before the report's thread opens"
          + " the call")
  void shouldOpenNoReportCallWhenTheWorkerSessionEndsBeforeTheReportsThreadOpensTheCall()
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), ONE_VIDEO_STREAM);

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.holdReportThreads();
      start(connection, requestBuilder().build());
      runtime.probes().drain();

      connection.fail("connection lost");
      connection.releaseReportThreads();
      connection.awaitReportThreads();

      assertThat(connection.reportCalls()).isEmpty();
    }
  }

  @Test
  @DisplayName(
      "Should send no report of a closed worker session on its replacement when the report's"
          + " thread opens the call after the server accepted the replacement")
  void shouldSendNoReportOfAClosedWorkerSessionOnItsReplacementWhenItsThreadOpensTheCallLater()
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), ONE_VIDEO_STREAM);

    try (var worker = workerBuilder(tempDir).runtime(runtime).build();
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      worker.start("localhost", 1);
      var old = runtime.connection();
      old.holdReportThreads();
      try {
        start(old, requestBuilder().build());
        runtime.probes().drain();
        // The close waits for the report thread without the worker monitor, which the replacement
        // session and the held thread both need.
        var closing = CompletableFuture.runAsync(worker::close, tasks);
        old.awaitJoiningReportThreads(closing);

        worker.start("localhost", 1);
        var replacement = runtime.connection();
        old.releaseReportThreads();
        closing.get();

        assertThat(old.reportCalls()).isEmpty();
        assertThat(replacement.reportCalls()).isEmpty();
        assertThat(old.attemptsEndedWithoutReport()).isEmpty();
        assertThat(replacement.attemptsEndedWithoutReport()).isEmpty();
      } finally {
        old.releaseReportThreads();
      }
    }
  }

  // Each test names the probe attempts of its own reports, so the session's events are checked for
  // every event that used to carry a part of a probe report: a probe's result, a keyframe positions
  // part and a stream measurement part. The contract reserves their fields (ADR 0062), so the
  // numbers name them.
  private static List<EventCase> probeReportEventsOnTheSession(Connection connection) {
    return connection.events().stream()
        .map(EstablishWorkerSessionRequest::getEventCase)
        .filter(event -> PROBE_REPORT_SESSION_EVENT_NUMBERS.contains(event.getNumber()))
        .toList();
  }

  private TranscodeWorkerConfiguration.TranscodeWorkerConfigurationBuilder configurationBuilder() {
    return workerConfigurationBuilder()
        .availableSlots(2)
        .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, tempDir));
  }

  // Writes ffprobe output whose one stream's language tag makes the probe report take exactly this
  // many bytes, and returns that report.
  private ProbeAttemptReport writeSourceWhoseReportTakes(ProbeRequest request, int reportBytes)
      throws IOException {
    var source = tempDir.resolve("movie.mkv");
    var languageLength = reportBytes;
    for (var attempt = 0; attempt < 4; attempt++) {
      Files.writeString(
          source,
          """
          {"streams":[{"index":0,"codec_type":"video","tags":{"language":"%s"}}]}
          """
              .formatted("a".repeat(languageLength)));
      var report = fileContentsProducer().probe(source, request);
      if (report.getSerializedSize() == reportBytes) {
        return report;
      }

      languageLength += reportBytes - report.getSerializedSize();
    }

    throw new AssertionError("No language tag makes a probe report of " + reportBytes + " bytes");
  }

  private static List<Integer> messageLengthsOf(int reportBytes) {
    var lengths = new ArrayList<Integer>();
    for (var sent = 0; sent < reportBytes; sent += MESSAGE_BYTES) {
      lengths.add(Math.min(MESSAGE_BYTES, reportBytes - sent));
    }

    return lengths;
  }
}
