package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.StreamMeasurementIntervals;
import lombok.NonNull;

/** Builds the probe reports that a probe attempt ends with. */
public final class ProbeReports {

  private ProbeReports() {}

  /** A report that holds the result, and neither keyframe positions nor a measurement. */
  public static ProbeAttemptReport resultOnly(@NonNull ProbeAttemptResult result) {
    return ProbeAttemptReport.newBuilder().setResult(result).build();
  }

  /** The report of a probe attempt that failed before it probed anything. */
  public static ProbeAttemptReport failed(@NonNull ProbeRequest request, ProbeFailure failure) {
    return resultOnly(
        ProbeAttemptResult.newBuilder()
            .setProbeAttemptId(request.getProbeAttemptId())
            .setProbeVersion(request.getProbeVersion())
            .setFailure(failure)
            .build());
  }

  /**
   * One measured stream's unsigned 32-bit bytes and packet counts in each keyframe interval, in
   * interval order. Both arrays hold one entry for each interval.
   */
  static StreamMeasurementIntervals intervals(
      int streamIndex, int[] intervalBytes, int[] intervalPacketCounts) {
    var intervals = StreamMeasurementIntervals.newBuilder().setStreamIndex(streamIndex);
    for (var index = 0; index < intervalBytes.length; index++) {
      intervals
          .addKeyframeIntervalBytes(intervalBytes[index])
          .addKeyframeIntervalPacketCounts(intervalPacketCounts[index]);
    }

    return intervals.build();
  }
}
