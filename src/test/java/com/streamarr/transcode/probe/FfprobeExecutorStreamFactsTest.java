package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.fixture;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeFixture;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeOutput;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ChromaSubsampling;
import build.buf.gen.streamarr.transcode.v1.ContainerCropping;
import com.google.protobuf.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Stream Facts Tests")
class FfprobeExecutorStreamFactsTest {

  @Nested
  @DisplayName("Chosen Video Stream")
  class ChosenVideoStreamTests {

    @Test
    @DisplayName(
        "Should choose the video stream after the cover art when the cover art comes first")
    void shouldChooseTheVideoStreamAfterTheCoverArtWhenTheCoverArtComesFirst() {
      var media = probeFixture("mp4-cover-art-first.mp4");

      assertThat(media.getChosenVideoStreamIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should choose the first video stream when no cover art precedes it")
    void shouldChooseTheFirstVideoStreamWhenNoCoverArtPrecedesIt() {
      var media = probeFixture("mp4-titled.mp4");

      assertThat(media.hasChosenVideoStreamIndex()).isTrue();
      assertThat(media.getChosenVideoStreamIndex()).isZero();
    }
  }

  @Nested
  @DisplayName("Rotation")
  class RotationTests {

    @Test
    @DisplayName("Should report the display matrix rotation when the video stream has one")
    void shouldReportTheDisplayMatrixRotationWhenTheVideoStreamHasOne() {
      // FFmpeg's -display_rotation 90 rotates the picture 90 degrees counterclockwise.
      var video = streamOf("mp4-rotated.mp4", 0);

      assertThat(video.getRotationDegrees()).isEqualTo(90);
    }

    @Test
    @DisplayName("Should report no rotation when the video stream has no display matrix")
    void shouldReportNoRotationWhenTheVideoStreamHasNoDisplayMatrix() {
      var video = streamOf("mp4-titled.mp4", 0);

      assertThat(video.hasRotationDegrees()).isFalse();
    }
  }

  @Nested
  @DisplayName("Container Cropping")
  class ContainerCroppingTests {

    // The clean aperture keeps the centered 56x32 of the 64x36 picture.
    private static final ContainerCropping CENTERED_56_BY_32 =
        ContainerCropping.newBuilder().setTop(2).setBottom(2).setLeft(4).setRight(4).build();

    @Test
    @DisplayName("Should report the crop of an MP4 clap box when the video stream has one")
    void shouldReportTheCropOfAnMp4ClapBoxWhenTheVideoStreamHasOne() {
      var video = streamOf("mp4-clap.mp4", 0);

      assertThat(video.getContainerCropping()).isEqualTo(CENTERED_56_BY_32);
    }

    @Test
    @DisplayName("Should report the crop of Matroska PixelCrop elements when the track has them")
    void shouldReportTheCropOfMatroskaPixelCropElementsWhenTheTrackHasThem() {
      var video = streamOf("mkv-pixel-crop.mkv", 0);

      assertThat(video.getContainerCropping()).isEqualTo(CENTERED_56_BY_32);
    }

    @Test
    @DisplayName("Should keep the size before cropping when the container crops the picture")
    void shouldKeepTheSizeBeforeCroppingWhenTheContainerCropsThePicture() {
      var video = streamOf("mp4-clap.mp4", 0);

      assertThat(video.getWidth()).isEqualTo(64);
      assertThat(video.getHeight()).isEqualTo(36);
    }

    @Test
    @DisplayName("Should report no crop when the video stream has no Frame Cropping side data")
    void shouldReportNoCropWhenTheVideoStreamHasNoFrameCroppingSideData() {
      var video = streamOf("mp4-titled.mp4", 0);

      assertThat(video.hasContainerCropping()).isFalse();
    }
  }

  @Nested
  @DisplayName("End Time")
  class EndTimeTests {

    @Test
    @DisplayName("Should omit the end time when ASF copies its play time into the stream duration")
    void shouldOmitTheEndTimeWhenAsfCopiesItsPlayTimeIntoTheStreamDuration() {
      var media =
          probeOutput(
              """
              {"format":{"format_name":"asf","start_time":"0.000000"},
               "streams":[{"index":0,"codec_type":"video","time_base":"1/1000",
                           "start_pts":46,"duration_ts":2046}]}
              """);

      assertThat(media.getStreams(0).hasEndTime()).isFalse();
    }

    @Test
    @DisplayName("Should end each video and audio stream at its MP4 header duration")
    void shouldEndEachVideoAndAudioStreamAtItsMp4HeaderDuration() {
      // The video lasts 24576 ticks of 1/12288 s and the audio 88200 ticks of 1/44100 s.
      var media = probeFixture("mp4-titled.mp4");

      assertThat(media.getStreams(0).getEndTime()).isEqualTo(seconds(2, 0));
      assertThat(media.getStreams(1).getEndTime()).isEqualTo(seconds(2, 0));
    }

    @Test
    @DisplayName(
        "Should measure each end time from the container start when the container starts late")
    void shouldMeasureEachEndTimeFromTheContainerStartWhenTheContainerStartsLate() {
      // The container starts at 1.4 s. The video starts at 128090 and lasts 180000 ticks of
      // 1/90000 s, so it ends at 308090 / 90000 - 1.4 = 2.0232222... s. The audio starts at 126000
      // and lasts 171363 ticks, so it ends at 297363 / 90000 - 1.4 = 1.9040333... s.
      var media = probeFixture("mpegts-empty-audio.ts");

      assertThat(media.getStreams(0).getEndTime()).isEqualTo(seconds(2, 23_222_222));
      assertThat(media.getStreams(1).getEndTime()).isEqualTo(seconds(1, 904_033_333));
    }

    @Test
    @DisplayName("Should end a Matroska track at its start plus its DURATION tag")
    void shouldEndAMatroskaTrackAtItsStartPlusItsDurationTag() {
      var media =
          probeOutput(
              """
              {"format":{"start_time":"0.000000",
                         "tags":{"encoder":"libebml v1.4.5 + libmatroska v1.7.1"}},
               "streams":[{"index":0,"codec_type":"video","time_base":"1/1000","start_pts":500,
                           "tags":{"DURATION":"00:00:02.000000000"}}]}
              """);

      assertThat(media.getStreams(0).getEndTime()).isEqualTo(seconds(2, 500_000_000));
    }

    @Test
    @DisplayName("Should read a DURATION tag with a language suffix when the tag has one")
    void shouldReadADurationTagWithALanguageSuffixWhenTheTagHasOne() {
      var media =
          probeOutput(
              """
              {"format":{"start_time":"0.000000"},
               "streams":[{"index":0,"codec_type":"video","time_base":"1/1000","start_pts":500,
                           "tags":{"DURATION-eng":"01:02:03.250000000"}}]}
              """);

      assertThat(media.getStreams(0).getEndTime()).isEqualTo(seconds(3723, 750_000_000));
    }

    @Test
    @DisplayName("Should end a track at its DURATION tag when FFmpeg wrote the Matroska file")
    void shouldEndATrackAtItsDurationTagWhenFfmpegWroteTheMatroskaFile() {
      // FFmpeg's Matroska muxer writes the track's last timestamp plus that packet's duration as
      // DURATION. The MP4 source's 2 s video starts at 0.023 s after the remux, and its DURATION
      // tag says 00:00:02.023000000. The audio starts at 0 and its tag says the same.
      var media = probeFixture("mkv-remuxed-from-mp4.mkv");

      assertThat(media.getStreams(0).getEndTime()).isEqualTo(seconds(2, 23_000_000));
      assertThat(media.getStreams(1).getEndTime()).isEqualTo(seconds(2, 23_000_000));
    }

    @Test
    @DisplayName("Should measure a DURATION tag from the container start when FFmpeg wrote it late")
    void shouldMeasureADurationTagFromTheContainerStartWhenFfmpegWroteItLate() {
      // The 2 s video starts at 10 s, and FFmpeg wrote its DURATION tag as 00:00:12.000000000.
      var video = streamOf("mkv-offset-chapters.mkv", 0);

      assertThat(video.getEndTime()).isEqualTo(seconds(2, 0));
    }

    @Test
    @DisplayName("Should report no end time when the header states no duration")
    void shouldReportNoEndTimeWhenTheHeaderStatesNoDuration() {
      var media = probeFixture("mkv-pipe-hevc-10bit.mkv");

      assertThat(media.getStreams(0).hasEndTime()).isFalse();
      assertThat(media.getStreams(1).hasEndTime()).isFalse();
    }

    @Test
    @DisplayName("Should report no end time when the stream is a subtitle stream")
    void shouldReportNoEndTimeWhenTheStreamIsASubtitleStream() {
      // The mov_text stream's header states a duration of 1 s.
      var subtitle = streamOf("mp4-titled.mp4", 3);

      assertThat(subtitle.hasEndTime()).isFalse();
    }

    private static Duration seconds(long seconds, int nanos) {
      return Duration.newBuilder().setSeconds(seconds).setNanos(nanos).build();
    }
  }

  @Nested
  @DisplayName("Audio Sample Rate and Profile")
  class AudioTests {

    @Test
    @DisplayName("Should report the Atmos profile and sample rate when the stream is E-AC-3 Atmos")
    void shouldReportTheAtmosProfileAndSampleRateWhenTheStreamIsEac3Atmos() {
      var audio = streamOf("mkv-eac3-atmos-dts-hd-ma.mkv", 1);

      assertThat(audio.getAudioProfile()).isEqualTo("Dolby Digital Plus + Dolby Atmos");
      assertThat(audio.getSampleRateHz()).isEqualTo(48_000);
    }

    @Test
    @DisplayName("Should report the DTS-HD MA profile when the stream is DTS-HD Master Audio")
    void shouldReportTheDtsHdMaProfileWhenTheStreamIsDtsHdMasterAudio() {
      var audio = streamOf("mkv-eac3-atmos-dts-hd-ma.mkv", 2);

      assertThat(audio.getAudioProfile()).isEqualTo("DTS-HD MA");
    }

    @Test
    @DisplayName("Should report the LC profile and sample rate when the stream is AAC LC")
    void shouldReportTheLcProfileAndSampleRateWhenTheStreamIsAacLc() {
      var audio = streamOf("mp4-titled.mp4", 1);

      assertThat(audio.getAudioProfile()).isEqualTo("LC");
      assertThat(audio.getSampleRateHz()).isEqualTo(44_100);
    }

    @Test
    @DisplayName("Should report the profile number as text when FFmpeg has no name for the profile")
    void shouldReportTheProfileNumberAsTextWhenFfmpegHasNoNameForTheProfile() {
      // ER AAC LC is audio object type 17, which FFmpeg stores as profile 16 and does not name.
      var audio = streamOf("mp4-er-aac-lc.mp4", 1);

      assertThat(audio.getAudioProfile()).isEqualTo("16");
    }

    @Test
    @DisplayName("Should report no profile when ffprobe reports none")
    void shouldReportNoProfileWhenFfprobeReportsNone() {
      var audio = streamOf("mkv-pipe-hevc-10bit.mkv", 1);

      assertThat(audio.getCodec()).isEqualTo("opus");
      assertThat(audio.hasAudioProfile()).isFalse();
    }

    @Test
    @DisplayName("Should report a sample rate below 16 kHz when the stream is low-rate MP3")
    void shouldReportASampleRateBelow16KhzWhenTheStreamIsLowRateMp3() {
      var audio = streamOf("mkv-mp3-11khz.mkv", 1);

      assertThat(audio.getSampleRateHz()).isEqualTo(11_025);
    }

    @Test
    @DisplayName("Should report no sample rate when ffprobe reports a sample rate of 0")
    void shouldReportNoSampleRateWhenFfprobeReportsASampleRateOf0() {
      // The second AAC stream has no packets, so ffprobe reports sample_rate "0".
      var audio = streamOf("mpegts-empty-audio.ts", 2);

      assertThat(audio.hasSampleRateHz()).isFalse();
    }

    @Test
    @DisplayName("Should report no sample rate when ffprobe reports none")
    void shouldReportNoSampleRateWhenFfprobeReportsNone() {
      var media =
          probeOutput(
              """
              {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"},
                          {"index":1,"codec_type":"audio","codec_name":"aac"}]}
              """);

      assertThat(media.getStreams(1).hasSampleRateHz()).isFalse();
    }

    @Test
    @DisplayName("Should report no audio profile when the stream is video")
    void shouldReportNoAudioProfileWhenTheStreamIsVideo() {
      // ffprobe reports the H.264 profile Constrained Baseline for this video stream.
      var video = streamOf("mp4-titled.mp4", 0);

      assertThat(video.hasAudioProfile()).isFalse();
      assertThat(video.hasSampleRateHz()).isFalse();
    }
  }

  @Nested
  @DisplayName("Hearing-Impaired Flag")
  class HearingImpairedTests {

    @Test
    @DisplayName("Should flag the audio and subtitle tracks when Matroska sets FlagHearingImpaired")
    void shouldFlagTheAudioAndSubtitleTracksWhenMatroskaSetsFlagHearingImpaired() {
      var media = probeFixture("mkv-remuxed-from-mp4.mkv");

      assertThat(media.getStreams(1).getIsHearingImpaired()).isTrue();
      assertThat(media.getStreams(3).getIsHearingImpaired()).isTrue();
    }

    @Test
    @DisplayName("Should flag the subtitle stream when an MP4 kind box marks it as a caption")
    void shouldFlagTheSubtitleStreamWhenAnMp4KindBoxMarksItAsACaption() {
      var subtitle = streamOf("mp4-titled.mp4", 3);

      assertThat(subtitle.getIsHearingImpaired()).isTrue();
    }

    @Test
    @DisplayName("Should report false when the container does not mark the stream")
    void shouldReportFalseWhenTheContainerDoesNotMarkTheStream() {
      var media = probeFixture("mp4-default-handlers.mp4");

      assertThat(media.getStreams(1).hasIsHearingImpaired()).isTrue();
      assertThat(media.getStreams(1).getIsHearingImpaired()).isFalse();
      assertThat(media.getStreams(2).hasIsHearingImpaired()).isTrue();
      assertThat(media.getStreams(2).getIsHearingImpaired()).isFalse();
    }

    @Test
    @DisplayName("Should report no flag when the stream is video")
    void shouldReportNoFlagWhenTheStreamIsVideo() {
      var video = streamOf("mkv-remuxed-from-mp4.mkv", 0);

      assertThat(video.hasIsHearingImpaired()).isFalse();
    }

    @Test
    @DisplayName("Should report no flag when ffprobe reports no disposition")
    void shouldReportNoFlagWhenFfprobeReportsNoDisposition() {
      var media =
          probeOutput(
              """
              {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"},
                          {"index":1,"codec_type":"subtitle","codec_name":"subrip"}]}
              """);

      assertThat(media.getStreams(1).hasIsHearingImpaired()).isFalse();
    }
  }

  @Nested
  @DisplayName("Pixel Format")
  class PixelFormatTests {

    @Test
    @DisplayName("Should report 8-bit 4:2:0 when the video stream is yuv420p")
    void shouldReport8Bit420WhenTheVideoStreamIsYuv420p() {
      var video = streamOf("mp4-titled.mp4", 0);

      assertThat(video.getBitDepth()).isEqualTo(8);
      assertThat(video.getChromaSubsampling()).isEqualTo(ChromaSubsampling.CHROMA_SUBSAMPLING_420);
    }

    @Test
    @DisplayName("Should report 10-bit 4:2:2 when the video stream is yuv422p10le")
    void shouldReport10Bit422WhenTheVideoStreamIsYuv422p10le() {
      var video = streamOf("mkv-pipe-hevc-10bit.mkv", 0);

      assertThat(video.getBitDepth()).isEqualTo(10);
      assertThat(video.getChromaSubsampling()).isEqualTo(ChromaSubsampling.CHROMA_SUBSAMPLING_422);
    }

    @Test
    @DisplayName(
        "Should report no bit depth and no subsampling when ffprobe reports no pixel format")
    void shouldReportNoBitDepthAndNoSubsamplingWhenFfprobeReportsNoPixelFormat() {
      var video = probeOutput(videoStreamWithPixelFormat(null)).getStreams(0);

      assertThat(video.hasBitDepth()).isFalse();
      assertThat(video.getChromaSubsampling())
          .isEqualTo(ChromaSubsampling.CHROMA_SUBSAMPLING_UNSPECIFIED);
    }

    @Test
    @DisplayName("Should report no pixel format facts when the stream is not video")
    void shouldReportNoPixelFormatFactsWhenTheStreamIsNotVideo() {
      var audio = streamOf("mp4-titled.mp4", 1);

      assertThat(audio.hasBitDepth()).isFalse();
      assertThat(audio.getChromaSubsampling())
          .isEqualTo(ChromaSubsampling.CHROMA_SUBSAMPLING_UNSPECIFIED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("pixelFormats")
    @DisplayName(
        "Should report FFmpeg's first component depth and subsampling when a pixel format is known")
    void shouldReportFfmpegsFirstComponentDepthAndSubsamplingWhenAPixelFormatIsKnown(
        String name, JsonNode descriptor) {
      var video = probeOutput(videoStreamWithPixelFormat(name)).getStreams(0);

      assertThat(video.getBitDepth())
          .isEqualTo(descriptor.path("components").path(0).path("bit_depth").asInt());
      assertThat(video.getChromaSubsampling()).isEqualTo(subsamplingOf(descriptor));
    }

    /**
     * Every pixel format that the pinned image's ffprobe describes, except hardware formats, which
     * hold no samples, and Bayer formats, whose first component is not a whole sample.
     */
    static Stream<Arguments> pixelFormats() {
      var descriptors =
          new ObjectMapper().readTree(fixture("pixel-formats")).path("pixel_formats").values();
      return descriptors.stream()
          .filter(descriptor -> descriptor.path("flags").path("hwaccel").asInt() == 0)
          .filter(descriptor -> !descriptor.path("name").asString().startsWith("bayer_"))
          .map(descriptor -> Arguments.of(descriptor.path("name").asString(), descriptor));
    }
  }

  /**
   * A YCbCr format subsamples chroma by its log2 chroma shifts. A format with fewer than three
   * components has no chroma. RGB, paletted and CIE XYZ formats have no chroma planes to subsample.
   */
  private static ChromaSubsampling subsamplingOf(JsonNode descriptor) {
    var flags = descriptor.path("flags");
    if (flags.path("rgb").asInt() == 1
        || flags.path("palette").asInt() == 1
        || descriptor.path("name").asString().startsWith("xyz")) {
      return ChromaSubsampling.CHROMA_SUBSAMPLING_UNSPECIFIED;
    }

    if (descriptor.path("nb_components").asInt() < 3) {
      return ChromaSubsampling.CHROMA_SUBSAMPLING_MONOCHROME;
    }

    var shifts =
        descriptor.path("log2_chroma_w").asInt() + "x" + descriptor.path("log2_chroma_h").asInt();
    return switch (shifts) {
      case "1x1" -> ChromaSubsampling.CHROMA_SUBSAMPLING_420;
      case "1x0" -> ChromaSubsampling.CHROMA_SUBSAMPLING_422;
      case "0x0" -> ChromaSubsampling.CHROMA_SUBSAMPLING_444;
      default -> ChromaSubsampling.CHROMA_SUBSAMPLING_UNSPECIFIED;
    };
  }

  private static String videoStreamWithPixelFormat(String pixelFormat) {
    var field = pixelFormat == null ? "" : ",\"pix_fmt\":\"" + pixelFormat + "\"";
    return """
        {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"%s}]}
        """
        .formatted(field);
  }
}
