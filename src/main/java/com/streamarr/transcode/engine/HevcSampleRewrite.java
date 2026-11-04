package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Strips Dolby Vision, HDR10+ or both from an HEVC sample whose NAL units each follow a big-endian
 * length of the size that the sample entry's {@code hvcC} declares (ISO/IEC 14496-15 8.3.2).
 * Stripping Dolby Vision removes every NAL unit of type 62, which holds the RPUs, and of type 63,
 * which holds a single-track profile 7 enhancement layer, whatever their layer and temporal id.
 * Stripping HDR10+ removes each HDR10+ message from the prefix and suffix SEI NAL units and keeps
 * their other messages; a NAL unit left with no message goes. Every other NAL unit keeps its bytes,
 * and so does an SEI NAL unit that holds no HDR10+ message. Bytes after the last NAL unit too few
 * to hold a length stay where they are.
 */
final class HevcSampleRewrite implements SampleRewrite {

  // NAL unit types (ITU-T H.265 table 7-1).
  private static final Set<Integer> SEI_TYPES = Set.of(39, 40);
  private static final Set<Integer> DOLBY_VISION_TYPES = Set.of(62, 63);
  private static final int NAL_UNIT_HEADER_BYTES = 2;
  // user_data_registered_itu_t_t35 (ITU-T H.265 D.2.6); HDR10+ registers country code 0xB5,
  // provider code 0x003C, provider-oriented code 0x0001 and application identifier 4.
  private static final int REGISTERED_USER_DATA = 4;
  private static final int SCALABLE_NESTING = 133;
  private static final byte[] HDR10_PLUS_PREFIX = {(byte) 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04};
  private static final int RBSP_STOP_BYTE = 0x80;

  private final int lengthSize;
  private final boolean stripsDolbyVision;
  private final boolean stripsHdr10Plus;

  /**
   * @param lengthSize the bytes of each NAL unit's length: 1, 2 or 4
   */
  HevcSampleRewrite(int lengthSize, Set<BitstreamRewrite> rewrites) {
    this.lengthSize = lengthSize;
    this.stripsDolbyVision = rewrites.contains(BitstreamRewrite.STRIP_DOLBY_VISION);
    this.stripsHdr10Plus = rewrites.contains(BitstreamRewrite.STRIP_HDR10_PLUS);
  }

  @Override
  public ByteBuffer rewrite(ByteBuffer sample) {
    var kept = new ArrayList<ByteBuffer>();
    var changed = false;
    var position = sample.position();
    while (sample.limit() - position >= lengthSize) {
      var length = lengthAt(sample, position);
      var unitStart = position + lengthSize;
      if (length == 0 || length > sample.limit() - unitStart) {
        throw FragmentedMp4Exception.malformed(
            "a video sample declares a NAL unit of "
                + length
                + " bytes where "
                + (sample.limit() - unitStart)
                + " remain");
      }

      var unit = sample.slice(unitStart, (int) length);
      var rewritten = rewrittenUnit(unit);
      changed |= rewritten.filter(each -> each == unit).isEmpty();
      rewritten.ifPresent(kept::add);
      position = unitStart + (int) length;
    }

    if (!changed) {
      return sample;
    }

    return joined(kept, sample.slice(position, sample.limit() - position));
  }

  // The NAL unit as the copy keeps it: the same buffer, a rewritten SEI NAL unit, or nothing.
  private Optional<ByteBuffer> rewrittenUnit(ByteBuffer unit) {
    if (unit.remaining() < NAL_UNIT_HEADER_BYTES) {
      return Optional.of(unit);
    }

    var type = (Byte.toUnsignedInt(unit.get(0)) >> 1) & 0x3F;
    if (stripsDolbyVision && DOLBY_VISION_TYPES.contains(type)) {
      return Optional.empty();
    }

    if (stripsHdr10Plus && SEI_TYPES.contains(type)) {
      return withoutHdr10Plus(unit);
    }

    return Optional.of(unit);
  }

  private static Optional<ByteBuffer> withoutHdr10Plus(ByteBuffer unit) {
    var payload = unescaped(unit.slice(NAL_UNIT_HEADER_BYTES, unit.remaining() - 2));
    var messages = messagesOf(payload);
    if (messages.stream().anyMatch(message -> nestsHdr10Plus(payload, message))) {
      throw new FragmentedMp4Exception(
          FragmentedMp4Exception.Reason.OUTPUT_MISMATCH,
          "a scalable nesting SEI message holds HDR10+, which the copy cannot strip");
    }

    var kept = messages.stream().filter(message -> !isHdr10Plus(payload, message)).toList();
    if (kept.size() == messages.size()) {
      return Optional.of(unit);
    }

    if (kept.isEmpty()) {
      return Optional.empty();
    }

    var rbsp = new ByteArrayOutputStream();
    kept.forEach(message -> rbsp.write(payload, message.start(), message.end() - message.start()));
    rbsp.write(RBSP_STOP_BYTE);
    var rewritten = new ByteArrayOutputStream();
    rewritten.write(unit.get(0));
    rewritten.write(unit.get(1));
    rewritten.writeBytes(escaped(rbsp.toByteArray()));
    return Optional.of(ByteBuffer.wrap(rewritten.toByteArray()));
  }

  // The SEI messages of an SEI RBSP (ITU-T H.265 7.3.5), each from its payload type to the end of
  // its payload, up to the RBSP's stop byte.
  private static List<Message> messagesOf(byte[] rbsp) {
    var end = rbsp.length;
    while (end > 0 && rbsp[end - 1] == 0) {
      end--;
    }

    if (end == 0 || Byte.toUnsignedInt(rbsp[end - 1]) != RBSP_STOP_BYTE) {
      throw FragmentedMp4Exception.malformed("an SEI NAL unit ends without its stop bit");
    }

    var messages = new ArrayList<Message>();
    var reading = ByteBuffer.wrap(rbsp, 0, end - 1);
    while (reading.hasRemaining()) {
      var start = reading.position();
      var type = codedValue(reading);
      var size = codedValue(reading);
      var payloadStart = reading.position();
      if (size > reading.remaining()) {
        throw FragmentedMp4Exception.malformed(
            "an SEI message declares a payload of " + size + " bytes past its NAL unit");
      }

      reading.position(payloadStart + size);
      messages.add(new Message(start, type, payloadStart, reading.position()));
    }

    return messages;
  }

  // A payload type or size: every 0xFF byte adds 255, and the first other byte ends the value.
  private static int codedValue(ByteBuffer reading) {
    var value = 0;
    while (reading.hasRemaining()) {
      var octet = Byte.toUnsignedInt(reading.get());
      value += octet;
      if (octet != 0xFF) {
        return value;
      }
    }

    throw FragmentedMp4Exception.malformed("an SEI message ends inside its payload type or size");
  }

  private static boolean isHdr10Plus(byte[] rbsp, Message message) {
    return message.type() == REGISTERED_USER_DATA
        && holdsAt(rbsp, message.payloadStart(), message.end(), HDR10_PLUS_PREFIX);
  }

  // A scalable nesting message hides its nested messages behind bit-level fields. Removing one of
  // them would mean rewriting those fields, so the copy refuses any nesting message whose payload
  // holds HDR10+'s registration anywhere.
  private static boolean nestsHdr10Plus(byte[] rbsp, Message message) {
    if (message.type() != SCALABLE_NESTING) {
      return false;
    }

    for (var start = message.payloadStart(); start < message.end(); start++) {
      if (holdsAt(rbsp, start, message.end(), HDR10_PLUS_PREFIX)) {
        return true;
      }
    }

    return false;
  }

  private static boolean holdsAt(byte[] rbsp, int start, int end, byte[] expected) {
    if (end - start < expected.length) {
      return false;
    }

    return Arrays.equals(rbsp, start, start + expected.length, expected, 0, expected.length);
  }

  // Removes each emulation prevention byte, the 0x03 that follows two zero bytes.
  private static byte[] unescaped(ByteBuffer escaped) {
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
  private static byte[] escaped(byte[] rbsp) {
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

  private ByteBuffer joined(List<ByteBuffer> units, ByteBuffer trailing) {
    var bytes = units.stream().mapToInt(unit -> lengthSize + unit.remaining()).sum();
    var joined = ByteBuffer.allocate(bytes + trailing.remaining());
    for (var unit : units) {
      putLength(joined, unit.remaining());
      joined.put(unit.duplicate());
    }

    joined.put(trailing.duplicate());
    return joined.flip();
  }

  private void putLength(ByteBuffer target, int length) {
    if (lengthSize < Integer.BYTES && length >>> (8 * lengthSize) != 0) {
      throw FragmentedMp4Exception.malformed(
          "a rewritten NAL unit of "
              + length
              + " bytes overflows its "
              + lengthSize
              + "-byte length");
    }

    for (var index = lengthSize - 1; index >= 0; index--) {
      target.put((byte) (length >>> (8 * index)));
    }
  }

  private long lengthAt(ByteBuffer sample, int position) {
    var length = 0L;
    for (var index = 0; index < lengthSize; index++) {
      length = length << 8 | Byte.toUnsignedInt(sample.get(position + index));
    }

    return length;
  }

  // An SEI message's bytes in the RBSP, from its payload type to the end of its payload.
  private record Message(int start, int type, int payloadStart, int end) {}
}
