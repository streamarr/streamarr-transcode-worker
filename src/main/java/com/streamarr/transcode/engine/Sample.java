package com.streamarr.transcode.engine;

import lombok.Builder;

/**
 * One sample of a fragment's track: its decode time and composition offset in the track's
 * timescale, its duration, size and flags, and where its bytes start in the fragment's {@code mdat}
 * box, counted from that box's first byte.
 */
@Builder
record Sample(
    long decodeTime,
    long compositionOffset,
    long duration,
    long size,
    int flags,
    int mediaDataPosition) {

  long presentationTime() {
    return decodeTime + compositionOffset;
  }
}
