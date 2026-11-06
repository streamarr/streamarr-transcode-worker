package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** A held encode's search for the video's last keyframe, run by real FFmpeg. */
@Tag("SmokeTest")
@DisplayName("Keyframe Search Smoke Tests")
class KeyframeSearchSmokeTest {

  @TempDir Path root;

  // At 4 fps with three B-frames in a pyramid, each picture decodes 0.5 s before it presents. The
  // attempt for the segment at 18 s reads back from 14 s, so the search reads from 10 s to 14 s and
  // then from 2 s to 10 s. The keyframe that presents at 10 s decodes at 9.5 s, so FFmpeg's scan of
  // an MPEG-TS source reads it in the interval before the one it presents in. An MPEG-TS seek to
  // the keyframe at 8 s lands on decode times, so the attempt reads from its decode time, 7.5 s; an
  // MP4 seek lands on presentation times, so the attempt reads from 8 s, where a seek to 7.5 s
  // would land on the keyframe at 6 s.
  @ParameterizedTest(name = "{0}")
  @CsvSource({"mpegts, ts, 7.5", "mp4, mp4, 8"})
  @DisplayName(
      "Should read from where a seek lands on the keyframe 0.5 s before the last when the last"
          + " keyframe decodes in the interval before the one it presents in")
  void shouldReadFromWhereASeekLandsOnTheKeyframeBeforeTheLastWhenTheLastDecodesBeforeItsInterval(
      String format, String extension, BigDecimal expectedSeconds) throws Exception {
    var source = delayedPicturesWithKeyframesUpToTenSeconds(format, extension);

    var readPosition =
        engine()
            .heldPictureReadPosition(heldRequest(source, OptionalInt.empty()), _ -> {})
            .orElseThrow();

    assertThat(readPosition)
        .as("where a seek lands on the keyframe that presents at 8 s")
        .isEqualTo(Duration.ofNanos(expectedSeconds.movePointRight(9).longValueExact()));
  }

  // The timeline's zero is the earliest start among the streams that a command reads. The 440 Hz
  // tone starts 2 s before the video's first picture, and the 880 Hz tone with the video, 0.02 s
  // before its first picture once the AAC encoder's priming is counted. The video's one keyframe
  // decodes 0.5 s before it presents. On the timeline of an attempt that reads the 440 Hz tone, it
  // presents at 2.021 s and decodes at 1.521 s, and an MPEG-TS seek to its presentation time lands
  // after it, so the attempt reads from its decode time. On the timeline of one that reads the
  // 880 Hz tone, it presents at 0.021 s, where a seek lands on it; a seek to 1.521 s there would
  // land after the keyframe and decode no picture.
  @ParameterizedTest(name = "audio stream {0}")
  @CsvSource({"1, 1.521333333", "2, 0.021333333"})
  @DisplayName(
      "Should read from where the attempt's own seek reads the video's only keyframe when an audio"
          + " stream starts before the video")
  void shouldReadFromWhereTheAttemptsOwnSeekReadsTheOnlyKeyframeWhenAnAudioStreamStartsFirst(
      int audioStream, BigDecimal expectedSeconds) throws Exception {
    var source = tonesThatStartBeforeTheVideo();

    var readPosition =
        engine()
            .heldPictureReadPosition(heldRequest(source, OptionalInt.of(audioStream)), _ -> {})
            .orElseThrow();

    assertThat(readPosition)
        .as("where a seek on the attempt's own timeline reads the video's keyframe")
        .isEqualTo(Duration.ofNanos(expectedSeconds.movePointRight(9).longValueExact()));
  }

  // A raw H.264 stream carries no index and its demuxer cannot seek: a scan from a seek reads no
  // packet. The attempt for the segment at 24 s reads back from 14 s, so the search reads from 4 s
  // to 14 s and then from 0 s to 4 s, where it finds the keyframes that decode before 4 s. A seek
  // to the one that decodes at 1.92 s would read no picture to hold.
  @Test
  @DisplayName(
      "Should read from the start of the timeline when the demuxer of a raw H.264 stream cannot seek"
          + " to the keyframe that the search finds")
  void shouldReadFromTheStartOfTheTimelineWhenARawStreamsDemuxerCannotSeekToTheFoundKeyframe()
      throws Exception {
    var source = delayedPicturesWithKeyframesUpToTenSeconds("h264", "h264");
    var request = heldRequest(source, OptionalInt.empty()).toBuilder().startSequenceNumber(12);

    var readPosition = engine().heldPictureReadPosition(request.build(), _ -> {}).orElseThrow();

    assertThat(readPosition).isZero();
  }

  // The clip's edit list starts it 1 s into its only GOP, so its keyframe and every picture before
  // the clip's start decode but are not presented. The attempt for the segment at 8 s reads back
  // from 4 s, or from the start of the timeline.
  @ParameterizedTest(name = "read back from {0} s")
  @ValueSource(ints = {4, 0})
  @DisplayName(
      "Should read from the start of the timeline when the only keyframe of an MP4 clip, which its"
          + " edit list trims inside its GOP, decodes but is not presented")
  void shouldReadFromTheStartOfTheTimelineWhenAnEditListClipsOnlyKeyframeIsNotPresented(
      int readBackSeconds) throws Exception {
    var source = clipTrimmedInsideItsOnlyGop();
    var request =
        heldRequest(source, OptionalInt.empty()).toBuilder()
            .startSequenceNumber(4)
            .seekPosition(Optional.of(Duration.ofSeconds(readBackSeconds)))
            .build();

    var readPosition = engine().heldPictureReadPosition(request, _ -> {});

    assertThat(readPosition.orElse(Duration.ofSeconds(readBackSeconds)))
        .as("where the attempt reads from")
        .isZero();
  }

  // 6 s of H.264 at 24 fps with one keyframe, copied from 1 s into a 20 s MP4 source.
  private Path clipTrimmedInsideItsOnlyGop() throws Exception {
    var whole = root.resolve("whole.mp4");
    ffmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=128x72:rate=24:duration=20",
            "-c:v",
            "libx264",
            "-bf",
            "3",
            "-x264-params",
            "scenecut=0:keyint=1000:min-keyint=1000",
            whole.toString()));
    var clip = root.resolve("clip.mp4");
    ffmpeg(List.of("-ss", "1", "-i", whole.toString(), "-c", "copy", "-t", "6", clip.toString()));
    return clip;
  }

  // 20 s of H.264 at 128x72 and 4 fps, with one keyframe, starting 2 s after a 440 Hz tone, and an
  // 880 Hz tone that starts with the video, in MPEG-TS.
  private Path tonesThatStartBeforeTheVideo() throws Exception {
    var source = root.resolve("tones-before-video.ts");
    ffmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:sample_rate=48000:duration=40",
            "-itsoffset",
            "2",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=128x72:rate=4:duration=20",
            "-itsoffset",
            "2",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=880:sample_rate=48000:duration=40",
            "-map",
            "1:v",
            "-map",
            "0:a",
            "-map",
            "2:a",
            "-c:v",
            "libx264",
            "-bf",
            "3",
            "-b_strategy",
            "0",
            "-x264-params",
            "b-pyramid=normal:scenecut=0:keyint=1000:min-keyint=1000",
            "-c:a",
            "aac",
            "-f",
            "mpegts",
            source.toString()));
    return source;
  }

  // 20 s of H.264 at 128x72 and 4 fps with IDR keyframes at 0, 2, 4, 6, 8 and 10 s only.
  private Path delayedPicturesWithKeyframesUpToTenSeconds(String format, String extension)
      throws Exception {
    var source = root.resolve("delayed-pictures." + extension);
    ffmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=128x72:rate=4:duration=20",
            "-c:v",
            "libx264",
            "-bf",
            "3",
            "-b_strategy",
            "0",
            "-x264-params",
            "b-pyramid=normal:scenecut=0:keyint=1000:min-keyint=1000",
            "-forced-idr",
            "1",
            "-force_key_frames",
            "0,2,4,6,8,10",
            "-f",
            format,
            source.toString()));
    return source;
  }

  private void ffmpeg(List<String> arguments) throws Exception {
    var command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-loglevel", "error"));
    command.addAll(arguments);
    var errors = root.resolve("ffmpeg-" + System.nanoTime() + ".log");
    var process =
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(errors.toFile())
            .start();
    assertThat(process.waitFor(5, TimeUnit.MINUTES)).as("FFmpeg exits").isTrue();
    assertThat(process.exitValue()).as(Files.readString(errors)).isZero();
  }

  private static FfmpegTranscodeEngine engine() {
    return FfmpegTranscodeEngine.builder()
        .commandBuilder(new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1)))
        .capabilityService(
            new TranscodeCapabilityService("ffmpeg", _ -> new FakeProcess("ffmpeg version 8.1", 0)))
        .launcher(new ProcessBuilderLauncher())
        .encoderStallTimeout(Duration.ofSeconds(30))
        .build();
  }

  // A held encode on a 2 s grid whose first segment starts at 18 s, read back from 14 s, of the
  // named audio stream, or of none.
  private static TranscodeRequest heldRequest(Path source, OptionalInt audioStream) {
    var audio = AudioDecision.builder().mode(AudioMode.NONE).build();
    if (audioStream.isPresent()) {
      audio =
          AudioDecision.builder()
              .mode(AudioMode.TRANSCODE)
              .codec("aac")
              .channels(2)
              .bitrate(128_000L)
              .streamIndex(audioStream)
              .padsWithSilenceToAdvertisedEnd(true)
              .build();
    }

    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .attemptId(UUID.randomUUID())
        .sourcePath(source)
        .targetSegmentDuration(2)
        .startSequenceNumber(9)
        .mediaSegmentCount(20)
        .outputFrameRate(Optional.of(new OutputFrameRate(4, 1)))
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.FULL_TRANSCODE)
                .videoCodecFamily("h264")
                .videoDecision(
                    VideoDecisionFixtures.encodeBuilder()
                        .holdsLastFrameToAdvertisedEnd(true)
                        .build())
                .audioDecision(audio)
                .subtitleDecision(SubtitleDecisions.EXCLUDED)
                .build())
        .encoderPreset(EncoderPreset.FASTER)
        .width(128)
        .height(72)
        .bitrate(100_000L)
        .seekPosition(Optional.of(Duration.ofSeconds(14)))
        .advertisedEnd(Optional.of(Duration.ofSeconds(40)))
        .build();
  }
}
