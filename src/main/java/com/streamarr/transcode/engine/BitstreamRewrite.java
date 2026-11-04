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
  STRIP_DOLBY_VISION(true),
  /**
   * Removes every HDR10+ message: in HEVC, each ITU-T T.35 SEI message of HDR10+, keeping the other
   * messages of its NAL unit; in AV1, every ITU-T T.35 metadata OBU of HDR10+.
   */
  STRIP_HDR10_PLUS(true),
  /**
   * Writes the Dolby Vision configuration record from the copy's planned Dolby Vision output
   * instead of the record that the source declares: version 1.0, the planned profile, level and
   * compatibility identifier, an RPU and a base layer, no enhancement layer, and metadata
   * compression 0. A copy that lists it plans a Dolby Vision output.
   */
  NORMALIZE_DOLBY_VISION_RECORD(false);

  private final boolean rewritesSamples;

  BitstreamRewrite(boolean rewritesSamples) {
    this.rewritesSamples = rewritesSamples;
  }

  /**
   * Whether the rewrite changes the video samples; any other changes the initialization segment.
   */
  boolean rewritesSamples() {
    return rewritesSamples;
  }
}
