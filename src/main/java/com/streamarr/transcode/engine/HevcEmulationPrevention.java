package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * The emulation prevention bytes of an HEVC NAL unit's payload (ITU-T H.265 7.4.2): the 0x03 that
 * keeps two zero bytes and a byte of at most 0x03 from reading as a start code.
 */
final class HevcEmulationPrevention {

  private HevcEmulationPrevention() {}

  // Removes each emulation prevention byte, the 0x03 that follows two zero bytes.
  static byte[] unescaped(ByteBuffer escaped) {
    var rbsp = new ByteArrayOutputStream(escaped.remaining());
    var zeros = 0;
    for (var index = 0; index < escaped.remaining(); index++) {
      var octet = Byte.toUnsignedInt(escaped.get(index));
      if (zeros >= 2 && octet == 0x03) {
        zeros = 0;
        continue;
      }

      zeros = octet == 0 ? zeros + 1 : 0;
      rbsp.write(octet);
    }

    return rbsp.toByteArray();
  }

  // Inserts an emulation prevention byte wherever two zero bytes precede a byte of at most 0x03.
  static byte[] escaped(byte[] rbsp) {
    var escaped = new ByteArrayOutputStream(rbsp.length + rbsp.length / 2);
    var zeros = 0;
    for (var octet : rbsp) {
      var value = Byte.toUnsignedInt(octet);
      if (zeros >= 2 && value <= 0x03) {
        escaped.write(0x03);
        zeros = 0;
      }

      zeros = value == 0 ? zeros + 1 : 0;
      escaped.write(value);
    }

    return escaped.toByteArray();
  }
}
