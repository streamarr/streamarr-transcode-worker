package com.streamarr.transcode.probe;

import java.util.List;
import java.util.Optional;

/**
 * How a codec's NAL units name their type, which of them carry a picture, SEI messages or a Dolby
 * Vision RPU, and how its decoder configuration record sets the length of each NAL unit in a
 * sample. An RPU is a NAL unit of the codec's RPU type whose payload starts with rpu_nal_prefix 25,
 * and for HEVC whose header names layer 0 and TemporalId 0.
 *
 * <p>The walks read only the NAL units that reach FFmpeg's decoders. FFmpeg's ff_h2645_packet_split
 * drops a NAL unit whose forbidden_zero_bit is 1, an H.264 NAL unit whose bytes are all 0, and an
 * HEVC NAL unit shorter than its 2-byte header or whose nuh_temporal_id_plus1 is 0 or nuh_layer_id
 * is 63, so the walks skip each of them as if the bytes did not hold it: it is no RPU, no SEI NAL
 * unit and no slice.
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

    @Override
    boolean isSei(byte[] unit) {
      return type(unit) == 6;
    }

    @Override
    boolean reachesDecoder(byte[] unit) {
      if (!super.reachesDecoder(unit)) {
        return false;
      }

      for (var value : unit) {
        if (value != 0) {
          return true;
        }
      }

      return false;
    }
  },

  /** HEVC, whose hvcC record holds lengthSizeMinusOne in the low 2 bits of its 22nd byte. */
  HEVC(2, 62, 0, 31) {
    @Override
    int type(byte[] unit) {
      return (unit[0] >> 1) & 0x3F;
    }

    @Override
    Optional<Integer> lengthSize(byte[] extradata) {
      return recordLengthSize(extradata, 21);
    }

    /** A prefix (type 39) or a suffix (type 40) SEI NAL unit. */
    @Override
    boolean isSei(byte[] unit) {
      var type = type(unit);
      return type == 39 || type == 40;
    }

    /**
     * FFmpeg's HEVC decoder reads a type 62 NAL unit as an RPU only when its nuh_layer_id is 0 and
     * its nuh_temporal_id_plus1 is 1.
     */
    @Override
    boolean isRpu(byte[] unit) {
      return super.isRpu(unit) && layerId(unit) == 0 && temporalIdPlus1(unit) == 1;
    }

    @Override
    boolean reachesDecoder(byte[] unit) {
      return unit.length >= 2
          && super.reachesDecoder(unit)
          && layerId(unit) != 63
          && temporalIdPlus1(unit) != 0;
    }

    private static int layerId(byte[] unit) {
      return (unit[0] & 0x01) << 5 | Byte.toUnsignedInt(unit[1]) >> 3;
    }

    private static int temporalIdPlus1(byte[] unit) {
      return unit[1] & 0x07;
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

  /** Whether the NAL unit holds SEI messages. */
  abstract boolean isSei(byte[] unit);

  /**
   * The NAL units of the extradata that the walks read. Extradata whose first byte is
   * configurationVersion 1 is an avcC or an hvcC record: the walks read every NAL unit of an hvcC
   * record's arrays, and none of an avcC record, which holds only parameter sets, but count an avcC
   * record that FFmpeg cannot read whole as incomplete. Start codes delimit the NAL units of any
   * other extradata, as in Annex B.
   */
  NalUnits extradataUnits(byte[] extradata) {
    if (Byte.toUnsignedInt(extradata[0]) != RECORD_VERSION) {
      return reachingDecoder(NalUnits.annexB(extradata));
    }

    return switch (this) {
      case H264 -> new NalUnits(List.of(), DecoderConfiguration.isWholeAvcRecord(extradata));
      case HEVC -> reachingDecoder(DecoderConfiguration.hevcRecordUnits(extradata));
    };
  }

  /** The NAL units that reach FFmpeg's decoders, and whether all of them reach the bytes' end. */
  NalUnits reachingDecoder(NalUnits delimited) {
    return new NalUnits(
        delimited.units().stream().filter(this::reachesDecoder).toList(), delimited.complete());
  }

  /** Whether FFmpeg's ff_h2645_packet_split hands the NAL unit to the codec's decoder. */
  boolean reachesDecoder(byte[] unit) {
    return unit.length > 0 && (unit[0] & 0x80) == 0;
  }

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
