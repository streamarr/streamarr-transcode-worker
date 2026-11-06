package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runs the smallest known reproduction of the FFmpeg 8 scheduler deadlock that Streamarr's FFmpeg
 * fork patches (ADR 0057) on the FFmpeg on {@code PATH}, the locked build in CI. A demuxed stream
 * goes first to a stream copy and then to a filter graph that also has a source of its own. An
 * FFmpeg without the patch unchokes only the first destination of a stream, so that filter graph
 * never runs again and FFmpeg waits forever. The inputs are lavfi sources, so no media file is
 * read.
 */
@Tag("SmokeTest")
@DisplayName("FFmpeg Scheduler Smoke Tests")
class FfmpegSchedulerSmokeTest {

  private static final String SOURCES =
      "testsrc2=s=160x120:r=24:d=4[out0];sine=r=48000:d=120[out1]";
  private static final String GRAPH =
      "[0:v]tpad=stop=-1:stop_mode=clone,trim=end=120[v];"
          + "aevalsrc=0:d=0.1[z];[1:a][z]amix=inputs=2:duration=first[a]";
  private static final String ARGUMENTS =
      """
      ffmpeg -nostdin -v error -f lavfi -i %s -f lavfi -i %s -filter_complex %s
        -map [v] -map [a] -c:v rawvideo -c:a pcm_s16le -f null -
        -map 1:a -c copy -f null -
        -map 1:v -c copy -f null -
      """;
  private static final Duration HANG_GUARD = Duration.ofSeconds(60);

  @TempDir Path root;

  @Test
  @DisplayName(
      "Should finish when a stream feeds a stream copy before a filter graph with its own source")
  void shouldFinishWhenAStreamFeedsAStreamCopyBeforeAFilterGraphWithItsOwnSource()
      throws Exception {
    var log = root.resolve("ffmpeg.log");
    var process =
        new ProcessBuilder(ARGUMENTS.formatted(SOURCES, SOURCES, GRAPH).strip().split("\\s+"))
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertThat(process.onExit())
          .as("FFmpeg must finish instead of waiting on a choked filter graph")
          .succeedsWithin(HANG_GUARD);
      assertThat(process.exitValue()).as(Files.readString(log)).isZero();
    } finally {
      process.destroyForcibly();
    }
  }
}
