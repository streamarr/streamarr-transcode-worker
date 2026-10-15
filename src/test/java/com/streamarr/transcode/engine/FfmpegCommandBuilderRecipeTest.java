package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.FfmpegRecordings.Recording;
import com.streamarr.transcode.fixtures.RecordingFixtures;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Pins the worker's FFmpeg command to the command lines the fMP4 recordings were made with, so that
 * the recordings and the worker cannot drift apart silently.
 */
@Tag("UnitTest")
@DisplayName("FFmpeg Command Builder Recipe Tests")
class FfmpegCommandBuilderRecipeTest {

  // What the recorder passes the encoders (src/test/fmp4-recorder/record-fixtures.sh).
  private static final int RECORDED_HEIGHT = 36;
  private static final long RECORDED_VIDEO_BITRATE = 6000;
  private static final AudioDecision ENCODED_AUDIO =
      AudioDecision.builder()
          .mode(AudioMode.TRANSCODE)
          .codec("aac")
          .channels(1)
          .bitrate(8000L)
          .build();
  // The presets Automatic resolves to for each software encoder (ADR 0044).
  private static final Map<String, EncoderPreset> RECORDED_PRESETS =
      Map.of(
          "libx264", EncoderPreset.FASTER,
          "libsvtav1", EncoderPreset.FASTER,
          "libx265", EncoderPreset.VERYFAST);
  private static final AudioDecision COPIED_AUDIO =
      AudioDecision.builder().mode(AudioMode.COPY).codec("aac").channels(1).bitrate(0L).build();
  // The fixture-only additions the fixture README lists: quiet, non-interactive logging and a
  // single-threaded encoder, so that a re-recording reproduces the same bytes.
  private static final List<List<String>> FIXTURE_ONLY_OPTIONS =
      List.of(
          List.of("-hide_banner"),
          List.of("-nostdin"),
          List.of("-loglevel", "error"),
          List.of("-threads", "1"));
  private static final String SVT_AV1_SINGLE_THREAD = ":lp=1";
  private static final String X265_SINGLE_THREAD_AND_QUIET =
      ":pools=none:frame-threads=1:log-level=error";

  static Stream<Recording> recordingsOfTheRecipe() {
    return FfmpegRecordings.recordings().stream()
        .filter(recording -> recording.recipeDeviation().isEmpty());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordingsOfTheRecipe")
  @DisplayName(
      "Should build the command a recording ran, without its fixture-only additions, when the"
          + " recording follows the recipe")
  void
      shouldBuildTheCommandARecordingRanWithoutItsFixtureOnlyAdditionsWhenTheRecordingFollowsTheRecipe(
          Recording recording) {
    var builder =
        new FfmpegCommandBuilder(
            "ffmpeg", Duration.of(recording.fragmentationTargetMicros(), ChronoUnit.MICROS));

    var command = builder.buildCommand(jobRecordedAs(recording));

    assertThat(command)
        .containsExactlyElementsOf(withoutFixtureOnlyAdditions(recording.ffmpegArguments()));
  }

  @Test
  @DisplayName(
      "Should pin each verified encoder and the stream copy when recordings follow the recipe")
  void shouldPinEachVerifiedEncoderAndTheStreamCopyWhenRecordingsFollowTheRecipe() {
    assertThat(recordingsOfTheRecipe().map(Recording::videoEncoder))
        .contains("libx264", "libsvtav1", "libx265", "copy");
  }

  private static TranscodeJob jobRecordedAs(Recording recording) {
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(Path.of("src", recording.source().file()))
            .startSequenceNumber(recording.startSequenceNumber())
            .mediaSegmentCount(recording.mediaSegmentCount())
            .targetSegmentDuration(recording.period())
            .framerate(recording.source().videoFrameRate())
            .transcodeDecision(decisionRecordedAs(recording))
            .encoderPreset(RECORDED_PRESETS.get(recording.videoEncoder()))
            .height(RECORDED_HEIGHT)
            .bitrate(RECORDED_VIDEO_BITRATE)
            .build();
    return TranscodeJob.builder().request(request).videoEncoder(recording.videoEncoder()).build();
  }

  private static TranscodeDecision decisionRecordedAs(Recording recording) {
    var decision = TranscodeDecision.builder().subtitleDecision(SubtitleDecisions.EXCLUDED);
    return switch (recording.mode()) {
      case ENCODE ->
          decision
              .transcodeMode(TranscodeMode.FULL_TRANSCODE)
              .videoDecision(encodeRecordedBy(recording.videoEncoder()))
              .audioDecision(ENCODED_AUDIO)
              .build();
      case COPY ->
          decision
              .transcodeMode(TranscodeMode.REMUX)
              .videoDecision(
                  new VideoDecision.Copy(RecordingFixtures.ENCODED_RECORDING_CODEC_STRING))
              .audioDecision(COPIED_AUDIO)
              .build();
    };
  }

  // The codec profile, tier and level that each encoder's recordings force.
  private static VideoDecision encodeRecordedBy(String encoder) {
    return switch (encoder) {
      case "libsvtav1" ->
          VideoDecision.Encode.builder()
              .codecProfile(VideoCodecProfile.AV1_MAIN)
              .tier(Optional.of(VideoCodecTier.MAIN))
              .level(20)
              .codecString("av01.0.00M.08")
              .build();
      case "libx265" ->
          VideoDecision.Encode.builder()
              .codecProfile(VideoCodecProfile.HEVC_MAIN)
              .tier(Optional.of(VideoCodecTier.MAIN))
              .level(10)
              .codecString("hvc1.1.6.L30.90")
              .build();
      default -> VideoDecisionFixtures.encodeBuilder().build();
    };
  }

  private static List<String> withoutFixtureOnlyAdditions(List<String> recorded) {
    var command = new ArrayList<>(recorded);
    for (var option : FIXTURE_ONLY_OPTIONS) {
      var start = Collections.indexOfSubList(command, option);
      assertThat(start).as("fixture-only option %s", option).isNotNegative();
      command.subList(start, start + option.size()).clear();
    }

    removeParameter(command, "-svtav1-params", SVT_AV1_SINGLE_THREAD);
    removeParameter(command, "-x265-params", X265_SINGLE_THREAD_AND_QUIET);
    return command;
  }

  private static void removeParameter(List<String> command, String option, String parameter) {
    var parameters = command.indexOf(option) + 1;
    if (parameters > 0) {
      command.set(parameters, command.get(parameters).replace(parameter, ""));
    }
  }
}
