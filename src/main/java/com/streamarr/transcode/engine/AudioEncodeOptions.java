package com.streamarr.transcode.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import lombok.NonNull;

/**
 * The FFmpeg options of an audio encode that names its output layout: the decoder options that go
 * before the input, and the encoder, filter and layout options of the output (ADR 0050's channel
 * layout rule, ADR 0043's sample processing).
 */
record AudioEncodeOptions(@NonNull List<String> inputOptions, @NonNull List<String> outputOptions) {

  // ADR 0043: every rate change uses these resampler settings, because the pinned image has no
  // soxr.
  private static final String TUNED_RESAMPLER =
      "filter_size=128:phase_shift=14:cutoff=0.95:kaiser_beta=14";
  private static final String TPDF_DITHER = "dither_method=triangular";
  private static final int OPUS_SAMPLE_RATE = 48_000;
  private static final int LOSSY_SAMPLE_RATE = 48_000;
  private static final Set<Integer> LOSSY_KEPT_SAMPLE_RATES = Set.of(44_100, 48_000);
  private static final int FLAC_FRAMES_PER_SECOND = 20;
  private static final int SIXTEEN_BITS = 16;
  private static final int TWENTY_FOUR_BITS = 24;
  private static final Set<String> FLOATING_POINT_FORMATS = Set.of("flt", "fltp", "dbl", "dblp");
  private static final Set<String> LOSSLESS_CODECS = Set.of("truehd", "mlp", "flac", "alac");

  public AudioEncodeOptions {
    inputOptions = List.copyOf(inputOptions);
    outputOptions = List.copyOf(outputOptions);
  }

  static AudioEncodeOptions of(AudioDecision audio, SourceAudio source) {
    var output = audio.channelLayout().orElseThrow();
    var encoder = Encoder.of(audio.codec());
    var channels = ChannelStage.of(source, output);
    var rate = targetSampleRate(encoder, audio, source);
    var filters = filters(encoder, audio, source, rate, channels);

    var outputOptions = new ArrayList<>(List.of("-c:a", encoder.ffmpegName(audio.codec())));
    outputOptions.addAll(List.of("-af", String.join(",", filters)));
    outputOptions.addAll(List.of("-ch_layout", output.name()));
    if (encoder == Encoder.OPUS) {
      outputOptions.addAll(opusOptions(output));
    }

    if (encoder != Encoder.LOSSLESS) {
      outputOptions.addAll(List.of("-b:a", audio.bitrate() / 1000 + "k"));
    }

    if ("flac".equals(audio.codec())) {
      outputOptions.addAll(flacFrameSize(rate, source));
    }

    return new AudioEncodeOptions(decoderOptions(source), outputOptions);
  }

  // ADR 0043: no dynamic range compression, and the dialogue normalization that a hardware
  // decoder applies to the copy.
  private static List<String> decoderOptions(SourceAudio source) {
    if (!isDolbyDigital(source)) {
      return List.of();
    }

    return List.of("-drc_scale:a:0", "0", "-target_level:a:0", "-31");
  }

  static boolean isDolbyDigital(SourceAudio source) {
    return "ac3".equals(source.codec()) || "eac3".equals(source.codec());
  }

  // Without mapping family 1, libopus places the channels of 5.0 and 6.1 in the wrong positions.
  private static List<String> opusOptions(SpeakerLayout output) {
    var options = new ArrayList<>(List.of("-vbr", "constrained"));
    if (output.channelCount() > 2) {
      options.addAll(List.of("-mapping_family", "1"));
    }

    return options;
  }

  // ADR 0043: FLAC frames hold the written sample rate divided by 20, rounded down, so that they
  // divide the audio frame grid; without a known rate the encoder keeps its default.
  private static List<String> flacFrameSize(Optional<Integer> rate, SourceAudio source) {
    var written = rate.isPresent() ? OptionalInt.of(rate.orElseThrow()) : source.sampleRate();
    if (written.isEmpty()) {
      return List.of();
    }

    return List.of("-frame_size:a", String.valueOf(written.getAsInt() / FLAC_FRAMES_PER_SECOND));
  }

  // A downmix mixes in floating point, so the format stage follows it. Otherwise the format stage
  // runs first, so its dither never reaches the silent channels that a pad adds.
  private static List<String> filters(
      Encoder encoder,
      AudioDecision audio,
      SourceAudio source,
      Optional<Integer> rate,
      ChannelStage channels) {
    if (channels instanceof ChannelStage.Downmix downmix) {
      var output = audio.channelLayout().orElseThrow();
      return List.of(
          downmix.filter(source.loRoLevels(), limiterFor(encoder, source, output)),
          formatStage(
              encoder, audio, source, rate, !downmix.onlyLeavesChannelsOut(source.loRoLevels())));
    }

    var filters = new ArrayList<>(List.of(formatStage(encoder, audio, source, rate, false)));
    channels.filter().ifPresent(filters::add);
    return filters;
  }

  private static DownmixLimiter limiterFor(
      Encoder encoder, SourceAudio source, SpeakerLayout output) {
    if (encoder == Encoder.LOSSLESS) {
      return DownmixLimiter.forLossless(source);
    }

    return DownmixLimiter.forLossy(output);
  }

  static String resample(int sampleRate) {
    return "aresample=osr=" + sampleRate + ":" + TUNED_RESAMPLER;
  }

  // A downmix that changes samples writes 16-bit FLAC or ALAC with TPDF dither.
  private static String formatStage(
      Encoder encoder,
      AudioDecision audio,
      SourceAudio source,
      Optional<Integer> rate,
      boolean mixed) {
    var options = new ArrayList<String>();
    rate.ifPresent(target -> options.add("osr=" + target));
    if (encoder == Encoder.LOSSLESS) {
      options.addAll(losslessFormat(audio, source, rate.isPresent(), mixed));
    }

    options.add(TUNED_RESAMPLER);
    return "aresample=" + String.join(":", options);
  }

  // A rate that the job names wins, and a rate equal to the source's needs no resampler. Without
  // one, ADR 0043: a lossy encode keeps 44.1 and 48 kHz and writes 48 kHz from any other rate, an
  // Opus encode writes 48 kHz, and a FLAC or ALAC encode keeps the source's rate.
  private static Optional<Integer> targetSampleRate(
      Encoder encoder, AudioDecision audio, SourceAudio source) {
    if (audio.outputSampleRate().isPresent()) {
      var named = audio.outputSampleRate().getAsInt();
      return source.sampleRate().equals(OptionalInt.of(named))
          ? Optional.empty()
          : Optional.of(named);
    }

    return switch (encoder) {
      case OPUS -> Optional.of(OPUS_SAMPLE_RATE);
      case LOSSY ->
          source.sampleRate().isPresent()
                  && !LOSSY_KEPT_SAMPLE_RATES.contains(source.sampleRate().getAsInt())
              ? Optional.of(LOSSY_SAMPLE_RATE)
              : Optional.empty();
      case LOSSLESS, OTHER -> Optional.empty();
    };
  }

  // FFmpeg's FLAC encoder takes packed samples and its ALAC encoder planar ones; both write 24 bits
  // from 32-bit samples. ADR 0043: every conversion that loses precision carries TPDF dither, at
  // the written depth, and a resample to 16 bits runs in floating point, where swresample can
  // dither; on 16-bit samples it would round each one.
  private static List<String> losslessFormat(
      AudioDecision audio, SourceAudio source, boolean resamples, boolean mixed) {
    var depth =
        audio.outputBitDepth().orElse(writesSixteenBits(source) ? SIXTEEN_BITS : TWENTY_FOUR_BITS);
    var options = new ArrayList<String>();
    if (resamples && depth == SIXTEEN_BITS) {
      options.add("internal_sample_fmt=fltp");
    }

    var format = depth == SIXTEEN_BITS ? "s16" : "s32";
    options.add("osf=" + ("alac".equals(audio.codec()) ? format + "p" : format));
    var mixedToSixteenBits = mixed && depth == SIXTEEN_BITS;
    if (!resamples && !mixedToSixteenBits && !losesPrecision(source, depth)) {
      return options;
    }

    if (depth != SIXTEEN_BITS) {
      options.add("output_sample_bits=" + depth);
    }

    options.add(TPDF_DITHER);
    return options;
  }

  // Decoded lossy audio and floating-point samples lose precision at any integer depth.
  private static boolean losesPrecision(SourceAudio source, int depth) {
    if (!isLossless(source) || isFloatingPoint(source)) {
      return true;
    }

    return source.bitDepth().orElse(writesSixteenBits(source) ? SIXTEEN_BITS : TWENTY_FOUR_BITS)
        > depth;
  }

  private static boolean isFloatingPoint(SourceAudio source) {
    return source.sampleFormat().filter(FLOATING_POINT_FORMATS::contains).isPresent();
  }

  // ADR 0043: decoded lossy audio writes 16 bits, and a lossless track keeps its depth up to 24
  // bits, as 16 or 24.
  static boolean writesSixteenBits(SourceAudio source) {
    if (!isLossless(source)) {
      return true;
    }

    if (isFloatingPoint(source)) {
      return false;
    }

    if (source.bitDepth().isPresent()) {
      return source.bitDepth().getAsInt() <= SIXTEEN_BITS;
    }

    return source.codec().startsWith("pcm_s16") || source.codec().startsWith("pcm_u16");
  }

  private static boolean isLossless(SourceAudio source) {
    var codec = source.codec();
    return LOSSLESS_CODECS.contains(codec)
        || codec.startsWith("pcm_")
        || ("dts".equals(codec)
            && source.profile().filter(p -> p.startsWith("DTS-HD MA")).isPresent());
  }

  private enum Encoder {
    LOSSY,
    OPUS,
    LOSSLESS,
    OTHER;

    static Encoder of(String codec) {
      return switch (codec) {
        case "aac", "ac3", "eac3" -> LOSSY;
        case "opus", "libopus" -> OPUS;
        case "flac", "alac" -> LOSSLESS;
        default -> OTHER;
      };
    }

    // FFmpeg's native Opus encoder is experimental and has no mapping family option.
    String ffmpegName(String codec) {
      return this == OPUS ? "libopus" : codec;
    }
  }
}
