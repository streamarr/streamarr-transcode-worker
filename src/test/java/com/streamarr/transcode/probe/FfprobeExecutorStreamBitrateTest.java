package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static com.streamarr.transcode.probe.RecordedFfprobe.recordedAnswers;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.Singular;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Stream Bitrate Tests")
class FfprobeExecutorStreamBitrateTest {

  private static final String MKVMERGE = "mkvmerge v92.0 ('Everglow') 64-bit";
  private static final String MKVPROPEDIT = "mkvpropedit v92.0 ('Everglow') 64-bit";

  // A probe succeeds only with a video stream.
  private static final MediaStream VIDEO_WITHOUT_STATISTICS =
      MediaStream.builder().codecType("video").build();

  @Nested
  @DisplayName("Recorded Matroska Files")
  class RecordedTests {

    @Test
    @DisplayName("Should report each stream's statistics bitrate when mkvmerge wrote the file")
    void shouldReportEachStreamsStatisticsBitrateWhenMkvmergeWroteTheFile() {
      var media = probeRecording("mkv-statistics-mkvmerge.mkv");

      assertThat(bitrates(media)).containsExactly(21_589L, 69_012L);
    }

    @Test
    @DisplayName(
        "Should report the fresh statistics bitrate when mkvpropedit rewrote the statistics later")
    void shouldReportTheFreshStatisticsBitrateWhenMkvpropeditRewroteTheStatisticsLater() {
      var media = probeRecording("mkv-statistics-mkvpropedit.mkv");

      assertThat(bitrates(media)).containsExactly(21_600L, 69_012L);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("staleRecordings")
    @DisplayName(
        "Should report no stream bitrate when FFmpeg rewrote the file and kept stale statistics")
    void shouldReportNoStreamBitrateWhenFfmpegRewroteTheFileAndKeptStaleStatistics(
        String recording) {
      var media = probeRecording(recording);

      assertThat(media.getStreamsList())
          .hasSize(2)
          .noneMatch(ProbeStreamInfo::hasBitrateBitsPerSecond);
    }

    // The trim and the re-encode keep the file's date, and their stale byte counts fit in the file.
    static Stream<String> staleRecordings() {
      return Stream.of(
          "mkv-statistics-ffmpeg-cut.mkv",
          "mkv-statistics-ffmpeg-cut-same-date.mkv",
          "mkv-statistics-ffmpeg-trim-same-date.mkv",
          "mkv-statistics-ffmpeg-reencode-same-date.mkv");
    }

    @Test
    @DisplayName("Should report no stream bitrate when the probe cannot read the Matroska file")
    void shouldReportNoStreamBitrateWhenTheProbeCannotReadTheMatroskaFile() {
      var media = probe(recordedAnswers("mkv-statistics-mkvmerge.mkv").build());

      assertThat(media.getStreamsList()).noneMatch(ProbeStreamInfo::hasBitrateBitsPerSecond);
    }

    private static ProbeMediaInfo probeRecording(String recording) {
      return probe(recordedAnswers(recording).source(head(recording)).build());
    }
  }

  @Nested
  @DisplayName("Statistics Tags")
  class StatisticsTagTests {

    @Test
    @DisplayName("Should compute the bitrate from bytes and duration when the BPS tag is missing")
    void shouldComputeTheBitrateFromBytesAndDurationWhenTheBpsTagIsMissing() {
      var media =
          probeOutput(
              output().stream(fresh("video", "7800").tag("DURATION", "00:00:05.023000000").build())
                  .build());

      // 7800 × 8 / 5.023 = 12,422.85
      assertThat(bitrates(media)).containsExactly(12_423L);
    }

    @Test
    @DisplayName("Should read language-suffixed statistics tags when the muxer added a language")
    void shouldReadLanguageSuffixedStatisticsTagsWhenTheMuxerAddedALanguage() {
      var media =
          probeOutput(
              output().stream(VIDEO_WITHOUT_STATISTICS).stream(
                      MediaStream.builder()
                          .codecType("audio")
                          .tag("DURATION-eng", "00:00:01.000000000")
                          .tag("NUMBER_OF_BYTES-eng", "8000")
                          .tag("_STATISTICS_WRITING_APP-eng", MKVMERGE)
                          .build())
                  .build());

      assertThat(bitrates(media)).containsExactly(64_000L);
    }

    @Test
    @DisplayName("Should keep ffprobe's bitrate when the stream reports one")
    void shouldKeepFfprobesBitrateWhenTheStreamReportsOne() {
      var media =
          probeOutput(
              output().stream(VIDEO_WITHOUT_STATISTICS).stream(
                      fresh("audio", "5400").bitRate("448000").tag("BPS", "640000").build())
                  .build());

      assertThat(bitrates(media)).containsExactly(448_000L);
    }

    @Test
    @DisplayName("Should report no bitrate for a subtitle stream when it carries statistics tags")
    void shouldReportNoBitrateForASubtitleStreamWhenItCarriesStatisticsTags() {
      var media =
          probeOutput(
              output().stream(VIDEO_WITHOUT_STATISTICS).stream(
                      fresh("subtitle", "5400").tag("BPS", "64").build())
                  .build());

      assertThat(media.getStreams(1).hasBitrateBitsPerSecond()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streamsBeyondTheFile")
    @DisplayName("Should report no stream bitrate when the trusted byte counts exceed the file")
    void shouldReportNoStreamBitrateWhenTheTrustedByteCountsExceedTheFile(
        String reason, MediaStream second) {
      var media =
          probeOutput(
              output().size("10000").stream(fresh("video", "5400").tag("BPS", "21589").build())
                  .stream(second)
                  .build());

      assertThat(media.getStreams(0).hasBitrateBitsPerSecond()).isFalse();
    }

    static Stream<Arguments> streamsBeyondTheFile() {
      return Stream.of(
          Arguments.of(
              "an audio stream that ffprobe measures",
              fresh("audio", "9000").bitRate("640000").tag("BPS", "640000").build()),
          Arguments.of("a subtitle stream", fresh("subtitle", "9000").tag("BPS", "64").build()),
          Arguments.of(
              "an audio stream whose bitrate the tags do not give",
              fresh("audio", "9000").build()));
    }

    @Test
    @DisplayName(
        "Should report no stream bitrate when mkvpropedit statistics sit in a file another"
            + " program wrote")
    void shouldReportNoStreamBitrateWhenMkvpropeditStatisticsSitInAFileAnotherProgramWrote() {
      var output =
          output().stream(
                  MediaStream.builder()
                      .codecType("video")
                      .tag("BPS", "21600")
                      .tag("NUMBER_OF_BYTES", "5400")
                      .tag("_STATISTICS_WRITING_APP", MKVPROPEDIT)
                      .build())
              .build();

      var media =
          probe(
              RecordedFfprobe.Answers.builder()
                  .ffprobeOutput(output.json())
                  .source(head("mkv-statistics-ffmpeg-cut.mkv"))
                  .build());

      assertThat(media.getStreamsList()).noneMatch(ProbeStreamInfo::hasBitrateBitsPerSecond);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("untrustedOutputs")
    @DisplayName("Should report no stream bitrate when the statistics cannot be trusted or read")
    void shouldReportNoStreamBitrateWhenTheStatisticsCannotBeTrustedOrRead(
        String reason, FfprobeOutput output) {
      var media = probeOutput(output);

      assertThat(media.getStreamsList()).noneMatch(ProbeStreamInfo::hasBitrateBitsPerSecond);
    }

    static Stream<Arguments> untrustedOutputs() {
      return Stream.of(
          Arguments.of(
              "no statistics writing app",
              output().stream(
                      MediaStream.builder()
                          .codecType("video")
                          .tag("BPS", "21589")
                          .tag("NUMBER_OF_BYTES", "5400")
                          .build())
                  .build()),
          Arguments.of(
              "statistics from a program other than the file's writer",
              output().stream(
                      MediaStream.builder()
                          .codecType("video")
                          .tag("BPS", "21589")
                          .tag("NUMBER_OF_BYTES", "5400")
                          .tag("_STATISTICS_WRITING_APP", "mkvmerge v91.0 ('Signs') 64-bit")
                          .build())
                  .build()),
          Arguments.of(
              "a format other than Matroska",
              output().formatName("mov,mp4,m4a,3gp,3g2,mj2").stream(
                      fresh("video", "5400").tag("BPS", "21589").build())
                  .build()),
          Arguments.of(
              "no file size",
              output().size(null).stream(fresh("video", "5400").tag("BPS", "21589").build())
                  .build()),
          Arguments.of(
              "no byte count",
              output().stream(
                      MediaStream.builder()
                          .codecType("video")
                          .tag("BPS", "21589")
                          .tag("_STATISTICS_WRITING_APP", MKVMERGE)
                          .build())
                  .build()),
          Arguments.of(
              "an unreadable byte count",
              output().stream(fresh("video", "many").tag("BPS", "21589").build()).build()),
          Arguments.of(
              "a negative byte count",
              output().stream(fresh("video", "-5400").tag("BPS", "21589").build()).build()),
          Arguments.of(
              "no BPS and no bytes",
              output().stream(fresh("video", "0").tag("DURATION", "00:00:02.001000000").build())
                  .build()),
          Arguments.of(
              "zero BPS and no duration",
              output().stream(fresh("video", "5400").tag("BPS", "0").build()).build()),
          Arguments.of(
              "no BPS and a zero duration",
              output().stream(fresh("video", "5400").tag("DURATION", "00:00:00.000000000").build())
                  .build()),
          Arguments.of(
              "no BPS and no duration", output().stream(fresh("video", "5400").build()).build()),
          Arguments.of(
              "a bitrate beyond a long",
              output().size(String.valueOf(Long.MAX_VALUE)).stream(
                      fresh("video", String.valueOf(Long.MAX_VALUE))
                          .tag("DURATION", "00:00:00.000000001")
                          .build())
                  .build()),
          Arguments.of(
              "byte counts whose sum exceeds a long",
              output().size(String.valueOf(Long.MAX_VALUE)).stream(
                      fresh("video", String.valueOf(Long.MAX_VALUE)).tag("BPS", "21589").build())
                  .stream(
                      fresh("audio", String.valueOf(Long.MAX_VALUE)).tag("BPS", "69012").build())
                  .build()));
    }
  }

  private static List<Long> bitrates(ProbeMediaInfo media) {
    return media.getStreamsList().stream()
        .filter(ProbeStreamInfo::hasBitrateBitsPerSecond)
        .map(ProbeStreamInfo::getBitrateBitsPerSecond)
        .toList();
  }

  /** Probes the output for a file whose segment names mkvmerge 92.0 as its WritingApp. */
  private static ProbeMediaInfo probeOutput(FfprobeOutput output) {
    return probe(
        RecordedFfprobe.Answers.builder()
            .ffprobeOutput(output.json())
            .source(head("mkv-statistics-mkvmerge.mkv"))
            .build());
  }

  /** The bytes of a recorded Matroska source before its first Cluster. */
  private static Path head(String recording) {
    var resource =
        FfprobeExecutorStreamBitrateTest.class.getResource("/probe/matroska-heads/" + recording);
    assertThat(resource).as("the head of %s", recording).isNotNull();
    try {
      return Path.of(resource.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  /** ffprobe output for a Matroska file of 29,793 bytes. */
  private static FfprobeOutput.FfprobeOutputBuilder output() {
    return FfprobeOutput.builder().formatName(ProbedSource.MATROSKA_FORMAT).size("29793");
  }

  /** A stream whose statistics, which mkvmerge wrote, count the given bytes. */
  private static MediaStream.MediaStreamBuilder fresh(String codecType, String bytes) {
    return MediaStream.builder()
        .codecType(codecType)
        .tag("NUMBER_OF_BYTES", bytes)
        .tag("_STATISTICS_WRITING_APP", MKVMERGE);
  }

  @Builder
  private record MediaStream(String codecType, String bitRate, @Singular Map<String, String> tags) {

    Map<String, Object> json(int index) {
      var stream = new LinkedHashMap<String, Object>();
      stream.put("index", index);
      stream.put("codec_type", codecType);
      stream.put("time_base", "1/1000");
      if (bitRate != null) {
        stream.put("bit_rate", bitRate);
      }

      stream.put("tags", tags);
      return stream;
    }
  }

  @Builder
  private record FfprobeOutput(
      String formatName, String size, @Singular List<MediaStream> streams) {

    String json() {
      var format = new LinkedHashMap<String, Object>();
      format.put("format_name", formatName);
      if (size != null) {
        format.put("size", size);
      }

      var streamList = new ArrayList<Map<String, Object>>();
      for (var stream : streams) {
        streamList.add(stream.json(streamList.size()));
      }

      return new ObjectMapper().writeValueAsString(Map.of("format", format, "streams", streamList));
    }
  }
}
