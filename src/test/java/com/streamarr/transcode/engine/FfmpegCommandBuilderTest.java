package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("FFmpeg Command Builder Tests")
class FfmpegCommandBuilderTest {

  private static final SubtitleDecision EXCLUDED_SUBTITLES =
      new SubtitleDecision(
          SubtitleMode.EXCLUDE, Optional.empty(), OptionalInt.empty(), Optional.empty());

  private static final AudioDecision NO_AUDIO =
      AudioDecision.builder().mode(AudioMode.NONE).codec(null).channels(0).bitrate(0L).build();

  private final FfmpegCommandBuilder builder = new FfmpegCommandBuilder("ffmpeg");

  private List<String> command(TranscodeRequest request, String videoEncoder) {
    return builder.buildCommand(
        TranscodeJob.builder()
            .request(request)
            .videoEncoder(videoEncoder)
            .outputDir(Path.of("/tmp/session-123"))
            .build());
  }

  private static TranscodeRequest.TranscodeRequestBuilder request(TranscodeMode mode) {
    return request(decision(mode).build());
  }

  private static TranscodeRequest.TranscodeRequestBuilder request(TranscodeDecision decision) {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(Path.of("/media/movie.mkv"))
        .targetSegmentDuration(6)
        .framerate(23.976)
        .transcodeDecision(decision)
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
        .audioDecision(audio)
        .subtitleDecision(EXCLUDED_SUBTITLES)
        .containerFormat(ContainerFormat.MPEGTS);
  }

  private static AudioDecision copiedAudio(String codec) {
    return AudioDecision.builder()
        .mode(AudioMode.COPY)
        .codec(codec)
        .channels(2)
        .bitrate(0L)
        .build();
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

  @ParameterizedTest(name = "{0}")
  @MethodSource("fullTranscodeExpectedFlags")
  @DisplayName("Should include expected flags when mode is full transcode")
  void shouldIncludeExpectedFlagsWhenModeIsFullTranscode(String scenario, String... expectedFlags) {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "libx264");

    assertThat(cmd).contains(expectedFlags);
  }

  static Stream<Arguments> fullTranscodeExpectedFlags() {
    return Stream.of(
        Arguments.of("scale filter", new String[] {"-vf", "scale=-2:1080"}),
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
        .contains("-vf", "scale=-2:720")
        .contains("-b:v", "3000000")
        .contains("-bufsize", "6000000");
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

    assertThat(cmd)
        .containsSubsequence("-c:v", "copy")
        .containsSubsequence("-c:a", "aac")
        .containsSubsequence("-b:a", "128k");
  }

  @Test
  @DisplayName("Should include H264 MPEGTS args when full transcode targets H264")
  void shouldIncludeH264MpegtsArgsWhenFullTranscodeTargetsH264() {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "libx264");

    assertThat(cmd)
        .containsSubsequence("-c:v", "libx264")
        .containsSubsequence("-c:a", "aac")
        .containsSubsequence("-b:a", "128k")
        .containsSubsequence("-hls_segment_type", "mpegts");
  }

  @Test
  @DisplayName("Should include AV1 fMP4 args when full transcode targets AV1")
  void shouldIncludeAv1Fmp4ArgsWhenFullTranscodeTargetsAv1() {
    var cmd =
        command(
            request(
                    decision(TranscodeMode.FULL_TRANSCODE)
                        .containerFormat(ContainerFormat.FMP4)
                        .build())
                .build(),
            "libsvtav1");

    assertThat(cmd)
        .contains("-c:v", "libsvtav1")
        .contains("-hls_segment_type", "fmp4")
        .contains("-hls_fmp4_init_filename", "init.mp4");
  }

  @Test
  @DisplayName("Should use force keyframes when encoder is libx264")
  void shouldUseForceKeyframesWhenEncoderIsLibx264() {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "libx264");

    assertThat(cmd)
        .isNotEmpty()
        .anyMatch(s -> s.startsWith("expr:gte(t,n_forced*"))
        .contains("-sc_threshold:v:0", "0");
  }

  @Test
  @DisplayName("Should use GOP size when encoder is NVENC")
  void shouldUseGopSizeWhenEncoderIsNvenc() {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "h264_nvenc");

    assertThat(cmd).isNotEmpty().contains("-g:v:0").doesNotContain("-force_key_frames:0");
  }

  @Test
  @DisplayName("Should use GOP size when encoder is libsvtav1")
  void shouldUseGopSizeWhenEncoderIsLibsvtav1() {
    var cmd =
        command(
            request(
                    decision(TranscodeMode.FULL_TRANSCODE)
                        .containerFormat(ContainerFormat.FMP4)
                        .build())
                .build(),
            "libsvtav1");

    assertThat(cmd).contains("-g:v:0");
  }

  @Test
  @DisplayName("Should place seek before input when seek position is non-zero")
  void shouldPlaceSeekBeforeInputWhenSeekPositionIsNonZero() {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).seekPosition(300).build(), "libx264");

    int ssIndex = cmd.indexOf("-ss");
    int iIndex = cmd.indexOf("-i");
    assertThat(ssIndex).isGreaterThan(-1);
    assertThat(iIndex).isGreaterThan(ssIndex);
    assertThat(cmd.get(ssIndex + 1)).isEqualTo("300");
  }

  @Test
  @DisplayName("Should not include seek when position is zero")
  void shouldNotIncludeSeekWhenPositionIsZero() {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "libx264");

    assertThat(cmd).isNotEmpty().doesNotContain("-ss");
  }

  @Test
  @DisplayName("Should include fMP4 segment options when container is fMP4")
  void shouldIncludeFmp4SegmentOptionsWhenContainerIsFmp4() {
    var cmd =
        command(
            request(
                    decision(TranscodeMode.FULL_TRANSCODE)
                        .containerFormat(ContainerFormat.FMP4)
                        .build())
                .build(),
            "libsvtav1");

    assertThat(cmd).contains("-hls_segment_options", "movflags=+frag_discont");
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

  @Test
  @DisplayName("Should include HLS temp file flag when building command")
  void shouldIncludeHlsTempFileFlagWhenBuildingCommand() {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "libx264");

    assertThat(cmd).isNotEmpty().anyMatch(s -> s.contains("temp_file"));
  }

  @Test
  @DisplayName("Should start with FFmpeg binary when building command")
  void shouldStartWithFfmpegBinaryWhenBuildingCommand() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd.getFirst()).isEqualTo("ffmpeg");
  }

  @Test
  @DisplayName("Should set TS segment filename pattern when container is MPEGTS")
  void shouldSetTsSegmentFilenamePatternWhenContainerIsMpegts() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd).contains("-hls_segment_filename");
    int idx = cmd.indexOf("-hls_segment_filename");
    assertThat(cmd.get(idx + 1)).contains("segment%d.ts");
  }

  @Test
  @DisplayName("Should set m4s segment filename pattern when container is fMP4")
  void shouldSetM4sSegmentFilenamePatternWhenContainerIsFmp4() {
    var cmd =
        command(
            request(
                    decision(TranscodeMode.FULL_TRANSCODE)
                        .containerFormat(ContainerFormat.FMP4)
                        .build())
                .build(),
            "libsvtav1");

    int idx = cmd.indexOf("-hls_segment_filename");
    assertThat(cmd.get(idx + 1)).contains("segment%d.m4s");
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
  @DisplayName("Should output to HLS format when building command")
  void shouldOutputToHlsFormatWhenBuildingCommand() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd).contains("-f", "hls");
  }

  @Test
  @DisplayName("Should use force keyframes when encoder is libx265")
  void shouldUseForceKeyframesWhenEncoderIsLibx265() {
    var cmd =
        command(
            request(
                    decision(TranscodeMode.FULL_TRANSCODE)
                        .containerFormat(ContainerFormat.FMP4)
                        .build())
                .build(),
            "libx265");

    assertThat(cmd)
        .isNotEmpty()
        .anyMatch(s -> s.startsWith("expr:gte(t,n_forced*"))
        .doesNotContain("-sc_threshold:v:0");
  }

  @Test
  @DisplayName("Should use force keyframes when encoder is VAAPI")
  void shouldUseForceKeyframesWhenEncoderIsVaapi() {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "h264_vaapi");

    assertThat(cmd).isNotEmpty().anyMatch(s -> s.startsWith("expr:gte(t,n_forced*"));
  }

  @Test
  @DisplayName("Should include overwrite flag but not nostdin when building command")
  void shouldIncludeOverwriteFlagButNotNostdinWhenBuildingCommand() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd).isNotEmpty().contains("-y").doesNotContain("-nostdin");
  }

  @Test
  @DisplayName("Should set HLS time when building command")
  void shouldSetHlsTimeWhenBuildingCommand() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd).contains("-hls_time", "6");
  }

  @Test
  @DisplayName("Should include start number when start number is non-zero")
  void shouldIncludeStartNumberWhenStartNumberIsNonZero() {
    var cmd = command(request(TranscodeMode.REMUX).startSequenceNumber(5).build(), "copy");

    assertThat(cmd).contains("-start_number", "5");
  }

  @Test
  @DisplayName("Should not include start number when start number is zero")
  void shouldNotIncludeStartNumberWhenStartNumberIsZero() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd).isNotEmpty().doesNotContain("-start_number");
  }

  @Test
  @DisplayName("Should map first video and audio streams when building command")
  void shouldMapFirstVideoAndAudioStreamsWhenBuildingCommand() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd).containsSequence("-map", "0:v:0", "-map", "0:a:0");
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
        .contains("-vf", "scale=-2:1080")
        .doesNotContain("-ac")
        .doesNotContain("-b:a");
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

  @Test
  @DisplayName("Should only add forced IDR when encoder is in neither keyframe set")
  void shouldOnlyAddForcedIdrWhenEncoderIsInNeitherKeyframeSet() {
    var cmd = command(request(TranscodeMode.FULL_TRANSCODE).build(), "h264_videotoolbox");

    assertThat(cmd)
        .isNotEmpty()
        .contains("-forced-idr", "1")
        .doesNotContain("-g:v:0")
        .noneMatch(s -> s.startsWith("-force_key_frames"));
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

    assertThat(cmd)
        .containsSubsequence("-c:a", "ac3")
        .containsSubsequence("-ac", "6")
        .containsSubsequence("-b:a", "384k");
  }

  @Test
  @DisplayName("Should transcode to E-AC-3 7.1 when audio decision is E-AC-3 transcode")
  void shouldTranscodeToEac3SurroundWhenAudioDecisionIsEac3Transcode() {
    var audio = new AudioDecision(AudioMode.TRANSCODE, "eac3", 8, 512_000L);
    var cmd =
        command(
            request(decision(TranscodeMode.AUDIO_TRANSCODE).audioDecision(audio).build()).build(),
            "copy");

    assertThat(cmd)
        .containsSubsequence("-c:a", "eac3")
        .containsSubsequence("-ac", "8")
        .containsSubsequence("-b:a", "512k");
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

    assertThat(cmd)
        .isNotEmpty()
        .contains("-c:a", "copy")
        .doesNotContain("-ac")
        .doesNotContain("-b:a");
  }

  // --- Map ordering ---

  @Test
  @DisplayName("Should map video, audio, and exclude subtitles in correct order")
  void shouldMapVideoAudioAndExcludeSubtitlesInCorrectOrder() {
    var cmd = command(request(TranscodeMode.REMUX).build(), "copy");

    assertThat(cmd).containsSequence("-map", "0:v:0", "-map", "0:a:0", "-map", "-0:s");
  }

  @Test
  @DisplayName("Should map video and exclude subtitles without audio when audio mode is none")
  void shouldMapVideoAndExcludeSubtitlesWithoutAudioWhenAudioModeIsNone() {
    var cmd =
        command(
            request(decision(TranscodeMode.FULL_TRANSCODE).audioDecision(NO_AUDIO).build()).build(),
            "libx264");

    assertThat(cmd).containsSequence("-map", "0:v:0", "-map", "-0:s").doesNotContain("0:a:0");
  }
}
