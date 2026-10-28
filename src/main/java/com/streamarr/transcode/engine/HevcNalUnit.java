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
 * @param bytes the length prefix and the NAL unit, viewed without copying them
 */
record HevcNalUnit(@NonNull ByteBuffer bytes, int type, boolean rpu) {

  /**
   * The most NAL units of a sample that {@link #delimit} returns: well above the 600 slice segments
   * of a picture at HEVC's highest level (ITU-T H.265 table A.8), with its parameter sets and SEI.
   */
  static final int MAXIMUM_NAL_UNITS_PER_SAMPLE = 4096;

  private static final int NAL_UNIT_HEADER_BYTES = 2;
  // NAL unit types below this one hold picture slices (ITU-T H.265 table 7-1).
  private static final int FIRST_NON_PICTURE_TYPE = 32;
  private static final int UNSPECIFIED_62 = 62;
  // A Dolby Vision RPU is an UNSPECIFIED_62 NAL unit whose payload starts with rpu_nal_prefix 25.
  private static final int RPU_NAL_PREFIX = 25;

  HevcNalUnit {
    bytes = bytes.slice();
  }

  boolean slice() {
    return type < FIRST_NON_PICTURE_TYPE;
  }

  int length() {
    return bytes.remaining();
  }

  /**
   * The sample's NAL units in order; empty when a length runs past the end of the sample, declares
   * fewer bytes than a NAL unit header, or the sample holds more than {@link
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

      var length = lengthAt(sample, position, lengthSize);
      var unitStart = position + lengthSize;
      if (length < NAL_UNIT_HEADER_BYTES || length > sample.limit() - unitStart) {
        return Optional.empty();
      }

      var end = unitStart + (int) length;
      units.add(unitAt(sample, position, unitStart, end));
      position = end;
    }

    return Optional.of(List.copyOf(units));
  }

  private static HevcNalUnit unitAt(ByteBuffer sample, int start, int unitStart, int end) {
    var type = (Byte.toUnsignedInt(sample.get(unitStart)) >> 1) & 0x3F;
    var rpu =
        type == UNSPECIFIED_62
            && end - unitStart > NAL_UNIT_HEADER_BYTES
            && Byte.toUnsignedInt(sample.get(unitStart + NAL_UNIT_HEADER_BYTES)) == RPU_NAL_PREFIX;
    return new HevcNalUnit(sample.slice(start, end - start), type, rpu);
  }

  private static long lengthAt(ByteBuffer sample, int position, int lengthSize) {
    var length = 0L;
    for (var index = 0; index < lengthSize; index++) {
      length = length << 8 | Byte.toUnsignedInt(sample.get(position + index));
    }

    return length;
  }
}
