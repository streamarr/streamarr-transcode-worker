package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.KeyframePositionSource;
import build.buf.gen.streamarr.transcode.v1.KeyframePositions;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;
import tools.jackson.databind.JsonNode;

/**
 * The keyframe positions of the chosen video stream, added to a successful probe result. Times
 * count ticks of the time base on the demuxer's timeline, before the container start time is
 * subtracted.
 */
@Builder
record KeyframePositionsReport(
    @NonNull ProbedSource probed,
    int streamIndex,
    @NonNull TimeBase timeBase,
    @NonNull KeyframePositionSource source,
    @NonNull List<KeyframeTime> keyframes) {

  /** The contract bounds each list of a ProbeKeyframePositionsPart to this many entries. */
  static final int MAXIMUM_KEYFRAMES_PER_PART = 4_096;

  KeyframePositionsReport {
    keyframes = List.copyOf(keyframes);
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
    return ProbeAttemptReport.builder()
        .keyframePositionsParts(parts(probed.result(), zeroBased))
        .result(withKeyframes(probed.result(), ProbeKeyframes.newBuilder().setPositions(positions)))
        .build();
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
  private static long containerStart(JsonNode ffprobeOutput, TimeBase timeBase) {
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
