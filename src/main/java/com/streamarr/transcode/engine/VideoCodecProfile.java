package com.streamarr.transcode.engine;

/** The codec profile that an encode forces. */
public enum VideoCodecProfile {
  H264_HIGH,
  HEVC_MAIN,
  /** HEVC Main 10, which an encode forces only with 10-bit output. */
  HEVC_MAIN_10,
  AV1_MAIN
}
