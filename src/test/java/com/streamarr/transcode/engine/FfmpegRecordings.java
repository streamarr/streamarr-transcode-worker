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
final class FfmpegRecordings {

  private static final String DIRECTORY = "/fmp4/";
  private static final Expectations EXPECTATIONS =
      JsonMapper.builder()
          .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
          .build()
          .readValue(bytesOf("expected.json"), Expectations.class);

  private FfmpegRecordings() {}

  static byte[] bytesOf(String file) {
    try (var stream = FfmpegRecordings.class.getResourceAsStream(DIRECTORY + file)) {
      assertThat(stream).as("recording %s", file).isNotNull();
      return stream.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<Recording> recordings() {
    return EXPECTATIONS.fixtures();
  }

  static Recording recording(String file) {
    return recordings().stream()
        .filter(recording -> recording.file().equals(file))
        .findFirst()
        .orElseThrow(() -> new AssertionError("expected.json describes no recording " + file));
  }

  record Expectations(List<Recording> fixtures) {}

  /** One recorded stream and what its initialization segment and media segments must be. */
  record Recording(
      String file,
      int period,
      int startSequenceNumber,
      Size initializationSegment,
      List<SegmentSummary> segments,
      List<SegmentSummary> discardedPreroll,
      Optional<ExpectedFailure> failure,
      int trailingAudioOnlyFragmentCount,
      List<HlsRun> hlsOracles) {

    @Override
    public String toString() {
      return file;
    }
  }

  record Size(int byteLength) {}

  /**
   * A media segment by its number, the media time of its first video sample in the video track's
   * timescale, the indexes of its fragments after the initialization segment, and its size.
   */
  @Builder
  record SegmentSummary(
      int number,
      long firstVideoPresentationTime,
      int firstFragmentIndex,
      int fragmentCount,
      int syncFirstFragmentCount,
      long byteLength) {}

  /**
   * The reason grouping fails and the fragment, counted from 0 after the initialization segment.
   */
  record ExpectedFailure(Reason reason, int fragmentIndex) {}

  /**
   * The HLS muxer's run over the same source, with the media time of the first video sample of each
   * segment it cut, mapped onto this recording.
   */
  record HlsRun(String hlsRun, boolean expectedToAgree, List<CutPoint> segments) {

    @Override
    public String toString() {
      return hlsRun;
    }
  }

  record CutPoint(int number, long firstVideoPresentationTime) {}
}
