package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeFixture;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeOutput;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static com.streamarr.transcode.probe.SyntheticMp4.ascii;
import static com.streamarr.transcode.probe.SyntheticMp4.box;
import static com.streamarr.transcode.probe.SyntheticMp4.concat;
import static com.streamarr.transcode.probe.SyntheticMp4.int32;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Source Facts Tests")
class FfprobeExecutorSourceFactsTest {

  private static final String VIDEO_STREAM =
      "{\"index\":0,\"codec_type\":\"video\",\"time_base\":\"1/1000\",\"codec_tag_string\":\"avc1\"}";

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

  // FFmpeg's metadata dictionary takes major_brand from the ftyp box and also from the file's
  // metadata, which can name another brand, so each case writes the file that the probe reads.
  @Nested
  @DisplayName("Major Brand")
  class MajorBrandTests {

    @TempDir Path directory;

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"isom", "mp42", "M4V ", "qt  "})
    @DisplayName("Should report the ftyp box's major brand when an MP4 or MOV file names one")
    void shouldReportTheFtypBoxsMajorBrandWhenAnMp4OrMovFileNamesOne(String brand)
        throws IOException {
      var file = write(fileType(brand), box("moov"));

      assertThat(majorBrandOf(file, "isom")).contains(brand);
    }

    @Test
    @DisplayName("Should report the ftyp box's brand when the file's metadata names another")
    void shouldReportTheFtypBoxsBrandWhenTheFilesMetadataNamesAnother() throws IOException {
      // FFmpeg's mp4 muxer with -movflags use_metadata_tags keeps a QuickTime source's
      // major_brand as metadata, which replaces the ftyp box's brand in ffprobe's tags.
      var file = write(fileType("isom"), box("moov"));

      assertThat(majorBrandOf(file, "qt  ")).contains("isom");
    }

    @Test
    @DisplayName("Should report the ftyp box's brand when free space comes before the box")
    void shouldReportTheFtypBoxsBrandWhenFreeSpaceComesBeforeTheBox() throws IOException {
      var file = write(box("free", int32(0)), fileType("qt  "), box("moov"));

      assertThat(majorBrandOf(file, "qt  ")).contains("qt  ");
    }

    @Test
    @DisplayName(
        "Should report no major brand when the file's metadata names one without an ftyp box")
    void shouldReportNoMajorBrandWhenTheFilesMetadataNamesOneWithoutAnFtypBox() throws IOException {
      var file = write(box("moov"));

      assertThat(majorBrandOf(file, "isom")).isEmpty();
    }

    @Test
    @DisplayName("Should report no major brand when the ftyp box follows the movie box")
    void shouldReportNoMajorBrandWhenTheFtypBoxFollowsTheMovieBox() throws IOException {
      // FFmpeg ignores an ftyp box once the movie box has added streams.
      var file = write(box("moov"), fileType("isom"));

      assertThat(majorBrandOf(file, "isom")).isEmpty();
    }

    @Test
    @DisplayName("Should report no major brand when the ftyp box holds fewer than four bytes")
    void shouldReportNoMajorBrandWhenTheFtypBoxHoldsFewerThanFourBytes() throws IOException {
      var file = write(box("ftyp", ascii("is")), box("moov"));

      assertThat(majorBrandOf(file, "isom")).isEmpty();
    }

    @Test
    @DisplayName("Should report no major brand when the brand holds a zero byte")
    void shouldReportNoMajorBrandWhenTheBrandHoldsAZeroByte() throws IOException {
      var file = write(box("ftyp", ascii("is"), new byte[] {0, 'm'}, int32(0)), box("moov"));

      assertThat(majorBrandOf(file, "is")).isEmpty();
    }

    @Test
    @DisplayName("Should report no major brand when a top-level box runs past the end of the file")
    void shouldReportNoMajorBrandWhenATopLevelBoxRunsPastTheEndOfTheFile() throws IOException {
      // The free box's size claims 64 bytes, and the file ends after its 8-byte header.
      var file = write(int32(64), ascii("free"));

      assertThat(majorBrandOf(file, "isom")).isEmpty();
    }

    @Test
    @DisplayName("Should fail the probe attempt when the probe cannot read the file")
    void shouldFailTheProbeAttemptWhenTheProbeCannotReadTheFile() {
      var output = mp4Output(VIDEO_STREAM, ",\"tags\":{\"major_brand\":\"isom\"}");

      var result =
          RecordedFfprobe.probeResult(
              RecordedFfprobe.Answers.builder()
                  .ffprobeOutput(output)
                  .source(directory.resolve("missing.mp4"))
                  .build());

      assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
    }

    @Test
    @DisplayName("Should report the later ftyp box's brand when two come before the movie box")
    void shouldReportTheLaterFtypBoxsBrandWhenTwoComeBeforeTheMovieBox() throws IOException {
      // FFmpeg reads every ftyp box until the movie box adds streams, and each one replaces the
      // brand.
      var file = write(fileType("qt  "), fileType("isom"), box("moov"));

      assertThat(majorBrandOf(file, "isom")).contains("isom");
    }

    @Test
    @DisplayName("Should report no major brand when a Matroska file keeps a remuxed file's tag")
    void shouldReportNoMajorBrandWhenAMatroskaFileKeepsARemuxedFilesTag() {
      // FFmpeg copies an MP4 file's major_brand into Matroska as an ordinary MAJOR_BRAND tag.
      var container = probeFixture("mkv-remuxed-from-mp4.mkv").getContainer();

      assertThat(container.hasMajorBrand()).isFalse();
    }

    @Test
    @DisplayName("Should report no major brand when an FLV file starts with an ftyp box")
    void shouldReportNoMajorBrandWhenAnFlvFileStartsWithAnFtypBox() throws IOException {
      var file = write(fileType("isom"), box("moov"));
      var output =
          """
          {"streams":[%s],"format":{"format_name":"flv","tags":{"major_brand":"isom"}}}
          """
              .formatted(VIDEO_STREAM);

      var container =
          probe(RecordedFfprobe.Answers.builder().ffprobeOutput(output).source(file).build())
              .getContainer();

      assertThat(container.hasMajorBrand()).isFalse();
    }

    private Optional<String> majorBrandOf(Path file, String taggedBrand) {
      var output = mp4Output(VIDEO_STREAM, ",\"tags\":{\"major_brand\":\"" + taggedBrand + "\"}");
      var container =
          probe(RecordedFfprobe.Answers.builder().ffprobeOutput(output).source(file).build())
              .getContainer();
      return container.hasMajorBrand() ? Optional.of(container.getMajorBrand()) : Optional.empty();
    }

    private static byte[] fileType(String brand) {
      return box("ftyp", ascii(brand), int32(0), ascii(brand), ascii("isom"));
    }

    private Path write(byte[]... boxes) throws IOException {
      var file = directory.resolve("source.mp4");
      Files.write(file, concat(boxes));
      return file;
    }
  }

  private static String mp4Output(String stream, String formatFields) {
    return """
        {"streams":[%s],"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2"%s}}
        """
        .formatted(stream, formatFields);
  }
}
