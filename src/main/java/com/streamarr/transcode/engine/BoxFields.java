package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import java.util.Optional;

/** Reads a box's big-endian fields in order; reading past the box fails as a malformed box. */
final class BoxFields {

  private final String type;
  private final ByteBuffer buffer;

  BoxFields(String type, ByteBuffer buffer) {
    this.type = type;
    this.buffer = buffer;
  }

  int u8() {
    require(1);
    return Byte.toUnsignedInt(buffer.get());
  }

  int u24() {
    require(3);
    return Byte.toUnsignedInt(buffer.get()) << 16 | Short.toUnsignedInt(buffer.getShort());
  }

  long u32() {
    return Integer.toUnsignedLong(s32());
  }

  int s32() {
    require(4);
    return buffer.getInt();
  }

  long s64() {
    require(8);
    return buffer.getLong();
  }

  /** Reads a four-character box type; an unprintable byte reads as {@code ?}. */
  String fourcc() {
    require(4);
    var type = new StringBuilder(4);
    for (var character = 0; character < 4; character++) {
      type.append(printable(buffer.get()));
    }

    return type.toString();
  }

  BoxFields skip(int bytes) {
    require(bytes);
    buffer.position(buffer.position() + bytes);
    return this;
  }

  BoxFields skipIf(boolean present, int bytes) {
    if (present) {
      skip(bytes);
    }

    return this;
  }

  Optional<Integer> s32If(boolean present) {
    if (present) {
      return Optional.of(s32());
    }

    return Optional.empty();
  }

  private static char printable(byte value) {
    var character = (char) Byte.toUnsignedInt(value);
    if (character < ' ' || character > '~') {
      return '?';
    }

    return character;
  }

  private void require(int bytes) {
    if (buffer.remaining() < bytes) {
      throw NestedBox.malformed(type + " ends before its fields do");
    }
  }
}
