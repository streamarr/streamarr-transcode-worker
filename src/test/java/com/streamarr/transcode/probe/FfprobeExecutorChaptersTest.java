package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.probeFixture;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeOutput;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeChapter;
import com.google.protobuf.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Chapters Tests")
class FfprobeExecutorChaptersTest {

  private static final List<ProbeChapter> OPENING_AND_ENDING =
      List.of(
          ProbeChapter.newBuilder()
              .setStartTime(seconds(0, 0))
              .setEndTime(seconds(0, 500_000_000))
              .setTitle("Opening")
              .build(),
          ProbeChapter.newBuilder()
              .setStartTime(seconds(0, 500_000_000))
              .setEndTime(seconds(2, 0))
              .setTitle("Ending")
              .build());

  @Test
  @DisplayName("Should report each titled chapter in order when a Matroska file has chapters")
  void shouldReportEachTitledChapterInOrderWhenAMatroskaFileHasChapters() {
    var container = probeFixture("mkv-remuxed-from-mp4.mkv").getContainer();

    assertThat(container.getChaptersList()).containsExactlyElementsOf(OPENING_AND_ENDING);
  }

  @Test
  @DisplayName("Should report each titled chapter in order when an MP4 file has chapters")
  void shouldReportEachTitledChapterInOrderWhenAnMp4FileHasChapters() {
    var container = probeFixture("mp4-titled.mp4").getContainer();

    assertThat(container.getChaptersList()).containsExactlyElementsOf(OPENING_AND_ENDING);
  }

  @Test
  @DisplayName("Should report no chapters and no omission when the file has no chapters")
  void shouldReportNoChaptersAndNoOmissionWhenTheFileHasNoChapters() {
    var container = probeFixture("mp4-default-handlers.mp4").getContainer();

    assertThat(container.getChaptersList()).isEmpty();
    assertThat(container.getChaptersOmitted()).isFalse();
  }

  @Test
  @DisplayName(
      "Should measure each chapter from the container start when the container starts late")
  void shouldMeasureEachChapterFromTheContainerStartWhenTheContainerStartsLate() {
    // The container starts at 10 s. The chapters span 0-12 s, 12-12.5 s and 12.5-13 s. The second
    // chapter has no title, and the third chapter's title takes 513 bytes.
    var container = probeFixture("mkv-offset-chapters.mkv").getContainer();

    assertThat(container.getChaptersList())
        .containsExactly(
            ProbeChapter.newBuilder()
                .setStartTime(seconds(-10, 0))
                .setEndTime(seconds(2, 0))
                .setTitle("Prologue")
                .build(),
            ProbeChapter.newBuilder()
                .setStartTime(seconds(2, 0))
                .setEndTime(seconds(2, 500_000_000))
                .build(),
            ProbeChapter.newBuilder()
                .setStartTime(seconds(2, 500_000_000))
                .setEndTime(seconds(3, 0))
                .build());
  }

  @Test
  @DisplayName("Should omit every chapter when the chapters take more than 32,768 bytes")
  void shouldOmitEveryChapterWhenTheChaptersTakeMoreThan32768Bytes() {
    // 400 chapters with 100-byte titles.
    var container = probeFixture("mkv-many-chapters.mkv").getContainer();

    assertThat(container.getChaptersList()).isEmpty();
    assertThat(container.getChaptersOmitted()).isTrue();
  }

  @Test
  @DisplayName("Should report every chapter when the chapters take exactly 32,768 bytes")
  void shouldReportEveryChapterWhenTheChaptersTakeExactly32768Bytes() {
    var container = probeOutput(chaptersTaking(0)).getContainer();

    assertThat(container.getChaptersList()).hasSize(64);
    assertThat(container.getChaptersOmitted()).isFalse();
  }

  @Test
  @DisplayName("Should omit every chapter when the chapters take 32,769 bytes")
  void shouldOmitEveryChapterWhenTheChaptersTake32769Bytes() {
    var container = probeOutput(chaptersTaking(1)).getContainer();

    assertThat(container.getChaptersList()).isEmpty();
    assertThat(container.getChaptersOmitted()).isTrue();
  }

  @Test
  @DisplayName("Should report no start or end time when ffprobe reports neither")
  void shouldReportNoStartOrEndTimeWhenFfprobeReportsNeither() {
    var container =
        probeOutput(
                """
                {"chapters":[{"id":0,"time_base":"1/1000","tags":{"title":"Untimed"}}],
                 "streams":[{"index":0,"codec_type":"video","time_base":"1/1000"}]}
                """)
            .getContainer();

    assertThat(container.getChaptersList())
        .containsExactly(ProbeChapter.newBuilder().setTitle("Untimed").build());
  }

  /**
   * 64 chapters that start at 0 s and end at 1 s, with 500-byte titles. Each chapter's message
   * holds a 2-byte empty start time, a 4-byte end time and a 503-byte title field: 509 bytes. Its
   * field in the container takes a 1-byte key, a 2-byte length and those 509 bytes: 512 bytes. 64
   * chapters take 32,768 bytes, and {@code extraTitleBytes} lengthen the last title.
   */
  private static String chaptersTaking(int extraTitleBytes) {
    var chapters = new ArrayList<Map<String, Object>>();
    for (var chapter = 0; chapter < 64; chapter++) {
      var title = "c".repeat(chapter == 63 ? 500 + extraTitleBytes : 500);
      chapters.add(
          Map.of(
              "id",
              chapter,
              "time_base",
              "1/1",
              "start",
              0,
              "end",
              1,
              "tags",
              Map.of("title", title)));
    }

    var streams = List.of(Map.of("index", 0, "codec_type", "video", "time_base", "1/1000"));
    return new ObjectMapper().writeValueAsString(Map.of("chapters", chapters, "streams", streams));
  }

  private static Duration seconds(long seconds, int nanos) {
    return Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();
  }
}
