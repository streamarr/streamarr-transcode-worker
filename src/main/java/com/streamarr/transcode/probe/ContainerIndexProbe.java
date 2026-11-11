package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.number;
import static com.streamarr.transcode.probe.FfprobeJson.text;

import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import build.buf.gen.streamarr.transcode.v1.KeyframePositionSource;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.Listed;
import com.streamarr.transcode.probe.KeyframeIndex.Unusable;
import com.streamarr.transcode.probe.Mp4StreamMeasurer.TrackStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
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

  private static final BigInteger MICROSECONDS_PER_SECOND = BigInteger.valueOf(1_000_000);

  @NonNull private final ObjectMapper objectMapper;
  @NonNull private final FfprobeRunner ffprobe;

  @FunctionalInterface
  interface FfprobeRunner {
    ProcessOutput run(List<String> arguments) throws ExecutionException, InterruptedException;
  }

  ProbeAttemptReport addKeyframes(ProbedSource probed)
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
    return switch (index.keyframes()) {
      case Unusable(var reason) -> packetScanRequired(probed, reason);
      case Listed listed -> report(probed, chosen.get(), listed, index.measured());
    };
  }

  private ContainerIndex readIndex(ProbedSource probed, ChosenVideoStream chosen)
      throws IOException {
    return switch (probed.formatName()) {
      case ProbedSource.MP4_FORMAT ->
          chosen.trackId().isPresent()
              ? Mp4SyncSampleReader.read(
                  probed.source(),
                  TrackStream.builder()
                      .streamIndex(chosen.streamIndex())
                      .trackId(chosen.trackId().get())
                      .isRequired(true)
                      .build(),
                  measuredStreams(probed.ffprobeOutput().path("streams"), chosen))
              : ContainerIndex.keyframesOnly(
                  new Unusable(
                      KeyframePacketScanReason
                          .KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX));
      case ProbedSource.MATROSKA_FORMAT ->
          ContainerIndex.keyframesOnly(
              MatroskaCueReader.read(probed.source(), chosen.videoOrdinal()));
      default ->
          ContainerIndex.keyframesOnly(
              new Unusable(
                  KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX));
    };
  }

  /**
   * The streams that a measurement holds, each with the MP4 track that ffprobe names as its id and
   * the codec and sample rate that a copy's sample sizes depend on: the chosen video stream and
   * every audio stream, which it requires, and every other video stream that is not an attached
   * picture. A required stream without a track leaves nothing to measure.
   */
  private static List<TrackStream> measuredStreams(JsonNode streams, ChosenVideoStream chosen) {
    var measured = new ArrayList<TrackStream>();
    var position = 0;
    for (var stream : streams) {
      var index = stream.path("index").asInt(position++);
      var codecType = stream.path("codec_type").asString("");
      var isChosen = index == chosen.streamIndex();
      var isRequired = isChosen || "audio".equals(codecType);
      var isOtherVideo =
          !isChosen
              && "video".equals(codecType)
              && stream.path("disposition").path("attached_pic").asInt() != 1;
      var track = ChosenVideoStream.trackId(stream);
      if (isRequired && track.isEmpty()) {
        return List.of();
      }

      if ((isRequired || isOtherVideo) && track.isPresent()) {
        measured.add(
            TrackStream.builder()
                .streamIndex(index)
                .trackId(track.get())
                .isRequired(isRequired)
                .codecName(text(stream, "codec_name").orElse(""))
                .sampleRate(number(stream, "sample_rate", Long::parseLong).orElse(0L))
                .build());
      }
    }

    return measured;
  }

  private ProbeAttemptReport report(
      ProbedSource probed,
      ChosenVideoStream chosen,
      Listed listed,
      List<StreamMeasurement> measured)
      throws ExecutionException, InterruptedException {
    if (!isLastKeyframeReadable(probed.source(), chosen, listed)) {
      return packetScanRequired(
          probed,
          KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_UNREADABLE_LAST_INDEXED_KEYFRAME);
    }

    return KeyframePositionsReport.builder()
        .probed(probed)
        .streamIndex(chosen.streamIndex())
        .timeBase(listed.timeBase())
        .source(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_CONTAINER_INDEX)
        .keyframes(listed.keyframes())
        .measured(measured)
        .build()
        .report();
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

  private static ProbeAttemptReport packetScanRequired(
      ProbedSource probed, KeyframePacketScanReason reason) {
    return ProbeAttemptReport.resultOnly(
        KeyframePositionsReport.withKeyframes(
            probed.result(), ProbeKeyframes.newBuilder().setPacketScanRequired(reason)));
  }
}
