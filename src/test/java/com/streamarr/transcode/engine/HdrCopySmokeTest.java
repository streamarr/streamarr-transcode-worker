package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs the producer over the FFmpeg on {@code PATH}, the locked build in CI, to check the
 * initialization segment of a copy whose output the server planned. The sources are synthetic
 * libx265 streams at 64x36 and 24 fps with a keyframe every second: one PQ stream whose samples
 * carry the mastering display and content light level messages, written by libx265 from the values
 * of {@link PlannedCopyOutputs}.
 */
@Tag("SmokeTest")
@DisplayName("HDR Copy Smoke Tests")
class HdrCopySmokeTest {

  private static final int SOURCE_SECONDS = 4;
  private static final int PERIOD_SECONDS = 1;
  // libx265's 64x36 Main 10 at level 1.0 in the Main tier.
  private static final String CODEC_STRING = "hvc1.2.4.L30.90";
  private static final Duration OUTCOME_LIMIT = Duration.ofSeconds(60);
  private static final Duration FRAGMENTATION_TARGET = Duration.ofMillis(250);
  private static final int PREFIX_SEI = 39;
  private static final int SUFFIX_SEI = 40;
  private static final int MASTERING_DISPLAY = 137;
  private static final int CONTENT_LIGHT_LEVEL = 144;
  private static final List<String> SAMPLE_ENTRY_PATH =
      List.of("moov", "trak", "mdia", "minf", "stbl", "stsd");

  @TempDir static Path root;
  private static Path hdr10;

  @BeforeAll
  static void writeTheSources() throws Exception {
    hdr10 =
        encoded(
            "hdr10",
            "smpte2084",
            ":master-display=" + x265MasteringDisplay() + ":max-cll=1000,400");
  }

  @ParameterizedTest(name = "starting at segment {0}")
  @ValueSource(ints = {0, 2})
  @DisplayName(
      "Should deliver the planned mastering display and content light level each as its own SEI"
          + " message in the hvcC when the copy plans an HDR10 output")
  void shouldDeliverThePlannedStaticMetadataAsSeiMessagesInTheHvccWhenTheCopyPlansHdr10(
      int firstSegment) throws Exception {
    var delivered = copied(hdr10, hdr10Copy(both()), firstSegment);

    assertThat(seiMessagesOfTheRecord(delivered))
        .containsExactly(
            List.of(new SeiMessage(MASTERING_DISPLAY, masteringDisplayPayload())),
            List.of(new SeiMessage(CONTENT_LIGHT_LEVEL, contentLightLevelPayload())));
  }

  @Test
  @DisplayName(
      "Should deliver the same initialization segment from attempts that start at different"
          + " keyframes when the copy plans an HDR10 output")
  void shouldDeliverTheSameInitializationSegmentFromAttemptsThatStartAtDifferentKeyframes()
      throws Exception {
    var fromStart = copied(hdr10, hdr10Copy(both()), 0);
    var replacement = copied(hdr10, hdr10Copy(both()), 2);

    assertThat(replacement).isEqualTo(fromStart);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("partialStaticMetadata")
  @DisplayName(
      "Should deliver only the static HDR messages whose values the copy plans when it plans one"
          + " or neither")
  void shouldDeliverOnlyThePlannedStaticHdrMessagesWhenTheCopyPlansOneOrNeither(
      PartialStaticMetadata partial) throws Exception {
    var delivered = copied(hdr10, hdr10Copy(partial.planned()), 0);

    assertThat(seiMessagesOfTheRecord(delivered)).containsExactlyElementsOf(partial.messages());
  }

  static Stream<PartialStaticMetadata> partialStaticMetadata() {
    return Stream.of(
        new PartialStaticMetadata(
            "only the mastering display",
            PlannedCopyOutputs.pqOutputBuilder()
                .masteringDisplay(Optional.of(PlannedCopyOutputs.MASTERING_DISPLAY))
                .build(),
            List.of(List.of(new SeiMessage(MASTERING_DISPLAY, masteringDisplayPayload())))),
        new PartialStaticMetadata(
            "only the content light level",
            PlannedCopyOutputs.pqOutputBuilder()
                .contentLightLevel(Optional.of(PlannedCopyOutputs.CONTENT_LIGHT_LEVEL))
                .build(),
            List.of(List.of(new SeiMessage(CONTENT_LIGHT_LEVEL, contentLightLevelPayload())))),
        new PartialStaticMetadata(
            "neither message", PlannedCopyOutputs.pqOutputBuilder().build(), List.of()));
  }

  // The planned output and the SEI messages of each SEI NAL unit in the delivered hvcC.
  record PartialStaticMetadata(
      String name, PlannedCopyOutput planned, List<List<SeiMessage>> messages) {

    @Override
    public String toString() {
      return name;
    }
  }

  /** One SEI message: its payload type and its payload, without emulation prevention bytes. */
  record SeiMessage(int payloadType, String payloadHex) {}

  private static PlannedCopyOutput both() {
    return PlannedCopyOutputs.pqOutputBuilder()
        .masteringDisplay(Optional.of(PlannedCopyOutputs.MASTERING_DISPLAY))
        .contentLightLevel(Optional.of(PlannedCopyOutputs.CONTENT_LIGHT_LEVEL))
        .build();
  }

  private static VideoDecision.Copy hdr10Copy(PlannedCopyOutput planned) {
    return VideoDecision.Copy.builder()
        .streamIndex(0)
        .codecString(CODEC_STRING)
        .keepsPacketBytes(true)
        .plannedOutput(Optional.of(planned))
        .build();
  }

  // x265 takes the primaries as G, B, R and the white point, then the luminances.
  private static String x265MasteringDisplay() {
    var display = PlannedCopyOutputs.MASTERING_DISPLAY;
    return "G(%d,%d)B(%d,%d)R(%d,%d)WP(%d,%d)L(%d,%d)"
        .formatted(
            display.green().x(),
            display.green().y(),
            display.blue().x(),
            display.blue().y(),
            display.red().x(),
            display.red().y(),
            display.whitePoint().x(),
            display.whitePoint().y(),
            display.maxLuminance(),
            display.minLuminance());
  }

  // ITU-T H.265 D.2.28: green, blue and red, the white point, then the luminances.
  private static String masteringDisplayPayload() {
    return "33C286C41D4C0BB884D03E803D134042" + "00989680" + "00000032";
  }

  // ITU-T H.265 D.2.35: MaxCLL 1000 and MaxFALL 400.
  private static String contentLightLevelPayload() {
    return "03E80190";
  }

  private static Path encoded(String name, String transfer, String extraParameters)
      throws Exception {
    var elementary = root.resolve(name + ".hevc");
    runFfmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x36:rate=24:duration=" + SOURCE_SECONDS,
            "-c:v",
            "libx265",
            "-preset",
            "ultrafast",
            "-pix_fmt",
            "yuv420p10le",
            "-x265-params",
            "keyint=24:min-keyint=24:scenecut=0:bframes=0:log-level=error:colorprim=bt2020"
                + ":transfer="
                + transfer
                + ":colormatrix=bt2020nc"
                + extraParameters,
            "-f",
            "hevc"),
        elementary);
    var muxed = root.resolve(name + ".mp4");
    runFfmpeg(
        List.of(
            "-f",
            "hevc",
            "-framerate",
            "24",
            "-i",
            elementary.toString(),
            "-c",
            "copy",
            "-tag:v",
            "hvc1"),
        muxed);
    return muxed;
  }

  private static byte[] copied(Path source, VideoDecision.Copy copy, int firstSegment)
      throws Exception {
    var capabilities = new TranscodeCapabilityService("ffmpeg", ProcessBuilder::start);
    capabilities.detectCapabilities();
    assertThat(capabilities.isFfmpegAvailable()).as(capabilities.getUnavailableReason()).isTrue();
    var engine =
        FfmpegTranscodeEngine.builder()
            .commandBuilder(new FfmpegCommandBuilder("ffmpeg", FRAGMENTATION_TARGET))
            .capabilityService(capabilities)
            .launcher(new ProcessBuilderLauncher())
            .encoderStallTimeout(FfmpegTranscodeEngine.DEFAULT_ENCODER_STALL_TIMEOUT)
            .build();
    var sink = new RecordingSegmentSink();

    var producer =
        engine.startProducer(
            request(source, copy, firstSegment), sink, SegmentMemoryBudget.forSlots(1));

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isInstanceOf(Completed.class);
    return sink.contentOf("init.mp4");
  }

  private static TranscodeRequest request(Path source, VideoDecision.Copy copy, int firstSegment) {
    var seek = Optional.<Duration>empty();
    if (firstSegment > 0) {
      seek = Optional.of(Duration.ofSeconds((long) firstSegment * PERIOD_SECONDS));
    }

    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(source)
        .targetSegmentDuration(PERIOD_SECONDS)
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.REMUX)
                .videoCodecFamily("hevc")
                .videoDecision(copy)
                .audioDecision(AudioDecision.builder().mode(AudioMode.NONE).build())
                .subtitleDecision(SubtitleDecisions.EXCLUDED)
                .build())
        .width(64)
        .height(36)
        .bitrate(100_000L)
        .startSequenceNumber(firstSegment)
        .mediaSegmentCount(SOURCE_SECONDS / PERIOD_SECONDS)
        .seekPosition(seek)
        .build();
  }

  // The messages of each SEI NAL unit in the hvcC of the delivered sample entry, in order.
  private static List<List<SeiMessage>> seiMessagesOfTheRecord(byte[] initialization) {
    var entry = sampleEntry(initialization);
    var hvcC = ByteBuffer.wrap(child(entry, 78, "hvcC"));
    hvcC.position(22);
    var arrays = Byte.toUnsignedInt(hvcC.get());
    var messages = new ArrayList<List<SeiMessage>>();
    for (var array = 0; array < arrays; array++) {
      var type = Byte.toUnsignedInt(hvcC.get()) & 0x3F;
      var units = Short.toUnsignedInt(hvcC.getShort());
      for (var unit = 0; unit < units; unit++) {
        var bytes = new byte[Short.toUnsignedInt(hvcC.getShort())];
        hvcC.get(bytes);
        if (type == PREFIX_SEI || type == SUFFIX_SEI) {
          messages.add(seiMessagesOf(bytes));
        }
      }
    }

    return messages;
  }

  private static List<SeiMessage> seiMessagesOf(byte[] nalUnit) {
    var rbsp = new ByteArrayOutputStream();
    var zeros = 0;
    for (var index = 2; index < nalUnit.length; index++) {
      var octet = Byte.toUnsignedInt(nalUnit[index]);
      if (zeros >= 2 && octet == 3) {
        zeros = 0;
        continue;
      }

      zeros = octet == 0 ? zeros + 1 : 0;
      rbsp.write(octet);
    }

    var reading = ByteBuffer.wrap(rbsp.toByteArray());
    var messages = new ArrayList<SeiMessage>();
    while (reading.remaining() > 1) {
      var type = Byte.toUnsignedInt(reading.get());
      var payload = new byte[Byte.toUnsignedInt(reading.get())];
      reading.get(payload);
      messages.add(new SeiMessage(type, HexFormat.of().withUpperCase().formatHex(payload)));
    }

    assertThat(Byte.toUnsignedInt(reading.get())).as("the RBSP stop byte").isEqualTo(0x80);
    return messages;
  }

  // The first sample entry of the first track: its type, then its fields and child boxes.
  static Box sampleEntry(byte[] initialization) {
    var box = new Box("file", initialization);
    for (var type : SAMPLE_ENTRY_PATH) {
      var fields = type.equals("stsd") ? 8 : 0;
      box = new Box(type, child(box, 0, type));
      if (fields > 0) {
        return firstChild(box, fields);
      }
    }

    throw new AssertionError("unreachable");
  }

  // The body of the first child of the type, after the box's first fields.
  static byte[] child(Box box, int fields, String type) {
    return children(box, fields).stream()
        .filter(child -> child.type().equals(type))
        .findFirst()
        .map(Box::body)
        .orElseThrow(() -> new AssertionError(box.type() + " holds no " + type));
  }

  static List<Box> children(Box box, int fields) {
    var children = new ArrayList<Box>();
    var reading = ByteBuffer.wrap(box.body());
    reading.position(fields);
    while (reading.remaining() >= 8) {
      var size = (int) Integer.toUnsignedLong(reading.getInt());
      var type = new byte[4];
      reading.get(type);
      var body = new byte[size - 8];
      reading.get(body);
      children.add(new Box(new String(type, StandardCharsets.US_ASCII), body));
    }

    return children;
  }

  private static Box firstChild(Box box, int fields) {
    return children(box, fields).getFirst();
  }

  /** A box's type and body. */
  record Box(String type, byte[] body) {}

  private static void runFfmpeg(List<String> arguments, Path output) throws Exception {
    var command = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y"));
    command.addAll(arguments);
    command.add(output.toString());
    runToCompletion(command);
  }

  private static String runToCompletion(List<String> command)
      throws IOException, InterruptedException {
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(OUTCOME_LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).as("%s: %s", command, output).isZero();
    return output;
  }
}
