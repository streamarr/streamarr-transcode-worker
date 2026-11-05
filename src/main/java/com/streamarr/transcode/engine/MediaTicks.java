package com.streamarr.transcode.engine;

import java.time.Duration;

/** Converts media times into ticks of a track's timescale. */
final class MediaTicks {

  private MediaTicks() {}

  /** The first tick of this timescale at or after the time. */
  // The nanoseconds of a Duration times a 32-bit timescale stay within a signed 64-bit value.
  static long firstTickAtOrAfter(Duration time, long timescale) {
    var wholeSeconds = Math.multiplyExact(time.getSeconds(), timescale);
    return Math.addExact(
        wholeSeconds, Math.ceilDiv(time.getNano() * timescale, Duration.ofSeconds(1).toNanos()));
  }
}
