package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamMeasurementPart;
import java.util.List;
import lombok.Builder;
import lombok.NonNull;

/**
 * The session messages that one probe attempt produces. The worker sends the keyframe position
 * parts in list order, then the stream measurement parts in list order, then the result.
 */
@Builder
public record ProbeAttemptReport(
    @NonNull List<ProbeKeyframePositionsPart> keyframePositionsParts,
    List<ProbeStreamMeasurementPart> streamMeasurementParts,
    @NonNull ProbeAttemptResult result) {

  public ProbeAttemptReport {
    keyframePositionsParts = List.copyOf(keyframePositionsParts);
    streamMeasurementParts =
        streamMeasurementParts == null ? List.of() : List.copyOf(streamMeasurementParts);
  }

  public static ProbeAttemptReport resultOnly(@NonNull ProbeAttemptResult result) {
    return new ProbeAttemptReport(List.of(), List.of(), result);
  }
}
