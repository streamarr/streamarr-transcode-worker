package com.streamarr.transcode.engine;

/**
 * Reads whether a video sample entry declares interlaced video. Three signals exist:
 *
 * <ul>
 *   <li>a QuickTime {@code fiel} box that counts two fields, which FFmpeg's mp4 muxer writes when
 *       the encoder received frames flagged as interlaced, as libx265 and libsvtav1 do;
 *   <li>an {@code avcC} whose first SPS sets {@code frame_mbs_only_flag} to 0 (ITU-T H.264
 *       7.4.2.1.1), so the stream may code fields;
 *   <li>an {@code hvcC} that sets {@code general_interlaced_source_flag} (ITU-T H.265 7.4.4).
 * </ul>
 *
 * <p>libx264 codes every frame of the worker's command as a frame, so only its SPS could show
 * interlaced coding. An SPS that ends before {@code frame_mbs_only_flag} declares nothing.
 */
final class InterlacedVideoSignals {

  // The hvcC's general_constraint_indicator_flags start at byte 6, and their second bit is
  // general_interlaced_source_flag (ISO/IEC 14496-15 8.3.2.1.2).
  private static final int HVCC_CONSTRAINT_FLAGS_OFFSET = 6;
  private static final int HVCC_INTERLACED_SOURCE_BIT = 0x40;

  private InterlacedVideoSignals() {}

  /**
   * @throws FragmentedMp4Exception when a decoder configuration record ends before the fields that
   *     this check reads
   */
  static boolean declaresInterlacedVideo(BoxView sampleEntry) {
    var children = sampleEntry.after(CodecConfigurationReader.VISUAL_SAMPLE_ENTRY_BYTES);
    if (children.children("fiel").stream().anyMatch(fiel -> fiel.fields().u8() == 2)) {
      return true;
    }

    return switch (sampleEntry.type()) {
      case "avc1", "avc3" -> codesFields(children.requiredChild("avcC").fields());
      case "hvc1", "hev1" -> declaresInterlacedSource(children.requiredChild("hvcC").fields());
      default -> false;
    };
  }

  private static boolean declaresInterlacedSource(BoxFields hvcC) {
    hvcC.skip(HVCC_CONSTRAINT_FLAGS_OFFSET);
    return (hvcC.u8() & HVCC_INTERLACED_SOURCE_BIT) != 0;
  }

  private static boolean codesFields(BoxFields avcC) {
    avcC.skip(5);
    if ((avcC.u8() & 0x1F) == 0) {
      return false;
    }

    var nalUnit = new byte[avcC.u16()];
    for (var index = 0; index < nalUnit.length; index++) {
      nalUnit[index] = (byte) avcC.u8();
    }

    return AvcSequenceParameterSet.codesFields(nalUnit);
  }
}
