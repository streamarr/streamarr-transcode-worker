package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.probeOutput;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ColorPrimaries;
import build.buf.gen.streamarr.transcode.v1.ColorRange;
import build.buf.gen.streamarr.transcode.v1.MatrixCoefficients;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.TransferCharacteristics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Color Tests")
class FfprobeExecutorColorTest {

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "bt709, COLOR_PRIMARIES_BT709",
    "bt470m, COLOR_PRIMARIES_BT470M",
    "bt470bg, COLOR_PRIMARIES_BT470BG",
    "smpte170m, COLOR_PRIMARIES_SMPTE170M",
    "smpte240m, COLOR_PRIMARIES_SMPTE240M",
    "film, COLOR_PRIMARIES_FILM",
    "bt2020, COLOR_PRIMARIES_BT2020",
    "smpte428, COLOR_PRIMARIES_SMPTE428",
    "smpte431, COLOR_PRIMARIES_SMPTE431",
    "smpte432, COLOR_PRIMARIES_SMPTE432",
    "ebu3213, COLOR_PRIMARIES_EBU3213",
    "vgamut, COLOR_PRIMARIES_UNSPECIFIED",
    "unknown, COLOR_PRIMARIES_UNSPECIFIED",
    "reserved, COLOR_PRIMARIES_UNSPECIFIED"
  })
  @DisplayName("Should map the primaries when ffprobe names them")
  void shouldMapThePrimariesWhenFfprobeNamesThem(String name, ColorPrimaries primaries) {
    assertThat(videoStream("color_primaries", name).getColorPrimaries()).isEqualTo(primaries);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "bt709, TRANSFER_CHARACTERISTICS_BT709",
    "bt470m, TRANSFER_CHARACTERISTICS_BT470M",
    "bt470bg, TRANSFER_CHARACTERISTICS_BT470BG",
    "smpte170m, TRANSFER_CHARACTERISTICS_SMPTE170M",
    "smpte240m, TRANSFER_CHARACTERISTICS_SMPTE240M",
    "linear, TRANSFER_CHARACTERISTICS_LINEAR",
    "log100, TRANSFER_CHARACTERISTICS_LOG100",
    "log316, TRANSFER_CHARACTERISTICS_LOG316",
    "iec61966-2-4, TRANSFER_CHARACTERISTICS_IEC61966_2_4",
    "bt1361e, TRANSFER_CHARACTERISTICS_BT1361",
    "iec61966-2-1, TRANSFER_CHARACTERISTICS_IEC61966_2_1",
    "bt2020-10, TRANSFER_CHARACTERISTICS_BT2020_10",
    "bt2020-12, TRANSFER_CHARACTERISTICS_BT2020_12",
    "smpte2084, TRANSFER_CHARACTERISTICS_SMPTE2084",
    "smpte428, TRANSFER_CHARACTERISTICS_SMPTE428",
    "arib-std-b67, TRANSFER_CHARACTERISTICS_ARIB_STD_B67",
    "unknown, TRANSFER_CHARACTERISTICS_UNSPECIFIED",
    "reserved, TRANSFER_CHARACTERISTICS_UNSPECIFIED"
  })
  @DisplayName("Should map the transfer when ffprobe names it")
  void shouldMapTheTransferWhenFfprobeNamesIt(String name, TransferCharacteristics transfer) {
    assertThat(videoStream("color_transfer", name).getTransferCharacteristics())
        .isEqualTo(transfer);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "gbr, MATRIX_COEFFICIENTS_IDENTITY",
    "bt709, MATRIX_COEFFICIENTS_BT709",
    "fcc, MATRIX_COEFFICIENTS_FCC",
    "bt470bg, MATRIX_COEFFICIENTS_BT470BG",
    "smpte170m, MATRIX_COEFFICIENTS_SMPTE170M",
    "smpte240m, MATRIX_COEFFICIENTS_SMPTE240M",
    "ycgco, MATRIX_COEFFICIENTS_YCGCO",
    "bt2020nc, MATRIX_COEFFICIENTS_BT2020_NCL",
    "bt2020c, MATRIX_COEFFICIENTS_BT2020_CL",
    "smpte2085, MATRIX_COEFFICIENTS_SMPTE2085",
    "chroma-derived-nc, MATRIX_COEFFICIENTS_CHROMA_DERIVED_NCL",
    "chroma-derived-c, MATRIX_COEFFICIENTS_CHROMA_DERIVED_CL",
    "ictcp, MATRIX_COEFFICIENTS_ICTCP",
    "ipt-c2, MATRIX_COEFFICIENTS_UNSPECIFIED",
    "ycgco-re, MATRIX_COEFFICIENTS_UNSPECIFIED",
    "ycgco-ro, MATRIX_COEFFICIENTS_UNSPECIFIED",
    "unknown, MATRIX_COEFFICIENTS_UNSPECIFIED",
    "reserved, MATRIX_COEFFICIENTS_UNSPECIFIED"
  })
  @DisplayName("Should map the matrix when ffprobe names it")
  void shouldMapTheMatrixWhenFfprobeNamesIt(String name, MatrixCoefficients matrix) {
    assertThat(videoStream("color_space", name).getMatrixCoefficients()).isEqualTo(matrix);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "tv, COLOR_RANGE_LIMITED",
    "pc, COLOR_RANGE_FULL",
    "unknown, COLOR_RANGE_UNSPECIFIED"
  })
  @DisplayName("Should map the range when ffprobe names it")
  void shouldMapTheRangeWhenFfprobeNamesIt(String name, ColorRange range) {
    assertThat(videoStream("color_range", name).getColorRange()).isEqualTo(range);
  }

  @Test
  @DisplayName("Should report BT.2020 PQ in limited range when the pinned image probes HDR10")
  void shouldReportBt2020PqInLimitedRangeWhenThePinnedImageProbesHdr10() {
    var video = streamOf("mp4-dovi-p81.mp4", 0);

    assertThat(video.getColorPrimaries()).isEqualTo(ColorPrimaries.COLOR_PRIMARIES_BT2020);
    assertThat(video.getTransferCharacteristics())
        .isEqualTo(TransferCharacteristics.TRANSFER_CHARACTERISTICS_SMPTE2084);
    assertThat(video.getMatrixCoefficients())
        .isEqualTo(MatrixCoefficients.MATRIX_COEFFICIENTS_BT2020_NCL);
    assertThat(video.getColorRange()).isEqualTo(ColorRange.COLOR_RANGE_LIMITED);
  }

  @Test
  @DisplayName("Should leave every color field unspecified when ffprobe reports no color signaling")
  void shouldLeaveEveryColorFieldUnspecifiedWhenFfprobeReportsNoColorSignaling() {
    var video = streamOf("mkv-pixel-crop.mkv", 0);

    assertThat(video.getColorPrimaries()).isEqualTo(ColorPrimaries.COLOR_PRIMARIES_UNSPECIFIED);
    assertThat(video.getTransferCharacteristics())
        .isEqualTo(TransferCharacteristics.TRANSFER_CHARACTERISTICS_UNSPECIFIED);
    assertThat(video.getMatrixCoefficients())
        .isEqualTo(MatrixCoefficients.MATRIX_COEFFICIENTS_UNSPECIFIED);
    assertThat(video.getColorRange()).isEqualTo(ColorRange.COLOR_RANGE_UNSPECIFIED);
  }

  private static ProbeStreamInfo videoStream(String field, String name) {
    return probeOutput(
            """
            {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000","%s":"%s"}]}
            """
                .formatted(field, name))
        .getStreams(0);
  }
}
