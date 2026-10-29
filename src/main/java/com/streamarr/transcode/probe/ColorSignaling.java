package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import build.buf.gen.streamarr.transcode.v1.ColorPrimaries;
import build.buf.gen.streamarr.transcode.v1.ColorRange;
import build.buf.gen.streamarr.transcode.v1.MatrixCoefficients;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.TransferCharacteristics;
import tools.jackson.databind.JsonNode;

/**
 * A video stream's color signaling, mapped from the names that ffprobe prints for FFmpeg's color
 * enums. A name that the contract does not list, and a missing field, map to UNSPECIFIED.
 */
final class ColorSignaling {

  // The names that ffprobe prints in more than one of FFmpeg's color enums.
  private static final String BT709 = "bt709";
  private static final String BT470M = "bt470m";
  private static final String BT470BG = "bt470bg";
  private static final String SMPTE170M = "smpte170m";
  private static final String SMPTE240M = "smpte240m";
  private static final String SMPTE428 = "smpte428";

  private ColorSignaling() {}

  static void add(JsonNode source, ProbeStreamInfo.Builder stream) {
    stream
        .setColorPrimaries(primaries(text(source, "color_primaries").orElse("")))
        .setTransferCharacteristics(transfer(source))
        .setMatrixCoefficients(matrix(text(source, "color_space").orElse("")))
        .setColorRange(range(text(source, "color_range").orElse("")));
  }

  private static ColorPrimaries primaries(String name) {
    return switch (name) {
      case BT709 -> ColorPrimaries.COLOR_PRIMARIES_BT709;
      case BT470M -> ColorPrimaries.COLOR_PRIMARIES_BT470M;
      case BT470BG -> ColorPrimaries.COLOR_PRIMARIES_BT470BG;
      case SMPTE170M -> ColorPrimaries.COLOR_PRIMARIES_SMPTE170M;
      case SMPTE240M -> ColorPrimaries.COLOR_PRIMARIES_SMPTE240M;
      case "film" -> ColorPrimaries.COLOR_PRIMARIES_FILM;
      case "bt2020" -> ColorPrimaries.COLOR_PRIMARIES_BT2020;
      case SMPTE428 -> ColorPrimaries.COLOR_PRIMARIES_SMPTE428;
      case "smpte431" -> ColorPrimaries.COLOR_PRIMARIES_SMPTE431;
      case "smpte432" -> ColorPrimaries.COLOR_PRIMARIES_SMPTE432;
      case "ebu3213" -> ColorPrimaries.COLOR_PRIMARIES_EBU3213;
      default -> ColorPrimaries.COLOR_PRIMARIES_UNSPECIFIED;
    };
  }

  /** The stream's signaled transfer, from the name that ffprobe prints. */
  static TransferCharacteristics transfer(JsonNode source) {
    return switch (text(source, "color_transfer").orElse("")) {
      case BT709 -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_BT709;
      case BT470M -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_BT470M;
      case BT470BG -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_BT470BG;
      case SMPTE170M -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_SMPTE170M;
      case SMPTE240M -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_SMPTE240M;
      case "linear" -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_LINEAR;
      case "log100" -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_LOG100;
      case "log316" -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_LOG316;
      case "iec61966-2-4" -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_IEC61966_2_4;
      case "bt1361e" -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_BT1361;
      case "iec61966-2-1" -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_IEC61966_2_1;
      case "bt2020-10" -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_BT2020_10;
      case "bt2020-12" -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_BT2020_12;
      case "smpte2084" -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_SMPTE2084;
      case SMPTE428 -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_SMPTE428;
      case "arib-std-b67" -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_ARIB_STD_B67;
      default -> TransferCharacteristics.TRANSFER_CHARACTERISTICS_UNSPECIFIED;
    };
  }

  private static MatrixCoefficients matrix(String name) {
    return switch (name) {
      case "gbr" -> MatrixCoefficients.MATRIX_COEFFICIENTS_IDENTITY;
      case BT709 -> MatrixCoefficients.MATRIX_COEFFICIENTS_BT709;
      case "fcc" -> MatrixCoefficients.MATRIX_COEFFICIENTS_FCC;
      case BT470BG -> MatrixCoefficients.MATRIX_COEFFICIENTS_BT470BG;
      case SMPTE170M -> MatrixCoefficients.MATRIX_COEFFICIENTS_SMPTE170M;
      case SMPTE240M -> MatrixCoefficients.MATRIX_COEFFICIENTS_SMPTE240M;
      case "ycgco" -> MatrixCoefficients.MATRIX_COEFFICIENTS_YCGCO;
      case "bt2020nc" -> MatrixCoefficients.MATRIX_COEFFICIENTS_BT2020_NCL;
      case "bt2020c" -> MatrixCoefficients.MATRIX_COEFFICIENTS_BT2020_CL;
      case "smpte2085" -> MatrixCoefficients.MATRIX_COEFFICIENTS_SMPTE2085;
      case "chroma-derived-nc" -> MatrixCoefficients.MATRIX_COEFFICIENTS_CHROMA_DERIVED_NCL;
      case "chroma-derived-c" -> MatrixCoefficients.MATRIX_COEFFICIENTS_CHROMA_DERIVED_CL;
      case "ictcp" -> MatrixCoefficients.MATRIX_COEFFICIENTS_ICTCP;
      default -> MatrixCoefficients.MATRIX_COEFFICIENTS_UNSPECIFIED;
    };
  }

  private static ColorRange range(String name) {
    return switch (name) {
      case "tv" -> ColorRange.COLOR_RANGE_LIMITED;
      case "pc" -> ColorRange.COLOR_RANGE_FULL;
      default -> ColorRange.COLOR_RANGE_UNSPECIFIED;
    };
  }
}
