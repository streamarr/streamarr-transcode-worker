package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

/**
 * A Dolby Vision RPU of type 2, read from and written to the payload of an HEVC NAL unit of type
 * 62: the NAL prefix 25, the header, the mapping unless the RPU reuses the previous one, the DM
 * metadata when the header announces it, alignment, any further bytes ahead of the CRC32, the CRC32
 * and the final byte 0x80. The parser and writer cover the subset of the syntax that converting a
 * profile 7 RPU to profile 8.1 needs, the way libdovi's mode 2 does: the residual and its NLQ go, a
 * full enhancement layer's mapping gives way to identity curves, the partitions and the DM colour
 * matrices take profile 8.1's values, and the extension blocks stay as they were.
 *
 * @param trailingBytes the bytes between the alignment after the metadata and the CRC32, which the
 *     syntax does not define and a write keeps
 */
@Builder(toBuilder = true)
record DolbyVisionRpu(
    @NonNull RpuDataHeader header,
    @NonNull Optional<RpuDataMapping> mapping,
    @NonNull Optional<VdrDmData> dmData,
    byte @NonNull [] trailingBytes) {

  /** What the NLQ parameters of a profile 7 RPU say about its enhancement layer. */
  enum EnhancementLayer {
    /** Every component's NLQ is the identity, so the layer carries no picture detail. */
    MEL,
    /** The layer carries residual picture detail. */
    FEL
  }

  private static final int RPU_NAL_PREFIX = 25;
  private static final int FINAL_BYTE = 0x80;
  // The CRC32 and the final byte: a prefix, a payload byte and both make the shortest RPU.
  private static final int CRC32_AND_FINAL_BYTES = 5;
  private static final int SHORTEST_RPU_BYTES = 7;
  private static final int CRC32_AND_FINAL_BITS = 40;
  private static final Set<Integer> CONVERTIBLE_PROFILES = Set.of(7, 8);
  private static final long PROFILE_7_BASE_LAYER_BIT_DEPTH_MINUS8 = 2;

  /**
   * @param escapedPayload the NAL unit's bytes after its 2-byte header, with emulation prevention
   * @throws DolbyVisionRpuException when the bytes are no RPU that this parser reads: too short, a
   *     last byte other than 0x80, a CRC32 that does not match, a prefix other than 25, or syntax
   *     that the conversion does not handle
   */
  static DolbyVisionRpu parse(ByteBuffer escapedPayload) {
    var bytes = withoutTrailingZeros(HevcEmulationPrevention.unescaped(escapedPayload));
    if (bytes.length < SHORTEST_RPU_BYTES) {
      throw new DolbyVisionRpuException("an RPU of " + bytes.length + " bytes");
    }

    if ((bytes[bytes.length - 1] & 0xFF) != FINAL_BYTE) {
      throw new DolbyVisionRpuException("the RPU's last byte is not 0x80");
    }

    var crcStart = bytes.length - CRC32_AND_FINAL_BYTES;
    var computed = RpuCrc32.of(bytes, 1, crcStart);
    var received = ByteBuffer.wrap(bytes, crcStart, 4).getInt();
    if (received != computed) {
      throw new DolbyVisionRpuException("the RPU's CRC32 does not match its bytes");
    }

    var reader = new RpuBitReader(bytes);
    if (reader.bits(8) != RPU_NAL_PREFIX) {
      throw new DolbyVisionRpuException("the RPU's NAL prefix is not 25");
    }

    var header = RpuDataHeader.parse(reader);
    var rpu = DolbyVisionRpu.builder().header(header);
    var mapping = Optional.<RpuDataMapping>empty();
    if (!header.usePreviousVdrRpu()) {
      mapping = Optional.of(RpuDataMapping.parse(reader, header));
    }

    var dmData = Optional.<VdrDmData>empty();
    if (header.dmMetadataPresent()) {
      dmData = Optional.of(VdrDmData.parse(reader, header));
    }

    rpu.mapping(mapping).dmData(dmData);
    reader.alignmentZeroBits();
    if (reader.bitsRemaining() < CRC32_AND_FINAL_BITS) {
      throw new DolbyVisionRpuException("the RPU's syntax runs into its CRC32");
    }

    return rpu.trailingBytes(reader.bytes((reader.bitsRemaining() - CRC32_AND_FINAL_BITS) / 8))
        .build();
  }

  /** The RPU's payload after its NAL unit header, with emulation prevention and a fresh CRC32. */
  byte[] write() {
    var writer = new RpuBitWriter().bits(RPU_NAL_PREFIX, 8);
    header.write(writer);
    mapping.ifPresent(data -> data.write(writer, header));
    dmData.ifPresent(data -> data.write(writer));
    writer.alignToByte().bytes(trailingBytes);
    var data = writer.toByteArray();
    writer.bits(RpuCrc32.of(data, 1, data.length), 32).bits(FINAL_BYTE, 8);
    return HevcEmulationPrevention.escaped(writer.toByteArray());
  }

  /** The Dolby Vision profile that the RPU serves, as libdovi and FFmpeg derive it. */
  int profile() {
    return header.dolbyVisionProfile();
  }

  /** The enhancement layer that the RPU's NLQ describes; empty for an RPU without a residual. */
  Optional<EnhancementLayer> enhancementLayer() {
    return mapping
        .flatMap(RpuDataMapping::nlq)
        .map(nlq -> nlq.isMinimalEnhancementLayer() ? EnhancementLayer.MEL : EnhancementLayer.FEL);
  }

  /**
   * This RPU converted to profile 8.1 the way libdovi's mode 2 converts it. A profile 8 RPU takes
   * the same changes, which leave its curves as they are.
   *
   * @throws DolbyVisionRpuException when the RPU serves neither profile 7 nor 8, or its base layer
   *     is not 10-bit, the only depth the profiles define
   */
  DolbyVisionRpu convertedToProfile81() {
    if (!CONVERTIBLE_PROFILES.contains(profile())) {
      throw new DolbyVisionRpuException(
          "an RPU of profile " + profile() + " cannot be converted to profile 8.1");
    }

    if (header.baseLayerBitDepthMinus8() != PROFILE_7_BASE_LAYER_BIT_DEPTH_MINUS8) {
      throw new DolbyVisionRpuException(
          "an RPU with a " + header.baseLayerBitDepth() + "-bit base layer");
    }

    return toBuilder()
        .header(header.withoutEnhancementLayer())
        .mapping(mapping.map(RpuDataMapping::withoutResidual))
        .dmData(dmData.map(VdrDmData::withProfile81Matrices))
        .build();
  }

  private static byte[] withoutTrailingZeros(byte[] bytes) {
    var end = bytes.length;
    while (end > 0 && bytes[end - 1] == 0) {
      end--;
    }

    return Arrays.copyOf(bytes, end);
  }
}
