package com.streamarr.transcode.probe;

import java.util.HexFormat;
import lombok.Builder;

/**
 * A Dolby Vision RPU's rpu_data_header, written field by field in the order that FFmpeg 8.1's RPU
 * parser reads it, as the payload of an HEVC or H.264 RPU NAL unit. The header takes its bit depths
 * and layer flags only when every 0x700 bit of rpu_format is clear, and its coefficient_log2_denom
 * only for coefficient_data_type 0.
 */
@Builder(toBuilder = true)
record RpuDataHeaderBits(
    int rpuNalPrefix,
    int rpuType,
    int rpuFormat,
    int vdrRpuProfile,
    int vdrRpuLevel,
    boolean vdrSeqInfoPresent,
    boolean chromaResamplingExplicitFilter,
    int coefficientDataType,
    long coefficientLog2Denom,
    int vdrRpuNormalizedIdc,
    boolean blVideoFullRange,
    long blBitDepthMinus8,
    long elBitDepthMinus8,
    long vdrBitDepthMinus8,
    boolean spatialResamplingFilter,
    boolean elSpatialResamplingFilter,
    boolean disableResidual) {

  private static final int UNESCAPED_ZERO_RUN = 2;

  /**
   * A profile 8.1 header: vdr_rpu_profile 1 without an enhancement layer, a 10-bit base layer and
   * 12-bit VDR, with fixed-point coefficients.
   */
  static RpuDataHeaderBitsBuilder profile81() {
    return builder()
        .rpuNalPrefix(25)
        .rpuType(2)
        .rpuFormat(0x012)
        .vdrRpuProfile(1)
        .vdrSeqInfoPresent(true)
        .coefficientLog2Denom(23)
        .vdrRpuNormalizedIdc(1)
        .blBitDepthMinus8(2)
        .elBitDepthMinus8(2)
        .vdrBitDepthMinus8(4)
        .disableResidual(true);
  }

  /** The header as an HEVC RPU NAL unit: type 62 on layer 0 with TemporalId 0. */
  String hevcNalUnit() {
    return "7c 01 " + escapedPayload();
  }

  /** The header as an H.264 RPU NAL unit: type 28 with nal_ref_idc 0. */
  String h264NalUnit() {
    return "1c " + escapedPayload();
  }

  /** The header's bytes up to its rbsp_stop_one_bit, without emulation prevention. */
  byte[] payload() {
    var bits =
        new Bits()
            .put(8, rpuNalPrefix)
            .put(6, rpuType)
            .put(11, rpuFormat)
            .put(4, vdrRpuProfile)
            .put(4, vdrRpuLevel)
            .flag(vdrSeqInfoPresent)
            .flag(chromaResamplingExplicitFilter)
            .put(2, coefficientDataType);
    if (coefficientDataType == 0) {
      bits.unsignedExpGolomb(coefficientLog2Denom);
    }

    bits.put(2, vdrRpuNormalizedIdc).flag(blVideoFullRange);
    if ((rpuFormat & 0x700) == 0) {
      bits.unsignedExpGolomb(blBitDepthMinus8)
          .unsignedExpGolomb(elBitDepthMinus8)
          .unsignedExpGolomb(vdrBitDepthMinus8)
          .flag(spatialResamplingFilter)
          .put(3, 0)
          .flag(elSpatialResamplingFilter)
          .flag(disableResidual);
    }

    return bits.flag(true).bytes();
  }

  private String escapedPayload() {
    var escaped = new StringBuilder();
    var zeros = 0;
    for (var value : payload()) {
      var unsigned = Byte.toUnsignedInt(value);
      if (zeros >= UNESCAPED_ZERO_RUN && unsigned <= 3) {
        escaped.append(" 03");
        zeros = 0;
      }

      escaped.append(' ').append(HexFormat.of().toHexDigits(value));
      zeros = unsigned == 0 ? zeros + 1 : 0;
    }

    return escaped.toString().trim();
  }

  private static final class Bits {

    private final StringBuilder bits = new StringBuilder();

    Bits put(int count, long value) {
      for (var bit = count - 1; bit >= 0; bit--) {
        bits.append((value >> bit) & 1);
      }

      return this;
    }

    Bits flag(boolean value) {
      return put(1, value ? 1 : 0);
    }

    /** ue(v): as many zero bits as value + 1 has bits after its leading one, then value + 1. */
    Bits unsignedExpGolomb(long value) {
      var codeNum = value + 1;
      var significantBits = 64 - Long.numberOfLeadingZeros(codeNum);
      return put(significantBits - 1, 0).put(significantBits, codeNum);
    }

    byte[] bytes() {
      while (bits.length() % 8 != 0) {
        bits.append('0');
      }

      var bytes = new byte[bits.length() / 8];
      for (var index = 0; index < bytes.length; index++) {
        bytes[index] = (byte) Integer.parseInt(bits.substring(index * 8, index * 8 + 8), 2);
      }

      return bytes;
    }
  }
}
