package com.streamarr.transcode.probe;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The profile, tier and level fields of a video stream's extradata: its decoder configuration
 * record or its parameter sets. MPEG-TS carries H.264 and HEVC parameter sets in Annex B form, and
 * IVF and raw OBU files carry AV1's sequence header OBU.
 */
final class DecoderConfiguration {

  private static final int AVC_RECORD_VERSION = 1;
  private static final int AVC_SEQUENCE_PARAMETER_SET = 7;
  // The avcC record's count of SPS NAL units follows its 5 header bytes.
  private static final int AVC_RECORD_SPS_COUNT = 5;
  private static final int AVC_RECORD_MINIMUM_BYTES = 7;
  // The hvcC record's count of NAL unit arrays follows its 22 header bytes.
  private static final int HVCC_ARRAY_COUNT = 22;
  private static final int HEVC_RECORD_VERSION = 1;
  private static final int HEVC_VIDEO_PARAMETER_SET = 32;
  private static final int HEVC_SEQUENCE_PARAMETER_SET = 33;
  // The general profile, tier and level start 4 bytes into a VPS's RBSP and 1 byte into an SPS's.
  private static final int VPS_PROFILE_OFFSET = 4;
  private static final int SPS_PROFILE_OFFSET = 1;
  private static final int AV1_RECORD_MARKER = 0x81;

  /** The bytes of an av1C record ahead of its configOBUs. */
  static final int AV1_RECORD_HEADER_BYTES = 4;

  private DecoderConfiguration() {}

  /**
   * The three bytes after the first SPS's NAL unit header. The copy's Annex B filter turns an avcC
   * record into its parameter sets, and the mp4 muxer builds the copy's avcC from the first SPS, so
   * the copy keeps the SPS's profile and level rather than the record header's. A record without an
   * SPS leaves the muxer to read the parameter sets in the first sample, so the record header alone
   * cannot determine the copy's profile.
   */
  static Optional<byte[]> avcProfile(byte[] extradata) {
    var isRecord = Byte.toUnsignedInt(extradata[0]) == AVC_RECORD_VERSION;
    var units = isRecord ? avcRecordSequenceParameterSets(extradata) : annexB(extradata);
    return units.stream()
        .filter(nal -> (nal[0] & 0x1F) == AVC_SEQUENCE_PARAMETER_SET)
        .findFirst()
        .map(nal -> Arrays.copyOfRange(nal, 1, nal.length))
        .filter(bytes -> bytes.length >= CodecStrings.AVC_PROFILE_BYTES);
  }

  /**
   * The VPS and SPS merged as FFmpeg's hvcC writer merges them, as hvcC bytes 1 to 12. The copy's
   * Annex B filter turns an hvcC record into its parameter sets, so the copy's record takes its
   * profile, tier and level from them rather than from the source record's header. A record without
   * them leaves the muxer to read the parameter sets in the first sample, so the record header
   * alone cannot determine the copy's profile.
   */
  static Optional<byte[]> hevcProfile(byte[] extradata) {
    var isRecord = Byte.toUnsignedInt(extradata[0]) == HEVC_RECORD_VERSION;
    var merged = new HevcProfileMerge();
    for (var nal : isRecord ? hevcRecordUnits(extradata).units() : annexB(extradata)) {
      if (nal.length < 2 || (nal[1] >> 3 & 0x1F) != 0 || (nal[0] & 0x01) != 0) {
        continue;
      }

      var type = (nal[0] >> 1) & 0x3F;
      var rbsp = Arrays.copyOfRange(nal, 2, nal.length);
      if (type == HEVC_VIDEO_PARAMETER_SET) {
        merged.add(rbsp, VPS_PROFILE_OFFSET);
      }

      if (type == HEVC_SEQUENCE_PARAMETER_SET) {
        merged.add(rbsp, SPS_PROFILE_OFFSET);
      }
    }

    return merged.generalProfile();
  }

  /**
   * Whether the extradata is an av1C record: its marker and version byte, then 3 bytes of fields,
   * then its configOBUs.
   */
  static boolean isAv1Record(byte[] extradata) {
    return extradata.length >= AV1_RECORD_HEADER_BYTES
        && Byte.toUnsignedInt(extradata[0]) == AV1_RECORD_MARKER;
  }

  /**
   * Whether FFmpeg reads the avcC record whole, and so takes its NAL unit length size: the record
   * holds at least {@value #AVC_RECORD_MINIMUM_BYTES} bytes and each SPS and PPS ends inside it.
   * FFmpeg reads a count past the record's end as 0.
   */
  static boolean isWholeAvcRecord(byte[] avcC) {
    if (avcC.length < AVC_RECORD_MINIMUM_BYTES) {
      return false;
    }

    var buffer = ByteBuffer.wrap(avcC).position(AVC_RECORD_SPS_COUNT);
    var sequenceParameterSets = buffer.get() & 0x1F;
    if (!skipsParameterSets(buffer, sequenceParameterSets)) {
      return false;
    }

    var pictureParameterSets = buffer.hasRemaining() ? Byte.toUnsignedInt(buffer.get()) : 0;
    return skipsParameterSets(buffer, pictureParameterSets);
  }

  /** Skips {@code count} parameter sets, each after its 2-byte length, if they fit. */
  private static boolean skipsParameterSets(ByteBuffer buffer, int count) {
    for (var index = 0; index < count; index++) {
      if (buffer.remaining() < 2) {
        return false;
      }

      var length = Short.toUnsignedInt(buffer.getShort());
      if (length > buffer.remaining()) {
        return false;
      }

      buffer.position(buffer.position() + length);
    }

    return true;
  }

  /**
   * Whether the extradata holds bare OBUs, as FFmpeg gives an AV1 stream in IVF or in a raw .obu
   * file: its first byte, an OBU header, has its forbidden bit clear.
   */
  static boolean isAv1Obus(byte[] extradata) {
    return (extradata[0] & 0x80) == 0;
  }

  /** av1C bytes 1 and 2, or the same fields read from the sequence header OBU. */
  static Optional<Av1SequenceHeader> av1Profile(byte[] extradata) {
    if (Byte.toUnsignedInt(extradata[0]) == AV1_RECORD_MARKER) {
      return Optional.of(extradata)
          .filter(av1C -> av1C.length >= 3)
          .map(
              av1C ->
                  new Av1SequenceHeader(Byte.toUnsignedInt(av1C[1]), Byte.toUnsignedInt(av1C[2])));
    }

    return Av1SequenceHeader.find(extradata);
  }

  /** The SPS NAL units of an avcC record (ISO/IEC 14496-15, 5.3.2.1.2). */
  private static List<byte[]> avcRecordSequenceParameterSets(byte[] avcC) {
    var units = new ArrayList<byte[]>();
    if (avcC.length <= AVC_RECORD_SPS_COUNT) {
      return units;
    }

    var buffer = ByteBuffer.wrap(avcC).position(AVC_RECORD_SPS_COUNT + 1);
    var count = avcC[AVC_RECORD_SPS_COUNT] & 0x1F;
    for (var index = 0; index < count; index++) {
      if (!readLengthPrefixedUnit(buffer, units)) {
        break;
      }
    }

    return units;
  }

  /**
   * The NAL units of every array in an hvcC record (ISO/IEC 14496-15, 8.3.3.1.2), complete when
   * every array and NAL unit that the record counts ends within it.
   */
  static NalUnits hevcRecordUnits(byte[] hvcC) {
    var units = new ArrayList<byte[]>();
    if (hvcC.length <= HVCC_ARRAY_COUNT) {
      return new NalUnits(units, false);
    }

    var buffer = ByteBuffer.wrap(hvcC).position(HVCC_ARRAY_COUNT + 1);
    var arrays = Byte.toUnsignedInt(hvcC[HVCC_ARRAY_COUNT]);
    var isComplete = true;
    for (var array = 0; array < arrays; array++) {
      if (buffer.remaining() < 3) {
        return new NalUnits(units, false);
      }

      buffer.get();
      var count = Short.toUnsignedInt(buffer.getShort());
      for (var index = 0; index < count; index++) {
        if (buffer.remaining() < 2) {
          return new NalUnits(units, false);
        }

        var length = Short.toUnsignedInt(buffer.getShort());
        if (length == 0 || buffer.remaining() < length) {
          return new NalUnits(units, false);
        }

        var region = lengthPrefixedRegion(hvcC, buffer.position(), buffer.position() + length);
        units.addAll(region.units());
        isComplete &= region.complete();
        buffer.position(buffer.position() + length);
      }
    }

    return new NalUnits(units, isComplete);
  }

  /**
   * The NAL units of the bytes that one length prefix covers, each without its emulation prevention
   * bytes. FFmpeg ends a NAL unit at a start code that the bytes hold, and reads the bytes after it
   * as Annex B NAL units, so the reader does too. A NAL unit cannot hold a start code, so such
   * bytes are not complete.
   */
  static NalUnits lengthPrefixedRegion(byte[] bytes, int start, int end) {
    var startCode = startCodeOffset(bytes, start, end);
    if (startCode.isEmpty()) {
      return new NalUnits(List.of(withoutEmulationPrevention(bytes, start, end)), true);
    }

    var units = new ArrayList<byte[]>();
    if (startCode.getAsInt() > start) {
      units.add(withoutEmulationPrevention(bytes, start, startCode.getAsInt()));
    }

    units.addAll(annexB(Arrays.copyOfRange(bytes, startCode.getAsInt(), end)));
    return new NalUnits(units, false);
  }

  private static OptionalInt startCodeOffset(byte[] bytes, int start, int end) {
    for (var index = start; index + 2 < end; index++) {
      if (bytes[index] == 0 && bytes[index + 1] == 0 && bytes[index + 2] == 1) {
        return OptionalInt.of(index);
      }
    }

    return OptionalInt.empty();
  }

  /** Reads one NAL unit after its 2-byte length; false when the record ends first. */
  private static boolean readLengthPrefixedUnit(ByteBuffer buffer, List<byte[]> units) {
    if (buffer.remaining() < 2) {
      return false;
    }

    var length = Short.toUnsignedInt(buffer.getShort());
    if (length == 0 || buffer.remaining() < length) {
      return false;
    }

    var start = buffer.position();
    units.add(withoutEmulationPrevention(buffer.array(), start, start + length));
    buffer.position(start + length);
    return true;
  }

  /** The NAL units of an Annex B byte stream, each without its start code or emulation bytes. */
  static List<byte[]> annexB(byte[] stream) {
    var units = new ArrayList<byte[]>();
    var start = -1;
    var index = 0;
    while (index + 2 < stream.length) {
      if (stream[index] == 0 && stream[index + 1] == 0 && stream[index + 2] == 1) {
        addUnit(units, stream, start, index);
        start = index + 3;
        index = start;
        continue;
      }

      index++;
    }

    addUnit(units, stream, start, stream.length);
    return units;
  }

  private static void addUnit(List<byte[]> units, byte[] stream, int start, int end) {
    if (start < 0) {
      return;
    }

    var last = end;
    while (last > start && stream[last - 1] == 0) {
      last--;
    }

    if (last > start) {
      units.add(withoutEmulationPrevention(stream, start, last));
    }
  }

  static byte[] withoutEmulationPrevention(byte[] stream, int start, int end) {
    var output = new ByteArrayOutputStream();
    var zeros = 0;
    for (var index = start; index < end; index++) {
      var value = stream[index];
      if (zeros >= 2 && value == 3) {
        zeros = 0;
        continue;
      }

      output.write(value);
      zeros = value == 0 ? zeros + 1 : 0;
    }

    return output.toByteArray();
  }

  /**
   * The general profile, tier and level that FFmpeg's hvcC writer builds from Annex B parameter
   * sets: the last profile space, the highest tier and profile_idc, the highest level within the
   * highest tier, and the compatibility and constraint flags that every parameter set sets.
   */
  private static final class HevcProfileMerge {

    private int profileSpace;
    private int tier;
    private int profileIdc;
    private long compatibility = 0xFFFF_FFFFL;
    private long constraints = 0xFFFF_FFFF_FFFFL;
    private int level;
    private boolean merged;

    void add(byte[] rbsp, int offset) {
      if (rbsp.length < offset + CodecStrings.HEVC_PROFILE_BYTES) {
        return;
      }

      var reader = new BitReader(Arrays.copyOfRange(rbsp, offset, rbsp.length));
      profileSpace = (int) reader.bits(2);
      var parameterSetTier = (int) reader.bits(1);
      var parameterSetProfile = (int) reader.bits(5);
      var parameterSetCompatibility = reader.bits(32);
      var parameterSetConstraints = (reader.bits(16) << 32) | reader.bits(32);
      var parameterSetLevel = (int) reader.bits(8);
      if (tier < parameterSetTier) {
        level = parameterSetLevel;
      } else {
        level = Math.max(level, parameterSetLevel);
      }

      tier = Math.max(tier, parameterSetTier);
      profileIdc = Math.max(profileIdc, parameterSetProfile);
      compatibility &= parameterSetCompatibility;
      constraints &= parameterSetConstraints;
      merged = true;
    }

    Optional<byte[]> generalProfile() {
      if (!merged) {
        return Optional.empty();
      }

      var bytes = new byte[CodecStrings.HEVC_PROFILE_BYTES];
      bytes[0] = (byte) ((profileSpace << 6) | (tier << 5) | profileIdc);
      for (var index = 0; index < 4; index++) {
        bytes[1 + index] = (byte) (compatibility >> (24 - 8 * index));
      }

      for (var index = 0; index < 6; index++) {
        bytes[5 + index] = (byte) (constraints >> (40 - 8 * index));
      }

      bytes[11] = (byte) level;
      return Optional.of(bytes);
    }
  }
}
