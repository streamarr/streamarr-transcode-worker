package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import java.util.OptionalInt;

/**
 * The Dolby Vision profile that an HEVC RPU header names when no configuration record names one, as
 * FFmpeg 8.1 guesses it. {@link StreamCodec#guessDolbyVisionProfile} guesses for the other codecs.
 */
final class DolbyVisionProfileGuess {

  private static final int ENHANCEMENT_LAYER_BIT_DEPTH = 12;

  private DolbyVisionProfileGuess() {}

  /** The guessed profile; empty when the header names no profile. */
  static OptionalInt ofHevc(DolbyVisionRpuHeader header) {
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
