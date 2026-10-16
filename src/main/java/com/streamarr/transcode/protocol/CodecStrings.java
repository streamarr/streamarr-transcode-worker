package com.streamarr.transcode.protocol;

import java.util.Locale;
import lombok.NonNull;

/**
 * RFC 6381 codec strings, built from the fields of a decoder configuration record. Hexadecimal
 * digits are uppercase, and every digit is ASCII whatever the default locale.
 */
public final class CodecStrings {

  /** The bytes that hold an AVC profile_idc, its constraint flags and its level_idc. */
  public static final int AVC_PROFILE_BYTES = 3;

  /**
   * The bytes that hold HEVC's general profile, tier and level in an hvcC record: the profile
   * space, tier flag and profile_idc, four bytes of compatibility flags, six constraint bytes and
   * level_idc.
   */
  public static final int HEVC_PROFILE_BYTES = 12;

  private static final int HEVC_CONSTRAINT_BYTES = 6;

  private CodecStrings() {}

  /**
   * {@code avc1.PPCCLL} for the sample entry, from profile_idc, the constraint flags and level_idc:
   * avcC bytes 1 to 3, or the three bytes after an SPS's NAL unit header.
   */
  public static String avc(@NonNull String sampleEntry, byte @NonNull [] profileConstraintsLevel) {
    return String.format(
        Locale.ROOT,
        "%s.%02X%02X%02X",
        sampleEntry,
        Byte.toUnsignedInt(profileConstraintsLevel[0]),
        Byte.toUnsignedInt(profileConstraintsLevel[1]),
        Byte.toUnsignedInt(profileConstraintsLevel[2]));
  }

  /**
   * The HEVC string for the sample entry, from hvcC bytes 1 to 12: the profile space and
   * profile_idc, the compatibility flags in reverse bit order, the tier and level, and each
   * constraint byte up to the last nonzero one (ISO/IEC 14496-15 E.3).
   */
  public static String hevc(@NonNull String sampleEntry, byte @NonNull [] generalProfile) {
    var spaceTierAndProfile = Byte.toUnsignedInt(generalProfile[0]);
    var compatibility = 0;
    for (var index = 1; index <= 4; index++) {
      compatibility = (compatibility << 8) | Byte.toUnsignedInt(generalProfile[index]);
    }

    return String.format(
        Locale.ROOT,
        "%s.%s%d.%X.%s%d%s",
        sampleEntry,
        profileSpace(spaceTierAndProfile >> 6),
        spaceTierAndProfile & 0x1F,
        Integer.reverse(compatibility),
        tierLetter((spaceTierAndProfile & 0x20) != 0, "L"),
        Byte.toUnsignedInt(generalProfile[HEVC_PROFILE_BYTES - 1]),
        constraintSuffix(generalProfile));
  }

  /**
   * {@code av01.P.LLT.DD} from av1C byte 1, which holds seq_profile and seq_level_idx_0, and av1C
   * byte 2, which holds seq_tier_0, high_bitdepth and twelve_bit.
   */
  public static String av1(int profileAndLevel, int flags) {
    return String.format(
        Locale.ROOT,
        "av01.%d.%02d%s.%02d",
        profileAndLevel >> 5,
        profileAndLevel & 0x1F,
        tierLetter((flags & 0x80) != 0, "M"),
        av1BitDepth(profileAndLevel, flags));
  }

  /** The bit depth that av1C bytes 1 and 2 state: 8, 10, or 12 for profile 2 only. */
  public static int av1BitDepth(int profileAndLevel, int flags) {
    var highBitDepth = (flags & 0x40) != 0;
    if (!highBitDepth) {
      return 8;
    }

    var twelveBit = (flags & 0x20) != 0;
    if (profileAndLevel >> 5 == 2 && twelveBit) {
      return 12;
    }

    return 10;
  }

  private static String profileSpace(int space) {
    return switch (space) {
      case 1 -> "A";
      case 2 -> "B";
      case 3 -> "C";
      default -> "";
    };
  }

  // Each constraint byte as hexadecimal, without the trailing zero bytes.
  private static String constraintSuffix(byte[] generalProfile) {
    var first = 5;
    var last = first + HEVC_CONSTRAINT_BYTES;
    while (last > first && generalProfile[last - 1] == 0) {
      last--;
    }

    var suffix = new StringBuilder();
    for (var index = first; index < last; index++) {
      suffix.append(String.format(Locale.ROOT, ".%X", Byte.toUnsignedInt(generalProfile[index])));
    }

    return suffix.toString();
  }

  // HEVC writes its Main tier as L, and AV1 as M; both write the High tier as H.
  private static String tierLetter(boolean highTier, String mainTierLetter) {
    if (highTier) {
      return "H";
    }

    return mainTierLetter;
  }
}
