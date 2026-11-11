package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.fixtures.DetectedCapabilities;
import com.streamarr.transcode.fixtures.Mp4Samples;
import com.streamarr.transcode.worker.support.TeeingProcessLauncher;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs SVT-AV1 encodes on the FFmpeg on {@code PATH}, the locked build in CI, with the worker's own
 * commands at production threading, and checks that the delivered av1C holds the sequence header of
 * the video stream itself. The source is a synthetic 64x36 H.264 stream at 24 fps with a keyframe
 * every second. The worker refuses 10-bit encodes until it encodes them, so the 10-bit PQ encode
 * runs the worker's command with the pixel format and SVT-AV1 parameters of a 10-bit PQ encode, and
 * the test rewrites its initialization segment as the producer does.
 */
@Tag("SmokeTest")
@DisplayName("AV1 Sequence Header Smoke Tests")
class Av1SequenceHeaderSmokeTest {

  private static final int SOURCE_SECONDS = 4;
  private static final int FRAME_RATE = 24;
  private static final int PERIOD_SECONDS = 1;
  private static final int REPLACEMENT_SEGMENT = 2;
  // SVT-AV1's 64x36 Main at level 2.0 in the Main tier, 8-bit and 10-bit.
  private static final String CODEC_STRING = "av01.0.00M.08";
  private static final String TEN_BIT_CODEC_STRING = "av01.0.00M.10";
  private static final Duration OUTCOME_LIMIT = Duration.ofSeconds(60);
  private static final Duration FRAGMENTATION_TARGET = Duration.ofMillis(250);
  private static final int OBU_SEQUENCE_HEADER = 1;
  // The SVT-AV1 parameters of a 10-bit PQ encode with HDR10 static metadata.
  private static final String TEN_BIT_PQ_PARAMETERS =
      ":color-primaries=bt2020:transfer-characteristics=smpte2084:matrix-coefficients=bt2020-ncl"
          + ":color-range=studio"
          + ":mastering-display=G(0.265,0.69)B(0.15,0.06)R(0.68,0.32)WP(0.3127,0.329)L(1000,0.005)"
          + ":content-light=1000,400";

  @TempDir static Path root;
  private static Path source;

  @BeforeAll
  static void writeTheSource() throws Exception {
    source = root.resolve("h264.mp4");
    run(
        List.of(
            "ffmpeg",
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x36:rate=" + FRAME_RATE + ":duration=" + SOURCE_SECONDS,
            "-c:v",
            "libx264",
            "-g",
            String.valueOf(FRAME_RATE),
            "-pix_fmt",
            "yuv420p",
            source.toString()));
  }

  @ParameterizedTest(name = "starting at segment {0}")
  @ValueSource(ints = {0, REPLACEMENT_SEGMENT})
  @DisplayName(
      "Should deliver an av1C whose configOBUs are every keyframe's in-band sequence header when"
          + " SVT-AV1 writes another sequence header into FFmpeg's av1C")
  void shouldDeliverAnAv1CWhoseConfigObusAreEveryKeyframesInBandSequenceHeader(int firstSegment) {
    var attempt = encoded(firstSegment);

    var delivered = attempt.sink().contentOf("init.mp4");

    var inBand = keyframeSequenceHeadersOf(attempt.sink().acceptedBytes());
    assertThat(inBand).isNotEmpty().containsOnly(inBand.getFirst());
    assertThat(configurationObusOf(delivered)).isEqualTo(inBand.getFirst());
    assertThat(configurationObusOf(initializationSegmentOf(attempt.ffmpegOutput())))
        .as("the sequence header that SVT-AV1 wrote into FFmpeg's av1C")
        .isNotEqualTo(inBand.getFirst());
  }

  @Test
  @DisplayName(
      "Should deliver byte-identical initialization segments with the job's codec string when two"
          + " SVT-AV1 attempts start at different segments")
  void shouldDeliverByteIdenticalInitializationSegmentsWhenTwoAttemptsStartAtDifferentSegments() {
    var fromStart = encoded(0).sink().contentOf("init.mp4");

    var replacement = encoded(REPLACEMENT_SEGMENT).sink().contentOf("init.mp4");

    assertThat(replacement).isEqualTo(fromStart);
    assertThat(codecStringOf(fromStart)).isEqualTo(CODEC_STRING);
  }

  @Test
  @DisplayName(
      "Should rewrite a 10-bit PQ encode's av1C to its in-band sequence header and keep its codec"
          + " string when two attempts start at different segments")
  void shouldRewriteATenBitPqEncodesAv1CToItsInBandSequenceHeaderWhenAttemptsStartApart()
      throws Exception {
    var command = tenBitPqCommandOf(encoded(0).commands().getFirst());

    var fromStart = tenBitPqOutput(command, 0);
    var replacement = tenBitPqOutput(command, REPLACEMENT_SEGMENT);

    var inBand = keyframeSequenceHeadersOf(fromStart);
    assertThat(inBand).isNotEmpty().containsOnly(inBand.getFirst());
    var rewritten = rewrittenInitializationSegmentOf(fromStart);
    assertThat(configurationObusOf(rewritten)).isEqualTo(inBand.getFirst());
    assertThat(rewrittenInitializationSegmentOf(replacement)).isEqualTo(rewritten);
    assertThat(configurationObusOf(initializationSegmentOf(fromStart)))
        .as("the sequence header that SVT-AV1 wrote into FFmpeg's av1C")
        .isNotEqualTo(inBand.getFirst());
    assertThat(codecStringOf(rewritten))
        .isEqualTo(codecStringOf(initializationSegmentOf(fromStart)))
        .isEqualTo(TEN_BIT_CODEC_STRING);
  }

  private static Attempt encoded(int firstSegment) {
    var sink = new RecordingSegmentSink();
    var commands = new CopyOnWriteArrayList<List<String>>();
    var launcher =
        new TeeingProcessLauncher(
            (command, jobAttemptId) -> {
              commands.add(List.copyOf(command));
              return new ProcessBuilderLauncher().launch(command, jobAttemptId);
            });
    var request = request(firstSegment);

    var producer = engine(launcher).startProducer(request, sink, SegmentMemoryBudget.forSlots(1));

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isInstanceOf(Completed.class);
    return new Attempt(sink, launcher.outputOf(request.attemptId()), List.copyOf(commands));
  }

  /** A completed attempt's deliveries, its FFmpeg's standard output, and its FFmpeg commands. */
  private record Attempt(
      RecordingSegmentSink sink, byte[] ffmpegOutput, List<List<String>> commands) {}

  private static FfmpegTranscodeEngine engine(ProcessLauncher launcher) {
    return FfmpegTranscodeEngine.builder()
        .commandBuilder(new FfmpegCommandBuilder("ffmpeg", FRAGMENTATION_TARGET))
        .capabilityService(DetectedCapabilities.realCapabilities())
        .launcher(launcher)
        .encoderStallTimeout(FfmpegTranscodeEngine.DEFAULT_ENCODER_STALL_TIMEOUT)
        .build();
  }

  private static TranscodeRequest request(int firstSegment) {
    var seek = Optional.<Duration>empty();
    if (firstSegment > 0) {
      seek = Optional.of(Duration.ofSeconds((long) firstSegment * PERIOD_SECONDS));
    }

    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(source)
        .targetSegmentDuration(PERIOD_SECONDS)
        .outputFrameRate(Optional.of(new OutputFrameRate(FRAME_RATE, 1)))
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.VIDEO_TRANSCODE)
                .videoCodecFamily("av1")
                .videoDecision(encode(CODEC_STRING))
                .audioDecision(AudioDecision.builder().mode(AudioMode.NONE).build())
                .subtitleDecision(SubtitleDecisions.EXCLUDED)
                .build())
        .encoderPreset(EncoderPreset.FASTER)
        .width(64)
        .height(36)
        .bitrate(100_000L)
        .startSequenceNumber(firstSegment)
        .mediaSegmentCount(SOURCE_SECONDS / PERIOD_SECONDS)
        .seekPosition(seek)
        .build();
  }

  private static VideoDecision.Encode encode(String codecString) {
    return VideoDecisionFixtures.encodeBuilder()
        .codecProfile(VideoCodecProfile.AV1_MAIN)
        .tier(Optional.of(VideoCodecTier.MAIN))
        .level(20)
        .codecString(codecString)
        .build();
  }

  // The worker's command up to its pipe:1 output, which writes 10-bit 4:2:0 and carries the
  // SVT-AV1 parameters of a 10-bit PQ encode; the framecrc output after it is left out.
  private static List<String> tenBitPqCommandOf(List<String> command) {
    var adjusted = new ArrayList<>(command.subList(0, command.indexOf("pipe:1")));
    var filters = adjusted.indexOf("-vf") + 1;
    assertThat(adjusted.get(filters)).contains("format=yuv420p,");
    adjusted.set(filters, adjusted.get(filters).replace("format=yuv420p,", "format=yuv420p10le,"));
    var parameters = adjusted.indexOf("-svtav1-params") + 1;
    assertThat(parameters).as("the SVT-AV1 command sets encoder parameters").isPositive();
    adjusted.set(parameters, adjusted.get(parameters) + TEN_BIT_PQ_PARAMETERS);
    return adjusted;
  }

  // FFmpeg's standard output for an attempt from the segment: the command seeks as the worker does
  // for a stream copy, to the segment's start, so that the attempt starts at a later keyframe.
  private static byte[] tenBitPqOutput(List<String> command, int firstSegment) throws Exception {
    var adjusted = new ArrayList<>(command);
    if (firstSegment > 0) {
      adjusted.addAll(
          adjusted.indexOf("-i"),
          List.of("-ss", String.valueOf(firstSegment * PERIOD_SECONDS), "-noaccurate_seek"));
    }

    var output = root.resolve("ten-bit-pq-" + firstSegment + ".mp4");
    adjusted.add(output.toString());
    run(adjusted);
    return Files.readAllBytes(output);
  }

  private static InitializationSegment rewrittenInitializationSegmentOf(byte[] output)
      throws IOException {
    var stream = Mp4Stream.read(Mp4Stream.readerOf(output));
    var fragment = stream.fragments().getFirst();
    var sample = fragment.videoSamples().getFirst();
    var bytes =
        ByteBuffer.wrap(
                fragment.mediaDataBox(), sample.mediaDataPosition(), Math.toIntExact(sample.size()))
            .slice();
    return InitializationSegmentRewrite.rewrite(
        encode(TEN_BIT_CODEC_STRING), stream.initializationSegment(), bytes);
  }

  private static InitializationSegment initializationSegmentOf(byte[] output) {
    try {
      return Mp4Stream.read(Mp4Stream.readerOf(output)).initializationSegment();
    } catch (IOException e) {
      throw new AssertionError(e);
    }
  }

  // The sequence header OBU of every keyframe of the output, as hexadecimal digits.
  private static List<String> keyframeSequenceHeadersOf(byte[] output) {
    var movie = Mp4Samples.read(output);
    return movie.samplesOf(movie.videoTrack()).stream()
        .filter(Mp4Samples.Sample::isSync)
        .map(sample -> sequenceHeaderOf(sample.data()))
        .toList();
  }

  // Walks the temporal unit's OBUs (AV1 5.3), each with a size field, to its sequence header.
  private static String sequenceHeaderOf(ByteBuffer data) {
    var temporalUnit = new byte[data.remaining()];
    data.duplicate().get(temporalUnit);
    var reading = ByteBuffer.wrap(temporalUnit);
    while (reading.hasRemaining()) {
      var start = reading.position();
      var header = Byte.toUnsignedInt(reading.get());
      assertThat(header & 0x02).as("obu_has_size_field").isNotZero();
      if ((header & 0x04) != 0) {
        reading.get();
      }

      var size = 0L;
      for (var shift = 0; ; shift += 7) {
        var octet = Byte.toUnsignedInt(reading.get());
        size |= (long) (octet & 0x7F) << shift;
        if ((octet & 0x80) == 0) {
          break;
        }
      }

      reading.position(reading.position() + (int) size);
      if (((header >> 3) & 0x0F) == OBU_SEQUENCE_HEADER) {
        return HexFormat.of().formatHex(temporalUnit, start, reading.position());
      }
    }

    throw new AssertionError("the keyframe holds no sequence header");
  }

  private static String configurationObusOf(byte[] initializationSegment) {
    return configurationObusOf(new InitializationSegment(initializationSegment));
  }

  private static String configurationObusOf(InitializationSegment segment) {
    var av1C =
        CodecConfigurationReader.firstVideoSampleEntryOf(segment)
            .orElseThrow()
            .after(CodecConfigurationReader.VISUAL_SAMPLE_ENTRY_BYTES)
            .requiredChild("av1C");
    var configurationObus = av1C.after(4).body();
    var bytes = new byte[configurationObus.remaining()];
    configurationObus.duplicate().get(bytes);
    return HexFormat.of().formatHex(bytes);
  }

  private static String codecStringOf(byte[] initializationSegment) {
    return codecStringOf(new InitializationSegment(initializationSegment));
  }

  private static String codecStringOf(InitializationSegment segment) {
    return CodecConfigurationReader.videoOf(
            CodecConfigurationReader.firstVideoSampleEntryOf(segment).orElseThrow())
        .codecString();
  }

  private static void run(List<String> command) throws Exception {
    var process =
        new ProcessBuilder(command)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    assertThat(process.waitFor(OUTCOME_LIMIT.toSeconds(), TimeUnit.SECONDS))
        .as("%s ends", command)
        .isTrue();
    assertThat(process.exitValue()).as("%s exits", command).isZero();
  }
}
