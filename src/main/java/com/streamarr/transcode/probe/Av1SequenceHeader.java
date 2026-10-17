package com.streamarr.transcode.probe;

import java.util.Arrays;
import java.util.Optional;

/**
 * The fields of an AV1 sequence header OBU (AV1 section 5.5) that an av1C record repeats in its
 * bytes 1 and 2: seq_profile and the first operating point's seq_level_idx, then that point's
 * seq_tier, high_bitdepth and twelve_bit.
 */
record Av1SequenceHeader(int profileAndLevel, int flags) {

  private static final int SEQUENCE_HEADER = 1;
  private static final int MAXIMUM_PROFILE_TWO = 2;

  /**
   * The first sequence header among the OBUs; empty when there is none.
   *
   * @throws BitReader.TruncatedBitstreamException when the OBUs end before the header does
   */
  static Optional<Av1SequenceHeader> find(byte[] obus) {
    var cursor = new ObuCursor(obus);
    while (cursor.hasMore()) {
      var header = cursor.u8();
      var type = (header >> 3) & 0x0F;
      cursor.skipIf((header & 0x04) != 0, 1);
      var size = (header & 0x02) != 0 ? cursor.leb128() : cursor.remaining();
      var payload = cursor.take(size);
      if (type == SEQUENCE_HEADER) {
        return Optional.of(parse(new BitReader(payload)));
      }
    }

    return Optional.empty();
  }

  private static Av1SequenceHeader parse(BitReader reader) {
    var profile = (int) reader.bits(3);
    reader.bits(1);
    var reduced = reader.flag();
    var level = 0;
    var tier = 0;
    if (reduced) {
      level = (int) reader.bits(5);
    } else {
      var firstPoint = operatingPoints(reader);
      level = firstPoint[0];
      tier = firstPoint[1];
    }

    skipFrameSizeAndTools(reader, reduced);
    var highBitDepth = reader.flag();
    var twelveBit = profile == MAXIMUM_PROFILE_TWO && highBitDepth && reader.flag();
    var flags = (tier << 7) | (highBitDepth ? 0x40 : 0) | (twelveBit ? 0x20 : 0);
    return new Av1SequenceHeader((profile << 5) | level, flags);
  }

  /** The timing info and every operating point; returns the first point's level and tier. */
  private static int[] operatingPoints(BitReader reader) {
    var bufferDelayLength = reader.flag() ? timingInfo(reader) : 0;
    var initialDisplayDelay = reader.flag();
    var count = (int) reader.bits(5) + 1;
    var first = new int[2];
    for (var point = 0; point < count; point++) {
      reader.bits(12);
      var level = (int) reader.bits(5);
      var tier = level > 7 ? (int) reader.bits(1) : 0;
      if (bufferDelayLength > 0 && reader.flag()) {
        reader.bits(bufferDelayLength * 2 + 1);
      }

      if (initialDisplayDelay && reader.flag()) {
        reader.bits(4);
      }

      if (point == 0) {
        first[0] = level;
        first[1] = tier;
      }
    }

    return first;
  }

  /**
   * Reads timing_info and decoder_model_info, and returns buffer_delay_length_minus_1 plus 1; 0
   * when the header has no decoder model.
   */
  private static int timingInfo(BitReader reader) {
    reader.bits(32);
    reader.bits(32);
    if (reader.flag()) {
      reader.skipUvlc();
    }

    if (!reader.flag()) {
      return 0;
    }

    var bufferDelayLength = (int) reader.bits(5) + 1;
    reader.bits(32);
    reader.bits(10);
    return bufferDelayLength;
  }

  /** Reads from frame_width_bits_minus_1 to enable_restoration, which precede color_config. */
  private static void skipFrameSizeAndTools(BitReader reader, boolean reduced) {
    var widthBits = (int) reader.bits(4) + 1;
    var heightBits = (int) reader.bits(4) + 1;
    reader.bits(widthBits);
    reader.bits(heightBits);
    if (!reduced && reader.flag()) {
      reader.bits(4);
      reader.bits(3);
    }

    reader.bits(3);
    if (!reduced) {
      skipCompoundAndScreenContentTools(reader);
    }

    reader.bits(3);
  }

  private static void skipCompoundAndScreenContentTools(BitReader reader) {
    reader.bits(4);
    var orderHint = reader.flag();
    if (orderHint) {
      reader.bits(2);
    }

    var forceScreenContentTools = reader.flag() ? 2 : (int) reader.bits(1);
    if (forceScreenContentTools > 0 && !reader.flag()) {
      reader.bits(1);
    }

    if (orderHint) {
      reader.bits(3);
    }
  }

  /** Walks the OBUs: each has a header byte, an optional extension byte and an optional size. */
  private static final class ObuCursor {

    private final byte[] obus;
    private int position;

    ObuCursor(byte[] obus) {
      this.obus = obus;
    }

    boolean hasMore() {
      return position < obus.length;
    }

    int remaining() {
      return obus.length - position;
    }

    int u8() {
      if (!hasMore()) {
        throw new BitReader.TruncatedBitstreamException();
      }

      return Byte.toUnsignedInt(obus[position++]);
    }

    void skipIf(boolean present, int count) {
      if (present) {
        position = Math.min(obus.length, position + count);
      }
    }

    /** AV1's leb128(): up to 8 bytes, 7 bits each, least significant group first. */
    int leb128() {
      var value = 0L;
      for (var index = 0; index < 8; index++) {
        var group = u8();
        value |= (long) (group & 0x7F) << (7 * index);
        if ((group & 0x80) == 0) {
          break;
        }
      }

      return (int) Math.min(value, remaining());
    }

    byte[] take(int count) {
      var bytes = Arrays.copyOfRange(obus, position, position + count);
      position += count;
      return bytes;
    }
  }
}
