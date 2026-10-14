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
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeContainerInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.StartProbeCommand;
import build.buf.gen.streamarr.transcode.v1.StartVariantCommand;
import build.buf.gen.streamarr.transcode.v1.WorkerIdentity;
import com.google.protobuf.Duration;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.protocol.ProtoUuid;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Transcode Worker Probe Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerProbeTest {

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
      "Should send the keyframe positions before the probe result when the worker reads a container index")
  void shouldSendKeyframePositionsBeforeProbeResultWhenWorkerReadsContainerIndex()
      throws Exception {
    var fixture = getClass().getResource("/keyframes/mp4-bframes-editlist.mp4");
    assertThat(fixture).isNotNull();
    Files.copy(Path.of(fixture.toURI()), tempDir.resolve("movie.mkv"));
    var runtime = new ScriptedWorkerRuntime();
    var request =
        requestBuilder().setProbeVersion(FfprobeExecutor.KEYFRAME_POSITIONS_PROBE_VERSION).build();
    var producer =
        new FfprobeExecutor(
            new ObjectMapper(),
            arguments ->
                processBuilder()
                    .stdout(
                        arguments.contains("-read_intervals")
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
  @DisplayName("Should advertise the supported probe version when the worker registers")
  void shouldAdvertiseTheSupportedProbeVersionWhenTheWorkerRegisters() throws Exception {
    var runtime = new ScriptedWorkerRuntime();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);

      assertThat(runtime.connection().registration().getCapabilities().getProbeVersionsList())
          .containsExactly(FfprobeExecutor.PROBE_VERSION);
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

  @ParameterizedTest
  @CsvSource({"1, PROBE_FAILURE_INVALID_MEDIA", "2, PROBE_FAILURE_UNSUPPORTED_VERSION"})
  @DisplayName("Should retain typed probe failures when the worker rejects media or a version")
  void shouldRetainTypedProbeFailuresWhenTheWorkerRejectsMediaOrAVersion(
      int version, ProbeFailure expectedFailure) throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var request = requestBuilder().setProbeVersion(version).build();
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

      assertThat(runtime.connection().results()).containsExactly(failure(request, expectedFailure));
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
            arguments ->
                Path.of(arguments.getLast()).equals(oldSource)
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
          {"index":7,"codec_type":"audio","codec_name":"aac","channels":6,"bit_rate":"384000",
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
            .setProbeVersion(1)
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
                            .setIsForced(true)))
            .build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().results()).containsExactly(expected);
    }
  }
}
