package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import com.streamarr.transcode.probe.BitReader.TruncatedBitstreamException;
import com.streamarr.transcode.protocol.CodecStrings;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The RFC 6381 codec string that a stream copy carries, computed from the stream's decoder
 * configuration record. The tag rule writes avc1 for every H.264 copy and hvc1 for every HEVC copy,
 * so a Dolby Vision copy names its base layer's sample entry. MPEG-TS carries H.264 and HEVC
 * parameter sets in Annex B form, and IVF and raw OBU files carry AV1's sequence header OBU.
 */
final class CodecStringReader {

  private static final int AVC_RECORD_VERSION = 1;
  private static final int AVC_SEQUENCE_PARAMETER_SET = 7;
  private static final int HEVC_RECORD_VERSION = 1;
  private static final int HEVC_VIDEO_PARAMETER_SET = 32;
  private static final int HEVC_SEQUENCE_PARAMETER_SET = 33;
  // The general profile, tier and level start 4 bytes into a VPS's RBSP and 1 byte into an SPS's.
  private static final int VPS_PROFILE_OFFSET = 4;
  private static final int SPS_PROFILE_OFFSET = 1;
  private static final int AV1_RECORD_MARKER = 0x81;
  private static final int ESCAPED_AUDIO_OBJECT_TYPE = 31;
  private static final int AAC_LC = 2;

  private CodecStringReader() {}

  static Optional<String> read(JsonNode stream) {
    var codec = text(stream, "codec_name").orElse("");
    try {
      return switch (codec) {
        case "h264" -> Extradata.of(stream).flatMap(CodecStringReader::avc);
        case "hevc" -> Extradata.of(stream).flatMap(CodecStringReader::hevc);
        case "av1" -> Extradata.of(stream).flatMap(CodecStringReader::av1);
        case "aac" -> Optional.of(aac(Extradata.of(stream)));
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

  /** avcC bytes 1 to 3, or the three bytes after the SPS's NAL unit header. */
  private static Optional<String> avc(byte[] extradata) {
    var profile =
        Byte.toUnsignedInt(extradata[0]) == AVC_RECORD_VERSION
            ? Optional.of(Arrays.copyOfRange(extradata, 1, extradata.length))
            : annexB(extradata).stream()
                .filter(nal -> (nal[0] & 0x1F) == AVC_SEQUENCE_PARAMETER_SET)
                .findFirst()
                .map(nal -> Arrays.copyOfRange(nal, 1, nal.length));
    return profile
        .filter(bytes -> bytes.length >= CodecStrings.AVC_PROFILE_BYTES)
        .map(bytes -> CodecStrings.avc("avc1", bytes));
  }

  /** hvcC bytes 1 to 12, or the VPS and SPS merged as FFmpeg's hvcC writer merges them. */
  private static Optional<String> hevc(byte[] extradata) {
    if (Byte.toUnsignedInt(extradata[0]) == HEVC_RECORD_VERSION) {
      return Optional.of(Arrays.copyOfRange(extradata, 1, extradata.length))
          .filter(bytes -> bytes.length >= CodecStrings.HEVC_PROFILE_BYTES)
          .map(bytes -> CodecStrings.hevc("hvc1", bytes));
    }

    var merged = new HevcProfileMerge();
    for (var nal : annexB(extradata)) {
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

    return merged.generalProfile().map(bytes -> CodecStrings.hevc("hvc1", bytes));
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
   * FFmpeg's aac_adtstoasc builds the copy's config from the ADTS profile as AAC LC.
   */
  private static String aac(Optional<byte[]> extradata) {
    if (extradata.isEmpty()) {
      return "mp4a.40." + AAC_LC;
    }

    var config = extradata.get();
    var objectType = Byte.toUnsignedInt(config[0]) >> 3;
    if (objectType == ESCAPED_AUDIO_OBJECT_TYPE && config.length > 1) {
      objectType = 32 + (((config[0] & 0x07) << 3) | (Byte.toUnsignedInt(config[1]) >> 5));
    }

    return "mp4a.40." + objectType;
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
