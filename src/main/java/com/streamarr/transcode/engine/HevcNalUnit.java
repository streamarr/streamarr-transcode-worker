package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * One NAL unit of an HEVC sample whose NAL units each follow a big-endian length of the size that
 * the sample entry's {@code hvcC} declares (ISO/IEC 14496-15 8.3.2), with that length prefix.
 *
 * <p>FFmpeg's ff_h2645_packet_split drops some NAL units before its HEVC decoder sees them: one
 * whose forbidden_zero_bit is 1, one shorter than its 2-byte header, one whose
 * nuh_temporal_id_plus1 is 0 or nuh_layer_id is 63. Such a NAL unit keeps its bytes, but it is no
 * slice and no RPU, and it never keeps an RPU from being the last NAL unit that the decoder sees. A
 * start code inside a NAL unit's length begins NAL units that FFmpeg reads on their own, so the NAL
 * unit reaches the decoder, holds a slice, or ends with an RPU when one of them does.
 *
 * @param bytes the length prefix and the NAL unit, viewed without copying them
 * @param slice whether a picture slice reaches the decoder from the NAL unit's bytes
 * @param rpu whether the last NAL unit of those bytes that reaches the decoder is an RPU
 * @param reachesDecoder whether FFmpeg's splitter hands the NAL unit, or one that a start code
 *     inside it begins, to the decoder
 */
record HevcNalUnit(@NonNull ByteBuffer bytes, boolean slice, boolean rpu, boolean reachesDecoder) {

  /**
   * The most NAL units of a sample that {@link #delimit} returns: well above the 600 slice segments
   * of a picture at HEVC's highest level (ITU-T H.265 table A.8), with its parameter sets and SEI.
   */
  static final int MAXIMUM_NAL_UNITS_PER_SAMPLE = 4096;

  // FFmpeg's splitter reads the next length only while at least this many bytes remain.
  private static final int FEWEST_SPLIT_BYTES = 4;
  private static final int NAL_UNIT_HEADER_BYTES = 2;
  // NAL unit types below this one hold picture slices (ITU-T H.265 table 7-1).
  private static final int FIRST_NON_PICTURE_TYPE = 32;
  private static final int UNSPECIFIED_62 = 62;
  // A Dolby Vision RPU is an UNSPECIFIED_62 NAL unit on layer 0 and TemporalId 0 whose payload
  // starts with rpu_nal_prefix 25: FFmpeg's HEVC decoder reads no other NAL unit as an RPU.
  private static final int RPU_NAL_PREFIX = 25;
  private static final int UNDECODED_LAYER = 63;

  HevcNalUnit {
    bytes = bytes.slice();
  }

  int length() {
    return bytes.remaining();
  }

  /**
   * The sample's NAL units in order. FFmpeg's splitter reads a length only while at least {@link
   * #FEWEST_SPLIT_BYTES} bytes of the sample remain, so the 1 to 3 bytes that can follow the last
   * NAL unit belong to none, whatever they hold. Empty when a length that the splitter reads is 0
   * or runs past the end of the sample, which makes FFmpeg reject the whole sample, or when the
   * sample holds more than {@link #MAXIMUM_NAL_UNITS_PER_SAMPLE} NAL units.
   *
   * @param lengthSize the bytes of each length prefix: 1, 2 or 4
   */
  static Optional<List<HevcNalUnit>> delimit(ByteBuffer sample, int lengthSize) {
    var units = new ArrayList<HevcNalUnit>();
    var position = sample.position();
    while (sample.limit() - position >= FEWEST_SPLIT_BYTES) {
      if (units.size() == MAXIMUM_NAL_UNITS_PER_SAMPLE) {
        return Optional.empty();
      }

      var length = lengthAt(sample, position, lengthSize);
      var unitStart = position + lengthSize;
      if (length == 0 || length > sample.limit() - unitStart) {
        return Optional.empty();
      }

      var end = unitStart + (int) length;
      units.add(unitAt(sample, position, unitStart, end));
      position = end;
    }

    return Optional.of(List.copyOf(units));
  }

  private static HevcNalUnit unitAt(ByteBuffer sample, int start, int unitStart, int end) {
    var bytes = sample.slice(start, end - start);
    var decoded = decodedHeaders(sample, unitStart, end);
    var slice =
        decoded.stream().anyMatch(header -> typeAt(sample, header) < FIRST_NON_PICTURE_TYPE);
    var rpu = !decoded.isEmpty() && isRpuAt(sample, decoded.getLast(), end);
    return new HevcNalUnit(bytes, slice, rpu, !decoded.isEmpty());
  }

  // FFmpeg ends a NAL unit at a start code that its length's bytes hold, and splits the bytes after
  // the start code into NAL units of their own, which reach the decoder by the same rules.
  private static List<Integer> decodedHeaders(ByteBuffer sample, int unitStart, int end) {
    var headers = new ArrayList<Integer>();
    headers.add(unitStart);
    for (var index = unitStart; index + 2 < end; index++) {
      if (sample.get(index) == 0 && sample.get(index + 1) == 0 && sample.get(index + 2) == 1) {
        headers.add(index + 3);
      }
    }

    headers.removeIf(header -> !headerReachesDecoder(sample, header, end));
    return headers;
  }

  private static int typeAt(ByteBuffer sample, int header) {
    return (Byte.toUnsignedInt(sample.get(header)) >> 1) & 0x3F;
  }

  private static boolean isRpuAt(ByteBuffer sample, int header, int end) {
    var first = Byte.toUnsignedInt(sample.get(header));
    var second = Byte.toUnsignedInt(sample.get(header + 1));
    return typeAt(sample, header) == UNSPECIFIED_62
        && layerId(first, second) == 0
        && (second & 0x07) == 1
        && end - header > NAL_UNIT_HEADER_BYTES
        && Byte.toUnsignedInt(sample.get(header + NAL_UNIT_HEADER_BYTES)) == RPU_NAL_PREFIX;
  }

  private static boolean headerReachesDecoder(ByteBuffer sample, int unitStart, int end) {
    if (end - unitStart < NAL_UNIT_HEADER_BYTES) {
      return false;
    }

    var first = Byte.toUnsignedInt(sample.get(unitStart));
    var second = Byte.toUnsignedInt(sample.get(unitStart + 1));
    return (first & 0x80) == 0 && layerId(first, second) != UNDECODED_LAYER && (second & 0x07) != 0;
  }

  private static int layerId(int first, int second) {
    return (first & 0x01) << 5 | second >> 3;
  }

  private static long lengthAt(ByteBuffer sample, int position, int lengthSize) {
    var length = 0L;
    for (var index = 0; index < lengthSize; index++) {
      length = length << 8 | Byte.toUnsignedInt(sample.get(position + index));
    }

    return length;
  }
}
