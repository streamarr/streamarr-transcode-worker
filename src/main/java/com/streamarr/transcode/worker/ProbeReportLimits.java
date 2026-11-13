package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailable;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailableReason;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.StreamMeasurementIntervals;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.probe.ProbeReports;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * The limits on a probe report that the server states when it accepts a worker session (ADR 0062).
 * The worker checks every probe report against them before it opens the report call. A limit of 0
 * states no limit.
 *
 * @param keyframes the most keyframes whose positions one probe report holds
 * @param measuredKeyframeIntervals the most measured keyframe intervals in one probe report, added
 *     up over its measured streams
 * @param reportLengthBytes the length of the longest encoded probe report that the server accepts
 * @param resultLengthBytes the length of the longest encoded result that the server accepts in a
 *     probe report
 */
@Slf4j
@Builder
record ProbeReportLimits(
    long keyframes,
    long measuredKeyframeIntervals,
    long reportLengthBytes,
    long resultLengthBytes) {

  static final ProbeReportLimits NONE = builder().build();

  // The contract's 32-bit limits are unsigned.
  static ProbeReportLimits of(WorkerSessionAccepted accepted) {
    return builder()
        .keyframes(Integer.toUnsignedLong(accepted.getKeyframeLimit()))
        .measuredKeyframeIntervals(
            Integer.toUnsignedLong(accepted.getMeasuredKeyframeIntervalLimit()))
        .reportLengthBytes(accepted.getProbeReportLengthLimitBytes())
        .resultLengthBytes(accepted.getProbeResultLengthLimitBytes())
        .build();
  }

  /**
   * The report that the worker sends for the probe attempt: the probe's own report when it stays
   * within the limits. Beyond the keyframe limit the report states the keyframes as unavailable and
   * holds neither keyframe positions nor a measurement. Beyond the interval limit a probe's or a
   * keyframe packet scan's report leaves the measurement out, and a bitrate scan chunk fails with
   * {@code PROBE_FAILURE_RESULT_TOO_LARGE}. A report that is then still longer than the length
   * limit, or whose result is then longer than the result length limit, gives way to one that holds
   * only that failure.
   */
  ProbeAttemptReport apply(ProbeRequest request, ProbeAttemptReport report) {
    var withinEntryLimits = withinIntervalLimit(request, withinKeyframeLimit(request, report));
    if (longerThanTheLengthLimit(request, withinEntryLimits)
        || resultLongerThanTheResultLengthLimit(request, withinEntryLimits)) {
      return ProbeReports.failed(request, ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE);
    }

    return withinEntryLimits;
  }

  private boolean longerThanTheLengthLimit(ProbeRequest request, ProbeAttemptReport report) {
    var length = report.getSerializedSize();
    if (!exceeds(length, reportLengthBytes)) {
      return false;
    }

    log.warn(
        "The report of probe attempt {} takes {} bytes, more than the {} bytes the server accepts",
        fromProto(request.getProbeAttemptId()),
        length,
        Long.toUnsignedString(reportLengthBytes));
    return true;
  }

  private boolean resultLongerThanTheResultLengthLimit(
      ProbeRequest request, ProbeAttemptReport report) {
    var length = report.getResult().getSerializedSize();
    if (!exceeds(length, resultLengthBytes)) {
      return false;
    }

    log.warn(
        "The result of probe attempt {} takes {} bytes, more than the {} bytes the server accepts"
            + " in a probe report",
        fromProto(request.getProbeAttemptId()),
        length,
        Long.toUnsignedString(resultLengthBytes));
    return true;
  }

  // The server encodes the video of a file whose keyframes are unavailable, so the file still
  // plays.
  private ProbeAttemptReport withinKeyframeLimit(ProbeRequest request, ProbeAttemptReport report) {
    var found = report.getKeyframePresentationTimeDeltasCount();
    if (!exceeds(found, keyframes)) {
      return report;
    }

    log.info(
        "Reporting the keyframes of probe attempt {} as unavailable: it found {} keyframes, more"
            + " than the {} the server accepts",
        fromProto(request.getProbeAttemptId()),
        found,
        keyframes);
    var unavailable =
        ProbeKeyframes.newBuilder()
            .setUnavailable(
                KeyframesUnavailable.newBuilder()
                    .setReason(
                        KeyframesUnavailableReason
                            .KEYFRAMES_UNAVAILABLE_REASON_TOO_MANY_KEYFRAMES));
    var result = report.getResult();
    return ProbeReports.resultOnly(
        result.toBuilder()
            .setMedia(
                result.getMedia().toBuilder().clearStreamMeasurements().setKeyframes(unavailable))
            .build());
  }

  private ProbeAttemptReport withinIntervalLimit(ProbeRequest request, ProbeAttemptReport report) {
    var measured =
        report.getStreamMeasurementIntervalsList().stream()
            .mapToLong(StreamMeasurementIntervals::getKeyframeIntervalBytesCount)
            .sum();
    if (!exceeds(measured, measuredKeyframeIntervals)) {
      return report;
    }

    var attemptId = fromProto(request.getProbeAttemptId());
    if (request.hasBitrateScanChunk()) {
      log.warn(
          "Failing the bitrate scan chunk of probe attempt {}: it measured {} keyframe intervals,"
              + " more than the {} the server accepts",
          attemptId,
          measured,
          measuredKeyframeIntervals);
      return ProbeReports.failed(request, ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE);
    }

    log.info(
        "Leaving the measurement out of the report of probe attempt {}: it holds {} keyframe"
            + " intervals, more than the {} the server accepts",
        attemptId,
        measured,
        measuredKeyframeIntervals);
    var result = report.getResult();
    return report.toBuilder()
        .clearStreamMeasurementIntervals()
        .setResult(
            result.toBuilder().setMedia(result.getMedia().toBuilder().clearStreamMeasurements()))
        .build();
  }

  private static boolean exceeds(long count, long limit) {
    return limit != 0 && Long.compareUnsigned(count, limit) > 0;
  }
}
