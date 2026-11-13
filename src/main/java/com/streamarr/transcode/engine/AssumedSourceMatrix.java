package com.streamarr.transcode.engine;

/** The matrix with which an SDR encode reads a source that names no matrix (ADR 0013). */
public enum AssumedSourceMatrix {
  /** Rec. ITU-R BT.709, the matrix of high-definition video. */
  BT709("bt709"),
  /** Rec. ITU-R BT.601, the matrix of standard-definition video, which SMPTE 170M shares. */
  BT601("smpte170m");

  private final String scaleFilterName;

  AssumedSourceMatrix(String scaleFilterName) {
    this.scaleFilterName = scaleFilterName;
  }

  /**
   * Whether an encode of a source in the range can assume a matrix: only the conversion of an SDR
   * or untagged source to BT.709 reads it.
   */
  public static boolean isAssumableFor(VideoDynamicRange source) {
    return switch (source.compatibleBase()) {
      case SDR, UNSPECIFIED -> true;
      case PQ, HLG, NONE -> false;
    };
  }

  /** The name that the scale filter's in_color_matrix takes. */
  String scaleFilterName() {
    return scaleFilterName;
  }
}
