package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.VideoDynamicRange.CompatibleBase;
import com.streamarr.transcode.engine.VideoDynamicRange.DolbyVisionRpuPlacement;
import com.streamarr.transcode.engine.VideoDynamicRange.Presence;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("FFmpeg Command Builder Tests")
class FfmpegCommandBuilderTest {

  private static final AudioDecision NO_AUDIO =
      AudioDecision.builder().mode(AudioMode.NONE).codec(null).channels(0).bitrate(0L).build();
  private static final String BT709_LIMITED_SCALE_OPTIONS =
      ":out_color_matrix=bt709:out_range=tv:out_primaries=bt709:out_transfer=bt709";

  private final FfmpegCommandBuilder builder =
      new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1));

  private List<String> command(TranscodeRequest request, String videoEncoder) {
    return singleCommandOf(builder.buildPipeline(job(request, videoEncoder)));
  }

  private static List<String> singleCommandOf(FfmpegPipeline pipeline) {
    return assertThat(pipeline)
        .asInstanceOf(InstanceOfAssertFactories.type(FfmpegPipeline.SingleProcess.class))
        .actual()
        .command();
  }

  private static TranscodeJob job(TranscodeRequest request, String videoEncoder) {
    return TranscodeJob.builder().request(request).videoEncoder(videoEncoder).build();
  }

  private static TranscodeRequest.TranscodeRequestBuilder request(TranscodeMode mode) {
    return request(decision(mode).build());
  }

  private static TranscodeRequest.TranscodeRequestBuilder request(TranscodeDecision decision) {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(Path.of("/media/movie.mkv"))
        .targetSegmentDuration(6)
        .outputFrameRate(Optional.of(new OutputFrameRate(24_000, 1001)))
        .mediaSegmentCount(11)
        .transcodeDecision(decision)
        .encoderPreset(EncoderPreset.FASTER)
        .width(1920)
        .height(1080)
        .bitrate(5_000_000L);
  }

  private static TranscodeDecision.TranscodeDecisionBuilder decision(TranscodeMode mode) {
    var audio =
        switch (mode) {
          case REMUX, VIDEO_TRANSCODE -> copiedAudio("aac");
          case AUDIO_TRANSCODE, FULL_TRANSCODE ->
              AudioDecision.builder()
                  .mode(AudioMode.TRANSCODE)
                  .codec("aac")
                  .channels(2)
                  .bitrate(128_000L)
                  .build();
        };
    return TranscodeDecision.builder()
        .transcodeMode(mode)
        .videoCodecFamily("h264")
        .videoDecision(VideoDecisionFixtures.forMode(mode))
        .audioDecision(audio)
        .subtitleDecision(SubtitleDecisions.EXCLUDED);
  }

  private static AudioDecision copiedAudio(String codec) {
    return AudioDecision.builder()
        .mode(AudioMode.COPY)
        .codec(codec)
        .channels(2)
        .bitrate(0L)
        .build();
  }

  // FFmpeg applies the last -b:a it reads, so a command must carry exactly one.
  private static String audioBitrate(List<String> cmd) {
    assertThat(cmd).as("the command's -b:a options").containsOnlyOnce("-b:a");
    return cmd.get(cmd.indexOf("-b:a") + 1);
  }

  @Test
  @DisplayName("Should use copy codecs when mode is remux")
  void shouldUseCopyCodecsWhenModeIsRemux() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd)
        .isNotEmpty()
        .containsSubsequence("-c:v", "copy")
        .containsSubsequence("-c:a", "copy")
        .doesNotContain("-vf")
        .doesNotContain("-b:v", "-maxrate", "-bufsize");
  }

  @ParameterizedTest(name = "{0} is {1}")
  @MethodSource("presetNames")
  @DisplayName("Should pass the preset name when the encoder is libx264")
  void shouldPassPresetNameWhenEncoderIsLibx264(EncoderPreset preset, String presetName) {
    var request = request(TranscodeMode.FULL_TRANSCODE).encoderPreset(preset).build();

    var cmd = command(request, "libx264");

    assertThat(cmd)
        .containsSequence("-c:v", "libx264", "-preset", presetName)
        .containsOnlyOnce("-preset");
  }

  @ParameterizedTest(name = "{0} is {1}")
  @MethodSource("presetNames")
  @DisplayName("Should pass the preset name when the encoder is libx265")
  void shouldPassPresetNameWhenEncoderIsLibx265(EncoderPreset preset, String presetName) {
    var request = request(TranscodeMode.FULL_TRANSCODE).encoderPreset(preset).build();

    var cmd = command(request, "libx265");

    assertThat(cmd)
        .containsSequence("-c:v", "libx265", "-preset", presetName)
        .containsOnlyOnce("-preset");
  }

  @ParameterizedTest(name = "{0} is preset {1}")
  @CsvSource({
    "VERYSLOW, 5",
    "SLOWER, 6",
    "SLOW, 7",
    "MEDIUM, 8",
    "FAST, 9",
    "FASTER, 10",
    "VERYFAST, 11",
    "SUPERFAST, 12"
  })
  @DisplayName("Should pass the scale number when the encoder is libsvtav1")
  void shouldPassScaleNumberWhenEncoderIsLibsvtav1(EncoderPreset preset, String svtAv1Preset) {
    var request = request(TranscodeMode.FULL_TRANSCODE).encoderPreset(preset).build();

    var cmd = command(request, "libsvtav1");

    assertThat(cmd)
        .containsSequence("-c:v", "libsvtav1", "-preset", svtAv1Preset)
        .containsOnlyOnce("-preset");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "h264_nvenc",
        "hevc_nvenc",
        "av1_nvenc",
        "h264_qsv",
        "hevc_qsv",
        "av1_qsv",
        "h264_amf",
        "hevc_amf",
        "av1_amf",
        "h264_rkmpp",
        "hevc_rkmpp",
        "h264_videotoolbox",
        "hevc_videotoolbox",
        "h264_vaapi"
      })
  @DisplayName("Should pass no preset when the encoder is hardware")
  void shouldPassNoPresetWhenEncoderIsHardware(String hardwareEncoder) {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), hardwareEncoder);

    assertThat(cmd).contains(hardwareEncoder).doesNotContain("-preset");
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName("Should pass no preset when the job copies the video")
  void shouldPassNoPresetWhenJobCopiesTheVideo(TranscodeMode mode) {
    var cmd = command(request(mode).build(), "copy");

    assertThat(cmd).containsSequence("-c:v", "copy").doesNotContain("-preset");
  }

  // libx264 and libx265 take the scale's own names.
  static Stream<Arguments> presetNames() {
    return Stream.of(
        Arguments.of(EncoderPreset.SUPERFAST, "superfast"),
        Arguments.of(EncoderPreset.VERYFAST, "veryfast"),
        Arguments.of(EncoderPreset.FASTER, "faster"),
        Arguments.of(EncoderPreset.FAST, "fast"),
        Arguments.of(EncoderPreset.MEDIUM, "medium"),
        Arguments.of(EncoderPreset.SLOW, "slow"),
        Arguments.of(EncoderPreset.SLOWER, "slower"),
        Arguments.of(EncoderPreset.VERYSLOW, "veryslow"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fullTranscodeExpectedFlags")
  @DisplayName("Should include expected flags when mode is full transcode")
  void shouldIncludeExpectedFlagsWhenModeIsFullTranscode(String scenario, String... expectedFlags) {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "libx264");

    assertThat(cmd).contains(expectedFlags);
  }

  static Stream<Arguments> fullTranscodeExpectedFlags() {
    return Stream.of(
        Arguments.of(
            "scale filter",
            new String[] {
              "-vf", "scale=-2:1080" + BT709_LIMITED_SCALE_OPTIONS + ",format=yuv420p"
            }),
        Arguments.of("forced IDR", new String[] {"-forced-idr", "1"}),
        Arguments.of("audio downmix to stereo", new String[] {"-ac", "2"}));
  }

  @Test
  @DisplayName("Should include bitrate control when mode is full transcode")
  void shouldIncludeBitrateControlWhenModeIsFullTranscode() {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "libx264");

    assertThat(cmd)
        .contains("-b:v", "5000000")
        .contains("-maxrate", "5000000")
        .contains("-bufsize", "10000000");
  }

  @Test
  @DisplayName("Should use variant dimensions when variant differs from source")
  void shouldUseVariantDimensionsWhenVariantDiffersFromSource() {
    var cmd =
        command(
            request(TranscodeMode.FULL_TRANSCODE)
                .width(1280)
                .height(720)
                .bitrate(3_000_000L)
                .build(),
            "libx264");

    assertThat(cmd)
        .contains("-vf", "scale=-2:720" + BT709_LIMITED_SCALE_OPTIONS + ",format=yuv420p")
        .contains("-b:v", "3000000")
        .contains("-bufsize", "6000000");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "libx264",
        "libx265",
        "libsvtav1",
        "h264_nvenc",
        "hevc_nvenc",
        "av1_nvenc",
        "h264_amf",
        "hevc_amf",
        "av1_amf",
        "h264_rkmpp",
        "hevc_rkmpp",
        "unlisted_encoder"
      })
  @DisplayName(
      "Should end the video filter chain in 8-bit yuv420p when any encoder other than QSV or VAAPI"
          + " runs")
  void shouldEndTheVideoFilterChainInEightBitYuv420pWhenAnyEncoderOtherThanQsvOrVaapiRuns(
      String encoder) {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).height(720).build(), encoder);

    assertThat(videoFilter(cmd))
        .isEqualTo("scale=-2:720" + BT709_LIMITED_SCALE_OPTIONS + ",format=yuv420p");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {"h264_qsv", "hevc_qsv", "av1_qsv", "h264_vaapi", "hevc_vaapi", "av1_vaapi"})
  @DisplayName("Should end the video filter chain in nv12 when a QSV or VAAPI encoder runs")
  void shouldEndTheVideoFilterChainInNv12WhenAQsvOrVaapiEncoderRuns(String encoder) {
    var cmd = command(request(TranscodeMode.VIDEO_TRANSCODE).height(720).build(), encoder);

    assertThat(videoFilter(cmd))
        .isEqualTo("scale=-2:720" + BT709_LIMITED_SCALE_OPTIONS + ",format=nv12");
  }

  @ParameterizedTest(name = "{0} to {1}, {2}")
  @MethodSource("toneMappedConversions")
  @DisplayName(
      "Should tone map to BT.709 limited planar video when an HDR source requests SDR output")
  void shouldToneMapToBt709LimitedPlanarVideoWhenAnHdrSourceRequestsSdrOutput(
      CompatibleBase source, CompatibleBase output, String encoder) {
    var encode =
        softwareEncode(encoder)
            .sourceDynamicRange(dynamicRange(source))
            .outputDynamicRange(dynamicRange(output));

    var cmd = command(request(encoding(encode)).height(720).build(), encoder);

    assertThat(videoFilter(cmd))
        .isEqualTo(
            "scale=-2:720,tonemapx=tonemap=mobius:peak=100:desat=0:t=bt709:m=bt709:p=bt709:r=tv:"
                + "format=yuv420p,sidedata=mode=delete:type=AMBIENT_VIEWING_ENVIRONMENT");
  }

  private static Stream<Arguments> toneMappedConversions() {
    return Stream.of(CompatibleBase.PQ, CompatibleBase.HLG, CompatibleBase.NONE)
        .flatMap(source -> softwareEncodersFor(source, CompatibleBase.SDR));
  }

  @ParameterizedTest(name = "{0} to {1}, {2}")
  @MethodSource("conversionsWithoutToneMapping")
  @DisplayName("Should omit tone mapping when the source needs none")
  void shouldOmitToneMappingWhenTheSourceNeedsNone(
      CompatibleBase source, CompatibleBase output, String encoder) {
    var encode =
        softwareEncode(encoder)
            .sourceDynamicRange(dynamicRange(source))
            .outputDynamicRange(dynamicRange(output));

    var cmd = command(request(encoding(encode)).height(720).build(), encoder);

    assertThat(videoFilter(cmd))
        .isEqualTo("scale=-2:720" + BT709_LIMITED_SCALE_OPTIONS + ",format=yuv420p");
  }

  private static Stream<Arguments> conversionsWithoutToneMapping() {
    return Stream.of(CompatibleBase.SDR, CompatibleBase.UNSPECIFIED)
        .flatMap(source -> softwareEncodersFor(source, CompatibleBase.SDR));
  }

  private static Stream<Arguments> softwareEncodersFor(
      CompatibleBase source, CompatibleBase output) {
    return Stream.of("libx264", "libx265", "libsvtav1")
        .map(encoder -> Arguments.of(source, output, encoder));
  }

  private static VideoDynamicRange dynamicRange(CompatibleBase compatibleBase) {
    return new VideoDynamicRange(
        compatibleBase,
        VideoDynamicRange.Presence.ABSENT,
        new VideoDynamicRange.DolbyVision.Absent());
  }

  private static VideoDecision.Encode.EncodeBuilder softwareEncode(String encoder) {
    return switch (encoder) {
      case "libx264" -> VideoDecisionFixtures.encodeBuilder();
      case "libx265" -> hevcEncode();
      case "libsvtav1" ->
          VideoDecisionFixtures.encodeBuilder()
              .codecProfile(VideoCodecProfile.AV1_MAIN)
              .tier(Optional.of(VideoCodecTier.MAIN))
              .level(41)
              .codecString("av01.0.09M.08");
      default -> throw new AssertionError("No software encode for " + encoder);
    };
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName("Should leave the copied video without a pixel format when video is copied")
  void shouldLeaveTheCopiedVideoWithoutAPixelFormatWhenVideoIsCopied(TranscodeMode mode) {
    var cmd = command(request(mode).build(), "copy");

    assertThat(cmd)
        .containsSequence("-c:v", "copy")
        .doesNotContain("-vf", "-pix_fmt")
        .noneMatch(
            argument ->
                argument.contains("format=")
                    || argument.contains("tonemap")
                    || argument.contains("sidedata"));
  }

  @Test
  @DisplayName("Should force the High profile and the level when libx264 encodes")
  void shouldForceTheHighProfileAndTheLevelWhenLibx264Encodes() {
    var encode = VideoDecisionFixtures.encodeBuilder().level(42).codecString("avc1.64002A");

    var cmd = command(request(encoding(encode)).build(), "libx264");

    assertThat(cmd).containsSequence("-profile:v", "high", "-level:v", "42");
  }

  @ParameterizedTest(name = "{0} tier")
  @CsvSource({
    "MAIN, no-scenecut=1:no-open-gop=1:level-idc=41:high-tier=0",
    "HIGH, no-scenecut=1:no-open-gop=1:level-idc=41:high-tier=1"
  })
  @DisplayName("Should force the Main profile, the level and the tier when libx265 encodes")
  void shouldForceTheMainProfileTheLevelAndTheTierWhenLibx265Encodes(
      VideoCodecTier tier, String parameters) {
    var encode = hevcEncode().tier(Optional.of(tier));

    var cmd = command(request(encoding(encode)).build(), "libx265");

    assertThat(cmd)
        .containsSequence("-profile:v", "main")
        .containsSequence("-x265-params", parameters);
  }

  @Test
  @DisplayName("Should pass every libx265 parameter in one -x265-params value when libx265 encodes")
  void shouldPassEveryLibx265ParameterInOneX265ParamsValueWhenLibx265Encodes() {
    var encode = hevcEncode().tier(Optional.of(VideoCodecTier.MAIN));

    var cmd = command(request(encoding(encode)).build(), "libx265");

    assertThat(cmd).containsOnlyOnce("-x265-params");
    assertThat(cmd.get(cmd.indexOf("-x265-params") + 1).split(":"))
        .contains("no-scenecut=1", "no-open-gop=1", "level-idc=41", "high-tier=0");
  }

  @Test
  @DisplayName("Should force only the level when libx265 encodes without a tier")
  void shouldForceOnlyTheLevelWhenLibx265EncodesWithoutATier() {
    var cmd = command(request(encoding(hevcEncode())).build(), "libx265");

    assertThat(cmd).containsSequence("-x265-params", "no-scenecut=1:no-open-gop=1:level-idc=41");
  }

  @Test
  @DisplayName("Should force the level when libsvtav1 encodes")
  void shouldForceTheLevelWhenLibsvtav1Encodes() {
    var encode =
        VideoDecision.Encode.builder()
            .sourceCodecFamily(Optional.empty())
            .sourceDynamicRange(VideoDynamicRange.unspecified())
            .outputDynamicRange(VideoDynamicRange.sdr())
            .codecProfile(VideoCodecProfile.AV1_MAIN)
            .tier(Optional.of(VideoCodecTier.MAIN))
            .level(41)
            .codecString("av01.0.09M.08");

    var cmd = command(request(encoding(encode)).build(), "libsvtav1");

    assertThat(cmd).containsSequence("-level:v", "41").doesNotContain("-profile:v");
  }

  @ParameterizedTest(name = "level {0}")
  @CsvSource({"10, 1.0", "31, 3.1", "40, 4.0", "52, 5.2"})
  @DisplayName("Should force the High profile and the level by name when h264_nvenc encodes")
  void shouldForceTheHighProfileAndTheLevelByNameWhenH264NvencEncodes(int level, String name) {
    var encode = VideoDecisionFixtures.encodeBuilder().level(level);

    var cmd = command(request(encoding(encode)).build(), "h264_nvenc");

    assertThat(cmd)
        .containsSequence("-c:v", "h264_nvenc")
        .containsSequence("-profile:v", "high", "-level:v", name)
        .doesNotContain("-tier:v", "-x265-params");
  }

  @ParameterizedTest(name = "{0} tier")
  @CsvSource({"MAIN, main", "HIGH, high"})
  @DisplayName("Should force the Main profile, the level and the tier when hevc_nvenc encodes")
  void shouldForceTheMainProfileTheLevelAndTheTierWhenHevcNvencEncodes(
      VideoCodecTier tier, String tierName) {
    var encode = hevcEncode().tier(Optional.of(tier));

    var cmd = command(request(encoding(encode)).build(), "hevc_nvenc");

    assertThat(cmd)
        .containsSequence("-c:v", "hevc_nvenc")
        .containsSequence("-profile:v", "main", "-level:v", "4.1", "-tier:v", tierName)
        .doesNotContain("-x265-params");
  }

  @Test
  @DisplayName("Should force no tier when hevc_nvenc encodes without one")
  void shouldForceNoTierWhenHevcNvencEncodesWithoutOne() {
    var cmd = command(request(encoding(hevcEncode())).build(), "hevc_nvenc");

    assertThat(cmd)
        .containsSequence("-profile:v", "main", "-level:v", "4.1")
        .doesNotContain("-tier:v");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"av1_nvenc", "h264_videotoolbox", "h264_qsv", "h264_vaapi"})
  @DisplayName(
      "Should leave the codec profile and level to the encoder when a hardware encoder the worker"
          + " cannot force runs")
  void shouldLeaveTheCodecProfileAndLevelToTheEncoderWhenAHardwareEncoderTheWorkerCannotForceRuns(
      String encoder) {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), encoder);

    assertThat(cmd)
        .containsSequence("-c:v", encoder)
        .doesNotContain("-profile:v", "-level:v", "-tier:v", "-x265-params");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName("Should force no codec profile or level when video is copied")
  void shouldForceNoCodecProfileOrLevelWhenVideoIsCopied(TranscodeMode mode) {
    var cmd = command(request(mode).build(), "copy");

    assertThat(cmd)
        .containsSequence("-c:v", "copy")
        .doesNotContain("-profile:v", "-level:v", "-x265-params");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"libx264", "libx265", "libsvtav1"})
  @DisplayName(
      "Should force no codec profile or level when an encoding request's video decision is a"
          + " stream copy")
  void shouldForceNoCodecProfileOrLevelWhenAnEncodingRequestsVideoDecisionIsAStreamCopy(
      String encoder) {
    var copy = new VideoDecision.Copy(0, "avc1.64000A");
    var request =
        request(decision(TranscodeMode.FULL_TRANSCODE).videoDecision(copy).build()).build();

    var cmd = command(request, encoder);

    assertThat(cmd)
        .containsSequence("-c:v", encoder)
        .doesNotContain("-profile:v", "-level:v")
        .noneMatch(argument -> argument.contains("level-idc") || argument.contains("high-tier"));
  }

  @ParameterizedTest(name = "{0} forces: {1}")
  @CsvSource({
    "libx264, true",
    "libx265, true",
    "libsvtav1, true",
    "h264_nvenc, true",
    "hevc_nvenc, true",
    "av1_nvenc, false",
    "hevc_qsv, false",
    "av1_vaapi, false",
    "h264_videotoolbox, false"
  })
  @DisplayName(
      "Should force the job's codec profile and level only on the software encoders and the H.264"
          + " and HEVC NVENC encoders")
  void
      shouldForceTheJobsCodecProfileAndLevelOnlyOnTheSoftwareEncodersAndTheH264AndHevcNvencEncoders(
          String encoder, boolean forces) {
    assertThat(FfmpegCommandBuilder.forcesCodecProfile(encoder)).isEqualTo(forces);
  }

  private static TranscodeDecision encoding(VideoDecision.Encode.EncodeBuilder encode) {
    return decision(TranscodeMode.FULL_TRANSCODE).videoDecision(encode.build()).build();
  }

  private static VideoDecision.Encode.EncodeBuilder hevcEncode() {
    return VideoDecision.Encode.builder()
        .sourceCodecFamily(Optional.empty())
        .sourceDynamicRange(VideoDynamicRange.unspecified())
        .outputDynamicRange(VideoDynamicRange.sdr())
        .codecProfile(VideoCodecProfile.HEVC_MAIN)
        .tier(Optional.empty())
        .level(41)
        .codecString("hvc1.1.6.L123.90");
  }

  private static String videoFilter(List<String> cmd) {
    assertThat(cmd).as("the command's -vf options").containsOnlyOnce("-vf");
    return cmd.get(cmd.indexOf("-vf") + 1);
  }

  @Test
  @DisplayName("Should not include scale or bitrate when mode is audio transcode")
  void shouldNotIncludeScaleOrBitrateWhenModeIsAudioTranscode() {
    var cmd = command(request(TranscodeMode.AUDIO_TRANSCODE).build(), "copy");

    assertThat(cmd).isNotEmpty().doesNotContain("-vf", "-b:v", "-maxrate", "-bufsize");
  }

  @Test
  @DisplayName("Should use copy video and AAC audio when mode is audio transcode")
  void shouldUseCopyVideoAndAacAudioWhenModeIsAudioTranscode() {
    var cmd = command(request(TranscodeMode.AUDIO_TRANSCODE).build(), "copy");

    assertThat(cmd).containsSubsequence("-c:v", "copy").containsSubsequence("-c:a", "aac");
    assertThat(audioBitrate(cmd)).isEqualTo("128k");
  }

  @Test
  @DisplayName("Should encode H264 video and AAC audio when full transcode targets H264")
  void shouldEncodeH264VideoAndAacAudioWhenFullTranscodeTargetsH264() {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "libx264");

    assertThat(cmd).containsSubsequence("-c:v", "libx264").containsSubsequence("-c:a", "aac");
    assertThat(audioBitrate(cmd)).isEqualTo("128k");
  }

  @Test
  @DisplayName("Should encode AV1 video when full transcode targets AV1")
  void shouldEncodeAv1VideoWhenFullTranscodeTargetsAv1() {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "libsvtav1");

    assertThat(cmd).containsSequence("-c:v", "libsvtav1");
  }

  @ParameterizedTest(name = "{0} source, {1}")
  @CsvSource({
    "hvc1, REMUX, hevc_mp4toannexb, hvc1",
    "hev1, REMUX, hevc_mp4toannexb, hvc1",
    "dvh1, AUDIO_TRANSCODE, hevc_mp4toannexb, hvc1",
    "dvhe, REMUX, hevc_mp4toannexb, hvc1",
    "avc1, AUDIO_TRANSCODE, h264_mp4toannexb, avc1",
    "avc3, REMUX, h264_mp4toannexb, avc1",
    "dva1, REMUX, h264_mp4toannexb, avc1",
    "dvav, AUDIO_TRANSCODE, h264_mp4toannexb, avc1"
  })
  @DisplayName(
      "Should write the codec family's playable sample entry from parameter sets the record or the"
          + " samples hold when the video is copied")
  void
      shouldWriteTheCodecFamilysPlayableSampleEntryFromParameterSetsTheRecordOrTheSamplesHoldWhenTheVideoIsCopied(
          String sourceSampleEntry, TranscodeMode mode, String filter, String sampleEntry) {
    var copy = new VideoDecision.Copy(0, sourceSampleEntry + ".64001F");
    var request = request(decision(mode).videoDecision(copy).build()).build();

    var cmd = command(request, "copy");

    assertThat(cmd)
        .containsSequence("-c:v", "copy", "-bsf:v", filter, "-tag:v", sampleEntry)
        .containsOnlyOnce("-bsf:v", "-tag:v");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"av01.0.08M.08", "vp09.00.40.08", "mp4v.20.9"})
  @DisplayName(
      "Should leave the sample entry to FFmpeg when the copied video is neither H.264 nor HEVC")
  void shouldLeaveTheSampleEntryToFfmpegWhenTheCopiedVideoIsNeitherH264NorHevc(String codecString) {
    var request =
        request(
                decision(TranscodeMode.REMUX)
                    .videoDecision(new VideoDecision.Copy(0, codecString))
                    .build())
            .build();

    assertThat(command(request, "copy")).doesNotContain("-tag:v", "-bsf:v");
  }

  @Test
  @DisplayName(
      "Should leave the sample entry to FFmpeg when a copying request's video decision is an"
          + " encode")
  void shouldLeaveTheSampleEntryToFfmpegWhenACopyingRequestsVideoDecisionIsAnEncode() {
    var request =
        request(decision(TranscodeMode.REMUX).videoDecision(hevcEncode().build()).build()).build();

    assertThat(command(request, "copy"))
        .containsSequence("-c:v", "copy", "-strict", "unofficial")
        .doesNotContain("-tag:v", "-bsf:v");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"hvc1.2.4.L63.90", "avc1.64001F", "av01.0.08M.08"})
  @DisplayName(
      "Should let the mp4 muxer keep the Dolby Vision configuration record when the video is"
          + " copied")
  void shouldLetTheMp4MuxerKeepTheDolbyVisionConfigurationRecordWhenTheVideoIsCopied(
      String codecString) {
    var request =
        request(
                decision(TranscodeMode.AUDIO_TRANSCODE)
                    .videoDecision(new VideoDecision.Copy(0, codecString))
                    .build())
            .build();

    assertThat(command(request, "copy")).containsSequence("-strict", "unofficial");
  }

  @ParameterizedTest(name = "{0} writes {1}")
  @CsvSource({
    "libx265, hvc1",
    "hevc_nvenc, hvc1",
    "hevc_qsv, hvc1",
    "hevc_videotoolbox, hvc1",
    "libx264, avc1",
    "h264_vaapi, avc1",
    "h264_videotoolbox, avc1"
  })
  @DisplayName("Should write the codec family's playable sample entry when an encoder runs")
  void shouldWriteTheCodecFamilysPlayableSampleEntryWhenAnEncoderRuns(
      String encoder, String sampleEntry) {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), encoder);

    assertThat(cmd)
        .containsSequence("-c:v", encoder)
        .containsSequence("-tag:v", sampleEntry)
        .containsOnlyOnce("-tag:v")
        .doesNotContain("-bsf:v");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"libsvtav1", "av1_nvenc", "av1_vaapi"})
  @DisplayName("Should leave the sample entry to FFmpeg when an AV1 encoder runs")
  void shouldLeaveTheSampleEntryToFfmpegWhenAnAv1EncoderRuns(String encoder) {
    var cmd = command(request(TranscodeMode.VIDEO_TRANSCODE).build(), encoder);

    assertThat(cmd).isNotEmpty().doesNotContain("-tag:v", "-bsf:v");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"libx264", "libx265", "libsvtav1", "hevc_nvenc"})
  @DisplayName("Should keep FFmpeg's strict compliance when an encoder runs")
  void shouldKeepFfmpegsStrictComplianceWhenAnEncoderRuns(String encoder) {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), encoder);

    assertThat(cmd).isNotEmpty().doesNotContain("-strict", "unofficial");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName("Should encode at the probed frame rate when video is transcoded")
  void shouldEncodeAtTheProbedFrameRateWhenVideoIsTranscoded(TranscodeMode mode) {
    var cmd =
        command(
            request(mode).outputFrameRate(Optional.of(new OutputFrameRate(24_000, 1001))).build(),
            "libx264");

    assertThat(cmd)
        .containsSequence("-r:v:0", "24000/1001")
        .noneMatch(argument -> argument.startsWith("-fps_mode"));
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName("Should keep the source frame timing when video is copied")
  void shouldKeepTheSourceFrameTimingWhenVideoIsCopied(TranscodeMode mode) {
    var cmd = command(request(mode).build(), "copy");

    assertThat(cmd)
        .doesNotContain("-r:v:0")
        .noneMatch(argument -> argument.startsWith("-fps_mode"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyEncoder")
  @DisplayName(
      "Should force an IDR keyframe at every advertised segment boundary when any encoder runs")
  void shouldForceAnIdrKeyframeAtEveryAdvertisedSegmentBoundaryWhenAnyEncoderRuns(String encoder) {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).mediaSegmentCount(4).build(), encoder);

    assertThat(cmd)
        .containsSequence("-forced-idr", "1")
        .containsSequence("-force_key_frames:0", "0,6,12,18")
        .noneMatch(argument -> argument.startsWith("expr:"));
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName(
      "Should force keyframes from the attempt's first segment to the last advertised one when a"
          + " replacement attempt starts mid-stream")
  void
      shouldForceKeyframesFromTheAttemptsFirstSegmentToTheLastAdvertisedOneWhenAReplacementAttemptStartsMidStream(
          TranscodeMode mode) {
    var cmd =
        command(
            request(mode)
                .targetSegmentDuration(4)
                .startSequenceNumber(5)
                .mediaSegmentCount(9)
                .build(),
            "libx264");

    assertThat(cmd).containsSequence("-force_key_frames:0", "20,24,28,32");
  }

  @Test
  @DisplayName("Should keep every boundary when the list fills the longest argument Linux accepts")
  void shouldKeepEveryBoundaryWhenTheListFillsTheLongestArgumentLinuxAccepts() {
    // 14,997 five-digit and 5,870 six-digit times with their commas: 131,071 bytes, which with the
    // terminating NUL is Linux's limit on one argument, 32 pages of 4 KiB.
    var cmd =
        command(
            request(TranscodeMode.FULL_TRANSCODE)
                .startSequenceNumber(1670)
                .mediaSegmentCount(22_537)
                .build(),
            "libx264");

    assertThat(forcedKeyframeTimes(cmd))
        .hasSize(131_071)
        .startsWith("10020,10026,")
        .endsWith(",135210,135216");
  }

  @ParameterizedTest(name = "{0} advertised segments")
  @ValueSource(ints = {22_538, Integer.MAX_VALUE})
  @DisplayName(
      "Should end the list at the last boundary that fits when the next would pass the argument"
          + " limit")
  void shouldEndTheListAtTheLastBoundaryThatFitsWhenTheNextWouldPassTheArgumentLimit(
      int mediaSegmentCount) {
    var cmd =
        command(
            request(TranscodeMode.FULL_TRANSCODE)
                .startSequenceNumber(1670)
                .mediaSegmentCount(mediaSegmentCount)
                .build(),
            "libx264");

    assertThat(forcedKeyframeTimes(cmd)).hasSize(131_071).endsWith(",135210,135216");
  }

  @Test
  @DisplayName(
      "Should end the list short of the limit when the next boundary would make the argument one"
          + " byte too long")
  void shouldEndTheListShortOfTheLimitWhenTheNextBoundaryWouldMakeTheArgumentOneByteTooLong() {
    // From segment 2 the list reaches 131,065 bytes at 128214 s; the next time, ",128220", would
    // make it 131,072 bytes, which with its NUL no longer fits in 32 pages of 4 KiB.
    var cmd =
        command(
            request(TranscodeMode.FULL_TRANSCODE)
                .startSequenceNumber(2)
                .mediaSegmentCount(Integer.MAX_VALUE)
                .build(),
            "libx264");

    assertThat(forcedKeyframeTimes(cmd))
        .hasSize(131_065)
        .startsWith("12,18,")
        .endsWith(",128208,128214");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"libx264", "libsvtav1", "libx265"})
  @DisplayName(
      "Should round the GOP down to whole frames in a segment period when the list ends before the"
          + " last advertised boundary")
  void shouldRoundTheGopDownToWholeFramesInASegmentPeriodWhenTheListEndsBeforeTheLastBoundary(
      String encoder) {
    var cmd =
        command(
            request(TranscodeMode.FULL_TRANSCODE)
                .startSequenceNumber(1670)
                .mediaSegmentCount(22_538)
                .build(),
            encoder);

    assertThat(cmd).containsSequence("-g:v:0", "143");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"libx264", "libsvtav1", "libx265"})
  @DisplayName(
      "Should lengthen the GOP one frame past the rounded-up period when the list from the first"
          + " segment fills the longest argument and reaches the last advertised boundary")
  void shouldLengthenTheGopWhenTheListFromTheFirstSegmentReachesTheLastBoundary(String encoder) {
    // 21,370 boundaries from zero at a 6 s period make a list of 131,069 bytes.
    var cmd =
        command(request(TranscodeMode.FULL_TRANSCODE).mediaSegmentCount(21_370).build(), encoder);

    assertThat(cmd).containsSequence("-g:v:0", "145");
  }

  @ParameterizedTest(name = "{0}, {1} advertised segments")
  @CsvSource({
    "libx264, 21370",
    "libx264, 21371",
    "libsvtav1, 21370",
    "libsvtav1, 21371",
    "libx265, 21370",
    "libx265, 21371"
  })
  @DisplayName(
      "Should give a replacement attempt the start attempt's encoder arguments apart from its seek"
          + " and forced keyframes when the list from the first segment does or does not fit")
  void shouldGiveAReplacementAttemptTheStartAttemptsEncoderArgumentsWhetherOrNotTheListFits(
      String encoder, int mediaSegmentCount) {
    // libx265 writes its settings, the GOP among them, into the initialization segment.
    var startAttempt =
        command(
            request(TranscodeMode.FULL_TRANSCODE).mediaSegmentCount(mediaSegmentCount).build(),
            encoder);
    var replacementAttempt =
        command(
            request(TranscodeMode.FULL_TRANSCODE)
                .startSequenceNumber(21_000)
                .mediaSegmentCount(mediaSegmentCount)
                .build(),
            encoder);

    assertThat(withoutSeekAndForcedKeyframes(replacementAttempt))
        .containsExactlyElementsOf(withoutSeekAndForcedKeyframes(startAttempt));
  }

  private static List<String> withoutSeekAndForcedKeyframes(List<String> cmd) {
    var remaining = new ArrayList<>(cmd);
    for (var option : List.of("-ss", "-force_key_frames:0")) {
      var index = remaining.indexOf(option);
      if (index >= 0) {
        remaining.subList(index, index + 2).clear();
      }
    }

    return remaining;
  }

  private static String forcedKeyframeTimes(List<String> cmd) {
    return cmd.get(cmd.indexOf("-force_key_frames:0") + 1);
  }

  @Test
  @DisplayName("Should disable scene-cut keyframes when encoder is libx264")
  void shouldDisableSceneCutKeyframesWhenEncoderIsLibx264() {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "libx264");

    assertThat(cmd).containsSequence("-sc_threshold:v:0", "0");
  }

  static Stream<String> everyEncoder() {
    return Stream.of(
        "libx264",
        "libx265",
        "libsvtav1",
        "h264_nvenc",
        "hevc_qsv",
        "av1_amf",
        "h264_vaapi",
        "hevc_rkmpp",
        "h264_videotoolbox");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"libx264", "libsvtav1", "libx265"})
  @DisplayName(
      "Should lengthen the GOP one frame past the rounded-up period when the encoder is verified")
  void shouldLengthenTheGopOneFramePastTheRoundedUpPeriodWhenTheEncoderIsVerified(String encoder) {
    var cmd =
        command(
            request(TranscodeMode.FULL_TRANSCODE)
                .outputFrameRate(Optional.of(new OutputFrameRate(24_000, 1001)))
                .build(),
            encoder);

    assertThat(cmd).containsSequence("-g:v:0", "145");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "h264_nvenc",
        "hevc_qsv",
        "av1_amf",
        "h264_vaapi",
        "hevc_rkmpp",
        "h264_videotoolbox"
      })
  @DisplayName(
      "Should round the GOP down to whole frames in a segment period when the encoder is not"
          + " verified")
  void shouldRoundTheGopDownToWholeFramesInASegmentPeriodWhenTheEncoderIsNotVerified(
      String encoder) {
    var cmd =
        command(
            request(TranscodeMode.FULL_TRANSCODE)
                .outputFrameRate(Optional.of(new OutputFrameRate(24_000, 1001)))
                .build(),
            encoder);

    assertThat(cmd).containsSequence("-g:v:0", "143");
  }

  @Test
  @DisplayName(
      "Should lengthen the GOP one frame past the period when the period spans whole frames and the"
          + " encoder is verified")
  void
      shouldLengthenTheGopOneFramePastThePeriodWhenThePeriodSpansWholeFramesAndTheEncoderIsVerified() {
    var cmd =
        command(
            request(TranscodeMode.FULL_TRANSCODE)
                .outputFrameRate(Optional.of(new OutputFrameRate(25, 1)))
                .build(),
            "libx264");

    assertThat(cmd).containsSequence("-g:v:0", "151");
  }

  @Test
  @DisplayName(
      "Should keep every frame of the period in the GOP when the period spans whole frames and the"
          + " encoder is not verified")
  void
      shouldKeepEveryFrameOfThePeriodInTheGopWhenThePeriodSpansWholeFramesAndTheEncoderIsNotVerified() {
    var cmd =
        command(
            request(TranscodeMode.FULL_TRANSCODE)
                .outputFrameRate(Optional.of(new OutputFrameRate(25, 1)))
                .build(),
            "h264_vaapi");

    assertThat(cmd).containsSequence("-g:v:0", "150");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "libsvtav1, 145",
    "h264_nvenc, 143",
    "hevc_qsv, 143",
    "av1_amf, 143",
    "hevc_rkmpp, 143"
  })
  @DisplayName(
      "Should fix the minimum keyframe interval to the GOP when the encoder has a fixed GOP")
  void shouldFixTheMinimumKeyframeIntervalToTheGopWhenTheEncoderHasAFixedGop(
      String encoder, String gopFrames) {
    var cmd =
        command(
            request(TranscodeMode.FULL_TRANSCODE)
                .outputFrameRate(Optional.of(new OutputFrameRate(24_000, 1001)))
                .build(),
            encoder);

    assertThat(cmd)
        .containsSequence("-g:v:0", gopFrames)
        .containsSequence("-keyint_min:v:0", gopFrames);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"libx264", "libx265", "h264_vaapi", "h264_videotoolbox"})
  @DisplayName(
      "Should leave the minimum keyframe interval to the encoder when the encoder has no fixed GOP")
  void shouldLeaveTheMinimumKeyframeIntervalToTheEncoderWhenTheEncoderHasNoFixedGop(
      String encoder) {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), encoder);

    assertThat(cmd).isNotEmpty().doesNotContain("-keyint_min:v:0");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName(
      "Should seek one period before its first segment when an encoded replacement attempt starts"
          + " mid-stream")
  void shouldSeekOnePeriodBeforeItsFirstSegmentWhenAnEncodedReplacementAttemptStartsMidStream(
      TranscodeMode mode) {
    var cmd = command(request(mode).startSequenceNumber(5).build(), "libsvtav1");

    assertThat(cmd).containsSubsequence("-ss", "24", "-i");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName(
      "Should read from the start when an encoded replacement attempt starts at the second segment")
  void shouldReadFromTheStartWhenAnEncodedReplacementAttemptStartsAtTheSecondSegment(
      TranscodeMode mode) {
    var cmd = command(request(mode).startSequenceNumber(1).build(), "libx264");

    assertThat(cmd)
        .doesNotContain("-ss")
        .containsSequence("-force_key_frames:0", "6,12,18,24,30,36,42,48,54,60");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName(
      "Should seek to its first segment when a stream-copy replacement attempt starts mid-stream")
  void shouldSeekToItsFirstSegmentWhenAStreamCopyReplacementAttemptStartsMidStream(
      TranscodeMode mode) {
    var cmd = command(request(mode).startSequenceNumber(5).build(), "copy");

    assertThat(cmd).containsSubsequence("-ss", "30", "-i");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName(
      "Should seek a stream copy to the server's keyframe position when the job carries one")
  void shouldSeekAStreamCopyToTheServersKeyframePositionWhenTheJobCarriesOne(TranscodeMode mode) {
    var request =
        request(mode)
            .startSequenceNumber(5)
            .seekPosition(Optional.of(Duration.ofSeconds(29, 196_000_000)))
            .build();

    var cmd = command(request, "copy");

    assertThat(cmd).containsSubsequence("-ss", "29.196", "-i");
  }

  @Test
  @DisplayName(
      "Should round a keyframe position up to the next microsecond when the position has a finer"
          + " precision")
  void shouldRoundAKeyframePositionUpToTheNextMicrosecondWhenThePositionHasAFinerPrecision() {
    // 1001/24000 s, the decode time of a 23.976 fps source's second frame, is 41,708.333... µs.
    var request =
        request(TranscodeMode.REMUX)
            .startSequenceNumber(1)
            .seekPosition(Optional.of(Duration.ofNanos(41_708_333)))
            .build();

    var cmd = command(request, "copy");

    assertThat(cmd).containsSubsequence("-ss", "0.041709", "-i");
  }

  @Test
  @DisplayName(
      "Should read from the start when a stream copy's keyframe position is not after the start")
  void shouldReadFromTheStartWhenAStreamCopysKeyframePositionIsNotAfterTheStart() {
    var request =
        request(TranscodeMode.REMUX)
            .startSequenceNumber(0)
            .seekPosition(Optional.of(Duration.ofMillis(-83)))
            .build();

    var cmd = command(request, "copy");

    assertThat(cmd).isNotEmpty().doesNotContain("-ss");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName("Should keep the period-based seek when an encoded job carries no position")
  void shouldKeepThePeriodBasedSeekWhenAnEncodedJobCarriesNoPosition(TranscodeMode mode) {
    var request = request(mode).startSequenceNumber(5).seekPosition(Optional.empty()).build();

    var cmd = command(request, "libx264");

    assertThat(cmd).containsSubsequence("-ss", "24", "-i");
  }

  @Test
  @DisplayName("Should keep the period-based seek when an encoded job carries a keyframe position")
  void shouldKeepThePeriodBasedSeekWhenAnEncodedJobCarriesAKeyframePosition() {
    var request =
        request(TranscodeMode.FULL_TRANSCODE)
            .startSequenceNumber(5)
            .seekPosition(Optional.of(Duration.ofMillis(29_196)))
            .build();

    var cmd = command(request, "libx264");

    assertThat(cmd).containsSubsequence("-ss", "24", "-i");
  }

  @Test
  @DisplayName("Should place seek before input when the attempt starts after segment zero")
  void shouldPlaceSeekBeforeInputWhenTheAttemptStartsAfterSegmentZero() {
    var cmd = command(request(TranscodeMode.REMUX).startSequenceNumber(50).build(), "copy");

    int ssIndex = cmd.indexOf("-ss");
    int iIndex = cmd.indexOf("-i");
    assertThat(ssIndex).isGreaterThan(-1);
    assertThat(iIndex).isGreaterThan(ssIndex);
    assertThat(cmd.get(ssIndex + 1)).isEqualTo("300");
  }

  @Test
  @DisplayName("Should not include seek when the attempt starts at segment zero")
  void shouldNotIncludeSeekWhenTheAttemptStartsAtSegmentZero() {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "libx264");

    assertThat(cmd).isNotEmpty().doesNotContain("-ss");
  }

  @Test
  @DisplayName("Should include common flags when mode is full transcode")
  void shouldIncludeCommonFlagsWhenModeIsFullTranscode() {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "libx264");

    assertThat(cmd)
        .contains("-map_metadata", "-1")
        .contains("-map_chapters", "-1")
        .contains("-copyts")
        .contains("-avoid_negative_ts", "disabled")
        .contains("-max_muxing_queue_size", "128");
  }

  @ParameterizedTest
  @EnumSource(TranscodeMode.class)
  @DisplayName(
      "Should bound how far the muxer lets one stream run ahead of another at FFmpeg's default when"
          + " building any mode")
  void shouldBoundHowFarTheMuxerLetsOneStreamRunAheadAtFfmpegsDefaultWhenBuildingAnyMode(
      TranscodeMode mode) {
    var cmd = command(request(mode).build(), mode.encodesVideo() ? "libx264" : "copy");

    assertThat(cmd).containsSequence("-max_interleave_delta", "10000000");
  }

  @Test
  @DisplayName("Should start with FFmpeg binary when building command")
  void shouldStartWithFfmpegBinaryWhenBuildingCommand() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd.getFirst()).isEqualTo("ffmpeg");
  }

  @Test
  @DisplayName("Should not include keyframe args when mode is remux")
  void shouldNotIncludeKeyframeArgsWhenModeIsRemux() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd)
        .isNotEmpty()
        .noneMatch(s -> s.contains("force_key_frames"))
        .doesNotContain("-g:v:0")
        .doesNotContain("-forced-idr");
  }

  @Test
  @DisplayName("Should write fragmented MP4 to standard output when building command")
  void shouldWriteFragmentedMp4ToStandardOutputWhenBuildingCommand() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd)
        .containsSequence("-f", "mp4")
        .containsSequence("-movflags", "cmaf+delay_moov+skip_trailer+frag_keyframe+frag_discont")
        .doesNotContain("-min_frag_duration", "-max_delay")
        .endsWith("pipe:1");
  }

  @Test
  @DisplayName("Should cut fragments at the fragmentation target when building command")
  void shouldCutFragmentsAtTheFragmentationTargetWhenBuildingCommand() {
    var targetedBuilder = new FfmpegCommandBuilder("ffmpeg", Duration.ofMillis(1500));

    var cmd =
        singleCommandOf(
            targetedBuilder.buildPipeline(job(request(TranscodeMode.REMUX).build(), "copy")));

    assertThat(cmd).containsSequence("-frag_duration", "1500000", "pipe:1");
  }

  @Test
  @DisplayName("Should leave out the HLS muxer when a replacement attempt starts mid-stream")
  void shouldLeaveOutTheHlsMuxerWhenAReplacementAttemptStartsMidStream() {
    var cmd = command(request(TranscodeMode.REMUX).startSequenceNumber(5).build(), "copy");

    assertThat(cmd)
        .doesNotContain("-start_number")
        .noneMatch(argument -> argument.startsWith("-hls_"))
        .endsWith("pipe:1");
  }

  @Test
  @DisplayName("Should measure media time from the source start when an attempt seeks")
  void shouldMeasureMediaTimeFromTheSourceStartWhenAnAttemptSeeks() {
    var cmd =
        command(request(TranscodeMode.FULL_TRANSCODE).startSequenceNumber(50).build(), "libx264");

    assertThat(cmd)
        .contains("-copyts", "-start_at_zero")
        .containsSequence("-avoid_negative_ts", "disabled");
  }

  @Test
  @DisplayName("Should close GOPs when encoder is libx265")
  void shouldCloseGopsWhenEncoderIsLibx265() {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "libx265");

    assertThat(cmd).containsOnlyOnce("-x265-params").doesNotContain("-sc_threshold:v:0");
    assertThat(cmd.get(cmd.indexOf("-x265-params") + 1)).startsWith("no-scenecut=1:no-open-gop=1:");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"libx264", "libsvtav1", "hevc_nvenc", "hevc_videotoolbox"})
  @DisplayName("Should pass no x265 parameters when encoder is not libx265")
  void shouldPassNoX265ParametersWhenEncoderIsNotLibx265(String encoder) {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), encoder);

    assertThat(cmd).isNotEmpty().doesNotContain("-x265-params");
  }

  @Test
  @DisplayName("Should include overwrite flag but not nostdin when building command")
  void shouldIncludeOverwriteFlagButNotNostdinWhenBuildingCommand() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd).isNotEmpty().contains("-y").doesNotContain("-nostdin");
  }

  @Test
  @DisplayName("Should map first video and audio streams when building command")
  void shouldMapFirstVideoAndAudioStreamsWhenBuildingCommand() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd).containsSequence("-map", "0:0", "-map", "0:a:0");
  }

  @Test
  @DisplayName("Should exclude subtitle streams when building command")
  void shouldExcludeSubtitleStreamsWhenBuildingCommand() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd).containsSequence("-map", "-0:s");
  }

  @Test
  @DisplayName("Should downmix audio to stereo when mode is audio transcode")
  void shouldDownmixAudioToStereoWhenModeIsAudioTranscode() {
    var cmd = command(request(TranscodeMode.AUDIO_TRANSCODE).build(), "copy");

    assertThat(cmd).contains("-ac", "2");
  }

  @Test
  @DisplayName("Should not downmix audio when mode is remux")
  void shouldNotDownmixAudioWhenModeIsRemux() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd).isNotEmpty().doesNotContain("-ac");
  }

  @Test
  @DisplayName("Should use video encoder with audio copy when mode is video transcode")
  void shouldUseVideoEncoderWithAudioCopyWhenModeIsVideoTranscode() {
    var cmd =
        command(
            request(
                    decision(TranscodeMode.VIDEO_TRANSCODE)
                        .audioDecision(copiedAudio("ac3"))
                        .build())
                .build(),
            "libx264");

    assertThat(cmd)
        .isNotEmpty()
        .contains("-c:v", "libx264")
        .contains("-c:a", "copy")
        .contains("-vf", "scale=-2:1080" + BT709_LIMITED_SCALE_OPTIONS + ",format=yuv420p")
        .doesNotContain("-ac");
    assertThat(audioBitrate(cmd)).isEqualTo("0");
  }

  @Test
  @DisplayName("Should include keyframe args when mode is video transcode")
  void shouldIncludeKeyframeArgsWhenModeIsVideoTranscode() {
    var cmd =
        command(
            request(
                    decision(TranscodeMode.VIDEO_TRANSCODE)
                        .audioDecision(copiedAudio("ac3"))
                        .build())
                .build(),
            "libx264");

    assertThat(cmd).contains("-forced-idr", "1");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "VIDEO_TRANSCODE"})
  @DisplayName("Should convert ADTS framing when AAC audio is copied")
  void shouldConvertAdtsFramingWhenAacAudioIsCopied(TranscodeMode mode) {
    var cmd = command(request(mode).build(), "libx264");

    assertThat(cmd).containsSequence("-c:a", "copy", "-bsf:a", "aac_adtstoasc");
  }

  @Test
  @DisplayName("Should leave copied audio unfiltered when it is not AAC")
  void shouldLeaveCopiedAudioUnfilteredWhenItIsNotAac() {
    var cmd =
        command(
            request(decision(TranscodeMode.REMUX).audioDecision(copiedAudio("ac3")).build())
                .build(),
            "copy");

    assertThat(cmd).contains("-c:a", "copy").doesNotContain("-bsf:a");
  }

  @ParameterizedTest
  @ValueSource(strings = {"aac", "ac3", "eac3", "flac", "opus", "alac"})
  @DisplayName("Should declare the bitrate unknown when audio is copied")
  void shouldDeclareTheBitrateUnknownWhenAudioIsCopied(String codec) {
    var cmd =
        command(
            request(decision(TranscodeMode.REMUX).audioDecision(copiedAudio(codec)).build())
                .build(),
            "copy");

    assertThat(cmd).containsSubsequence("-c:a", "copy", "-b:a", "pipe:1");
    assertThat(audioBitrate(cmd)).isEqualTo("0");
  }

  @Test
  @DisplayName("Should leave encoded AAC audio unfiltered when audio is transcoded")
  void shouldLeaveEncodedAacAudioUnfilteredWhenAudioIsTranscoded() {
    var cmd = command(request(TranscodeMode.AUDIO_TRANSCODE).build(), "copy");

    assertThat(cmd).containsSequence("-c:a", "aac").doesNotContain("-bsf:a");
  }

  // --- Video-only (no audio) ---

  @Test
  @DisplayName("Should omit audio map and codec args when audio mode is none")
  void shouldOmitAudioMapAndCodecArgsWhenAudioModeIsNone() {
    var cmd =
        command(
            request(decision(TranscodeMode.FULL_TRANSCODE).audioDecision(NO_AUDIO).build()).build(),
            "libx264");

    assertThat(cmd)
        .isNotEmpty()
        .doesNotContain("0:a:0")
        .doesNotContain("-c:a")
        .doesNotContain("-ac")
        .doesNotContain("-b:a");
  }

  // --- Surround sound audio args ---

  @Test
  @DisplayName("Should transcode to AC-3 5.1 when audio decision is AC-3 transcode")
  void shouldTranscodeToAc3SurroundWhenAudioDecisionIsAc3Transcode() {
    var audio = new AudioDecision(AudioMode.TRANSCODE, "ac3", 6, 384_000L);
    var cmd =
        command(
            request(decision(TranscodeMode.AUDIO_TRANSCODE).audioDecision(audio).build()).build(),
            "copy");

    assertThat(cmd).containsSubsequence("-c:a", "ac3").containsSubsequence("-ac", "6");
    assertThat(audioBitrate(cmd)).isEqualTo("384k");
  }

  @Test
  @DisplayName("Should transcode to E-AC-3 7.1 when audio decision is E-AC-3 transcode")
  void shouldTranscodeToEac3SurroundWhenAudioDecisionIsEac3Transcode() {
    var audio = new AudioDecision(AudioMode.TRANSCODE, "eac3", 8, 512_000L);
    var cmd =
        command(
            request(decision(TranscodeMode.AUDIO_TRANSCODE).audioDecision(audio).build()).build(),
            "copy");

    assertThat(cmd).containsSubsequence("-c:a", "eac3").containsSubsequence("-ac", "8");
    assertThat(audioBitrate(cmd)).isEqualTo("512k");
  }

  @Test
  @DisplayName("Should copy surround audio when audio decision is copy with 5.1 channels")
  void shouldCopySurroundAudioWhenAudioDecisionIsCopyWith51Channels() {
    var audio =
        AudioDecision.builder()
            .mode(AudioMode.COPY)
            .codec("ac3")
            .channels(6)
            .bitrate(384_000L)
            .build();
    var cmd =
        command(
            request(decision(TranscodeMode.REMUX).audioDecision(audio).build()).build(), "copy");

    assertThat(cmd).isNotEmpty().contains("-c:a", "copy").doesNotContain("-ac");
    assertThat(audioBitrate(cmd)).isEqualTo("0");
  }

  // --- Map ordering ---

  @Test
  @DisplayName("Should map video, audio, and exclude subtitles in correct order")
  void shouldMapVideoAudioAndExcludeSubtitlesInCorrectOrder() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd).containsSequence("-map", "0:0", "-map", "0:a:0", "-map", "-0:s");
  }

  @Test
  @DisplayName("Should map video and exclude subtitles without audio when audio mode is none")
  void shouldMapVideoAndExcludeSubtitlesWithoutAudioWhenAudioModeIsNone() {
    var cmd =
        command(
            request(decision(TranscodeMode.FULL_TRANSCODE).audioDecision(NO_AUDIO).build()).build(),
            "libx264");

    assertThat(cmd).containsSequence("-map", "0:0", "-map", "-0:s").doesNotContain("0:a:0");
  }

  // --- Dolby Vision profile 5 whose RPU starts its sample ---

  private static final String TONE_MAPPED_TO_SDR =
      "tonemapx=tonemap=mobius:peak=100:desat=0:t=bt709:m=bt709:p=bt709:r=tv:format=yuv420p,"
          + "sidedata=mode=delete:type=AMBIENT_VIEWING_ENVIRONMENT";

  @Test
  @DisplayName(
      "Should copy the source into fragmented MP4 on the copy's standard output and its video"
          + " timing as framecrc when a Dolby Vision profile 5 sample starts with the RPU of the"
          + " picture before")
  void
      shouldCopyTheSourceIntoFragmentedMp4OnTheCopysStandardOutputAndItsVideoTimingAsFramecrcWhenADolbyVisionProfile5SampleStartsWithTheRpuOfThePictureBefore() {
    var request =
        request(encoding(profile5Encode(DolbyVisionRpuPlacement.STARTS_SAMPLE)))
            .height(720)
            .build();

    var pipe = rpuMovingPipe(builder.buildPipeline(job(request, "libx264")));

    assertThat(pipe.copyCommand())
        .isEqualTo(
            List.of(
                "ffmpeg",
                "-y",
                "-i",
                "/media/movie.mkv",
                "-map",
                "0:0",
                "-map",
                "0:a:0",
                "-map",
                "-0:s",
                "-map_metadata",
                "-1",
                "-map_chapters",
                "-1",
                "-copyts",
                "-avoid_negative_ts",
                "disabled",
                "-start_at_zero",
                "-max_muxing_queue_size",
                "128",
                "-max_interleave_delta",
                "10000000",
                "-c:v",
                "copy",
                "-strict",
                "unofficial",
                "-c:a",
                "aac",
                "-ac",
                "2",
                "-b:a",
                "128k",
                "-f",
                "mp4",
                "-movflags",
                "delay_moov+default_base_moof+frag_keyframe+skip_trailer",
                "-frag_duration",
                "1",
                "-movie_timescale",
                "1000000",
                "-flush_packets",
                "1",
                "pipe:1",
                "-map",
                "0:0",
                "-c:v",
                "copy",
                "-f",
                "framecrc",
                "-flush_packets",
                "1"));
  }

  @Test
  @DisplayName(
      "Should encode the copy read from standard input as one FFmpeg encodes the source, trimmed at"
          + " the start and copying the copy's audio, when a Dolby Vision profile 5 sample starts"
          + " with the RPU of the picture before")
  void
      shouldEncodeTheCopyReadFromStandardInputAsOneFfmpegEncodesTheSourceTrimmedAtTheStartAndCopyingTheCopysAudioWhenADolbyVisionProfile5SampleStartsWithTheRpuOfThePictureBefore() {
    var request =
        request(encoding(profile5Encode(DolbyVisionRpuPlacement.STARTS_SAMPLE)))
            .height(720)
            .build();
    var oneFfmpeg =
        command(
            request(
                    encoding(
                        profile5Encode(DolbyVisionRpuPlacement.STARTS_SAMPLE)
                            .sourcePacketsCarryPresentationTimes(false)))
                .height(720)
                .build(),
            "libx264");

    var pipe = rpuMovingPipe(builder.buildPipeline(job(request, "libx264")));

    assertThat(pipe.encodeCommand()).isEqualTo(asTheEncodeOfTheCopy(oneFfmpeg));
  }

  // One FFmpeg's command, changed only where ADR 0013 makes the encode of the copy differ: it reads
  // the copy's fragmented MP4 on its standard input at once, keeps the copy's timestamps, trims its
  // video at the attempt's start, and copies the AAC that the copy encoded.
  private static List<String> asTheEncodeOfTheCopy(List<String> oneFfmpeg) {
    var expected =
        new ArrayList<>(
            List.of(
                "ffmpeg",
                "-y",
                "-analyzeduration",
                "1",
                "-fpsprobesize",
                "0",
                "-f",
                "mp4",
                "-i",
                "pipe:0",
                "-map",
                "0:v:0",
                "-map",
                "0:a:0"));
    expected.addAll(oneFfmpeg.subList(oneFfmpeg.indexOf("-map_metadata"), oneFfmpeg.size()));
    expected.remove("-start_at_zero");
    expected.set(expected.indexOf("-vf") + 1, "trim=start=0," + videoFilter(oneFfmpeg));
    var audio = expected.indexOf("-c:a");
    expected.subList(audio, audio + 6).clear();
    expected.addAll(audio, List.of("-c:a", "copy", "-bsf:a", "aac_adtstoasc", "-b:a", "0"));
    return expected;
  }

  @Test
  @DisplayName(
      "Should write the framecrc of the chosen video stream's packets to the given URL as the copy's"
          + " last output when a Dolby Vision profile 5 source's RPUs may start samples")
  void
      shouldWriteTheFramecrcOfTheChosenVideoStreamsPacketsToTheGivenUrlAsTheCopysLastOutputWhenADolbyVisionProfile5SourcesRpusMayStartSamples() {
    var request =
        request(encoding(profile5Encode(DolbyVisionRpuPlacement.STARTS_SAMPLE).streamIndex(2)))
            .build();

    var pipe = rpuMovingPipe(builder.buildPipeline(job(request, "libx264")));

    assertThat(pipe.copyCommandWritingVideoTimingTo("unix:/tmp/sts1/v"))
        .endsWith(
            "pipe:1",
            "-map",
            "0:2",
            "-c:v",
            "copy",
            "-f",
            "framecrc",
            "-flush_packets",
            "1",
            "unix:/tmp/sts1/v");
  }

  @ParameterizedTest(name = "observed RPU {0}")
  @EnumSource(Presence.class)
  @DisplayName(
      "Should move each RPU that starts a sample behind the previous picture when the probe did not"
          + " observe where a Dolby Vision profile 5 source places its RPUs")
  void
      shouldMoveEachRpuThatStartsASampleBehindThePreviousPictureWhenTheProbeDidNotObserveWhereADolbyVisionProfile5SourcePlacesItsRpus(
          Presence observedRpu) {
    var source =
        new VideoDynamicRange(
            CompatibleBase.NONE,
            Presence.ABSENT,
            new VideoDynamicRange.DolbyVision.Present(
                Optional.empty(), observedRpu, DolbyVisionRpuPlacement.UNSPECIFIED));
    var request =
        request(
                encoding(
                    profile5Encode(DolbyVisionRpuPlacement.UNSPECIFIED).sourceDynamicRange(source)))
            .build();

    assertThat(builder.buildPipeline(job(request, "libx264")))
        .isInstanceOf(FfmpegPipeline.RpuMovingPipe.class);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = DolbyVisionRpuPlacement.class,
      names = {"STARTS_SAMPLE", "UNSPECIFIED"})
  @DisplayName(
      "Should encode the source's samples unchanged in one FFmpeg when the packets of a Dolby Vision"
          + " profile 5 source whose RPUs may start samples carry no presentation times")
  void
      shouldEncodeTheSourcesSamplesUnchangedInOneFfmpegWhenThePacketsOfADolbyVisionProfile5SourceWhoseRpusMayStartSamplesCarryNoPresentationTimes(
          DolbyVisionRpuPlacement placement) {
    var request =
        request(encoding(profile5Encode(placement).sourcePacketsCarryPresentationTimes(false)))
            .build();
    var requestWithoutDolbyVision =
        request(
                encoding(
                    profile5Encode(placement)
                        .sourcePacketsCarryPresentationTimes(false)
                        .sourceDynamicRange(withoutDolbyVision(profile5(placement)))))
            .build();

    var pipeline = builder.buildPipeline(job(request, "libx264"));

    assertThat(pipeline)
        .isEqualTo(new FfmpegPipeline.SingleProcess(command(requestWithoutDolbyVision, "libx264")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("profile5EncodesWithOneFactChanged")
  @DisplayName(
      "Should build the command of the source without Dolby Vision, whether or not its packets carry"
          + " presentation times, when one fact of the profile 5 source that the encode needs keeps"
          + " every RPU where it is")
  void
      shouldBuildTheCommandOfTheSourceWithoutDolbyVisionWhetherOrNotItsPacketsCarryPresentationTimesWhenOneFactOfTheProfile5SourceThatTheEncodeNeedsKeepsEveryRpuWhereItIs(
          UnaryOperator<VideoDecision.Encode.EncodeBuilder> changeOneFact) {
    var source = changeOneFact.apply(profile5Encode(DolbyVisionRpuPlacement.STARTS_SAMPLE));
    var withoutDolbyVision =
        command(
            request(
                    encoding(
                        changeOneFact
                            .apply(profile5Encode(DolbyVisionRpuPlacement.STARTS_SAMPLE))
                            .sourceDynamicRange(
                                withoutDolbyVision(source.build().sourceDynamicRange()))))
                .build(),
            "libx264");

    for (var presentationTimes : List.of(true, false)) {
      var request =
          request(
                  encoding(
                      changeOneFact
                          .apply(profile5Encode(DolbyVisionRpuPlacement.STARTS_SAMPLE))
                          .sourcePacketsCarryPresentationTimes(presentationTimes)))
              .build();

      assertThat(builder.buildPipeline(job(request, "libx264")))
          .as("packets carry presentation times: %s", presentationTimes)
          .isEqualTo(new FfmpegPipeline.SingleProcess(withoutDolbyVision));
    }
  }

  // Each case changes exactly one of the facts that make a source need moved RPUs.
  private static Stream<Named<UnaryOperator<VideoDecision.Encode.EncodeBuilder>>>
      profile5EncodesWithOneFactChanged() {
    return Stream.of(
        oneFactChanged(
            "every RPU after its picture",
            encode ->
                encode.sourceDynamicRange(profile5(DolbyVisionRpuPlacement.AFTER_OWN_PICTURE))),
        oneFactChanged(
            "PQ compatible base, as profile 8.1",
            encode -> encode.sourceDynamicRange(rpusStartingSamplesOver(CompatibleBase.PQ))),
        oneFactChanged(
            "HLG compatible base, as profile 8.4",
            encode -> encode.sourceDynamicRange(rpusStartingSamplesOver(CompatibleBase.HLG))),
        oneFactChanged(
            "SDR compatible base, as profile 9",
            encode -> encode.sourceDynamicRange(rpusStartingSamplesOver(CompatibleBase.SDR))),
        oneFactChanged(
            "Dolby Vision absent",
            encode ->
                encode.sourceDynamicRange(
                    new VideoDynamicRange(
                        CompatibleBase.NONE,
                        Presence.ABSENT,
                        new VideoDynamicRange.DolbyVision.Absent()))),
        oneFactChanged(
            "Dolby Vision unspecified",
            encode ->
                encode.sourceDynamicRange(
                    new VideoDynamicRange(
                        CompatibleBase.NONE,
                        Presence.ABSENT,
                        new VideoDynamicRange.DolbyVision.Unspecified()))),
        oneFactChanged(
            "source codec family unspecified",
            encode -> encode.sourceCodecFamily(Optional.empty())),
        oneFactChanged(
            "H.264 source", encode -> encode.sourceCodecFamily(Optional.of(CodecFamily.H264))),
        oneFactChanged(
            "AV1 source", encode -> encode.sourceCodecFamily(Optional.of(CodecFamily.AV1))));
  }

  private static Named<UnaryOperator<VideoDecision.Encode.EncodeBuilder>> oneFactChanged(
      String fact, UnaryOperator<VideoDecision.Encode.EncodeBuilder> change) {
    return Named.of(fact, change);
  }

  private static VideoDynamicRange rpusStartingSamplesOver(CompatibleBase compatibleBase) {
    return new VideoDynamicRange(
        compatibleBase, Presence.ABSENT, dolbyVisionPresent(DolbyVisionRpuPlacement.STARTS_SAMPLE));
  }

  private static VideoDynamicRange withoutDolbyVision(VideoDynamicRange source) {
    return new VideoDynamicRange(
        source.compatibleBase(), source.hdr10Plus(), new VideoDynamicRange.DolbyVision.Absent());
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName(
      "Should copy the video in one FFmpeg when a job that copies video carries the encode decision"
          + " of a Dolby Vision profile 5 source whose RPUs start their samples")
  void
      shouldCopyTheVideoInOneFfmpegWhenAJobThatCopiesVideoCarriesTheEncodeDecisionOfADolbyVisionProfile5SourceWhoseRpusStartTheirSamples(
          TranscodeMode mode) {
    var request =
        request(
                decision(mode)
                    .videoDecision(profile5Encode(DolbyVisionRpuPlacement.STARTS_SAMPLE).build())
                    .build())
            .build();

    assertThat(command(request, "copy")).containsSequence("-c:v", "copy");
  }

  @Test
  @DisplayName(
      "Should seek the copy and encode its audio there when a replacement attempt encodes a profile"
          + " 5 source whose RPU starts its sample")
  void
      shouldSeekTheCopyAndEncodeItsAudioThereWhenAReplacementAttemptEncodesAProfile5SourceWhoseRpuStartsItsSample() {
    var request =
        request(encoding(profile5Encode(DolbyVisionRpuPlacement.STARTS_SAMPLE)))
            .startSequenceNumber(3)
            .build();

    var pipe = rpuMovingPipe(builder.buildPipeline(job(request, "libx264")));

    assertThat(pipe.copyCommand())
        .containsSequence("ffmpeg", "-y", "-ss", "12", "-i", "/media/movie.mkv", "-map", "0:0")
        .containsSequence("-c:a", "aac", "-ac", "2", "-b:a", "128k", "-f", "mp4");
  }

  @Test
  @DisplayName(
      "Should trim the encode's video at the seek without seeking the copy again when a replacement"
          + " attempt encodes a profile 5 source whose RPU starts its sample")
  void
      shouldTrimTheEncodesVideoAtTheSeekWithoutSeekingTheCopyAgainWhenAReplacementAttemptEncodesAProfile5SourceWhoseRpuStartsItsSample() {
    var request =
        request(encoding(profile5Encode(DolbyVisionRpuPlacement.STARTS_SAMPLE)))
            .height(720)
            .startSequenceNumber(3)
            .build();

    var pipe = rpuMovingPipe(builder.buildPipeline(job(request, "libx264")));

    assertThat(pipe.encodeCommand()).doesNotContain("-ss", "-start_at_zero");
    assertThat(videoFilter(pipe.encodeCommand()))
        .isEqualTo("trim=start=12,scale=-2:720," + TONE_MAPPED_TO_SDR);
  }

  @Test
  @DisplayName(
      "Should copy the copied source's audio untrimmed when the encode of a profile 5 source whose"
          + " RPU starts its sample seeks")
  void
      shouldCopyTheCopiedSourcesAudioUntrimmedWhenTheEncodeOfAProfile5SourceWhoseRpuStartsItsSampleSeeks() {
    var decision =
        decision(TranscodeMode.VIDEO_TRANSCODE)
            .videoDecision(profile5Encode(DolbyVisionRpuPlacement.STARTS_SAMPLE).build())
            .build();
    var request = request(decision).startSequenceNumber(3).build();

    var pipe = rpuMovingPipe(builder.buildPipeline(job(request, "libx264")));

    assertThat(pipe.copyCommand())
        .containsSequence("-c:a", "copy", "-bsf:a", "aac_adtstoasc", "-b:a", "0", "-f", "mp4");
    assertThat(pipe.encodeCommand())
        .containsSequence("-map", "0:v:0", "-map", "0:a:0")
        .containsSequence("-c:a", "copy", "-bsf:a", "aac_adtstoasc", "-b:a", "0", "-r:v:0")
        .doesNotContain("-ss");
  }

  @Test
  @DisplayName(
      "Should map no audio in either FFmpeg when the encode of a profile 5 source whose RPU starts"
          + " its sample has none")
  void
      shouldMapNoAudioInEitherFfmpegWhenTheEncodeOfAProfile5SourceWhoseRpuStartsItsSampleHasNone() {
    var decision =
        decision(TranscodeMode.FULL_TRANSCODE)
            .videoDecision(profile5Encode(DolbyVisionRpuPlacement.STARTS_SAMPLE).build())
            .audioDecision(NO_AUDIO)
            .build();

    var pipe = rpuMovingPipe(builder.buildPipeline(job(request(decision).build(), "libx264")));

    assertThat(pipe.copyCommand()).doesNotContain("0:a:0");
    assertThat(pipe.encodeCommand()).doesNotContain("0:a:0", "-c:a");
  }

  @ParameterizedTest(name = "{0}")
  @NullSource
  @ValueSource(strings = {"aac", "ac3", "eac3", "flac", "alac", "opus"})
  @DisplayName(
      "Should copy the audio into the copy of the source, and copy it from there, whatever the"
          + " codec, when the encode of a profile 5 source whose RPU starts its sample copies audio")
  void
      shouldCopyTheAudioIntoTheCopyOfTheSourceAndCopyItFromThereWhateverTheCodecWhenTheEncodeOfAProfile5SourceWhoseRpuStartsItsSampleCopiesAudio(
          String audioCodec) {
    var decision =
        decision(TranscodeMode.VIDEO_TRANSCODE)
            .videoDecision(profile5Encode(DolbyVisionRpuPlacement.STARTS_SAMPLE).build())
            .audioDecision(copiedAudio(audioCodec))
            .build();

    var pipe = rpuMovingPipe(builder.buildPipeline(job(request(decision).build(), "libx264")));

    assertThat(pipe.copyCommand())
        .containsSequence("-map", "0:a:0")
        .containsSequence("-c:a", "copy");
    assertThat(pipe.encodeCommand())
        .containsSequence("-map", "0:a:0")
        .containsSequence("-c:a", "copy");
    assertThat(pipe.encodeCommand().contains("aac_adtstoasc"))
        .as("only a copy of AAC passes through aac_adtstoasc")
        .isEqualTo("aac".equals(audioCodec));
  }

  @ParameterizedTest(name = "{0} as {1}")
  @CsvSource({"aac, aac", "ac3, ac3", "eac3, eac3", "flac, flac", "alac, alac", "opus, libopus"})
  @DisplayName(
      "Should encode the audio in the copy of the source, with libopus for Opus, when the encode of"
          + " a profile 5 source whose RPU starts its sample encodes audio")
  void
      shouldEncodeTheAudioInTheCopyOfTheSourceWithLibopusForOpusWhenTheEncodeOfAProfile5SourceWhoseRpuStartsItsSampleEncodesAudio(
          String audioCodec, String encoder) {
    var decision =
        decision(TranscodeMode.FULL_TRANSCODE)
            .videoDecision(profile5Encode(DolbyVisionRpuPlacement.STARTS_SAMPLE).build())
            .audioDecision(
                AudioDecision.builder()
                    .mode(AudioMode.TRANSCODE)
                    .codec(audioCodec)
                    .channels(2)
                    .bitrate(128_000)
                    .build())
            .build();

    var pipe = rpuMovingPipe(builder.buildPipeline(job(request(decision).build(), "libx264")));

    assertThat(pipe.copyCommand()).containsSequence("-c:a", encoder, "-ac", "2", "-b:a", "128k");
    assertThat(pipe.encodeCommand()).containsSequence("-c:a", "copy").doesNotContain("-ac");
  }

  private static VideoDecision.Encode.EncodeBuilder profile5Encode(
      DolbyVisionRpuPlacement rpuPlacement) {
    return VideoDecisionFixtures.encodeBuilder()
        .sourceCodecFamily(Optional.of(CodecFamily.HEVC))
        .sourceDynamicRange(profile5(rpuPlacement))
        .sourcePacketsCarryPresentationTimes(true);
  }

  private static VideoDynamicRange profile5(DolbyVisionRpuPlacement rpuPlacement) {
    return new VideoDynamicRange(
        CompatibleBase.NONE, Presence.ABSENT, dolbyVisionPresent(rpuPlacement));
  }

  private static VideoDynamicRange.DolbyVision.Present dolbyVisionPresent(
      DolbyVisionRpuPlacement rpuPlacement) {
    return new VideoDynamicRange.DolbyVision.Present(
        Optional.empty(), Presence.PRESENT, rpuPlacement);
  }

  private static FfmpegPipeline.RpuMovingPipe rpuMovingPipe(FfmpegPipeline pipeline) {
    return assertThat(pipeline)
        .asInstanceOf(InstanceOfAssertFactories.type(FfmpegPipeline.RpuMovingPipe.class))
        .actual();
  }
}
