package com.streamarr.transcode.worker;

import static com.streamarr.transcode.worker.support.BitrateScanChunks.intervalCounts;
import static com.streamarr.transcode.worker.support.BitrateScanChunks.joined;
import static com.streamarr.transcode.worker.support.BitrateScanChunks.request;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.BitrateScanChunkResult;
import build.buf.gen.streamarr.transcode.v1.BitrateScanCopyChangesPackets;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.worker.support.BitrateScanChunks.IntervalCounts;
import com.streamarr.transcode.worker.support.BitrateScanChunks.Positions;
import com.streamarr.transcode.worker.support.WorkerProbeFixtures;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("SmokeTest")
@Timeout(value = 10, unit = TimeUnit.MINUTES)
@DisplayName("Worker Bitrate Scan Smoke Tests")
class WorkerBitrateScanSmokeTest {

  // 20 s at 24 fps with a keyframe at least every second, read as three chunks.
  private static final int SECONDS = 20;
  private static final List<Integer> CHUNK_STARTS = List.of(0, 6, 13);

  private static final FfprobeExecutor FFPROBE =
      FfprobeExecutor.forBinaries(Path.of("ffprobe"), Path.of("ffmpeg"));

  @TempDir Path root;

  /**
   * Synthetic sources whose streams a stream copy keeps unchanged, so that a chunk measures them.
   * The Matroska source reorders frames, the MPEG-TS source starts at a container start time that
   * is not zero, and the AVI source gives its video packets no presentation time.
   */
  enum Source {
    MATROSKA(
        "mkv",
        List.of("-c:v", "libx264", "-bf", "2", "-g", "24", "-c:a:0", "aac", "-c:a:1", "libopus")),
    MPEG_TS(
        "ts",
        List.of("-c:v", "mpeg2video", "-bf", "2", "-g", "24", "-c:a:0", "mp2", "-c:a:1", "ac3")),
    AVI("avi", List.of("-c:v", "mpeg4", "-bf", "0", "-g", "24", "-c:a:0", "libmp3lame"));

    private final String extension;
    private final List<String> codecs;

    Source(String extension, List<String> codecs) {
      this.extension = extension;
      this.codecs = codecs;
    }

    Path generate(Path directory) throws Exception {
      var output = directory.resolve("source." + extension);
      var audioStreams = codecs.contains("-c:a:1") ? 2 : 1;
      var command =
          new ArrayList<>(
              List.of(
                  "ffmpeg",
                  "-nostdin",
                  "-v",
                  "error",
                  "-y",
                  "-f",
                  "lavfi",
                  "-i",
                  "testsrc2=s=320x240:r=24:d=" + SECONDS,
                  "-f",
                  "lavfi",
                  "-i",
                  "sine=frequency=440:sample_rate=48000:d=" + SECONDS,
                  "-map",
                  "0:v"));
      for (var i = 0; i < audioStreams; i++) {
        command.addAll(List.of("-map", "1:a"));
      }

      command.addAll(codecs);
      command.add(output.toString());
      run(command);
      return output;
    }
  }

  @ParameterizedTest
  @EnumSource(Source.class)
  @DisplayName(
      "Should count every packet in the interval of the whole file's packet listing when the chunks"
          + " of a synthetic source are read one by one")
  void shouldCountEveryPacketInTheIntervalOfTheWholeFilesPacketListingWhenTheChunksAreReadOneByOne(
      Source source) throws Exception {
    var file = source.generate(root);

    var positions = positions(file);

    assertThat(chunkCounts(file, positions, CHUNK_STARTS))
        .isEqualTo(WholeFileListing.read(file, positions.streamIndex()).intervalCounts());
  }

  @Test
  @DisplayName(
      "Should count every audio packet once when a synthetic Matroska source places its audio more"
          + " than 120 s behind the video")
  void shouldCountEveryAudioPacketOnceWhenASyntheticMatroskaSourcePlacesItsAudioFarBehind()
      throws Exception {
    // areverse holds back every audio frame until the input ends, so the muxer writes the first
    // audio packet after at least 200 s of video: a frame every 40 s, each one a keyframe. The
    // first chunk ends at 40 s, so its audio lies more than 120 s behind its end in the file.
    var file = root.resolve("late-audio.mkv");
    run(
        List.of(
            "ffmpeg",
            "-nostdin",
            "-v",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=s=160x120:r=0.025:d=600",
            "-f",
            "lavfi",
            "-i",
            "sine=sample_rate=48000:d=600",
            "-map",
            "0:v",
            "-map",
            "1:a",
            "-af",
            "areverse",
            "-c:v",
            "libx264",
            "-g",
            "1",
            "-c:a",
            "aac",
            file.toString()));

    var positions = positions(file);

    assertThat(chunkCounts(file, positions, List.of(0, 1, 2, 8)))
        .isEqualTo(WholeFileListing.read(file, positions.streamIndex()).intervalCounts());
  }

  /** The counts of the chunks that start at the keyframes, joined per stream; each is measured. */
  private static Map<Integer, IntervalCounts> chunkCounts(
      Path file, Positions positions, List<Integer> chunkStarts) {
    var chunks = new ArrayList<Map<Integer, IntervalCounts>>();
    for (var i = 0; i < chunkStarts.size(); i++) {
      var end = i + 1 < chunkStarts.size() ? chunkStarts.get(i + 1) : positions.keyframes().size();
      var report = FFPROBE.probe(file, request(positions.chunk(chunkStarts.get(i), end)));
      assertThat(report.result().getBitrateScanChunk().getResultCase())
          .as("chunk %d: %s", i, report.result())
          .isEqualTo(BitrateScanChunkResult.ResultCase.MEASURED);
      chunks.add(intervalCounts(report.streamMeasurementParts()));
    }

    return joined(chunks);
  }

  @Test
  @DisplayName(
      "Should fail only the chunks that reach the truncation when a synthetic Matroska source is"
          + " cut short after its probe")
  void shouldFailOnlyTheChunksThatReachTheTruncationWhenASyntheticMatroskaSourceIsCutShort()
      throws Exception {
    var file = Source.MATROSKA.generate(root);
    var positions = positions(file);
    var bytes = Files.readAllBytes(file);
    Files.write(file, Arrays.copyOf(bytes, bytes.length * 6 / 10));

    var first = FFPROBE.probe(file, request(positions.chunk(0, 6)));
    var middle = FFPROBE.probe(file, request(positions.chunk(6, 13)));
    var last = FFPROBE.probe(file, request(positions.chunk(13, positions.keyframes().size())));

    assertThat(first.result().getBitrateScanChunk().getResultCase())
        .isEqualTo(BitrateScanChunkResult.ResultCase.MEASURED);
    assertThat(List.of(middle.result().getFailure(), last.result().getFailure()))
        .containsOnly(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
    assertThat(middle.streamMeasurementParts()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should report that a copy changes the packets when a chunk reads H.264 in MPEG-TS, which is"
          + " in Annex B form")
  void shouldReportThatACopyChangesThePacketsWhenAChunkReadsH264InMpegTs() throws Exception {
    var file = root.resolve("annex-b.ts");
    run(
        List.of(
            "ffmpeg",
            "-nostdin",
            "-v",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=s=320x240:r=24:d=4",
            "-c:v",
            "libx264",
            "-g",
            "24",
            file.toString()));
    var positions = positions(file);

    var report = FFPROBE.probe(file, request(positions.chunk(0, positions.keyframes().size())));

    assertThat(report.result().getBitrateScanChunk())
        .isEqualTo(
            BitrateScanChunkResult.newBuilder()
                .setCopyChangesPackets(
                    BitrateScanCopyChangesPackets.newBuilder()
                        .setStreamIndex(positions.streamIndex()))
                .build());
    assertThat(report.streamMeasurementParts()).isEmpty();
  }

  private static Positions positions(Path file) {
    var scan = WorkerProbeFixtures.requestBuilder().setScanKeyframePackets(true).build();
    return Positions.of(FFPROBE.probe(file, scan));
  }

  private static String run(List<String> command) throws Exception {
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    var output = new String(process.getInputStream().readAllBytes());
    assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("%s finished", command).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    return output;
  }

  /**
   * One ffprobe listing of every packet of the file, counted into the keyframe intervals of the
   * chosen video stream: a video packet in the interval of the last keyframe packet before it in
   * decode order, and an audio packet in the interval of the last keyframe that presents at or
   * before it, or the first interval before the first keyframe.
   */
  private record WholeFileListing(int chosen, List<Packet> packets, Map<Integer, Fraction> bases) {

    static WholeFileListing read(Path file, int chosen) throws Exception {
      var streams =
          run(
              List.of(
                  "ffprobe",
                  "-v",
                  "error",
                  "-show_entries",
                  "stream=index,codec_type,time_base",
                  "-of",
                  "compact=p=0",
                  file.toString()));
      var bases = new TreeMap<Integer, Fraction>();
      var audio = new ArrayList<Integer>();
      for (var line : streams.lines().filter(line -> line.contains("index=")).toList()) {
        var fields = fields(line);
        var index = Integer.parseInt(fields.get("index"));
        bases.put(index, Fraction.parse(fields.get("time_base")));
        if ("audio".equals(fields.get("codec_type"))) {
          audio.add(index);
        }
      }

      var listing =
          run(
              List.of(
                  "ffprobe",
                  "-v",
                  "error",
                  "-show_entries",
                  "packet=stream_index,pts,dts,size,flags",
                  "-of",
                  "compact=p=0",
                  file.toString()));
      var packets =
          listing
              .lines()
              .filter(line -> line.startsWith("stream_index="))
              .map(Packet::parse)
              .filter(packet -> packet.stream() == chosen || audio.contains(packet.stream()))
              .toList();
      return new WholeFileListing(chosen, packets, bases);
    }

    Map<Integer, IntervalCounts> intervalCounts() {
      var keyframeTimes =
          packets.stream()
              .filter(packet -> packet.stream() == chosen && packet.isKeyframe())
              .map(packet -> bases.get(chosen).times(packet.time()))
              .sorted()
              .toList();
      var counted = new LinkedHashMap<Integer, long[][]>();
      counted.put(chosen, new long[2][keyframeTimes.size()]);
      var keyframesSeen = 0;
      for (var packet : packets) {
        var isVideo = packet.stream() == chosen;
        if (isVideo && packet.isKeyframe()) {
          keyframesSeen++;
        }

        var time = bases.get(packet.stream()).times(packet.time());
        var keyframesAtOrBefore =
            isVideo
                ? keyframesSeen
                : (int) keyframeTimes.stream().filter(t -> t.compareTo(time) <= 0).count();
        var interval = Math.max(0, keyframesAtOrBefore - 1);
        var counts =
            counted.computeIfAbsent(packet.stream(), _ -> new long[2][keyframeTimes.size()]);
        counts[0][interval] += packet.size();
        counts[1][interval]++;
      }

      var result = new LinkedHashMap<Integer, IntervalCounts>();
      counted.forEach(
          (stream, counts) ->
              result.put(
                  stream,
                  new IntervalCounts(
                      Arrays.stream(counts[0]).boxed().toList(),
                      Arrays.stream(counts[1]).boxed().toList())));
      return result;
    }

    private static Map<String, String> fields(String line) {
      var fields = new LinkedHashMap<String, String>();
      for (var field : line.split("\\|")) {
        var separator = field.indexOf('=');
        if (separator > 0) {
          fields.put(field.substring(0, separator), field.substring(separator + 1));
        }
      }

      return fields;
    }
  }

  /**
   * A packet's stream, its time (its presentation time, or else its decode time), size and key
   * flag.
   */
  private record Packet(int stream, long time, long size, boolean isKeyframe) {

    static Packet parse(String line) {
      var fields = WholeFileListing.fields(line);
      var time = "N/A".equals(fields.get("pts")) ? fields.get("dts") : fields.get("pts");
      return new Packet(
          Integer.parseInt(fields.get("stream_index")),
          Long.parseLong(time),
          Long.parseLong(fields.get("size")),
          fields.get("flags").startsWith("K"));
    }
  }

  /** An exact rational number of seconds. */
  private record Fraction(BigInteger numerator, BigInteger denominator)
      implements Comparable<Fraction> {

    static Fraction parse(String value) {
      var parts = value.split("/");
      return new Fraction(new BigInteger(parts[0]), new BigInteger(parts[1]));
    }

    Fraction times(long ticks) {
      return new Fraction(numerator.multiply(BigInteger.valueOf(ticks)), denominator);
    }

    @Override
    public int compareTo(Fraction other) {
      return numerator
          .multiply(other.denominator())
          .compareTo(other.numerator().multiply(denominator));
    }
  }
}
