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
 * nuh_temporal_id_plus1 is 0 or nuh_layer_id is 63, and the last one of a sample when it takes
 * fewer than {@link #FEWEST_SPLIT_BYTES} bytes with its length. Such a NAL unit keeps its bytes,
 * but it is no slice and no RPU, and it never keeps an RPU from being the last NAL unit that the
 * decoder sees.
 *
 * @param bytes the length prefix and the NAL unit, viewed without copying them
 * @param reachesDecoder whether FFmpeg's splitter hands the NAL unit to the decoder
 */
record HevcNalUnit(@NonNull ByteBuffer bytes, int type, boolean rpu, boolean reachesDecoder) {

  /**
   * The most NAL units of a sample that {@link #delimit} returns: well above the 600 slice segments
   * of a picture at HEVC's highest level (ITU-T H.265 table A.8), with its parameter sets and SEI.
   */
  static final int MAXIMUM_NAL_UNITS_PER_SAMPLE = 4096;

  // FFmpeg's splitter reads the next NAL unit only while at least this many bytes remain.
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

  boolean slice() {
    return reachesDecoder && type < FIRST_NON_PICTURE_TYPE;
  }

  int length() {
    return bytes.remaining();
  }

  /**
   * The sample's NAL units in order; empty when a length runs past the end of the sample, is 0,
   * which makes FFmpeg reject the whole sample, or the sample holds more than {@link
   * #MAXIMUM_NAL_UNITS_PER_SAMPLE} NAL units.
   *
   * @param lengthSize the bytes of each length prefix: 1, 2 or 4
   */
  static Optional<List<HevcNalUnit>> delimit(ByteBuffer sample, int lengthSize) {
    var units = new ArrayList<HevcNalUnit>();
    var position = sample.position();
    while (position < sample.limit()) {
      if (sample.limit() - position < lengthSize || units.size() == MAXIMUM_NAL_UNITS_PER_SAMPLE) {
        return Optional.empty();
      }

      var isSplit = sample.limit() - position >= FEWEST_SPLIT_BYTES;
      var length = lengthAt(sample, position, lengthSize);
      var unitStart = position + lengthSize;
      if (length == 0 || length > sample.limit() - unitStart) {
        return Optional.empty();
      }

      var end = unitStart + (int) length;
      units.add(unitAt(sample, position, unitStart, end, isSplit));
      position = end;
    }

    return Optional.of(List.copyOf(units));
  }

  private static HevcNalUnit unitAt(
      ByteBuffer sample, int start, int unitStart, int end, boolean isSplit) {
    var bytes = sample.slice(start, end - start);
    if (!isSplit || end - unitStart < NAL_UNIT_HEADER_BYTES) {
      return new HevcNalUnit(bytes, -1, false, false);
    }

    var first = Byte.toUnsignedInt(sample.get(unitStart));
    var second = Byte.toUnsignedInt(sample.get(unitStart + 1));
    var type = (first >> 1) & 0x3F;
    var layerId = (first & 0x01) << 5 | second >> 3;
    var temporalIdPlus1 = second & 0x07;
    var reachesDecoder = (first & 0x80) == 0 && layerId != UNDECODED_LAYER && temporalIdPlus1 != 0;
    var rpu =
        reachesDecoder
            && type == UNSPECIFIED_62
            && layerId == 0
            && temporalIdPlus1 == 1
            && end - unitStart > NAL_UNIT_HEADER_BYTES
            && Byte.toUnsignedInt(sample.get(unitStart + NAL_UNIT_HEADER_BYTES)) == RPU_NAL_PREFIX;
    return new HevcNalUnit(bytes, type, rpu, reachesDecoder);
  }

  private static long lengthAt(ByteBuffer sample, int position, int lengthSize) {
    var length = 0L;
    for (var index = 0; index < lengthSize; index++) {
      length = length << 8 | Byte.toUnsignedInt(sample.get(position + index));
    }

    return length;
  }
}
