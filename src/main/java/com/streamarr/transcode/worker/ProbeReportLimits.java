package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.probe.ProbeReports;
import lombok.extern.slf4j.Slf4j;

/**
 * The limits on a probe report that the server states when it accepts a worker session (ADR 0062).
 * The worker checks every probe report against them before it opens the report call. A limit of 0
 * states no limit.
 *
 * @param reportLengthBytes the length of the longest encoded probe report that the server accepts
 */
@Slf4j
record ProbeReportLimits(long reportLengthBytes) {

  static final ProbeReportLimits NONE = new ProbeReportLimits(0);

  static ProbeReportLimits of(WorkerSessionAccepted accepted) {
    return new ProbeReportLimits(accepted.getProbeReportLengthLimitBytes());
  }

  /**
   * The report that the worker sends for the probe attempt: the probe's own report when it stays
   * within the limits, and otherwise a report that holds only the failure {@code
   * PROBE_FAILURE_RESULT_TOO_LARGE}.
   */
  ProbeAttemptReport apply(ProbeRequest request, ProbeAttemptReport report) {
    var length = report.getSerializedSize();
    if (!exceeds(length, reportLengthBytes)) {
      return report;
    }

    log.warn(
        "The report of probe attempt {} takes {} bytes, more than the {} bytes the server accepts",
        fromProto(request.getProbeAttemptId()),
        length,
        Long.toUnsignedString(reportLengthBytes));
    return ProbeReports.failed(request, ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE);
  }

  // The contract's limits are unsigned.
  private static boolean exceeds(long count, long limit) {
    return limit != 0 && Long.compareUnsigned(count, limit) > 0;
  }
}
