package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import com.streamarr.transcode.probe.BitReader.TruncatedBitstreamException;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The RFC 6381 codec string that a stream copy carries, computed from the stream's decoder
 * configuration record or its parameter sets, or, for an avcC or hvcC record that holds no NAL
 * unit, from the parameter sets in the first sample. The tag rule writes avc1 for every H.264 copy
 * and hvc1 for every HEVC copy, so a Dolby Vision copy names its base layer's sample entry. MPEG-TS
 * carries H.264 and HEVC parameter sets in Annex B form, and IVF and raw OBU files carry AV1's
 * sequence header OBU.
 */
final class CodecStringReader {

  private static final int ESCAPED_AUDIO_OBJECT_TYPE = 31;
  private static final String AAC_CODEC_STRING_PREFIX = "mp4a.40.";
  private static final int AAC_MAIN = 1;
  private static final int AAC_LC = 2;
  private static final int AAC_SSR = 3;
  private static final int AAC_LTP = 4;

  private CodecStringReader() {}

  static Optional<String> read(JsonNode stream) {
    try {
      return switch (ProbedCodecFamily.of(stream)) {
        case H264 -> Extradata.of(stream).flatMap(extradata -> avc(stream, extradata));
        case HEVC -> Extradata.of(stream).flatMap(extradata -> hevc(stream, extradata));
        case AV1 -> Extradata.of(stream).flatMap(CodecStringReader::av1);
        case OTHER -> audioCodecString(stream);
      };
    } catch (TruncatedBitstreamException _) {
      return Optional.empty();
    }
  }

  private static Optional<String> audioCodecString(JsonNode stream) {
    return switch (text(stream, "codec_name").orElse("")) {
      case "aac" -> aac(stream, Extradata.of(stream));
      case "ac3" -> Optional.of("ac-3");
      case "eac3" -> Optional.of("ec-3");
      case "opus" -> Optional.of("Opus");
      case "flac" -> Optional.of("fLaC");
      case "alac" -> Optional.of("alac");
      default -> Optional.empty();
    };
  }

  private static Optional<String> avc(JsonNode stream, byte[] extradata) {
    return DecoderConfiguration.avcCopyProfile(extradata)
        .or(
            () ->
                firstSample(stream, NalSyntax.H264)
                    .flatMap(
                        sample -> DecoderConfiguration.avcFirstSampleProfile(extradata, sample)))
        .map(bytes -> CodecStrings.avc("avc1", bytes));
  }

  private static Optional<String> hevc(JsonNode stream, byte[] extradata) {
    return DecoderConfiguration.hevcProfile(extradata)
        .or(
            () ->
                firstSample(stream, NalSyntax.HEVC)
                    .flatMap(
                        sample -> DecoderConfiguration.hevcFirstSampleProfile(extradata, sample)))
        .map(bytes -> CodecStrings.hevc("hvc1", bytes));
  }

  // The probe reads the first packets of the chosen video stream only.
  private static Optional<NalUnits> firstSample(JsonNode stream, NalSyntax syntax) {
    return FirstPacketSamples.read(stream, syntax).flatMap(samples -> samples.stream().findFirst());
  }

  private static Optional<String> av1(byte[] extradata) {
    return DecoderConfiguration.av1Profile(extradata)
        .map(header -> CodecStrings.av1(header.profileAndLevel(), header.flags()));
  }

  /**
   * mp4a.40 and the AudioSpecificConfig's audioObjectType. An ADTS stream has no config, and
   * FFmpeg's aac_adtstoasc builds the copy's config from the ADTS profile. ffprobe names that
   * profile, or HE-AAC when the decoder finds SBR in an AAC LC stream.
   */
  private static Optional<String> aac(JsonNode stream, Optional<byte[]> extradata) {
    if (extradata.isEmpty()) {
      return adtsObjectType(text(stream, "profile").orElse(""))
          .map(type -> AAC_CODEC_STRING_PREFIX + type);
    }

    var config = extradata.get();
    var objectType = Byte.toUnsignedInt(config[0]) >> 3;
    if (objectType != ESCAPED_AUDIO_OBJECT_TYPE) {
      return Optional.of(AAC_CODEC_STRING_PREFIX + objectType);
    }

    if (config.length < 2) {
      return Optional.empty();
    }

    return Optional.of(
        AAC_CODEC_STRING_PREFIX
            + (32 + (((config[0] & 0x07) << 3) | (Byte.toUnsignedInt(config[1]) >> 5))));
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
}
