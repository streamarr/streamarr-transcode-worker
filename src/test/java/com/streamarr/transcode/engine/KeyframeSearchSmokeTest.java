package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A held encode's search for the video's last keyframe, run by real FFmpeg. */
@Tag("SmokeTest")
@DisplayName("Keyframe Search Smoke Tests")
class KeyframeSearchSmokeTest {

  @TempDir Path root;

  // At 4 fps with three B-frames in a pyramid, each picture decodes 0.5 s before it presents. The
  // attempt for the segment at 18 s reads back from 14 s, so the search reads from 10 s to 14 s and
  // then from 2 s to 10 s. The keyframe that presents at 10 s decodes at 9.5 s, so FFmpeg's scan of
  // an MPEG-TS source reads it in the interval before the one it presents in.
  @Test
  @DisplayName(
      "Should read from the decode time of the keyframe 0.5 s before the last when the last"
          + " keyframe of an MPEG-TS source decodes in the interval before the one it presents in")
  void shouldReadFromTheKeyframeBeforeTheLastWhenTheLastDecodesInTheIntervalBeforeItsOwn()
      throws Exception {
    var source = delayedPicturesWithKeyframesUpToTenSeconds();

    var readPosition = engine().heldPictureReadPosition(heldRequest(source), _ -> {}).orElseThrow();

    assertThat(readPosition)
        .as("the decode time of the keyframe that presents at 8 s")
        .isEqualTo(Duration.ofMillis(7_500));
  }

  // 20 s of H.264 at 128x72 and 4 fps with IDR keyframes at 0, 2, 4, 6, 8 and 10 s only, in
  // MPEG-TS, which carries no index.
  private Path delayedPicturesWithKeyframesUpToTenSeconds() throws Exception {
    var source = root.resolve("delayed-pictures.ts");
    var command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-loglevel", "error"));
    command.addAll(
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
            "mpegts",
            source.toString()));
    var errors = root.resolve("ffmpeg.log");
    var process =
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(errors.toFile())
            .start();
    assertThat(process.waitFor(5, TimeUnit.MINUTES)).as("FFmpeg exits").isTrue();
    assertThat(process.exitValue()).as(Files.readString(errors)).isZero();
    return source;
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

  // A held encode on a 2 s grid whose first segment starts at 18 s, read back from 14 s.
  private static TranscodeRequest heldRequest(Path source) {
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
                .audioDecision(
                    AudioDecision.builder()
                        .mode(AudioMode.TRANSCODE)
                        .codec("aac")
                        .channels(2)
                        .bitrate(128_000L)
                        .padsWithSilenceToAdvertisedEnd(true)
                        .build())
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
