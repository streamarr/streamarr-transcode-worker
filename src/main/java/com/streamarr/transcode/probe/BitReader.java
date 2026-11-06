package com.streamarr.transcode.probe;

import java.util.OptionalLong;

/** Reads a byte array's bits, most significant first, as the H.26x and AV1 syntax tables do. */
final class BitReader {

  private static final int UVLC_LONGEST_VALUE = 32;
  private static final int TABLE_BITS = 9;
  private static final int LONGEST_GET_UE_GOLOMB_PREFIX = 12;
  private static final int LONGEST_LONG_PREFIX = 31;
  private static final long UNSIGNED_32_BITS = 0xFFFF_FFFFL;
  private static final int LONGEST_TABLE_CODE_BITS = 19;

  private final byte[] bytes;
  private int position;

  BitReader(byte[] bytes) {
    this.bytes = bytes;
  }

  /**
   * The next {@code count} bits as an unsigned number, for counts up to 32.
   *
   * @throws TruncatedBitstreamException when the bytes end first
   */
  long bits(int count) {
    var value = 0L;
    for (var bit = 0; bit < count; bit++) {
      if (position >= bytes.length * 8) {
        throw new TruncatedBitstreamException();
      }

      value = (value << 1) | ((bytes[position >> 3] >> (7 - (position & 7))) & 1);
      position++;
    }

    return value;
  }

  /**
   * The count of bytes that the bits read so far take, once the reader skips to a byte boundary.
   */
  int alignedBytes() {
    return (position + 7) / 8;
  }

  /** The count of bits that the reader has not read yet. */
  long bitsLeft() {
    return (long) bytes.length * 8 - position;
  }

  /** Moves to the next byte boundary, unless the reader already stands on one. */
  void alignToByte() {
    position = (position + 7) / 8 * 8;
  }

  /**
   * Skips {@code count} bits.
   *
   * @throws TruncatedBitstreamException when the bytes end first
   */
  void skip(long count) {
    if (count > bitsLeft()) {
      throw new TruncatedBitstreamException();
    }

    position += (int) count;
  }

  boolean flag() {
    return bits(1) == 1;
  }

  /**
   * FFmpeg's get_ue_golomb(): ue(v), empty for a code of more than 12 leading zeros, which FFmpeg
   * refuses after reading past it.
   */
  OptionalLong unsignedExpGolombUpTo12LeadingZeros() {
    var leadingZeros = 0;
    while (!flag()) {
      leadingZeros++;
      if (leadingZeros > LONGEST_GET_UE_GOLOMB_PREFIX) {
        return OptionalLong.empty();
      }
    }

    return OptionalLong.of((1L << leadingZeros) - 1 + bits(leadingZeros));
  }

  /**
   * FFmpeg's get_ue_golomb_long(): ue(v) with at most 31 leading zeros. After 31 zeros it reads the
   * next 32 bits, whatever they hold, as the code, and wraps the value minus one to 32 bits.
   */
  long unsignedExpGolombLong() {
    for (var zeros = 0; zeros < LONGEST_LONG_PREFIX; zeros++) {
      if (flag()) {
        return ((1L << zeros) | bits(zeros)) - 1;
      }
    }

    return (bits(Integer.SIZE) - 1) & UNSIGNED_32_BITS;
  }

  /**
   * FFmpeg's get_ue_golomb_31(), which reads ue(v) through a 9-bit table: exact up to 30. A code of
   * 5 leading zeros reads as 31 when its first 3 value bits are zero and as 32 otherwise, a code of
   * 6 to 8 leading zeros as 32, and 9 or more leading zeros consume 19 bits and read as 32.
   */
  long unsignedExpGolomb31() {
    var leadingZeros = 0;
    while (leadingZeros < TABLE_BITS && !flag()) {
      leadingZeros++;
    }

    return switch (leadingZeros) {
      case 0, 1, 2, 3, 4 -> (1L << leadingZeros) - 1 + bits(leadingZeros);
      case 5 -> bits(5) < 4 ? 31 : 32;
      case TABLE_BITS -> {
        skip(LONGEST_TABLE_CODE_BITS - TABLE_BITS);
        yield 32;
      }
      default -> {
        skip(leadingZeros);
        yield 32;
      }
    };
  }

  /**
   * The H.26x syntax's ue(v): a run of zero bits, a one bit, then as many value bits as the run
   * held. No field that the probe reads holds a value beyond 32 bits, so a longer run reads as a
   * bitstream that ended early.
   */
  long unsignedExpGolomb() {
    var leadingZeros = 0;
    while (!flag()) {
      leadingZeros++;
      if (leadingZeros > UVLC_LONGEST_VALUE) {
        throw new TruncatedBitstreamException();
      }
    }

    return (1L << leadingZeros) - 1 + bits(leadingZeros);
  }

  /**
   * AV1's uvlc(): a run of zero bits, a one bit, then as many value bits as the run held. A run of
   * 32 or more zeros has no value bits.
   */
  void skipUvlc() {
    var leadingZeros = 0;
    while (!flag()) {
      leadingZeros++;
    }

    if (leadingZeros < UVLC_LONGEST_VALUE) {
      bits(leadingZeros);
    }
  }

  /** The bitstream ended before a field that the syntax requires. */
  static final class TruncatedBitstreamException extends RuntimeException {
    TruncatedBitstreamException() {
      super("the bitstream ended before its syntax did");
    }
  }
}
