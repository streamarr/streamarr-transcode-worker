package com.streamarr.transcode.worker;

import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.cancel;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.failure;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.probe.ProbeAttemptReport;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Worker Probe Frame Rate Cancellation Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class WorkerProbeFrameRateCancellationTest {

  private static final String SCAN_REQUIRED =
      """
      {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000",
                   "r_frame_rate":"30/1","avg_frame_rate":"24/1"}]}
      """;

  @TempDir Path root;

  @Test
  @DisplayName(
      "Should withhold cancellation until the process exits when a frame-rate scan is reading")
  void shouldWithholdCancellationUntilTheProcessExitsWhenAFrameRateScanIsReading()
      throws Exception {
    Files.writeString(root.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var scan = new ParkedPacketScanProcess();
    var request = requestBuilder().build();

    try (var worker = workerBuilder(root).runtime(runtime).ffprobe(executor(scan)).build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, request);
        var running = runtime.probes().runNext();
        awaitSignal(scan.reading, running);

        cancel(connection, request);
        awaitSignal(scan.terminationRequested, running);
        awaitSignal(scan.awaitingExit, running);

        assertThat(worker.hasAcceptedSession()).isTrue();
        assertThat(scan.isAlive()).isTrue();
        assertThat(connection.results())
            .as("server capacity remains claimed until the scan process exits")
            .isEmpty();
        assertThat(running).isNotDone();
        scan.finish();
        running.get();

        assertThat(scan.isAlive()).isFalse();
        assertThat(connection.results())
            .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_CANCELLED));
      } finally {
        scan.finish();
      }
    }
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(SessionEnd.class)
  @DisplayName("Should stop the frame-rate scan without reporting when the control session ends")
  void shouldStopTheFrameRateScanWithoutReportingWhenTheControlSessionEnds(SessionEnd ending)
      throws Exception {
    Files.writeString(root.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var scan = new ParkedPacketScanProcess();

    try (var worker = workerBuilder(root).runtime(runtime).ffprobe(executor(scan)).build()) {
      try {
        worker.start("localhost", 1);
        var connection = runtime.connection();
        start(connection, requestBuilder().build());
        var running = runtime.probes().runNext();
        awaitSignal(scan.reading, running);

        ending.end(connection);
        awaitSignal(scan.terminationRequested, running);
        awaitSignal(scan.awaitingExit, running);

        assertThat(worker.hasAcceptedSession()).isFalse();
        assertThat(scan.isAlive()).isTrue();
        assertThat(running).isNotDone();
        assertThat(connection.results()).isEmpty();
        scan.finish();
        running.get();

        assertThat(scan.isAlive()).isFalse();
        assertThat(connection.results())
            .as("a lost session must not receive a late result")
            .isEmpty();
      } finally {
        scan.finish();
      }
    }
  }

  private enum SessionEnd {
    COMPLETED,
    FAILED;

    private void end(ScriptedWorkerRuntime.Connection connection) {
      Consumer<ScriptedWorkerRuntime.Connection> action =
          switch (this) {
            case COMPLETED -> ScriptedWorkerRuntime.Connection::complete;
            case FAILED -> value -> value.fail("control connection failed during packet scan");
          };
      action.accept(connection);
    }
  }

  @Test
  @DisplayName("Should restore the interrupt flag when a frame-rate scan is cancelled")
  void shouldRestoreTheInterruptFlagWhenAFrameRateScanIsCancelled() throws Exception {
    var scan = new ParkedPacketScanProcess();
    var request = requestBuilder().build();
    var completed = new CompletableFuture<ProbeCompletion>();
    var probing =
        Thread.ofVirtual()
            .start(
                () -> {
                  var report = executor(scan).probe(root.resolve("movie.mkv"), request);
                  completed.complete(
                      new ProbeCompletion(report, Thread.currentThread().isInterrupted()));
                });
    try {
      awaitSignal(scan.reading, completed);

      probing.interrupt();
      awaitSignal(scan.terminationRequested, completed);
      awaitSignal(scan.awaitingExit, completed);
      assertThat(completed).isNotDone();
      scan.finish();

      var completion = completed.get();
      assertThat(completion.report().result())
          .isEqualTo(failure(request, ProbeFailure.PROBE_FAILURE_CANCELLED));
      assertThat(completion.interrupted()).isTrue();
    } finally {
      scan.finish();
      probing.join();
    }
  }

  private record ProbeCompletion(ProbeAttemptReport report, boolean interrupted) {}

  private static FfprobeExecutor executor(ParkedPacketScanProcess scan) {
    return new FfprobeExecutor(
        new ObjectMapper(),
        command -> {
          if (command.arguments().contains("-read_intervals")) {
            return scan;
          }

          return ScriptedProcess.builder()
              .output(
                  switch (command.tool()) {
                    case FFPROBE -> SCAN_REQUIRED.getBytes(StandardCharsets.UTF_8);
                    case FFMPEG -> new byte[0];
                  })
              .build();
        });
  }

  private static void awaitSignal(CompletableFuture<?> signal, CompletableFuture<?> work)
      throws Exception {
    CompletableFuture.anyOf(signal, work).get(5, TimeUnit.SECONDS);
    assertThat(signal).as("the process reaches its hook before the probe completes").isDone();
  }

  private static final class ParkedPacketScanProcess extends Process {

    private final CompletableFuture<Void> reading = new CompletableFuture<>();
    private final CompletableFuture<Void> terminationRequested = new CompletableFuture<>();
    private final CompletableFuture<Void> awaitingExit = new CompletableFuture<>();
    private final CompletableFuture<Boolean> releaseRead = new CompletableFuture<>();
    private final CompletableFuture<Process> exited =
        new CompletableFuture<>() {
          @Override
          public Process join() {
            awaitingExit.complete(null);
            return super.join();
          }
        };

    private final InputStream stdout =
        new InputStream() {
          @Override
          public int read() throws IOException {
            reading.complete(null);
            if (releaseRead.join()) {
              throw new IOException("packet output pipe failed");
            }

            return -1;
          }
        };

    void finish() {
      releaseRead.complete(false);
      exited.complete(this);
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return stdout;
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() throws InterruptedException {
      try {
        exited.get();
        return 0;
      } catch (ExecutionException failure) {
        throw new AssertionError(failure);
      }
    }

    @Override
    public int exitValue() {
      if (isAlive()) {
        throw new IllegalThreadStateException("packet scan is still running");
      }

      return 0;
    }

    @Override
    public boolean isAlive() {
      return !exited.isDone();
    }

    @Override
    public void destroy() {
      releaseRead.complete(false);
      terminationRequested.complete(null);
    }

    @Override
    public Process destroyForcibly() {
      destroy();
      return this;
    }

    @Override
    public CompletableFuture<Process> onExit() {
      return exited;
    }
  }
}
