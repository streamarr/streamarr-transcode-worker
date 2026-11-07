package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Strips Dolby Vision, HDR10+ or both from a sample whose NAL units each follow a big-endian length
 * of the size that the sample entry's decoder configuration record declares (ISO/IEC 14496-15), or
 * converts the sample's single-track Dolby Vision profile 7 to profile 8.1. Stripping Dolby Vision
 * removes every NAL unit that the syntax names as Dolby Vision: in HEVC, type 62, which holds the
 * RPUs, and type 63, which holds a single-track profile 7 enhancement layer, whatever their layer
 * and temporal id. Converting profile 7 removes the same NAL units and writes the sample's one RPU,
 * converted to profile 8.1, as the sample's last NAL unit (ADR 0036); a sample without exactly one
 * RPU that converts fails the attempt as a conversion failure, and so does an RPU that reuses a
 * previous RPU's mapping that no RPU since the last random access point defined, as the decoder
 * resolves such a reuse from the mappings it holds. Stripping HDR10+ removes each HDR10+ message
 * from the SEI NAL units and keeps their other messages; a NAL unit left with no message goes.
 * Every other NAL unit keeps its bytes, and so does an SEI NAL unit that holds no HDR10+ message.
 * Bytes after the last NAL unit too few to hold a length stay where they are.
 */
final class NalUnitSampleRewrite implements SampleRewrite {

  // user_data_registered_itu_t_t35 (ITU-T H.265 D.2.6, ITU-T H.264 D.1.6); HDR10+ registers country
  // code 0xB5,
  // provider code 0x003C, provider-oriented code 0x0001 and application identifier 4.
  private static final int REGISTERED_USER_DATA = 4;
  private static final byte[] HDR10_PLUS_PREFIX = {(byte) 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04};
  private static final int RBSP_STOP_BYTE = 0x80;
  // A payload type or size past this would overflow with its next 0xFF byte; no SEI message inside
  // a segment comes near it.
  private static final int LARGEST_CODED_VALUE = Integer.MAX_VALUE - 0xFF;
  // An HEVC NAL unit header of type 62 in layer 0 at temporal id 0.
  private static final byte[] BASE_LAYER_RPU_HEADER = {0x7C, 0x01};

  private final NalSyntax syntax;
  private final int lengthSize;
  private final boolean stripsDolbyVision;
  private final boolean stripsHdr10Plus;
  private final boolean convertsProfile7;
  private final DefinedRpuMappings definedMappings = new DefinedRpuMappings();

  /**
   * @param lengthSize the bytes of each NAL unit's length: 1, 2 or 4
   */
  NalUnitSampleRewrite(NalSyntax syntax, int lengthSize, Set<BitstreamRewrite> rewrites) {
    this.syntax = syntax;
    this.lengthSize = lengthSize;
    this.stripsDolbyVision = rewrites.contains(BitstreamRewrite.STRIP_DOLBY_VISION);
    this.stripsHdr10Plus = rewrites.contains(BitstreamRewrite.STRIP_HDR10_PLUS);
    this.convertsProfile7 =
        rewrites.contains(BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1);
  }

  @Override
  public ByteBuffer rewrite(ByteBuffer sample) {
    return new Walk(sample).rewritten();
  }

  // One sample's walk: its units by position, each kept one copied straight into the output, so
  // that the sample's bytes bound the walk's memory and its unit count allocates nothing. A sample
  // of a million small units sits far below the segment cap and would otherwise hold an object per
  // unit, outside the producer's budget.
  private final class Walk {

    private final ByteBuffer sample;
    // Every kept unit is at most as long as in the sample, so the sample's size holds them all; the
    // converted RPU, which can outgrow the sample's, joins them at the end.
    private ByteBuffer kept;
    private final List<ByteBuffer> rpus = new ArrayList<>(1);
    private boolean changed;
    private boolean randomAccessPoint;
    private int position;

    private Walk(ByteBuffer sample) {
      this.sample = sample;
      this.kept = ByteBuffer.allocate(sample.remaining());
      this.position = sample.position();
    }

    private ByteBuffer rewritten() {
      while (sample.limit() - position >= lengthSize) {
        var length = lengthOfTheNextUnit();
        var start = position + lengthSize;
        position = start + length;
        visit(start, length);
      }

      var trailing = sample.slice(position, sample.limit() - position);
      if (convertsProfile7) {
        return joinedWith(convertedRpuOf(rpus, randomAccessPoint), trailing);
      }

      if (!changed) {
        return sample;
      }

      return withRoomFor(trailing.remaining()).put(trailing).flip();
    }

    private int lengthOfTheNextUnit() {
      var length = lengthAt(sample, position);
      var remaining = sample.limit() - position - lengthSize;
      if (length == 0 || length > remaining) {
        throw FragmentedMp4Exception.malformed(
            "a video sample declares a NAL unit of "
                + length
                + " bytes where "
                + remaining
                + " remain");
      }

      return (int) length;
    }

    // The unit as the copy keeps it: copied as it is, rewritten, or dropped. A conversion drops the
    // RPU here and writes its converted form last.
    private void visit(int start, int length) {
      if (length < syntax.headerBytes()) {
        visitWithoutAHeader(start, length);
        return;
      }

      var type = syntax.typeOf(sample.get(start));
      randomAccessPoint |= syntax.isRandomAccessPoint(type);
      if ((stripsDolbyVision || convertsProfile7) && syntax.isDolbyVision(type)) {
        dropDolbyVision(start, length, type);
        return;
      }

      if (stripsHdr10Plus && syntax.isSei(type)) {
        visitSei(sample.slice(start, length));
        return;
      }

      if (stripsHdr10Plus
          && syntax.wrapsNalUnits(type)
          && wrapsHdr10Plus(sample.slice(start, length))) {
        throw new FragmentedMp4Exception(
            Reason.OUTPUT_MISMATCH,
            "an enhancement-layer NAL unit wraps HDR10+, which the copy cannot strip");
      }

      copy(start, length);
    }

    // A Dolby Vision picture without an RPU can flash in a player, so the conversion neither drops
    // a picture's RPU nor repeats an earlier one: it keeps the sample's one RPU and fails the
    // attempt at a second, which also keeps a sample of many RPUs from holding them all.
    private void dropDolbyVision(int start, int length, int type) {
      changed = true;
      if (!convertsProfile7 || !syntax.isRpu(type)) {
        return;
      }

      rpus.add(sample.slice(start, length));
      if (rpus.size() > 1) {
        throw conversionFailed("a video sample holds a second RPU where the conversion needs one");
      }
    }

    // A unit shorter than its header names no type, so the conversion cannot tell whether it is
    // the picture's RPU; the other rewrites pass it through as the source wrote it.
    private void visitWithoutAHeader(int start, int length) {
      if (convertsProfile7) {
        throw conversionFailed("a NAL unit of " + length + " bytes is shorter than its header");
      }

      copy(start, length);
    }

    private void visitSei(ByteBuffer unit) {
      var rewritten = withoutHdr10Plus(unit);
      changed |= rewritten.filter(each -> each == unit).isEmpty();
      rewritten.ifPresent(this::put);
    }

    private void copy(int start, int length) {
      withRoomFor(lengthSize + length);
      putLength(kept, length);
      kept.put(kept.position(), sample, start, length);
      kept.position(kept.position() + length);
    }

    private void put(ByteBuffer unit) {
      withRoomFor(lengthSize + unit.remaining());
      putLength(kept, unit.remaining());
      kept.put(unit.duplicate());
    }

    // The kept units outgrow the sample when an SEI rewrite escapes a run of zeros that the source
    // left unescaped, so the room doubles: the output so far is then copied a bounded number of
    // times over the whole sample, not once per growing unit.
    private ByteBuffer withRoomFor(int bytes) {
      if (kept.remaining() < bytes) {
        var room = Math.max((long) kept.position() + bytes, 2L * kept.capacity());
        kept = ByteBuffer.allocate((int) Math.min(room, Integer.MAX_VALUE)).put(kept.flip());
      }

      return kept;
    }

    private ByteBuffer joinedWith(ByteBuffer converted, ByteBuffer trailing) {
      var joined =
          ByteBuffer.allocate(
              kept.position() + lengthSize + converted.remaining() + trailing.remaining());
      joined.put(kept.flip());
      putLength(joined, converted.remaining());
      return joined.put(converted).put(trailing).flip();
    }

    private ByteBuffer convertedRpuOf(List<ByteBuffer> rpus, boolean randomAccessPoint) {
      if (rpus.isEmpty()) {
        throw conversionFailed("a video sample holds no RPU where the conversion needs one");
      }

      var rpu = rpus.getFirst();
      if (!hasBaseLayerRpuHeader(rpu)) {
        throw conversionFailed(
            "an RPU NAL unit header of "
                + HexFormat.of().formatHex(new byte[] {rpu.get(0), rpu.get(1)})
                + " where the decoder reads an RPU only from layer 0 at temporal id 0");
      }

      var headerBytes = syntax.headerBytes();
      var payload = rpu.slice(headerBytes, rpu.remaining() - headerBytes);
      return withHeaderOf(rpu, convertedToProfile81(payload, randomAccessPoint));
    }

    private Optional<ByteBuffer> withoutHdr10Plus(ByteBuffer unit) {
      var headerBytes = syntax.headerBytes();
      var payload =
          HevcEmulationPrevention.unescaped(
              unit.slice(headerBytes, unit.remaining() - headerBytes));
      var messages = messagesOf(payload);
      if (messages.stream().anyMatch(message -> nestsHdr10Plus(payload, message))) {
        throw new FragmentedMp4Exception(
            Reason.OUTPUT_MISMATCH, "an SEI message nests HDR10+, which the copy cannot strip");
      }

      var otherMessages =
          messages.stream().filter(message -> !isHdr10Plus(payload, message)).toList();
      if (otherMessages.size() == messages.size()) {
        return Optional.of(unit);
      }

      if (otherMessages.isEmpty()) {
        return Optional.empty();
      }

      var rbsp = new ByteArrayOutputStream();
      otherMessages.forEach(
          message -> rbsp.write(payload, message.start(), message.end() - message.start()));
      rbsp.write(RBSP_STOP_BYTE);
      return Optional.of(withHeaderOf(unit, HevcEmulationPrevention.escaped(rbsp.toByteArray())));
    }

    // A wrapping NAL unit, such as HEVC's type 63, holds whole NAL units, which can be SEI NAL
    // units;
    // the registration bytes hold no two zero bytes in a row, so no emulation prevention byte
    // splits
    // them.
    private boolean wrapsHdr10Plus(ByteBuffer unit) {
      var bytes = new byte[unit.remaining()];
      unit.duplicate().get(bytes);
      return holdsHdr10PlusAnywhere(bytes, syntax.headerBytes(), bytes.length);
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

    private byte[] convertedToProfile81(ByteBuffer escapedPayload, boolean randomAccessPoint) {
      if (randomAccessPoint) {
        definedMappings.startAtRandomAccessPoint();
      }

      try {
        var rpu = DolbyVisionRpu.parse(escapedPayload);
        definedMappings.resolve(rpu);
        return rpu.convertedToProfile81().write();
      } catch (DolbyVisionRpuException exception) {
        throw conversionFailed(exception.getMessage());
      }
    }

    // The unit's NAL unit header, then the escaped payload.
    private ByteBuffer withHeaderOf(ByteBuffer unit, byte[] escapedPayload) {
      var rewritten = new ByteArrayOutputStream();
      for (var index = 0; index < syntax.headerBytes(); index++) {
        rewritten.write(unit.get(index));
      }

      rewritten.writeBytes(escapedPayload);
      return ByteBuffer.wrap(rewritten.toByteArray());
    }
  }

  // The pinned decoder reads an RPU only from a NAL unit of type 62 with forbidden_zero_bit 0,
  // nuh_layer_id 0 and nuh_temporal_id_plus1 1, which is the header 7C 01; a picture whose RPU
  // sits under another header would play without Dolby Vision, so the conversion refuses it.
  private static boolean hasBaseLayerRpuHeader(ByteBuffer rpu) {
    return rpu.get(0) == BASE_LAYER_RPU_HEADER[0] && rpu.get(1) == BASE_LAYER_RPU_HEADER[1];
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
      if (value > LARGEST_CODED_VALUE) {
        throw FragmentedMp4Exception.malformed(
            "an SEI message codes a payload type or size past " + LARGEST_CODED_VALUE);
      }

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

  private static FragmentedMp4Exception conversionFailed(String detail) {
    return new FragmentedMp4Exception(Reason.DOLBY_VISION_CONVERSION_FAILED, detail);
  }

  // An SEI message's bytes in the RBSP, from its payload type to the end of its payload.
  private record Message(int start, int type, int payloadStart, int end) {}
}
