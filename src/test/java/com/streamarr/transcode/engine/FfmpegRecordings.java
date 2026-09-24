package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * FFmpeg's standard output recorded from ADR 0037's recipe by the pinned worker image, with the
 * media segments each recording groups into. {@code src/test/resources/fmp4/README.md} describes
 * every recording and how to record them again.
 */
public final class FfmpegRecordings {

  private static final String DIRECTORY = "/fmp4/";
  private static final Expectations EXPECTATIONS =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build()
          .readValue(bytesOf("expected.json"), Expectations.class);

  private FfmpegRecordings() {}

  public static byte[] bytesOf(String file) {
    try (var stream = FfmpegRecordings.class.getResourceAsStream(DIRECTORY + file)) {
      assertThat(stream).as("recording %s", file).isNotNull();
      return stream.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static List<Recording> recordings() {
    return EXPECTATIONS.fixtures();
  }

  public static Recording recording(String file) {
    return recordings().stream()
        .filter(recording -> recording.file().equals(file))
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected.json describes no recording " + file));
  }

  public record Expectations(List<Recording> fixtures) {}

  /**
   * One recorded stream, the FFmpeg command line that recorded it, and what its initialization
   * segment and media segments must be.
   *
   * @param encoder the video encoder of an encode; empty for a stream copy
   * @param recipeDeviation how the recording deliberately departs from the worker's recipe; empty
   *     when it follows the recipe
   * @param mediaSegmentCount the media segment count the server advertises for what the recording
   *     read, which an encode forces keyframes up to
   */
  public record Recording(
      String file,
      String mode,
      Optional<String> encoder,
      Source source,
      int seekSeconds,
      int period,
      long fragmentationTargetMicros,
      List<String> ffmpegArguments,
      Optional<String> recipeDeviation,
      int startSequenceNumber,
      int mediaSegmentCount,
      Size initializationSegment,
      List<SegmentSummary> segments,
      List<SegmentSummary> discardedPreroll,
      Optional<ExpectedFailure> failure,
      int trailingAudioOnlyFragmentCount,
      List<HlsRun> hlsComparisons) {

    @Override
    public String toString() {
      return file;
    }
  }

  /** The source a recording read, with its video stream's r_frame_rate as ffprobe reports it. */
  public record Source(String file, String videoRealFrameRate) {

    /** The probed frame rate as the worker receives it: the rational as a double. */
    public double videoFrameRate() {
      var rational = videoRealFrameRate.split("/");
      return Double.parseDouble(rational[0]) / Double.parseDouble(rational[1]);
    }
  }

  public record Size(int byteLength) {}

  /**
   * A media segment by its number, the media time of its first video sample in the video track's
   * timescale, the indexes of its fragments after the initialization segment, and its size.
   */
  @Builder
  public record SegmentSummary(
      int number,
      long firstVideoPresentationTime,
      int firstFragmentIndex,
      int fragmentCount,
      int syncFirstFragmentCount,
      long byteLength) {}

  /**
   * The reason grouping fails and the fragment, counted from 0 after the initialization segment;
   * for a skipped segment number, also the number the attempt expected and the one the fragment's
   * keyframe opens.
   */
  @Builder
  public record ExpectedFailure(
      Reason reason,
      int fragmentIndex,
      Optional<Long> expectedNumber,
      Optional<Long> actualNumber) {}

  /**
   * The HLS muxer's run over the same source, with the media time of the first video sample of each
   * segment it cut, mapped onto this recording, and the segments whose start differs from the
   * grid's.
   */
  public record HlsRun(String hlsRun, List<CutPoint> segments, List<CutPointMismatch> mismatches) {

    @Override
    public String toString() {
      return hlsRun;
    }
  }

  public record CutPoint(int number, long firstVideoPresentationTime) {}

  /** A segment number whose first video sample the grid and the HLS muxer place differently. */
  public record CutPointMismatch(int number, Optional<Long> grouping, Optional<Long> hls) {}
}
