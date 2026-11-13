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
  // FFmpeg's error output after the stream mapping when NVDEC fails to start, from the pinned
  // FFmpeg
  // on an RTX 5090, with NVDEC's reason in place of %s. FFmpeg then decodes in software, and the
  // GPU
  // filters, which take only CUDA frames, fail. The list of pixel formats pushes NVDEC's reason
  // more than 2000 characters before the end.
  private static final String NVDEC_START_FAILURE =
      """
      Stream mapping:
        Stream #0:0 -> #0:0 (hevc (native) -> hevc (hevc_nvenc))
      %s
      [hevc @ 0x56489279cf00] Failed setup for format cuda: hwaccel initialisation returned \
      error.
      Impossible to convert between the formats supported by the filter 'graph -1 input from \
      stream 0:0' and the filter 'auto_scale_0'
      Link 'graph -1 input from stream 0:0.default' -> 'auto_scale_0.default':
        Pixel formats:
          src: yuv420p10le
          dst: yuv420p10le
      Link 'auto_scale_0.default' -> 'Parsed_scale_cuda_0.default':
        Pixel formats:
          src: yuv420p yuyv422 rgb24 bgr24 yuv422p yuv444p yuv410p yuv411p gray monow monob \
      pal8 yuvj420p yuvj422p yuvj444p uyvy422 bgr8 bgr4 bgr4_byte rgb8 rgb4 rgb4_byte nv12 \
      nv21 argb rgba abgr bgra gray16be gray16le yuv440p yuvj440p yuva420p rgb48be rgb48le \
      rgb565be rgb565le rgb555be rgb555le bgr565be bgr565le bgr555be bgr555le yuv420p16le \
      yuv420p16be yuv422p16le yuv422p16be yuv444p16le yuv444p16be rgb444le rgb444be bgr444le \
      bgr444be ya8 bgr48be bgr48le yuv420p9be yuv420p9le yuv420p10be yuv420p10le yuv422p10be \
      yuv422p10le yuv444p9be yuv444p9le yuv444p10be yuv444p10le yuv422p9be yuv422p9le gbrp \
      gbrp9be gbrp9le gbrp10be gbrp10le gbrp16be gbrp16le yuva422p yuva444p yuva420p9be \
      yuva420p9le yuva422p9be yuva422p9le yuva444p9be yuva444p9le yuva420p10be yuva420p10le \
      yuva422p10be yuva422p10le yuva444p10be yuva444p10le yuva420p16be yuva420p16le \
      yuva422p16be yuva422p16le yuva444p16be yuva444p16le xyz12le xyz12be nv16 nv20le nv20be \
      rgba64be rgba64le bgra64be bgra64le yvyu422 ya16be ya16le gbrap gbrap16be gbrap16le 0rgb \
      rgb0 0bgr bgr0 yuv420p12be yuv420p12le yuv420p14be yuv420p14le yuv422p12be yuv422p12le \
      yuv422p14be yuv422p14le yuv444p12be yuv444p12le yuv444p14be yuv444p14le gbrp12be \
      gbrp12le gbrp14be gbrp14le yuvj411p yuv440p10le yuv440p10be yuv440p12le yuv440p12be \
      ayuv64le ayuv64be p010le p010be gbrap12be gbrap12le gbrap10be gbrap10le gray12be \
      gray12le gray10be gray10le p016le p016be gray9be gray9le gbrpf32be gbrpf32le gbrapf32be \
      gbrapf32le gray14be gray14le grayf32be grayf32le yuva422p12be yuva422p12le yuva444p12be \
      yuva444p12le nv24 nv42 y210le x2rgb10le x2bgr10le p210be p210le p410be p410le p216be \
      p216le p416be p416le vuya vuyx p012le p012be y212le xv30le xv36be xv36le p212be p212le \
      p412be p412le gbrap14be gbrap14le ayuv uyva vyu444 v30xle y216le xv48be xv48le \
      yuv444p10msbbe yuv444p10msble yuv444p12msbbe yuv444p12msble gbrp10msbbe gbrp10msble \
      gbrp12msbbe gbrp12msble
          dst: cuda
      [vf#0:0 @ 0x5648916cac80] Error reinitializing filters!
      [vf#0:0 @ 0x5648916cac80] Task finished with error code: -38 (Function not implemented)
      [vf#0:0 @ 0x5648916cac80] Terminating thread with return code -38 (Function not \
      implemented)
      [vost#0:0/hevc_nvenc @ 0x5648916ef040] [enc:hevc_nvenc @ 0x5648916ef2c0] Could not open \
      encoder before EOF
      [vost#0:0/hevc_nvenc @ 0x5648916ef040] Task finished with error code: -22 (Invalid \
      argument)
      [vost#0:0/hevc_nvenc @ 0x5648916ef040] Terminating thread with return code -22 (Invalid \
      argument)
      [out#0/null @ 0x5648916ca280] Nothing was written into output file, because at least one \
      of its streams received no packets.
      Conversion failed!
      """;
  // NVDEC refuses a picture smaller than 144x144.
  private static final String DECODER_REFUSAL =
      NVDEC_START_FAILURE.formatted(
          "[hevc @ 0x56489279cf00] Video width 64 not within range from 144 to 8192");
  // The line that FFmpeg's CHECK_CU prints when cuvidCreateDecoder fails, as after a driver reset
  // that takes the hardware decoder down.
  private static final String DECODER_FAILURE =
      NVDEC_START_FAILURE.formatted(
          "[hevc @ 0x56489279cf00] decoder->cvdl->cuvidCreateDecoder(&decoder->decoder, params)"
              + " failed -> CUDA_ERROR_UNKNOWN: unknown error");
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

  // ADR 0044: a driver reset that takes down the hardware encoder can take down the hardware
  // decoder on the same device too, so the worker withdraws the encoder before it reports the
  // failure.
  @Test
  @DisplayName(
      "Should stop offering NVENC before it reports the hardware encoder failure when a CUDA error"
          + " stops the hardware decoder from starting")
  void shouldStopOfferingNvencBeforeItReportsTheFailureWhenACudaErrorStopsTheHardwareDecoder()
      throws Exception {
    var launcher = launcherFailingNvencWith(DECODER_FAILURE);
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
      assertThat(lastEncodersChange(connection)).contains("libx264").doesNotContain("h264_nvenc");
      assertThat(event(connection, EventCase.JOB_ATTEMPT_FAILED).getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_HARDWARE_ENCODER_FAILED);
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
