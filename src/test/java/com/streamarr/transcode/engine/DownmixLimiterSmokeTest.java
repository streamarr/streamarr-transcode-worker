package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The downmix filter that the worker builds, run by real FFmpeg on raw floating-point samples, and
 * read by an Annex 2 filter written from the standard's table. The worker's true-peak ceiling is a
 * measured result, not a proven bound: each input here is one that the research found hardest.
 */
@Tag("SmokeTest")
@DisplayName("Downmix Limiter Smoke Tests")
class DownmixLimiterSmokeTest {

  // The Annex 2 filter read every two-stage output in the research within 0.0013 dB of −1 dBTP.
  private static final double CEILING = 0.891251;
  private static final double LOSSY_CEILING = 0.707946;
  // A float32 step at the ceiling, for the samples that leave the limiter through a float stage.
  private static final double FLOAT_STEP = 6e-8;
  private static final List<Integer> RATES = List.of(22_050, 44_100, 48_000, 96_000);

  // numpy's default_rng(97).uniform(-1, 1, 16): a burst that read 0.10 dB over the ceiling after
  // one stage, at 22.05 kHz in a mono downmix, though every sample stays within ±0.957.
  private static final double[] SEED_97_BURST = {
    0.3485468114436678, -0.1353775113382722, 0.03896396307699224, 0.956912037226036,
    -0.6401070655921512, 0.4931617773081705, -0.9333614594315829, -0.9021604089683997,
    -0.2998921991235353, 0.3740848898781128, 0.8855019572012761, -0.6619448460557293,
    -0.1830819719463412, 0.6827423235752741, -0.886912056485859, 0.8935113096423715
  };

  // The five summed source channels of 5.1 (FL FR FC BL BR), and the four of 7.1 (BL BR SL SR).
  private static final int[] FIVE_POINT_ONE_SUMMED = {0, 1, 2, 4, 5};
  private static final int[] SEVEN_POINT_ONE_SURROUNDS = {4, 5, 6, 7};

  @TempDir Path root;

  enum Job {
    STEREO("5.1", "stereo"),
    MONO("5.1", "mono"),
    SURROUND_PAIR("7.1", "5.1");

    private final SpeakerLayout source;
    private final SpeakerLayout output;

    Job(String source, String output) {
      this.source = SpeakerLayout.named(source);
      this.output = SpeakerLayout.named(output);
    }

    // The output channels that the limiter holds.
    int[] limited() {
      return this == SURROUND_PAIR
          ? new int[] {4, 5}
          : IntStream.range(0, output.channelCount()).toArray();
    }
  }

  enum Placement {
    WHOLE_FILE,
    START,
    MIDDLE,
    END;

    int frames(int burst, int sampleRate) {
      return this == WHOLE_FILE ? burst : sampleRate;
    }

    int offset(int burst, int sampleRate) {
      return switch (this) {
        case WHOLE_FILE, START -> 0;
        case MIDDLE -> sampleRate / 2;
        case END -> sampleRate - burst;
      };
    }
  }

  /** Where a burst sits in the source of one job, at one sample rate. */
  @Builder
  record Placing(Job job, Placement placement, int sampleRate) {

    @Override
    public String toString() {
      return sampleRate + " Hz, " + job + ", " + placement;
    }
  }

  /** What every limited channel of an output must keep. */
  @Builder
  record Expected(int frames, Job job, double ceiling) {}

  /** One FFmpeg run of a filter over raw floating-point frames. */
  @Builder
  record Filtering(String layout, String filter, int sampleRate, int channels) {}

  static Stream<Arguments> seed97Cases() {
    return RATES.stream()
        .flatMap(
            rate ->
                Stream.of(Job.STEREO, Job.MONO)
                    .flatMap(
                        job ->
                            Arrays.stream(Placement.values())
                                .map(placement -> Arguments.of(placing(job, placement, rate)))));
  }

  private static Placing placing(Job job, Placement placement, int sampleRate) {
    return Placing.builder().job(job).placement(placement).sampleRate(sampleRate).build();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("seed97Cases")
  @DisplayName(
      "Should hold the bounded burst within a hundredth of a decibel of the ceiling when FLAC"
          + " downmixes 5.1")
  void shouldHoldTheBoundedBurstWithinAHundredthOfADecibelOfTheCeilingWhenFlacDownmixes51(
      Placing placing) throws Exception {
    var source = placed(seed97Burst(), placing);

    var output = limitLossless(source, placing.job(), placing.sampleRate());

    assertHeldAtTheCeiling(output, source.length, placing.job());
  }

  static Stream<Arguments> lossyCases() {
    return Stream.of(44_100, 48_000)
        .flatMap(
            rate ->
                Arrays.stream(Job.values())
                    .flatMap(
                        job ->
                            Stream.of(Placement.MIDDLE, Placement.END)
                                .map(placement -> Arguments.of(placing(job, placement, rate)))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("lossyCases")
  @DisplayName(
      "Should hold the bounded burst within a hundredth of a decibel of -3 dBTP when an AAC encode"
          + " downmixes it")
  void shouldHoldTheBoundedBurstWithinAHundredthOfADecibelOfMinus3DbtpWhenAnAacEncodeDownmixesIt(
      Placing placing) throws Exception {
    var job = placing.job();
    var source = placed(seed97Burst(), placing);
    var audio =
        AudioDecision.builder()
            .mode(AudioMode.TRANSCODE)
            .codec("aac")
            .channels(job.output.channelCount())
            .bitrate(384_000)
            .channelLayout(Optional.of(job.output))
            .build();
    var sourceAudio =
        SourceAudio.builder()
            .codec("flac")
            .channels(job.source.channelCount())
            .channelLayout(Optional.of(job.source.name()))
            .sampleRate(OptionalInt.of(placing.sampleRate()))
            .build();
    var options = AudioEncodeOptions.of(audio, sourceAudio, Duration.ZERO).outputOptions();
    var filter = options.get(options.indexOf("-af") + 1);

    var output = run(source, filtering(job, filter, placing.sampleRate()));

    assertHeldAt(
        output, Expected.builder().frames(source.length).job(job).ceiling(LOSSY_CEILING).build());
  }

  static Stream<Arguments> searchedBurstCases() {
    var fiveChannelBursts =
        Stream.of(
            "one-stage-mono-22050",
            "one-stage-mono-48000",
            "one-stage-stereo-48000",
            "two-stage-mono-22050",
            "two-stage-mono-48000");
    var bursts =
        Stream.concat(
            fiveChannelBursts.flatMap(
                burst -> Stream.of(Job.STEREO, Job.MONO).map(job -> Map.entry(burst, job))),
            Stream.of(Map.entry("one-stage-surround-pair-48000", Job.SURROUND_PAIR)));
    var placements = List.of(Placement.START, Placement.MIDDLE, Placement.END);
    return bursts.flatMap(
        burst ->
            RATES.stream()
                .flatMap(
                    rate ->
                        placements.stream()
                            .map(
                                placement ->
                                    Arguments.of(
                                        burst.getKey(),
                                        placing(burst.getValue(), placement, rate)))));
  }

  @ParameterizedTest(name = "{0}, {1}")
  @MethodSource("searchedBurstCases")
  @DisplayName(
      "Should hold a searched burst within a hundredth of a decibel of the ceiling when FLAC"
          + " downmixes it")
  void shouldHoldASearchedBurstWithinAHundredthOfADecibelOfTheCeilingWhenFlacDownmixesIt(
      String burst, Placing placing) throws Exception {
    var source = placed(searchedBurst(burst), placing);

    var output = limitLossless(source, placing.job(), placing.sampleRate());

    assertHeldAtTheCeiling(output, source.length, placing.job());
  }

  static Stream<Arguments> noiseCases() {
    var lengths = Stream.of(1, 16, 93, 100, 127, 128, 129, 196, 239, 1_000, 10_000);
    var shortFiles =
        lengths.flatMap(
            frames ->
                Stream.of(22_050, 48_000)
                    .flatMap(
                        rate ->
                            Stream.of(Job.STEREO, Job.MONO)
                                .map(job -> Arguments.of(frames, rate, job))));
    var longFiles =
        Stream.of(44_100, 48_000, 96_000).map(rate -> Arguments.of(2 * rate, rate, Job.STEREO));
    return Stream.concat(shortFiles, longFiles);
  }

  @ParameterizedTest(name = "{0} frames, {1} Hz, {2}")
  @MethodSource("noiseCases")
  @DisplayName(
      "Should keep the sample count and hold the ceiling when FLAC downmixes full-scale correlated"
          + " noise")
  void shouldKeepTheSampleCountAndHoldTheCeilingWhenFlacDownmixesFullScaleCorrelatedNoise(
      int frames, int sampleRate, Job job) throws Exception {
    var random = new Random(100_000L + frames);
    var source = new double[frames][job.source.channelCount()];
    for (var frame = 0; frame < frames; frame++) {
      var sample = 2 * random.nextDouble() - 1;
      source[frame][0] = sample;
      source[frame][1] = sample;
    }

    var output = limitLossless(source, job, sampleRate);

    assertHeldAtTheCeiling(output, frames, job);
  }

  @Test
  @DisplayName(
      "Should read a burst in the file's last samples as it reads in the middle when FLAC"
          + " downmixes 5.1")
  void shouldReadABurstInTheFilesLastSamplesAsItReadsInTheMiddleWhenFlacDownmixes51()
      throws Exception {
    var random = new Random(16);
    var burst = new double[16][];
    Arrays.setAll(burst, _ -> repeated(Math.sqrt(2) * (2 * random.nextDouble() - 1), 5));
    var sampleRate = 48_000;

    var end =
        limitLossless(
            placed(burst, placing(Job.STEREO, Placement.END, sampleRate)), Job.STEREO, sampleRate);
    var middle =
        limitLossless(
            placed(burst, placing(Job.STEREO, Placement.MIDDLE, sampleRate)),
            Job.STEREO,
            sampleRate);

    assertHeldAtTheCeiling(end, sampleRate, Job.STEREO);
    for (var channel = 0; channel < 2; channel++) {
      var last = Arrays.copyOfRange(end[channel], sampleRate - 16, sampleRate);
      assertThat(peakOf(last)).isLessThanOrEqualTo(CEILING + FLOAT_STEP);
      assertThat(AnnexTwoMeter.truePeakDb(end[channel]))
          .as("channel %d at the end against the middle", channel)
          .isCloseTo(AnnexTwoMeter.truePeakDb(middle[channel]), within(0.001));
    }
  }

  @ParameterizedTest(name = "{0} Hz")
  @ValueSource(ints = {44_100, 48_000})
  @DisplayName(
      "Should keep every sample when the summed pair stays under the ceiling between samples")
  void shouldKeepEverySampleWhenTheSummedPairStaysUnderTheCeilingBetweenSamples(int sampleRate)
      throws Exception {
    // 2.1 to stereo sums the silent low-frequency channel into each side, so the pan passes the
    // front pair through and the limiter's input is the source, float32 values included.
    var frames = sampleRate * 2;
    var source = new double[frames][3];
    for (var frame = 0; frame < frames; frame++) {
      var time = (double) frame / sampleRate;
      var envelope = (time % 0.5) < 0.1 ? 1.0 : 0.3;
      source[frame][0] =
          (float)
              (envelope
                  * (0.6 * Math.sin(2 * Math.PI * 440 * time)
                      + 0.2 * Math.sin(2 * Math.PI * 3_150 * time)));
      source[frame][1] =
          (float)
              (envelope
                  * (0.6 * Math.sin(2 * Math.PI * 550 * time)
                      + 0.2 * Math.sin(2 * Math.PI * 2_730 * time)));
    }

    var stereo = SpeakerLayout.named("stereo");
    var filter =
        DownmixFilter.of(
            stereo,
            DownmixMatrix.of(SpeakerLayout.named("2.1"), stereo, Optional.empty()),
            DownmixLimiter.forLossless(OptionalInt.of(sampleRate)),
            Optional.empty());
    var output =
        run(
            source,
            Filtering.builder()
                .layout("2.1")
                .filter(filter)
                .sampleRate(sampleRate)
                .channels(2)
                .build());

    for (var channel = 0; channel < 2; channel++) {
      var expected = new double[frames];
      final var column = channel;
      Arrays.setAll(expected, frame -> source[frame][column]);
      assertThat(output[channel]).as("channel %d", channel).containsExactly(expected);
    }
  }

  @Test
  @DisplayName("Should keep the input's timestamps when the limiter holds the summed pair")
  void shouldKeepTheInputsTimestampsWhenTheLimiterHoldsTheSummedPair() throws Exception {
    var sampleRate = 48_000;
    var random = new Random(7);
    var source = new double[sampleRate][6];
    for (var frame = 0; frame < sampleRate; frame++) {
      source[frame][0] = 2 * random.nextDouble() - 1;
      source[frame][1] = source[frame][0];
    }

    var input = writeRaw(source);
    var command =
        List.of(
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-copyts",
            "-f",
            "f64le",
            "-ar",
            String.valueOf(sampleRate),
            "-ch_layout",
            "5.1",
            "-i",
            input.toString(),
            "-af",
            // The source's timestamps start ten seconds in, as a seek's do under -copyts.
            "asetpts=PTS+10/TB," + stereoFilter(sampleRate),
            "-c:a",
            "pcm_f64le",
            "-f",
            "framecrc",
            "-");
    var packets = new ArrayList<long[]>();
    for (var line : ffmpeg(command).split("\n")) {
      if (!line.startsWith("#") && !line.isBlank()) {
        var fields = line.split(",\\s*");
        packets.add(new long[] {Long.parseLong(fields[2]), Long.parseLong(fields[3])});
      }
    }

    assertThat(packets.getFirst()[0]).as("first timestamp").isEqualTo(10L * sampleRate);
    assertThat(packets.stream().mapToLong(packet -> packet[1]).sum()).isEqualTo(sampleRate);
  }

  @Test
  @DisplayName(
      "Should keep every limited sample and write digital silence after them when a dithered FLAC"
          + " downmix pads its audio")
  void shouldKeepEveryLimitedSampleAndWriteDigitalSilenceAfterThemWhenAFlacDownmixPads()
      throws Exception {
    var sampleRate = 48_000;
    var source = placed(seed97Burst(), placing(Job.STEREO, Placement.END, sampleRate));
    var audio =
        AudioDecision.builder()
            .mode(AudioMode.TRANSCODE)
            .codec("flac")
            .channels(2)
            .bitrate(0)
            .channelLayout(Optional.of(Job.STEREO.output));
    var sourceAudio =
        SourceAudio.builder()
            .codec("pcm_f64le")
            .channels(6)
            .channelLayout(Optional.of("5.1"))
            .sampleFormat(Optional.of("dbl"))
            .sampleRate(OptionalInt.of(sampleRate))
            .build();

    var unpadded = run(source, filtering(Job.STEREO, encodeFilter(audio, sourceAudio), sampleRate));
    var padded =
        run(
            source,
            filtering(
                Job.STEREO,
                encodeFilter(audio.padsWithSilenceToAdvertisedEnd(true), sourceAudio),
                sampleRate));

    assertHeldAtTheCeiling(unpadded, sampleRate, Job.STEREO);
    for (var channel = 0; channel < 2; channel++) {
      assertThat(padded[channel])
          .as("frames of channel %d, up to one second past the advertised end", channel)
          .hasSize(3 * sampleRate);
      assertThat(Arrays.copyOf(padded[channel], sampleRate))
          .as("limited samples of channel %d", channel)
          .containsExactly(unpadded[channel]);
      assertThat(Arrays.copyOfRange(padded[channel], sampleRate, 3 * sampleRate))
          .as("silence of channel %d", channel)
          .containsOnly(0.0);
    }
  }

  // The -af chain that the worker's command for an audio encode of the source names, padded up to
  // an advertised end two seconds in when the decision pads.
  private static String encodeFilter(
      AudioDecision.AudioDecisionBuilder audio, SourceAudio sourceAudio) {
    var decision =
        TranscodeDecision.builder()
            .transcodeMode(TranscodeMode.AUDIO_TRANSCODE)
            .videoCodecFamily("h264")
            .videoDecision(VideoDecisionFixtures.forMode(TranscodeMode.AUDIO_TRANSCODE))
            .audioDecision(audio.build())
            .subtitleDecision(SubtitleDecisions.EXCLUDED)
            .build();
    var request =
        TranscodeRequest.builder()
            .sessionId(UUID.randomUUID())
            .sourcePath(Path.of("/media/movie.mkv"))
            .targetSegmentDuration(6)
            .mediaSegmentCount(1)
            .transcodeDecision(decision)
            .sourceAudio(Optional.of(sourceAudio))
            .advertisedEnd(Optional.of(Duration.ofSeconds(2)))
            .build();
    var job = TranscodeJob.builder().request(request).videoEncoder("copy").build();
    var command =
        ((FfmpegPipeline.SingleProcess)
                new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1)).buildPipeline(job))
            .command();
    assertThat(command).containsOnlyOnce("-af");
    return command.get(command.indexOf("-af") + 1);
  }

  private String stereoFilter(int sampleRate) {
    var stereo = SpeakerLayout.named("stereo");
    return DownmixFilter.of(
        stereo,
        DownmixMatrix.of(SpeakerLayout.named("5.1"), stereo, Optional.empty()),
        DownmixLimiter.forLossless(OptionalInt.of(sampleRate)),
        Optional.empty());
  }

  private static void assertHeldAtTheCeiling(double[][] output, int frames, Job job) {
    assertHeldAt(output, Expected.builder().frames(frames).job(job).ceiling(CEILING).build());
  }

  // Every limited channel keeps its frames, reads within a hundredth of a decibel of the ceiling
  // on the Annex 2 filter, and keeps its samples at or below the ceiling.
  private static void assertHeldAt(double[][] output, Expected expected) {
    var ceiling = expected.ceiling();
    var withinAHundredth = AnnexTwoMeter.decibels(ceiling) + 0.01;
    for (var channel : expected.job().limited()) {
      assertThat(output[channel]).as("frames of channel %d", channel).hasSize(expected.frames());
      assertThat(AnnexTwoMeter.truePeakDb(output[channel]))
          .as("Annex 2 reading of channel %d, dBTP", channel)
          .isLessThanOrEqualTo(withinAHundredth);
      assertThat(peakOf(output[channel]))
          .as("sample peak of channel %d", channel)
          .isLessThanOrEqualTo(ceiling + FLOAT_STEP);
    }
  }

  private double[][] limitLossless(double[][] source, Job job, int sampleRate) throws Exception {
    var filter =
        DownmixFilter.of(
            job.output,
            DownmixMatrix.of(job.source, job.output, Optional.empty()),
            DownmixLimiter.forLossless(OptionalInt.of(sampleRate)),
            Optional.empty());
    return run(source, filtering(job, filter, sampleRate));
  }

  private static Filtering filtering(Job job, String filter, int sampleRate) {
    return Filtering.builder()
        .layout(job.source.name())
        .filter(filter)
        .sampleRate(sampleRate)
        .channels(job.output.channelCount())
        .build();
  }

  // The bounded burst in each of the five summed source channels of 5.1.
  private static double[][] seed97Burst() {
    var burst = new double[SEED_97_BURST.length][];
    Arrays.setAll(burst, sample -> repeated(SEED_97_BURST[sample], FIVE_POINT_ONE_SUMMED.length));
    return burst;
  }

  // The burst's columns in the job's summed source channels, in a file of the placement's length.
  private static double[][] placed(double[][] burst, Placing placing) {
    var job = placing.job();
    var placement = placing.placement();
    var sampleRate = placing.sampleRate();
    var columns = job == Job.SURROUND_PAIR ? SEVEN_POINT_ONE_SURROUNDS : FIVE_POINT_ONE_SUMMED;
    var source = new double[placement.frames(burst.length, sampleRate)][job.source.channelCount()];
    var offset = placement.offset(burst.length, sampleRate);
    for (var sample = 0; sample < burst.length; sample++) {
      for (var column = 0; column < columns.length; column++) {
        source[offset + sample][columns[column]] = burst[sample][column];
      }
    }

    return source;
  }

  private static double[][] searchedBurst(String name) throws IOException {
    try (InputStream stream =
        DownmixLimiterSmokeTest.class.getResourceAsStream("downmix-bursts/" + name + ".txt")) {
      assertThat(stream).as(name).isNotNull();
      var text = new String(stream.readAllBytes(), StandardCharsets.US_ASCII);
      return text.lines()
          .filter(line -> !line.startsWith("#") && !line.isBlank())
          .map(
              line ->
                  Arrays.stream(line.trim().split(" ")).mapToDouble(Double::parseDouble).toArray())
          .toArray(double[][]::new);
    }
  }

  private static double[] repeated(double value, int times) {
    var row = new double[times];
    Arrays.fill(row, value);
    return row;
  }

  private static double peakOf(double[] samples) {
    return Arrays.stream(samples).map(Math::abs).max().orElse(0);
  }

  // Each output channel of FFmpeg's run of the filter over the interleaved source frames.
  private double[][] run(double[][] source, Filtering filtering) throws Exception {
    var sampleRate = filtering.sampleRate();
    var channels = filtering.channels();
    var input = writeRaw(source);
    var output = root.resolve("out-" + System.nanoTime() + ".raw");
    ffmpeg(
        List.of(
            "ffmpeg",
            "-nostdin",
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "f64le",
            "-ar",
            String.valueOf(sampleRate),
            "-ch_layout",
            filtering.layout(),
            "-i",
            input.toString(),
            "-af",
            filtering.filter(),
            "-c:a",
            "pcm_f64le",
            "-f",
            "f64le",
            output.toString()));
    var samples =
        ByteBuffer.wrap(Files.readAllBytes(output)).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer();
    var frames = samples.remaining() / channels;
    var decoded = new double[channels][frames];
    for (var frame = 0; frame < frames; frame++) {
      for (var channel = 0; channel < channels; channel++) {
        decoded[channel][frame] = samples.get();
      }
    }

    return decoded;
  }

  private Path writeRaw(double[][] source) throws IOException {
    var channels = source.length == 0 ? 0 : source[0].length;
    var bytes =
        ByteBuffer.allocate(source.length * channels * Double.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    Arrays.stream(source).forEach(frame -> Arrays.stream(frame).forEach(bytes::putDouble));
    var input = root.resolve("in-" + System.nanoTime() + ".raw");
    Files.write(input, bytes.array());
    return input;
  }

  // FFmpeg's standard output, after it exits successfully.
  private String ffmpeg(List<String> command) throws Exception {
    var errors = root.resolve("ffmpeg-" + System.nanoTime() + ".log");
    var process = new ProcessBuilder(command).redirectError(errors.toFile()).start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("FFmpeg exits").isTrue();
    assertThat(process.exitValue()).as(Files.readString(errors)).isZero();
    return output;
  }
}
