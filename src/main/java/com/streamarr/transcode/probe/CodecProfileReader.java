package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.VideoCodecProfile;
import build.buf.gen.streamarr.transcode.v1.VideoCodecTier;
import com.streamarr.transcode.probe.BitReader.TruncatedBitstreamException;
import tools.jackson.databind.JsonNode;

/**
 * A video stream's codec profile, level and tier, read from the same decoder configuration fields
 * that its codec string comes from. A profile that the contract does not name leaves all three
 * unspecified.
 */
final class CodecProfileReader {

  private static final int AVC_BASELINE = 66;
  private static final int AVC_MAIN = 77;
  private static final int AVC_EXTENDED = 88;
  private static final int AVC_CONSTRAINT_SET1 = 0x40;
  private static final int AVC_CONSTRAINT_SET3 = 0x10;
  // Level 1b: level_idc 9, or level_idc 11 with constraint_set3_flag in the three profiles that
  // define it that way.
  private static final int AVC_LEVEL_1B = 9;
  private static final int AVC_LEVEL_11 = 11;
  private static final int AV1_LEVEL_UNCONSTRAINED = 31;

  private CodecProfileReader() {}

  static void add(JsonNode source, ProbeStreamInfo.Builder stream) {
    var codec = text(source, "codec_name").orElse("");
    try {
      switch (codec) {
        case "h264" ->
            Extradata.of(source)
                .flatMap(DecoderConfiguration::avcProfile)
                .ifPresent(bytes -> addAvc(bytes, stream));
        case "hevc" ->
            Extradata.of(source)
                .flatMap(DecoderConfiguration::hevcProfile)
                .ifPresent(bytes -> addHevc(bytes, stream));
        case "av1" ->
            Extradata.of(source)
                .flatMap(DecoderConfiguration::av1Profile)
                .ifPresent(header -> addAv1(header, stream));
        default -> {
          // The contract names profiles of H.264, HEVC and AV1 only.
        }
      }
    } catch (TruncatedBitstreamException _) {
      // A sequence header that ends early names no profile.
    }
  }

  private static void addAvc(byte[] profileConstraintsLevel, ProbeStreamInfo.Builder stream) {
    var profileIdc = Byte.toUnsignedInt(profileConstraintsLevel[0]);
    var constraints = Byte.toUnsignedInt(profileConstraintsLevel[1]);
    var levelIdc = Byte.toUnsignedInt(profileConstraintsLevel[2]);
    var profile = avcProfile(profileIdc, constraints);
    if (profile == VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED) {
      return;
    }

    stream.setCodecProfile(profile);
    if (!isLevel1b(profileIdc, constraints, levelIdc)) {
      stream.setCodecLevel(levelIdc);
    }
  }

  private static VideoCodecProfile avcProfile(int profileIdc, int constraints) {
    return switch (profileIdc) {
      case AVC_BASELINE ->
          (constraints & AVC_CONSTRAINT_SET1) != 0
              ? VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_CONSTRAINED_BASELINE
              : VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_BASELINE;
      case AVC_MAIN -> VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_MAIN;
      case AVC_EXTENDED -> VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_EXTENDED;
      case 100 -> VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_HIGH;
      case 110 -> VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_HIGH_10;
      case 122 -> VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_HIGH_422;
      case 244 -> VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_HIGH_444_PREDICTIVE;
      default -> VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED;
    };
  }

  private static boolean isLevel1b(int profileIdc, int constraints, int levelIdc) {
    if (levelIdc == AVC_LEVEL_1B) {
      return true;
    }

    var definesLevel1bByFlag =
        profileIdc == AVC_BASELINE || profileIdc == AVC_MAIN || profileIdc == AVC_EXTENDED;
    return definesLevel1bByFlag
        && levelIdc == AVC_LEVEL_11
        && (constraints & AVC_CONSTRAINT_SET3) != 0;
  }

  /** hvcC bytes 1 to 12: the profile space, tier and profile_idc first, and level_idc last. */
  private static void addHevc(byte[] generalProfile, ProbeStreamInfo.Builder stream) {
    var spaceTierAndProfile = Byte.toUnsignedInt(generalProfile[0]);
    var profile =
        switch (spaceTierAndProfile & 0x1F) {
          case 1 -> VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN;
          case 2 -> VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN_10;
          case 3 -> VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN_STILL_PICTURE;
          case 4 -> VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_FORMAT_RANGE_EXTENSIONS;
          default -> VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED;
        };
    if (profile == VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED) {
      return;
    }

    stream
        .setCodecProfile(profile)
        .setCodecTier(tier((spaceTierAndProfile & 0x20) != 0))
        .setCodecLevel(Byte.toUnsignedInt(generalProfile[generalProfile.length - 1]) / 3);
  }

  /** av1C bytes 1 and 2: seq_profile and seq_level_idx[0], then seq_tier[0] first. */
  private static void addAv1(Av1SequenceHeader header, ProbeStreamInfo.Builder stream) {
    var profile =
        switch (header.profileAndLevel() >> 5) {
          case 0 -> VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN;
          case 1 -> VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_HIGH;
          case 2 -> VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_PROFESSIONAL;
          default -> VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED;
        };
    if (profile == VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED) {
      return;
    }

    stream.setCodecProfile(profile).setCodecTier(tier((header.flags() & 0x80) != 0));
    var levelIndex = header.profileAndLevel() & 0x1F;
    if (levelIndex != AV1_LEVEL_UNCONSTRAINED) {
      stream.setCodecLevel((2 + levelIndex / 4) * 10 + levelIndex % 4);
    }
  }

  private static VideoCodecTier tier(boolean high) {
    return high ? VideoCodecTier.VIDEO_CODEC_TIER_HIGH : VideoCodecTier.VIDEO_CODEC_TIER_MAIN;
  }
}
