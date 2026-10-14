package com.streamarr.transcode.worker;

import build.buf.gen.streamarr.transcode.v1.VideoCodecFamily;
import build.buf.gen.streamarr.transcode.v1.VideoMode;
import com.streamarr.transcode.engine.TranscodeMode;
import com.streamarr.transcode.engine.VideoCodecProfile;
import com.streamarr.transcode.engine.VideoCodecTier;
import com.streamarr.transcode.engine.VideoDecision;
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
    if (!encodes(video.getMode()).equals(Optional.of(mode.encodesVideo()))) {
      throw new InvalidJobSpecificationException(
          "Video mode " + video.getMode() + " contradicts transcode mode " + mode);
    }

    if (video.getCodecString().isEmpty()) {
      throw new InvalidJobSpecificationException("The video decision carries no codec string");
    }

    if (!mode.encodesVideo()) {
      return new VideoDecision.Copy(video.getCodecString());
    }

    return encode(video);
  }

  /** The engine's name for the codec family; empty when the decision names none. */
  static Optional<String> codecFamilyName(VideoCodecFamily family) {
    return switch (family) {
      case VIDEO_CODEC_FAMILY_H264 -> Optional.of("h264");
      case VIDEO_CODEC_FAMILY_HEVC -> Optional.of("hevc");
      case VIDEO_CODEC_FAMILY_AV1 -> Optional.of("av1");
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
  private static VideoDecision.Encode encode(
      build.buf.gen.streamarr.transcode.v1.VideoDecision video) {
    if (codecFamilyName(video.getCodecFamily()).isEmpty()) {
      throw new InvalidJobSpecificationException("The encode carries no codec family");
    }

    if (!video.hasLevel()) {
      throw new InvalidJobSpecificationException("The encode carries no level");
    }

    var profile = codecProfile(video.getCodecProfile());
    if (!familyOf(profile).equals(video.getCodecFamily())) {
      throw new InvalidJobSpecificationException(
          "Codec profile " + profile + " does not belong to " + video.getCodecFamily());
    }

    return VideoDecision.Encode.builder()
        .codecProfile(profile)
        .tier(tier(video.getTier()))
        .level(video.getLevel())
        .codecString(video.getCodecString())
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
    };
  }

  private static VideoCodecFamily familyOf(VideoCodecProfile profile) {
    return switch (profile) {
      case H264_HIGH -> VideoCodecFamily.VIDEO_CODEC_FAMILY_H264;
      case HEVC_MAIN -> VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC;
      case AV1_MAIN -> VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1;
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
