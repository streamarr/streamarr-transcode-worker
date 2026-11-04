package com.streamarr.transcode.worker;

import build.buf.gen.streamarr.transcode.v1.ChromaticityCoordinates;
import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.MasteringDisplayColorVolume;
import com.streamarr.transcode.engine.PlannedCopyOutput;
import com.streamarr.transcode.engine.PlannedCopyOutput.Chromaticity;
import com.streamarr.transcode.engine.VideoDynamicRange;
import com.streamarr.transcode.engine.VideoDynamicRange.CompatibleBase;
import com.streamarr.transcode.engine.VideoDynamicRange.DolbyVision;
import com.streamarr.transcode.engine.VideoDynamicRange.Presence;
import java.util.Optional;

// Translates the output that the server planned for a copy (ADR 0013, ADR 0036) into the engine's,
// and refuses one that the contract forbids or that the worker cannot write.
final class PlannedCopies {

  // ITU-T H.265 D.3.28 and CTA-861.3 bound each chromaticity and each light level.
  private static final int MAX_CHROMATICITY = 50_000;
  private static final int MAX_LIGHT_LEVEL = 65_535;

  private PlannedCopies() {}

  /** Whether the decision carries a value that only a copy with a planned output carries. */
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static boolean carriesPlannedValues(build.buf.gen.streamarr.transcode.v1.VideoDecision video) {
    return video.hasMasteringDisplay() || video.hasContentLightLevel();
  }

  /** The copy's planned output; empty for a copy that passes the source's range through. */
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Optional<PlannedCopyOutput> of(build.buf.gen.streamarr.transcode.v1.VideoDecision video) {
    if (!video.hasOutputDynamicRange() && carriesPlannedValues(video)) {
      throw new InvalidJobSpecificationException(
          "A copy carries planned output values without a planned range");
    }

    if (!video.hasOutputDynamicRange()) {
      return Optional.empty();
    }

    var range = specified(VideoDynamicRanges.of(video.getOutputDynamicRange()));
    if (range.compatibleBase() != CompatibleBase.PQ && carriesPlannedValues(video)) {
      throw new InvalidJobSpecificationException(
          "Only a copy with a PQ output carries static HDR metadata, and this one plans "
              + range.compatibleBase());
    }

    return Optional.of(
        PlannedCopyOutput.builder()
            .dynamicRange(range)
            .masteringDisplay(
                video.hasMasteringDisplay()
                    ? Optional.of(masteringDisplay(video.getMasteringDisplay()))
                    : Optional.empty())
            .contentLightLevel(
                video.hasContentLightLevel()
                    ? Optional.of(contentLightLevel(video.getContentLightLevel()))
                    : Optional.empty())
            .build());
  }

  // A planned output holds no UNSPECIFIED value (ADR 0036).
  private static VideoDynamicRange specified(VideoDynamicRange range) {
    if (range.compatibleBase() == CompatibleBase.UNSPECIFIED
        || range.hdr10Plus() == Presence.UNSPECIFIED
        || range.dolbyVision() instanceof DolbyVision.Unspecified) {
      throw new InvalidJobSpecificationException(
          "A copy's planned range holds an UNSPECIFIED value: " + range);
    }

    return range;
  }

  private static PlannedCopyOutput.MasteringDisplay masteringDisplay(
      MasteringDisplayColorVolume display) {
    return PlannedCopyOutput.MasteringDisplay.builder()
        .red(chromaticity(display.getRed()))
        .green(chromaticity(display.getGreen()))
        .blue(chromaticity(display.getBlue()))
        .whitePoint(chromaticity(display.getWhitePoint()))
        .maxLuminance(Integer.toUnsignedLong(display.getMaxLuminance()))
        .minLuminance(Integer.toUnsignedLong(display.getMinLuminance()))
        .build();
  }

  private static Chromaticity chromaticity(ChromaticityCoordinates coordinates) {
    return new Chromaticity(
        bounded(coordinates.getX(), MAX_CHROMATICITY, "chromaticity"),
        bounded(coordinates.getY(), MAX_CHROMATICITY, "chromaticity"));
  }

  private static PlannedCopyOutput.ContentLightLevel contentLightLevel(ContentLightLevel level) {
    return new PlannedCopyOutput.ContentLightLevel(
        bounded(level.getMaxContentLightLevel(), MAX_LIGHT_LEVEL, "MaxCLL"),
        bounded(level.getMaxFrameAverageLightLevel(), MAX_LIGHT_LEVEL, "MaxFALL"));
  }

  // The contract's unsigned value, refused above the largest value that its SEI field holds.
  private static int bounded(int unsigned, int max, String field) {
    if (Integer.compareUnsigned(unsigned, max) > 0) {
      throw new InvalidJobSpecificationException(
          "The " + field + " " + Integer.toUnsignedString(unsigned) + " exceeds " + max);
    }

    return unsigned;
  }
}
