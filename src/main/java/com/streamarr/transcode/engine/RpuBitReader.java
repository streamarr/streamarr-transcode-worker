package com.streamarr.transcode.engine;

/**
 * Reads the bits of an RPU's bytes, most significant first, as the RPU syntax tables do: fixed
 * widths up to 32 bits, ue(v) and se(v) Exp-Golomb codes, and whole bytes at any bit position.
 */
final class RpuBitReader {

  // The syntax codes ue(v) values up to 2^32 - 2, which take 31 leading zeros; a longer run codes
  // a value that no 32-bit field and no write of this port holds.
  private static final int LONGEST_EXP_GOLOMB_RUN = 31;
  private static final int WIDEST_FIELD = 32;

  private final byte[] bytes;
  private int position;

  RpuBitReader(byte[] bytes) {
    this.bytes = bytes;
  }

  /**
   * The values of a field that the conversion accepts: the range the pinned decoder validates, or a
   * tighter one. An RPU with a value outside it would not play as the decoder reads it, so the
   * conversion refuses it instead of writing it back.
   */
  record AcceptedRange(String field, long smallest, long largest) {

    // The pinned decoder keeps 16 mappings and 16 sets of DM metadata, so every id is 0 to 15.
    private static final long LARGEST_ID = 15;
    // The decoder reads each layer's bit depth as 8 to 16, coded minus 8.
    private static final long LARGEST_BIT_DEPTH_MINUS8 = 8;
    // The decoder reads most Exp-Golomb fields through a 9-bit table that decodes 0 to 30; a longer
    // code reads as 31 or 32, or past 9 leading zeros loses the bit position.
    private static final long LARGEST_DECODED_EXP_GOLOMB = 30;

    static AcceptedRange id(String field) {
      return new AcceptedRange(field, 0, LARGEST_ID);
    }

    static AcceptedRange bitDepthMinus8(String field) {
      return new AcceptedRange(field, 0, LARGEST_BIT_DEPTH_MINUS8);
    }

    /** The values of a field that the decoder reads with its 31-value Exp-Golomb table. */
    static AcceptedRange decodedExpGolomb(String field) {
      return new AcceptedRange(field, 0, LARGEST_DECODED_EXP_GOLOMB);
    }

    /**
     * @throws DolbyVisionRpuException when the value lies outside the range
     */
    long checked(long value) {
      if (value < smallest || value > largest) {
        throw new DolbyVisionRpuException(
            "a "
                + field
                + " of "
                + value
                + " outside the "
                + smallest
                + " to "
                + largest
                + " that the conversion accepts");
      }

      return value;
    }
  }

  /** A fixed-width field whose value the range checks. */
  long bits(int count, AcceptedRange range) {
    return range.checked(bits(count));
  }

  /** A ue(v) whose value the range checks. */
  long unsignedExpGolomb(AcceptedRange range) {
    return range.checked(unsignedExpGolomb());
  }

  /**
   * @throws DolbyVisionRpuException when the bytes end first, or the count is not a width of the
   *     syntax: a negative count would come from a field of the RPU read as one, so it is malformed
   *     input, not a programming error
   */
  long bits(int count) {
    if (count < 0 || count > WIDEST_FIELD) {
      throw new DolbyVisionRpuException("a field of " + count + " bits");
    }

    if (count > bitsRemaining()) {
      throw new DolbyVisionRpuException("the RPU ends before its syntax does");
    }

    var value = 0L;
    for (var bit = 0; bit < count; bit++) {
      value = (value << 1) | ((bytes[position >> 3] >> (7 - (position & 7))) & 1);
      position++;
    }

    return value;
  }

  boolean flag() {
    return bits(1) == 1;
  }

  /**
   * ue(v): a run of zero bits, a one bit, then as many value bits as the run held.
   *
   * @throws DolbyVisionRpuException when the run codes a value above 2^32 - 2, the largest that the
   *     syntax defines, so that every value read is one {@link RpuBitWriter} writes
   */
  long unsignedExpGolomb() {
    var leadingZeros = 0;
    while (!flag()) {
      leadingZeros++;
      if (leadingZeros > LONGEST_EXP_GOLOMB_RUN) {
        throw new DolbyVisionRpuException(
            "an Exp-Golomb code of more than "
                + LONGEST_EXP_GOLOMB_RUN
                + " leading zeros, past the largest value the syntax defines");
      }
    }

    return (1L << leadingZeros) - 1 + bits(leadingZeros);
  }

  /** se(v): the odd codes are the positive values, the even codes the negative ones. */
  long signedExpGolomb() {
    var code = unsignedExpGolomb();
    if ((code & 1) == 1) {
      return (code + 1) / 2;
    }

    return -(code / 2);
  }

  /**
   * The next bytes, from the current bit position, which need not lie on a byte boundary.
   *
   * @throws DolbyVisionRpuException when the bytes end first, before any byte is allocated for them
   */
  byte[] bytes(long count) {
    if (count > bitsRemaining() / 8) {
      throw new DolbyVisionRpuException("the RPU ends before its syntax does");
    }

    var read = new byte[(int) count];
    for (var index = 0; index < read.length; index++) {
      read[index] = (byte) bits(8);
    }

    return read;
  }

  /**
   * Skips to the next byte boundary.
   *
   * @throws DolbyVisionRpuException when a skipped bit is set, which an alignment bit never is
   */
  void alignmentZeroBits() {
    while (!isByteAligned()) {
      if (flag()) {
        throw new DolbyVisionRpuException("an alignment bit is set");
      }
    }
  }

  boolean isByteAligned() {
    return (position & 7) == 0;
  }

  int bitsRemaining() {
    return bytes.length * 8 - position;
  }
}
