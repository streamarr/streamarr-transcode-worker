package com.streamarr.transcode.engine;

/**
 * A change that a stream copy makes to the source's video without decoding it. The worker applies
 * exactly the rewrites that the job lists to the fragmented MP4 that FFmpeg writes.
 */
public enum BitstreamRewrite {
  /**
   * Removes every Dolby Vision RPU, every enhancement-layer NAL unit and the Dolby Vision
   * configuration record: in HEVC, every NAL unit of type 62 or 63; in AV1, every ITU-T T.35
   * metadata OBU that carries an RPU.
   */
  STRIP_DOLBY_VISION,
  /**
   * Removes every HDR10+ message: in HEVC, each ITU-T T.35 SEI message of HDR10+, keeping the other
   * messages of its NAL unit; in AV1, every ITU-T T.35 metadata OBU of HDR10+.
   */
  STRIP_HDR10_PLUS
}
