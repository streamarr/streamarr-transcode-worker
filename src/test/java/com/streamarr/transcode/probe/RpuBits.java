package com.streamarr.transcode.probe;

import com.streamarr.transcode.probe.RpuDataHeaderBits.Bits;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import lombok.Singular;

/**
 * A whole Dolby Vision RPU, written in the order that FFmpeg 8.1's RPU parser reads it: the
 * rpu_data_header, one reshaping curve of two pivots and one linear polynomial piece for each
 * component, the NLQ parameters unless the header disables the residual, and an uncompressed DM
 * payload whose first group of extension blocks the test lists. The CRC32 is not checked by the
 * probe, so the RPU carries zeros in its place.
 */
@Builder(toBuilder = true)
record RpuBits(
    @NonNull RpuDataHeaderBits header,
    boolean usePreviousMapping,
    long xPartitionsMinus1,
    @Singular List<Nlq> nlqs,
    boolean hasDisplayManagement,
    @Singular List<ExtensionBlock> extensionBlocks) {

  private static final int COMPONENTS = 3;

  /** A profile 8.1 RPU: no residual, display management without extension blocks. */
  static RpuBitsBuilder profile81() {
    return builder().header(RpuDataHeaderBits.profile81().build()).hasDisplayManagement(true);
  }

  /** A profile 7 RPU with these NLQ parameters for each of the three components. */
  static RpuBitsBuilder profile7(Nlq nlq) {
    return profile81()
        .header(
            RpuDataHeaderBits.profile81()
                .elSpatialResamplingFilter(true)
                .disableResidual(false)
                .build())
        .nlq(nlq)
        .nlq(nlq)
        .nlq(nlq);
  }

  /** The RPU as an HEVC RPU NAL unit, as hex. */
  String hevcNalUnit() {
    return "7c 01 " + RpuDataHeaderBits.escaped(payload());
  }

  byte[] payload() {
    var bits = header.written(new Bits()).flag(hasDisplayManagement).flag(usePreviousMapping);
    if (usePreviousMapping) {
      bits.unsignedExpGolomb(0);
    } else {
      writeMapping(bits);
    }

    if (hasDisplayManagement) {
      writeDisplayManagement(bits);
    }

    return bits.align().put(32, 0).put(8, 0x80).bytes();
  }

  private void writeMapping(Bits bits) {
    var baseLayerBits = (int) header.blBitDepthMinus8() + 8;
    bits.unsignedExpGolomb(0).unsignedExpGolomb(0).unsignedExpGolomb(0);
    for (var component = 0; component < COMPONENTS; component++) {
      bits.unsignedExpGolomb(0).put(baseLayerBits, 0).put(baseLayerBits, (1L << baseLayerBits) - 1);
    }

    if (!header.disableResidual()) {
      bits.put(3, 0).put(baseLayerBits, 0).put(baseLayerBits, (1L << baseLayerBits) - 1);
    }

    bits.unsignedExpGolomb(xPartitionsMinus1).unsignedExpGolomb(0);
    for (var component = 0; component < COMPONENTS; component++) {
      // A polynomial of order 1 without linear interpolation: 0 + 1 * x.
      bits.unsignedExpGolomb(0).unsignedExpGolomb(0).flag(false);
      coefficient(bits, 0, 0);
      coefficient(bits, 1, 0);
    }

    for (var nlq : header.disableResidual() ? List.<Nlq>of() : nlqs) {
      bits.put((int) header.elBitDepthMinus8() + 8, nlq.offset());
      coefficient(bits, nlq.vdrInMaxInteger(), nlq.vdrInMaxFraction());
      coefficient(bits, nlq.slopeInteger(), nlq.slopeFraction());
      coefficient(bits, nlq.thresholdInteger(), nlq.thresholdFraction());
    }
  }

  // A fixed-point coefficient: ue(v) or se(v) of the integer part, which share a code for the
  // values here, then coefficient_log2_denom bits of fraction. A floating-point one: 32 bits.
  private void coefficient(Bits bits, long integer, long fraction) {
    if (header.coefficientDataType() != 0) {
      bits.put(32, fraction);
      return;
    }

    bits.unsignedExpGolomb(integer).put((int) header.coefficientLog2Denom(), fraction);
  }

  private void writeDisplayManagement(Bits bits) {
    bits.unsignedExpGolomb(0).unsignedExpGolomb(0).unsignedExpGolomb(0);
    for (var matrixValue = 0; matrixValue < 9; matrixValue++) {
      bits.put(16, 0);
    }

    for (var offset = 0; offset < 3; offset++) {
      bits.put(32, 0);
    }

    for (var matrixValue = 0; matrixValue < 9; matrixValue++) {
      bits.put(16, 0);
    }

    bits.put(16, 0).put(16, 0).put(16, 0).put(32, 0).put(5, 12).put(2, 0).put(2, 0).put(2, 0);
    bits.put(12, 62).put(12, 3696).put(10, 42);
    bits.unsignedExpGolomb(extensionBlocks.size()).align();
    for (var block : extensionBlocks) {
      bits.unsignedExpGolomb(block.payload().size()).put(8, block.level());
      block.payload().forEach(value -> bits.put(8, value));
    }
  }

  /** One component's NLQ parameters, each coefficient as its integer part and its fraction. */
  @Builder(toBuilder = true)
  record Nlq(
      long offset,
      long vdrInMaxInteger,
      long vdrInMaxFraction,
      long slopeInteger,
      long slopeFraction,
      long thresholdInteger,
      long thresholdFraction) {

    /** The identity that libdovi calls a minimal enhancement layer. */
    static Nlq identity() {
      return builder().vdrInMaxInteger(1).build();
    }

    /**
     * The NLQ of a profile 7 full enhancement layer: offset 512, the midpoint of a 10-bit layer, a
     * vdr_in_max of 1048576 / 2^23 and a slope of 2048 / 2^23.
     */
    static Nlq fullEnhancementLayer() {
      return builder().offset(512).vdrInMaxFraction(1_048_576).slopeFraction(2_048).build();
    }
  }

  /** One extension block: its level and its payload bytes. */
  record ExtensionBlock(int level, List<Integer> payload) {

    /** A level 6 block: mastering luminances, then MaxCLL and MaxFALL, each 16 bits. */
    static ExtensionBlock level6(int maxContentLightLevel, int maxFrameAverageLightLevel) {
      return new ExtensionBlock(
          6,
          List.of(
              0x03,
              0xE8,
              0x00,
              0x01,
              maxContentLightLevel >> 8,
              maxContentLightLevel & 0xFF,
              maxFrameAverageLightLevel >> 8,
              maxFrameAverageLightLevel & 0xFF));
    }

    /** A level 1 block, whose 36 bits of PQ values fill five bytes. */
    static ExtensionBlock level1() {
      return new ExtensionBlock(1, List.of(0x01, 0x02, 0x03, 0x04, 0x00));
    }
  }
}
