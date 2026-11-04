package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

/**
 * The fields of a Dolby Vision configuration record box (Dolby's ISO base media file format
 * specification, 3.2): {@code dvcC} for profiles up to 7, {@code dvvC} for profiles 8 to 10 and
 * {@code dvwC} above. The box body is 24 bytes: the version, then the profile (7 bits), the level
 * (6 bits), the RPU, enhancement-layer and base-layer flags, bl_signal_compatibility_id (4 bits)
 * and dv_md_compression (2 bits), then reserved zero bits.
 *
 * @param boxType the record's box type
 */
@Builder
record DolbyVisionRecord(
    @NonNull String boxType,
    int versionMajor,
    int versionMinor,
    int profile,
    int level,
    boolean rpuPresent,
    boolean enhancementLayerPresent,
    boolean baseLayerPresent,
    int compatibilityId,
    int compression) {

  /** The box types of a Dolby Vision configuration record. */
  static final Set<String> BOX_TYPES = Set.of("dvcC", "dvvC", "dvwC");

  private static final int BODY_BYTES = 24;
  private static final int BOX_HEADER_BYTES = 8;
  private static final int LAST_DVCC_PROFILE = 7;
  private static final int LAST_DVVC_PROFILE = 10;

  /**
   * The record that a normalized copy writes for its planned Dolby Vision output: version 1.0, an
   * RPU and a base layer, no enhancement layer, and metadata compression 0 (ADR 0036).
   */
  static DolbyVisionRecord normalizedFor(PlannedCopyOutput.DolbyVisionOutput output) {
    return DolbyVisionRecord.builder()
        .boxType(boxTypeOf(output.profile()))
        .versionMajor(1)
        .versionMinor(0)
        .profile(output.profile())
        .level(output.level())
        .rpuPresent(true)
        .baseLayerPresent(true)
        .compatibilityId(output.baseLayerCompatibilityId())
        .compression(0)
        .build();
  }

  /** The box type that Dolby's specification gives a record of the profile. */
  static String boxTypeOf(int profile) {
    if (profile <= LAST_DVCC_PROFILE) {
      return "dvcC";
    }

    if (profile <= LAST_DVVC_PROFILE) {
      return "dvvC";
    }

    return "dvwC";
  }

  /**
   * The records among a sample entry's child boxes, in order.
   *
   * @throws FragmentedMp4Exception when a record's box ends before its compression does
   */
  static List<DolbyVisionRecord> recordsAmong(List<BoxView> children) {
    return children.stream()
        .filter(child -> BOX_TYPES.contains(child.type()))
        .map(DolbyVisionRecord::read)
        .toList();
  }

  private static DolbyVisionRecord read(BoxView box) {
    var fields = box.fields();
    var versionMajor = fields.u8();
    var versionMinor = fields.u8();
    var profileAndLevel = fields.u8();
    var levelAndFlags = fields.u8();
    var compatibilityAndCompression = fields.u8();
    return DolbyVisionRecord.builder()
        .boxType(box.type())
        .versionMajor(versionMajor)
        .versionMinor(versionMinor)
        .profile(profileAndLevel >> 1)
        .level(((profileAndLevel & 0x01) << 5) | (levelAndFlags >> 3))
        .rpuPresent((levelAndFlags & 0x04) != 0)
        .enhancementLayerPresent((levelAndFlags & 0x02) != 0)
        .baseLayerPresent((levelAndFlags & 0x01) != 0)
        .compatibilityId(compatibilityAndCompression >> 4)
        .compression((compatibilityAndCompression >> 2) & 0x03)
        .build();
  }

  /** The record as a box: its header and its 24-byte body. */
  byte[] box() {
    var levelAndFlags =
        ((level & 0x1F) << 3)
            | flag(rpuPresent, 0x04)
            | flag(enhancementLayerPresent, 0x02)
            | flag(baseLayerPresent, 0x01);
    return ByteBuffer.allocate(BOX_HEADER_BYTES + BODY_BYTES)
        .putInt(BOX_HEADER_BYTES + BODY_BYTES)
        .put(boxType.getBytes(StandardCharsets.US_ASCII))
        .put((byte) versionMajor)
        .put((byte) versionMinor)
        .put((byte) (((profile & 0x7F) << 1) | ((level >> 5) & 0x01)))
        .put((byte) levelAndFlags)
        .put((byte) (((compatibilityId & 0x0F) << 4) | ((compression & 0x03) << 2)))
        .array();
  }

  private static int flag(boolean set, int bit) {
    if (set) {
      return bit;
    }

    return 0;
  }
}
