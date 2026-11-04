package com.streamarr.transcode.engine;

import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * The output that the server planned for a copy, beyond passing the source's video through (ADR
 * 0013): its dynamic range, and the static HDR metadata that the worker writes into the {@code
 * hvcC} of an HEVC copy (ADR 0036). Every job attempt of a variant carries the same planned output,
 * so every attempt writes the same initialization segment.
 *
 * @param dynamicRange the planned range, whose compatible base is never {@code UNSPECIFIED}
 * @param masteringDisplay the mastering display color volume that the probe holds; empty when it
 *     holds none
 * @param contentLightLevel the content light level that the probe holds; empty when it holds none
 */
@Builder
public record PlannedCopyOutput(
    @NonNull VideoDynamicRange dynamicRange,
    @NonNull Optional<MasteringDisplay> masteringDisplay,
    @NonNull Optional<ContentLightLevel> contentLightLevel) {

  public PlannedCopyOutput {
    if (dynamicRange.compatibleBase() == VideoDynamicRange.CompatibleBase.UNSPECIFIED) {
      throw new IllegalArgumentException("a planned output names its compatible base");
    }
  }

  /**
   * A mastering display color volume (SMPTE ST 2086), in the units of the HEVC SEI message's fields
   * (ITU-T H.265 D.3.28).
   *
   * @param maxLuminance in units of 0.0001 cd/m²
   * @param minLuminance in units of 0.0001 cd/m²
   */
  @Builder
  public record MasteringDisplay(
      @NonNull Chromaticity red,
      @NonNull Chromaticity green,
      @NonNull Chromaticity blue,
      @NonNull Chromaticity whitePoint,
      long maxLuminance,
      long minLuminance) {}

  /** A CIE 1931 chromaticity in units of 0.00002, from 0 to 50000. */
  public record Chromaticity(int x, int y) {}

  /**
   * A content light level (CTA-861.3) in cd/m², from 0 to 65535 each, as the HEVC SEI message holds
   * it (ITU-T H.265 D.3.35).
   */
  public record ContentLightLevel(int maxContentLightLevel, int maxFrameAverageLightLevel) {}

  // Lombok builder defaults — their generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class PlannedCopyOutputBuilder {
    private Optional<MasteringDisplay> masteringDisplay = Optional.empty();
    private Optional<ContentLightLevel> contentLightLevel = Optional.empty();
  }
}
