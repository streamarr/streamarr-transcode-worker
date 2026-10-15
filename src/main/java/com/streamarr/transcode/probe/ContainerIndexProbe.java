package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import build.buf.gen.streamarr.transcode.v1.KeyframePositionSource;
import build.buf.gen.streamarr.transcode.v1.KeyframePositions;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.Listed;
import com.streamarr.transcode.probe.KeyframeIndex.Unusable;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Adds the keyframe positions of the chosen video stream to a successful probe result, read from
 * the container index. A source whose index the worker cannot trust gets a packet-scan request
 * instead.
 */
@RequiredArgsConstructor
final class ContainerIndexProbe {

  /** The contract bounds each list of a ProbeKeyframePositionsPart to this many entries. */
  static final int MAXIMUM_KEYFRAMES_PER_PART = 4_096;

  private static final String MP4_FORMAT = "mov,mp4,m4a,3gp,3g2,mj2";
  private static final String MATROSKA_FORMAT = "matroska,webm";
  private static final BigInteger MICROSECONDS_PER_SECOND = BigInteger.valueOf(1_000_000);

  @NonNull private final ObjectMapper objectMapper;
  @NonNull private final FfprobeRunner ffprobe;

  @FunctionalInterface
  interface FfprobeRunner {
    ProcessOutput run(List<String> arguments) throws ExecutionException, InterruptedException;
  }

  /** A successful probe result together with the ffprobe output it came from. */
  @Builder
  record Probed(
      @NonNull Path source, @NonNull JsonNode ffprobeOutput, @NonNull ProbeAttemptResult result) {}

  ProbeAttemptReport addKeyframes(Probed probed)
      throws IOException, ExecutionException, InterruptedException {
    var chosen = ChosenVideoStream.find(probed.ffprobeOutput().path("streams"));
    if (chosen.isEmpty()) {
      return ProbeAttemptReport.resultOnly(
          probed.result().toBuilder()
              .clearMedia()
              .setFailure(ProbeFailure.PROBE_FAILURE_NO_VIDEO_STREAM)
              .build());
    }

    var index = readIndex(probed, chosen.get());
    return switch (index) {
      case Unusable(var reason) -> packetScanRequired(probed, reason);
      case Listed listed -> report(probed, chosen.get(), listed);
    };
  }

  private KeyframeIndex readIndex(Probed probed, ChosenVideoStream chosen) throws IOException {
    var format = probed.ffprobeOutput().path("format").path("format_name").asString("");
    return switch (format) {
      case MP4_FORMAT ->
          chosen.trackId().isPresent()
              ? Mp4SyncSampleReader.read(probed.source(), chosen.trackId().get())
              : new Unusable(
                  KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX);
      case MATROSKA_FORMAT -> MatroskaCueReader.read(probed.source(), chosen.videoOrdinal());
      default ->
          new Unusable(KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX);
    };
  }

  private ProbeAttemptReport report(Probed probed, ChosenVideoStream chosen, Listed listed)
      throws ExecutionException, InterruptedException {
    if (!isLastKeyframeReadable(probed.source(), chosen, listed)) {
      return packetScanRequired(
          probed,
          KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_UNREADABLE_LAST_INDEXED_KEYFRAME);
    }

    var start = containerStart(probed.ffprobeOutput(), listed.timeBase());
    var keyframes =
        listed.keyframes().stream()
            .map(
                keyframe ->
                    new KeyframeTime(
                        keyframe.presentationTime() - start, keyframe.decodeTime() - start))
            .toList();
    var positions =
        KeyframePositions.newBuilder()
            .setStreamIndex(chosen.streamIndex())
            .setTimeBaseNumerator(Math.toIntExact(listed.timeBase().numerator()))
            .setTimeBaseDenominator(Math.toIntExact(listed.timeBase().denominator()))
            .setSource(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_CONTAINER_INDEX)
            .setKeyframeCount(keyframes.size());
    return ProbeAttemptReport.builder()
        .keyframePositionsParts(parts(probed.result(), keyframes))
        .result(withKeyframes(probed.result(), ProbeKeyframes.newBuilder().setPositions(positions)))
        .build();
  }

  /**
   * Reads one packet of the chosen stream at the last indexed keyframe. A truncated file keeps an
   * index that lists keyframes past its real end, and the read then finds no such keyframe.
   */
  private boolean isLastKeyframeReadable(Path source, ChosenVideoStream chosen, Listed listed)
      throws ExecutionException, InterruptedException {
    var last = listed.keyframes().getLast();
    var output =
        ffprobe.run(
            List.of(
                "-v",
                "quiet",
                "-print_format",
                "json",
                "-select_streams",
                Integer.toString(chosen.streamIndex()),
                "-show_entries",
                "packet=pts,dts,flags",
                "-read_intervals",
                readInterval(last, listed.timeBase()),
                source.toString()));
    if (output.exitCode() != 0) {
      return false;
    }

    var packet = objectMapper.readTree(output.stdout()).path("packets").path(0);
    return packet.path("pts").isIntegralNumber()
        && packet.path("pts").asLong() == last.presentationTime()
        && packet.path("flags").asString("").startsWith("K");
  }

  /**
   * ffprobe seeks to the last keyframe at or before the interval's start, so the start rounds the
   * later of the keyframe's two times up to whole microseconds.
   */
  private static String readInterval(KeyframeTime keyframe, KeyframeIndex.TimeBase timeBase) {
    var ticks = Math.max(keyframe.presentationTime(), keyframe.decodeTime());
    var scaled =
        BigInteger.valueOf(ticks)
            .multiply(BigInteger.valueOf(timeBase.numerator()))
            .multiply(MICROSECONDS_PER_SECOND);
    var denominator = BigInteger.valueOf(timeBase.denominator());
    var microseconds =
        new BigDecimal(scaled).divide(new BigDecimal(denominator), 0, RoundingMode.CEILING);
    return microseconds.movePointLeft(6).setScale(6, RoundingMode.UNNECESSARY).toPlainString()
        + "%+#1";
  }

  /**
   * FFmpeg's -copyts -start_at_zero subtracts the container start time from every packet, rescaled
   * to the stream's time base with rounding half away from zero.
   */
  private static long containerStart(JsonNode ffprobeOutput, KeyframeIndex.TimeBase timeBase) {
    var startTime = ffprobeOutput.path("format").path("start_time").asString("");
    BigDecimal seconds;
    try {
      seconds = new BigDecimal(startTime);
    } catch (NumberFormatException _) {
      return 0;
    }

    return seconds
        .multiply(BigDecimal.valueOf(timeBase.denominator()))
        .divide(BigDecimal.valueOf(timeBase.numerator()), 0, RoundingMode.HALF_UP)
        .longValueExact();
  }

  private static List<ProbeKeyframePositionsPart> parts(
      ProbeAttemptResult result, List<KeyframeTime> keyframes) {
    var parts = new ArrayList<ProbeKeyframePositionsPart>();
    var previous = new KeyframeTime(0, 0);
    for (var first = 0; first < keyframes.size(); first += MAXIMUM_KEYFRAMES_PER_PART) {
      var part =
          ProbeKeyframePositionsPart.newBuilder().setProbeAttemptId(result.getProbeAttemptId());
      var end = Math.min(first + MAXIMUM_KEYFRAMES_PER_PART, keyframes.size());
      for (var keyframe : keyframes.subList(first, end)) {
        part.addPresentationTimeDeltas(keyframe.presentationTime() - previous.presentationTime());
        part.addDecodeTimeDeltas(keyframe.decodeTime() - previous.decodeTime());
        previous = keyframe;
      }

      parts.add(part.build());
    }

    return parts;
  }

  private static ProbeAttemptReport packetScanRequired(
      Probed probed, KeyframePacketScanReason reason) {
    return ProbeAttemptReport.resultOnly(
        withKeyframes(probed.result(), ProbeKeyframes.newBuilder().setPacketScanRequired(reason)));
  }

  private static ProbeAttemptResult withKeyframes(
      ProbeAttemptResult result, ProbeKeyframes.Builder keyframes) {
    return result.toBuilder()
        .setMedia(result.getMedia().toBuilder().setKeyframes(keyframes))
        .build();
  }

  /**
   * The chosen video stream: the first video stream that is not an attached picture. Its video
   * ordinal counts the video streams before it, and its track ID is the MP4 track_ID ffprobe
   * reports as the stream id.
   */
  private record ChosenVideoStream(int streamIndex, int videoOrdinal, Optional<Long> trackId) {

    static Optional<ChosenVideoStream> find(JsonNode streams) {
      var videoOrdinal = 0;
      var position = 0;
      for (var stream : streams) {
        var isVideo = "video".equals(stream.path("codec_type").asString(""));
        if (isVideo && stream.path("disposition").path("attached_pic").asInt() != 1) {
          return Optional.of(
              new ChosenVideoStream(
                  stream.path("index").asInt(position), videoOrdinal, trackId(stream)));
        }

        if (isVideo) {
          videoOrdinal++;
        }

        position++;
      }

      return Optional.empty();
    }

    private static Optional<Long> trackId(JsonNode stream) {
      var id = stream.path("id").asString("");
      if (!id.startsWith("0x")) {
        return Optional.empty();
      }

      try {
        return Optional.of(Long.parseLong(id.substring(2), 16));
      } catch (NumberFormatException _) {
        return Optional.empty();
      }
    }
  }
}
