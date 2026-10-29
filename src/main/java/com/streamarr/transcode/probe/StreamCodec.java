package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import java.util.Arrays;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.NonNull;
import tools.jackson.databind.JsonNode;

/**
 * The codec that ffprobe's codec_name names for a stream, with the rules that differ between the
 * codecs the probe reads. The probe walks the extradata and first packets of the {@link Walked}
 * codecs only; any other codec keeps its name as {@link Other}.
 */
sealed interface StreamCodec permits StreamCodec.Walked, StreamCodec.Other {

  /** The codec of the stream; {@link Other} with an empty name when ffprobe names none. */
  static StreamCodec of(JsonNode stream) {
    return named(text(stream, "codec_name").orElse(""));
  }

  /** The codec that ffprobe calls by the name. */
  static StreamCodec named(String codecName) {
    return Arrays.stream(Walked.values())
        .filter(codec -> codec.codecName.equals(codecName))
        .<StreamCodec>map(codec -> codec)
        .findFirst()
        .orElseGet(() -> new Other(codecName));
  }

  /** The syntax of the codec's NAL units; empty for a codec without NAL units. */
  default Optional<NalSyntax> nalSyntax() {
    return switch (this) {
      case Walked.H264 -> Optional.of(NalSyntax.H264);
      case Walked.HEVC -> Optional.of(NalSyntax.HEVC);
      case Walked.AV1 -> Optional.empty();
      case Other _ -> Optional.empty();
    };
  }

  /**
   * Whether the walks read every unit of the extradata: an av1C record or bare OBUs for AV1, and
   * for H.264 and HEVC a configuration record or Annex B whose NAL units the walks can all delimit.
   * The walks read no extradata of any other codec.
   */
  default boolean walksEveryUnitOf(byte[] extradata) {
    if (this == Walked.AV1) {
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
  default OptionalInt guessDolbyVisionProfile(DolbyVisionRpuHeader header) {
    return switch (this) {
      case Walked.HEVC -> DolbyVisionProfileGuess.ofHevc(header);
      case Walked.AV1 -> OptionalInt.of(10);
      case Walked.H264 -> OptionalInt.of(9);
      case Other _ -> OptionalInt.empty();
    };
  }

  /** A codec whose extradata and first packets the probe walks. */
  enum Walked implements StreamCodec {
    H264("h264"),
    HEVC("hevc"),
    AV1("av1");

    private final String codecName;

    Walked(String codecName) {
      this.codecName = codecName;
    }
  }

  /** A codec that the probe does not walk, under the name that ffprobe gives it. */
  record Other(@NonNull String name) implements StreamCodec {}
}
