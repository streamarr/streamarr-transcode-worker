package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.KeyframePositionSource;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailable;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailableReason;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
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
 * the packet count.
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
      return ProbeAttemptReport.resultOnly(
          probed.result().toBuilder()
              .clearMedia()
              .setFailure(ProbeFailure.PROBE_FAILURE_NO_VIDEO_STREAM)
              .build());
    }

    var stream = chosen.get().description();
    var timeBase = timeBase(stream);
    var keyframes = new PacketKeyframes(timeRule(probed, stream));
    var diagnostics = new DemuxingDiagnostics();
    // -copyts keeps each packet's time from the source; the report subtracts the start time.
    var exitCode =
        ffmpeg.run(
            List.of(
                "-nostdin",
                "-hide_banner",
                "-nostats",
                "-v",
                "error",
                "-copyts",
                "-i",
                probed.source().toString(),
                "-map",
                "0:" + chosen.get().streamIndex(),
                "-c",
                "copy",
                "-f",
                "framecrc",
                "-"),
            keyframes,
            diagnostics);
    if (keyframes.isMissingTime()) {
      return ProbeAttemptReport.resultOnly(
          KeyframePositionsReport.withKeyframes(
              probed.result(),
              ProbeKeyframes.newBuilder()
                  .setUnavailable(
                      KeyframesUnavailable.newBuilder()
                          .setReason(
                              KeyframesUnavailableReason
                                  .KEYFRAMES_UNAVAILABLE_REASON_KEYFRAME_WITHOUT_TIMESTAMP))));
    }

    if (exitCode != 0) {
      throw new PacketScanException("FFmpeg exited with code " + exitCode);
    }

    if (diagnostics.isDemuxingFailed()) {
      throw new PacketScanException("FFmpeg stopped reading the source before its end");
    }

    return KeyframePositionsReport.builder()
        .probed(probed)
        .streamIndex(chosen.get().streamIndex())
        .timeBase(timeBase)
        .source(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN)
        .keyframes(keyframes.times())
        .build()
        .report();
  }

  private static TimeBase timeBase(JsonNode stream) {
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
   * 3109, 0x5e1c1fb1}: the stream, decode time, presentation time, duration, size and checksum.
   * Flags and side data may follow, such as {@code F=0x0}.
   */
  @RequiredArgsConstructor
  private static final class PacketKeyframes implements PacketLines {

    private final TimeRule rule;
    private final List<KeyframeTime> times = new ArrayList<>();
    private boolean missingTime;

    /** A keyframe without a time decides the outcome, so the scan needs no lines after it. */
    @Override
    public boolean take(String line) {
      if (line.startsWith("#")) {
        return true;
      }

      var packet = Packet.parse(line);
      if (!packet.isKeyframe()) {
        return true;
      }

      var time = keyframeTime(packet.pts(), packet.dts());
      if (time.isEmpty()) {
        missingTime = true;
        times.clear();
        return false;
      }

      times.add(time.get());
      return true;
    }

    private Optional<KeyframeTime> keyframeTime(OptionalLong pts, OptionalLong dts) {
      var packetTimes =
          switch (rule) {
            case AS_REPORTED -> new PacketTimes(pts, dts);
            case DECODE_AT_PRESENTATION -> new PacketTimes(pts, pts);
            case EQUAL_WHEN_ONE_IS_MISSING ->
                new PacketTimes(pts.isPresent() ? pts : dts, dts.isPresent() ? dts : pts);
          };
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

    List<KeyframeTime> times() {
      return times;
    }
  }

  private record PacketTimes(OptionalLong presentation, OptionalLong decode) {}

  private record Packet(OptionalLong pts, OptionalLong dts, int flags) {

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
      return new Packet(time(fields.get(2)), time(fields.get(1)), flags);
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
