package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import java.util.OptionalInt;

/**
 * The Dolby Vision profile that an RPU header names when no configuration record names one, as
 * FFmpeg 8.1 guesses it for each codec. Empty when the header names no profile.
 */
final class DolbyVisionProfileGuess {

  private static final int ENHANCEMENT_LAYER_BIT_DEPTH = 12;

  private DolbyVisionProfileGuess() {}

  static OptionalInt of(String codec, DolbyVisionRpuHeader header) {
    return switch (codec) {
      case "hevc" -> hevc(header);
      case "av1" -> OptionalInt.of(10);
      case "h264" -> OptionalInt.of(9);
      default -> OptionalInt.empty();
    };
  }

  private static OptionalInt hevc(DolbyVisionRpuHeader header) {
    return switch (header.getVdrRpuProfile()) {
      case 0 -> header.getBaseLayerFullRange() ? OptionalInt.of(5) : OptionalInt.empty();
      case 1 -> OptionalInt.of(profileOneLayers(header));
      default -> OptionalInt.empty();
    };
  }

  private static int profileOneLayers(DolbyVisionRpuHeader header) {
    var hasEnhancementLayer =
        header.getEnhancementLayerSpatialResamplingFilter() && !header.getDisableResidual();
    if (!hasEnhancementLayer) {
      return 8;
    }

    return header.getVdrBitDepth() == ENHANCEMENT_LAYER_BIT_DEPTH ? 7 : 4;
  }
}
