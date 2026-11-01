package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamMeasurementPart;
import java.util.ArrayList;
import java.util.List;

/**
 * One stream's bytes and packet counts per keyframe interval, as the session messages carry them.
 */
final class StreamMeasurementParts {

  /** The contract bounds each list of a ProbeStreamMeasurementPart to this many entries. */
  static final int MAXIMUM_INTERVALS_PER_PART = 4_096;

  private StreamMeasurementParts() {}

  /**
   * The parts, in interval order, that carry one stream's unsigned 32-bit counts. {@code stream}
   * names the probe attempt and the stream; both arrays hold one entry for each keyframe interval.
   */
  static List<ProbeStreamMeasurementPart> of(
      ProbeStreamMeasurementPart stream, int[] intervalBytes, int[] intervalPacketCounts) {
    var parts = new ArrayList<ProbeStreamMeasurementPart>();
    for (var first = 0; first < intervalBytes.length; first += MAXIMUM_INTERVALS_PER_PART) {
      var part = stream.toBuilder();
      var end = Math.min(first + MAXIMUM_INTERVALS_PER_PART, intervalBytes.length);
      for (var i = first; i < end; i++) {
        part.addKeyframeIntervalBytes(intervalBytes[i])
            .addKeyframeIntervalPacketCounts(intervalPacketCounts[i]);
      }

      parts.add(part.build());
    }

    return parts;
  }
}
