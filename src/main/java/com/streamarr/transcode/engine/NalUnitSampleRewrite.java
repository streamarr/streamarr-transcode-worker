package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Strips Dolby Vision, HDR10+ or both from a sample whose NAL units each follow a big-endian length
 * of the size that the sample entry's decoder configuration record declares (ISO/IEC 14496-15).
 * Stripping Dolby Vision removes every NAL unit that the syntax names as Dolby Vision: in HEVC,
 * type 62, which holds the RPUs, and type 63, which holds a single-track profile 7 enhancement
 * layer, whatever their layer and temporal id. Stripping HDR10+ removes each HDR10+ message from
 * the SEI NAL units and keeps their other messages; a NAL unit left with no message goes. Every
 * other NAL unit keeps its bytes, and so does an SEI NAL unit that holds no HDR10+ message. Bytes
 * after the last NAL unit too few to hold a length stay where they are.
 */
final class NalUnitSampleRewrite implements SampleRewrite {

  // user_data_registered_itu_t_t35 (ITU-T H.265 D.2.6, ITU-T H.264 D.1.6); HDR10+ registers country
  // code 0xB5,
  // provider code 0x003C, provider-oriented code 0x0001 and application identifier 4.
  private static final int REGISTERED_USER_DATA = 4;
  private static final byte[] HDR10_PLUS_PREFIX = {(byte) 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04};
  private static final int RBSP_STOP_BYTE = 0x80;

  private final NalSyntax syntax;
  private final int lengthSize;
  private final boolean stripsDolbyVision;
  private final boolean stripsHdr10Plus;

  /**
   * @param lengthSize the bytes of each NAL unit's length: 1, 2 or 4
   */
  NalUnitSampleRewrite(NalSyntax syntax, int lengthSize, Set<BitstreamRewrite> rewrites) {
    this.syntax = syntax;
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
    if (unit.remaining() < syntax.headerBytes()) {
      return Optional.of(unit);
    }

    var type = syntax.typeOf(unit.get(0));
    if (stripsDolbyVision && syntax.isDolbyVision(type)) {
      return Optional.empty();
    }

    if (stripsHdr10Plus && syntax.isSei(type)) {
      return withoutHdr10Plus(unit);
    }

    if (stripsHdr10Plus && syntax.wrapsNalUnits(type) && wrapsHdr10Plus(unit)) {
      throw new FragmentedMp4Exception(
          FragmentedMp4Exception.Reason.OUTPUT_MISMATCH,
          "an enhancement-layer NAL unit wraps HDR10+, which the copy cannot strip");
    }

    return Optional.of(unit);
  }

  private Optional<ByteBuffer> withoutHdr10Plus(ByteBuffer unit) {
    var headerBytes = syntax.headerBytes();
    var payload =
        HevcEmulationPrevention.unescaped(unit.slice(headerBytes, unit.remaining() - headerBytes));
    var messages = messagesOf(payload);
    if (messages.stream().anyMatch(message -> nestsHdr10Plus(payload, message))) {
      throw new FragmentedMp4Exception(
          FragmentedMp4Exception.Reason.OUTPUT_MISMATCH,
          "an SEI message nests HDR10+, which the copy cannot strip");
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
    for (var index = 0; index < headerBytes; index++) {
      rewritten.write(unit.get(index));
    }

    rewritten.writeBytes(HevcEmulationPrevention.escaped(rbsp.toByteArray()));
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

  // A nesting message, such as scalable nesting (payload type 133), hides its nested messages
  // behind bit-level fields. Removing one of them would mean rewriting those fields, so the copy
  // refuses any other message whose payload holds HDR10+'s registration anywhere.
  private static boolean nestsHdr10Plus(byte[] rbsp, Message message) {
    return !isHdr10Plus(rbsp, message)
        && holdsHdr10PlusAnywhere(rbsp, message.payloadStart(), message.end());
  }

  // A wrapping NAL unit, such as HEVC's type 63, holds whole NAL units, which can be SEI NAL units;
  // the registration bytes hold no two zero bytes in a row, so no emulation prevention byte splits
  // them.
  private boolean wrapsHdr10Plus(ByteBuffer unit) {
    var bytes = new byte[unit.remaining()];
    unit.duplicate().get(bytes);
    return holdsHdr10PlusAnywhere(bytes, syntax.headerBytes(), bytes.length);
  }

  private static boolean holdsHdr10PlusAnywhere(byte[] bytes, int start, int end) {
    for (var at = start; at < end; at++) {
      if (holdsAt(bytes, at, end, HDR10_PLUS_PREFIX)) {
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
