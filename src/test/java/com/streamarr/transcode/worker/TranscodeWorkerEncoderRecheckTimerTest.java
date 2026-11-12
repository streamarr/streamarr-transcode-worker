package com.streamarr.transcode.worker;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.fileContentsProducer;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.startVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.engine.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.MonotonicClock;
import com.streamarr.transcode.engine.TranscodeCapabilityService;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcess.ExitTiming;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.fixtures.FfmpegMuxerHelpFixtures;
import com.streamarr.transcode.fixtures.TopLevelBoxes;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

// ADR 0037: the worker reports how far each active attempt's output has reached, and each report
// that advances restarts the server's stall clock. ADR 0044: while a hardware encoder is withdrawn,
// the worker runs its test encode again. No GPU runs here: the scripted test encode stays running
// until the test lets it exit or the worker stops it, as an FFmpeg that waits on an unresponsive
// driver does.
@Tag("UnitTest")
@DisplayName("Transcode Worker Encoder Recheck Timer Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerEncoderRecheckTimerTest {

  private static final String H264_NVENC_ENCODERS =
      "V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)\n";
  private static final String DEVICE_LOST =
      """
      [h264_nvenc @ 0x55d0c8a4e1c0] Failed locking bitstream buffer: device lost (40)
      Error submitting video frame to the encoder
      """;
  private static final Duration EVENT_LIMIT = Duration.ofSeconds(10);
  // Three of the worker's progress intervals, and well inside the test encode's 10 s deadline.
  private static final Duration PROGRESS_LIMIT = Duration.ofSeconds(3);
  // Well inside the test encode's 10 s deadline, after which the recheck ends it on its own.
  private static final Duration STOP_LIMIT = Duration.ofSeconds(5);

  @TempDir Path tempDir;

  private final ScriptedWorkerRuntime runtime = new ScriptedWorkerRuntime();
  private final AtomicBoolean nvencTestEncodeStaysRunning = new AtomicBoolean();
  private final CompletableFuture<ScriptedProcess> runningNvencTestEncode =
      new CompletableFuture<>();

  @BeforeEach
  void setUp() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
  }

  @Test
  @DisplayName(
      "Should keep reporting a running attempt's progress when the test encode of a withdrawn"
          + " hardware encoder has not exited")
  void shouldKeepReportingARunningAttemptsProgressWhenAWithdrawnEncodersTestEncodeHasNotExited()
      throws Exception {
    var softwareOutput = bytesOf(ENCODED_RECORDING);
    var launcher =
        ScriptedProcessLauncher.forCommands(
            (command, _) -> {
              if (command.contains("h264_nvenc")) {
                return nvencLosingItsDevice();
              }

              return ScriptedProcess.builder()
                  .output(softwareOutput)
                  .pauseAfter(endOfFragment(softwareOutput, 1))
                  .exitTiming(ExitTiming.WHEN_TEST_EXITS)
                  .build();
            });
    var failed = encodeJob();
    var running = encodeJob();

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      nvencTestEncodeStaysRunning.set(true);
      startVariant(connection, failed);
      var testEncode = runningNvencTestEncode.get(EVENT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);

      startVariant(connection, running);
      var software = awaitLaunch(launcher, running);
      assertThat(software.pauseReached()).succeedsWithin(EVENT_LIMIT);
      var reportedWhileTheTestEncodeRan = reportsProgressWithin(connection, running);
      var testEncodeStillRan = testEncode.isAlive();
      nvencTestEncodeStaysRunning.set(false);
      testEncode.exit();
      var reportedOnceTheTestEncodeExited = reportsProgressWithin(connection, running);
      software.resume();
      software.exit();

      assertThat(testEncodeStillRan)
          .as("the test encode of the withdrawn encoder ran through the whole wait")
          .isTrue();
      assertThat(reportedOnceTheTestEncodeExited)
          .as("the attempt's output had advanced, so a progress report was due")
          .isTrue();
      assertThat(reportedWhileTheTestEncodeRan)
          .as(
              "a progress report of the running attempt arrived within %s, before the test encode"
                  + " exited",
              PROGRESS_LIMIT)
          .isTrue();
    }
  }

  // Every session path ends in close(), so the close is what keeps a recheck from outliving its
  // session.
  @Test
  @DisplayName(
      "Should stop the running test encode of a withdrawn hardware encoder when the worker closes")
  void shouldStopTheRunningTestEncodeOfAWithdrawnHardwareEncoderWhenTheWorkerCloses()
      throws Exception {
    var launcher = ScriptedProcessLauncher.forCommands((_, _) -> nvencLosingItsDevice());
    ScriptedProcess testEncode;

    try (var worker = worker(launcher)) {
      worker.start("localhost", 1);
      nvencTestEncodeStaysRunning.set(true);
      startVariant(runtime.connection(), encodeJob());
      testEncode = runningNvencTestEncode.get(EVENT_LIMIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    assertThat(testEncode.onExit())
        .as("the test encode ended within %s of the close, before its own deadline", STOP_LIMIT)
        .succeedsWithin(STOP_LIMIT);
  }

  // The worker's progress timer is wall-clock time that no fake drives, so nothing but the session
  // stream shows a report.
  private static boolean reportsProgressWithin(
      ScriptedWorkerRuntime.Connection connection, VariantJob job) {
    try {
      await().atMost(PROGRESS_LIMIT).until(() -> hasProgressReportOf(connection, job));
      return true;
    } catch (ConditionTimeoutException _) {
      return false;
    }
  }

  private static boolean hasProgressReportOf(
      ScriptedWorkerRuntime.Connection connection, VariantJob job) {
    return connection.events().stream()
        .filter(EstablishWorkerSessionRequest::hasJobAttemptProgress)
        .map(EstablishWorkerSessionRequest::getJobAttemptProgress)
        .anyMatch(progress -> progress.getJobAttemptId().equals(job.getJobAttemptId()));
  }

  private static ScriptedProcess awaitLaunch(ScriptedProcessLauncher launcher, VariantJob job) {
    var jobAttemptId = fromProto(job.getJobAttemptId());
    await().atMost(EVENT_LIMIT).until(() -> launcher.hasLaunched(jobAttemptId));
    return launcher.process(jobAttemptId);
  }

  // Where the fragment at this position after the initialization segment ends.
  private static int endOfFragment(byte[] output, int fragmentIndex) {
    return TopLevelBoxes.of(output).stream()
        .filter(box -> box.type().equals("mdat"))
        .skip(fragmentIndex)
        .findFirst()
        .orElseThrow()
        .end();
  }

  private TranscodeWorker worker(ScriptedProcessLauncher launcher) {
    var capabilities =
        new TranscodeCapabilityService("ffmpeg", command -> capabilityCheck(command.command()));
    capabilities.detectCapabilities();
    var engine =
        FfmpegTranscodeEngine.builder()
            .commandBuilder(new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1)))
            .capabilityService(capabilities)
            .launcher(launcher)
            .encoderStallTimeout(FfmpegTranscodeEngine.DEFAULT_ENCODER_STALL_TIMEOUT)
            .build();
    return TranscodeWorker.builder()
        .configuration(
            workerConfigurationBuilder()
                .availableSlots(2)
                .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, tempDir))
                .hardwareEncoderRecheckInterval(Duration.ofMillis(10))
                .build())
        .engine(engine)
        .ffprobe(fileContentsProducer())
        .runtime(runtime)
        .clock(MonotonicClock.system())
        .build();
  }

  // Every capability check has exited cleanly by the time the worker waits for it, except the test
  // encode of NVENC once the test makes it stay running.
  private ScriptedProcess capabilityCheck(List<String> arguments) {
    if (arguments.contains("h264_nvenc") && nvencTestEncodeStaysRunning.get()) {
      var testEncode =
          ScriptedProcess.builder()
              .output(new byte[0])
              .exitTiming(ExitTiming.WHEN_TEST_EXITS)
              .build();
      runningNvencTestEncode.complete(testEncode);
      return testEncode;
    }

    return ScriptedProcess.builder()
        .output(stdoutOf(arguments).getBytes(StandardCharsets.UTF_8))
        .exitTiming(ExitTiming.AT_LAUNCH)
        .build();
  }

  private static String stdoutOf(List<String> arguments) {
    if (arguments.contains("muxer=mp4")) {
      return FfmpegMuxerHelpFixtures.FRAGMENTED_MP4_MUXER_HELP;
    }

    if (arguments.contains("-encoders")) {
      return H264_NVENC_ENCODERS;
    }

    return "";
  }

  private static ScriptedProcess nvencLosingItsDevice() {
    return ScriptedProcess.builder().output(new byte[0]).exitCode(1).stderr(DEVICE_LOST).build();
  }

  private static VariantJob encodeJob() {
    return variantJobBuilder(TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE)
        .setJobAttemptId(toProto(UUID.randomUUID()))
        .build();
  }
}
