package com.streamarr.transcode.engine;

import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * The output that the server planned for a copy, beyond passing the source's video through (ADR
 * 0013): its dynamic range, the Dolby Vision that the playlist announces, and the static HDR
 * metadata that the worker writes into the {@code hvcC} of an HEVC copy (ADR 0036). Every job
 * attempt of a variant carries the same planned output, so every attempt writes the same
 * initialization segment.
 *
 * @param dynamicRange the planned range, whose compatible base is never {@code UNSPECIFIED}
 * @param dolbyVision the Dolby Vision that the copy's sample entry and configuration record carry;
 *     empty for a copy that the playlist does not describe as Dolby Vision
 * @param masteringDisplay the mastering display color volume that the probe holds; empty when it
 *     holds none
 * @param contentLightLevel the content light level that the probe holds; empty when it holds none
 */
@Builder
public record PlannedCopyOutput(
    @NonNull VideoDynamicRange dynamicRange,
    @NonNull Optional<DolbyVisionOutput> dolbyVision,
    @NonNull Optional<MasteringDisplay> masteringDisplay,
    @NonNull Optional<ContentLightLevel> contentLightLevel) {

  public PlannedCopyOutput {
    if (dynamicRange.compatibleBase() == VideoDynamicRange.CompatibleBase.UNSPECIFIED) {
      throw new IllegalArgumentException("a planned output names its compatible base");
    }
  }

  /**
   * The Dolby Vision of a copy's output (ADR 0013).
   *
   * @param profile the Dolby Vision profile, such as 5, 8 or 10
   * @param level the Dolby Vision level, from 1 to 13, that the server set
   * @param baseLayerCompatibilityId bl_signal_compatibility_id: 0 for profiles 5 and 10.0, 1 for a
   *     PQ base, 2 for an SDR base and 4 for an HLG base
   * @param sampleEntry the sample entry that the copy declares, which the playlist's codec string
   *     names
   */
  @Builder
  public record DolbyVisionOutput(
      int profile, int level, int baseLayerCompatibilityId, @NonNull SampleEntry sampleEntry) {

    /** Whether the output is profile 8.1: profile 8 on a PQ base under the HEVC base entry. */
    public boolean isProfile81() {
      return profile == 8 && baseLayerCompatibilityId == 1 && sampleEntry == SampleEntry.HVC1;
    }
  }

  /** The sample entry of a copy that carries Dolby Vision. */
  public enum SampleEntry {
    /** The HEVC base entry with a Dolby Vision record, for profiles 8.1 and 8.4. */
    HVC1("hvc1"),
    /** The Dolby Vision HEVC entry, for profile 5. */
    DVH1("dvh1"),
    /** The AV1 base entry with a Dolby Vision record, for profiles 10.1 and 10.4. */
    AV01("av01");

    private final String type;

    SampleEntry(String type) {
      this.type = type;
    }

    /** The sample entry's box type. */
    public String type() {
      return type;
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
    private Optional<DolbyVisionOutput> dolbyVision = Optional.empty();
    private Optional<MasteringDisplay> masteringDisplay = Optional.empty();
    private Optional<ContentLightLevel> contentLightLevel = Optional.empty();
  }
}
