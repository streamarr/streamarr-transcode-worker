package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.probeFixture;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeOutput;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Source Facts Tests")
class FfprobeExecutorSourceFactsTest {

  @Nested
  @DisplayName("Sample Entry")
  class SampleEntryTests {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "mp4-dovi-p82.mp4, hvc1",
      "mp4-dovi-p5-hev1.mp4, hev1",
      "mp4-dovi-p5-dvh1.mp4, dvh1",
      "mp4-dovi-p9-avc1.mp4, avc1",
      "mp4-dovi-p9-dva1.mp4, dva1",
      "mp4-av1-10bit.mp4, av01",
      "mov-phone-60.mov, hvc1"
    })
    @DisplayName("Should report the stored sample entry when an MP4 or MOV file holds the video")
    void shouldReportTheStoredSampleEntryWhenAnMp4OrMovFileHoldsTheVideo(
        String fixture, String sampleEntry) {
      var video = streamOf(fixture, 0);

      assertThat(video.getSampleEntry()).isEqualTo(sampleEntry);
    }

    @Test
    @DisplayName(
        "Should keep the source's sample entry apart from the copy's when a Dolby Vision file"
            + " stores dvh1")
    void shouldKeepTheSourcesSampleEntryApartFromTheCopysWhenADolbyVisionFileStoresDvh1() {
      var video = streamOf("mp4-dovi-p5-dvh1.mp4", 0);

      assertThat(video.getSampleEntry()).isEqualTo("dvh1");
      assertThat(video.getCodecString()).startsWith("hvc1.");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "mkv-hdr10-static.mkv",
          "mkv-remuxed-from-mp4.mkv",
          "webm-vp9-hdr10-plus.webm",
          "mpegts-hevc-ac3.ts",
          "mpegts-dovi-p7.ts",
          "mpegts-avchd-interlaced.mts",
          "avi-h264-unknown-extradata.avi",
          "ivf-av1-10bit.ivf"
        })
    @DisplayName("Should report no sample entry when the source container stores none")
    void shouldReportNoSampleEntryWhenTheSourceContainerStoresNone(String fixture) {
      // Matroska prints a zero codec tag. MPEG-TS prints its stream type or registration
      // descriptor, such as HEVC or DOVI, and AVI and IVF print a codec FourCC: none of them is a
      // sample entry.
      var video = streamOf(fixture, 0);

      assertThat(video.hasSampleEntry()).isFalse();
    }

    @Test
    @DisplayName("Should report no sample entry for cover art when an MP4 file holds cover art")
    void shouldReportNoSampleEntryForCoverArtWhenAnMp4FileHoldsCoverArt() {
      var media = probeFixture("mp4-cover-art-first.mp4");

      assertThat(media.getStreams(0).hasSampleEntry()).isFalse();
      assertThat(media.getStreams(1).getSampleEntry()).isEqualTo("avc1");
    }

    @Test
    @DisplayName("Should report no sample entry for audio when an MP4 file holds audio")
    void shouldReportNoSampleEntryForAudioWhenAnMp4FileHoldsAudio() {
      var media = probeFixture("mp4-audio-codecs.mp4");

      assertThat(media.getStreamsList().subList(1, media.getStreamsCount()))
          .extracting(ProbeStreamInfo::getCodecType, ProbeStreamInfo::hasSampleEntry)
          .containsOnly(tuple("audio", false));
    }

    @Test
    @DisplayName(
        "Should report no sample entry when ffprobe prints an unprintable byte of the codec tag")
    void shouldReportNoSampleEntryWhenFfprobePrintsAnUnprintableByteOfTheCodecTag() {
      // ffprobe prints a byte outside letters, digits, space, period, hyphen and underscore as its
      // decimal value in brackets.
      var video =
          probeOutput(
                  mp4Output(
                      "{\"index\":0,\"codec_type\":\"video\",\"time_base\":\"1/1000\",\"codec_tag_string\":\"av[36]1\"}",
                      ""))
              .getStreams(0);

      assertThat(video.hasSampleEntry()).isFalse();
    }
  }

  @Nested
  @DisplayName("Major Brand")
  class MajorBrandTests {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "mp4-sdr8-bt709.mp4, isom",
      "mp4-dovi-p82.mp4, iso4",
      "mov-hevc-extradata-reload.mov, mp42",
    })
    @DisplayName("Should report the ftyp box's major brand when an MP4 or MOV file names one")
    void shouldReportTheFtypBoxsMajorBrandWhenAnMp4OrMovFileNamesOne(
        String fixture, String majorBrand) {
      var container = probeFixture(fixture).getContainer();

      assertThat(container.getMajorBrand()).isEqualTo(majorBrand);
    }

    @Test
    @DisplayName("Should keep the trailing spaces when a QuickTime file names the qt brand")
    void shouldKeepTheTrailingSpacesWhenAQuickTimeFileNamesTheQtBrand() {
      var container = probeFixture("mov-phone-60.mov").getContainer();

      assertThat(container.getMajorBrand()).isEqualTo("qt  ");
    }

    @Test
    @DisplayName("Should report no major brand when a Matroska file keeps a remuxed file's tag")
    void shouldReportNoMajorBrandWhenAMatroskaFileKeepsARemuxedFilesTag() {
      // FFmpeg copies an MP4 file's major_brand into Matroska as an ordinary MAJOR_BRAND tag.
      var container = probeFixture("mkv-remuxed-from-mp4.mkv").getContainer();

      assertThat(container.hasMajorBrand()).isFalse();
    }

    @Test
    @DisplayName("Should report no major brand when an MP4 file has no ftyp box")
    void shouldReportNoMajorBrandWhenAnMp4FileHasNoFtypBox() {
      var container = probeOutput(mp4Output(videoStream(), "")).getContainer();

      assertThat(container.hasMajorBrand()).isFalse();
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"qt", "is\u00e9m", "isom2"})
    @DisplayName(
        "Should report no major brand when the brand is not four printable ASCII characters")
    void shouldReportNoMajorBrandWhenTheBrandIsNotFourPrintableAsciiCharacters(String brand) {
      // FFmpeg stores the brand as a C string, so a zero byte cuts it short.
      var container =
          probeOutput(mp4Output(videoStream(), ",\"tags\":{\"major_brand\":\"" + brand + "\"}"))
              .getContainer();

      assertThat(container.hasMajorBrand()).isFalse();
    }

    private static String videoStream() {
      return "{\"index\":0,\"codec_type\":\"video\",\"time_base\":\"1/1000\",\"codec_tag_string\":\"avc1\"}";
    }
  }

  private static String mp4Output(String stream, String formatFields) {
    return """
        {"streams":[%s],"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2"%s}}
        """
        .formatted(stream, formatFields);
  }
}
