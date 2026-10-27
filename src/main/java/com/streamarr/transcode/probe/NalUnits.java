package com.streamarr.transcode.probe;

import java.util.List;

/**
 * NAL units that the probe delimited in a sample or a decoder configuration record, each without
 * its emulation prevention bytes, and whether they reach the end of the bytes that hold them.
 */
record NalUnits(List<byte[]> units, boolean complete) {

  /**
   * The NAL units that start codes delimit, as in Annex B. Bytes without a start code, or with data
   * ahead of the first one, hold bytes that the reader cannot delimit. Zero bytes may lead the
   * first start code.
   */
  static NalUnits annexB(byte[] bytes) {
    var units = DecoderConfiguration.annexB(bytes);
    var leading = 0;
    while (leading < bytes.length && bytes[leading] == 0) {
      leading++;
    }

    var startsWithStartCode =
        leading >= 2 && leading < bytes.length && Byte.toUnsignedInt(bytes[leading]) == 1;
    return new NalUnits(units, startsWithStartCode && !units.isEmpty());
  }
}
