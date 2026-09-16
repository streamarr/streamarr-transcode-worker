package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.streamarr.transcode.worker.support.ControlledProbeProcess;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Logging Tests")
class FfprobeExecutorLoggingTest {

  private static final Path SOURCE = Path.of("/media/movie.mkv");

  private final Logger logger = (Logger) LoggerFactory.getLogger(FfprobeExecutor.class);
  private final ConcurrentLinkedQueue<ILoggingEvent> events = new ConcurrentLinkedQueue<>();
  private final AppenderBase<ILoggingEvent> appender =
      new AppenderBase<>() {
        @Override
        protected void append(ILoggingEvent event) {
          event.prepareForDeferredProcessing();
          events.add(event);
        }
      };

  @BeforeEach
  void captureLogs() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detachLogs() {
    logger.detachAppender(appender);
    appender.stop();
  }

  @Test
  @DisplayName("Should log the attempt source and cause when ffprobe cannot start")
  void shouldLogTheAttemptSourceAndCauseWhenFfprobeCannotStart() {
    var attemptId = UUID.randomUUID();
    var request = requestBuilder().setProbeAttemptId(toProto(attemptId)).build();
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            _ -> {
              throw new UncheckedIOException(new IOException("binary unavailable"));
            });

    var result = executor.probe(SOURCE, request);

    assertExecutionFailure(result, request);
    assertThat(eventsFor(attemptId))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains(SOURCE.toString());
              assertThat(event.getThrowableProxy()).isNotNull();
              assertThat(event.getThrowableProxy().getCause()).isNotNull();
              assertThat(event.getThrowableProxy().getCause().getMessage())
                  .isEqualTo("binary unavailable");
            });
  }

  @Test
  @DisplayName("Should log local exit diagnostics when ffprobe reports an execution failure")
  void shouldLogLocalExitDiagnosticsWhenFfprobeReportsAnExecutionFailure() {
    var attemptId = UUID.randomUUID();
    var request = requestBuilder().setProbeAttemptId(toProto(attemptId)).build();
    var process =
        ControlledProbeProcess.builder()
            .stdout(
                """
            {"error":{"code":-12345,"string":"local probe diagnostic"}}
            """)
            .exitCode(17)
            .build();
    var executor = new FfprobeExecutor(new ObjectMapper(), _ -> process);

    var result = executor.probe(SOURCE, request);

    assertExecutionFailure(result, request);
    assertThat(eventsFor(attemptId))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage())
                  .contains(
                      SOURCE.toString(),
                      "exited with code 17",
                      "error -12345",
                      "local probe diagnostic");
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"not json", "null", ""})
  @DisplayName("Should log the exit status when ffprobe output cannot be interpreted")
  void shouldLogTheExitStatusWhenFfprobeOutputCannotBeInterpreted(String output) {
    var attemptId = UUID.randomUUID();
    var request = requestBuilder().setProbeAttemptId(toProto(attemptId)).build();
    var process = ControlledProbeProcess.builder().stdout(output).exitCode(19).build();
    var executor = new FfprobeExecutor(new ObjectMapper(), _ -> process);

    var result = executor.probe(SOURCE, request);

    assertExecutionFailure(result, request);
    assertThat(eventsFor(attemptId))
        .singleElement()
        .satisfies(
            event -> {
              assertThat(event.getLevel()).isEqualTo(Level.WARN);
              assertThat(event.getFormattedMessage()).contains(SOURCE.toString(), "exit code 19");
              assertThat(event.getThrowableProxy()).isNotNull();
            });
  }

  private ProbeRequest.Builder requestBuilder() {
    return ProbeRequest.newBuilder().setProbeVersion(FfprobeExecutor.PROBE_VERSION);
  }

  private List<ILoggingEvent> eventsFor(UUID attemptId) {
    return events.stream()
        .filter(event -> event.getFormattedMessage().contains(attemptId.toString()))
        .toList();
  }

  private void assertExecutionFailure(ProbeAttemptResult result, ProbeRequest request) {
    assertThat(result)
        .isEqualTo(
            ProbeAttemptResult.newBuilder()
                .setProbeAttemptId(request.getProbeAttemptId())
                .setProbeVersion(request.getProbeVersion())
                .setFailure(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED)
                .build());
  }
}
