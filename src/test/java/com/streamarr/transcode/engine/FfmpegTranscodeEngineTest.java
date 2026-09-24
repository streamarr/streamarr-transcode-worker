package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.fixtures.FfmpegMuxerHelpFixtures.FRAGMENTED_MP4_MUXER_HELP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("FFmpeg Transcode Engine Tests")
class FfmpegTranscodeEngineTest {

  private static final Duration OUTCOME_LIMIT = Duration.ofSeconds(10);

  private ScriptedProcessLauncher launcher;
  private FfmpegTranscodeEngine executor;

  @BeforeEach
  void setUp() {
    var hwCapability =
        HardwareEncodingCapability.builder()
            .available(true)
            .encoders(Set.of("h264_nvenc", "av1_nvenc"))
            .accelerator("cuda")
            .build();

    var capabilityService = createCapabilityService(true, hwCapability);

    executor = engineLaunching(runningProcess(), capabilityService);
  }

  private TranscodeRequest createRequest(TranscodeMode mode, String codecFamily) {
    return createRequest(mode, codecFamily, null);
  }

  private TranscodeRequest createRequest(
      TranscodeMode mode, String codecFamily, String variantLabel) {
    return requestBuilder(mode, codecFamily).variantLabel(variantLabel).build();
  }

  private static TranscodeRequest.TranscodeRequestBuilder requestBuilder(
      TranscodeMode mode, String codecFamily) {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(Path.of("/media/movie.mkv"))
        .targetSegmentDuration(6)
        .framerate(23.976)
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(mode)
                .videoCodecFamily(codecFamily)
                .audioDecision(
                    AudioDecision.builder()
                        .mode(AudioMode.TRANSCODE)
                        .codec("aac")
                        .channels(2)
                        .bitrate(128_000L)
                        .build())
                .subtitleDecision(SubtitleDecisions.EXCLUDED)
                .needsKeyframeAlignment(mode != TranscodeMode.FULL_TRANSCODE)
                .build())
        .width(1920)
        .height(1080)
        .bitrate(5_000_000L);
  }

  @Test
  @DisplayName(
      "Should deliver the job attempt's media segments from its own FFmpeg when starting a"
          + " producer")
  void shouldDeliverTheJobAttemptsMediaSegmentsFromItsOwnFfmpegWhenStartingAProducer() {
    var recording = recording("01-encode-cfr-seek30.fmp4");
    var process = ScriptedProcess.builder().output(bytesOf(recording.file())).build();
    executor = engineLaunching(process, createCapabilityService(true, noHardware()));
    var request =
        requestBuilder(TranscodeMode.FULL_TRANSCODE, "h264")
            .attemptId(UUID.randomUUID())
            .startSequenceNumber(recording.startSequenceNumber())
            .build();
    var sink = new RecordingSegmentSink();

    var producer = executor.startProducer(request, sink);

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(launcher.command(request.attemptId()))
        .startsWith("ffmpeg")
        .containsSubsequence("-ss", "24", "-i", "/media/movie.mkv")
        .endsWith("pipe:1");
    assertThat(sink.acceptedNames())
        .containsExactly(
            "init.mp4",
            "segment5.m4s",
            "segment6.m4s",
            "segment7.m4s",
            "segment8.m4s",
            "segment9.m4s",
            "segment10.m4s");
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TranscodeMode.class,
      names = {"VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName(
      "Should fail the attempt as a short video sample when it encodes video and its output holds a"
          + " sample shorter than half a frame")
  void shouldFailTheAttemptAsAShortVideoSampleWhenItEncodesVideoAndItsOutputHoldsOne(
      TranscodeMode mode) {
    var producer = startProducerOverAOneTickVideoSample(mode);

    assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .asInstanceOf(InstanceOfAssertFactories.type(Failed.class))
        .extracting(Failed::reason)
        .isEqualTo(ProducerFailure.SHORT_VIDEO_SAMPLE);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName(
      "Should complete the attempt when it copies the video and its output holds a sample shorter"
          + " than half a frame")
  void shouldCompleteTheAttemptWhenItCopiesTheVideoAndItsOutputHoldsAShortSample(
      TranscodeMode mode) {
    var producer = startProducerOverAOneTickVideoSample(mode);

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
  }

  // FFmpeg writes three 1 s segments of 23.976 fps video whose second holds a 1-tick sample.
  private Producer startProducerOverAOneTickVideoSample(TranscodeMode mode) {
    var output =
        IsoBoxes.oneSecondKeyframeFragments(
            List.of(List.of(1001, 1001), List.of(1001, 1, 1001), List.of(1001)));
    executor =
        engineLaunching(
            ScriptedProcess.builder().output(output).build(),
            createCapabilityService(true, noHardware()));
    var request =
        requestBuilder(mode, "h264").targetSegmentDuration(1).framerate(24_000.0 / 1001).build();
    return executor.startProducer(request, new RecordingSegmentSink());
  }

  @Test
  @DisplayName("Should refuse to start a producer without launching FFmpeg when it is unavailable")
  void shouldRefuseToStartAProducerWithoutLaunchingFfmpegWhenItIsUnavailable() {
    executor = engineLaunching(runningProcess(), createCapabilityService(false, noHardware()));
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "h264");

    var thrown = catchThrowable(() -> executor.startProducer(request, new RecordingSegmentSink()));

    assertThat(thrown)
        .isInstanceOf(TranscodeException.class)
        .hasMessageStartingWith("FFmpeg is unavailable");
    assertThat(launcher.hasLaunchedAny()).isFalse();
  }

  @Test
  @DisplayName("Should launch FFmpeg with the hardware encoder when one is available")
  void shouldLaunchFfmpegWithTheHardwareEncoderWhenOneIsAvailable() {
    var hardware =
        HardwareEncodingCapability.builder()
            .available(true)
            .encoders(Set.of("h264_nvenc"))
            .accelerator("cuda")
            .build();
    executor = engineLaunching(runningProcess(), createCapabilityService(true, hardware));
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "h264");

    var producer = executor.startProducer(request, new RecordingSegmentSink());

    producer.stop();
    assertThat(launcher.command(request.attemptId())).containsSubsequence("-c:v", "h264_nvenc");
  }

  @Test
  @DisplayName(
      "Should launch FFmpeg with the software encoder when no hardware encoder is available")
  void shouldLaunchFfmpegWithTheSoftwareEncoderWhenNoHardwareEncoderIsAvailable() {
    executor = engineLaunching(runningProcess(), createCapabilityService(true, noHardware()));
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "av1");

    var producer = executor.startProducer(request, new RecordingSegmentSink());

    producer.stop();
    assertThat(launcher.command(request.attemptId())).containsSubsequence("-c:v", "libsvtav1");
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName("Should launch FFmpeg copying the video when the mode keeps the video stream")
  void shouldLaunchFfmpegCopyingTheVideoWhenTheModeKeepsTheVideoStream(TranscodeMode mode) {
    executor = engineLaunching(runningProcess(), createCapabilityService(true, noHardware()));
    var request = createRequest(mode, "h264");

    var producer = executor.startProducer(request, new RecordingSegmentSink());

    producer.stop();
    assertThat(launcher.command(request.attemptId())).containsSubsequence("-c:v", "copy");
  }

  @Test
  @DisplayName("Should report healthy when FFmpeg available")
  void shouldReportHealthyWhenFfmpegAvailable() {
    assertThat(executor.isHealthy()).isTrue();
  }

  @Test
  @DisplayName("Should report unhealthy when FFmpeg unavailable")
  void shouldReportUnhealthyWhenFfmpegUnavailable() {
    var capabilityService =
        createCapabilityService(
            false,
            HardwareEncodingCapability.builder().available(false).encoders(Set.of()).build());

    executor = engineLaunching(runningProcess(), capabilityService);

    assertThat(executor.isHealthy()).isFalse();
  }

  @Test
  @DisplayName("Should reject producer start when FFmpeg cannot fragment mp4 output")
  void shouldRejectProducerStartWhenFfmpegCannotFragmentMp4Output() {
    var capabilityService =
        new TranscodeCapabilityService(
            "ffmpeg",
            command -> {
              if (String.join(" ", command).contains("muxer=mp4")) {
                return new FakeProcess("Muxer mp4 [MP4 (MPEG-4 Part 14)]:", 0);
              }

              return new FakeProcess("ffmpeg version 4.4.2", 0);
            });
    capabilityService.detectCapabilities();
    executor = engineLaunching(runningProcess(), capabilityService);
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "av1");

    var thrown = catchThrowable(() -> executor.startProducer(request, new RecordingSegmentSink()));

    assertThat(launcher.hasLaunchedAny()).isFalse();
    assertThat(thrown)
        .isInstanceOf(TranscodeException.class)
        .hasMessage(
            "FFmpeg is unavailable: Missing mp4 muxer options: "
                + "-frag_duration, cmaf, delay_moov, skip_trailer, frag_keyframe, frag_discont");
  }

  private FfmpegTranscodeEngine engineLaunching(
      ScriptedProcess process, TranscodeCapabilityService capabilities) {
    launcher = new ScriptedProcessLauncher(_ -> process);
    return FfmpegTranscodeEngine.builder()
        .commandBuilder(new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1)))
        .capabilityService(capabilities)
        .launcher(launcher)
        .build();
  }

  private static ScriptedProcess runningProcess() {
    return ScriptedProcessLauncher.runningProcessBuilder().build();
  }

  private static HardwareEncodingCapability noHardware() {
    return HardwareEncodingCapability.builder().available(false).encoders(Set.of()).build();
  }

  private TranscodeCapabilityService createCapabilityService(
      boolean available, HardwareEncodingCapability hwCapability) {
    var service =
        new TranscodeCapabilityService(
            "ffmpeg", command -> new FakeProcess("ffmpeg version 7.0", available ? 0 : 1));
    if (!available) {
      return service;
    }

    var outputs =
        Map.of(
            "ffmpeg", (Process) new FakeProcess("ffmpeg version 7.0", 0),
            "mp4", (Process) new FakeProcess(FRAGMENTED_MP4_MUXER_HELP, 0),
            "hwaccels",
                (Process)
                    new FakeProcess(
                        hwCapability.available()
                            ? "Hardware acceleration methods:\ncuda\n"
                            : "Hardware acceleration methods:\n",
                        0),
            "encoders", (Process) new FakeProcess(buildEncoderOutput(hwCapability.encoders()), 0));

    var testService =
        new TranscodeCapabilityService(
            "ffmpeg",
            command -> {
              var cmdStr = String.join(" ", command);
              if (cmdStr.contains("-version")) {
                return outputs.get("ffmpeg");
              }

              if (cmdStr.contains("muxer=mp4")) {
                return outputs.get("mp4");
              }

              if (cmdStr.contains("-hwaccels")) {
                return outputs.get("hwaccels");
              }

              if (cmdStr.contains("-encoders")) {
                return outputs.get("encoders");
              }

              // The trial encode that proves a listed hardware encoder works.
              if (cmdStr.contains("-f null")) {
                return new FakeProcess("", 0);
              }

              return new FakeProcess("", 1);
            });
    testService.detectCapabilities();
    return testService;
  }

  private String buildEncoderOutput(Set<String> encoders) {
    var sb = new StringBuilder();
    for (var encoder : encoders) {
      sb.append(" V....D ")
          .append(encoder)
          .append("           ")
          .append(encoder)
          .append(" encoder\n");
    }

    return sb.toString();
  }
}
