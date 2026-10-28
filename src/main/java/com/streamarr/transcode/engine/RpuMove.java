package com.streamarr.transcode.engine;

/**
 * Where the worker moves an HEVC sample's Dolby Vision RPU that comes ahead of the sample's first
 * slice, so that the RPU ends the sample of the picture it belongs to, where FFmpeg's HEVC decoder
 * reads it. Either way, an RPU that follows a slice of its sample but is not the sample's last NAL
 * unit moves to the end of that sample.
 */
public enum RpuMove {
  /** The RPU belongs to the picture of its own sample, and moves to the end of that sample. */
  BEHIND_OWN_PICTURE,
  /**
   * The RPU belongs to the picture before, and moves to the end of that picture's sample; one that
   * has no previous picture, such as the first after a seek, is dropped.
   */
  BEHIND_PREVIOUS_PICTURE
}
