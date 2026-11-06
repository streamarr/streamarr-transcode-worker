package com.streamarr.transcode.engine;

/**
 * The CRC-32/MPEG-2 that ends every Dolby Vision RPU: polynomial 0x04C11DB7, an initial value of
 * all ones, no reflection and no final exclusive or, over the RPU's bytes after its NAL prefix.
 */
final class RpuCrc32 {

  private static final int POLYNOMIAL = 0x04C11DB7;

  private RpuCrc32() {}

  /** The checksum of the bytes from {@code from} inclusive to {@code to} exclusive. */
  static int of(byte[] bytes, int from, int to) {
    var crc = 0xFFFFFFFF;
    for (var index = from; index < to; index++) {
      crc ^= (bytes[index] & 0xFF) << 24;
      for (var bit = 0; bit < 8; bit++) {
        var carries = (crc & 0x80000000) != 0;
        crc <<= 1;
        if (carries) {
          crc ^= POLYNOMIAL;
        }
      }
    }

    return crc;
  }
}
