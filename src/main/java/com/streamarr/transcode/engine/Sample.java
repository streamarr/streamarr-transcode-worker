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

  /**
   * @throws FragmentedMp4Exception when the presentation time, the decode time plus the composition
   *     offset, overflows a signed 64-bit value
   */
  Sample {
    try {
      Math.addExact(decodeTime, compositionOffset);
    } catch (ArithmeticException _) {
      throw FragmentedMp4Exception.malformed("presentation time overflows a signed 64-bit value");
    }
  }

  long presentationTime() {
    return decodeTime + compositionOffset;
  }
}
