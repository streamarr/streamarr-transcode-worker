package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.NonNull;
import tools.jackson.databind.JsonNode;

/**
 * The codec that ffprobe's codec_name names for a stream, with the rules that differ between the
 * codecs the probe reads. The probe walks the extradata and first packets of H.264, HEVC and AV1
 * only; every other codec reads as {@link #OTHER}.
 */
enum StreamCodec {
  H264,
  HEVC,
  AV1,
  /** A codec that the probe does not walk. */
  OTHER;

  /** The codec of the stream; {@link #OTHER} when ffprobe names none. */
  static StreamCodec of(JsonNode stream) {
    return named(text(stream, "codec_name").orElse(""));
  }

  /** The codec that ffprobe calls by the name. */
  static StreamCodec named(@NonNull String codecName) {
    return switch (codecName) {
      case "h264" -> H264;
      case "hevc" -> HEVC;
      case "av1" -> AV1;
      default -> OTHER;
    };
  }

  /** The syntax of the codec's NAL units; empty for a codec without NAL units. */
  Optional<NalSyntax> nalSyntax() {
    return switch (this) {
      case H264 -> Optional.of(NalSyntax.H264);
      case HEVC -> Optional.of(NalSyntax.HEVC);
      case AV1, OTHER -> Optional.empty();
    };
  }

  /**
   * Whether the walks read every unit of the extradata: an av1C record or bare OBUs for AV1, and
   * for H.264 and HEVC a configuration record or Annex B whose NAL units the walks can all delimit.
   * The walks read no extradata of any other codec.
   */
  boolean walksEveryUnitOf(byte[] extradata) {
    if (this == AV1) {
      return DecoderConfiguration.isAv1Record(extradata)
          || DecoderConfiguration.isAv1Obus(extradata);
    }

    return nalSyntax().filter(syntax -> syntax.extradataUnits(extradata).complete()).isPresent();
  }

  /**
   * The Dolby Vision profile that an RPU header names when no configuration record names one, as
   * FFmpeg 8.1 guesses it for each codec. Empty when the header names no profile, and for a codec
   * that carries no Dolby Vision.
   */
  OptionalInt guessDolbyVisionProfile(DolbyVisionRpuHeader header) {
    return switch (this) {
      case HEVC -> DolbyVisionProfileGuess.ofHevc(header);
      case AV1 -> OptionalInt.of(10);
      case H264 -> OptionalInt.of(9);
      case OTHER -> OptionalInt.empty();
    };
  }
}
