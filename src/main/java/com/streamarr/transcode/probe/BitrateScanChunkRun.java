package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.number;
import static com.streamarr.transcode.probe.FfprobeJson.text;

import build.buf.gen.streamarr.transcode.v1.BitrateScanChunk;
import build.buf.gen.streamarr.transcode.v1.BitrateScanChunkResult;
import build.buf.gen.streamarr.transcode.v1.BitrateScanChunkStream;
import build.buf.gen.streamarr.transcode.v1.BitrateScanChunkStreams;
import build.buf.gen.streamarr.transcode.v1.BitrateScanCopyChangesPackets;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamMeasurementPart;
import com.streamarr.transcode.probe.ChunkPackets.AudioWindow;
import com.streamarr.transcode.probe.ChunkPackets.SixBlockDuration;
import com.streamarr.transcode.probe.ChunkPackets.StreamCounts;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Measures one chunk of a bitrate scan (ADR 0054): it checks from ffprobe's stream descriptions
 * that a stream copy keeps every measured stream's packets, then lists the chunk's packets with
 * ffprobe and counts them into the chunk's keyframe intervals ({@link ChunkPackets}). The listing
 * decodes no frame. A chunk that does not start the file is listed from a seek before its first
 * keyframe, so that the audio muxed around that keyframe is read too.
 */
@RequiredArgsConstructor
final class BitrateScanChunkRun {

  private static final int MAXIMUM_KEYFRAMES = 4_096;

  /**
   * How far before the chunk's first keyframe the listing seeks, tried in order until the listing
   * reaches back to the chunk's start. After the last, the listing reads from the file's start.
   */
  private static final List<BigDecimal> SEEK_MARGINS_SECONDS =
      List.of(BigDecimal.valueOf(10), BigDecimal.valueOf(120));

  private static final int SEEK_SCALE = 6;
  private static final int FIRST_PACKET_OUTPUT_BYTES = 1 << 20;

  private final PacketListingRunner ffprobe;
  private final BoundedFfprobeRunner boundedFfprobe;
  private final ObjectMapper objectMapper;

  @FunctionalInterface
  interface PacketListingRunner {
    /**
     * Runs ffprobe, passes each line of its standard output to the packet lines and each line of
     * its standard error to the diagnostic consumer, and returns the exit code. When the packet
     * lines need no more lines, the runner stops ffprobe.
     */
    int run(
        List<String> arguments,
        KeyframePacketScan.PacketLines packetLines,
        Consumer<String> diagnosticLines)
        throws ExecutionException, InterruptedException;
  }

  /**
   * The chunk's report. {@code probed} holds ffprobe's descriptions of the source's streams and
   * format, with each stream's extradata, and a result that names the attempt.
   */
  ProbeAttemptReport measure(ProbedSource probed, BitrateScanChunk chunk)
      throws ExecutionException, InterruptedException {
    if (chunk.getMeasurementVersion() != FfprobeExecutor.MEASUREMENT_VERSION) {
      return failed(probed, ProbeFailure.PROBE_FAILURE_UNSUPPORTED_VERSION);
    }

    if (!isWellFormed(chunk)) {
      return failed(probed, ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
    }

    var streams = measuredStreams(probed.ffprobeOutput(), chunk);
    if (streams.isEmpty()) {
      return failed(probed, ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
    }

    for (var stream : streams.get()) {
      var copy = packetCopy(probed, stream);
      if (copy == PacketCopy.CHANGES_PACKETS) {
        return copyChangesPackets(probed, stream.index());
      }

      if (copy == PacketCopy.UNREADABLE) {
        return failed(probed, ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
      }
    }

    var timeline = Timeline.of(probed.ffprobeOutput(), chunk, streams.get());
    if (timeline.isEmpty()) {
      return failed(probed, ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
    }

    return list(probed, timeline.get());
  }

  private static boolean isWellFormed(BitrateScanChunk chunk) {
    var keyframes = chunk.getPresentationTimeDeltasCount();
    var measured = chunk.getMeasuredStreamIndexesList();
    return keyframes >= 1
        && keyframes <= MAXIMUM_KEYFRAMES
        && chunk.getDecodeTimeDeltasCount() == keyframes
        && chunk.getTimeBaseNumerator() > 0
        && chunk.getTimeBaseDenominator() > 0
        && chunk.hasEndPresentationTime() == chunk.hasEndDecodeTime()
        && measured.contains(chunk.getChosenVideoStreamIndex())
        && new HashSet<>(measured).size() == measured.size();
  }

  /** One measured stream: its index, ffprobe's description and its time base. */
  private record MeasuredStream(int index, JsonNode description, TimeBase timeBase) {

    boolean isAudio() {
      return text(description, "codec_type").filter("audio"::equals).isPresent();
    }

    CopiedPacketRule copyRule() {
      return CopiedPacketRule.of(text(description, "codec_name").orElse(""));
    }
  }

  /**
   * The measured streams in the chunk's order; empty when one is missing from the source, or when
   * the chosen stream is not video or another stream is not audio.
   */
  private static Optional<List<MeasuredStream>> measuredStreams(
      JsonNode ffprobeOutput, BitrateScanChunk chunk) {
    var descriptions = new HashMap<Integer, JsonNode>();
    for (var stream : ffprobeOutput.path("streams")) {
      descriptions.put(stream.path("index").asInt(-1), stream);
    }

    var measured = new ArrayList<MeasuredStream>();
    for (var index : chunk.getMeasuredStreamIndexesList()) {
      var description = descriptions.get(index);
      var expectedType = index == chunk.getChosenVideoStreamIndex() ? "video" : "audio";
      if (description == null
          || text(description, "codec_type").filter(expectedType::equals).isEmpty()) {
        return Optional.empty();
      }

      measured.add(
          new MeasuredStream(index, description, KeyframePacketScan.timeBase(description)));
    }

    return Optional.of(measured);
  }

  /** What a stream copy does to a stream's packets. */
  private enum PacketCopy {
    KEEPS_PACKETS,
    CHANGES_PACKETS,
    /** The stream's first packet is too large to be one of its codec's. */
    UNREADABLE
  }

  /**
   * What a copy does to the stream's packets, as far as the stream's description and first packet
   * tell. The E-AC-3 rule needs each packet's duration, so the listing checks it.
   */
  private PacketCopy packetCopy(ProbedSource probed, MeasuredStream stream)
      throws ExecutionException, InterruptedException {
    return switch (stream.copyRule()) {
      case H264_RECORD, HEVC_RECORD ->
          keepsPacketsWhen(
              CopiedPacketBytes.of(stream.description(), probed.formatName()).orElse(false));
      case ALWAYS_CHANGED -> PacketCopy.CHANGES_PACKETS;
      case AAC_WITHOUT_ADTS ->
          Extradata.of(stream.description()).isPresent()
              ? firstAacPacketCopy(probed, stream.index())
              : PacketCopy.CHANGES_PACKETS;
      case SIX_BLOCK_EAC3 -> keepsPacketsWhen(sampleRate(stream).isPresent());
      case KEPT -> PacketCopy.KEEPS_PACKETS;
    };
  }

  private static PacketCopy keepsPacketsWhen(boolean isKept) {
    return isKept ? PacketCopy.KEEPS_PACKETS : PacketCopy.CHANGES_PACKETS;
  }

  /**
   * The mp4 muxer adds {@code aac_adtstoasc} when the first AAC packet it receives starts with an
   * ADTS sync word. A read that fails says nothing about the packet, so it fails the chunk.
   */
  private PacketCopy firstAacPacketCopy(ProbedSource probed, int streamIndex)
      throws ExecutionException, InterruptedException {
    var arguments = new ArrayList<>(List.of("-v", "quiet", "-print_format", "json"));
    arguments.addAll(FfprobeExecutor.READ_LIMITS);
    arguments.addAll(
        List.of(
            "-select_streams",
            String.valueOf(streamIndex),
            "-read_intervals",
            "%+#1",
            "-show_entries",
            "packet=data",
            "-show_data",
            probed.source().toString()));
    var output = boundedFfprobe.run(arguments, FIRST_PACKET_OUTPUT_BYTES);
    if (output.isEmpty()) {
      return PacketCopy.UNREADABLE;
    }

    if (output.get().exitCode() != 0) {
      throw new PacketScanException(
          "ffprobe's first AAC packet read exited with code " + output.get().exitCode());
    }

    var packets = objectMapper.readTree(output.get().stdout()).path("packets");
    if (packets.isEmpty()) {
      return PacketCopy.KEEPS_PACKETS;
    }

    var data = Extradata.dumped(packets.get(0).path("data").asString("")).orElse(new byte[0]);
    var isAdts = data.length > 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xF0) == 0xF0;
    return keepsPacketsWhen(!isAdts);
  }

  private static Optional<Long> sampleRate(MeasuredStream stream) {
    return number(stream.description(), "sample_rate", Long::parseLong).filter(rate -> rate > 0);
  }

  /**
   * The chunk's keyframes and the audio intervals on the demuxer's timeline, in each stream's time
   * base, and the start that a seek before the chunk's first keyframe aims at, in seconds.
   */
  private record Timeline(
      ListingPlan listing, BigDecimal firstKeyframeSeconds, List<MeasuredStream> streams) {

    /** Empty when a keyframe time is no whole tick of the chosen stream's time base. */
    static Optional<Timeline> of(
        JsonNode ffprobeOutput, BitrateScanChunk chunk, List<MeasuredStream> streams) {
      var times = new long[chunk.getPresentationTimeDeltasCount() + 1];
      long time = 0;
      for (var i = 0; i < chunk.getPresentationTimeDeltasCount(); i++) {
        time = Math.addExact(time, chunk.getPresentationTimeDeltas(i));
        times[i] = time;
      }

      // The last entry holds the end keyframe's time, when the chunk has one.
      var hasEnd = chunk.hasEndPresentationTime();
      times[times.length - 1] = hasEnd ? chunk.getEndPresentationTime() : 0;
      var keyframes =
          new KeyframeTimes(
              times,
              new TimeBase(chunk.getTimeBaseNumerator(), chunk.getTimeBaseDenominator()),
              hasEnd,
              ffprobeOutput);
      // A well-formed chunk measures its chosen stream, in any place of its list.
      var chosen =
          streams.stream()
              .filter(stream -> stream.index() == chunk.getChosenVideoStreamIndex())
              .findFirst()
              .orElseThrow();
      var video = keyframes.rescaledExactly(chosen.timeBase());
      if (video.isEmpty()) {
        return Optional.empty();
      }

      var plan =
          ListingPlan.builder()
              .chosenStreamIndex(chosen.index())
              .keyframes(Arrays.copyOf(video.get(), video.get().length - 1))
              .end(keyframes.end(video.get()))
              .isFirstChunk(chunk.getFirstChunk())
              .audioWindows(
                  streams.stream()
                      .filter(MeasuredStream::isAudio)
                      .map(keyframes::audioWindow)
                      .toList())
              .build();
      return Optional.of(new Timeline(plan, seconds(video.get()[0], chosen.timeBase()), streams));
    }

    /** A time in seconds, rounded down to a microsecond. */
    private static BigDecimal seconds(long ticks, TimeBase timeBase) {
      return BigDecimal.valueOf(ticks)
          .multiply(BigDecimal.valueOf(timeBase.numerator()))
          .divide(BigDecimal.valueOf(timeBase.denominator()), SEEK_SCALE, RoundingMode.FLOOR);
    }
  }

  /**
   * The chunk's keyframe presentation times on the contract's zero-based timeline, followed by the
   * end keyframe's time, in the chunk's time base. A stream's demuxer times are its zero-based
   * times plus the container's start in its own time base, as the measured times are reported. Each
   * audio interval therefore holds exactly the zero-based times from its keyframe up to the next,
   * whatever the rounding of the container's start in each time base.
   */
  private static final class KeyframeTimes {
    private final long[] times;
    private final TimeBase timeBase;
    private final boolean hasEnd;
    private final JsonNode ffprobeOutput;

    private KeyframeTimes(long[] times, TimeBase timeBase, boolean hasEnd, JsonNode ffprobeOutput) {
      this.times = times;
      this.timeBase = timeBase;
      this.hasEnd = hasEnd;
      this.ffprobeOutput = ffprobeOutput;
    }

    /** The demuxer times in another time base; empty when one is no whole tick of it. */
    Optional<long[]> rescaledExactly(TimeBase other) {
      var start = KeyframePositionsReport.containerStart(ffprobeOutput, other);
      var rescaled = new long[times.length];
      for (var i = 0; i < times.length; i++) {
        var time = rescaleExactly(times[i], timeBase, other);
        if (time.isEmpty()) {
          return Optional.empty();
        }

        rescaled[i] = Math.addExact(time.getAsLong(), start);
      }

      return Optional.of(rescaled);
    }

    OptionalLong end(long[] rescaled) {
      return hasEnd ? OptionalLong.of(rescaled[rescaled.length - 1]) : OptionalLong.empty();
    }

    AudioWindow audioWindow(MeasuredStream stream) {
      var start = KeyframePositionsReport.containerStart(ffprobeOutput, stream.timeBase());
      var starts = new long[times.length];
      for (var i = 0; i < times.length; i++) {
        starts[i] = Math.addExact(rescaleUp(times[i], timeBase, stream.timeBase()), start);
      }

      var sixBlocks =
          stream.copyRule() == CopiedPacketRule.SIX_BLOCK_EAC3
              ? sampleRate(stream).map(rate -> new SixBlockDuration(rate, stream.timeBase()))
              : Optional.<SixBlockDuration>empty();
      return AudioWindow.builder()
          .streamIndex(stream.index())
          .starts(Arrays.copyOf(starts, starts.length - 1))
          .end(end(starts))
          .sixBlocks(sixBlocks)
          .build();
    }
  }

  /**
   * The parts of a chunk's {@link ChunkPackets} that stay the same from one listing to the next.
   */
  private static final class ListingPlan {
    private final int chosenStreamIndex;
    private final long[] keyframes;
    private final OptionalLong end;
    private final boolean isFirstChunk;
    private final List<AudioWindow> audioWindows;

    @Builder
    private ListingPlan(
        int chosenStreamIndex,
        long[] keyframes,
        OptionalLong end,
        boolean isFirstChunk,
        List<AudioWindow> audioWindows) {
      this.chosenStreamIndex = chosenStreamIndex;
      this.keyframes = keyframes;
      this.end = end;
      this.isFirstChunk = isFirstChunk;
      this.audioWindows = List.copyOf(audioWindows);
    }

    boolean isFirstChunk() {
      return isFirstChunk;
    }

    ChunkPackets packets(boolean isReadFromFileStart) {
      return ChunkPackets.builder()
          .chosenStreamIndex(chosenStreamIndex)
          .keyframes(keyframes)
          .end(end)
          .isFirstChunk(isFirstChunk)
          .isReadFromFileStart(isReadFromFileStart)
          .audioWindows(audioWindows)
          .build();
    }
  }

  private ProbeAttemptReport list(ProbedSource probed, Timeline timeline)
      throws ExecutionException, InterruptedException {
    var seekStarts =
        timeline.listing().isFirstChunk()
            ? List.<BigDecimal>of()
            : SEEK_MARGINS_SECONDS.stream()
                .map(margin -> timeline.firstKeyframeSeconds().subtract(margin))
                .filter(start -> start.signum() > 0)
                .toList();
    for (var start : seekStarts) {
      var listed = listFrom(probed, timeline, Optional.of(start));
      if (!listed.packets().isSeekMissedStart()) {
        return report(probed, timeline, listed);
      }
    }

    return report(probed, timeline, listFrom(probed, timeline, Optional.empty()));
  }

  private Listed listFrom(ProbedSource probed, Timeline timeline, Optional<BigDecimal> start)
      throws ExecutionException, InterruptedException {
    var packets = timeline.listing().packets(start.isEmpty());
    var diagnostics = new DemuxerErrors(probed.formatName());
    var exitCode = ffprobe.run(arguments(probed, start), packets, diagnostics);
    return new Listed(packets, exitCode, diagnostics.isFailed());
  }

  private record Listed(ChunkPackets packets, int exitCode, boolean isDemuxingFailed) {}

  private static List<String> arguments(ProbedSource probed, Optional<BigDecimal> start) {
    var arguments = new ArrayList<>(List.of("-v", "error"));
    arguments.addAll(FfprobeExecutor.READ_LIMITS);
    start.ifPresent(
        seconds -> arguments.addAll(List.of("-read_intervals", seconds.toPlainString() + "%")));
    arguments.addAll(
        List.of(
            "-show_entries",
            "packet=stream_index,pts,dts,duration,size,flags",
            "-of",
            "compact=p=0",
            probed.source().toString()));
    return arguments;
  }

  private ProbeAttemptReport report(ProbedSource probed, Timeline timeline, Listed listed) {
    var packets = listed.packets();
    var stop = packets.stop();
    if (stop.isPresent()) {
      return switch (stop.get()) {
        case ChunkPackets.Stop.CopyChangesPackets(var streamIndex) ->
            copyChangesPackets(probed, streamIndex);
        case ChunkPackets.Stop.PacketWithoutTime _ ->
            failed(probed, ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
      };
    }

    if (!packets.isStoppedEarly() && listed.exitCode() != 0) {
      throw new PacketScanException(
          "ffprobe's packet listing exited with code " + listed.exitCode());
    }

    if (listed.isDemuxingFailed() || !packets.isComplete()) {
      return failed(probed, ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
    }

    return packets
        .counts()
        .map(counts -> measured(probed, timeline, counts))
        .orElseGet(() -> failed(probed, ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE));
  }

  private static ProbeAttemptReport measured(
      ProbedSource probed, Timeline timeline, List<StreamCounts> counts) {
    var countsByStream = new HashMap<Integer, StreamCounts>();
    counts.forEach(stream -> countsByStream.put(stream.streamIndex(), stream));
    var attemptId = probed.result().getProbeAttemptId();
    var streams = BitrateScanChunkStreams.newBuilder();
    var parts = new ArrayList<ProbeStreamMeasurementPart>();
    for (var stream : timeline.streams()) {
      var counted = countsByStream.get(stream.index());
      var start = KeyframePositionsReport.containerStart(probed.ffprobeOutput(), stream.timeBase());
      var measured =
          BitrateScanChunkStream.newBuilder()
              .setStreamIndex(stream.index())
              .setMeasuredBytes(counted.measuredBytes())
              .setPacketCount(counted.packetCount())
              .setTimeBaseNumerator(Math.toIntExact(stream.timeBase().numerator()))
              .setTimeBaseDenominator(Math.toIntExact(stream.timeBase().denominator()));
      counted
          .firstPresentationTime()
          .ifPresent(time -> measured.setFirstPresentationTime(Math.subtractExact(time, start)));
      counted.endTime().ifPresent(time -> measured.setEndTime(Math.subtractExact(time, start)));
      streams.addStreams(measured);
      parts.addAll(
          StreamMeasurementParts.of(
              ProbeStreamMeasurementPart.newBuilder()
                  .setProbeAttemptId(attemptId)
                  .setStreamIndex(stream.index())
                  .build(),
              counted.intervalBytes(),
              counted.intervalPacketCounts()));
    }

    return ProbeAttemptReport.builder()
        .keyframePositionsParts(List.of())
        .streamMeasurementParts(parts)
        .result(
            probed.result().toBuilder()
                .setBitrateScanChunk(BitrateScanChunkResult.newBuilder().setMeasured(streams))
                .build())
        .build();
  }

  private static ProbeAttemptReport copyChangesPackets(ProbedSource probed, int streamIndex) {
    return ProbeAttemptReport.resultOnly(
        probed.result().toBuilder()
            .setBitrateScanChunk(
                BitrateScanChunkResult.newBuilder()
                    .setCopyChangesPackets(
                        BitrateScanCopyChangesPackets.newBuilder().setStreamIndex(streamIndex)))
            .build());
  }

  private static ProbeAttemptReport failed(ProbedSource probed, ProbeFailure failure) {
    return ProbeAttemptReport.resultOnly(probed.result().toBuilder().setFailure(failure).build());
  }

  // The time in the other time base when it is a whole tick of it. The products can exceed a long
  // even when the result fits, as at a time base of 1/1,000,000,000.
  private static OptionalLong rescaleExactly(long ticks, TimeBase from, TimeBase to) {
    var quotient = scaled(ticks, from, to).divideAndRemainder(denominator(from, to));
    if (quotient[1].signum() != 0) {
      return OptionalLong.empty();
    }

    return OptionalLong.of(quotient[0].longValueExact());
  }

  // The first tick of the other time base at or after the time.
  private static long rescaleUp(long ticks, TimeBase from, TimeBase to) {
    return new BigDecimal(scaled(ticks, from, to))
        .divide(new BigDecimal(denominator(from, to)), 0, RoundingMode.CEILING)
        .longValueExact();
  }

  private static BigInteger scaled(long ticks, TimeBase from, TimeBase to) {
    return BigInteger.valueOf(ticks)
        .multiply(BigInteger.valueOf(from.numerator()))
        .multiply(BigInteger.valueOf(to.denominator()));
  }

  private static BigInteger denominator(TimeBase from, TimeBase to) {
    return BigInteger.valueOf(from.denominator()).multiply(BigInteger.valueOf(to.numerator()));
  }

  /**
   * Watches ffprobe's diagnostics for an error that the source's demuxer logs, such as a file that
   * ends before its last packet. ffprobe exits with code 0 after a read error, so its diagnostics
   * are the only sign of one.
   */
  private static final class DemuxerErrors implements Consumer<String> {

    private final String prefix;
    private volatile boolean failed;

    private DemuxerErrors(String formatName) {
      this.prefix = "[" + formatName + " @";
    }

    @Override
    public void accept(String line) {
      if (line.startsWith(prefix)) {
        failed = true;
      }
    }

    boolean isFailed() {
      return failed;
    }
  }
}
