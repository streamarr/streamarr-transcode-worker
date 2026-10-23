package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.ProbePriority;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;

/** The kind of a probe, which decides its capacity on the worker and its CPU priority. */
public enum ProbeKind {
  PLAYBACK,
  BACKGROUND,
  KEYFRAME_PACKET_SCAN;

  // A keyframe packet scan is never a playback probe, whatever its priority.
  public static ProbeKind of(ProbeRequest request) {
    if (request.getScanKeyframePackets()) {
      return KEYFRAME_PACKET_SCAN;
    }

    if (request.getPriority() == ProbePriority.PROBE_PRIORITY_PLAYBACK) {
      return PLAYBACK;
    }

    return BACKGROUND;
  }
}
