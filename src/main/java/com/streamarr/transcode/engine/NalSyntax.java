package com.streamarr.transcode.engine;

import java.util.Set;
import java.util.function.IntUnaryOperator;

/** How a codec's NAL units name their type, and which types a sample rewrite looks into. */
enum NalSyntax {
  /**
   * ITU-T H.265: a 2-byte header whose type takes 6 bits after the forbidden bit (table 7-1). SEI
   * NAL units take types 39 and 40, Dolby Vision RPUs type 62, and a single-track profile 7
   * enhancement layer type 63, which wraps whole NAL units.
   */
  HEVC(2, first -> (first >> 1) & 0x3F, Set.of(39, 40), Set.of(62, 63), Set.of(63)),
  /**
   * ITU-T H.264: a 1-byte header whose type takes the low 5 bits (table 7-1). SEI NAL units take
   * type 6. Dolby Vision profile 9 keeps its RPUs in a type that the copy never strips (ADR 0036),
   * and no H.264 NAL unit wraps SEI NAL units.
   */
  AVC(1, first -> first & 0x1F, Set.of(6), Set.of(), Set.of());

  private final int headerBytes;
  private final IntUnaryOperator typeOfFirstByte;
  private final Set<Integer> seiTypes;
  private final Set<Integer> dolbyVisionTypes;
  private final Set<Integer> wrappingTypes;

  NalSyntax(
      int headerBytes,
      IntUnaryOperator typeOfFirstByte,
      Set<Integer> seiTypes,
      Set<Integer> dolbyVisionTypes,
      Set<Integer> wrappingTypes) {
    this.headerBytes = headerBytes;
    this.typeOfFirstByte = typeOfFirstByte;
    this.seiTypes = seiTypes;
    this.dolbyVisionTypes = dolbyVisionTypes;
    this.wrappingTypes = wrappingTypes;
  }

  /** The bytes of a NAL unit's header. */
  int headerBytes() {
    return headerBytes;
  }

  /** The type that a NAL unit's first byte names. */
  int typeOf(byte first) {
    return typeOfFirstByte.applyAsInt(Byte.toUnsignedInt(first));
  }

  boolean isSei(int type) {
    return seiTypes.contains(type);
  }

  /** Whether NAL units of the type carry Dolby Vision RPUs or an enhancement layer. */
  boolean isDolbyVision(int type) {
    return dolbyVisionTypes.contains(type);
  }

  /** Whether a NAL unit of the type wraps whole NAL units, which a rewrite cannot reach. */
  boolean wrapsNalUnits(int type) {
    return wrappingTypes.contains(type);
  }

  /**
   * Whether NAL units of the type carry a Dolby Vision RPU: the Dolby Vision type that wraps none.
   */
  boolean isRpu(int type) {
    return isDolbyVision(type) && !wrapsNalUnits(type);
  }
}
