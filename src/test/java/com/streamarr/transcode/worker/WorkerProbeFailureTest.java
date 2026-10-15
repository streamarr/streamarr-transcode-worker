package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.failure;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.processBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Worker Probe Failure Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class WorkerProbeFailureTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName(
      "Should reply with execution failure when the producer throws an unchecked exception")
  void shouldReplyWithExecutionFailureWhenTheProducerThrowsAnUncheckedException() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var producer =
        new FfprobeExecutor(
            new ObjectMapper(),
            _ -> {
              throw new IllegalStateException("producer unavailable");
            });
    var request = requestBuilder().build();

    try (var worker = workerBuilder(tempDir).runtime(runtime).ffprobe(producer).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().results())
          .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED));
    }
  }

  @Test
  @DisplayName("Should report a terminal failure when a filesystem provider fails unexpectedly")
  void shouldReportATerminalFailureWhenAFilesystemProviderFailsUnexpectedly() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var root = mock(Path.class);
    when(root.toRealPath())
        .thenThrow(new IllegalStateException("filesystem provider failed"))
        .thenReturn(tempDir.toRealPath());
    var configuration =
        workerConfigurationBuilder()
            .availableSlots(1)
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, root))
            .build();
    var runtime = new ScriptedWorkerRuntime();
    var producer = new FfprobeExecutor(new ObjectMapper(), _ -> processBuilder().build());
    var request = requestBuilder().build();

    try (var worker =
        workerBuilder(tempDir)
            .configuration(configuration)
            .runtime(runtime)
            .ffprobe(producer)
            .build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      start(connection, request);
      runtime.probes().drain();

      assertThat(connection.results())
          .containsExactly(failure(request, ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED));

      var next = requestBuilder().build();
      start(connection, next);
      runtime.probes().drain();
      assertThat(connection.results())
          .hasSize(2)
          .last()
          .satisfies(
              result -> {
                assertThat(result.getProbeAttemptId()).isEqualTo(next.getProbeAttemptId());
                assertThat(result.hasMedia()).isTrue();
              });
    }
  }
}
