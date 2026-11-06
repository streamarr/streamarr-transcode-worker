package com.streamarr.transcode.worker;

import build.buf.gen.streamarr.transcode.v1.DeinterlaceOutput;
import build.buf.gen.streamarr.transcode.v1.FieldOrder;
import build.buf.gen.streamarr.transcode.v1.VideoCodecFamily;
import build.buf.gen.streamarr.transcode.v1.VideoMode;
import com.streamarr.transcode.engine.CodecFamily;
import com.streamarr.transcode.engine.Deinterlace;
import com.streamarr.transcode.engine.TranscodeMode;
import com.streamarr.transcode.engine.VideoCodecProfile;
import com.streamarr.transcode.engine.VideoCodecTier;
import com.streamarr.transcode.engine.VideoDecision;
import com.streamarr.transcode.engine.VideoDynamicRange;
import com.streamarr.transcode.engine.VideoDynamicRange.CompatibleBase;
import com.streamarr.transcode.engine.VideoDynamicRange.DolbyVision;
import com.streamarr.transcode.engine.VideoDynamicRange.Presence;
import java.util.Optional;

// Translates the contract's video decision into the engine's, and refuses one that contradicts the
// job or lacks a value that the worker forces or checks. Domain and protobuf contracts
// intentionally share simple names.
final class VideoDecisions {

  private VideoDecisions() {}

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  static VideoDecision of(
      build.buf.gen.streamarr.transcode.v1.TranscodeDecision decision, TranscodeMode mode) {
    if (!decision.hasVideo()) {
      throw new InvalidJobSpecificationException("The job carries no video decision");
    }

    var video = decision.getVideo();
    if (!video.hasStreamIndex() || video.getStreamIndex() < 0) {
      throw new InvalidJobSpecificationException(
          "The chosen video stream requires an index in FFmpeg's nonnegative integer range");
    }

    if (!encodes(video.getMode()).equals(Optional.of(mode.encodesVideo()))) {
      throw new InvalidJobSpecificationException(
          "Video mode " + video.getMode() + " contradicts transcode mode " + mode);
    }

    if (video.getCodecString().isEmpty()) {
      throw new InvalidJobSpecificationException("The video decision carries no codec string");
    }

    if (!mode.encodesVideo()) {
      return copy(video);
    }

    return encode(video);
  }

  /** The engine's name for the codec family; empty when the decision names none. */
  static Optional<String> codecFamilyName(VideoCodecFamily family) {
    return codecFamily(family).map(CodecFamily::familyName);
  }

  private static Optional<CodecFamily> codecFamily(VideoCodecFamily family) {
    return switch (family) {
      case VIDEO_CODEC_FAMILY_H264 -> Optional.of(CodecFamily.H264);
      case VIDEO_CODEC_FAMILY_HEVC -> Optional.of(CodecFamily.HEVC);
      case VIDEO_CODEC_FAMILY_AV1 -> Optional.of(CodecFamily.AV1);
      case VIDEO_CODEC_FAMILY_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
    };
  }

  private static Optional<Boolean> encodes(VideoMode mode) {
    return switch (mode) {
      case VIDEO_MODE_COPY -> Optional.of(false);
      case VIDEO_MODE_ENCODE -> Optional.of(true);
      case VIDEO_MODE_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static VideoDecision.Copy copy(build.buf.gen.streamarr.transcode.v1.VideoDecision video) {
    if (carriesEncodeValues(video)) {
      throw new InvalidJobSpecificationException(
          "A stream copy carries a codec profile, tier, level, deinterlace instruction or"
              + " instruction to hold the last frame");
    }

    if (video.hasSourceDynamicRange()) {
      VideoDynamicRanges.of(video.getSourceDynamicRange());
    }

    var rewrites = BitstreamRewrites.of(video.getBitstreamRewritesList());
    // A server that does not send the field leaves it false, so the copy keeps the Annex B filter.
    return VideoDecision.Copy.builder()
        .streamIndex(video.getStreamIndex())
        .codecString(video.getCodecString())
        .keepsPacketBytes(video.getCopyKeepsPacketBytes())
        .bitstreamRewrites(rewrites)
        .plannedOutput(PlannedCopies.of(video, rewrites))
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static boolean carriesEncodeValues(
      build.buf.gen.streamarr.transcode.v1.VideoDecision video) {
    return video.getCodecProfileValue()
            != build.buf.gen.streamarr.transcode.v1.VideoCodecProfile
                .VIDEO_CODEC_PROFILE_UNSPECIFIED_VALUE
        || video.getTierValue()
            != build.buf.gen.streamarr.transcode.v1.VideoCodecTier
                .VIDEO_CODEC_TIER_UNSPECIFIED_VALUE
        || video.hasLevel()
        || video.hasDeinterlace()
        || video.getHoldLastFrameToAdvertisedEnd();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static VideoDecision.Encode encode(
      build.buf.gen.streamarr.transcode.v1.VideoDecision video) {
    if (codecFamilyName(video.getCodecFamily()).isEmpty()) {
      throw new InvalidJobSpecificationException("The encode carries no codec family");
    }

    if (!video.hasLevel()) {
      throw new InvalidJobSpecificationException("The encode carries no level");
    }

    if (PlannedCopies.carriesCopyOnlyValues(video)) {
      throw new InvalidJobSpecificationException("An encode carries values that only a copy plans");
    }

    if (!video.hasSourceDynamicRange() || !video.hasOutputDynamicRange()) {
      throw new InvalidJobSpecificationException(
          "An encode requires source and output dynamic range");
    }

    var sourceDynamicRange = VideoDynamicRanges.of(video.getSourceDynamicRange());
    var outputDynamicRange = encodedRange(VideoDynamicRanges.of(video.getOutputDynamicRange()));
    requireTransferTheEncodeCanWrite(sourceDynamicRange, outputDynamicRange);
    var pictureFormat = EncodedPictureFormats.of(video, outputDynamicRange);
    var staticHdrMetadata = PlannedCopies.staticHdrMetadataOf(video);
    var outputsPq = outputDynamicRange.compatibleBase() == CompatibleBase.PQ;
    if (outputsPq != staticHdrMetadata.isPresent()) {
      throw new InvalidJobSpecificationException(
          "An encode carries static HDR metadata exactly when its output is PQ, and this one plans "
              + outputDynamicRange.compatibleBase());
    }

    var profile = codecProfile(video.getCodecProfile());
    if (!familyOf(profile).equals(video.getCodecFamily())) {
      throw new InvalidJobSpecificationException(
          "Codec profile " + profile + " does not belong to " + video.getCodecFamily());
    }

    EncodedPictureFormats.requireProfileThatHolds(profile, pictureFormat);
    var tier = tier(video.getTier());
    if (tier.isPresent() != hasTiers(profile)) {
      throw new InvalidJobSpecificationException(
          "An encode carries a tier exactly when its codec family has tiers, and "
              + video.getCodecFamily()
              + " carries "
              + video.getTier());
    }

    return VideoDecision.Encode.builder()
        .streamIndex(video.getStreamIndex())
        .sourceCodecFamily(codecFamily(video.getSourceCodecFamily()))
        .codecProfile(profile)
        .tier(tier)
        .level(video.getLevel())
        .codecString(video.getCodecString())
        .sourceDynamicRange(sourceDynamicRange)
        .outputDynamicRange(outputDynamicRange)
        .sourcePacketsCarryPresentationTimes(video.getSourcePacketsCarryPresentationTimes())
        .deinterlace(deinterlace(video))
        .holdsLastFrameToAdvertisedEnd(video.getHoldLastFrameToAdvertisedEnd())
        .softwareEncoder(video.getSoftwareEncoder())
        .outputPictureFormat(pictureFormat)
        .staticHdrMetadata(staticHdrMetadata)
        .build();
  }

  // An encode writes SDR, PQ or HLG without HDR10+ or Dolby Vision (ADR 0036).
  private static VideoDynamicRange encodedRange(VideoDynamicRange range) {
    var base = range.compatibleBase();
    if ((base == CompatibleBase.SDR || base == CompatibleBase.PQ || base == CompatibleBase.HLG)
        && range.hdr10Plus() == Presence.ABSENT
        && range.dolbyVision() instanceof DolbyVision.Absent) {
      return range;
    }

    throw new InvalidJobSpecificationException(
        "An encode writes SDR, PQ or HLG without HDR10+ or Dolby Vision, not " + range);
  }

  // An HDR encode keeps the source's transfer, or reshapes Dolby Vision without a compatible base
  // into PQ; no filter converts between PQ and HLG, or from SDR into HDR.
  private static void requireTransferTheEncodeCanWrite(
      VideoDynamicRange source, VideoDynamicRange output) {
    var sourceBase = source.compatibleBase();
    var writable =
        switch (output.compatibleBase()) {
          case PQ -> sourceBase == CompatibleBase.PQ || sourceBase == CompatibleBase.NONE;
          case HLG -> sourceBase == CompatibleBase.HLG;
          case UNSPECIFIED, NONE, SDR -> true;
        };
    if (!writable) {
      throw new InvalidJobSpecificationException(
          "An encode cannot write "
              + output.compatibleBase()
              + " from a "
              + sourceBase
              + " source");
    }
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<Deinterlace> deinterlace(
      build.buf.gen.streamarr.transcode.v1.VideoDecision video) {
    if (!video.hasDeinterlace()) {
      return Optional.empty();
    }

    var deinterlace = video.getDeinterlace();
    return Optional.of(
        new Deinterlace(firstField(deinterlace.getFieldOrder()), output(deinterlace.getOutput())));
  }

  // The field displayed first is the earlier one: the top field for TT and BT, the bottom field for
  // BB and TB.
  private static Deinterlace.FirstField firstField(FieldOrder fieldOrder) {
    return switch (fieldOrder) {
      case FIELD_ORDER_TT, FIELD_ORDER_BT -> Deinterlace.FirstField.TOP;
      case FIELD_ORDER_BB, FIELD_ORDER_TB -> Deinterlace.FirstField.BOTTOM;
      case FIELD_ORDER_UNSPECIFIED, FIELD_ORDER_PROGRESSIVE, UNRECOGNIZED ->
          throw new InvalidJobSpecificationException(
              "A deinterlace instruction requires an interlaced field order, not " + fieldOrder);
    };
  }

  private static Deinterlace.Output output(DeinterlaceOutput output) {
    return switch (output) {
      case DEINTERLACE_OUTPUT_ONE_FRAME_PER_FIELD -> Deinterlace.Output.ONE_FRAME_PER_FIELD;
      case DEINTERLACE_OUTPUT_ONE_FRAME_PER_FRAME -> Deinterlace.Output.ONE_FRAME_PER_FRAME;
      case DEINTERLACE_OUTPUT_UNSPECIFIED, UNRECOGNIZED ->
          throw new InvalidJobSpecificationException(
              "A deinterlace instruction requires an output, not " + output);
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static VideoCodecProfile codecProfile(
      build.buf.gen.streamarr.transcode.v1.VideoCodecProfile profile) {
    return switch (profile) {
      case VIDEO_CODEC_PROFILE_H264_HIGH -> VideoCodecProfile.H264_HIGH;
      case VIDEO_CODEC_PROFILE_HEVC_MAIN -> VideoCodecProfile.HEVC_MAIN;
      case VIDEO_CODEC_PROFILE_HEVC_MAIN_10 -> VideoCodecProfile.HEVC_MAIN_10;
      case VIDEO_CODEC_PROFILE_AV1_MAIN -> VideoCodecProfile.AV1_MAIN;
      case VIDEO_CODEC_PROFILE_UNSPECIFIED, UNRECOGNIZED ->
          throw new InvalidJobSpecificationException("The encode carries no codec profile");
      case VIDEO_CODEC_PROFILE_H264_CONSTRAINED_BASELINE,
          VIDEO_CODEC_PROFILE_H264_BASELINE,
          VIDEO_CODEC_PROFILE_H264_MAIN,
          VIDEO_CODEC_PROFILE_H264_EXTENDED,
          VIDEO_CODEC_PROFILE_H264_HIGH_10,
          VIDEO_CODEC_PROFILE_H264_HIGH_422,
          VIDEO_CODEC_PROFILE_H264_HIGH_444_PREDICTIVE,
          VIDEO_CODEC_PROFILE_HEVC_MAIN_STILL_PICTURE,
          VIDEO_CODEC_PROFILE_HEVC_FORMAT_RANGE_EXTENSIONS,
          VIDEO_CODEC_PROFILE_AV1_HIGH,
          VIDEO_CODEC_PROFILE_AV1_PROFESSIONAL ->
          throw new InvalidJobSpecificationException(
              "An encode forces only a Main, Main 10 or High profile, and carries " + profile);
    };
  }

  private static VideoCodecFamily familyOf(VideoCodecProfile profile) {
    return switch (profile) {
      case H264_HIGH -> VideoCodecFamily.VIDEO_CODEC_FAMILY_H264;
      case HEVC_MAIN, HEVC_MAIN_10 -> VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC;
      case AV1_MAIN -> VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1;
    };
  }

  // H.264 has no tier; HEVC and AV1 write one into every codec string.
  private static boolean hasTiers(VideoCodecProfile profile) {
    return switch (profile) {
      case H264_HIGH -> false;
      case HEVC_MAIN, HEVC_MAIN_10, AV1_MAIN -> true;
    };
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static Optional<VideoCodecTier> tier(
      build.buf.gen.streamarr.transcode.v1.VideoCodecTier tier) {
    return switch (tier) {
      case VIDEO_CODEC_TIER_MAIN -> Optional.of(VideoCodecTier.MAIN);
      case VIDEO_CODEC_TIER_HIGH -> Optional.of(VideoCodecTier.HIGH);
      case VIDEO_CODEC_TIER_UNSPECIFIED, UNRECOGNIZED -> Optional.empty();
    };
  }
}
