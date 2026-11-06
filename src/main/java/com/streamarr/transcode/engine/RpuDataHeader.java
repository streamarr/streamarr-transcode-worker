package com.streamarr.transcode.engine;

import lombok.Builder;

/**
 * The rpu_data_header of a Dolby Vision RPU, after its NAL prefix: which profile the RPU serves,
 * how its coefficients are coded, the bit depths of its layers, and whether a residual, an
 * enhancement-layer filter, DM metadata and a mapping of its own follow. {@code
 * enhancementLayerBitDepthField} keeps the ue(v) that holds el_bit_depth_minus8 in its low 8 bits
 * and ext_mapping_idc above them, so that a write restores it exactly.
 */
@Builder(toBuilder = true)
record RpuDataHeader(
    int rpuFormat,
    int vdrRpuProfile,
    int vdrRpuLevel,
    boolean sequenceInformationPresent,
    boolean chromaResamplingExplicitFilter,
    int coefficientDataType,
    long coefficientLog2Denom,
    int vdrRpuNormalizedIdc,
    boolean baseLayerFullRange,
    long baseLayerBitDepthMinus8,
    long enhancementLayerBitDepthField,
    long vdrBitDepthMinus8,
    boolean spatialResamplingFilter,
    int reservedZero3Bits,
    boolean enhancementLayerSpatialResamplingFilter,
    boolean disableResidual,
    boolean dmMetadataPresent,
    boolean usePreviousVdrRpu,
    long previousVdrRpuId) {

  private static final int RPU_TYPE = 2;
  // rpu_format bits that, when set, leave the bit depths and layer flags out of the header.
  private static final int FORMAT_WITHOUT_BIT_DEPTHS = 0x700;
  private static final int FIXED_POINT_COEFFICIENTS = 0;
  private static final int FLOATING_POINT_COEFFICIENTS = 1;
  private static final int FLOATING_POINT_COEFFICIENT_BITS = 32;
  private static final long LARGEST_COEFFICIENT_LOG2_DENOM = 23;
  private static final int COMPRESSED_DM_METADATA = 1;
  // The VDR bit depth that tells profile 7 from profile 4, both of which carry a residual.
  private static final long PROFILE_7_VDR_BIT_DEPTH_MINUS8 = 4;

  /**
   * @throws DolbyVisionRpuException when the RPU is not of type 2 or codes its coefficients in
   *     neither fixed nor floating point
   */
  static RpuDataHeader parse(RpuBitReader reader) {
    if (reader.bits(6) != RPU_TYPE) {
      throw new DolbyVisionRpuException("the RPU is not of type 2");
    }

    var rpuFormat = (int) reader.bits(11);
    var header =
        RpuDataHeader.builder()
            .rpuFormat(rpuFormat)
            .vdrRpuProfile((int) reader.bits(4))
            .vdrRpuLevel((int) reader.bits(4));
    var sequenceInformationPresent = reader.flag();
    header.sequenceInformationPresent(sequenceInformationPresent);
    if (sequenceInformationPresent) {
      parseSequenceInformation(reader, header, rpuFormat);
    }

    header.dmMetadataPresent(reader.flag());
    var usePreviousVdrRpu = reader.flag();
    header.usePreviousVdrRpu(usePreviousVdrRpu);
    if (usePreviousVdrRpu) {
      header.previousVdrRpuId(reader.unsignedExpGolomb());
    }

    return header.build();
  }

  // The denominator sizes every fixed-point coefficient fraction, so a value past the syntax's
  // bound
  // would size them as no field of the RPU can be.
  private static long coefficientLog2Denom(RpuBitReader reader) {
    var denominator = reader.unsignedExpGolomb();
    if (denominator > LARGEST_COEFFICIENT_LOG2_DENOM) {
      throw new DolbyVisionRpuException(
          "a coefficient_log2_denom of "
              + denominator
              + " where the syntax allows up to "
              + LARGEST_COEFFICIENT_LOG2_DENOM);
    }

    return denominator;
  }

  private static void parseSequenceInformation(
      RpuBitReader reader, RpuDataHeaderBuilder header, int rpuFormat) {
    header.chromaResamplingExplicitFilter(reader.flag());
    var coefficientDataType = (int) reader.bits(2);
    if (coefficientDataType != FIXED_POINT_COEFFICIENTS
        && coefficientDataType != FLOATING_POINT_COEFFICIENTS) {
      throw new DolbyVisionRpuException(
          "coefficient_data_type " + coefficientDataType + " is not 0 or 1");
    }

    header.coefficientDataType(coefficientDataType);
    if (coefficientDataType == FIXED_POINT_COEFFICIENTS) {
      header.coefficientLog2Denom(coefficientLog2Denom(reader));
    }

    header.vdrRpuNormalizedIdc((int) reader.bits(2)).baseLayerFullRange(reader.flag());
    if ((rpuFormat & FORMAT_WITHOUT_BIT_DEPTHS) != 0) {
      return;
    }

    header
        .baseLayerBitDepthMinus8(reader.unsignedExpGolomb())
        .enhancementLayerBitDepthField(reader.unsignedExpGolomb())
        .vdrBitDepthMinus8(reader.unsignedExpGolomb())
        .spatialResamplingFilter(reader.flag())
        .reservedZero3Bits((int) reader.bits(3))
        .enhancementLayerSpatialResamplingFilter(reader.flag())
        .disableResidual(reader.flag());
  }

  void write(RpuBitWriter writer) {
    writer.bits(RPU_TYPE, 6).bits(rpuFormat, 11).bits(vdrRpuProfile, 4).bits(vdrRpuLevel, 4);
    writer.flag(sequenceInformationPresent);
    if (sequenceInformationPresent) {
      writeSequenceInformation(writer);
    }

    writer.flag(dmMetadataPresent).flag(usePreviousVdrRpu);
    if (usePreviousVdrRpu) {
      writer.unsignedExpGolomb(previousVdrRpuId);
    }
  }

  private void writeSequenceInformation(RpuBitWriter writer) {
    writer.flag(chromaResamplingExplicitFilter).bits(coefficientDataType, 2);
    if (coefficientDataType == FIXED_POINT_COEFFICIENTS) {
      writer.unsignedExpGolomb(coefficientLog2Denom);
    }

    writer.bits(vdrRpuNormalizedIdc, 2).flag(baseLayerFullRange);
    if (holdsBitDepths()) {
      writer
          .unsignedExpGolomb(baseLayerBitDepthMinus8)
          .unsignedExpGolomb(enhancementLayerBitDepthField)
          .unsignedExpGolomb(vdrBitDepthMinus8)
          .flag(spatialResamplingFilter)
          .bits(reservedZero3Bits, 3)
          .flag(enhancementLayerSpatialResamplingFilter)
          .flag(disableResidual);
    }
  }

  /** The bits of each coefficient's fraction: the log2 denominator, or 32 in floating point. */
  int coefficientBits() {
    if (coefficientDataType == FIXED_POINT_COEFFICIENTS) {
      return (int) coefficientLog2Denom;
    }

    return FLOATING_POINT_COEFFICIENT_BITS;
  }

  /** Whether each coefficient has an integer part, coded as se(v) or ue(v), before its bits. */
  boolean codesIntegerParts() {
    return coefficientDataType == FIXED_POINT_COEFFICIENTS;
  }

  /** Whether the header names the layers' bit depths and the residual, which rpu_format decides. */
  boolean holdsBitDepths() {
    return (rpuFormat & FORMAT_WITHOUT_BIT_DEPTHS) == 0;
  }

  /** Whether the RPU carries a residual for an enhancement layer: the NLQ fields follow. */
  boolean carriesResidual() {
    return holdsBitDepths() && !disableResidual;
  }

  /** Whether the DM metadata holds only its ids, as reserved_zero_3bits 1 says. */
  boolean compressesDmMetadata() {
    return reservedZero3Bits == COMPRESSED_DM_METADATA;
  }

  int baseLayerBitDepth() {
    return (int) baseLayerBitDepthMinus8 + 8;
  }

  int enhancementLayerBitDepth() {
    return (int) (enhancementLayerBitDepthField & 0xFF) + 8;
  }

  /**
   * The Dolby Vision profile that the header serves, as libdovi and FFmpeg derive it: 5 for
   * vdr_rpu_profile 0 with a full-range base, 7 or 4 for vdr_rpu_profile 1 with a residual, 8 for
   * vdr_rpu_profile 1 without one, and 0 for any other.
   */
  int dolbyVisionProfile() {
    if (vdrRpuProfile == 0) {
      return baseLayerFullRange ? 5 : 0;
    }

    if (vdrRpuProfile != 1) {
      return 0;
    }

    if (!enhancementLayerSpatialResamplingFilter || disableResidual) {
      return 8;
    }

    return vdrBitDepthMinus8 == PROFILE_7_VDR_BIT_DEPTH_MINUS8 ? 7 : 4;
  }

  /** This header without an enhancement layer: no filter for it and no residual. */
  RpuDataHeader withoutEnhancementLayer() {
    return toBuilder().enhancementLayerSpatialResamplingFilter(false).disableResidual(true).build();
  }
}
