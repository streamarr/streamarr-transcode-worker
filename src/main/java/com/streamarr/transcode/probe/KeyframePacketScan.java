package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.KeyframePositionSource;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailable;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailableReason;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import java.util.ArrayList;
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
 * scanning every packet of that stream. ffprobe prints one line per packet, and the scan keeps only
 * the keyframes, so its memory grows with the keyframe count rather than the packet count.
 */
@RequiredArgsConstructor
final class KeyframePacketScan {

  private static final String NO_VALUE = "N/A";

  @NonNull private final PacketLineRunner ffprobe;

  @FunctionalInterface
  interface PacketLineRunner {
    /**
     * Runs ffprobe, passes each line of its standard output to the consumer, and returns the exit
     * code.
     */
    int run(List<String> arguments, Consumer<String> lines)
        throws ExecutionException, InterruptedException;
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
    var exitCode =
        ffprobe.run(
            List.of(
                "-v",
                "quiet",
                "-select_streams",
                Integer.toString(chosen.get().streamIndex()),
                "-show_entries",
                "packet=pts,dts,flags",
                "-print_format",
                "compact=print_section=0",
                probed.source().toString()),
            keyframes);
    if (exitCode != 0) {
      throw new PacketScanException("ffprobe exited with code " + exitCode);
    }

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
    var parts = stream.path("time_base").asString("").split("/");
    try {
      if (parts.length == 2) {
        return new TimeBase(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
      }
    } catch (NumberFormatException _) {
      // Reported below with the value that could not be read.
    }

    throw new PacketScanException("ffprobe reported an unreadable time base: " + stream);
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
   * Collects the keyframes from ffprobe's compact packet lines, such as pts=0|dts=-1024|flags=K__.
   */
  @RequiredArgsConstructor
  private static final class PacketKeyframes implements Consumer<String> {

    private final TimeRule rule;
    private final List<KeyframeTime> times = new ArrayList<>();
    private boolean missingTime;

    @Override
    public void accept(String line) {
      var fields = Fields.parse(line);
      if (missingTime || !fields.flags().startsWith("K")) {
        return;
      }

      var time = keyframeTime(fields.pts(), fields.dts());
      if (time.isEmpty()) {
        missingTime = true;
        times.clear();
        return;
      }

      times.add(time.get());
    }

    private Optional<KeyframeTime> keyframeTime(OptionalLong pts, OptionalLong dts) {
      var presentation = pts;
      var decode = dts;
      switch (rule) {
        case DECODE_AT_PRESENTATION -> decode = pts;
        case EQUAL_WHEN_ONE_IS_MISSING -> {
          presentation = pts.isPresent() ? pts : dts;
          decode = dts.isPresent() ? dts : pts;
        }
        case AS_REPORTED -> {
          // Both times come from the packet.
        }
      }

      if (presentation.isEmpty() || decode.isEmpty()) {
        return Optional.empty();
      }

      return Optional.of(new KeyframeTime(presentation.getAsLong(), decode.getAsLong()));
    }

    boolean isMissingTime() {
      return missingTime;
    }

    List<KeyframeTime> times() {
      return times;
    }
  }

  private record Fields(OptionalLong pts, OptionalLong dts, String flags) {

    static Fields parse(String line) {
      var pts = OptionalLong.empty();
      var dts = OptionalLong.empty();
      var flags = "";
      for (var field : line.split("\\|")) {
        var separator = field.indexOf('=');
        if (separator < 0) {
          continue;
        }

        var value = field.substring(separator + 1);
        switch (field.substring(0, separator)) {
          case "pts" -> pts = time(value);
          case "dts" -> dts = time(value);
          case "flags" -> flags = value;
          default -> {
            // ffprobe prints only the requested entries.
          }
        }
      }

      return new Fields(pts, dts, flags);
    }

    private static OptionalLong time(String value) {
      if (NO_VALUE.equals(value)) {
        return OptionalLong.empty();
      }

      return OptionalLong.of(Long.parseLong(value));
    }
  }
}
