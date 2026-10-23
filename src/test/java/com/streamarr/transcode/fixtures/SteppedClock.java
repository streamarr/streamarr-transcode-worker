package com.streamarr.transcode.fixtures;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock that stands still until the test advances it. Safe to read from any thread. */
public final class SteppedClock extends Clock {

  private volatile Instant now = Instant.parse("2026-10-16T12:00:00Z");

  public void advance(Duration duration) {
    now = now.plus(duration);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return now;
  }
}
