package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Process Tests")
class FfprobeExecutorProcessTest {

  @TempDir Path temporaryDirectory;

  @Test
  @DisplayName("Should return an execution failure when the configured binary is unavailable")
  void shouldReturnAnExecutionFailureWhenTheConfiguredBinaryIsUnavailable() {
    var executor =
        FfprobeExecutor.forBinaries(
            temporaryDirectory.resolve("missing-ffprobe"), temporaryDirectory.resolve("ffmpeg"));

    assertThat(
            executor
                .probe(temporaryDirectory.resolve("movie.mkv"), request())
                .result()
                .getFailure())
        .isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
  }

  @Test
  @Timeout(5)
  @DisplayName("Should finish probing when stdout and stderr exceed pipe capacity")
  void shouldFinishProbingWhenStdoutAndStderrExceedPipeCapacity() throws Exception {
    var binary =
        script(
            """
        dd if=/dev/zero bs=65536 count=4 >&2
        printf '%262144s' ''
        printf '%s' '{"streams":[{"codec_type":"video"}]}'
        """);

    var result =
        executorFor(binary).probe(temporaryDirectory.resolve("movie.mkv"), request()).result();

    assertThat(result.hasMedia()).isTrue();
    assertThat(result.getMedia().getStreamsList()).hasSize(1);
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName(
      "Should terminate ffprobe before returning cancellation when output or exit is blocked")
  void shouldTerminateFfprobeBeforeReturningCancellationWhenOutputOrExitIsBlocked(
      boolean closeOutput) throws Exception {
    var output =
        closeOutput ? "printf '%s' '{\"streams\":[{\"codec_type\":\"video\"}]}'; exec 1>&-\n" : "";
    var binary =
        script(
            output
                + """
        printf '%s' "$$" > "$0.pid"
        exec sleep 60
        """);
    var request = request();

    var completed =
        probeUntilInterrupted(
            executorFor(binary),
            Path.of(binary + ".pid"),
            temporaryDirectory.resolve("movie.mkv"),
            request);

    assertThat(completed.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_CANCELLED);
    assertThat(completed.result().getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
    assertThat(completed.result().getProbeVersion()).isEqualTo(request.getProbeVersion());
    assertThat(completed.processAlive()).isFalse();
    assertThat(completed.interrupted()).isTrue();
  }

  @Test
  @Timeout(10)
  @DisplayName(
      "Should report CANCELLED and stop FFmpeg when a packet scan is cancelled while the source"
          + " blocks reads")
  void shouldReportCancelledAndStopFfmpegWhenPacketScanIsCancelledWhileSourceBlocksReads()
      throws Exception {
    var source = temporaryDirectory.resolve("movie.ts");
    var fifo = new ProcessBuilder("mkfifo", source.toString()).start();
    assertThat(fifo.waitFor()).isZero();
    var ffprobe =
        script(
            "ffprobe",
            """
        printf '%s' '{"format":{"format_name":"mpegts","start_time":"0.000000"},
          "streams":[{"index":0,"codec_type":"video","time_base":"1/90000","has_b_frames":2}]}'
        """);
    // The packet scan reads the source, a FIFO that no process writes, so the read never returns.
    var ffmpeg =
        script(
            "ffmpeg",
            """
        while [ "$1" != "-i" ]; do shift; done
        printf '%s\n' '0, 0, 0, 3750, 100, 0x00000000'
        printf '%s' "$$" > "$0.pid"
        exec cat "$2"
        """);
    var request =
        request().toBuilder()
            .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
            .setScanKeyframePackets(true)
            .build();

    var completed =
        probeUntilInterrupted(
            FfprobeExecutor.forBinaries(ffprobe, ffmpeg),
            Path.of(ffmpeg + ".pid"),
            source,
            request);

    assertThat(completed.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_CANCELLED);
    assertThat(completed.processAlive()).isFalse();
    assertThat(completed.interrupted()).isTrue();
  }

  /**
   * Probes on its own thread and interrupts that thread once a scripted binary writes its process
   * id to the given file.
   */
  private ProbeCompletion probeUntilInterrupted(
      FfprobeExecutor executor, Path pidFile, Path source, ProbeRequest request) throws Exception {
    var process = new AtomicReference<ProcessHandle>();
    var completion = new CompletableFuture<ProbeCompletion>();
    var task =
        Thread.ofVirtual()
            .start(
                () -> {
                  var result = executor.probe(source, request).result();
                  completion.complete(
                      new ProbeCompletion(
                          result,
                          Optional.ofNullable(process.get())
                              .map(ProcessHandle::isAlive)
                              .orElse(true),
                          Thread.currentThread().isInterrupted()));
                });

    try {
      await()
          .atMost(Duration.ofSeconds(5))
          .until(() -> Files.exists(pidFile) && !Files.readString(pidFile).isBlank());
      process.set(ProcessHandle.of(Long.parseLong(Files.readString(pidFile))).orElseThrow());
      task.interrupt();

      return completion.get(3, TimeUnit.SECONDS);
    } finally {
      Optional.ofNullable(process.get()).ifPresent(ProcessHandle::destroyForcibly);
      task.interrupt();
      task.join(Duration.ofSeconds(3));
    }
  }

  private record ProbeCompletion(
      ProbeAttemptResult result, boolean processAlive, boolean interrupted) {}

  @Test
  @DisplayName(
      "Should pass the probe's read limits and the chapter listing when executing the configured"
          + " binary")
  void shouldPassTheProbesReadLimitsAndTheChapterListingWhenExecutingTheConfiguredBinary()
      throws Exception {
    var binary =
        script(
            """
        printf '%s\\n' "$@" > "$0.args"
        printf '%s' '{"streams":[{"codec_type":"video","codec_name":"h264"}]}'
        """);
    var source = temporaryDirectory.resolve("movie with spaces.mkv");

    var result = executorFor(binary).probe(source, request()).result();

    assertThat(result.hasMedia()).isTrue();
    assertThat(result.getMedia().getStreams(0).getCodec()).isEqualTo("h264");
    assertThat(Files.readAllLines(Path.of(binary + ".args")))
        .containsExactly(
            "-v",
            "quiet",
            "-print_format",
            "json",
            "-analyzeduration",
            "200M",
            "-probesize",
            "1G",
            "-scan_all_pmts",
            "0",
            "-show_streams",
            "-show_format",
            "-show_chapters",
            "-show_error",
            source.toString());
  }

  private FfprobeExecutor executorFor(Path ffprobe) {
    return FfprobeExecutor.forBinaries(ffprobe, temporaryDirectory.resolve("ffmpeg"));
  }

  private ProbeRequest request() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
        .build();
  }

  private Path script(String body) throws Exception {
    return script("ffprobe", body);
  }

  private Path script(String name, String body) throws Exception {
    var binary = temporaryDirectory.resolve(name);
    Files.writeString(binary, "#!/bin/sh\n" + body);
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
    return binary;
  }
}
