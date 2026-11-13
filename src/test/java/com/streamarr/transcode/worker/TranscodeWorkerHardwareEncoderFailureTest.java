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

import build.buf.gen.streamarr.transcode.v1.EncoderKind;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest.EventCase;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import build.buf.gen.streamarr.transcode.v1.VideoCodecFamily;
import build.buf.gen.streamarr.transcode.v1.VideoEncoderBackend;
import com.streamarr.transcode.engine.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.MonotonicClock;
import com.streamarr.transcode.engine.TranscodeCapabilityService;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.fixtures.FfmpegMuxerHelpFixtures;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

// ADR 0044 and streamarr/streamarr-transcode-worker#56: what the worker does when NVENC fails
// during
// a stream. No GPU runs here: the scripted FFmpeg prints the errors that FFmpeg's nvenc.c prints.
@Tag("UnitTest")
@DisplayName("Transcode Worker Hardware Encoder Failure Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerHardwareEncoderFailureTest {

  private static final String H264_NVENC_ENCODERS =
      "V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)\n";
  private static final String DEVICE_LOST =
      """
      [h264_nvenc @ 0x55d0c8a4e1c0] Failed locking bitstream buffer: device lost (40)
      Error submitting video frame to the encoder
      """;
  private static final String SESSION_LIMIT =
      """
      [h264_nvenc @ 0x55d0c8a4e1c0] OpenEncodeSessionEx failed: incompatible client key (21): \
      (no details)
      [h264_nvenc @ 0x55d0c8a4e1c0] No capable devices found
      """;
  // The end of FFmpeg's error output when NVDEC refuses the source's picture and the GPU filters
  // then get frames from system memory.
  private static final String DECODER_REFUSAL =
      """
          src: yuv420p yuv420p10le p010le p016le
          dst: cuda
      [vf#0:0 @ 0x5613beacf940] Error reinitializing filters!
      [vost#0:0/h264_nvenc @ 0x5613beaf3780] [enc:h264_nvenc @ 0x5613beaf3a40] Could not open \
      encoder before EOF
      Conversion failed!
      """;
  private static final Duration EVENT_LIMIT = Duration.ofSeconds(10);

  @TempDir Path tempDir;

  private final ScriptedWorkerRuntime runtime = new ScriptedWorkerRuntime();
  // Whether NVENC passes its test encode, which the worker runs at startup and while NVENC is
  // withdrawn.
  private final AtomicBoolean nvencPassesItsTestEncode = new AtomicBoolean(true);
  private final AtomicInteger failedNvencTestEncodes = new AtomicInteger();

  @BeforeEach
  void setUp() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
  }

  @Test
  @DisplayName(
      "Should stop offering NVENC before it reports the hardware encoder failure when NVENC fails"
          + " during a stream")
  void shouldStopOfferingNvencBeforeItReportsTheHardwareEncoderFailureWhenNvencFailsDuringAStream()
      throws Exception {
    var launcher = launcherFailingNvencWith(DEVICE_LOST);
    var failed = encodeJob();

    try (var worker = worker(launcher, Duration.ofHours(1))) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, failed);

      awaitEvents(
          connection,
          EventCase.JOB_ATTEMPT_STARTED,
          EventCase.VIDEO_ENCODERS_CHANGED,
          EventCase.JOB_ATTEMPT_FAILED);
      assertThat(started(connection, failed).getEncoder()).isEqualTo("h264_nvenc");
      assertThat(lastEncodersChange(connection)).contains("libx264").doesNotContain("h264_nvenc");
      assertThat(event(connection, EventCase.JOB_ATTEMPT_FAILED).getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_HARDWARE_ENCODER_FAILED);
    }
  }

  @Test
  @DisplayName(
      "Should encode the next job and its seeks with libx264 decoding in software when NVENC"
          + " failed")
  void shouldEncodeTheNextJobWithLibx264DecodingInSoftwareWhenNvencFailed() throws Exception {
    var launcher = launcherFailingNvencWith(DEVICE_LOST);
    var failed = encodeJob();
    var next = encodeJob().toBuilder();
    next.getExecutionBuilder().setStartSequenceNumber(5).setSeekPositionSeconds(30);

    try (var worker = worker(launcher, Duration.ofHours(1))) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, failed);
      awaitEventCount(connection, EventCase.JOB_ATTEMPT_FAILED, 1);

      startVariant(connection, next.build());

      awaitEventCount(connection, EventCase.JOB_ATTEMPT_STARTED, 2);
      assertThat(started(connection, next.build()))
          .isEqualTo(backend("libx264", EncoderKind.ENCODER_KIND_SOFTWARE));
      assertThat(launcher.command(fromProto(next.getJobAttemptId())))
          .containsSubsequence("-c:v", "libx264")
          .doesNotContain("-hwaccel", "h264_nvenc");
    }
  }

  @Test
  @DisplayName(
      "Should keep offering NVENC and give the next job NVENC when the driver refuses a session at"
          + " its limit")
  void shouldKeepOfferingNvencAndGiveTheNextJobNvencWhenTheDriverRefusesASessionAtItsLimit()
      throws Exception {
    var launcher = launcherFailingNvencOnceWith(SESSION_LIMIT);
    var refused = encodeJob();
    var next = encodeJob();

    try (var worker = worker(launcher, Duration.ofHours(1))) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, refused);
      awaitEventCount(connection, EventCase.JOB_ATTEMPT_FAILED, 1);

      startVariant(connection, next);

      awaitEventCount(connection, EventCase.JOB_ATTEMPT_STARTED, 2);
      assertThat(event(connection, EventCase.JOB_ATTEMPT_FAILED).getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_HARDWARE_ENCODER_FAILED);
      assertThat(eventsOf(connection)).doesNotContain(EventCase.VIDEO_ENCODERS_CHANGED);
      assertThat(started(connection, next).getEncoder()).isEqualTo("h264_nvenc");
    }
  }

  // The server places the fallback on the software encoder, whose command decodes in software.
  @Test
  @DisplayName(
      "Should keep offering NVENC and report the hardware encoder failure of that job alone when"
          + " the hardware decoder refuses the job's source")
  void shouldKeepOfferingNvencAndReportTheFailureOfThatJobWhenTheHardwareDecoderRefusesItsSource()
      throws Exception {
    var launcher = launcherFailingNvencOnceWith(DECODER_REFUSAL);
    var refused = encodeJob();
    var next = encodeJob();

    try (var worker = worker(launcher, Duration.ofHours(1))) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, refused);
      awaitEventCount(connection, EventCase.JOB_ATTEMPT_FAILED, 1);

      startVariant(connection, next);

      awaitEventCount(connection, EventCase.JOB_ATTEMPT_STARTED, 2);
      assertThat(event(connection, EventCase.JOB_ATTEMPT_FAILED).getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_HARDWARE_ENCODER_FAILED);
      assertThat(eventsOf(connection)).doesNotContain(EventCase.VIDEO_ENCODERS_CHANGED);
      assertThat(started(connection, next).getEncoder()).isEqualTo("h264_nvenc");
    }
  }

  @Test
  @DisplayName(
      "Should run the software encoder of the family when the job asks for it while NVENC is"
          + " offered")
  void shouldRunTheSoftwareEncoderOfTheFamilyWhenTheJobAsksForItWhileNvencIsOffered()
      throws Exception {
    var launcher = ScriptedProcessLauncher.writing(ENCODED_RECORDING);
    var software = encodeJob().toBuilder();
    software.getDecisionBuilder().getVideoBuilder().setSoftwareEncoder(true);

    try (var worker = worker(launcher, Duration.ofHours(1))) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, software.build());

      awaitEventCount(connection, EventCase.JOB_ATTEMPT_COMPLETED, 1);
      assertThat(connection.registration().getCapabilities().getSoftwareVideoEncodersList())
          .extracting(encoder -> encoder.getBackend().getEncoder())
          .contains("libx264");
      assertThat(started(connection, software.build()))
          .isEqualTo(backend("libx264", EncoderKind.ENCODER_KIND_SOFTWARE));
      assertThat(launcher.command(fromProto(software.getJobAttemptId())))
          .containsSubsequence("-c:v", "libx264");
    }
  }

  @Test
  @DisplayName("Should offer NVENC again once its test encode passes after it failed")
  void shouldOfferNvencAgainOnceItsTestEncodePassesAfterItFailed() throws Exception {
    var launcher = launcherFailingNvencWith(DEVICE_LOST);

    try (var worker = worker(launcher, Duration.ofMillis(10))) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      nvencPassesItsTestEncode.set(false);
      startVariant(connection, encodeJob());
      awaitEventCount(connection, EventCase.VIDEO_ENCODERS_CHANGED, 1);

      nvencPassesItsTestEncode.set(true);

      awaitEventCount(connection, EventCase.VIDEO_ENCODERS_CHANGED, 2);
      assertThat(lastEncodersChange(connection)).contains("h264_nvenc");
    }
  }

  // ADR 0044: the encoder stays withdrawn while its test encode fails.
  @Test
  @DisplayName("Should keep NVENC withdrawn while its test encode fails")
  void shouldKeepNvencWithdrawnWhileItsTestEncodeFails() throws Exception {
    var launcher = launcherFailingNvencWith(DEVICE_LOST);

    try (var worker = worker(launcher, Duration.ofMillis(10))) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      nvencPassesItsTestEncode.set(false);
      startVariant(connection, encodeJob());
      awaitEventCount(connection, EventCase.JOB_ATTEMPT_FAILED, 1);
      var rechecked = failedNvencTestEncodes.get() + 2;
      await().atMost(EVENT_LIMIT).until(() -> failedNvencTestEncodes.get() >= rechecked);
      var next = encodeJob();

      startVariant(connection, next);

      awaitEventCount(connection, EventCase.JOB_ATTEMPT_STARTED, 2);
      assertThat(started(connection, next).getEncoder()).isEqualTo("libx264");
      assertThat(eventsOf(connection).stream().filter(EventCase.VIDEO_ENCODERS_CHANGED::equals))
          .hasSize(1);
    }
  }

  private TranscodeWorker worker(ScriptedProcessLauncher launcher, Duration recheckInterval) {
    var capabilities =
        new TranscodeCapabilityService(
            "ffmpeg",
            command ->
                new FinishedFfmpeg(
                    stdoutOf(command.command()), testEncodeExitCode(command.command())));
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
                .hardwareEncoderRecheckInterval(recheckInterval)
                .build())
        .engine(engine)
        .ffprobe(fileContentsProducer())
        .runtime(runtime)
        .clock(MonotonicClock.system())
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

  private int testEncodeExitCode(List<String> arguments) {
    if (arguments.contains("h264_nvenc") && !nvencPassesItsTestEncode.get()) {
      failedNvencTestEncodes.incrementAndGet();
      return 1;
    }

    return 0;
  }

  // Every NVENC encode fails with the error output; every software encode writes the recording.
  private static ScriptedProcessLauncher launcherFailingNvencWith(String errorOutput) {
    return ScriptedProcessLauncher.forCommands(
        (command, _) -> {
          if (command.contains("h264_nvenc")) {
            return ScriptedProcess.builder()
                .output(new byte[0])
                .exitCode(1)
                .stderr(errorOutput)
                .build();
          }

          return ScriptedProcess.builder().output(bytesOf(ENCODED_RECORDING)).build();
        });
  }

  // The first NVENC encode fails with the error output, and every later encode succeeds.
  private static ScriptedProcessLauncher launcherFailingNvencOnceWith(String errorOutput) {
    var failed = new AtomicBoolean();
    return ScriptedProcessLauncher.forCommands(
        (command, _) -> {
          if (command.contains("h264_nvenc") && failed.compareAndSet(false, true)) {
            return ScriptedProcess.builder()
                .output(new byte[0])
                .exitCode(1)
                .stderr(errorOutput)
                .build();
          }

          return ScriptedProcess.builder().output(bytesOf(ENCODED_RECORDING)).build();
        });
  }

  private static VariantJob encodeJob() {
    return variantJobBuilder(TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE)
        .setJobAttemptId(toProto(UUID.randomUUID()))
        .build();
  }

  private static VideoEncoderBackend backend(String encoder, EncoderKind kind) {
    return VideoEncoderBackend.newBuilder()
        .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264)
        .setEncoder(encoder)
        .setKind(kind)
        .build();
  }

  private static VideoEncoderBackend started(
      ScriptedWorkerRuntime.Connection connection, VariantJob job) {
    return connection.events().stream()
        .filter(EstablishWorkerSessionRequest::hasJobAttemptStarted)
        .map(EstablishWorkerSessionRequest::getJobAttemptStarted)
        .filter(started -> started.getJobAttemptId().equals(job.getJobAttemptId()))
        .findFirst()
        .orElseThrow()
        .getEncoderBackend();
  }

  private static EstablishWorkerSessionRequest event(
      ScriptedWorkerRuntime.Connection connection, EventCase eventCase) {
    return connection.events().stream()
        .filter(event -> event.getEventCase() == eventCase)
        .findFirst()
        .orElseThrow();
  }

  private static List<String> lastEncodersChange(ScriptedWorkerRuntime.Connection connection) {
    return connection.events().stream()
        .filter(EstablishWorkerSessionRequest::hasVideoEncodersChanged)
        .reduce((_, later) -> later)
        .orElseThrow()
        .getVideoEncodersChanged()
        .getVideoEncodersList()
        .stream()
        .map(encoder -> encoder.getBackend().getEncoder())
        .toList();
  }

  private static List<EventCase> eventsOf(ScriptedWorkerRuntime.Connection connection) {
    return connection.events().stream()
        .map(EstablishWorkerSessionRequest::getEventCase)
        .filter(
            eventCase ->
                eventCase != EventCase.REGISTRATION
                    && eventCase != EventCase.PROBE_CAPACITY_CHANGED
                    && eventCase != EventCase.JOB_ATTEMPT_PROGRESS)
        .toList();
  }

  private static void awaitEvents(
      ScriptedWorkerRuntime.Connection connection, EventCase... expected) {
    await()
        .atMost(EVENT_LIMIT)
        .untilAsserted(() -> assertThat(eventsOf(connection)).containsExactly(expected));
  }

  private static void awaitEventCount(
      ScriptedWorkerRuntime.Connection connection, EventCase eventCase, int count) {
    await()
        .atMost(EVENT_LIMIT)
        .untilAsserted(
            () ->
                assertThat(eventsOf(connection).stream().filter(eventCase::equals))
                    .hasSizeGreaterThanOrEqualTo(count));
  }

  // An FFmpeg run that has already exited with the exit code after writing the standard output.
  private static final class FinishedFfmpeg extends Process {

    private final InputStream standardOutput;
    private final int exitCode;

    private FinishedFfmpeg(String standardOutput, int exitCode) {
      this.standardOutput =
          new ByteArrayInputStream(standardOutput.getBytes(StandardCharsets.UTF_8));
      this.exitCode = exitCode;
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return standardOutput;
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return exitCode;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) {
      return true;
    }

    @Override
    public int exitValue() {
      return exitCode;
    }

    @Override
    public boolean isAlive() {
      return false;
    }

    @Override
    public void destroy() {
      // It has already exited.
    }
  }
}
