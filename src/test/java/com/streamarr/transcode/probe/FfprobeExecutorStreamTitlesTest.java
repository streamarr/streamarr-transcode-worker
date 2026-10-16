package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.probeFixture;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeOutput;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Builder;
import lombok.Singular;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Stream Titles Tests")
class FfprobeExecutorStreamTitlesTest {

  // 512 bytes of UTF-8 in 256 two-byte characters.
  private static final String TITLE_OF_512_BYTES = "é".repeat(256);

  @Nested
  @DisplayName("Titles")
  class TitleTests {

    @Test
    @DisplayName("Should report the title tag when a Matroska track has a name")
    void shouldReportTheTitleTagWhenAMatroskaTrackHasAName() {
      var subtitle = streamOf("mkv-title-tags.mkv", 3);

      assertThat(subtitle.getTitle()).isEqualTo("English SDH");
    }

    @Test
    @DisplayName("Should report the name tag when FFmpeg wrote an MP4 track's title")
    void shouldReportTheNameTagWhenFfmpegWroteAnMp4TracksTitle() {
      var media = probeFixture("mp4-titled.mp4");

      assertThat(media.getStreams(1).getTitle()).isEqualTo("Commentary");
      assertThat(media.getStreams(3).getTitle()).isEqualTo("SDH");
    }

    @Test
    @DisplayName("Should match tag keys without regard to case when Matroska keeps an MP4 name")
    void shouldMatchTagKeysWithoutRegardToCaseWhenMatroskaKeepsAnMp4Name() {
      // Remuxing keeps the MP4 name tag as the Matroska tag NAME.
      var audio = streamOf("mkv-remuxed-from-mp4.mkv", 1);

      assertThat(audio.getTitle()).isEqualTo("Commentary");
    }

    @Test
    @DisplayName("Should take the name tag when the title tag is blank")
    void shouldTakeTheNameTagWhenTheTitleTagIsBlank() {
      var audio = streamOf("mkv-title-tags.mkv", 1);

      assertThat(audio.getTitle()).isEqualTo("Director");
    }

    @Test
    @DisplayName(
        "Should report no title and keep the name tag unused when the title exceeds 512 bytes")
    void shouldReportNoTitleAndKeepTheNameTagUnusedWhenTheTitleExceeds512Bytes() {
      var audio = streamOf("mkv-title-tags.mkv", 2);

      assertThat(audio.hasTitle()).isFalse();
    }

    @Test
    @DisplayName("Should report a title of exactly 512 bytes when the title fits the limit")
    void shouldReportATitleOfExactly512BytesWhenTheTitleFitsTheLimit() {
      var media =
          probeOutput(
              FfprobeOutput.builder().subtitle(Labels.titled(TITLE_OF_512_BYTES)).build().json());

      assertThat(media.getStreams(1).getTitle()).isEqualTo(TITLE_OF_512_BYTES);
    }

    @Test
    @DisplayName("Should report no title when an MP4 track has only a handler name")
    void shouldReportNoTitleWhenAnMp4TrackHasOnlyAHandlerName() {
      var audio = streamOf("mp4-titled.mp4", 2);

      assertThat(audio.hasTitle()).isFalse();
      assertThat(audio.getHandlerName()).isEqualTo("Stereo Mix");
    }
  }

  @Nested
  @DisplayName("Handler Names")
  class HandlerNameTests {

    @Test
    @DisplayName("Should report FFmpeg's default handler names unchanged when an MP4 has no others")
    void shouldReportFfmpegsDefaultHandlerNamesUnchangedWhenAnMp4HasNoOthers() {
      var media = probeFixture("mp4-default-handlers.mp4");

      assertThat(media.getStreams(1).getHandlerName()).isEqualTo("SoundHandler");
      assertThat(media.getStreams(2).getHandlerName()).isEqualTo("SubtitleHandler");
    }

    @Test
    @DisplayName("Should report a Matroska HANDLER_NAME tag when FFmpeg remuxed the file from MP4")
    void shouldReportAMatroskaHandlerNameTagWhenFfmpegRemuxedTheFileFromMp4() {
      var audio = streamOf("mkv-remuxed-from-mp4.mkv", 2);

      assertThat(audio.getHandlerName()).isEqualTo("Stereo Mix");
    }

    @Test
    @DisplayName("Should report no handler name when the handler name exceeds 512 bytes")
    void shouldReportNoHandlerNameWhenTheHandlerNameExceeds512Bytes() {
      var subtitle = streamOf("mkv-title-tags.mkv", 3);

      assertThat(subtitle.hasHandlerName()).isFalse();
    }

    @Test
    @DisplayName("Should report an empty handler name when the tag is empty")
    void shouldReportAnEmptyHandlerNameWhenTheTagIsEmpty() {
      var media =
          probeOutput(
              FfprobeOutput.builder()
                  .subtitle(Labels.builder().handlerName("").build())
                  .build()
                  .json());

      assertThat(media.getStreams(1).hasHandlerName()).isTrue();
      assertThat(media.getStreams(1).getHandlerName()).isEmpty();
    }

    @Test
    @DisplayName("Should report no title and no handler name when the stream is video")
    void shouldReportNoTitleAndNoHandlerNameWhenTheStreamIsVideo() {
      // The video stream has a title tag and a HANDLER_NAME tag.
      var video = streamOf("mkv-title-tags.mkv", 0);

      assertThat(video.hasTitle()).isFalse();
      assertThat(video.hasHandlerName()).isFalse();
    }
  }

  @Nested
  @DisplayName("Title Limit")
  class TitleLimitTests {

    @Test
    @DisplayName("Should omit every title when the titles take more than 16,384 bytes")
    void shouldOmitEveryTitleWhenTheTitlesTakeMoreThan16384Bytes() {
      // 40 titles of 500 bytes take 40 x 504 = 20,160 bytes in their protobuf encoding.
      var media = probeFixture("mkv-many-titles.mkv");

      assertThat(media.getStreamTitlesOmitted()).isTrue();
      assertThat(media.getStreamsList()).noneMatch(ProbeStreamInfo::hasTitle);
    }

    @Test
    @DisplayName("Should report every title and handler name when they take exactly 16,384 bytes")
    void shouldReportEveryTitleAndHandlerNameWhenTheyTakeExactly16384Bytes() {
      var media = probeOutput(labelsTaking(384));

      assertThat(media.getStreamTitlesOmitted()).isFalse();
      assertThat(media.getStreamsList()).filteredOn(ProbeStreamInfo::hasTitle).hasSize(31);
      assertThat(media.getStreamsList()).filteredOn(ProbeStreamInfo::hasHandlerName).hasSize(1);
    }

    @Test
    @DisplayName("Should omit every title and handler name when they take 16,385 bytes")
    void shouldOmitEveryTitleAndHandlerNameWhenTheyTake16385Bytes() {
      var media = probeOutput(labelsTaking(385));

      assertThat(media.getStreamTitlesOmitted()).isTrue();
      assertThat(media.getStreamsList())
          .noneMatch(stream -> stream.hasTitle() || stream.hasHandlerName());
    }

    @Test
    @DisplayName("Should count nothing for a title that exceeds 512 bytes when it checks the limit")
    void shouldCountNothingForATitleThatExceeds512BytesWhenItChecksTheLimit() {
      var output = FfprobeOutput.builder();
      for (var stream = 0; stream < 32; stream++) {
        output.subtitle(Labels.titled(TITLE_OF_512_BYTES));
      }

      output.subtitle(Labels.titled("é".repeat(10_000)));

      var media = probeOutput(output.build().json());

      // 32 titles of 512 bytes take 32 x 516 = 16,512 bytes, which exceeds the limit on its own.
      assertThat(media.getStreamTitlesOmitted()).isTrue();
    }

    /**
     * 31 titles of 512 bytes take 31 x 516 = 15,996 bytes in their protobuf encoding: a two-byte
     * field key, a two-byte length and the title. A handler name of {@code handlerBytes} bytes then
     * takes {@code handlerBytes} + 4 more, so 384 bytes reach exactly 16,384.
     */
    private static String labelsTaking(int handlerBytes) {
      var output = FfprobeOutput.builder();
      for (var stream = 0; stream < 31; stream++) {
        output.subtitle(Labels.titled(TITLE_OF_512_BYTES));
      }

      return output
          .subtitle(Labels.builder().handlerName("h".repeat(handlerBytes)).build())
          .build()
          .json();
    }
  }

  @Builder
  private record Labels(String title, String handlerName) {

    static Labels titled(String title) {
      return Labels.builder().title(title).build();
    }
  }

  /** ffprobe output with one video stream and a subtitle stream for each set of labels. */
  @Builder
  private record FfprobeOutput(@Singular List<Labels> subtitles) {

    String json() {
      var streams = new ArrayList<Map<String, Object>>();
      streams.add(Map.of("index", 0, "codec_type", "video", "time_base", "1/1000"));
      for (var labels : subtitles) {
        var tags = new LinkedHashMap<String, String>();
        if (labels.title() != null) {
          tags.put("title", labels.title());
        }

        if (labels.handlerName() != null) {
          tags.put("handler_name", labels.handlerName());
        }

        streams.add(Map.of("index", streams.size(), "codec_type", "subtitle", "tags", tags));
      }

      return new ObjectMapper().writeValueAsString(Map.of("streams", streams));
    }
  }
}
