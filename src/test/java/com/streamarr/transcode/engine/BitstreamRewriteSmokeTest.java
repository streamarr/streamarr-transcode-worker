package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.fixtures.Mp4Samples;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs the producer over the FFmpeg on {@code PATH}, the locked build in CI, to check the bitstream
 * rewrites end to end. The source is a synthetic libx265 PQ stream at 64x36 and 24 fps with a
 * keyframe every second and a decoded picture hash in a suffix SEI NAL unit behind every slice,
 * into which the test writes a single-track Dolby Vision profile 7 layout: several NAL units of
 * type 63 in every picture's sample, on both sides of its slice, a synthetic FEL RPU of type 62 in
 * front of the suffix SEI NAL unit, and a Dolby Vision configuration record in the MP4 sample
 * entry. No unit of type 63 holds real enhancement-layer data, so the stream checks the rewrites'
 * plumbing and the RPU conversion, not a picture.
 */
@Tag("SmokeTest")
@DisplayName("Bitstream Rewrite Smoke Tests")
class BitstreamRewriteSmokeTest {

  private static final int SOURCE_SECONDS = 4;
  private static final int PERIOD_SECONDS = 1;
  // libx265's 64x36 Main 10 at level 1.0 in the Main tier.
  private static final String CODEC_STRING = "hvc1.2.4.L30.90";
  private static final Duration OUTCOME_LIMIT = Duration.ofSeconds(60);
  private static final Duration FRAGMENTATION_TARGET = Duration.ofMillis(250);
  private static final int FIRST_NON_PICTURE_TYPE = 32;
  private static final int SUFFIX_SEI_TYPE = 40;
  private static final int RPU_TYPE = 62;
  private static final int ENHANCEMENT_LAYER_TYPE = 63;
  // The hvcC that FFmpeg writes declares 4-byte NAL unit lengths.
  private static final int LENGTH_SIZE = 4;
  private static final int NAL_UNIT_HEADER_BYTES = 2;
  // Profile 7 at level 1 with an RPU, an enhancement layer and a base layer, compatibility id 6.
  private static final byte[] DOLBY_VISION_RECORD = {
    0x01, 0x00, 0x0E, 0x0F, 0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
  };
  // The normalized record of the planned output: profile 8, level 6, an RPU and a base layer,
  // compatibility 1 and compression 0.
  private static final String PROFILE_8_LEVEL_6_PQ_RECORD = "0100103510" + "00".repeat(19);
  // A synthetic FEL RPU behind the NAL unit header of type 62.
  private static final byte[] FEL_RPU =
      IsoBoxes.concat(
          new byte[] {(byte) (RPU_TYPE << 1), 0x01}, SyntheticRpus.profile7().escaped());

  @TempDir static Path root;
  private static Path source;

  @BeforeAll
  static void writeTheSource() throws Exception {
    var encoded = root.resolve("encoded.hevc");
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
            "keyint=24:min-keyint=24:scenecut=0:bframes=0:aud=1:hash=2:log-level=error"
                + ":colorprim=bt2020:transfer=smpte2084:colormatrix=bt2020nc",
            "-f",
            "hevc"),
        encoded);
    var layered = root.resolve("layered.hevc");
    Files.write(layered, withDolbyVisionUnits(Files.readAllBytes(encoded)));
    var muxed = root.resolve("layered.mp4");
    runFfmpeg(
        List.of(
            "-f",
            "hevc",
            "-framerate",
            "24",
            "-i",
            layered.toString(),
            "-c",
            "copy",
            "-tag:v",
            "hvc1"),
        muxed);
    source = root.resolve("profile-7.mp4");
    Files.write(source, withDolbyVisionRecord(Files.readAllBytes(muxed)));
    assertThat(sideDataOf(source)).contains("DOVI configuration record");
  }

  @ParameterizedTest(name = "starting at segment {0}")
  @ValueSource(ints = {0, 2})
  @DisplayName(
      "Should deliver no NAL unit of type 62 or 63 and no configuration record when the copy strips"
          + " Dolby Vision from samples with several enhancement-layer units")
  void shouldDeliverNoNalUnitOf62Or63AndNoRecordWhenTheCopyStripsDolbyVision(int firstSegment) {
    var delivered = copied(EnumSet.of(BitstreamRewrite.STRIP_DOLBY_VISION), firstSegment);

    assertThat(delivered.names()).containsExactlyElementsOf(namesFrom(firstSegment));
    assertThat(delivered.recordTypes()).isEmpty();
    assertThat(delivered.nalUnitTypesPerSample())
        .isNotEmpty()
        .allSatisfy(
            types ->
                assertThat(types)
                    .anyMatch(type -> type < FIRST_NON_PICTURE_TYPE)
                    .doesNotContain(RPU_TYPE, ENHANCEMENT_LAYER_TYPE));
  }

  // The source holds each RPU in front of the picture's suffix SEI NAL unit, the layout of the
  // worker fixture hevc-dovi-p81-rpus-before-suffix-sei.hevc, which the locked build's decoder
  // reads no RPU from (streamarr/streamarr-server#651).
  @Test
  @DisplayName(
      "Should deliver the RPUs in front of the suffix SEI NAL units, the enhancement-layer units and"
          + " the configuration record when the copy lists no rewrite")
  void shouldDeliverTheRpusEnhancementLayerUnitsAndRecordWhenTheCopyListsNoRewrite() {
    var delivered = copied(Set.of(), 0);

    assertThat(delivered.recordTypes()).containsExactly("dvcC");
    assertThat(delivered.nalUnitTypesPerSample())
        .isNotEmpty()
        .allSatisfy(
            types -> {
              assertThat(types).containsOnlyOnce(RPU_TYPE);
              assertThat(types.indexOf(RPU_TYPE)).isLessThan(types.lastIndexOf(SUFFIX_SEI_TYPE));
              assertThat(types.getLast()).isEqualTo(SUFFIX_SEI_TYPE);
              assertThat(types.stream().filter(type -> type == ENHANCEMENT_LAYER_TYPE)).hasSize(4);
            });
  }

  // ADR 0036 and streamarr/streamarr-server#651: every picture keeps exactly one RPU, converted to
  // profile 8.1 as libdovi's mode 2 converts it, as the last NAL unit of its sample, behind the
  // suffix SEI NAL unit, so the locked build's decoder reads it with every frame.
  @ParameterizedTest(name = "starting at segment {0}")
  @ValueSource(ints = {0, 2})
  @DisplayName(
      "Should deliver each picture's RPU converted to profile 8.1 as its sample's last NAL unit, no"
          + " enhancement-layer unit and the profile 8.1 record when the copy converts profile 7")
  void shouldDeliverEachRpuConvertedToProfile81LastAndTheProfile81RecordWhenTheCopyConvertsProfile7(
      int firstSegment) throws Exception {
    var delivered = copied(profile7Conversion(), firstSegment);

    assertThat(delivered.names()).containsExactlyElementsOf(namesFrom(firstSegment));
    assertThat(delivered.recordTypes()).containsExactly("dvvC");
    assertThat(delivered.dolbyVisionRecord()).isEqualTo(PROFILE_8_LEVEL_6_PQ_RECORD);
    assertThat(delivered.nalUnitsPerSample())
        .isNotEmpty()
        .allSatisfy(
            units -> {
              var types = units.stream().map(NalUnit::type).toList();
              assertThat(types)
                  .containsOnlyOnce(RPU_TYPE)
                  .contains(SUFFIX_SEI_TYPE)
                  .doesNotContain(ENHANCEMENT_LAYER_TYPE);
              assertThat(types.getLast()).isEqualTo(RPU_TYPE);
              var rpu = DolbyVisionRpu.parse(ByteBuffer.wrap(units.getLast().payload()));
              assertThat(rpu.profile()).isEqualTo(8);
              assertThat(rpu.header().disableResidual()).isTrue();
              assertThat(rpu.mapping().orElseThrow().nlq()).isEmpty();
            });
    var frames = probedFrames(delivered);
    assertThat(frames.total()).isEqualTo(delivered.nalUnitsPerSample().size());
    assertThat(frames.withDolbyVision()).isEqualTo(frames.total());
  }

  // ADR 0037: every attempt of a variant writes the same initialization segment.
  @Test
  @DisplayName(
      "Should write the same initialization segment when the converting copy starts after a seek")
  void shouldWriteTheSameInitializationSegmentWhenTheConvertingCopyStartsAfterASeek() {
    var fromTheStart = copied(profile7Conversion(), 0);

    var afterASeek = copied(profile7Conversion(), 2);

    assertThat(afterASeek.initialization()).isEqualTo(fromTheStart.initialization());
  }

  // The locked build's MP4 muxer writes no record without -strict unofficial, which the strip
  // rewrite leaves out; it applies no bitstream filter option of its own. Every filter that a copy
  // command names must exist in the build with each option that it sets.
  @Test
  @DisplayName(
      "Should find every bitstream filter option that a copy command names in the FFmpeg build")
  void shouldFindEveryBitstreamFilterOptionThatACopyCommandNamesInTheFfmpegBuild()
      throws Exception {
    var builder = new FfmpegCommandBuilder("ffmpeg", FRAGMENTATION_TARGET);
    var named = new HashSet<String>();
    for (var rewrites :
        List.of(
            Set.<BitstreamRewrite>of(),
            EnumSet.of(BitstreamRewrite.STRIP_DOLBY_VISION, BitstreamRewrite.STRIP_HDR10_PLUS))) {
      for (var codecString : List.of(CODEC_STRING, "avc1.64001F", "av01.0.08M.08")) {
        for (var keepsPacketBytes : List.of(false, true)) {
          var copy =
              VideoDecision.Copy.builder()
                  .streamIndex(0)
                  .codecString(codecString)
                  .keepsPacketBytes(keepsPacketBytes)
                  .bitstreamRewrites(rewrites)
                  .build();
          var job =
              TranscodeJob.builder().request(request(copy, 0).build()).videoEncoder("copy").build();
          var pipeline = (FfmpegPipeline.SingleProcess) builder.buildPipeline(job);
          named.addAll(bitstreamFiltersOf(pipeline.command()));
        }
      }
    }

    assertThat(named).isNotEmpty();
    for (var filter : named) {
      var parts = filter.split("=", 2);
      var help = ffmpegOutput(List.of("-hide_banner", "-h", "bsf=" + parts[0]));
      assertThat(help).as("the help of %s", parts[0]).contains("Bit stream filter " + parts[0]);
      for (var option : optionsOf(parts)) {
        assertThat(help).as("option %s of %s", option, parts[0]).contains("-" + option + " ");
      }
    }
  }

  private static Delivered copied(Set<BitstreamRewrite> rewrites, int firstSegment) {
    return copied(copyBuilder().bitstreamRewrites(rewrites).build(), firstSegment);
  }

  private static Delivered copied(VideoDecision.Copy copy, int firstSegment) {
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
            request(copy, firstSegment).build(), sink, SegmentMemoryBudget.forSlots(1));

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isInstanceOf(Completed.class);
    return Delivered.of(sink);
  }

  private static VideoDecision.Copy.CopyBuilder copyBuilder() {
    return VideoDecision.Copy.builder()
        .streamIndex(0)
        .codecString(CODEC_STRING)
        .keepsPacketBytes(true);
  }

  // The copy that the server plans for a single-track profile 7 source and a client that declares
  // profile 8.1: the conversion and the normalized record of profile 8.1 at level 6 on the PQ base.
  private static VideoDecision.Copy profile7Conversion() {
    return copyBuilder()
        .bitstreamRewrites(
            EnumSet.of(
                BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1,
                BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD))
        .plannedOutput(
            Optional.of(
                PlannedCopyOutputs.pqOutputBuilder()
                    .dolbyVision(Optional.of(PlannedCopyOutputs.profile81Output()))
                    .build()))
        .build();
  }

  private static TranscodeRequest.TranscodeRequestBuilder request(
      VideoDecision.Copy copy, int firstSegment) {
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
        .seekPosition(seek);
  }

  private static List<String> namesFrom(int firstSegment) {
    return Stream.concat(
            Stream.of("init.mp4"),
            Stream.iterate(
                    firstSegment, segment -> segment < SOURCE_SECONDS, segment -> segment + 1)
                .map(segment -> "segment" + segment + ".m4s"))
        .toList();
  }

  // Every NAL unit of the encode, in order, with each access unit's type 63 units written after its
  // delimiter and parameter sets, in front of its slice, and behind it with the RPU between them;
  // the slice's suffix SEI NAL unit follows them all.
  private static byte[] withDolbyVisionUnits(byte[] annexB) {
    var written = new ByteArrayOutputStream();
    var picture = 0;
    for (var unit : nalUnitsOf(annexB)) {
      var type = (Byte.toUnsignedInt(unit[0]) >> 1) & 0x3F;
      if (type < FIRST_NON_PICTURE_TYPE) {
        writeAnnexB(written, unitOf(ENHANCEMENT_LAYER_TYPE, picture));
        writeAnnexB(written, unit);
        writeAnnexB(written, unitOf(ENHANCEMENT_LAYER_TYPE, picture));
        writeAnnexB(written, unitOf(ENHANCEMENT_LAYER_TYPE, picture));
        writeAnnexB(written, FEL_RPU);
        writeAnnexB(written, unitOf(ENHANCEMENT_LAYER_TYPE, picture));
        picture++;
        continue;
      }

      writeAnnexB(written, unit);
    }

    return written.toByteArray();
  }

  private static List<byte[]> nalUnitsOf(byte[] annexB) {
    var starts = new ArrayList<Integer>();
    for (var index = 0; index + 2 < annexB.length; index++) {
      if (annexB[index] == 0 && annexB[index + 1] == 0 && annexB[index + 2] == 1) {
        starts.add(index + 3);
      }
    }

    var units = new ArrayList<byte[]>();
    for (var index = 0; index < starts.size(); index++) {
      var end = index + 1 < starts.size() ? starts.get(index + 1) - 3 : annexB.length;
      while (end > starts.get(index) && annexB[end - 1] == 0) {
        end--;
      }

      units.add(Arrays.copyOfRange(annexB, starts.get(index), end));
    }

    return units;
  }

  private static byte[] unitOf(int type, int picture) {
    return new byte[] {(byte) (type << 1), 0x01, 0x02, (byte) picture, 0x7F};
  }

  private static void writeAnnexB(ByteArrayOutputStream target, byte[] unit) {
    target.writeBytes(new byte[] {0, 0, 0, 1});
    target.writeBytes(unit);
  }

  // The MP4 with a dvcC box appended to its hvc1 sample entry. FFmpeg writes the moov behind the
  // mdat, so no chunk offset moves.
  private static byte[] withDolbyVisionRecord(byte[] mp4) {
    var dvcC = box("dvcC", DOLBY_VISION_RECORD);
    var path = List.of("moov", "trak", "mdia", "minf", "stbl", "stsd", "hvc1");
    var rebuilt = new ByteArrayOutputStream();
    var position = 0;
    var passedMdat = false;
    while (position < mp4.length) {
      var size = (int) readU32(mp4, position);
      var type = typeAt(mp4, position);
      var bytes = Arrays.copyOfRange(mp4, position, position + size);
      passedMdat |= type.equals("mdat");
      if (type.equals("moov")) {
        assertThat(passedMdat).as("the moov follows the mdat").isTrue();
        bytes = appended(bytes, path.subList(1, path.size()), dvcC);
      }

      rebuilt.writeBytes(bytes);
      position += size;
    }

    return rebuilt.toByteArray();
  }

  // The box with the child appended to the last box of the path below it.
  private static byte[] appended(byte[] box, List<String> path, byte[] child) {
    var type = typeAt(box, 0);
    var fields =
        switch (type) {
          case "stsd" -> 8;
          case "hvc1" -> 78;
          default -> 0;
        };
    var body = new ByteArrayOutputStream();
    body.write(box, 8, fields);
    var position = 8 + fields;
    while (position < box.length) {
      var size = (int) readU32(box, position);
      var nested = Arrays.copyOfRange(box, position, position + size);
      if (!path.isEmpty() && typeAt(nested, 0).equals(path.getFirst())) {
        nested = appended(nested, path.subList(1, path.size()), child);
      }

      body.writeBytes(nested);
      position += size;
    }

    if (path.isEmpty()) {
      body.writeBytes(child);
    }

    return box(type, body.toByteArray());
  }

  private static byte[] box(String type, byte[] body) {
    return ByteBuffer.allocate(8 + body.length)
        .putInt(8 + body.length)
        .put(type.getBytes(StandardCharsets.US_ASCII))
        .put(body)
        .array();
  }

  private static long readU32(byte[] bytes, int position) {
    return Integer.toUnsignedLong(ByteBuffer.wrap(bytes, position, 4).getInt());
  }

  private static String typeAt(byte[] bytes, int position) {
    return new String(bytes, position + 4, 4, StandardCharsets.US_ASCII);
  }

  private static List<String> bitstreamFiltersOf(List<String> command) {
    var filters = new ArrayList<String>();
    for (var index = 0; index + 1 < command.size(); index++) {
      if (command.get(index).startsWith("-bsf")) {
        filters.addAll(Arrays.asList(command.get(index + 1).split(",")));
      }
    }

    return filters;
  }

  private static List<String> optionsOf(String[] filter) {
    if (filter.length < 2) {
      return List.of();
    }

    return Arrays.stream(filter[1].split(":")).map(option -> option.split("=", 2)[0]).toList();
  }

  private static String sideDataOf(Path media) throws Exception {
    return runToCompletion(
        List.of(
            "ffprobe",
            "-v",
            "error",
            "-show_entries",
            "stream_side_data=side_data_type",
            "-of",
            "default=nw=1",
            media.toString()));
  }

  // The frames that the locked build's decoder reads from the delivered segments, and how many of
  // them carry Dolby Vision side data, which it attaches only from an RPU that ends the packet.
  private static ProbedFrames probedFrames(Delivered delivered) throws Exception {
    var media = root.resolve("delivered-" + UUID.randomUUID() + ".mp4");
    Files.write(media, delivered.media());
    var lines =
        runToCompletion(
                List.of(
                    "ffprobe",
                    "-v",
                    "error",
                    "-select_streams",
                    "v:0",
                    "-show_entries",
                    "frame=pts:frame_side_data=side_data_type",
                    "-of",
                    "default=nw=1",
                    media.toString()))
            .lines()
            .toList();
    var total = 0;
    var withDolbyVision = 0;
    var currentHasDolbyVision = false;
    for (var line : lines) {
      if (line.startsWith("pts=")) {
        total++;
        currentHasDolbyVision = false;
        continue;
      }

      if (line.contains("Dolby Vision") && !currentHasDolbyVision) {
        withDolbyVision++;
        currentHasDolbyVision = true;
      }
    }

    return new ProbedFrames(total, withDolbyVision);
  }

  private static void runFfmpeg(List<String> arguments, Path output) throws Exception {
    var command = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y"));
    command.addAll(arguments);
    command.add(output.toString());
    runToCompletion(command);
  }

  private static String ffmpegOutput(List<String> arguments) throws Exception {
    var command = new ArrayList<>(List.of("ffmpeg"));
    command.addAll(arguments);
    return runToCompletion(command);
  }

  private static String runToCompletion(List<String> command)
      throws IOException, InterruptedException {
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(OUTCOME_LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).as("%s: %s", command, output).isZero();
    return output;
  }

  private record ProbedFrames(int total, int withDolbyVision) {}

  /** A NAL unit of a delivered sample: its type and its payload after the 2-byte header. */
  private record NalUnit(int type, byte[] payload) {}

  /**
   * What the producer delivered: the segment names, the initialization segment, every segment's
   * bytes in order, and the NAL units of each video sample.
   */
  private record Delivered(
      List<String> names,
      byte[] initialization,
      byte[] media,
      List<List<NalUnit>> nalUnitsPerSample) {

    private static final Pattern RECORD_TYPES = Pattern.compile("dv[cvw]C");

    static Delivered of(RecordingSegmentSink sink) {
      var media = sink.acceptedBytes();
      var movie = Mp4Samples.read(media);
      var units =
          movie.samplesOf(movie.videoTrack()).stream()
              .map(sample -> nalUnitsOf(sample.data()))
              .toList();
      return new Delivered(sink.acceptedNames(), sink.contentOf("init.mp4"), media, units);
    }

    /** The types of the Dolby Vision record boxes that the initialization segment holds. */
    List<String> recordTypes() {
      var text = new String(initialization, StandardCharsets.ISO_8859_1);
      return RECORD_TYPES.matcher(text).results().map(MatchResult::group).toList();
    }

    /** The body of the initialization segment's one Dolby Vision record box, in hexadecimal. */
    String dolbyVisionRecord() {
      var text = new String(initialization, StandardCharsets.ISO_8859_1);
      var matcher = RECORD_TYPES.matcher(text);
      assertThat(matcher.find()).as("a Dolby Vision record box").isTrue();
      var body = matcher.end();
      return HexFormat.of()
          .formatHex(initialization, body, body + DOLBY_VISION_RECORD.length)
          .toUpperCase();
    }

    List<List<Integer>> nalUnitTypesPerSample() {
      return nalUnitsPerSample.stream()
          .map(units -> units.stream().map(NalUnit::type).toList())
          .toList();
    }

    private static List<NalUnit> nalUnitsOf(ByteBuffer sample) {
      var units = new ArrayList<NalUnit>();
      var data = sample.duplicate();
      while (data.remaining() >= LENGTH_SIZE) {
        var length = data.getInt();
        var type = (Byte.toUnsignedInt(data.get(data.position())) >> 1) & 0x3F;
        var payload = new byte[length - NAL_UNIT_HEADER_BYTES];
        data.position(data.position() + NAL_UNIT_HEADER_BYTES);
        data.get(payload);
        units.add(new NalUnit(type, payload));
      }

      return units;
    }
  }
}
