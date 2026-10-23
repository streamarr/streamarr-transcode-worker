package com.streamarr.transcode.fixtures;

import com.streamarr.transcode.engine.MonotonicClock;
import java.time.Duration;

/** A monotonic clock that stands still until the test advances it. Safe to read from any thread. */
public final class SteppedClock implements MonotonicClock {

  // A reading far from zero, as System.nanoTime() gives, so no code may treat zero as unset.
  private volatile long nanos = 7_000_000_000_000L;

  public void advance(Duration duration) {
    nanos += duration.toNanos();
  }

  @Override
  public long nanoTime() {
    return nanos;
  }
}
