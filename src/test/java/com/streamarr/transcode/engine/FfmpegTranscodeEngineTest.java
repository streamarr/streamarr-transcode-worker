package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.fixtures.FfmpegMuxerHelpFixtures.FRAGMENTED_MP4_MUXER_HELP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.engine.SourceEnd.EndedEarly;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.fixtures.Mp4Samples;
import com.streamarr.transcode.fixtures.Mp4Samples.Sample;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("FFmpeg Transcode Engine Tests")
class FfmpegTranscodeEngineTest {

  // A scripted FFmpeg writes no framecrc of the source's streams.
  private static final SourceEnd NOTHING_READ_FROM_THE_SOURCE =
      new SourceEnd(List.of(), Optional.empty());

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

  @Test
  @DisplayName("Should refuse to build an engine without an encoder stall timeout")
  void shouldRefuseToBuildAnEngineWithoutAnEncoderStallTimeout() {
    var builder =
        FfmpegTranscodeEngine.builder()
            .commandBuilder(new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1)))
            .capabilityService(createCapabilityService(true, noHardware()))
            .launcher(new ScriptedProcessLauncher(_ -> runningProcess()));

    assertThatNullPointerException()
        .isThrownBy(builder::build)
        .withMessageContaining("encoderStallTimeout");
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
    return requestBuilder(mode, codecFamily, stereoAac().build());
  }

  private static TranscodeRequest.TranscodeRequestBuilder requestBuilder(
      TranscodeMode mode, String codecFamily, AudioDecision audio) {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(Path.of("/media/movie.mkv"))
        .targetSegmentDuration(6)
        .outputFrameRate(Optional.of(new OutputFrameRate(24_000, 1001)))
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(mode)
                .videoCodecFamily(codecFamily)
                .videoDecision(VideoDecisionFixtures.forMode(mode))
                .audioDecision(audio)
                .subtitleDecision(SubtitleDecisions.EXCLUDED)
                .build())
        .encoderPreset(EncoderPreset.FASTER)
        .width(1920)
        .height(1080)
        .bitrate(5_000_000L);
  }

  // An encode of the source's first audio stream to stereo AAC, which names no stream.
  private static AudioDecision.AudioDecisionBuilder stereoAac() {
    return AudioDecision.builder()
        .mode(AudioMode.TRANSCODE)
        .codec("aac")
        .channels(2)
        .bitrate(128_000L);
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

    var producer = executor.startProducer(request, sink, SegmentMemoryBudget.forSlots(1));

    assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .isEqualTo(new Completed(0, NOTHING_READ_FROM_THE_SOURCE));
    assertThat(launcher.command(request.attemptId()))
        .startsWith("ffmpeg")
        .containsSubsequence("-ss", "24", "-i", "/media/movie.mkv", "pipe:1");
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
      "Should fail the attempt as a short video sample when it encodes video and its output holds"
          + " one")
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
      "Should complete the attempt when it copies the video and its output holds a short sample")
  void shouldCompleteTheAttemptWhenItCopiesTheVideoAndItsOutputHoldsAShortSample(
      TranscodeMode mode) {
    var producer = startProducerOverAOneTickVideoSample(mode);

    assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .isEqualTo(new Completed(0, NOTHING_READ_FROM_THE_SOURCE));
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
        requestBuilder(mode, "h264")
            .targetSegmentDuration(1)
            .outputFrameRate(Optional.of(new OutputFrameRate(24_000, 1001)))
            .build();
    return executor.startProducer(
        request, new RecordingSegmentSink(), SegmentMemoryBudget.forSlots(1));
  }

  @Test
  @DisplayName("Should refuse to start a producer without launching FFmpeg when it is unavailable")
  void shouldRefuseToStartAProducerWithoutLaunchingFfmpegWhenItIsUnavailable() {
    executor = engineLaunching(runningProcess(), createCapabilityService(false, noHardware()));
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "h264");

    var thrown =
        catchThrowable(
            () ->
                executor.startProducer(
                    request, new RecordingSegmentSink(), SegmentMemoryBudget.forSlots(1)));

    assertThat(thrown)
        .isInstanceOf(TranscodeException.class)
        .hasMessageStartingWith("FFmpeg is unavailable");
    assertThat(launcher.hasLaunchedAny()).isFalse();
  }

  @Test
  @DisplayName(
      "Should refuse to start a producer on an encoder backend resolved earlier without launching"
          + " FFmpeg when it is unavailable")
  void
      shouldRefuseToStartAProducerOnAnEncoderBackendResolvedEarlierWithoutLaunchingFfmpegWhenItIsUnavailable() {
    executor =
        engineLaunching(
            ScriptedProcess.builder().output(new byte[0]).build(),
            createCapabilityService(false, noHardware()));
    var request = createRequest(TranscodeMode.REMUX, "h264");

    var thrown =
        catchThrowable(
            () ->
                executor.startProducer(
                    request,
                    Optional.empty(),
                    new RecordingSegmentSink(),
                    SegmentMemoryBudget.forSlots(1)));

    assertThat(thrown)
        .isInstanceOf(TranscodeException.class)
        .hasMessageStartingWith("FFmpeg is unavailable");
    assertThat(launcher.hasLaunchedAny()).isFalse();
  }

  @Test
  @DisplayName(
      "Should have an encoder waiting for its recheck only after a hardware encoder is withdrawn")
  void shouldHaveAnEncoderWaitingForItsRecheckOnlyAfterAHardwareEncoderIsWithdrawn() {
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "h264");
    var nvenc = executor.encoderBackendOf(request).orElseThrow();
    var waitingBefore = executor.hasWithdrawnEncoders();

    executor.withdraw(nvenc);

    assertThat(waitingBefore).isFalse();
    assertThat(executor.hasWithdrawnEncoders()).isTrue();
  }

  @Test
  @DisplayName(
      "Should report no change when a hardware encoder that is already withdrawn fails again")
  void shouldReportNoChangeWhenAHardwareEncoderThatIsAlreadyWithdrawnFailsAgain() {
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "h264");
    var nvenc = executor.encoderBackendOf(request).orElseThrow();
    var changedFirst = executor.withdraw(nvenc);

    var changedAgain = executor.withdraw(nvenc);

    assertThat(changedFirst).isTrue();
    assertThat(changedAgain).isFalse();
    assertThat(executor.encoderBackendOf(request)).map(EncoderBackend::encoder).contains("libx264");
  }

  // FFmpeg delivers segment 0 of three 1 s segments. The source's audio, stream 1, ends with a
  // packet that starts 8 ms before segment 0's end and ends 13 ms after it. The producer places a
  // copied packet whole by its start, and an encode converts each sample over its whole duration.
  @ParameterizedTest(name = "{0}")
  @MethodSource("audioWhoseLastPacketCrossesTheLastDeliveredSegmentsEnd")
  @DisplayName(
      "Should report the audio as having media after the last delivered segment only when the"
          + " attempt encodes it, when the audio's last packet crosses that segment's end")
  void shouldReportTheAudioAsHavingMediaAfterTheLastDeliveredSegmentOnlyWhenTheAttemptEncodesIt(
      TranscodeMode mode, AudioMode audioMode, List<Integer> streamsWithMediaAfterSegment0) {
    var process =
        ScriptedProcess.builder()
            .output(IsoBoxes.oneSecondKeyframeFragments(List.of(List.of(1001))))
            .stderr(
                """
                Stream mapping:
                  Stream #0:0 -> #1:0 (copy)
                  Stream #0:1 -> #1:1 (copy)
                #tb 0: 1/1000
                #tb 1: 1/48000
                0, 0, 0, 40, 1000, 0x0a1b2c3d
                0, 960, 960, 40, 1000, 0x0a1b2c3d
                1, 47616, 47616, 1024, 300, 0x0a1b2c3d
                """)
            .build();
    executor = engineLaunching(process, createCapabilityService(true, noHardware()));
    var request =
        requestBuilder(mode, "h264", stereoAac().mode(audioMode).build())
            .targetSegmentDuration(1)
            .mediaSegmentCount(3)
            .build();

    var producer =
        executor.startProducer(
            request, new RecordingSegmentSink(), SegmentMemoryBudget.forSlots(1));

    assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .asInstanceOf(InstanceOfAssertFactories.type(Completed.class))
        .extracting(completed -> completed.sourceEnd().endedEarly())
        .isEqualTo(Optional.of(new EndedEarly(0, streamsWithMediaAfterSegment0)));
  }

  static Stream<Arguments> audioWhoseLastPacketCrossesTheLastDeliveredSegmentsEnd() {
    return Stream.of(
        Arguments.of(TranscodeMode.REMUX, AudioMode.COPY, List.of()),
        Arguments.of(TranscodeMode.AUDIO_TRANSCODE, AudioMode.TRANSCODE, List.of(1)));
  }

  @Test
  @DisplayName(
      "Should complete the attempt when the job names no audio stream and FFmpeg's output declares"
          + " no sound track")
  void shouldCompleteTheAttemptWhenTheJobNamesNoAudioStreamAndTheOutputDeclaresNoSoundTrack() {
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(),
            IsoBoxes.moov(IsoBoxes.Track.video().build()),
            IsoBoxes.moof(oneSecondOfVideoFrom(0)),
            IsoBoxes.mdat(1));
    executor =
        engineLaunching(
            ScriptedProcess.builder().output(output).build(),
            createCapabilityService(true, noHardware()));
    var request = requestBuilder(TranscodeMode.REMUX, "h264").targetSegmentDuration(1).build();
    var sink = new RecordingSegmentSink();

    var producer = executor.startProducer(request, sink, SegmentMemoryBudget.forSlots(1));

    assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .isEqualTo(new Completed(0, NOTHING_READ_FROM_THE_SOURCE));
    assertThat(sink.acceptedNames()).containsExactly("init.mp4", "segment0.m4s");
  }

  // FFmpeg writes two 1 s segments whose audio runs on to 2 s; the job advertises 1.5 s.
  @Test
  @DisplayName(
      "Should deliver no audio that starts at or after the advertised end when the job pads its"
          + " audio with silence up to that end")
  void shouldDeliverNoAudioThatStartsAtOrAfterTheAdvertisedEndWhenTheJobPadsItsAudioWithSilence() {
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(),
            IsoBoxes.videoAndAudioMoov(),
            oneSecondOfVideoWithAudio(0, 24_000, 24_000),
            oneSecondOfVideoWithAudio(1, 12_000, 12_000, 12_000, 12_000));
    executor =
        engineLaunching(
            ScriptedProcess.builder().output(output).build(),
            createCapabilityService(true, noHardware()));
    var paddedAudio = stereoAac().padsWithSilenceToAdvertisedEnd(true).build();
    var request =
        requestBuilder(TranscodeMode.AUDIO_TRANSCODE, "h264", paddedAudio)
            .targetSegmentDuration(1)
            .advertisedEnd(Optional.of(Duration.ofMillis(1_500)))
            .build();
    var sink = new RecordingSegmentSink();

    var producer = executor.startProducer(request, sink, SegmentMemoryBudget.forSlots(1));

    assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .isEqualTo(new Completed(0, NOTHING_READ_FROM_THE_SOURCE));
    var lastSegment =
        Mp4Samples.read(
            IsoBoxes.concat(sink.contentOf("init.mp4"), sink.contentOf("segment1.m4s")));
    assertThat(lastSegment.samplesOf(lastSegment.audioTrack()))
        .extracting(Sample::presentationTime)
        .as("segment 1's audio, in 48 kHz ticks, up to the advertised end at 72,000")
        .containsExactly(48_000L, 60_000L);
  }

  // A fragment of the second's video from a keyframe and the audio samples it carries from the
  // second's start.
  private static byte[] oneSecondOfVideoWithAudio(int second, Integer... audioDurations) {
    return IsoBoxes.concat(
        IsoBoxes.moof(
            oneSecondOfVideoFrom(second),
            IsoBoxes.audioTraf()
                .baseMediaDecodeTime(second * 48_000L)
                .sampleDurations(List.of(audioDurations))
                .build()),
        IsoBoxes.mdat(1 + audioDurations.length));
  }

  private static IsoBoxes.TrackFragment oneSecondOfVideoFrom(int second) {
    return IsoBoxes.videoTraf()
        .baseMediaDecodeTime(second * 24_000L)
        .sampleDurations(List.of(24_000))
        .firstSampleFlags(IsoBoxes.SYNC_SAMPLE_FLAGS)
        .build();
  }

  @Test
  @DisplayName(
      "Should refuse to start a producer without launching FFmpeg when no encoder of the codec"
          + " family is advertised")
  void
      shouldRefuseToStartAProducerWithoutLaunchingFfmpegWhenNoEncoderOfTheCodecFamilyIsAdvertised() {
    executor = engineLaunching(runningProcess(), createCapabilityService(true, noHardware()));
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "vp9");

    var thrown =
        catchThrowable(
            () ->
                executor.startProducer(
                    request, new RecordingSegmentSink(), SegmentMemoryBudget.forSlots(1)));

    assertThat(thrown)
        .isInstanceOf(UnadvertisedCodecFamilyException.class)
        .hasMessage("The worker advertises no encoder for codec family vp9");
    assertThat(launcher.hasLaunchedAny()).isFalse();
  }

  @Test
  @DisplayName(
      "Should launch FFmpeg with the software encoder when the available hardware encoder does not"
          + " force the job's codec profile and level")
  void
      shouldLaunchFfmpegWithTheSoftwareEncoderWhenTheAvailableHardwareEncoderDoesNotForceTheJobsCodecProfileAndLevel() {
    var hardware =
        HardwareEncodingCapability.builder()
            .available(true)
            .encoders(Set.of("h264_qsv"))
            .accelerator("qsv")
            .build();
    executor = engineLaunching(runningProcess(), createCapabilityService(true, hardware));
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "h264");

    var producer =
        executor.startProducer(
            request, new RecordingSegmentSink(), SegmentMemoryBudget.forSlots(1));

    producer.stop();
    assertThat(launcher.command(request.attemptId())).containsSubsequence("-c:v", "libx264");
  }

  @Test
  @DisplayName(
      "Should launch FFmpeg with the software encoder when no hardware encoder is available")
  void shouldLaunchFfmpegWithTheSoftwareEncoderWhenNoHardwareEncoderIsAvailable() {
    executor = engineLaunching(runningProcess(), createCapabilityService(true, noHardware()));
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "av1");

    var producer =
        executor.startProducer(
            request, new RecordingSegmentSink(), SegmentMemoryBudget.forSlots(1));

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

    var producer =
        executor.startProducer(
            request, new RecordingSegmentSink(), SegmentMemoryBudget.forSlots(1));

    producer.stop();
    assertThat(launcher.command(request.attemptId())).containsSubsequence("-c:v", "copy");
  }

  @Test
  @DisplayName("Should reject producer start when FFmpeg cannot fragment mp4 output")
  void shouldRejectProducerStartWhenFfmpegCannotFragmentMp4Output() {
    var capabilityService =
        new TranscodeCapabilityService(
            "ffmpeg",
            command -> {
              if (String.join(" ", command.command()).contains("muxer=mp4")) {
                return new FakeProcess("Muxer mp4 [MP4 (MPEG-4 Part 14)]:", 0);
              }

              return new FakeProcess("ffmpeg version 4.4.2", 0);
            });
    capabilityService.detectCapabilities();
    executor = engineLaunching(runningProcess(), capabilityService);
    var request = createRequest(TranscodeMode.FULL_TRANSCODE, "av1");

    var thrown =
        catchThrowable(
            () ->
                executor.startProducer(
                    request, new RecordingSegmentSink(), SegmentMemoryBudget.forSlots(1)));

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
        .encoderStallTimeout(FfmpegTranscodeEngine.DEFAULT_ENCODER_STALL_TIMEOUT)
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
              var cmdStr = String.join(" ", command.command());
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
