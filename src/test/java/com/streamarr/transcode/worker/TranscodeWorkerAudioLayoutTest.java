package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.startVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.AudioChannelLayout;
import build.buf.gen.streamarr.transcode.v1.AudioMode;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.probe.ProbeCommand;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Transcode Worker Audio Layout Tests")
class TranscodeWorkerAudioLayoutTest {

  // The pinned ffprobe's description of AC-3 5.1 in Matroska.
  private static final String AC3_SIDE_SURROUND =
      """
      {"streams":[{"index":1,"codec_name":"ac3","codec_type":"audio","sample_fmt":"fltp",
        "sample_rate":"48000","channels":6,"channel_layout":"5.1(side)","bits_per_sample":0}]}
      """;

  @TempDir Path tempDir;

  private final ScriptedWorkerRuntime runtime = new ScriptedWorkerRuntime();
  private final List<ProbeCommand> probeCommands = new ArrayList<>();
  private final List<TranscodeWorker> workers = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
  }

  @AfterEach
  void tearDown() {
    workers.forEach(TranscodeWorker::close);
  }

  @Test
  @DisplayName("Should encode in the named layout from the stream that ffprobe reads before FFmpeg")
  void shouldEncodeInTheNamedLayoutFromTheStreamThatFfprobeReadsBeforeFfmpeg() throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = layoutJob(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE, 6);

    var connection = start(launcher, ffprobeAnswering(AC3_SIDE_SURROUND), job);

    assertThat(eventsOf(connection))
        .containsExactly(EstablishWorkerSessionRequest.EventCase.JOB_ATTEMPT_STARTED);
    assertThat(probeCommands)
        .singleElement()
        .satisfies(
            command ->
                assertThat(command.arguments())
                    .containsSubsequence(
                        "-select_streams", "a:0", tempDir.resolve("movie.mkv").toString()));
    assertThat(launcher.command(fromProto(job.getJobAttemptId())))
        .containsSubsequence("-drc_scale:a:0", "0", "-i")
        .containsSubsequence(
            "-af",
            "channelmap=map=FL-FL|FR-FR|FC-FC|LFE-LFE|SL-BL|SR-BR:channel_layout=5.1,"
                + "aresample=filter_size=128:phase_shift=14:cutoff=0.95:kaiser_beta=14")
        .containsSubsequence("-ch_layout", "5.1")
        .doesNotContain("-ac");
  }

  @Test
  @DisplayName("Should read no audio stream when the job encodes its audio without a layout")
  void shouldReadNoAudioStreamWhenTheJobEncodesItsAudioWithoutALayout() throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var job = layoutJob(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_UNSPECIFIED, 2);

    var connection = start(launcher, ffprobeAnswering(AC3_SIDE_SURROUND), job);

    assertThat(eventsOf(connection))
        .containsExactly(EstablishWorkerSessionRequest.EventCase.JOB_ATTEMPT_STARTED);
    assertThat(probeCommands).isEmpty();
    assertThat(launcher.command(fromProto(job.getJobAttemptId()))).containsSubsequence("-ac", "2");
  }

  @Test
  @DisplayName("Should fail the attempt at startup when ffprobe cannot read the audio stream")
  void shouldFailTheAttemptAtStartupWhenFfprobeCannotReadTheAudioStream() throws Exception {
    var launcher = ScriptedProcessLauncher.running();
    var failing =
        new FfprobeExecutor(
            new ObjectMapper(),
            command -> {
              probeCommands.add(command);
              return ScriptedProcess.builder().output(new byte[0]).exitCode(1).build();
            });

    var connection =
        start(launcher, failing, layoutJob(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO, 2));

    assertFailedAs(connection, JobAttemptFailure.JOB_ATTEMPT_FAILURE_STARTUP_FAILED);
    assertThat(launcher.hasLaunchedAny()).isFalse();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = AudioMode.class,
      names = {"AUDIO_MODE_COPY", "AUDIO_MODE_NONE"})
  @DisplayName(
      "Should refuse the job as an invalid specification when it names a layout without an encode")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenItNamesALayoutWithoutAnEncode(AudioMode mode)
      throws Exception {
    var job = layoutJob(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO, 2).toBuilder();
    job.getDecisionBuilder().getAudioBuilder().setMode(mode);

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  @Test
  @DisplayName(
      "Should refuse the job as an invalid specification when it names an undefined layout")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenItNamesAnUndefinedLayout() throws Exception {
    var job = layoutJob(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO, 2).toBuilder();
    job.getDecisionBuilder().getAudioBuilder().setChannelLayoutValue(17);

    assertRefusedAsAnInvalidSpecification(job.build());
  }

  @Test
  @DisplayName(
      "Should refuse the job as an invalid specification when the channels differ from the layout's")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenTheChannelsDifferFromTheLayouts()
      throws Exception {
    assertRefusedAsAnInvalidSpecification(
        layoutJob(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_QUAD, 6));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE, 7",
    "AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE_BACK, 7",
    "AUDIO_CHANNEL_LAYOUT_SEVEN_POINT_ONE, 8",
    "AUDIO_CHANNEL_LAYOUT_SEVEN_POINT_ONE_WIDE, 8"
  })
  @DisplayName(
      "Should refuse the job as an invalid specification when AAC names a layout of more than 6 channels")
  void shouldRefuseTheJobAsAnInvalidSpecificationWhenAacNamesALayoutOfMoreThan6Channels(
      AudioChannelLayout layout, int channels) throws Exception {
    assertRefusedAsAnInvalidSpecification(layoutJob(layout, channels));
  }

  private void assertRefusedAsAnInvalidSpecification(VariantJob job) throws Exception {
    var launcher = ScriptedProcessLauncher.writing(ENCODED_RECORDING);

    var connection = start(launcher, ffprobeAnswering(AC3_SIDE_SURROUND), job);

    assertFailedAs(connection, JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
    assertThat(launcher.hasLaunchedAny()).isFalse();
  }

  private ScriptedWorkerRuntime.Connection start(
      ScriptedProcessLauncher launcher, FfprobeExecutor ffprobe, VariantJob job) throws Exception {
    var worker =
        workerBuilder(tempDir).runtime(runtime).engine(engine(launcher)).ffprobe(ffprobe).build();
    worker.start("localhost", 1);
    closeAfterTest(worker);
    var connection = runtime.connection();
    startVariant(connection, job);
    return connection;
  }

  private void closeAfterTest(TranscodeWorker worker) {
    workers.add(worker);
  }

  private FfprobeExecutor ffprobeAnswering(String output) {
    return new FfprobeExecutor(
        new ObjectMapper(),
        command -> {
          probeCommands.add(command);
          return ScriptedProcess.builder().output(output.getBytes(StandardCharsets.UTF_8)).build();
        });
  }

  private static VariantJob layoutJob(AudioChannelLayout layout, int channels) {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
        .setCodec("aac")
        .setChannels(channels)
        .setBitrateBitsPerSecond(384_000)
        .setChannelLayout(layout);
    return job.build();
  }

  private static void assertFailedAs(
      ScriptedWorkerRuntime.Connection connection, JobAttemptFailure failure) {
    assertThat(eventsOf(connection))
        .containsExactly(EstablishWorkerSessionRequest.EventCase.JOB_ATTEMPT_FAILED);
    assertThat(
            connection.events().stream()
                .filter(EstablishWorkerSessionRequest::hasJobAttemptFailed)
                .findFirst()
                .map(event -> event.getJobAttemptFailed().getFailure()))
        .isEqualTo(Optional.of(failure));
  }

  private static List<EstablishWorkerSessionRequest.EventCase> eventsOf(
      ScriptedWorkerRuntime.Connection connection) {
    return connection.events().stream()
        .map(EstablishWorkerSessionRequest::getEventCase)
        .filter(
            eventCase ->
                eventCase != EstablishWorkerSessionRequest.EventCase.REGISTRATION
                    && eventCase != EstablishWorkerSessionRequest.EventCase.PROBE_CAPACITY_CHANGED)
        .toList();
  }
}
