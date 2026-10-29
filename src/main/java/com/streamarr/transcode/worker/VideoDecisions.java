package com.streamarr.transcode.worker;

import build.buf.gen.streamarr.transcode.v1.VideoCodecFamily;
import build.buf.gen.streamarr.transcode.v1.VideoMode;
import com.streamarr.transcode.engine.CodecFamily;
import com.streamarr.transcode.engine.TranscodeMode;
import com.streamarr.transcode.engine.VideoCodecProfile;
import com.streamarr.transcode.engine.VideoCodecTier;
import com.streamarr.transcode.engine.VideoDecision;
import com.streamarr.transcode.engine.VideoDynamicRange;
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
          "A stream copy carries a codec profile, tier, level or output dynamic range");
    }

    if (video.hasSourceDynamicRange()) {
      VideoDynamicRanges.of(video.getSourceDynamicRange());
    }

    return new VideoDecision.Copy(video.getStreamIndex(), video.getCodecString());
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
        || video.hasOutputDynamicRange();
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

    if (!video.hasSourceDynamicRange() || !video.hasOutputDynamicRange()) {
      throw new InvalidJobSpecificationException(
          "An encode requires source and output dynamic range");
    }

    var outputDynamicRange = VideoDynamicRanges.of(video.getOutputDynamicRange());
    if (!outputDynamicRange.equals(VideoDynamicRange.sdr())) {
      throw new InvalidJobSpecificationException(
          "Only fully specified SDR video output without HDR enhancements is supported");
    }

    var profile = codecProfile(video.getCodecProfile());
    if (!familyOf(profile).equals(video.getCodecFamily())) {
      throw new InvalidJobSpecificationException(
          "Codec profile " + profile + " does not belong to " + video.getCodecFamily());
    }

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
        .sourceDynamicRange(VideoDynamicRanges.of(video.getSourceDynamicRange()))
        .outputDynamicRange(outputDynamicRange)
        .sourcePacketsCarryPresentationTimes(video.getSourcePacketsCarryPresentationTimes())
        .build();
  }

  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static VideoCodecProfile codecProfile(
      build.buf.gen.streamarr.transcode.v1.VideoCodecProfile profile) {
    return switch (profile) {
      case VIDEO_CODEC_PROFILE_H264_HIGH -> VideoCodecProfile.H264_HIGH;
      case VIDEO_CODEC_PROFILE_HEVC_MAIN -> VideoCodecProfile.HEVC_MAIN;
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
          VIDEO_CODEC_PROFILE_HEVC_MAIN_10,
          VIDEO_CODEC_PROFILE_HEVC_MAIN_STILL_PICTURE,
          VIDEO_CODEC_PROFILE_HEVC_FORMAT_RANGE_EXTENSIONS,
          VIDEO_CODEC_PROFILE_AV1_HIGH,
          VIDEO_CODEC_PROFILE_AV1_PROFESSIONAL ->
          throw new InvalidJobSpecificationException(
              "An encode forces only a Main or High profile, and carries " + profile);
    };
  }

  private static VideoCodecFamily familyOf(VideoCodecProfile profile) {
    return switch (profile) {
      case H264_HIGH -> VideoCodecFamily.VIDEO_CODEC_FAMILY_H264;
      case HEVC_MAIN -> VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC;
      case AV1_MAIN -> VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1;
    };
  }

  // H.264 has no tier; HEVC and AV1 write one into every codec string.
  private static boolean hasTiers(VideoCodecProfile profile) {
    return switch (profile) {
      case H264_HIGH -> false;
      case HEVC_MAIN, AV1_MAIN -> true;
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
