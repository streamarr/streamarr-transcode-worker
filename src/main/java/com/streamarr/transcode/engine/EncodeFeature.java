package com.streamarr.transcode.engine;

/** An output beyond 8-bit 4:2:0 SDR that a verified encoder writes, after its own test encode. */
public enum EncodeFeature {
  /** 10-bit 4:2:0 video. */
  TEN_BIT,
  /** 10-bit BT.2020 PQ video with HDR10 static metadata. */
  PQ,
  /** 10-bit BT.2020 HLG video. */
  HLG,
  /** Dolby Vision profile 5 or 10.0 reshaped with its RPUs into PQ before the encoder. */
  DOLBY_VISION_TO_PQ
}
