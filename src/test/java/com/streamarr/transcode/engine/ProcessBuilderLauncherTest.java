package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.fixtures.DeliveredSamples.assertCarriesTheRecordedSamples;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.engine.AttemptOutcome.Stopped;
import com.streamarr.transcode.fixtures.RecordingFixtures;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Process Builder Launcher Tests")
class ProcessBuilderLauncherTest {

  // A scripted FFmpeg writes no framecrc of the source's streams.
  private static final SourceEnd NOTHING_READ_FROM_THE_SOURCE =
      new SourceEnd(List.of(), Optional.empty());

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
      "Should stop a launched process's output while it is paused and continue it once resumed")
  void shouldStopALaunchedProcesssOutputWhileItIsPausedAndContinueItOnceResumed()
      throws IOException {
    var process =
        launcher.launch(
            List.of("bash", "-c", "while :; do printf x; sleep 0.01; done"), UUID.randomUUID());
    try {
      var output = process.getInputStream();
      await().atMost(OUTCOME_LIMIT).until(() -> output.available() > 0);

      launcher.pause(process);
      var lastSeen = new AtomicInteger(-1);
      await()
          .pollInterval(Duration.ofMillis(100))
          .atMost(OUTCOME_LIMIT)
          .until(
              () -> {
                var available = output.available();
                return available == lastSeen.getAndSet(available);
              });
      var settled = output.available();

      await()
          .during(Duration.ofMillis(300))
          .atMost(OUTCOME_LIMIT)
          .until(() -> output.available() == settled);
      launcher.resume(process);
      await().atMost(OUTCOME_LIMIT).until(() -> output.available() > settled);
    } finally {
      process.destroyForcibly();
    }
  }

  @Test
  @DisplayName("Should leave a process that has exited alone when pausing and resuming it")
  void shouldLeaveAProcessThatHasExitedAloneWhenPausingAndResumingIt() throws Exception {
    var process = launcher.launch(List.of("bash", "-c", "exit 0"), UUID.randomUUID());
    assertThat(process.waitFor(OUTCOME_LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

    launcher.pause(process);
    launcher.resume(process);

    assertThat(process.exitValue()).isZero();
  }

  @Test
  @EnabledOnOs(OS.LINUX)
  @DisplayName(
      "Should leave the process that took a child's pid alone when the child is reaped before it"
          + " is paused")
  void shouldLeaveTheProcessThatTookAChildsPidAloneWhenTheChildIsReapedBeforeItIsPaused()
      throws Exception {
    var child = launcher.launch(List.of("bash", "-c", "exit 0"), UUID.randomUUID());
    assertThat(child.waitFor(OUTCOME_LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    var successor = launcher.launch(List.of("sleep", "60"), UUID.randomUUID());
    try {
      launcher.pause(new ReapedChildWhosePidWasTaken(child, successor.pid()));

      await()
          .during(Duration.ofMillis(500))
          .atMost(OUTCOME_LIMIT)
          .until(() -> linuxProcessState(successor.pid()) != 'T');
    } finally {
      successor.destroyForcibly();
    }
  }

  @Test
  @DisplayName(
      "Should complete the attempt when a launched process writes a recorded output and exits")
  void shouldCompleteTheAttemptWhenALaunchedProcessWritesARecordedOutputAndExits()
      throws IOException {
    var recordingFile = Files.write(tempDir.resolve(ENCODED_RECORDING), bytesOf(ENCODED_RECORDING));

    var producer = producerRunning("cat \"$0\"", recordingFile.toString()).start();

    assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .isEqualTo(new Completed(0, NOTHING_READ_FROM_THE_SOURCE));
    assertCarriesTheRecordedSamples(sink.acceptedBytes(), recording(ENCODED_RECORDING));
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
      "Should fail the attempt and kill FFmpeg when it stops writing and ignores termination")
  void shouldFailTheAttemptAndKillFfmpegWhenItStopsWritingAndIgnoresTermination()
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
        producerRunning("unused", "unused")
            .pipeline(
                new FfmpegPipeline.SingleProcess(
                    List.of(tempDir.resolve("missing-ffmpeg").toString())));

    assertThatThrownBy(producer::start)
        .isInstanceOf(TranscodeException.class)
        .hasCauseInstanceOf(IOException.class);
  }

  @Test
  @DisplayName(
      "Should end both FFmpegs when they ignore termination while the copy waits for the encoding"
          + " FFmpeg to read")
  void shouldEndBothFfmpegsWhenTheyIgnoreTerminationWhileTheCopyWaitsForTheEncodingFfmpegToRead()
      throws IOException {
    var initializationSegment = tempDir.resolve("init.mp4");
    var fragment = tempDir.resolve("fragment.m4s");
    Files.write(initializationSegment, HevcCopies.initializationSegment());
    var sample = HevcCopies.sample(HevcCopies.slice(0), new byte[64 * 1024]);
    Files.write(fragment, HevcCopies.fragmentBuilder().video(0, sample).build());
    var endlessCopy = "trap '' TERM; cat \"$0\"; while :; do cat \"$1\"; done";
    // The fragment's one sample is no keyframe and lasts 1001 ticks of the copy's 24000 Hz
    // timescale; every repeat of the fragment repeats its record.
    var timingPastWhatThePipeHolds =
        "#tb 0: 1/48000\n"
            + ("0,          0,       4004,     2002, %8d, 0x00000000, F=0x0"
                        .formatted(sample.length)
                    + "\n")
                .repeat(64);
    var launched = new CopyOnWriteArrayList<Process>();
    ProcessLauncher copyWritingTiming =
        (command, jobAttemptId) -> {
          if (command.contains(endlessCopy)) {
            CopiedVideoTiming.writeTo(command, timingPastWhatThePipeHolds);
          }

          var process = launcher.launch(command, jobAttemptId);
          launched.add(process);
          return process;
        };
    var producer =
        producerRunning("unused", "unused")
            .launcher(copyWritingTiming)
            .pipeline(
                new FfmpegPipeline.RpuMovingPipe(
                    List.of(
                        "bash",
                        "-c",
                        endlessCopy,
                        initializationSegment.toString(),
                        fragment.toString()),
                    List.of("bash", "-c", "trap '' TERM; exec sleep 600")))
            .stallTimeout(Duration.ofMillis(300))
            .gracePeriod(Duration.ofMillis(300))
            .start();

    assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .asInstanceOf(InstanceOfAssertFactories.type(Failed.class))
        .extracting(Failed::reason)
        .isEqualTo(ProducerFailure.ENCODER_STALLED);
    assertThat(launched).hasSize(2);
    assertThat(launched.getFirst().isAlive()).as("the copying FFmpeg is alive").isFalse();
    assertThat(launched.getLast().isAlive()).as("the encoding FFmpeg is alive").isFalse();
  }

  // The state letter of /proc/<pid>/stat; T is stopped by a signal.
  private static char linuxProcessState(long pid) throws IOException {
    var stat = Files.readString(Path.of("/proc", Long.toString(pid), "stat"));
    return stat.charAt(stat.lastIndexOf(')') + 2);
  }

  // A child that the kernel has reaped, whose pid another process took before the JDK recorded
  // the exit: Process.isAlive still answers true, and the pid names the successor.
  private static final class ReapedChildWhosePidWasTaken extends Process {

    private final Process reaped;
    private final long successorPid;

    private ReapedChildWhosePidWasTaken(Process reaped, long successorPid) {
      this.reaped = reaped;
      this.successorPid = successorPid;
    }

    @Override
    public long pid() {
      return successorPid;
    }

    @Override
    public boolean isAlive() {
      return true;
    }

    @Override
    public ProcessHandle toHandle() {
      return reaped.toHandle();
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public void destroy() {
      // The child has exited.
    }
  }

  // A producer whose FFmpeg is this bash script, with $0 bound to the argument.
  private Producer.ProducerBuilder producerRunning(String script, String argument) {
    return Producer.builder()
        .launcher(launcher)
        .pipeline(new FfmpegPipeline.SingleProcess(List.of("bash", "-c", script, argument)))
        .jobAttemptId(UUID.randomUUID())
        .intervals(SegmentIntervals.onPlainGrid(6))
        .startSequenceNumber(0)
        .gracePeriod(Duration.ofSeconds(5))
        .stallTimeout(Duration.ofMinutes(1))
        .encodedFrameRate(Optional.<OutputFrameRate>empty())
        .videoDecision(
            VideoDecisionFixtures.copyOf(RecordingFixtures.ENCODED_RECORDING_CODEC_STRING))
        .paddedAudioEnd(Optional.empty())
        .memoryBudget(SegmentMemoryBudget.forSlots(1))
        .sink(sink)
        .clock(MonotonicClock.system());
  }
}
