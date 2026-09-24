package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.FfmpegRecordings.Recording;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
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
  private static final AudioDecision COPIED_AUDIO =
      AudioDecision.builder().mode(AudioMode.COPY).codec("aac").channels(1).bitrate(0L).build();
  private static final SubtitleDecision EXCLUDED_SUBTITLES =
      new SubtitleDecision(
          SubtitleMode.EXCLUDE, Optional.empty(), OptionalInt.empty(), Optional.empty());

  // The fixture-only additions the fixture README lists: quiet, non-interactive logging and a
  // single-threaded encoder, so that a re-recording reproduces the same bytes.
  private static final List<List<String>> FIXTURE_ONLY_OPTIONS =
      List.of(
          List.of("-hide_banner"),
          List.of("-nostdin"),
          List.of("-loglevel", "error"),
          List.of("-threads", "1"));
  private static final String SVT_AV1_SINGLE_THREAD = ":lp=1";

  static Stream<Recording> recordingsOfTheRecipe() {
    return FfmpegRecordings.recordings().stream()
        .filter(recording -> recording.recipeDeviation().isEmpty());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordingsOfTheRecipe")
  @DisplayName(
      "Should build the command a recording ran, without its fixture-only additions, when the"
          + " recording follows the recipe")
  void shouldBuildTheCommandARecordingRanWhenTheRecordingFollowsTheRecipe(Recording recording) {
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
    assertThat(recordingsOfTheRecipe().map(recording -> recording.encoder().orElse("copy")))
        .contains("libx264", "libsvtav1", "copy");
  }

  private static TranscodeJob jobRecordedAs(Recording recording) {
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(Path.of("src", recording.source().file()))
            .seekPosition(recording.seekSeconds())
            .targetSegmentDuration(recording.period())
            .framerate(recording.source().videoFrameRate())
            .transcodeDecision(decisionRecordedAs(recording))
            .height(RECORDED_HEIGHT)
            .bitrate(RECORDED_VIDEO_BITRATE)
            .build();
    return TranscodeJob.builder()
        .request(request)
        .videoEncoder(recording.encoder().orElse("copy"))
        .build();
  }

  private static TranscodeDecision decisionRecordedAs(Recording recording) {
    var decision = TranscodeDecision.builder().subtitleDecision(EXCLUDED_SUBTITLES);
    return switch (recording.mode()) {
      case "encode" ->
          decision.transcodeMode(TranscodeMode.FULL_TRANSCODE).audioDecision(ENCODED_AUDIO).build();
      case "copy" ->
          decision.transcodeMode(TranscodeMode.REMUX).audioDecision(COPIED_AUDIO).build();
      default -> throw new IllegalArgumentException("Unknown recording mode: " + recording.mode());
    };
  }

  private static List<String> withoutFixtureOnlyAdditions(List<String> recorded) {
    var command = new ArrayList<>(recorded);
    for (var option : FIXTURE_ONLY_OPTIONS) {
      var start = Collections.indexOfSubList(command, option);
      assertThat(start).as("fixture-only option %s", option).isNotNegative();
      command.subList(start, start + option.size()).clear();
    }

    var svtAv1Parameters = command.indexOf("-svtav1-params") + 1;
    if (svtAv1Parameters > 0) {
      command.set(
          svtAv1Parameters, command.get(svtAv1Parameters).replace(SVT_AV1_SINGLE_THREAD, ""));
    }

    return command;
  }
}
