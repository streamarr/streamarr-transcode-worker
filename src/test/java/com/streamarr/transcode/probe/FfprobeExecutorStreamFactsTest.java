package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.fixture;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeFixture;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeOutput;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ChromaSubsampling;
import build.buf.gen.streamarr.transcode.v1.ContainerCropping;
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
