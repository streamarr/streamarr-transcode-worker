package com.streamarr.transcode.engine;

import java.util.Optional;

/** The transfer characteristic of a PQ or HLG encode, as FFmpeg and the encoders name it. */
enum HdrTransfer {
  /** SMPTE ST 2084, ITU-T H.273 transfer characteristics 16. */
  PQ("smpte2084", 16),
  /** ARIB STD-B67, ITU-T H.273 transfer characteristics 18. */
  HLG("arib-std-b67", 18);

  private final String ffmpegName;
  private final int transferCharacteristics;

  HdrTransfer(String ffmpegName, int transferCharacteristics) {
    this.ffmpegName = ffmpegName;
    this.transferCharacteristics = transferCharacteristics;
  }

  /** The transfer of a PQ or HLG range; empty for any other range. */
  static Optional<HdrTransfer> of(VideoDynamicRange range) {
    return switch (range.compatibleBase()) {
      case PQ -> Optional.of(PQ);
      case HLG -> Optional.of(HLG);
      case SDR, NONE, UNSPECIFIED -> Optional.empty();
    };
  }

  /** The name that FFmpeg's -color_trc, x265's transfer and SVT-AV1's parameters take. */
  String ffmpegName() {
    return ffmpegName;
  }

  /** The code point that a colr box declares. */
  int transferCharacteristics() {
    return transferCharacteristics;
  }
}
