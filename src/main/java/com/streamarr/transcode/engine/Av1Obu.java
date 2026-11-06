package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;

/**
 * One OBU among a buffer's OBUs, such as an AV1 sample's temporal unit (AV1 5.3) or an av1C
 * record's configOBUs: its type, and where its header, its payload and its end lie in the buffer.
 * An OBU without a size field runs to the buffer's limit.
 *
 * @param type obu_type (AV1 6.2.2)
 * @param start the position of its header
 * @param payloadStart the position of its payload, after its optional extension byte and size
 * @param end the position after its last byte
 */
record Av1Obu(int type, int start, int payloadStart, int end) {

  private static final int MAXIMUM_LEB128_BYTES = 8;

  /**
   * The OBU whose header (AV1 5.3.2) starts at the position.
   *
   * @throws FragmentedMp4Exception with {@link FragmentedMp4Exception.Reason#MALFORMED_BOX} when
   *     its header sets the forbidden bit, or its header, extension byte, size or payload runs past
   *     the buffer's limit
   */
  static Av1Obu at(ByteBuffer obus, int position) {
    var header = Byte.toUnsignedInt(obus.get(position));
    if ((header & 0x80) != 0) {
      throw FragmentedMp4Exception.malformed("an AV1 OBU header sets its forbidden bit");
    }

    var type = (header >> 3) & 0x0F;
    var payloadStart = position + 1 + ((header >> 2) & 0x01);
    requireWithin(obus, payloadStart);
    if ((header & 0x02) == 0) {
      return new Av1Obu(type, position, payloadStart, obus.limit());
    }

    var reading = obus.duplicate().position(payloadStart);
    var size = leb128(reading);
    if (size > reading.remaining()) {
      throw FragmentedMp4Exception.malformed(
          "an AV1 OBU declares " + size + " bytes where " + reading.remaining() + " remain");
    }

    return new Av1Obu(type, position, reading.position(), reading.position() + (int) size);
  }

  /**
   * An unsigned value of up to 8 bytes, 7 bits each, least significant first (AV1 4.10.5), which
   * ends within the buffer's limit.
   *
   * @throws FragmentedMp4Exception with {@link FragmentedMp4Exception.Reason#MALFORMED_BOX} when it
   *     runs past the limit or past 8 bytes
   */
  static long leb128(ByteBuffer reading) {
    var value = 0L;
    for (var index = 0; index < MAXIMUM_LEB128_BYTES && reading.hasRemaining(); index++) {
      var octet = Byte.toUnsignedInt(reading.get());
      value |= (long) (octet & 0x7F) << (7 * index);
      if ((octet & 0x80) == 0) {
        return value;
      }
    }

    throw FragmentedMp4Exception.malformed("an AV1 OBU's leb128 value runs past its end");
  }

  private static void requireWithin(ByteBuffer obus, int position) {
    if (position > obus.limit()) {
      throw FragmentedMp4Exception.malformed("an AV1 OBU header runs past its sample");
    }
  }
}
