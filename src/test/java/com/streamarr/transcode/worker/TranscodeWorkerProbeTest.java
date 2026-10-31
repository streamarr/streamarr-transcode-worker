package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.cancel;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.failure;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.processBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.sourceBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilderWithoutFfprobe;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import build.buf.gen.streamarr.transcode.v1.CancelProbeCommand;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.Fraction;
import build.buf.gen.streamarr.transcode.v1.FrameRateRuleInputs;
import build.buf.gen.streamarr.transcode.v1.FrameRateRuleResult;
import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import build.buf.gen.streamarr.transcode.v1.KeyframePositionSource;
import build.buf.gen.streamarr.transcode.v1.OutputFrameRateBasis;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeContainerInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.StartProbeCommand;
import build.buf.gen.streamarr.transcode.v1.StartVariantCommand;
import build.buf.gen.streamarr.transcode.v1.VideoDynamicRange;
import build.buf.gen.streamarr.transcode.v1.WorkerIdentity;
import com.google.protobuf.Duration;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.probe.ProbeCommand;
import com.streamarr.transcode.protocol.ProtoUuid;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
@DisplayName("Transcode Worker Probe Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerProbeTest {

  // worker_session.proto: the largest session message the server accepts.
  private static final int SESSION_MESSAGE_LIMIT_BYTES = 131_072;

  // A source whose container has no index, such as MPEG-TS, needs a packet scan for its keyframes.
  private static final ProbeKeyframes PACKET_SCAN_REQUIRED_WITHOUT_INDEX =
      ProbeKeyframes.newBuilder()
          .setPacketScanRequired(
              KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX)
          .build();

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should refuse to build a worker when it has no ffprobe executor")
  void shouldRefuseToBuildAWorkerWhenItHasNoFfprobeExecutor() {
    var builder = workerBuilderWithoutFfprobe(tempDir);

    assertThatThrownBy(builder::build)
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("ffprobe");
  }

  @Test
  @DisplayName(
      "Should preserve stream facts without codec strings when records have no parameter sets")
  void shouldPreserveStreamFactsWithoutCodecStringsWhenRecordsHaveNoParameterSets()
      throws Exception {
    Files.writeString(
        tempDir.resolve("movie.mkv"),
        """
        {"streams":[{"index":0,"codec_type":"video","codec_name":"h264",
                     "width":1920,"height":1080,
                     "extradata":"\\n00000000: 0142 c033 ffe0 00                        .B.3...\\n"},
                    {"index":1,"codec_type":"video","codec_name":"hevc",
                     "width":3840,"height":2160,
                     "extradata":"\\n00000000: 0102 2000 0000 b000 0000 0000 7bf0 00fc  .. .........{...\\n00000010: fdfa fa00 000f 00                        .......\\n"}]}
        """);
    var runtime = new ScriptedWorkerRuntime();
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().results())
          .singleElement()
          .satisfies(
              result -> {
                assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_UNSPECIFIED);
                assertThat(result.hasMedia()).isTrue();
                var streams = result.getMedia().getStreamsList();
                assertThat(streams)
                    .extracting(ProbeStreamInfo::getCodec)
                    .containsExactly("h264", "hevc");
                assertThat(streams)
                    .extracting(ProbeStreamInfo::getWidth)
                    .containsExactly(1920, 3840);
                assertThat(streams)
                    .extracting(ProbeStreamInfo::getHeight)
                    .containsExactly(1080, 2160);
                assertThat(streams)
                    .extracting(ProbeStreamInfo::hasCodecString)
                    .containsExactly(false, false);
              });
    }
  }

  @Test
  @DisplayName(
      "Should omit stream end times when ASF reports the container duration for each stream")
  void shouldOmitStreamEndTimesWhenAsfReportsTheContainerDurationForEachStream() throws Exception {
    // The WMV audio's last packet ends at 1.021 s, but ASF gives it the global play time.
    Files.writeString(
        tempDir.resolve("movie.wmv"),
        """
        {"format":{"format_name":"asf","start_time":"0.000000","duration":"2.092000"},
         "streams":[{"index":0,"codec_type":"video","codec_name":"wmv2",
                     "time_base":"1/1000","start_pts":46,"duration_ts":2046},
                    {"index":1,"codec_type":"audio","codec_name":"wmav2",
                     "time_base":"1/1000","start_pts":0,"duration_ts":2046}]}
        """);
    var runtime = new ScriptedWorkerRuntime();
    var request = requestBuilder().setSource(sourceBuilder().setRelativeKey("movie.wmv")).build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().results())
          .singleElement()
          .satisfies(
              result -> {
                assertThat(result.hasMedia()).isTrue();
                assertThat(result.getMedia().getContainer().getDuration())
                    .isEqualTo(Duration.newBuilder().setSeconds(2).setNanos(92_000_000).build());
                assertThat(result.getMedia().getStreamsList())
                    .extracting(ProbeStreamInfo::hasEndTime)
                    .containsExactly(false, false);
              });
    }
  }

  @Test
  @DisplayName(
      "Should send the keyframe positions before the probe result when the worker reads a container index")
  void shouldSendKeyframePositionsBeforeProbeResultWhenWorkerReadsContainerIndex()
      throws Exception {
    var fixture = getClass().getResource("/keyframes/mp4-bframes-editlist.mp4");
    assertThat(fixture).isNotNull();
    Files.copy(Path.of(fixture.toURI()), tempDir.resolve("movie.mp4"));
    var runtime = new ScriptedWorkerRuntime();
    var request =
        requestBuilder()
            .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
            .setSource(sourceBuilder().setRelativeKey("movie.mp4"))
            .build();
    var producer =
        new FfprobeExecutor(
            new ObjectMapper(),
            command ->
                processBuilder()
                    .stdout(
                        command.arguments().contains("-read_intervals")
                            ? """
                              {"packets":[{"pts":35840,"dts":34816,"flags":"K__"}]}
                              """
                            : """
                              {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2",
                                         "start_time":"0.000000"},
                               "streams":[{"index":0,"codec_type":"video","id":"0x1"}]}
                              """)
                    .build());

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().events())
          .filteredOn(event -> !event.hasRegistration())
          .satisfiesExactly(
              part ->
                  assertThat(part.getProbeKeyframePositionsPart().getPresentationTimeDeltasList())
                      .containsExactly(0L, 8_704L, 16_896L, 10_240L),
              result ->
                  assertThat(
                          result
                              .getProbeResult()
                              .getMedia()
                              .getKeyframes()
                              .getPositions()
                              .getKeyframeCount())
                      .isEqualTo(4));
    }
  }

  @Test
  @DisplayName(
      "Should send the scanned keyframe positions before the probe result when the server requests"
          + " a packet scan")
  void shouldSendScannedKeyframePositionsBeforeProbeResultWhenServerRequestsPacketScan()
      throws Exception {
    Files.writeString(tempDir.resolve("movie.ts"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var request =
        requestBuilder()
            .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
            .setScanKeyframePackets(true)
            .setSource(sourceBuilder().setRelativeKey("movie.ts"))
            .build();
    var producer =
        new FfprobeExecutor(
            new ObjectMapper(),
            command ->
                processBuilder()
                    .stdout(
                        command.tool() == ProbeCommand.ProbeTool.FFMPEG
                            ? """
                              0, 0, 0, 3750, 100, 0x00000000
                              0, 3750, 3750, 3750, 100, 0x00000000, F=0x0
                              0, 180000, 180000, 3750, 100, 0x00000000
                              """
                            : """
                              {"format":{"format_name":"mpegts","start_time":"0.000000"},
                               "streams":[{"index":0,"codec_type":"video",
                                           "time_base":"1/90000","has_b_frames":0}]}
                              """)
                    .build());

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().events())
          .filteredOn(event -> !event.hasRegistration())
          .satisfiesExactly(
              part ->
                  assertThat(part.getProbeKeyframePositionsPart().getPresentationTimeDeltasList())
                      .containsExactly(0L, 180_000L),
              result ->
                  assertThat(result.getProbeResult().getMedia().getKeyframes().getPositions())
                      .satisfies(
                          positions -> {
                            assertThat(positions.getKeyframeCount()).isEqualTo(2);
                            assertThat(positions.getSource())
                                .isEqualTo(
                                    KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN);
                          }));
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {-1, 0})
  @DisplayName(
      "Should send the probe result when its session message fits within the server's limit")
  void shouldSendTheProbeResultWhenItsSessionMessageFitsWithinTheServersLimit(int bytesFromLimit)
      throws Exception {
    var request = requestBuilder().build();
    var language =
        languageFillingSessionMessage(request, SESSION_MESSAGE_LIMIT_BYTES + bytesFromLimit);

    assertThat(probeResults(request, ffprobeOutput(1, language)))
        .containsExactly(resultWithOneVideoStream(request, language).build());
  }

  @Test
  @DisplayName(
      "Should report the result as too large when its session message exceeds the server's limit"
          + " by one byte")
  void shouldReportTheResultAsTooLargeWhenItsSessionMessageExceedsTheServersLimitByOneByte()
      throws Exception {
    var request = requestBuilder().build();
    var language = languageFillingSessionMessage(request, SESSION_MESSAGE_LIMIT_BYTES + 1);

    assertThat(probeResults(request, ffprobeOutput(1, language)))
        .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE));
  }

  @Test
  @DisplayName(
      "Should report the result as too large when the file has more streams than one session"
          + " message can carry")
  void shouldReportTheResultAsTooLargeWhenTheFileHasMoreStreamsThanOneSessionMessageCanCarry()
      throws Exception {
    var request = requestBuilder().build();

    assertThat(probeResults(request, ffprobeOutput(20_000, "")))
        .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE));
  }

  @Test
  @DisplayName(
      "Should send no keyframe positions part when the probe result is too large for one session"
          + " message")
  void shouldSendNoKeyframePositionsPartWhenTheProbeResultIsTooLargeForOneSessionMessage()
      throws Exception {
    var fixture = getClass().getResource("/keyframes/mp4-bframes-editlist.mp4");
    assertThat(fixture).isNotNull();
    Files.copy(Path.of(fixture.toURI()), tempDir.resolve("movie.mp4"));
    var runtime = new ScriptedWorkerRuntime();
    var request =
        requestBuilder()
            .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
            .setSource(sourceBuilder().setRelativeKey("movie.mp4"))
            .build();
    var language = "a".repeat(SESSION_MESSAGE_LIMIT_BYTES);
    var producer =
        new FfprobeExecutor(
            new ObjectMapper(),
            command ->
                processBuilder()
                    .stdout(
                        command.arguments().contains("-read_intervals")
                            ? """
                              {"packets":[{"pts":35840,"dts":34816,"flags":"K__"}]}
                              """
                            : """
                              {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2",
                                         "start_time":"0.000000"},
                               "streams":[{"index":0,"codec_type":"video","id":"0x1",
                                           "tags":{"language":"%s"}}]}
                              """
                                .formatted(language))
                    .build());

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().events())
          .filteredOn(event -> !event.hasRegistration())
          .extracting(EstablishWorkerSessionRequest::getProbeResult)
          .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE));
    }
  }

  private List<ProbeAttemptResult> probeResults(ProbeRequest request, String ffprobeOutput)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), ffprobeOutput);
    var runtime = new ScriptedWorkerRuntime();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      return runtime.connection().results();
    }
  }

  // ffprobe output with the given number of video streams. Only the first stream has a language
  // tag, which neither the contract nor the worker bounds.
  private static String ffprobeOutput(int streams, String firstLanguage) {
    var output = new StringBuilder("{\"streams\":[");
    for (var index = 0; index < streams; index++) {
      output
          .append(index == 0 ? "" : ",")
          .append("{\"index\":")
          .append(index)
          .append(",\"codec_type\":\"video\"");
      if (index == 0) {
        output.append(",\"tags\":{\"language\":\"").append(firstLanguage).append("\"}");
      }

      output.append('}');
    }

    return output.append("]}").toString();
  }

  private static ProbeAttemptResult.Builder resultWithOneVideoStream(
      ProbeRequest request, String language) {
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(request.getProbeAttemptId())
        .setProbeVersion(request.getProbeVersion())
        .setMedia(
            ProbeMediaInfo.newBuilder()
                .setContainer(ProbeContainerInfo.getDefaultInstance())
                .addStreams(
                    ProbeStreamInfo.newBuilder()
                        .setIndex(0)
                        .setCodecType("video")
                        .setDynamicRange(VideoDynamicRange.getDefaultInstance())
                        .setLanguage(language))
                .setChosenVideoStreamIndex(0)
                // The stream declares no frame rate and no time base.
                .setFrameRateRule(
                    FrameRateRuleResult.newBuilder()
                        .setBasis(OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_REFUSED)
                        .setInputs(FrameRateRuleInputs.getDefaultInstance()))
                .setKeyframes(PACKET_SCAN_REQUIRED_WITHOUT_INDEX));
  }

  // The language tag that makes the session message carrying the result take exactly the given
  // number of bytes.
  private static String languageFillingSessionMessage(ProbeRequest request, int bytes) {
    var length = 0;
    for (var attempt = 0; attempt < 3; attempt++) {
      var language = "a".repeat(length);
      var size =
          EstablishWorkerSessionRequest.newBuilder()
              .setProbeResult(resultWithOneVideoStream(request, language))
              .build()
              .getSerializedSize();
      if (size == bytes) {
        return language;
      }

      length += bytes - size;
    }

    throw new AssertionError("No language tag fills a session message of " + bytes + " bytes");
  }

  @Test
  @DisplayName("Should advertise only the probe version it answers when it registers")
  void shouldAdvertiseOnlyTheProbeVersionItAnswersWhenItRegisters() throws Exception {
    var runtime = new ScriptedWorkerRuntime();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);

      assertThat(runtime.connection().registration().getCapabilities().getProbeVersionsList())
          .containsExactly(6);
    }
  }

  @Test
  @DisplayName("Should reject a probe source when a symlink escapes its namespace")
  void shouldRejectAProbeSourceWhenASymlinkEscapesItsNamespace() throws Exception {
    var root = Files.createDirectory(tempDir.resolve("media"));
    var outside =
        Files.writeString(
            tempDir.resolve("outside.mkv"),
            """
        {"streams":[{"index":0,"codec_type":"video"}]}
        """);
    Files.createSymbolicLink(root.resolve("escape.mkv"), outside);
    var request = requestBuilder().setSource(sourceBuilder().setRelativeKey("escape.mkv")).build();
    var runtime = new ScriptedWorkerRuntime();

    try (var worker = workerBuilder(root).runtime(runtime).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().results())
          .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE));
    }
  }

  @Test
  @DisplayName("Should return a source failure when the mapped media file is missing")
  void shouldReturnASourceFailureWhenTheMappedMediaFileIsMissing() throws Exception {
    var runtime = new ScriptedWorkerRuntime();
    var request = requestBuilder().setSource(sourceBuilder().setRelativeKey("missing.mkv")).build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> processBuilder().build());

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().results())
          .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE));
    }
  }

  @Test
  @DisplayName(
      "Should fail the probe as invalid media when ffprobe reports invalid data for the version the"
          + " worker answers")
  void shouldFailTheProbeAsInvalidMediaWhenFfprobeReportsInvalidDataForTheVersionTheWorkerAnswers()
      throws Exception {
    var request = requestBuilder().setProbeVersion(FfprobeExecutor.PROBE_VERSION).build();

    assertThat(resultsWhenFfprobeReportsInvalidData(request))
        .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_INVALID_MEDIA));
  }

  @ParameterizedTest(name = "probe version {0}")
  @ValueSource(ints = {1, 5, 7})
  @DisplayName(
      "Should fail the probe as an unsupported version when the request names a version the worker"
          + " does not answer")
  void shouldFailTheProbeAsAnUnsupportedVersionWhenTheRequestNamesAVersionTheWorkerDoesNotAnswer(
      int version) throws Exception {
    var request = requestBuilder().setProbeVersion(version).build();

    assertThat(resultsWhenFfprobeReportsInvalidData(request))
        .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_UNSUPPORTED_VERSION));
  }

  // The worker's results for the probe when ffprobe would fail with AVERROR_INVALIDDATA.
  private List<ProbeAttemptResult> resultsWhenFfprobeReportsInvalidData(ProbeRequest request)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var producer =
        new FfprobeExecutor(
            new ObjectMapper(),
            _ ->
                processBuilder()
                    .stdout(
                        """
            {"error":{"code":-1094995529}}
            """)
                    .exitCode(1)
                    .build());

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      return runtime.connection().results();
    }
  }

  @Test
  @DisplayName("Should return cancellation when a queued probe is cancelled before execution")
  void shouldReturnCancellationWhenAQueuedProbeIsCancelledBeforeExecution() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var request = requestBuilder().build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> processBuilder().build());

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      start(connection, request);

      cancel(connection, request);
      runtime.probes().drain();

      assertThat(connection.results())
          .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_CANCELLED));
    }
  }

  @Test
  @DisplayName("Should withhold the cancellation result when the probe process has not exited")
  void shouldWithholdCancellationResultWhenProbeProcessHasNotExited() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var process = processBuilder().running(true).deferredTermination(true).build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> process);
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, request);
        var running = runtime.probes().runNext();
        process.awaitStarted(running);

        cancel(connection, request);
        process.awaitTerminationRequested(running);
        process.awaitExitWait(running);

        assertThat(process.isAlive()).isTrue();
        assertThat(connection.results())
            .as("cancellation must not release server capacity before process exit")
            .isEmpty();
        assertThat(running.isDone()).isFalse();
        process.finish();
        running.get();
        assertThat(connection.results())
            .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_CANCELLED));
      } finally {
        process.finish();
      }
    }
  }

  @ParameterizedTest
  @EnumSource(IdentityMismatch.class)
  @DisplayName("Should ignore a probe start when it targets another worker or boot")
  void shouldIgnoreAProbeStartWhenItTargetsAnotherWorkerOrBoot(IdentityMismatch mismatch)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var ignored = requestBuilder().build();
    var accepted = requestBuilder().build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> processBuilder().build());

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      connection.deliver(
          EstablishWorkerSessionResponse.newBuilder()
              .setStartProbe(
                  StartProbeCommand.newBuilder()
                      .setTarget(mismatch.change(connection.registration().getWorker()))
                      .setRequest(ignored))
              .build());
      start(connection, accepted);

      runtime.probes().drain();

      assertThat(connection.results())
          .singleElement()
          .satisfies(
              result -> {
                assertThat(result.getProbeAttemptId()).isEqualTo(accepted.getProbeAttemptId());
                assertThat(result.getProbeVersion()).isEqualTo(accepted.getProbeVersion());
                assertThat(result.hasMedia()).isTrue();
              });
    }
  }

  private enum IdentityMismatch {
    WORKER,
    BOOT;

    private WorkerIdentity change(WorkerIdentity identity) {
      var otherId = ProtoUuid.toProto(UUID.randomUUID());
      return switch (this) {
        case WORKER -> identity.toBuilder().setWorkerId(otherId).build();
        case BOOT -> identity.toBuilder().setBootId(otherId).build();
      };
    }
  }

  @ParameterizedTest
  @EnumSource(SessionEnding.class)
  @DisplayName("Should terminate an active probe when the control connection ends")
  void shouldTerminateAnActiveProbeWhenTheControlConnectionEnds(SessionEnding ending)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var process = processBuilder().running(true).build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> process);

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, requestBuilder().build());
        var running = runtime.probes().runNext();
        process.awaitStarted(running);

        ending.end(connection);

        process.awaitTerminationRequested(running);
        running.get();
        assertThat(process.isAlive()).isFalse();
      } finally {
        process.finish();
      }
    }
  }

  private enum SessionEnding {
    COMPLETED,
    FAILED;

    private void end(ScriptedWorkerRuntime.Connection connection) {
      Consumer<ScriptedWorkerRuntime.Connection> action =
          switch (this) {
            case COMPLETED -> ScriptedWorkerRuntime.Connection::complete;
            case FAILED -> value -> value.fail("old connection failed");
          };
      action.accept(connection);
    }
  }

  @ParameterizedTest
  @EnumSource(SessionEnding.class)
  @DisplayName("Should preserve replacement work and disconnection when an old callback arrives")
  void shouldPreserveReplacementWorkAndDisconnectionWhenAnOldCallbackArrives(SessionEnding ending)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var launcher = ScriptedProcessLauncher.running();
    var process = processBuilder().running(true).build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> process);
    var job = variantJobBuilder().build();
    var request = requestBuilder().build();

    try (var worker =
        workerBuilder(tempDir)
            .engine(engine(launcher))
            .runtime(runtime)
            .ffprobe(producer)
            .build()) {
      try {
        worker.start("localhost", 1);
        var old = runtime.connection();
        worker.close();
        worker.start("localhost", 1);
        var replacement = runtime.connection();
        replacement.deliver(
            EstablishWorkerSessionResponse.newBuilder()
                .setStartVariant(
                    StartVariantCommand.newBuilder()
                        .setTarget(replacement.registration().getWorker())
                        .setJob(job))
                .build());
        start(replacement, request);
        var running = runtime.probes().runNext();
        process.awaitStarted(running);

        ending.end(old);

        assertThat(launcher.process(fromProto(job.getJobAttemptId())).isAlive()).isTrue();
        process.finish();
        running.get();
        assertThat(replacement.results())
            .singleElement()
            .satisfies(
                result -> {
                  assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
                  assertThat(result.hasMedia()).isTrue();
                });

        replacement.fail("replacement connection ended");
        assertThatThrownBy(worker::awaitDisconnection)
            .isInstanceOf(WorkerJobException.class)
            .hasRootCauseMessage("UNAVAILABLE: replacement connection ended");
      } finally {
        process.finish();
      }
    }
  }

  @Test
  @DisplayName("Should await probe termination outside the worker monitor when closing")
  void shouldAwaitProbeTerminationOutsideTheWorkerMonitorWhenClosing() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var process = processBuilder().running(true).deferredTermination(true).build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> process);

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build();
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        worker.start("localhost", 1);
        start(runtime.connection(), requestBuilder().build());
        var probes = runtime.probes();
        var running = probes.runNext();
        process.awaitStarted(running);

        var closing = CompletableFuture.runAsync(worker::close, tasks);
        try {
          probes.awaitClosing(closing);
          process.awaitTerminationRequested(running);
          assertThat(closing.isDone()).as("close is waiting for its live probe task").isFalse();
        } finally {
          process.finish();
        }

        closing.get();
        running.get();
        assertThat(process.isAlive()).isFalse();
      } finally {
        process.finish();
      }
    }
  }

  @Test
  @DisplayName("Should discard an old probe result when a replacement connection is active")
  void shouldDiscardAnOldProbeResultWhenAReplacementConnectionIsActive() throws Exception {
    var oldSource = Files.writeString(tempDir.resolve("old.mkv"), "old media").toRealPath();
    Files.writeString(tempDir.resolve("movie.mkv"), "new media");
    var runtime = new ScriptedWorkerRuntime();
    var process = processBuilder().running(true).deferredTermination(true).build();
    var producer =
        new FfprobeExecutor(
            new ObjectMapper(),
            command ->
                Path.of(command.arguments().getLast()).equals(oldSource)
                    ? process
                    : processBuilder().build());
    var oldRequest = requestBuilder().setSource(sourceBuilder().setRelativeKey("old.mkv")).build();
    var nextRequest = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build();
        var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
      try {
        worker.start("localhost", 1);
        var old = runtime.connection();
        var oldProbes = runtime.probes();
        start(old, oldRequest);
        var running = oldProbes.runNext();
        process.awaitStarted(running);
        var closing = CompletableFuture.runAsync(worker::close, tasks);
        oldProbes.awaitClosing(closing);

        worker.start("localhost", 1);
        var replacement = runtime.connection();
        process.finish();
        closing.get();
        start(replacement, nextRequest);
        runtime.probes().drain();

        assertThat(old.results()).isEmpty();
        assertThat(replacement.results())
            .singleElement()
            .satisfies(
                result -> {
                  assertThat(result.getProbeAttemptId()).isEqualTo(nextRequest.getProbeAttemptId());
                  assertThat(result.hasMedia()).isTrue();
                });
      } finally {
        process.finish();
      }
    }
  }

  @Test
  @DisplayName("Should ignore a queued old command when its worker connection has closed")
  void shouldIgnoreAQueuedOldCommandWhenItsWorkerConnectionHasClosed() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> processBuilder().build());
    var ignored = requestBuilder().build();
    var accepted = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      var old = runtime.connection();
      worker.close();
      worker.start("localhost", 1);
      var replacement = runtime.connection();

      start(old, ignored);
      start(replacement, accepted);
      runtime.probes().drain();

      assertThat(old.results()).isEmpty();
      assertThat(replacement.results())
          .singleElement()
          .satisfies(
              result -> {
                assertThat(result.getProbeAttemptId()).isEqualTo(accepted.getProbeAttemptId());
                assertThat(result.hasMedia()).isTrue();
              });
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should remain usable when cancelling an unknown or completed probe attempt")
  void shouldRemainUsableWhenCancellingAnUnknownOrCompletedProbeAttempt(boolean completed)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> processBuilder().build());
    var absent = requestBuilder().build();
    var accepted = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      if (completed) {
        start(connection, absent);
        runtime.probes().drain();
      }

      cancel(connection, absent);
      start(connection, accepted);
      runtime.probes().drain();

      assertThat(connection.results())
          .filteredOn(result -> result.getProbeAttemptId().equals(accepted.getProbeAttemptId()))
          .singleElement()
          .satisfies(result -> assertThat(result.hasMedia()).isTrue());
    }
  }

  @ParameterizedTest
  @EnumSource(IdentityMismatch.class)
  @DisplayName("Should preserve a probe when cancellation targets another worker or boot")
  void shouldPreserveAProbeWhenCancellationTargetsAnotherWorkerOrBoot(IdentityMismatch mismatch)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var process = processBuilder().running(true).build();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> process);
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, request);
        var running = runtime.probes().runNext();
        process.awaitStarted(running);

        connection.deliver(
            EstablishWorkerSessionResponse.newBuilder()
                .setCancelProbe(
                    CancelProbeCommand.newBuilder()
                        .setTarget(mismatch.change(connection.registration().getWorker()))
                        .setProbeAttemptId(request.getProbeAttemptId()))
                .build());
        process.finish();
        running.get();

        assertThat(connection.results())
            .singleElement()
            .satisfies(
                result -> {
                  assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
                  assertThat(result.hasMedia()).isTrue();
                });
      } finally {
        process.finish();
      }
    }
  }

  @Test
  @DisplayName("Should preserve the complete typed result when probing a literal source key")
  void shouldPreserveTheCompleteTypedResultWhenProbingALiteralSourceKey() throws Exception {
    Files.writeString(
        tempDir.resolve("Film %20.mkv"),
        """
        {"format":{"format_name":"matroska","duration":"1.25","bit_rate":"3500000"},
         "streams":[
          {"index":4,"codec_type":"video","codec_name":"h264","width":1920,"height":1080,
           "r_frame_rate":"24000/1001","bit_rate":"3000000"},
          {"index":7,"codec_type":"audio","codec_name":"aac","profile":"LC","channels":6,
           "bit_rate":"384000",
           "tags":{"language":"eng"},"disposition":{"default":1}},
          {"index":9,"codec_type":"subtitle","codec_name":"subrip",
           "tags":{"language":"fra"},"disposition":{"forced":1}}]}
        """);
    var runtime = new ScriptedWorkerRuntime();
    var request =
        requestBuilder().setSource(sourceBuilder().setRelativeKey("Film %20.mkv")).build();
    var expected =
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(request.getProbeVersion())
            .setMedia(
                ProbeMediaInfo.newBuilder()
                    .setContainer(
                        ProbeContainerInfo.newBuilder()
                            .setFormat("matroska")
                            .setDuration(Duration.newBuilder().setSeconds(1).setNanos(250_000_000))
                            .setBitrateBitsPerSecond(3_500_000))
                    .addStreams(
                        ProbeStreamInfo.newBuilder()
                            .setIndex(4)
                            .setCodecType("video")
                            .setDynamicRange(VideoDynamicRange.getDefaultInstance())
                            .setCodec("h264")
                            .setWidth(1920)
                            .setHeight(1080)
                            .setFramerate(24000.0 / 1001)
                            .setBitrateBitsPerSecond(3_000_000))
                    .addStreams(
                        ProbeStreamInfo.newBuilder()
                            .setIndex(7)
                            .setCodecType("audio")
                            .setCodec("aac")
                            .setAudioProfile("LC")
                            .setCodecString("mp4a.40.2")
                            .setChannels(6)
                            .setBitrateBitsPerSecond(384_000)
                            .setLanguage("eng")
                            .setIsDefault(true))
                    .addStreams(
                        ProbeStreamInfo.newBuilder()
                            .setIndex(9)
                            .setCodecType("subtitle")
                            .setCodec("subrip")
                            .setLanguage("fra")
                            .setIsForced(true))
                    .setChosenVideoStreamIndex(4)
                    // Without a time base, the rule cannot scan, so it snaps the base rate.
                    .setFrameRateRule(
                        FrameRateRuleResult.newBuilder()
                            .setOutputFrameRate(
                                Fraction.newBuilder().setNumerator(24_000).setDenominator(1001))
                            .setBasis(
                                OutputFrameRateBasis.OUTPUT_FRAME_RATE_BASIS_DECLARED_RATE_FALLBACK)
                            .setInputs(
                                FrameRateRuleInputs.newBuilder()
                                    .setBaseRate(
                                        Fraction.newBuilder()
                                            .setNumerator(24_000)
                                            .setDenominator(1001))))
                    .setKeyframes(PACKET_SCAN_REQUIRED_WITHOUT_INDEX))
            .build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().results()).containsExactly(expected);
    }
  }
}
