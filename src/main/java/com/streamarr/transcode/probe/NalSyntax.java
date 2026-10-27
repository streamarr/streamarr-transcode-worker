package com.streamarr.transcode.probe;

import java.util.Optional;

/**
 * How a codec's NAL units name their type, which of them carry a picture or a Dolby Vision RPU, and
 * how its decoder configuration record sets the length of each NAL unit in a sample. An RPU is a
 * NAL unit of the codec's RPU type whose payload starts with rpu_nal_prefix 25.
 */
enum NalSyntax {
  /** H.264, whose avcC record holds lengthSizeMinusOne in the low 2 bits of its fifth byte. */
  H264(1, 28, 1, 5) {
    @Override
    int type(byte[] unit) {
      return unit[0] & 0x1F;
    }

    @Override
    Optional<Integer> lengthSize(byte[] extradata) {
      return recordLengthSize(extradata, 4);
    }
  };

  private static final int RECORD_VERSION = 1;
  private static final int RPU_NAL_PREFIX = 25;

  private final int headerLength;
  private final int rpuType;
  private final int firstPictureType;
  private final int lastPictureType;

  NalSyntax(int headerLength, int rpuType, int firstPictureType, int lastPictureType) {
    this.headerLength = headerLength;
    this.rpuType = rpuType;
    this.firstPictureType = firstPictureType;
    this.lastPictureType = lastPictureType;
  }

  /** The NAL unit's type, from its header. */
  abstract int type(byte[] unit);

  /**
   * The byte count of the length ahead of each NAL unit of a sample; empty when the extradata is no
   * decoder configuration record, so that start codes delimit the NAL units.
   */
  abstract Optional<Integer> lengthSize(byte[] extradata);

  boolean isRpu(byte[] unit) {
    return unit.length > headerLength
        && type(unit) == rpuType
        && Byte.toUnsignedInt(unit[headerLength]) == RPU_NAL_PREFIX;
  }

  /** Whether the NAL unit holds a slice of a picture. */
  boolean isPicture(byte[] unit) {
    var type = type(unit);
    return type >= firstPictureType && type <= lastPictureType;
  }

  /** The offset of a NAL unit's payload: the byte count of its header. */
  int payloadOffset() {
    return headerLength;
  }

  private static Optional<Integer> recordLengthSize(byte[] extradata, int offset) {
    if (extradata.length <= offset || Byte.toUnsignedInt(extradata[0]) != RECORD_VERSION) {
      return Optional.empty();
    }

    return Optional.of((extradata[offset] & 0x03) + 1);
  }
}
