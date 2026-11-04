package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Strips Dolby Vision, HDR10+ or both from an AV1 sample, a temporal unit of OBUs (AV1 5.3), by
 * removing the metadata OBUs of type ITU-T T.35 that carry them, however many bytes their metadata
 * type takes. A Dolby Vision RPU registers country code 0xB5, provider code 0x003B and
 * provider-oriented code 0x00000800; HDR10+ registers country code 0xB5, provider code 0x003C,
 * provider-oriented code 0x0001 and application identifier 4. Every other OBU keeps its bytes. An
 * OBU without a size field runs to the end of the sample.
 */
final class Av1SampleRewrite implements SampleRewrite {

  private static final int OBU_METADATA = 5;
  private static final int METADATA_TYPE_ITUT_T35 = 4;
  private static final int MAXIMUM_LEB128_BYTES = 8;
  private static final byte[] DOLBY_VISION_PREFIX = {
    (byte) 0xB5, 0x00, 0x3B, 0x00, 0x00, 0x08, 0x00
  };
  private static final byte[] HDR10_PLUS_PREFIX = {(byte) 0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04};

  private final List<byte[]> strippedPrefixes = new ArrayList<>();

  Av1SampleRewrite(Set<BitstreamRewrite> rewrites) {
    if (rewrites.contains(BitstreamRewrite.STRIP_DOLBY_VISION)) {
      strippedPrefixes.add(DOLBY_VISION_PREFIX);
    }

    if (rewrites.contains(BitstreamRewrite.STRIP_HDR10_PLUS)) {
      strippedPrefixes.add(HDR10_PLUS_PREFIX);
    }
  }

  @Override
  public ByteBuffer rewrite(ByteBuffer sample) {
    var kept = new ArrayList<ByteBuffer>();
    var changed = false;
    var position = sample.position();
    while (position < sample.limit()) {
      var obu = obuAt(sample, position);
      var bytes = sample.slice(position, obu.end() - position);
      if (isStripped(sample, obu)) {
        changed = true;
      } else {
        kept.add(bytes);
      }

      position = obu.end();
    }

    if (!changed) {
      return sample;
    }

    var joined = ByteBuffer.allocate(kept.stream().mapToInt(ByteBuffer::remaining).sum());
    kept.forEach(obu -> joined.put(obu.duplicate()));
    return joined.flip();
  }

  // The OBU header (AV1 5.3.2), its optional extension byte, and its optional leb128 size.
  private static Obu obuAt(ByteBuffer sample, int position) {
    var header = Byte.toUnsignedInt(sample.get(position));
    if ((header & 0x80) != 0) {
      throw FragmentedMp4Exception.malformed("an AV1 OBU header sets its forbidden bit");
    }

    var type = (header >> 3) & 0x0F;
    var payloadStart = position + 1 + ((header >> 2) & 0x01);
    requireWithin(sample, payloadStart);
    if ((header & 0x02) == 0) {
      return new Obu(type, payloadStart, sample.limit());
    }

    var reading = sample.duplicate().position(payloadStart);
    var size = leb128(reading);
    if (size > reading.remaining()) {
      throw FragmentedMp4Exception.malformed(
          "an AV1 OBU declares " + size + " bytes where " + reading.remaining() + " remain");
    }

    return new Obu(type, reading.position(), reading.position() + (int) size);
  }

  // An unsigned value of up to 8 bytes, 7 bits each, least significant first (AV1 4.10.5), which
  // ends within the buffer's limit.
  private static long leb128(ByteBuffer reading) {
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

  private static void requireWithin(ByteBuffer sample, int position) {
    if (position > sample.limit()) {
      throw FragmentedMp4Exception.malformed("an AV1 OBU header runs past its sample");
    }
  }

  // A metadata OBU whose metadata_type, a leb128 (AV1 6.7.1), names ITU-T T.35 and is followed by
  // the country code, provider code and provider-oriented code of a stripped registration.
  private boolean isStripped(ByteBuffer sample, Obu obu) {
    if (obu.type() != OBU_METADATA) {
      return false;
    }

    var reading = sample.duplicate().limit(obu.end()).position(obu.payloadStart());
    if (leb128(reading) != METADATA_TYPE_ITUT_T35) {
      return false;
    }

    return strippedPrefixes.stream()
        .anyMatch(prefix -> startsWith(sample, reading.position(), obu.end(), prefix));
  }

  private static boolean startsWith(ByteBuffer sample, int start, int end, byte[] prefix) {
    if (end - start < prefix.length) {
      return false;
    }

    for (var index = 0; index < prefix.length; index++) {
      if (sample.get(start + index) != prefix[index]) {
        return false;
      }
    }

    return true;
  }

  private record Obu(int type, int payloadStart, int end) {}
}
