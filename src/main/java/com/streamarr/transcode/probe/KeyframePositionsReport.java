package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.KeyframePositionSource;
import build.buf.gen.streamarr.transcode.v1.KeyframePositions;
import build.buf.gen.streamarr.transcode.v1.MeasuredStream;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamMeasurementPart;
import build.buf.gen.streamarr.transcode.v1.SourceStreamEnd;
import build.buf.gen.streamarr.transcode.v1.StreamMeasurementSource;
import build.buf.gen.streamarr.transcode.v1.StreamMeasurements;
import com.google.protobuf.Duration;
import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;
import tools.jackson.databind.JsonNode;

/**
 * The keyframe positions of the chosen video stream, added to a successful probe result, with the
 * measurement of each video and audio stream when the container index gave one. Times count ticks
 * of the time base on the demuxer's timeline, before the container start time is subtracted. {@code
 * streamEnds} holds where each scanned stream's packets end, which only a packet scan reads.
 */
@Builder
record KeyframePositionsReport(
    @NonNull ProbedSource probed,
    int streamIndex,
    @NonNull TimeBase timeBase,
    @NonNull KeyframePositionSource source,
    @NonNull List<KeyframeTime> keyframes,
    List<StreamMeasurement> measured,
    List<ScannedStreamEnd> streamEnds) {

  /** The contract bounds each list of a ProbeKeyframePositionsPart to this many entries. */
  static final int MAXIMUM_KEYFRAMES_PER_PART = 4_096;

  /** The contract bounds the entries across one attempt's ProbeKeyframePositionsPart messages. */
  static final int MAXIMUM_KEYFRAMES_PER_PROBE = 1_048_576;

  /** The contract bounds the interval entries across one attempt's measurement parts. */
  static final long MAXIMUM_INTERVALS_PER_PROBE = 8_388_608;

  /** The contract bounds the streams that one attempt's measurement parts name. */
  static final int MAXIMUM_MEASURED_STREAMS = 1_024;

  /** The greatest whole seconds that a protobuf duration holds. */
  private static final long MAXIMUM_DURATION_SECONDS = 315_576_000_000L;

  KeyframePositionsReport {
    keyframes = List.copyOf(keyframes);
    measured = measured == null ? List.of() : List.copyOf(measured);
    streamEnds = streamEnds == null ? List.of() : List.copyOf(streamEnds);
  }

  ProbeAttemptReport report() {
    if (keyframes.size() > MAXIMUM_KEYFRAMES_PER_PROBE) {
      return ProbeAttemptReport.resultOnly(
          probed.result().toBuilder()
              .clearMedia()
              .setFailure(ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE)
              .build());
    }

    var start = containerStart(probed.ffprobeOutput(), timeBase);
    var zeroBased =
        keyframes.stream()
            .map(
                keyframe ->
                    new KeyframeTime(
                        keyframe.presentationTime() - start, keyframe.decodeTime() - start))
            .toList();
    var positions =
        KeyframePositions.newBuilder()
            .setStreamIndex(streamIndex)
            .setTimeBaseNumerator(Math.toIntExact(timeBase.numerator()))
            .setTimeBaseDenominator(Math.toIntExact(timeBase.denominator()))
            .setSource(source)
            .setKeyframeCount(zeroBased.size());
    var result =
        MaximumFrameRate.withMeasuredRate(
            withStreamEnds(
                withKeyframes(
                    probed.result(), ProbeKeyframes.newBuilder().setPositions(positions))),
            measured.stream()
                .filter(stream -> stream.streamIndex() == streamIndex)
                .findFirst()
                .flatMap(StreamMeasurement::maximumFrameRate));
    if (!isReportable(measured, zeroBased.size())) {
      return ProbeAttemptReport.builder()
          .keyframePositionsParts(parts(probed.result(), zeroBased))
          .result(result)
          .build();
    }

    return ProbeAttemptReport.builder()
        .keyframePositionsParts(parts(probed.result(), zeroBased))
        .streamMeasurementParts(measurementParts(result, measured))
        .result(
            result.toBuilder()
                .setMedia(result.getMedia().toBuilder().setStreamMeasurements(measurements()))
                .build())
        .build();
  }

  /**
   * The result with each scanned stream's end on the timeline of the keyframe positions. The
   * contract carries a positive end only, so an end at or before the container's start is left out,
   * as an unknown end is, and so is one beyond the contract's duration range.
   */
  private ProbeAttemptResult withStreamEnds(ProbeAttemptResult result) {
    var timeline = ProbeTimeline.of(probed.ffprobeOutput().path("format"));
    var media = result.getMedia().toBuilder();
    for (var end : streamEnds) {
      endTimeOf(end, timeline)
          .ifPresent(
              endTime ->
                  media.addStreamEnds(
                      SourceStreamEnd.newBuilder()
                          .setStreamIndex(end.streamIndex())
                          .setEndTime(endTime)));
    }

    return result.toBuilder().setMedia(media).build();
  }

  private static Optional<Duration> endTimeOf(ScannedStreamEnd end, ProbeTimeline timeline) {
    Duration endTime;
    try {
      endTime = timeline.at(BigInteger.valueOf(end.ticks()), end.timeBase());
    } catch (ArithmeticException _) {
      return Optional.empty();
    }

    var isPositive =
        endTime.getSeconds() > 0 || (endTime.getSeconds() == 0 && endTime.getNanos() > 0);
    if (!isPositive || endTime.getSeconds() > MAXIMUM_DURATION_SECONDS) {
      return Optional.empty();
    }

    return Optional.of(endTime);
  }

  /**
   * Whether the contract can carry the measurement: each stream has one entry for each keyframe,
   * the time bases fit, and the entries stay within the attempt's bounds. A measurement that cannot
   * be carried is left out, and the result reports no measurement.
   */
  private static boolean isReportable(List<StreamMeasurement> measured, int keyframeCount) {
    return !measured.isEmpty()
        && measured.size() <= MAXIMUM_MEASURED_STREAMS
        && (long) keyframeCount * measured.size() <= MAXIMUM_INTERVALS_PER_PROBE
        && measured.stream()
            .allMatch(
                stream ->
                    stream.intervalBytes().length == keyframeCount
                        && stream.timeBase().numerator() <= Integer.MAX_VALUE
                        && stream.timeBase().denominator() <= Integer.MAX_VALUE);
  }

  private StreamMeasurements.Builder measurements() {
    // A probe measures a file in the step that reads its keyframe positions.
    var measurements =
        StreamMeasurements.newBuilder()
            .setSource(
                source == KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN
                    ? StreamMeasurementSource.STREAM_MEASUREMENT_SOURCE_KEYFRAME_PACKET_SCAN
                    : StreamMeasurementSource.STREAM_MEASUREMENT_SOURCE_SAMPLE_TABLE);
    for (var stream : measured) {
      measurements.addStreams(
          MeasuredStream.newBuilder()
              .setStreamIndex(stream.streamIndex())
              .setMeasuredBytes(stream.measuredBytes())
              .setPacketCount(stream.packetCount())
              .setTimeBaseNumerator((int) stream.timeBase().numerator())
              .setTimeBaseDenominator((int) stream.timeBase().denominator())
              .setMeasuredDuration(stream.measuredDuration()));
    }

    return measurements;
  }

  private static List<ProbeStreamMeasurementPart> measurementParts(
      ProbeAttemptResult result, List<StreamMeasurement> measured) {
    var parts = new ArrayList<ProbeStreamMeasurementPart>();
    for (var stream : measured) {
      parts.addAll(
          StreamMeasurementParts.of(
              ProbeStreamMeasurementPart.newBuilder()
                  .setProbeAttemptId(result.getProbeAttemptId())
                  .setStreamIndex(stream.streamIndex())
                  .build(),
              stream.intervalBytes(),
              stream.intervalPacketCounts()));
    }

    return parts;
  }

  static ProbeAttemptResult withKeyframes(
      ProbeAttemptResult result, ProbeKeyframes.Builder keyframes) {
    return result.toBuilder()
        .setMedia(result.getMedia().toBuilder().setKeyframes(keyframes))
        .build();
  }

  /**
   * FFmpeg's -copyts -start_at_zero subtracts the container start time from every packet, rescaled
   * to the stream's time base with rounding half away from zero.
   */
  static long containerStart(JsonNode ffprobeOutput, TimeBase timeBase) {
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
}
