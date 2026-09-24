package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.engine.AttemptOutcome.Stopped;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
class ProcessBuilderLauncherTest {

  private static final Duration OUTCOME_LIMIT = Duration.ofSeconds(10);

  @TempDir Path tempDir;

  private final ProcessBuilderLauncher launcher = new ProcessBuilderLauncher();
  private final RecordingSegmentSink sink = new RecordingSegmentSink();

  @Test
  @DisplayName("Should name the job attempt in the process environment when launching")
  void shouldNameTheJobAttemptInTheProcessEnvironmentWhenLaunching()
      throws IOException, InterruptedException {
    var jobAttemptId = UUID.randomUUID();

    var process =
        launcher.launch(
            List.of("bash", "-c", "printf %s \"$STREAMARR_JOB_ATTEMPT_ID\""), jobAttemptId);

    assertThat(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8))
        .isEqualTo(jobAttemptId.toString());
    assertThat(process.waitFor(OUTCOME_LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
  }

  @Test
  @DisplayName(
      "Should complete the attempt when a launched process writes a recorded output and exits")
  void shouldCompleteTheAttemptWhenALaunchedProcessWritesARecordedOutputAndExits()
      throws IOException {
    var recordingFile = Files.write(tempDir.resolve(ENCODED_RECORDING), bytesOf(ENCODED_RECORDING));

    var producer = producerRunning("cat \"$0\"", recordingFile.toString()).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.acceptedBytes()).isEqualTo(bytesOf(ENCODED_RECORDING));
  }

  @Test
  @DisplayName("Should report the launched process's error output when it exits non-zero")
  void shouldReportTheLaunchedProcesssErrorOutputWhenItExitsNonZero() {
    var producer = producerRunning("echo 'Conversion failed!' >&2; exit 1", "ffmpeg").start();

    assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .asInstanceOf(InstanceOfAssertFactories.type(Failed.class))
        .satisfies(
            failure -> {
              assertThat(failure.reason()).isEqualTo(ProducerFailure.PROCESS_EXITED_WITH_ERROR);
              assertThat(failure.detail()).endsWith("Conversion failed!");
            });
  }

  @Test
  @DisplayName(
      "Should settle the stop without destroying the process when a launched process quits on q")
  void shouldSettleTheStopWithoutDestroyingTheProcessWhenALaunchedProcessQuitsOnQ()
      throws IOException, InterruptedException {
    var recordingFile = Files.write(tempDir.resolve(ENCODED_RECORDING), bytesOf(ENCODED_RECORDING));
    var initializationSegmentLength =
        recording(ENCODED_RECORDING).initializationSegment().byteLength();
    var producer =
        producerRunning(
                "head -c " + initializationSegmentLength + " \"$0\"; read -r -n 1 quit; exit 0",
                recordingFile.toString())
            .gracePeriod(Duration.ofSeconds(30))
            .start();

    var stopping = Thread.ofVirtual().start(producer::stop);

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Stopped());
    assertThat(stopping.join(OUTCOME_LIMIT)).isTrue();
  }

  @Test
  @DisplayName(
      "Should kill a launched process that stops writing and ignores termination, and fail the"
          + " attempt as an encoder stall")
  void shouldKillALaunchedProcessThatStopsWritingAndIgnoresTerminationAndFailTheAttempt()
      throws IOException {
    var recordingFile = Files.write(tempDir.resolve(ENCODED_RECORDING), bytesOf(ENCODED_RECORDING));
    var initializationSegmentLength =
        recording(ENCODED_RECORDING).initializationSegment().byteLength();
    // Ignoring SIGTERM survives the exec, as a hung FFmpeg ignores it.
    var producer =
        producerRunning(
                "head -c " + initializationSegmentLength + " \"$0\"; trap '' TERM; exec sleep 60",
                recordingFile.toString())
            .stallTimeout(Duration.ofMillis(300))
            .gracePeriod(Duration.ofMillis(300))
            .start();

    assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .asInstanceOf(InstanceOfAssertFactories.type(Failed.class))
        .extracting(Failed::reason)
        .isEqualTo(ProducerFailure.ENCODER_STALLED);
  }

  @Test
  @DisplayName("Should refuse to start the attempt when the process cannot be launched")
  void shouldRefuseToStartTheAttemptWhenTheProcessCannotBeLaunched() {
    var producer =
        Producer.builder()
            .launcher(launcher)
            .command(List.of(tempDir.resolve("missing-ffmpeg").toString()))
            .jobAttemptId(UUID.randomUUID())
            .periodSeconds(6)
            .startSequenceNumber(0)
            .gracePeriod(Duration.ofSeconds(5))
            .stallTimeout(Duration.ofMinutes(1))
            .memoryBudget(SegmentMemoryBudget.forSlots(1))
            .sink(sink);

    assertThatThrownBy(producer::start)
        .isInstanceOf(TranscodeException.class)
        .hasCauseInstanceOf(IOException.class);
  }

  // A producer whose FFmpeg is this bash script, with $0 bound to the argument.
  private Producer.ProducerBuilder producerRunning(String script, String argument) {
    return Producer.builder()
        .launcher(launcher)
        .command(List.of("bash", "-c", script, argument))
        .jobAttemptId(UUID.randomUUID())
        .periodSeconds(6)
        .startSequenceNumber(0)
        .gracePeriod(Duration.ofSeconds(5))
        .stallTimeout(Duration.ofMinutes(1))
        .memoryBudget(SegmentMemoryBudget.forSlots(1))
        .sink(sink);
  }
}
