package com.streamarr.transcode.worker;

import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ChromaSubsampling;
import build.buf.gen.streamarr.transcode.v1.ContainerCropping;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.google.protobuf.Duration;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Worker Probe Facts Tests")
@Timeout(30)
class WorkerProbeFactsTest {

  @TempDir Path sourceRoot;

  @Test
  @DisplayName("Should choose the video stream after the cover art when the cover art comes first")
  void shouldChooseTheVideoStreamAfterTheCoverArtWhenTheCoverArtComesFirst() throws Exception {
    var media = probeFixture("mp4-cover-art-first.mp4");

    assertThat(media.getChosenVideoStreamIndex()).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({"256, true", "257, false"})
  @DisplayName("Should measure label limits in UTF-8 bytes when tags contain multibyte characters")
  void shouldMeasureLabelLimitsInUtf8BytesWhenTagsContainMultibyteCharacters(
      int characters, boolean retained) throws Exception {
    var label = "é".repeat(characters);
    var media =
        probeOutput(
            """
            {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"},
                        {"index":1,"codec_type":"audio",
                         "tags":{"title":"%s","handler_name":"%s"}}]}
            """
                .formatted(label, label));

    var audio = media.getStreams(1);
    assertThat(audio.hasTitle()).isEqualTo(retained);
    assertThat(audio.hasHandlerName()).isEqualTo(retained);
    assertThat(audio.getTitle()).isEqualTo(retained ? label : "");
    assertThat(audio.getHandlerName()).isEqualTo(retained ? label : "");
  }

  @Test
  @DisplayName("Should prefer the title when both title and name tags are nonblank")
  void shouldPreferTheTitleWhenBothTitleAndNameTagsAreNonblank() throws Exception {
    var media =
        probeOutput(
            """
            {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"},
                        {"index":1,"codec_type":"audio",
                         "tags":{"title":"Director commentary","NAME":"Alternate name"}}]}
            """);

    assertThat(media.getStreams(1).getTitle()).isEqualTo("Director commentary");
  }

  @Test
  @DisplayName("Should preserve label whitespace when the tags are not blank")
  void shouldPreserveLabelWhitespaceWhenTheTagsAreNotBlank() throws Exception {
    var media =
        probeOutput(
            """
            {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"},
                        {"index":1,"codec_type":"audio",
                         "tags":{"title":"  Commentary  ","handler_name":"  Stereo Mix  "}}]}
            """);

    assertThat(media.getStreams(1).getTitle()).isEqualTo("  Commentary  ");
    assertThat(media.getStreams(1).getHandlerName()).isEqualTo("  Stereo Mix  ");
  }

  @Test
  @DisplayName("Should ignore nontext labels when ffprobe reports malformed tags")
  void shouldIgnoreNontextLabelsWhenFfprobeReportsMalformedTags() throws Exception {
    var media =
        probeOutput(
            """
            {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"},
                        {"index":1,"codec_type":"audio","sample_rate":"48000",
                         "tags":{"title":42,"name":"Commentary","handler_name":true}}]}
            """);

    var audio = media.getStreams(1);
    assertThat(audio.getTitle()).isEqualTo("Commentary");
    assertThat(audio.hasHandlerName()).isFalse();
    assertThat(audio.getSampleRateHz()).isEqualTo(48_000);
  }

  @Test
  @DisplayName("Should preserve chapter order when their timestamps are out of order")
  void shouldPreserveChapterOrderWhenTheirTimestampsAreOutOfOrder() throws Exception {
    var media =
        probeOutput(
            """
            {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"}],
             "chapters":[
              {"id":9,"time_base":"1/1000","start":2000,"end":3000,"tags":{"title":"Later"}},
              {"id":3,"time_base":"1/1000","start":0,"end":1000,"tags":{"title":"Opening"}},
              {"id":6,"time_base":"1/1000","start":1000,"end":2000,"tags":{"title":"Middle"}}]}
            """);

    assertThat(media.getContainer().getChaptersList())
        .extracting(chapter -> chapter.getTitle())
        .containsExactly("Later", "Opening", "Middle");
    assertThat(media.getContainer().getChaptersList())
        .extracting(chapter -> chapter.getStartTime().getSeconds())
        .containsExactly(2L, 0L, 1L);
  }

  @ParameterizedTest
  @CsvSource({"256, true", "257, false"})
  @DisplayName(
      "Should measure chapter title limits in UTF-8 bytes when the title contains multibyte characters")
  void shouldMeasureChapterTitleLimitsInUtf8BytesWhenTheTitleContainsMultibyteCharacters(
      int characters, boolean retained) throws Exception {
    var title = "é".repeat(characters);
    var media =
        probeOutput(
            """
            {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"}],
             "chapters":[{"id":0,"time_base":"1/1000","start":0,"end":1000,
                          "tags":{"title":"%s"}}]}
            """
                .formatted(title));

    assertThat(media.getContainer().getChaptersList())
        .singleElement()
        .satisfies(
            chapter -> {
              assertThat(chapter.hasTitle()).isEqualTo(retained);
              assertThat(chapter.getTitle()).isEqualTo(retained ? title : "");
              assertThat(chapter.getEndTime().getSeconds()).isEqualTo(1);
            });
    assertThat(media.getContainer().getChaptersOmitted()).isFalse();
  }

  @Test
  @DisplayName("Should preserve each crop edge when the container crops asymmetrically")
  void shouldPreserveEachCropEdgeWhenTheContainerCropsAsymmetrically() throws Exception {
    var media =
        probeOutput(
            """
            {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000",
                         "width":64,"height":36,
                         "side_data_list":[{"side_data_type":"Frame Cropping",
                                            "crop_top":1,"crop_bottom":2,
                                            "crop_left":3,"crop_right":4}]}]}
            """);

    var video = media.getStreams(0);
    assertThat(video.getContainerCropping())
        .isEqualTo(
            ContainerCropping.newBuilder().setTop(1).setBottom(2).setLeft(3).setRight(4).build());
    assertThat(video.getWidth()).isEqualTo(64);
    assertThat(video.getHeight()).isEqualTo(36);
  }

  @Test
  @DisplayName("Should exclude pixel facts when an audio stream has a pixel format")
  void shouldExcludePixelFactsWhenAnAudioStreamHasAPixelFormat() throws Exception {
    var media =
        probeOutput(
            """
            {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"},
                        {"index":1,"codec_type":"audio","pix_fmt":"yuv422p10le"}]}
            """);

    var audio = media.getStreams(1);
    assertThat(audio.hasBitDepth()).isFalse();
    assertThat(audio.getChromaSubsampling())
        .isEqualTo(ChromaSubsampling.CHROMA_SUBSAMPLING_UNSPECIFIED);
  }

  @Test
  @DisplayName("Should omit rotation when the display matrix value is not numeric")
  void shouldOmitRotationWhenTheDisplayMatrixValueIsNotNumeric() throws Exception {
    var media =
        probeOutput(
            """
            {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000",
                         "width":1920,"height":1080,
                         "side_data_list":[{"side_data_type":"Display Matrix","rotation":"N/A"}]}]}
            """);

    var video = media.getStreams(0);
    assertThat(video.hasRotationDegrees()).isFalse();
    assertThat(video.getWidth()).isEqualTo(1920);
    assertThat(video.getHeight()).isEqualTo(1080);
  }

  @Test
  @DisplayName("Should report the display matrix rotation when the video stream has one")
  void shouldReportTheDisplayMatrixRotationWhenTheVideoStreamHasOne() throws Exception {
    // FFmpeg's -display_rotation 90 rotates the picture 90 degrees counterclockwise.
    var video = streamOf("mp4-rotated.mp4", 0);

    assertThat(video.getRotationDegrees()).isEqualTo(90);
  }

  @Test
  @DisplayName("Should end each stream at its header duration when the source is MP4")
  void shouldEndEachStreamAtItsHeaderDurationWhenTheSourceIsMp4() throws Exception {
    // The video lasts 24576 ticks of 1/12288 s and the audio 88200 ticks of 1/44100 s.
    var media = probeFixture("mp4-titled.mp4");

    assertThat(media.getStreams(0).getEndTime()).isEqualTo(seconds(2, 0));
    assertThat(media.getStreams(1).getEndTime()).isEqualTo(seconds(2, 0));
  }

  @Test
  @DisplayName("Should report the LC profile and sample rate when the stream is AAC LC")
  void shouldReportTheLcProfileAndSampleRateWhenTheStreamIsAacLc() throws Exception {
    var audio = streamOf("mp4-titled.mp4", 1);

    assertThat(audio.getAudioProfile()).isEqualTo("LC");
    assertThat(audio.getSampleRateHz()).isEqualTo(44_100);
  }

  @Test
  @DisplayName("Should flag the audio and subtitle tracks when Matroska sets FlagHearingImpaired")
  void shouldFlagTheAudioAndSubtitleTracksWhenMatroskaSetsFlagHearingImpaired() throws Exception {
    var media = probeFixture("mkv-remuxed-from-mp4.mkv");

    assertThat(media.getStreams(1).getIsHearingImpaired()).isTrue();
    assertThat(media.getStreams(3).getIsHearingImpaired()).isTrue();
  }

  @Test
  @DisplayName("Should report 8-bit 4:2:0 when the video stream is yuv420p")
  void shouldReport8Bit420WhenTheVideoStreamIsYuv420p() throws Exception {
    var video = streamOf("mp4-titled.mp4", 0);

    assertThat(video.getBitDepth()).isEqualTo(8);
    assertThat(video.getChromaSubsampling()).isEqualTo(ChromaSubsampling.CHROMA_SUBSAMPLING_420);
  }

  @Test
  @DisplayName("Should report the name tag when FFmpeg wrote an MP4 track's title")
  void shouldReportTheNameTagWhenFfmpegWroteAnMp4TracksTitle() throws Exception {
    var media = probeFixture("mp4-titled.mp4");

    assertThat(media.getStreams(1).getTitle()).isEqualTo("Commentary");
    assertThat(media.getStreams(3).getTitle()).isEqualTo("SDH");
  }

  @Test
  @DisplayName("Should report FFmpeg's default handler names unchanged when an MP4 has no others")
  void shouldReportFfmpegsDefaultHandlerNamesUnchangedWhenAnMp4HasNoOthers() throws Exception {
    var media = probeFixture("mp4-default-handlers.mp4");

    assertThat(media.getStreams(1).getHandlerName()).isEqualTo("SoundHandler");
    assertThat(media.getStreams(2).getHandlerName()).isEqualTo("SubtitleHandler");
  }

  @Test
  @DisplayName("Should omit every title when the titles take more than 16,384 bytes")
  void shouldOmitEveryTitleWhenTheTitlesTakeMoreThan16384Bytes() throws Exception {
    // 40 titles of 500 bytes take 40 x 504 = 20,160 bytes in their protobuf encoding.
    var media = probeFixture("mkv-many-titles.mkv");

    assertThat(media.getStreamTitlesOmitted()).isTrue();
    assertThat(media.getStreamsList()).noneMatch(ProbeStreamInfo::hasTitle);
  }

  @Test
  @DisplayName("Should omit every chapter when the chapters take more than 32,768 bytes")
  void shouldOmitEveryChapterWhenTheChaptersTakeMoreThan32768Bytes() throws Exception {
    // 400 chapters with 100-byte titles.
    var container = probeFixture("mkv-many-chapters.mkv").getContainer();

    assertThat(container.getChaptersList()).isEmpty();
    assertThat(container.getChaptersOmitted()).isTrue();
  }

  private static Duration seconds(long seconds, int nanos) {
    return Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();
  }

  private ProbeStreamInfo streamOf(String fixture, int index) throws Exception {
    return probeFixture(fixture).getStreamsList().stream()
        .filter(stream -> stream.getIndex() == index)
        .findFirst()
        .orElseThrow(() -> new AssertionError(fixture + " has no stream " + index));
  }

  private ProbeMediaInfo probeFixture(String fixture) throws Exception {
    try (var recorded = getClass().getResourceAsStream("/probe/" + fixture + ".json")) {
      assertThat(recorded).as("recorded fixture %s", fixture).isNotNull();
      return probeOutput(new String(recorded.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private ProbeMediaInfo probeOutput(String output) throws Exception {
    Files.writeString(sourceRoot.resolve("movie.mkv"), "source");
    var runtime = new ScriptedWorkerRuntime();
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            command ->
                ScriptedProcess.builder()
                    .output(
                        switch (command.tool()) {
                          case FFPROBE ->
                              command.arguments().contains("-read_intervals")
                                  ? new byte[0]
                                  : output.getBytes(StandardCharsets.UTF_8);
                          case FFMPEG -> new byte[0];
                        })
                    .build());

    try (var worker = workerBuilder(sourceRoot).runtime(runtime).ffprobe(executor).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), requestBuilder().setScanKeyframePackets(true).build());
      runtime.probes().drain();

      assertThat(runtime.connection().results())
          .singleElement()
          .satisfies(result -> assertThat(result.hasMedia()).as("the probe succeeds").isTrue());
      return runtime.connection().results().getFirst().getMedia();
    }
  }
}
