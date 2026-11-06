package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.RpuBitReader.AcceptedRange;
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
  // The decoder reads a denominator of 13 to 32; 23 is the one every real RPU carries, and it sizes
  // every fixed-point coefficient fraction.
  private static final AcceptedRange COEFFICIENT_LOG2_DENOM =
      new AcceptedRange("coefficient_log2_denom", 13, 23);
  private static final AcceptedRange BASE_LAYER_BIT_DEPTH_MINUS8 =
      AcceptedRange.bitDepthMinus8("bl_bit_depth_minus8");
  private static final AcceptedRange ENHANCEMENT_LAYER_BIT_DEPTH_MINUS8 =
      AcceptedRange.bitDepthMinus8("el_bit_depth_minus8");
  private static final AcceptedRange EXT_MAPPING_IDC =
      new AcceptedRange("ext_mapping_idc", 0, 0xFF);
  private static final AcceptedRange VDR_BIT_DEPTH_MINUS8 =
      AcceptedRange.bitDepthMinus8("vdr_bit_depth_minus8");
  private static final AcceptedRange PREVIOUS_VDR_RPU_ID = AcceptedRange.id("prev_vdr_rpu_id");
  private static final int COMPRESSED_DM_METADATA = 1;
  // The VDR bit depth that tells profile 7 from profile 4, both of which carry a residual.
  private static final long PROFILE_7_VDR_BIT_DEPTH_MINUS8 = 4;

  /**
   * @throws DolbyVisionRpuException when the RPU is not of type 2, codes its coefficients in
   *     anything but fixed point, or holds a field outside the range the conversion accepts
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
      header.previousVdrRpuId(reader.unsignedExpGolomb(PREVIOUS_VDR_RPU_ID));
    }

    return header.build();
  }

  // The field holds el_bit_depth_minus8 in its low 8 bits and ext_mapping_idc above them, and the
  // decoder bounds each part.
  private static long enhancementLayerBitDepthField(RpuBitReader reader) {
    var field = reader.unsignedExpGolomb();
    ENHANCEMENT_LAYER_BIT_DEPTH_MINUS8.checked(field & 0xFF);
    EXT_MAPPING_IDC.checked(field >> 8);
    return field;
  }

  private static void parseSequenceInformation(
      RpuBitReader reader, RpuDataHeaderBuilder header, int rpuFormat) {
    header.chromaResamplingExplicitFilter(reader.flag());
    // Real RPUs code their coefficients in fixed point, and so do the identity curves that the
    // conversion writes in place of a full enhancement layer's; a floating-point RPU would get
    // curves of 0 and 0 instead of 0 and 1, so the conversion refuses it.
    var coefficientDataType = (int) reader.bits(2);
    if (coefficientDataType != FIXED_POINT_COEFFICIENTS) {
      throw new DolbyVisionRpuException(
          "coefficient_data_type "
              + coefficientDataType
              + " where the conversion handles fixed-point coefficients only");
    }

    header
        .coefficientDataType(coefficientDataType)
        .coefficientLog2Denom(reader.unsignedExpGolomb(COEFFICIENT_LOG2_DENOM));

    header.vdrRpuNormalizedIdc((int) reader.bits(2)).baseLayerFullRange(reader.flag());
    if ((rpuFormat & FORMAT_WITHOUT_BIT_DEPTHS) != 0) {
      return;
    }

    header
        .baseLayerBitDepthMinus8(reader.unsignedExpGolomb(BASE_LAYER_BIT_DEPTH_MINUS8))
        .enhancementLayerBitDepthField(enhancementLayerBitDepthField(reader))
        .vdrBitDepthMinus8(reader.unsignedExpGolomb(VDR_BIT_DEPTH_MINUS8))
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
    writer.unsignedExpGolomb(coefficientLog2Denom);

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

  /** The bits of each coefficient's fraction: the log2 denominator. */
  int coefficientBits() {
    return (int) coefficientLog2Denom;
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
