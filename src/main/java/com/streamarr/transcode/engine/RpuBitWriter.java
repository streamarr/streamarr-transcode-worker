package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;

/** Writes the bits of an RPU, most significant first, as {@link RpuBitReader} reads them. */
final class RpuBitWriter {

  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private int current;
  private int filled;

  /** The low {@code count} bits of the value, for counts up to 32. */
  RpuBitWriter bits(long value, int count) {
    for (var index = count - 1; index >= 0; index--) {
      bit((value >>> index) & 1);
    }

    return this;
  }

  RpuBitWriter flag(boolean value) {
    return bit(value ? 1 : 0);
  }

  /** ue(v): as many zero bits as the value plus one has bits after its leading one, then it. */
  RpuBitWriter unsignedExpGolomb(long value) {
    var length = 63 - Long.numberOfLeadingZeros(value + 1);
    bits(0, length);
    return bits(value + 1, length + 1);
  }

  /** se(v): positive values take the odd codes, negative ones the even codes. */
  RpuBitWriter signedExpGolomb(long value) {
    if (value > 0) {
      return unsignedExpGolomb(2 * value - 1);
    }

    return unsignedExpGolomb(-2 * value);
  }

  RpuBitWriter bytes(byte[] values) {
    for (var value : values) {
      bits(value & 0xFF, 8);
    }

    return this;
  }

  /** Writes zero bits up to the next byte boundary. */
  RpuBitWriter alignToByte() {
    while (filled != 0) {
      bit(0);
    }

    return this;
  }

  /** The whole bytes written so far; the caller aligns first. */
  byte[] toByteArray() {
    return bytes.toByteArray();
  }

  private RpuBitWriter bit(long value) {
    current = (current << 1) | (int) (value & 1);
    filled++;
    if (filled == 8) {
      bytes.write(current);
      current = 0;
      filled = 0;
    }

    return this;
  }
}
