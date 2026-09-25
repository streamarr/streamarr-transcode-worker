package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import lombok.NonNull;

/**
 * A box's type and declared size, and the length of the header that declared them: 8 bytes, or 16
 * when the 32-bit size is 1 and a 64-bit size follows the type.
 */
record BoxHeader(@NonNull String type, long size, int length) {

  static final int COMPACT_LENGTH = 8;
  static final int LARGE_LENGTH = 16;
  private static final int LARGE_SIZE = 1;

  static BoxHeader read(BoxFields fields) {
    var size = fields.u32();
    var type = fields.fourcc();
    if (size != LARGE_SIZE) {
      return new BoxHeader(type, size, COMPACT_LENGTH);
    }

    return new BoxHeader(type, fields.s64(), LARGE_LENGTH);
  }

  /** Whether a 32-bit size of zero declares that the box extends to the end of the file. */
  boolean isUnsized() {
    return length == COMPACT_LENGTH && size == 0;
  }

  /** Whether the first 8 bytes of a header announce the 64-bit size that follows them. */
  static boolean declaresLargeSize(byte[] compact) {
    return ByteBuffer.wrap(compact).getInt() == LARGE_SIZE;
  }
}
