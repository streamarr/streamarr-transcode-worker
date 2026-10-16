package com.streamarr.transcode.worker;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import lombok.NonNull;

/** The server did not accept the worker's session within the configured bound. */
public final class SessionAcceptanceTimeoutException extends TimeoutException {

  private static final List<DurationUnit> UNITS =
      List.of(
          new DurationUnit("m", Duration.ofMinutes(1)),
          new DurationUnit("s", Duration.ofSeconds(1)),
          new DurationUnit("ms", Duration.ofMillis(1)),
          new DurationUnit("us", Duration.ofNanos(1_000)),
          new DurationUnit("ns", Duration.ofNanos(1)));

  SessionAcceptanceTimeoutException(@NonNull String host, int port, @NonNull Duration timeout) {
    super(
        "The Streamarr server at %s:%d did not accept the worker session within %s"
            .formatted(host, port, inSettingFormat(timeout)));
  }

  // The format of the worker's duration settings: a whole number of the largest exact unit.
  private static String inSettingFormat(Duration timeout) {
    var nanos = timeout.toNanos();
    var unit =
        UNITS.stream()
            .filter(candidate -> nanos % candidate.nanos() == 0)
            .findFirst()
            .orElseThrow();
    return nanos / unit.nanos() + unit.symbol();
  }

  private record DurationUnit(String symbol, Duration length) {

    private long nanos() {
      return length.toNanos();
    }
  }
}
