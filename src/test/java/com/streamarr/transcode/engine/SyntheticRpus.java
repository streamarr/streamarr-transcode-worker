package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.HexFormat;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Writes Dolby Vision RPUs field by field, as the RPU syntax lays them out, so that a test can hand
 * the parser an RPU whose every value it chose: a profile 7 RPU whose NLQ parameters make its
 * enhancement layer minimal or full, with polynomial luma pieces and MMR chroma curves, CM v2.9
 * extension blocks and optionally CM v4.0 ones, or one that reuses the previous RPU's mapping. The
 * values follow the one FEL frame of a public profile 7 sample where the spec leaves a choice.
 */
final class SyntheticRpus {

  static final int RPU_NAL_PREFIX = 25;
  static final int BL_BIT_DEPTH = 10;
  static final int COEFFICIENT_LOG2_DENOM = 23;
  // Chroma MMR coefficients repeat these integer parts and fractions.
  static final long MMR_COEFFICIENT_FRACTION = 0x2A5A5A;
  static final int FEL_NLQ_OFFSET = 512;
  static final long FEL_VDR_IN_MAX = 1_048_576;
  static final long FEL_DEADZONE_SLOPE = 2048;
  // The DM colour coefficients that no profile 8.1 conversion leaves in place: values whose bytes
  // hold no zero, so that the conversion's own zero-bearing coefficients add emulation prevention.
  static final int SOURCE_COLOR_COEFFICIENT = 0x1234;
  static final long SOURCE_COLOR_OFFSET = 0x12345678L;

  private static final int RPU_TYPE = 2;
  private static final int RPU_FORMAT = 18;
  private static final int FINAL_BYTE = 0x80;
  // An extension block level that the decoder skips without reading a byte of the block.
  private static final int UNREAD_BLOCK_LEVEL = 200;

  // dovi_tool 2.3.4, `editor` with {"mode": 2}, on profile7(): 323 bytes in, 159 out.
  private static final String CONVERTED_PROFILE_7 =
      "19080908406136506F003FF801FFC00FFFD0000008000006800000400000340000030200000301"
          + "A2566000035EA2566F9FCEB1C256644CA00000100000030080000003008000000301C362243"
          + "01860A5E308E0514000001A63E5AFFFF000000300000300000300060200F80E1514300803E03"
          + "AEE00C028218008008008008004000100A0000003000F0078024180FA000040FA00640060189C"
          + "A780";
  // The same on profile7().previousRpu(0): 122 bytes in, 126 out, as the profile 8.1 colour
  // matrices take emulation prevention bytes that the source's did not.
  private static final String CONVERTED_PREVIOUS_RPU =
      "19080908406136507E895980000D7A8959BE7F3AC7095991328000004000000302000003000200"
          + "000300070D8890C06182978C23814500000300698F96BFFFC000000300000300000300180803E0"
          + "385450300803E03AEE00C028218008008008008004000100A0000003000F0078024180FA000040"
          + "FA006400B9371AD480";

  private SyntheticRpus() {}

  static Builder profile7() {
    return new Builder();
  }

  /** The profile 8.1 RPU that dovi_tool's mode 2 writes of {@link #profile7()}, escaped. */
  static byte[] convertedProfile7() {
    return HexFormat.of().parseHex(CONVERTED_PROFILE_7);
  }

  /** The profile 8.1 RPU that dovi_tool's mode 2 writes of {@code profile7().previousRpu(0)}. */
  static byte[] convertedPreviousRpu() {
    return HexFormat.of().parseHex(CONVERTED_PREVIOUS_RPU);
  }

  /** The NLQ parameters of one component. */
  record Nlq(
      int offset,
      long vdrInMaxInteger,
      long vdrInMax,
      long slopeInteger,
      long slope,
      long thresholdInteger,
      long threshold) {

    /** libdovi's minimal enhancement layer: no offset, a clamp of exactly 1.0 and no dead zone. */
    static Nlq minimal() {
      return new Nlq(0, 1, 0, 0, 0, 0, 0);
    }

    /** The parameters of the public FEL sample. */
    static Nlq full() {
      return new Nlq(FEL_NLQ_OFFSET, 0, FEL_VDR_IN_MAX, 0, FEL_DEADZONE_SLOPE, 0, 0);
    }
  }

  /** An RPU as the sample rewrite sees it: a NAL unit of type 62 after a 4-byte length. */
  static byte[] nalUnit(byte[] escapedPayload) {
    var unit = ByteBuffer.allocate(4 + 2 + escapedPayload.length);
    unit.putInt(2 + escapedPayload.length).put((byte) 0x7C).put((byte) 0x01).put(escapedPayload);
    return unit.array();
  }

  static final class Builder {
    private List<Nlq> nlq = List.of(Nlq.full(), Nlq.full(), Nlq.full());
    private boolean residual = true;
    private boolean enhancementLayerFilter = true;
    private int lumaPieces = 1;
    private boolean mmrChroma = true;
    private boolean dmMetadata = true;
    private boolean compressedDm;
    private boolean cmv40;
    private OptionalLong previousRpuId = OptionalLong.empty();
    private int colorCoefficient = SOURCE_COLOR_COEFFICIENT;
    private long colorOffset = SOURCE_COLOR_OFFSET;
    private int vdrRpuProfile = 1;
    private boolean fullRange;
    private int trailingZeroBytes;
    private OptionalLong firstBlockClaimedBytes = OptionalLong.empty();
    private byte[] bytesBeforeCrc32 = new byte[0];
    private long coefficientLog2Denom = COEFFICIENT_LOG2_DENOM;
    private int fractionBits = COEFFICIENT_LOG2_DENOM;
    private OptionalInt emptyBlocks = OptionalInt.empty();
    private long lumaMappingIdc = 0;

    private Builder() {}

    /**
     * A CM v2.9 block list of this many blocks of no bytes under a level the decoder does not read,
     * in place of the usual four blocks.
     */
    Builder emptyBlocks(int count) {
      emptyBlocks = OptionalInt.of(count);
      return this;
    }

    /**
     * The mapping_idc ahead of every luma piece's polynomial fields; the syntax defines 0 and 1.
     */
    Builder lumaMappingIdc(long value) {
      lumaMappingIdc = value;
      return this;
    }

    /** A header that declares the denominator; the fraction fields keep their own width. */
    Builder coefficientLog2Denom(long value) {
      coefficientLog2Denom = value;
      return this;
    }

    /** The width of every coefficient fraction field, which a malformed RPU can shrink to none. */
    Builder fractionBits(int count) {
      fractionBits = count;
      return this;
    }

    /** An RPU whose first extension block claims the length while it holds its five bytes. */
    Builder firstBlockClaiming(long bytes) {
      firstBlockClaimedBytes = OptionalLong.of(bytes);
      return this;
    }

    /**
     * Bytes that the syntax does not define, between the alignment after the metadata and the
     * CRC32.
     */
    Builder bytesBeforeCrc32(int... values) {
      bytesBeforeCrc32 = new byte[values.length];
      for (var index = 0; index < values.length; index++) {
        bytesBeforeCrc32[index] = (byte) values[index];
      }

      return this;
    }

    Builder nlq(Nlq nlq) {
      this.nlq = List.of(nlq, nlq, nlq);
      return this;
    }

    Builder nlq(Nlq luma, Nlq cb, Nlq cr) {
      this.nlq = List.of(luma, cb, cr);
      return this;
    }

    /** A profile 8 RPU: no residual, no enhancement-layer filter and no NLQ. */
    Builder withoutResidual() {
      residual = false;
      enhancementLayerFilter = false;
      return this;
    }

    Builder lumaPieces(int pieces) {
      lumaPieces = pieces;
      return this;
    }

    Builder polynomialChroma() {
      mmrChroma = false;
      return this;
    }

    Builder withoutDmMetadata() {
      dmMetadata = false;
      return this;
    }

    Builder compressedDm() {
      compressedDm = true;
      return this;
    }

    Builder cmv40() {
      cmv40 = true;
      return this;
    }

    /** An RPU without a mapping of its own, which reuses the mapping of the RPU with the id. */
    Builder previousRpu(long id) {
      previousRpuId = OptionalLong.of(id);
      return this;
    }

    /** The DM colour coefficients and offsets, which a profile 8.1 conversion replaces. */
    Builder colorCoefficients(int coefficient, long offset) {
      colorCoefficient = coefficient;
      colorOffset = offset;
      return this;
    }

    /** A profile 5 RPU: vdr_rpu_profile 0 with a full-range base layer. */
    Builder profile5() {
      vdrRpuProfile = 0;
      fullRange = true;
      return this;
    }

    Builder trailingZeroBytes(int count) {
      trailingZeroBytes = count;
      return this;
    }

    /** The RPU's payload after its NAL unit header, with emulation prevention. */
    byte[] escaped() {
      var sink = new BitSink();
      sink.u(RPU_NAL_PREFIX, 8);
      writeHeader(sink);
      if (previousRpuId.isEmpty()) {
        writeMapping(sink);
      }

      if (dmMetadata) {
        writeDmData(sink);
      }

      sink.align();
      for (var value : bytesBeforeCrc32) {
        sink.u(value & 0xFF, 8);
      }

      var data = sink.toBytes();
      var out = new ByteArrayOutputStream();
      out.writeBytes(data);
      var crc = crc32Mpeg2(data, 1, data.length);
      out.writeBytes(ByteBuffer.allocate(4).putInt(crc).array());
      out.write(FINAL_BYTE);
      out.writeBytes(new byte[trailingZeroBytes]);
      return HevcEmulationPrevention.escaped(out.toByteArray());
    }

    byte[] nalUnit() {
      return SyntheticRpus.nalUnit(escaped());
    }

    private void writeHeader(BitSink sink) {
      sink.u(RPU_TYPE, 6).u(RPU_FORMAT, 11).u(vdrRpuProfile, 4).u(0, 4).bit(1);
      sink.bit(0).u(0, 2).ue(coefficientLog2Denom).u(1, 2).bit(fullRange ? 1 : 0);
      sink.ue(BL_BIT_DEPTH - 8).ue(2).ue(4).bit(0).u(compressedDm ? 1 : 0, 3);
      sink.bit(enhancementLayerFilter ? 1 : 0).bit(residual ? 0 : 1);
      sink.bit(dmMetadata ? 1 : 0);
      if (previousRpuId.isPresent()) {
        sink.bit(1).ue(previousRpuId.getAsLong());
        return;
      }

      sink.bit(0);
    }

    private void writeMapping(BitSink sink) {
      sink.ue(0).ue(0).ue(0);
      for (var component = 0; component < 3; component++) {
        writePivots(sink, component == 0 ? lumaPieces : 1);
      }

      if (residual) {
        sink.u(0, 3).u(0, BL_BIT_DEPTH).u(1023, BL_BIT_DEPTH);
      }

      sink.ue(2).ue(1);
      for (var piece = 0; piece < lumaPieces; piece++) {
        writeIdentityPolynomial(sink, lumaMappingIdc);
      }

      for (var component = 1; component < 3; component++) {
        if (mmrChroma) {
          writeMmr(sink, component);
        } else {
          writeIdentityPolynomial(sink);
        }
      }

      if (residual) {
        nlq.forEach(parameters -> writeNlq(sink, parameters));
      }
    }

    // Pivots that split the 10-bit range evenly into the pieces.
    private static void writePivots(BitSink sink, int pieces) {
      sink.ue(pieces - 1);
      for (var pivot = 0; pivot <= pieces; pivot++) {
        sink.u(pivot == pieces ? 1023 : pivot * 1024L / pieces, BL_BIT_DEPTH);
      }
    }

    // mapping_idc 0, first order, no linear interpolation, 0 + 1 * x.
    private void writeIdentityPolynomial(BitSink sink) {
      writeIdentityPolynomial(sink, 0);
    }

    private void writeIdentityPolynomial(BitSink sink, long mappingIdc) {
      sink.ue(mappingIdc).ue(0).bit(0);
      sink.se(0).u(0, fractionBits);
      sink.se(1).u(0, fractionBits);
    }

    // mapping_idc 1, third order: a constant and 3 x 7 coefficients, signed integer parts first.
    private void writeMmr(BitSink sink, int component) {
      sink.ue(1).u(2, 2);
      sink.se(-component).u(MMR_COEFFICIENT_FRACTION, fractionBits);
      for (var order = 0; order < 3; order++) {
        for (var coefficient = 0; coefficient < 7; coefficient++) {
          sink.se((coefficient % 3) - 1 + order).u(MMR_COEFFICIENT_FRACTION, fractionBits);
        }
      }
    }

    private void writeNlq(BitSink sink, Nlq parameters) {
      sink.u(parameters.offset(), BL_BIT_DEPTH);
      sink.ue(parameters.vdrInMaxInteger()).u(parameters.vdrInMax(), fractionBits);
      sink.ue(parameters.slopeInteger()).u(parameters.slope(), fractionBits);
      sink.ue(parameters.thresholdInteger()).u(parameters.threshold(), fractionBits);
    }

    private void writeDmData(BitSink sink) {
      sink.ue(0).ue(0).ue(1);
      if (!compressedDm) {
        writeSignalDescription(sink);
      }

      writeCmv29Blocks(sink);
      if (cmv40) {
        sink.ue(2).align();
        sink.ue(2).u(254, 8).u(0, 8).u(2, 8);
        sink.ue(4).u(11, 8).u(1, 8).u(0x08, 8).u(0, 8).u(0, 8);
      }
    }

    private void writeCmv29Blocks(BitSink sink) {
      if (emptyBlocks.isPresent()) {
        sink.ue(emptyBlocks.getAsInt()).align();
        for (var block = 0; block < emptyBlocks.getAsInt(); block++) {
          sink.ue(0).u(UNREAD_BLOCK_LEVEL, 8);
        }

        return;
      }

      // CM v2.9: L1, L2, L5 and L6 blocks.
      sink.ue(4).align();
      sink.ue(firstBlockClaimedBytes.orElse(5)).u(1, 8).u(7, 12).u(3079, 12).u(1500, 12).u(0, 4);
      sink.ue(11).u(2, 8).u(2081, 12).u(2048, 12).u(2048, 12).u(2048, 12).u(2048, 12).u(2048, 12);
      sink.u(2048, 13).u(0, 3);
      sink.ue(7).u(5, 8).u(0, 13).u(0, 13).u(60, 13).u(60, 13).u(0, 4);
      sink.ue(8).u(6, 8).u(1000, 16).u(1, 16).u(1000, 16).u(400, 16);
    }

    // The 3 x 3 colour matrices and offsets, then the signal's EOTF and ranges.
    private void writeSignalDescription(BitSink sink) {
      for (var coefficient = 0; coefficient < 9; coefficient++) {
        sink.u(colorCoefficient, 16);
      }

      for (var offset = 0; offset < 3; offset++) {
        sink.u(colorOffset, 32);
      }

      for (var coefficient = 0; coefficient < 9; coefficient++) {
        sink.u(colorCoefficient, 16);
      }

      sink.u(65535, 16).u(0, 16).u(0, 16).u(0, 32);
      sink.u(12, 5).u(1, 2).u(0, 2).u(1, 2).u(7, 12).u(3079, 12).u(42, 10);
    }

    // CRC-32/MPEG-2: polynomial 0x04C11DB7, initial value all ones, no reflection, no final xor.
    private static int crc32Mpeg2(byte[] bytes, int from, int to) {
      var crc = 0xFFFFFFFF;
      for (var index = from; index < to; index++) {
        crc ^= (bytes[index] & 0xFF) << 24;
        for (var bit = 0; bit < 8; bit++) {
          var carries = (crc & 0x80000000) != 0;
          crc <<= 1;
          if (carries) {
            crc ^= 0x04C11DB7;
          }
        }
      }

      return crc;
    }
  }

  /** Writes bits most significant first. */
  static final class BitSink {
    private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    private int current;
    private int filled;

    BitSink u(long value, int count) {
      for (var index = count - 1; index >= 0; index--) {
        bit((value >>> index) & 1);
      }

      return this;
    }

    BitSink bit(long value) {
      current = (current << 1) | (int) (value & 1);
      filled++;
      if (filled == 8) {
        bytes.write(current);
        current = 0;
        filled = 0;
      }

      return this;
    }

    /** ue(v): leading zeros, then the value plus one. */
    BitSink ue(long value) {
      var length = 63 - Long.numberOfLeadingZeros(value + 1);
      u(0, length);
      return u(value + 1, length + 1);
    }

    /** se(v): positive values take the odd codes, negative ones the even codes. */
    BitSink se(long value) {
      return ue(value > 0 ? 2 * value - 1 : -2 * value);
    }

    BitSink align() {
      while (filled != 0) {
        bit(0);
      }

      return this;
    }

    byte[] toBytes() {
      return bytes.toByteArray();
    }
  }
}
