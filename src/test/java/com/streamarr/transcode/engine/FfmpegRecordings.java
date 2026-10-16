package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.fixtures.RecordingFixtures;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
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
          .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
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

  /**
   * Asserts that the delivered bytes begin with the initialization segment that expected.json
   * digests: FFmpeg's ftyp and moov, without the SEI messages of an hvc1 record.
   */
  public static void assertBeginsWithTheDeliveredInitializationSegment(
      byte[] delivered, Recording recording) {
    var initializationSegment = recording.deliveredInitializationSegment();
    assertThat(delivered)
        .as("delivered bytes")
        .hasSizeGreaterThanOrEqualTo(initializationSegment.byteLength());
    assertThat(sha256Of(Arrays.copyOf(delivered, initializationSegment.byteLength())))
        .as("SHA-256 of the delivered initialization segment")
        .isEqualTo(initializationSegment.sha256());
  }

  private static String sha256Of(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
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
      Mode mode,
      Optional<String> encoder,
      Source source,
      int period,
      long fragmentationTargetMicros,
      List<String> ffmpegArguments,
      Optional<String> recipeDeviation,
      int startSequenceNumber,
      int mediaSegmentCount,
      Size initializationSegment,
      Digest deliveredInitializationSegment,
      List<SegmentSummary> segments,
      List<SegmentSummary> discardedPreroll,
      Optional<ExpectedFailure> failure,
      int trailingAudioOnlyFragmentCount,
      List<HlsRun> hlsComparisons) {

    /** The encoder a job names for the recording: its video encoder, or copy for a stream copy. */
    public String videoEncoder() {
      return encoder().orElse("copy");
    }

    /**
     * The codec string that the recording's initialization segment declares for its video: every
     * copy is of a 64x36 libx264 High 4:4:4 Predictive source at level 1.0, and every encode forces
     * the level that its encoder chooses for 64x36 at 23.976 fps.
     */
    public String videoCodecString() {
      return switch (videoEncoder()) {
        case "copy" -> "avc1.F4000A";
        case "libx264" -> RecordingFixtures.ENCODED_RECORDING_CODEC_STRING;
        case "libsvtav1" -> "av01.0.00M.08";
        case "libx265" -> "hvc1.1.6.L30.90";
        default -> throw new AssertionError("No codec string is known for " + videoEncoder());
      };
    }

    @Override
    public String toString() {
      return file;
    }
  }

  /** Whether a recording encodes the video or copies it from the source. */
  public enum Mode {
    ENCODE,
    COPY
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

  /** A segment's length and the SHA-256 of its bytes, in lowercase hexadecimal. */
  public record Digest(int byteLength, String sha256) {}

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
