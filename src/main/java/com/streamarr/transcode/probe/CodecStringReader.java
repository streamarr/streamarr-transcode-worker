package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import com.streamarr.transcode.probe.BitReader.TruncatedBitstreamException;
import com.streamarr.transcode.protocol.CodecStrings;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The RFC 6381 codec string that a stream copy carries, computed from the stream's decoder
 * configuration record or its parameter sets. The tag rule writes avc1 for every H.264 copy and
 * hvc1 for every HEVC copy, so a Dolby Vision copy names its base layer's sample entry. MPEG-TS
 * carries H.264 and HEVC parameter sets in Annex B form, and IVF and raw OBU files carry AV1's
 * sequence header OBU.
 */
final class CodecStringReader {

  private static final int AVC_RECORD_VERSION = 1;
  private static final int AVC_SEQUENCE_PARAMETER_SET = 7;
  // The avcC record's count of SPS NAL units follows its 5 header bytes.
  private static final int AVC_RECORD_SPS_COUNT = 5;
  // The hvcC record's count of NAL unit arrays follows its 22 header bytes.
  private static final int HVCC_ARRAY_COUNT = 22;
  private static final int HEVC_RECORD_VERSION = 1;
  private static final int HEVC_VIDEO_PARAMETER_SET = 32;
  private static final int HEVC_SEQUENCE_PARAMETER_SET = 33;
  // The general profile, tier and level start 4 bytes into a VPS's RBSP and 1 byte into an SPS's.
  private static final int VPS_PROFILE_OFFSET = 4;
  private static final int SPS_PROFILE_OFFSET = 1;
  private static final int AV1_RECORD_MARKER = 0x81;
  private static final int ESCAPED_AUDIO_OBJECT_TYPE = 31;
  private static final int AAC_MAIN = 1;
  private static final int AAC_LC = 2;
  private static final int AAC_SSR = 3;
  private static final int AAC_LTP = 4;

  private CodecStringReader() {}

  static Optional<String> read(JsonNode stream) {
    var codec = text(stream, "codec_name").orElse("");
    try {
      return switch (codec) {
        case "h264" -> Extradata.of(stream).flatMap(CodecStringReader::avc);
        case "hevc" -> Extradata.of(stream).flatMap(CodecStringReader::hevc);
        case "av1" -> Extradata.of(stream).flatMap(CodecStringReader::av1);
        case "aac" -> aac(stream, Extradata.of(stream));
        case "ac3" -> Optional.of("ac-3");
        case "eac3" -> Optional.of("ec-3");
        case "opus" -> Optional.of("Opus");
        case "flac" -> Optional.of("fLaC");
        case "alac" -> Optional.of("alac");
        default -> Optional.empty();
      };
    } catch (TruncatedBitstreamException _) {
      return Optional.empty();
    }
  }

  /**
   * The three bytes after the first SPS's NAL unit header. The copy's Annex B filter turns an avcC
   * record into its parameter sets, and the mp4 muxer builds the copy's avcC from the first SPS, so
   * the copy keeps the SPS's profile and level rather than the record header's. A record without an
   * SPS leaves the muxer to read the parameter sets in the first sample, which the record header
   * describes.
   */
  private static Optional<String> avc(byte[] extradata) {
    var isRecord = Byte.toUnsignedInt(extradata[0]) == AVC_RECORD_VERSION;
    var units = isRecord ? avcRecordSequenceParameterSets(extradata) : annexB(extradata);
    var profile =
        units.stream()
            .filter(nal -> (nal[0] & 0x1F) == AVC_SEQUENCE_PARAMETER_SET)
            .findFirst()
            .map(nal -> Arrays.copyOfRange(nal, 1, nal.length));
    if (profile.isEmpty() && isRecord) {
      profile = Optional.of(Arrays.copyOfRange(extradata, 1, extradata.length));
    }

    return profile
        .filter(bytes -> bytes.length >= CodecStrings.AVC_PROFILE_BYTES)
        .map(bytes -> CodecStrings.avc("avc1", bytes));
  }

  /**
   * The VPS and SPS merged as FFmpeg's hvcC writer merges them. The copy's Annex B filter turns an
   * hvcC record into its parameter sets, so the copy's record takes its profile, tier and level
   * from them rather than from the source record's header. A record without them leaves the muxer
   * to read the parameter sets in the first sample, which the record header describes.
   */
  private static Optional<String> hevc(byte[] extradata) {
    var isRecord = Byte.toUnsignedInt(extradata[0]) == HEVC_RECORD_VERSION;
    var merged = new HevcProfileMerge();
    for (var nal : isRecord ? hevcRecordUnits(extradata) : annexB(extradata)) {
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

    var profile = merged.generalProfile();
    if (profile.isEmpty() && isRecord) {
      profile =
          Optional.of(Arrays.copyOfRange(extradata, 1, extradata.length))
              .filter(bytes -> bytes.length >= CodecStrings.HEVC_PROFILE_BYTES);
    }

    return profile.map(bytes -> CodecStrings.hevc("hvc1", bytes));
  }

  /** The SPS NAL units of an avcC record (ISO/IEC 14496-15, 5.3.2.1.2). */
  private static List<byte[]> avcRecordSequenceParameterSets(byte[] record) {
    var units = new ArrayList<byte[]>();
    if (record.length <= AVC_RECORD_SPS_COUNT) {
      return units;
    }

    var buffer = ByteBuffer.wrap(record).position(AVC_RECORD_SPS_COUNT + 1);
    var count = record[AVC_RECORD_SPS_COUNT] & 0x1F;
    for (var index = 0; index < count; index++) {
      if (!readLengthPrefixedUnit(buffer, units)) {
        break;
      }
    }

    return units;
  }

  /** The NAL units of every array in an hvcC record (ISO/IEC 14496-15, 8.3.3.1.2). */
  private static List<byte[]> hevcRecordUnits(byte[] record) {
    var units = new ArrayList<byte[]>();
    if (record.length <= HVCC_ARRAY_COUNT) {
      return units;
    }

    var buffer = ByteBuffer.wrap(record).position(HVCC_ARRAY_COUNT + 1);
    var arrays = Byte.toUnsignedInt(record[HVCC_ARRAY_COUNT]);
    for (var array = 0; array < arrays && buffer.remaining() >= 3; array++) {
      buffer.get();
      var count = Short.toUnsignedInt(buffer.getShort());
      for (var index = 0; index < count; index++) {
        if (!readLengthPrefixedUnit(buffer, units)) {
          return units;
        }
      }
    }

    return units;
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

  /** av1C bytes 1 and 2, or the same fields read from the sequence header OBU. */
  private static Optional<String> av1(byte[] extradata) {
    if (Byte.toUnsignedInt(extradata[0]) == AV1_RECORD_MARKER) {
      return Optional.of(extradata)
          .filter(record -> record.length >= 3)
          .map(
              record ->
                  CodecStrings.av1(Byte.toUnsignedInt(record[1]), Byte.toUnsignedInt(record[2])));
    }

    return Av1SequenceHeader.find(extradata)
        .map(header -> CodecStrings.av1(header.profileAndLevel(), header.flags()));
  }

  /**
   * mp4a.40 and the AudioSpecificConfig's audioObjectType. An ADTS stream has no config, and
   * FFmpeg's aac_adtstoasc builds the copy's config from the ADTS profile. ffprobe names that
   * profile, or HE-AAC when the decoder finds SBR in an AAC LC stream.
   */
  private static Optional<String> aac(JsonNode stream, Optional<byte[]> extradata) {
    if (extradata.isEmpty()) {
      return adtsObjectType(text(stream, "profile").orElse("")).map(type -> "mp4a.40." + type);
    }

    var config = extradata.get();
    var objectType = Byte.toUnsignedInt(config[0]) >> 3;
    if (objectType == ESCAPED_AUDIO_OBJECT_TYPE && config.length > 1) {
      objectType = 32 + (((config[0] & 0x07) << 3) | (Byte.toUnsignedInt(config[1]) >> 5));
    }

    return Optional.of("mp4a.40." + objectType);
  }

  private static Optional<Integer> adtsObjectType(String profile) {
    return switch (profile) {
      case "Main" -> Optional.of(AAC_MAIN);
      case "LC", "HE-AAC", "HE-AACv2" -> Optional.of(AAC_LC);
      case "SSR" -> Optional.of(AAC_SSR);
      case "LTP" -> Optional.of(AAC_LTP);
      default -> Optional.empty();
    };
  }

  /** The NAL units of an Annex B byte stream, each without its start code or emulation bytes. */
  private static List<byte[]> annexB(byte[] stream) {
    var units = new ArrayList<byte[]>();
    var start = -1;
    for (var index = 0; index + 2 < stream.length; index++) {
      if (stream[index] == 0 && stream[index + 1] == 0 && stream[index + 2] == 1) {
        addUnit(units, stream, start, index);
        start = index + 3;
        index += 2;
      }
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

  private static byte[] withoutEmulationPrevention(byte[] stream, int start, int end) {
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
