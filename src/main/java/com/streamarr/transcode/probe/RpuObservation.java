package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionEnhancementLayer;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuPlacement;
import build.buf.gen.streamarr.transcode.v1.Presence;
import com.streamarr.transcode.probe.DolbyVisionRpuReader.RpuFacts;
import java.util.Collection;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.NonNull;

/**
 * What the probe saw of a video stream's Dolby Vision RPUs: whether the frames or packets that it
 * read hold one, the header of the first one, where the packets place each picture's RPU, the
 * enhancement layer that the parsed RPUs' NLQ parameters describe, and the MaxCLL and MaxFALL of
 * the first parsed level 6 block.
 */
record RpuObservation(
    @NonNull Presence presence,
    @NonNull Optional<DolbyVisionRpuHeader> firstHeader,
    @NonNull DolbyVisionRpuPlacement placement,
    @NonNull DolbyVisionEnhancementLayer enhancementLayer,
    @NonNull Optional<ContentLightLevel> level6) {

  /** An observation that parsed no RPU's mapping or display management. */
  RpuObservation(
      @NonNull Presence presence,
      @NonNull Optional<DolbyVisionRpuHeader> firstHeader,
      @NonNull DolbyVisionRpuPlacement placement) {
    this(
        presence,
        firstHeader,
        placement,
        DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_UNSPECIFIED,
        Optional.empty());
  }

  /**
   * This observation with the facts of the RPUs that the probe parsed, in stream order. A full
   * enhancement layer in any RPU makes the stream FEL, a minimal one MEL, and RPUs that all disable
   * the residual make it NONE. The level 6 values come from the first RPU that carries a block.
   */
  RpuObservation withParsed(@NonNull Collection<RpuFacts> parsed) {
    var layers = parsed.stream().map(RpuFacts::enhancementLayer).toList();
    var layer =
        Stream.of(
                DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_FEL,
                DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_MEL,
                DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_NONE)
            .filter(layers::contains)
            .findFirst()
            .orElse(DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_UNSPECIFIED);
    var firstLevel6 = parsed.stream().flatMap(facts -> facts.level6().stream()).findFirst();
    return new RpuObservation(presence, firstHeader, placement, layer, firstLevel6);
  }
}
