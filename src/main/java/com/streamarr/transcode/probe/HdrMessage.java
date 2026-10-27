package com.streamarr.transcode.probe;

/** The HDR metadata that the probe's walks of a stream's bitstream look for. */
enum HdrMessage {
  /** An HDR10+ message: ITU-T T.35 with the HDR10+ provider and application identifiers. */
  HDR10_PLUS,
  /** A mastering display color volume. */
  MASTERING_DISPLAY,
  /** A content light level. */
  CONTENT_LIGHT_LEVEL
}
