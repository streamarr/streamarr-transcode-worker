package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;

/** The Main 10 compatibility that a Main encode implies (ITU-T H.265 A.3.2). */
final class HevcMainCompatibility {

  private static final int MAIN_COMPATIBILITY = 0x40000000;
  private static final int MAIN_AND_MAIN_10_COMPATIBILITY = 0x60000000;
  private static final int RECORD_FIELDS = 23;
  private static final int PROFILE_TIER_LEVEL_BYTES = 12;

  private HevcMainCompatibility() {}

  // Some NVENC devices omit compatibility flag 2 for Main. Add only that implied flag, both in
  // hvcC and in every VPS/SPS: hvcC's compatibility flags are their intersection. Never relabel a
  // different profile, picture format, level, tier or constraint to make the output check pass.
  static byte[] normalize(byte[] record) {
    var reading = ByteBuffer.wrap(record);
    if (record.length < RECORD_FIELDS
        || (record[1] & 0xDF) != 1
        || reading.getInt(2) != MAIN_COMPATIBILITY
        || (record[16] & 3) != 1
        || (record[17] & 7) != 0
        || (record[18] & 7) != 0) {
      return record;
    }

    var result = new ByteArrayOutputStream(record.length);
    var header = Arrays.copyOf(record, RECORD_FIELDS);
    ByteBuffer.wrap(header).putInt(2, MAIN_AND_MAIN_10_COMPATIBILITY);
    result.writeBytes(header);
    reading.position(RECORD_FIELDS);
    var fields = new BoxFields("hvcC", reading);
    for (var array = 0; array < Byte.toUnsignedInt(record[22]); array++) {
      rewriteArray(fields, result);
    }

    result.writeBytes(Arrays.copyOfRange(record, reading.position(), record.length));
    return result.toByteArray();
  }

  private static void rewriteArray(BoxFields fields, ByteArrayOutputStream result) {
    var descriptor = fields.u8();
    var count = fields.u16();
    result.write(descriptor);
    writeUnsignedShort(result, count);
    for (var unit = 0; unit < count; unit++) {
      var bytes = fields.bytes(fields.u16());
      var rewritten = rewriteParameterSet(descriptor & 0x3F, bytes);
      writeUnsignedShort(result, rewritten.length);
      result.writeBytes(rewritten);
    }
  }

  private static byte[] rewriteParameterSet(int type, byte[] unit) {
    if (type != 32 && type != 33) {
      return unit;
    }

    if (unit.length < 2 || ((unit[0] >>> 1) & 0x3F) != type) {
      throw mismatch();
    }

    var payload =
        HevcEmulationPrevention.unescaped(ByteBuffer.wrap(unit, 2, unit.length - 2).slice());
    // VPS has four bytes before profile_tier_level; SPS has one (H.265 7.3.2.1 / 7.3.2.2).
    var profileOffset = type == 32 ? 4 : 1;
    if (payload.length < profileOffset + PROFILE_TIER_LEVEL_BYTES
        || (payload[profileOffset] & 0xDF) != 1) {
      throw mismatch();
    }

    var fields = ByteBuffer.wrap(payload);
    var compatibility = fields.getInt(profileOffset + 1);
    if (compatibility != MAIN_COMPATIBILITY && compatibility != MAIN_AND_MAIN_10_COMPATIBILITY) {
      throw mismatch();
    }

    fields.putInt(profileOffset + 1, MAIN_AND_MAIN_10_COMPATIBILITY);
    var escaped = HevcEmulationPrevention.escaped(payload);
    var rewritten = Arrays.copyOf(unit, 2 + escaped.length);
    System.arraycopy(escaped, 0, rewritten, 2, escaped.length);
    return rewritten;
  }

  private static FragmentedMp4Exception mismatch() {
    return new FragmentedMp4Exception(
        FragmentedMp4Exception.Reason.OUTPUT_MISMATCH,
        "the Main encode's parameter sets do not declare Main compatibility");
  }

  private static void writeUnsignedShort(ByteArrayOutputStream output, int value) {
    output.write(value >>> 8);
    output.write(value);
  }
}
