package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.KeyframePositionSource;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailable;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailableReason;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * Adds the keyframe positions of the chosen video stream to a successful probe result, found by
 * copying every packet of that stream through FFmpeg. FFmpeg prints one framecrc line per packet,
 * and the scan keeps only the keyframes, so its memory grows with the keyframe count rather than
 * the packet count. The scan copies each audio stream's packets beside the video's, and reports
 * where the chosen video stream and each audio stream ends (ADR 0037, early ends).
 */
@RequiredArgsConstructor
final class KeyframePacketScan {

  /** framecrc prints FFmpeg's AV_NOPTS_VALUE for a packet time that the source does not give. */
  private static final String NO_TIME = Long.toString(Long.MIN_VALUE);

  /** framecrc omits the flags of a packet whose only flag is AV_PKT_FLAG_KEY. */
  private static final int KEYFRAME_FLAG = 1;

  /**
   * FFmpeg logs this line when the source fails before its end, and then exits with code 0. The
   * packets printed before the failure are an incomplete list.
   */
  private static final String DEMUXING_ERROR = "Error during demuxing";

  /**
   * Matroska rounds each packet's time to a whole millisecond, so an audio stream's last sample can
   * end up to 1 ms after the latest end that the stream's packets give.
   */
  private static final BigDecimal AUDIO_END_MARGIN_SECONDS = new BigDecimal("0.001");

  @NonNull private final PacketScanRunner ffmpeg;

  @FunctionalInterface
  interface PacketScanRunner {
    /**
     * Runs FFmpeg, passes each line of its standard output to the packet lines and each line of its
     * standard error to the diagnostic consumer, and returns the exit code. When the packet lines
     * need no more lines, the runner stops FFmpeg.
     */
    int run(List<String> arguments, PacketLines packetLines, Consumer<String> diagnosticLines)
        throws ExecutionException, InterruptedException;
  }

  @FunctionalInterface
  interface PacketLines {
    /** Takes one line of FFmpeg's output and returns whether the scan needs the lines after it. */
    boolean take(String line);
  }

  ProbeAttemptReport addKeyframes(ProbedSource probed)
      throws ExecutionException, InterruptedException {
    var chosen = ChosenVideoStream.find(probed.ffprobeOutput().path("streams"));
    if (chosen.isEmpty()) {
      return failed(probed, ProbeFailure.PROBE_FAILURE_NO_VIDEO_STREAM);
    }

    var stream = chosen.get().description();
    var timeBase = timeBase(stream);
    var scanned = scannedStreams(probed, chosen.get().streamIndex(), timeBase);
    var keyframes = new PacketKeyframes(timeRule(probed, stream), scanned.size());
    var diagnostics = new DemuxingDiagnostics();
    var exitCode = ffmpeg.run(arguments(probed, scanned), keyframes, diagnostics);
    if (keyframes.isMissingTime()) {
      return unavailable(
          probed,
          KeyframesUnavailableReason.KEYFRAMES_UNAVAILABLE_REASON_KEYFRAME_WITHOUT_TIMESTAMP);
    }

    // The server stores a packet scan's invalid-media failure as keyframes that are unavailable.
    if (keyframes.isKeyframeNotAfterPrevious()) {
      return failed(probed, ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
    }

    if (exitCode != 0) {
      throw new PacketScanException("FFmpeg exited with code " + exitCode);
    }

    if (diagnostics.isDemuxingFailed()) {
      throw new PacketScanException("FFmpeg stopped reading the source before its end");
    }

    if (keyframes.times().isEmpty()) {
      return unavailable(
          probed, KeyframesUnavailableReason.KEYFRAMES_UNAVAILABLE_REASON_NO_KEYFRAMES);
    }

    return KeyframePositionsReport.builder()
        .probed(probed)
        .streamIndex(chosen.get().streamIndex())
        .timeBase(timeBase)
        .source(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN)
        .keyframes(keyframes.times())
        .streamEnds(streamEnds(scanned, keyframes))
        .build()
        .report();
  }

  /** One stream that the scan copies: its index in the source and its time base. */
  private record ScannedStream(int index, TimeBase timeBase) {}

  /**
   * The streams that the scan copies, in the order of FFmpeg's output streams: the chosen video
   * stream, then each audio stream. FFmpeg refuses to write an audio stream whose sample rate it
   * does not know, and that would fail the whole scan, so the scan leaves such a stream out. It
   * also leaves out an audio stream without a readable time base, whose end it could not place.
   */
  private static List<ScannedStream> scannedStreams(
      ProbedSource probed, int chosenVideoStream, TimeBase videoTimeBase) {
    var scanned = new ArrayList<ScannedStream>();
    scanned.add(new ScannedStream(chosenVideoStream, videoTimeBase));
    for (var stream : probed.ffprobeOutput().path("streams")) {
      var audioTimeBase = ProbeTimeline.timeBase(stream, "time_base");
      if (isCopiedAudio(stream) && audioTimeBase.isPresent()) {
        scanned.add(new ScannedStream(stream.path("index").asInt(), audioTimeBase.get()));
      }
    }

    return scanned;
  }

  private static boolean isCopiedAudio(JsonNode stream) {
    return FfprobeJson.text(stream, "codec_type").filter("audio"::equals).isPresent()
        && stream.path("index").isIntegralNumber()
        && FfprobeJson.number(stream, "sample_rate", Long::parseLong)
            .filter(rate -> rate > 0)
            .isPresent();
  }

  // -copyts keeps each packet's time from the source; the report subtracts the start time. FFmpeg
  // reads the source as far as ffprobe did, so that it knows the parameters of each stream that the
  // scan maps. With its smaller default limits, FFmpeg would not know the sample rate of audio that
  // starts late, and it refuses to write such a stream. FFmpeg, unlike ffprobe, refuses an option
  // that the source's demuxer lacks.
  private static List<String> arguments(ProbedSource probed, List<ScannedStream> scanned) {
    var arguments =
        new ArrayList<>(List.of("-nostdin", "-hide_banner", "-nostats", "-v", "error", "-copyts"));
    arguments.addAll(FfprobeExecutor.STREAM_READ_LIMITS);
    if (ProbedSource.MPEG_TS_FORMAT.equals(probed.formatName())) {
      arguments.addAll(FfprobeExecutor.MPEG_TS_READ_LIMITS);
    }

    arguments.addAll(List.of("-i", probed.source().toString()));
    for (var stream : scanned) {
      arguments.add("-map");
      arguments.add("0:" + stream.index());
    }

    arguments.addAll(List.of("-c", "copy", "-f", "framecrc", "-"));
    return arguments;
  }

  // FFmpeg's output stream 0 is the chosen video stream, and each later one an audio stream. An
  // audio stream's last packet can state less time than its samples last: Matroska states each
  // packet's duration in whole milliseconds. So the last audio packet counts at the stream's
  // longest packet duration, and the audio ends 1 ms after that (ADR 0032, probe version 9).
  private static List<ScannedStreamEnd> streamEnds(
      List<ScannedStream> scanned, PacketKeyframes packets) {
    var ends = new ArrayList<ScannedStreamEnd>();
    packets
        .streamEnd(0)
        .latestPacketEnd()
        .ifPresent(
            ticks ->
                ends.add(endOf(scanned.getFirst(), BigInteger.valueOf(ticks), BigDecimal.ZERO)));
    for (var output = 1; output < scanned.size(); output++) {
      var audio = scanned.get(output);
      packets
          .streamEnd(output)
          .lastPacketAtLongestDuration()
          .ifPresent(ticks -> ends.add(endOf(audio, ticks, AUDIO_END_MARGIN_SECONDS)));
    }

    return ends;
  }

  private static ScannedStreamEnd endOf(
      ScannedStream stream, BigInteger ticks, BigDecimal marginSeconds) {
    return ScannedStreamEnd.builder()
        .streamIndex(stream.index())
        .timeBase(stream.timeBase())
        .ticks(ticks)
        .marginSeconds(marginSeconds)
        .build();
  }

  private static ProbeAttemptReport unavailable(
      ProbedSource probed, KeyframesUnavailableReason reason) {
    return ProbeAttemptReport.resultOnly(
        KeyframePositionsReport.withKeyframes(
            probed.result(),
            ProbeKeyframes.newBuilder()
                .setUnavailable(KeyframesUnavailable.newBuilder().setReason(reason))));
  }

  private static ProbeAttemptReport failed(ProbedSource probed, ProbeFailure failure) {
    return ProbeAttemptReport.resultOnly(
        probed.result().toBuilder().clearMedia().setFailure(failure).build());
  }

  static TimeBase timeBase(JsonNode stream) {
    var value = stream.path("time_base").asString("");
    var parts = value.split("/");
    try {
      if (parts.length == 2) {
        return new TimeBase(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
      }
    } catch (NumberFormatException _) {
      // Reported below with the value that could not be read.
    }

    throw new PacketScanException("ffprobe reported an unreadable time base: '" + value + "'");
  }

  /**
   * How a keyframe gets a time that its packet lacks. The Matroska demuxer seeks by a keyframe's
   * presentation time, so a Matroska keyframe's decode time is its presentation time, as the
   * container index reader stores it. A stream that does not reorder frames presents each frame at
   * its decode time, which is how FFmpeg's muxers fill the missing time of such a packet.
   */
  private static TimeRule timeRule(ProbedSource probed, JsonNode stream) {
    if (ProbedSource.MATROSKA_FORMAT.equals(probed.formatName())) {
      return TimeRule.DECODE_AT_PRESENTATION;
    }

    if (stream.path("has_b_frames").isIntegralNumber()
        && stream.path("has_b_frames").asInt() == 0) {
      return TimeRule.EQUAL_WHEN_ONE_IS_MISSING;
    }

    return TimeRule.AS_REPORTED;
  }

  private enum TimeRule {
    AS_REPORTED,
    DECODE_AT_PRESENTATION,
    EQUAL_WHEN_ONE_IS_MISSING
  }

  /**
   * Collects the keyframes from FFmpeg's framecrc packet lines, such as {@code 0, -1024, 0, 512,
   * 3109, 0x5e1c1fb1}: the output stream, decode time, presentation time, duration, size and
   * checksum. Flags and side data may follow, such as {@code F=0x0}. Output stream 0 is the chosen
   * video stream, and the scan keeps only the end of every other output stream.
   */
  private static final class PacketKeyframes implements PacketLines {

    private final TimeRule rule;
    private final List<KeyframeTime> times = new ArrayList<>();
    private final List<StreamEnd> streamEnds = new ArrayList<>();
    private boolean missingTime;
    private boolean keyframeNotAfterPrevious;

    PacketKeyframes(TimeRule rule, int outputStreamCount) {
      this.rule = rule;
      for (var output = 0; output < outputStreamCount; output++) {
        streamEnds.add(new StreamEnd());
      }
    }

    /**
     * A keyframe without a time, or one whose times are not both after the previous keyframe's,
     * decides the outcome, so the scan needs no lines after it. A damaged source can make FFmpeg
     * print the same keyframe twice.
     */
    @Override
    public boolean take(String line) {
      if (line.startsWith("#")) {
        return true;
      }

      var packet = Packet.parse(line);
      if (packet.outputStream() >= streamEnds.size()) {
        throw new PacketScanException(
            "FFmpeg printed a packet of a stream that the scan did not map");
      }

      streamEnds.get(packet.outputStream()).extendBy(packet);
      if (packet.outputStream() != 0 || !packet.isKeyframe()) {
        return true;
      }

      var time = keyframeTime(timesOf(packet));
      if (time.isEmpty()) {
        missingTime = true;
        times.clear();
        return false;
      }

      if (!times.isEmpty() && !time.get().isAfter(times.getLast())) {
        keyframeNotAfterPrevious = true;
        times.clear();
        return false;
      }

      times.add(time.get());
      return true;
    }

    private PacketTimes timesOf(Packet packet) {
      var pts = packet.pts();
      var dts = packet.dts();
      return switch (rule) {
        case AS_REPORTED -> new PacketTimes(pts, dts);
        case DECODE_AT_PRESENTATION -> new PacketTimes(pts, pts);
        case EQUAL_WHEN_ONE_IS_MISSING ->
            new PacketTimes(pts.isPresent() ? pts : dts, dts.isPresent() ? dts : pts);
      };
    }

    private static Optional<KeyframeTime> keyframeTime(PacketTimes packetTimes) {
      if (packetTimes.presentation().isEmpty() || packetTimes.decode().isEmpty()) {
        return Optional.empty();
      }

      return Optional.of(
          new KeyframeTime(
              packetTimes.presentation().getAsLong(), packetTimes.decode().getAsLong()));
    }

    boolean isMissingTime() {
      return missingTime;
    }

    boolean isKeyframeNotAfterPrevious() {
      return keyframeNotAfterPrevious;
    }

    List<KeyframeTime> times() {
      return times;
    }

    /** Where the output stream's packets end. */
    StreamEnd streamEnd(int outputStream) {
      return streamEnds.get(outputStream);
    }
  }

  /**
   * Where one stream's packets end. A packet's time is its presentation time. FFmpeg's stream copy
   * passes a packet without a presentation time on with its decode time, which FFmpeg fills from
   * the packets before it, and the fMP4 muxer writes the packet at that time, so the packet counts
   * at its decode time. The end is unknown when a packet has neither time or when a packet's end
   * exceeds the long range.
   */
  private static final class StreamEnd {

    private long latestEnd = Long.MIN_VALUE;
    private long latestTime = Long.MIN_VALUE;
    private long longestDuration;
    private boolean latestPacketHasDuration;
    private boolean isUnknown;

    void extendBy(Packet packet) {
      var time = packet.pts().isPresent() ? packet.pts() : packet.dts();
      var duration = Math.max(0, packet.duration());
      if (time.isEmpty() || time.getAsLong() > Long.MAX_VALUE - duration) {
        isUnknown = true;
        return;
      }

      if (time.getAsLong() >= latestTime) {
        latestTime = time.getAsLong();
        latestPacketHasDuration = duration > 0;
      }

      latestEnd = Math.max(latestEnd, time.getAsLong() + duration);
      longestDuration = Math.max(longestDuration, duration);
    }

    /**
     * The latest sum of a packet's time and its duration, in ticks from the demuxer's zero, when
     * known. It is also unknown when the packet that presents last has no positive duration: that
     * packet's end is then unknown.
     */
    OptionalLong latestPacketEnd() {
      if (isUnknown || !latestPacketHasDuration) {
        return OptionalLong.empty();
      }

      return OptionalLong.of(latestEnd);
    }

    /**
     * The time of the packet that presents last plus the stream's longest packet duration, in ticks
     * from the demuxer's zero, when known. That packet then counts as long as the stream's longest
     * packet, whatever duration it states. It is also unknown when no packet has a positive
     * duration.
     */
    Optional<BigInteger> lastPacketAtLongestDuration() {
      if (isUnknown || longestDuration == 0) {
        return Optional.empty();
      }

      return Optional.of(BigInteger.valueOf(latestTime).add(BigInteger.valueOf(longestDuration)));
    }
  }

  private record PacketTimes(OptionalLong presentation, OptionalLong decode) {}

  private record Packet(
      int outputStream, OptionalLong pts, OptionalLong dts, long duration, int flags) {

    static Packet parse(String line) {
      var fields = Arrays.stream(line.split(",")).map(String::strip).toList();
      if (fields.size() < 6) {
        throw new PacketScanException("FFmpeg printed a packet line without six fields");
      }

      var flags =
          fields.stream()
              .skip(6)
              .filter(field -> field.startsWith("F="))
              .findFirst()
              .map(field -> Integer.decode(field.substring("F=".length())))
              .orElse(KEYFRAME_FLAG);
      return new Packet(
          Integer.parseInt(fields.get(0)),
          time(fields.get(2)),
          time(fields.get(1)),
          Long.parseLong(fields.get(3)),
          flags);
    }

    boolean isKeyframe() {
      return (flags & KEYFRAME_FLAG) != 0;
    }

    private static OptionalLong time(String value) {
      if (NO_TIME.equals(value)) {
        return OptionalLong.empty();
      }

      return OptionalLong.of(Long.parseLong(value));
    }
  }

  /** Watches FFmpeg's diagnostics for a source that failed before its end. */
  private static final class DemuxingDiagnostics implements Consumer<String> {

    private volatile boolean demuxingFailed;

    @Override
    public void accept(String line) {
      if (line.contains(DEMUXING_ERROR)) {
        demuxingFailed = true;
      }
    }

    boolean isDemuxingFailed() {
      return demuxingFailed;
    }
  }
}
