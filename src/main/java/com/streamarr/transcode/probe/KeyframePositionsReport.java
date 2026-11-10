package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.KeyframePositionSource;
import build.buf.gen.streamarr.transcode.v1.KeyframePositions;
import build.buf.gen.streamarr.transcode.v1.MeasuredStream;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import build.buf.gen.streamarr.transcode.v1.StreamMeasurementSource;
import build.buf.gen.streamarr.transcode.v1.StreamMeasurements;
import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import com.streamarr.transcode.probe.Mp4StreamMeasurer.TrackMeasurement;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import tools.jackson.databind.JsonNode;

/**
 * The keyframe positions of the chosen video stream, added to a successful probe result, with the
 * measurement of each video and audio stream when the container index gave one. Times count ticks
 * of the time base on the demuxer's timeline, before the container start time is subtracted.
 */
@Builder
record KeyframePositionsReport(
    @NonNull ProbedSource probed,
    int streamIndex,
    @NonNull TimeBase timeBase,
    @NonNull KeyframePositionSource source,
    @NonNull List<KeyframeTime> keyframes,
    List<TrackMeasurement> measured) {

  KeyframePositionsReport {
    keyframes = List.copyOf(keyframes);
    measured = measured == null ? List.of() : List.copyOf(measured);
  }

  ProbeAttemptReport report() {
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
            withKeyframes(probed.result(), ProbeKeyframes.newBuilder().setPositions(positions)),
            measured.stream()
                .filter(stream -> stream.streamIndex() == streamIndex)
                .findFirst()
                .flatMap(TrackMeasurement::maximumFrameRate));
    var report = withPositions(zeroBased);
    if (!isReportable(measured, zeroBased.size())) {
      return report.setResult(result).build();
    }

    measured.forEach(
        stream ->
            report.addStreamMeasurementIntervals(
                ProbeReports.intervals(
                    stream.streamIndex(), stream.intervalBytes(), stream.intervalPacketCounts())));
    return report
        .setResult(
            result.toBuilder()
                .setMedia(result.getMedia().toBuilder().setStreamMeasurements(measurements())))
        .build();
  }

  // Each keyframe's times as deltas from the keyframe before it, and the first one's from 0.
  private static ProbeAttemptReport.Builder withPositions(List<KeyframeTime> keyframes) {
    var report = ProbeAttemptReport.newBuilder();
    var previous = new KeyframeTime(0, 0);
    for (var keyframe : keyframes) {
      report
          .addKeyframePresentationTimeDeltas(
              keyframe.presentationTime() - previous.presentationTime())
          .addKeyframeDecodeTimeDeltas(keyframe.decodeTime() - previous.decodeTime());
      previous = keyframe;
    }

    return report;
  }

  /**
   * Whether the contract can carry the measurement: each stream has one entry for each keyframe,
   * and the time bases fit. A measurement that cannot be carried is left out, and the result
   * reports no measurement.
   */
  private static boolean isReportable(List<TrackMeasurement> measured, int keyframeCount) {
    return !measured.isEmpty()
        && measured.stream()
            .allMatch(
                stream ->
                    stream.intervalBytes().length == keyframeCount
                        && stream.timescale() <= Integer.MAX_VALUE);
  }

  private StreamMeasurements.Builder measurements() {
    var measurements =
        StreamMeasurements.newBuilder()
            .setSource(StreamMeasurementSource.STREAM_MEASUREMENT_SOURCE_SAMPLE_TABLE);
    for (var stream : measured) {
      measurements.addStreams(
          MeasuredStream.newBuilder()
              .setStreamIndex(stream.streamIndex())
              .setMeasuredBytes(stream.measuredBytes())
              .setPacketCount(stream.packetCount())
              .setTimeBaseNumerator(1)
              .setTimeBaseDenominator((int) stream.timescale())
              .setMeasuredDuration(stream.measuredDuration()));
    }

    return measurements;
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
}
