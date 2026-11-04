package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Lossy downmixes of FFmpeg's pink noise, limited at −3 dBTP, encoded at a step of the bitrate
 * ladder, decoded, and read by the Annex 2 filter. The limiter leaves the codec 3 dB of room, and
 * each step here kept the decoded summed channels below 0 dBTP in the research. It is a measured
 * result for these steps, not a bound: stereo AAC at 64, 96 and 128 kb/s and stereo Opus at 64, 96
 * and 128 kb/s each decoded at least one pink-noise seed above 0 dBTP, and white noise, a square
 * wave and a clipped sweep fail at many steps, so none of those is checked here. Too slow for CI.
 */
@Tag("SmokeTest")
@DisplayName("Downmix Lossy Ceiling Smoke Tests")
class DownmixLossyCeilingSmokeTest {

  private static final int SAMPLE_RATE = 48_000;
  private static final int SECONDS = 10;
  private static final double FULL_SCALE_DB = 0.0;

  @TempDir Path root;

  @Builder
  record Step(String source, String output, String codec, long bitrate) {

    @Override
    public String toString() {
      return source + " to " + output + ", " + codec + " " + bitrate / 1000 + " kb/s";
    }
  }

  private static Step.StepBuilder step(String source, String output) {
    return Step.builder().source(source).output(output);
  }

  static Stream<Arguments> steps() {
    var steps =
        List.of(
            step("5.1", "stereo").codec("ac3").bitrate(128_000).build(),
            step("5.1", "stereo").codec("ac3").bitrate(192_000).build(),
            step("5.1", "stereo").codec("eac3").bitrate(128_000).build(),
            step("5.1", "stereo").codec("eac3").bitrate(192_000).build(),
            step("5.1", "mono").codec("aac").bitrate(96_000).build(),
            step("5.1", "3.0").codec("aac").bitrate(192_000).build(),
            step("7.1", "5.1").codec("aac").bitrate(320_000).build(),
            step("7.1", "5.1").codec("ac3").bitrate(384_000).build(),
            step("7.1", "5.1").codec("eac3").bitrate(384_000).build(),
            step("7.1", "5.1").codec("libopus").bitrate(256_000).build(),
            step("7.1", "5.0").codec("ac3").bitrate(448_000).build(),
            step("7.1", "5.0").codec("eac3").bitrate(448_000).build(),
            step("7.1", "5.0").codec("eac3").bitrate(640_000).build(),
            step("7.1", "4.0").codec("eac3").bitrate(384_000).build());
    return steps.stream()
        .flatMap(step -> IntStream.rangeClosed(1, 2).mapToObj(seed -> Arguments.of(step, seed)));
  }

  @ParameterizedTest(name = "{0}, seed {1}")
  @MethodSource("steps")
  @DisplayName(
      "Should decode every summed channel below 0 dBTP when a lossy step encodes pink noise")
  void shouldDecodeEverySummedChannelBelow0DbtpWhenALossyStepEncodesPinkNoise(Step step, int seed)
      throws Exception {
    var source = SpeakerLayout.named(step.source());
    var output = SpeakerLayout.named(step.output());
    var input = pinkNoise(source, seed);
    var encoded = encode(input, step);

    var decoded = decode(encoded, output.channelCount());

    for (var channel : summedChannels(source, output)) {
      assertThat(AnnexTwoMeter.truePeakDb(decoded[channel]))
          .as("Annex 2 reading of %s channel %d, dBTP", step, channel)
          .isLessThan(FULL_SCALE_DB);
    }
  }

  // The output channels whose row of the matrix sums more than one source channel.
  private static List<Integer> summedChannels(SpeakerLayout source, SpeakerLayout output) {
    var matrix = DownmixMatrix.of(source, output, Optional.empty());
    var speakers = output.speakers();
    return IntStream.range(0, speakers.size())
        .filter(channel -> matrix.get(speakers.get(channel)).size() > 1)
        .boxed()
        .toList();
  }

  // Independent pink noise in every source channel, 6 dB above full scale in floating point, so
  // that the limiter works on every sum.
  private Path pinkNoise(SpeakerLayout source, int seed) throws Exception {
    var channels = source.channelCount();
    var graph = new StringBuilder();
    for (var channel = 0; channel < channels; channel++) {
      graph
          .append("anoisesrc=color=pink:amplitude=1:r=")
          .append(SAMPLE_RATE)
          .append(":d=")
          .append(SECONDS)
          .append(":seed=")
          .append(seed * 100 + channel)
          .append("[n")
          .append(channel)
          .append("];");
    }

    IntStream.range(0, channels).forEach(channel -> graph.append("[n").append(channel).append(']'));
    graph.append("join=inputs=").append(channels).append(":channel_layout=").append(source.name());
    graph.append(",volume=2");
    var input = root.resolve("pink-" + seed + ".nut");
    ffmpeg(
        List.of(
            "-filter_complex",
            graph.toString(),
            "-c:a",
            "pcm_f64le",
            "-f",
            "nut",
            input.toString()));
    return input;
  }

  // The worker's audio options for the step, applied to the source.
  private Path encode(Path input, Step step) throws Exception {
    var source = SpeakerLayout.named(step.source());
    var output = SpeakerLayout.named(step.output());
    var audio =
        AudioDecision.builder()
            .mode(AudioMode.TRANSCODE)
            .codec("libopus".equals(step.codec()) ? "opus" : step.codec())
            .channels(output.channelCount())
            .bitrate(step.bitrate())
            .channelLayout(Optional.of(output))
            .build();
    var sourceAudio =
        SourceAudio.builder()
            .codec("flac")
            .channels(source.channelCount())
            .channelLayout(Optional.of(source.name()))
            .sampleRate(OptionalInt.of(SAMPLE_RATE))
            .build();
    var options = AudioEncodeOptions.of(audio, sourceAudio);
    var encoded = root.resolve("encoded.mka");
    var command = new ArrayList<>(options.inputOptions());
    command.addAll(List.of("-i", input.toString()));
    command.addAll(options.outputOptions());
    command.addAll(List.of("-f", "matroska", encoded.toString()));
    ffmpeg(command);
    return encoded;
  }

  // Each channel of the decoded output, in the output layout's order.
  private double[][] decode(Path encoded, int channels) throws Exception {
    var decoded = root.resolve("decoded.raw");
    ffmpeg(
        List.of("-i", encoded.toString(), "-c:a", "pcm_f64le", "-f", "f64le", decoded.toString()));
    var samples =
        ByteBuffer.wrap(Files.readAllBytes(decoded))
            .order(ByteOrder.LITTLE_ENDIAN)
            .asDoubleBuffer();
    var frames = samples.remaining() / channels;
    var output = new double[channels][frames];
    for (var frame = 0; frame < frames; frame++) {
      for (var channel = 0; channel < channels; channel++) {
        output[channel][frame] = samples.get();
      }
    }

    return output;
  }

  private void ffmpeg(List<String> arguments) throws Exception {
    var command = new ArrayList<>(List.of("ffmpeg", "-nostdin", "-y", "-loglevel", "error"));
    command.addAll(arguments);
    var errors = root.resolve("ffmpeg-" + System.nanoTime() + ".log");
    var process =
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(errors.toFile())
            .start();
    assertThat(process.waitFor(5, TimeUnit.MINUTES)).as("FFmpeg exits").isTrue();
    assertThat(process.exitValue()).as(Files.readString(errors)).isZero();
  }
}
