package com.streamarr.transcode.probe;

/** Reads a byte array's bits, most significant first, as the H.26x and AV1 syntax tables do. */
final class BitReader {

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

  boolean flag() {
    return bits(1) == 1;
  }

  /** AV1's uvlc(): a run of zero bits, a one bit, then as many value bits as the run held. */
  void skipUvlc() {
    var leadingZeros = 0;
    while (!flag()) {
      leadingZeros++;
      if (leadingZeros >= 32) {
        return;
      }
    }

    bits(leadingZeros);
  }

  /** The bitstream ended before a field that the syntax requires. */
  static final class TruncatedBitstreamException extends RuntimeException {
    TruncatedBitstreamException() {
      super("the bitstream ended before its syntax did");
    }
  }
}
