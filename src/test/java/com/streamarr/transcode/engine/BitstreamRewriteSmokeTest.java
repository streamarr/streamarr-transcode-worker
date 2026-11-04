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
 * keyframe every second, into which the test writes a single-track Dolby Vision profile 7 layout:
 * several NAL units of type 63 in every picture's sample, on both sides of its slice, an RPU of
 * type 62, and a Dolby Vision configuration record in the MP4 sample entry. No unit holds real
 * enhancement-layer or RPU data, so the stream checks the rewrite's plumbing, not a picture.
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
  private static final int RPU_TYPE = 62;
  private static final int ENHANCEMENT_LAYER_TYPE = 63;
  // The hvcC that FFmpeg writes declares 4-byte NAL unit lengths.
  private static final int LENGTH_SIZE = 4;
  // Profile 7 at level 1 with an RPU, an enhancement layer and a base layer, compatibility id 6.
  private static final byte[] DOLBY_VISION_RECORD = {
    0x01, 0x00, 0x0E, 0x0F, 0x60, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0
  };

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
            "keyint=24:min-keyint=24:scenecut=0:bframes=0:aud=1:log-level=error:colorprim=bt2020"
                + ":transfer=smpte2084:colormatrix=bt2020nc",
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
  void shouldDeliverNoNalUnitOf62Or63AndNoRecordWhenTheCopyStripsDolbyVision(int firstSegment)
      throws Exception {
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

  @Test
  @DisplayName(
      "Should deliver the RPUs, the enhancement-layer units and the configuration record when the"
          + " copy lists no rewrite")
  void shouldDeliverTheRpusEnhancementLayerUnitsAndRecordWhenTheCopyListsNoRewrite()
      throws Exception {
    var delivered = copied(Set.of(), 0);

    assertThat(delivered.recordTypes()).containsExactly("dvcC");
    assertThat(delivered.nalUnitTypesPerSample())
        .isNotEmpty()
        .allSatisfy(
            types ->
                assertThat(types)
                    .containsOnlyOnce(RPU_TYPE)
                    .filteredOn(type -> type == ENHANCEMENT_LAYER_TYPE)
                    .hasSize(4));
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
        List.of(Set.<BitstreamRewrite>of(), EnumSet.allOf(BitstreamRewrite.class))) {
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

  private static Delivered copied(Set<BitstreamRewrite> rewrites, int firstSegment)
      throws Exception {
    var copy =
        VideoDecision.Copy.builder()
            .streamIndex(0)
            .codecString(CODEC_STRING)
            .keepsPacketBytes(true)
            .bitstreamRewrites(rewrites)
            .build();
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
  // delimiter and parameter sets, in front of its slice, and behind it with the RPU between them.
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
        writeAnnexB(written, rpuOf(picture));
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

  // rpu_nal_prefix 25 and the picture's number.
  private static byte[] rpuOf(int picture) {
    return new byte[] {(byte) (RPU_TYPE << 1), 0x01, 0x19, (byte) picture, 0x7F};
  }

  private static void writeAnnexB(ByteArrayOutputStream target, byte[] unit) {
    target.writeBytes(new byte[] {0, 0, 0, 1});
    target.writeBytes(unit);
  }

  // The MP4 with a dvcC box appended to its hvc1 sample entry. FFmpeg writes the moov behind the
  // mdat, so no chunk offset moves.
  private static byte[] withDolbyVisionRecord(byte[] mp4) {
    var record = box("dvcC", DOLBY_VISION_RECORD);
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
        bytes = appended(bytes, path.subList(1, path.size()), record);
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

  /**
   * What the producer delivered: the segment names, the Dolby Vision record boxes that the
   * initialization segment holds, and the type of every NAL unit of each video sample.
   */
  private record Delivered(
      List<String> names, List<String> recordTypes, List<List<Integer>> nalUnitTypesPerSample) {

    private static final Pattern RECORD_TYPES = Pattern.compile("dv[cvw]C");

    static Delivered of(RecordingSegmentSink sink) {
      var initialization = new String(sink.contentOf("init.mp4"), StandardCharsets.ISO_8859_1);
      var recordTypes = RECORD_TYPES.matcher(initialization).results().map(MatchResult::group);
      var movie = Mp4Samples.read(sink.acceptedBytes());
      var types =
          movie.samplesOf(movie.videoTrack()).stream()
              .map(sample -> nalUnitTypesOf(sample.data()))
              .toList();
      return new Delivered(sink.acceptedNames(), recordTypes.toList(), types);
    }

    private static List<Integer> nalUnitTypesOf(ByteBuffer sample) {
      var types = new ArrayList<Integer>();
      var data = sample.duplicate();
      while (data.remaining() >= LENGTH_SIZE) {
        var length = data.getInt();
        types.add((Byte.toUnsignedInt(data.get(data.position())) >> 1) & 0x3F);
        data.position(data.position() + length);
      }

      return types;
    }
  }
}
