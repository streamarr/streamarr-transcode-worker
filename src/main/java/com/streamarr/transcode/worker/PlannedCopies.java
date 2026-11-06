package com.streamarr.transcode.worker;

import build.buf.gen.streamarr.transcode.v1.ChromaticityCoordinates;
import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionOutput;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionSampleEntry;
import build.buf.gen.streamarr.transcode.v1.MasteringDisplayColorVolume;
import build.buf.gen.streamarr.transcode.v1.VideoCopyFeature;
import com.streamarr.transcode.engine.BitstreamRewrite;
import com.streamarr.transcode.engine.PlannedCopyOutput;
import com.streamarr.transcode.engine.PlannedCopyOutput.Chromaticity;
import com.streamarr.transcode.engine.PlannedCopyOutput.SampleEntry;
import com.streamarr.transcode.engine.StaticHdrMetadata;
import com.streamarr.transcode.engine.VideoDynamicRange;
import com.streamarr.transcode.engine.VideoDynamicRange.CompatibleBase;
import com.streamarr.transcode.engine.VideoDynamicRange.DolbyVision;
import com.streamarr.transcode.engine.VideoDynamicRange.Presence;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// Translates the output that the server planned for a copy (ADR 0013, ADR 0036) into the engine's,
// and refuses one that the contract forbids or that the worker cannot write.
final class PlannedCopies {

  // ITU-T H.265 D.3.28 and CTA-861.3 bound each chromaticity and each light level.
  private static final int MAX_CHROMATICITY = 50_000;
  private static final int MAX_LIGHT_LEVEL = 65_535;
  private static final int MIN_DOLBY_VISION_LEVEL = 1;
  private static final int MAX_DOLBY_VISION_LEVEL = 13;

  private PlannedCopies() {}

  /** Whether the decision carries a value that only a copy with a planned output carries. */
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static boolean carriesPlannedValues(build.buf.gen.streamarr.transcode.v1.VideoDecision video) {
    return carriesStaticValues(video)
        || video.getBitstreamRewritesCount() > 0
        || video.hasDolbyVisionOutput();
  }

  /**
   * The copy's planned output; empty for a copy that passes the source's range through.
   *
   * @param rewrites the bitstream rewrites that the copy lists
   */
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Optional<PlannedCopyOutput> of(
      build.buf.gen.streamarr.transcode.v1.VideoDecision video, Set<BitstreamRewrite> rewrites) {
    if (!video.hasOutputDynamicRange() && carriesPlannedValues(video)) {
      throw new InvalidJobSpecificationException(
          "A copy carries planned output values without a planned range");
    }

    if (!video.hasOutputDynamicRange()) {
      return Optional.empty();
    }

    var range = specified(VideoDynamicRanges.of(video.getOutputDynamicRange()));
    if (range.compatibleBase() != CompatibleBase.PQ && carriesStaticValues(video)) {
      throw new InvalidJobSpecificationException(
          "Only a copy with a PQ output carries static HDR metadata, and this one plans "
              + range.compatibleBase());
    }

    requireRewritesThatFit(range, rewrites, video.hasDolbyVisionOutput());
    return Optional.of(
        PlannedCopyOutput.builder()
            .dynamicRange(range)
            .dolbyVision(
                video.hasDolbyVisionOutput()
                    ? Optional.of(dolbyVisionOutput(video.getDolbyVisionOutput(), range))
                    : Optional.empty())
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

  /** Whether the decision carries a bitstream rewrite or a Dolby Vision output. */
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static boolean carriesCopyOnlyValues(build.buf.gen.streamarr.transcode.v1.VideoDecision video) {
    return video.getBitstreamRewritesCount() > 0 || video.hasDolbyVisionOutput();
  }

  /**
   * The HDR10 static metadata of a PQ encode, which carries both values; empty for an encode that
   * carries neither.
   */
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static Optional<StaticHdrMetadata> staticHdrMetadataOf(
      build.buf.gen.streamarr.transcode.v1.VideoDecision video) {
    if (!carriesStaticValues(video)) {
      return Optional.empty();
    }

    if (!video.hasMasteringDisplay() || !video.hasContentLightLevel()) {
      throw new InvalidJobSpecificationException(
          "A PQ encode carries both the mastering display and the content light level");
    }

    return Optional.of(
        new StaticHdrMetadata(
            masteringDisplay(video.getMasteringDisplay()),
            contentLightLevel(video.getContentLightLevel())));
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static boolean carriesStaticValues(
      build.buf.gen.streamarr.transcode.v1.VideoDecision video) {
    return video.hasMasteringDisplay() || video.hasContentLightLevel();
  }

  // The planned range describes the output after the rewrites, so it keeps nothing that a rewrite
  // strips, and only a planned Dolby Vision output names the record that the copy normalizes to.
  private static void requireRewritesThatFit(
      VideoDynamicRange range, Set<BitstreamRewrite> rewrites, boolean plansDolbyVision) {
    if (rewrites.contains(BitstreamRewrite.STRIP_DOLBY_VISION)
        && range.dolbyVision() instanceof DolbyVision.Present) {
      throw new InvalidJobSpecificationException(
          "A copy that strips Dolby Vision plans a range with Dolby Vision");
    }

    if (rewrites.contains(BitstreamRewrite.STRIP_HDR10_PLUS)
        && range.hdr10Plus() == Presence.PRESENT) {
      throw new InvalidJobSpecificationException("A copy that strips HDR10+ plans a range with it");
    }

    if (rewrites.contains(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD) && !plansDolbyVision) {
      throw new InvalidJobSpecificationException(
          "A copy normalizes its Dolby Vision record without a planned Dolby Vision output");
    }
  }

  // The sample entry decides the profile, and the planned base decides the compatibility
  // identifier (ADR 0013). Level 1 to 13 is the range that the contract allows.
  private static PlannedCopyOutput.DolbyVisionOutput dolbyVisionOutput(
      DolbyVisionOutput output, VideoDynamicRange range) {
    if (!(range.dolbyVision() instanceof DolbyVision.Present)) {
      throw new InvalidJobSpecificationException(
          "A copy plans a Dolby Vision output with a range without Dolby Vision");
    }

    var sampleEntry = sampleEntry(output.getSampleEntry());
    if (output.getProfile() != profileOf(sampleEntry)) {
      throw new InvalidJobSpecificationException(
          "A " + sampleEntry.type() + " copy cannot carry profile " + output.getProfile());
    }

    if (output.getLevel() < MIN_DOLBY_VISION_LEVEL || output.getLevel() > MAX_DOLBY_VISION_LEVEL) {
      throw new InvalidJobSpecificationException(
          "The Dolby Vision level "
              + Integer.toUnsignedString(output.getLevel())
              + " is not 1 to 13");
    }

    var compatibilityId = compatibilityIdOf(range.compatibleBase());
    if (output.getBaseLayerCompatibilityId() != compatibilityId) {
      throw new InvalidJobSpecificationException(
          "A copy with a "
              + range.compatibleBase()
              + " base takes compatibility identifier "
              + compatibilityId
              + ", not "
              + Integer.toUnsignedString(output.getBaseLayerCompatibilityId()));
    }

    return PlannedCopyOutput.DolbyVisionOutput.builder()
        .profile(output.getProfile())
        .level(output.getLevel())
        .baseLayerCompatibilityId(compatibilityId)
        .sampleEntry(sampleEntry)
        .build();
  }

  /**
   * The copy features that the worker declares when it registers: the planned output, and the
   * feature of each Dolby Vision sample entry that the engine writes and the base entry lacks.
   */
  static List<VideoCopyFeature> declaredFeatures() {
    var features = new ArrayList<VideoCopyFeature>();
    features.add(VideoCopyFeature.VIDEO_COPY_FEATURE_PLANNED_OUTPUT);
    Arrays.stream(SampleEntry.values())
        .map(PlannedCopies::featureOf)
        .flatMap(Optional::stream)
        .forEach(features::add);
    return List.copyOf(features);
  }

  private static Optional<VideoCopyFeature> featureOf(SampleEntry sampleEntry) {
    return switch (sampleEntry) {
      case DVH1 -> Optional.of(VideoCopyFeature.VIDEO_COPY_FEATURE_DVH1_SAMPLE_ENTRY);
      case HVC1, AV01 -> Optional.empty();
    };
  }

  private static SampleEntry sampleEntry(DolbyVisionSampleEntry value) {
    return switch (value) {
      case DOLBY_VISION_SAMPLE_ENTRY_HVC1 -> SampleEntry.HVC1;
      case DOLBY_VISION_SAMPLE_ENTRY_DVH1 -> SampleEntry.DVH1;
      case DOLBY_VISION_SAMPLE_ENTRY_AV01 -> SampleEntry.AV01;
      case DOLBY_VISION_SAMPLE_ENTRY_DAV1, DOLBY_VISION_SAMPLE_ENTRY_UNSPECIFIED, UNRECOGNIZED ->
          throw new InvalidJobSpecificationException("The worker does not write " + value);
    };
  }

  private static int profileOf(SampleEntry sampleEntry) {
    return switch (sampleEntry) {
      case HVC1 -> 8;
      case DVH1 -> 5;
      case AV01 -> 10;
    };
  }

  // bl_signal_compatibility_id: 0 without a compatible base, 1 for PQ, 2 for SDR and 4 for HLG.
  private static int compatibilityIdOf(CompatibleBase base) {
    return switch (base) {
      case NONE -> 0;
      case PQ -> 1;
      case SDR -> 2;
      case HLG -> 4;
      case UNSPECIFIED -> throw new IllegalStateException("a planned range names its base");
    };
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
