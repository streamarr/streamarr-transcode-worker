package com.streamarr.transcode.worker;

import com.streamarr.transcode.engine.ProductionTiming;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.OptionalDouble;
import lombok.NonNull;

/**
 * One job attempt's production speed over the media segments that the attempt finished in the last
 * 15 s of wall-clock time, and at least its last 2 media segments, each weighted by its media
 * duration. The window judges the attempt only once the attempt has produced media segments for 15
 * s, because FFmpeg's startup and the source seek slow the first segments.
 */
final class ProductionSpeedWindow {

  static final Duration WINDOW = Duration.ofSeconds(15);
  private static final int MINIMUM_SEGMENTS = 2;

  private final Clock clock;
  private final Deque<FinishedSegment> segments = new ArrayDeque<>();
  private Duration produced = Duration.ZERO;

  ProductionSpeedWindow(@NonNull Clock clock) {
    this.clock = clock;
  }

  void add(@NonNull ProductionTiming timing) {
    segments.addLast(new FinishedSegment(clock.instant(), timing));
    produced = produced.plus(timing.productionTime());
  }

  /** The media seconds produced per wall-clock second; empty until the window can judge. */
  OptionalDouble judgedSpeed() {
    if (produced.compareTo(WINDOW) < 0 || segments.size() < MINIMUM_SEGMENTS) {
      return OptionalDouble.empty();
    }

    dropSegmentsBeforeTheWindow();
    var media = Duration.ZERO;
    var production = Duration.ZERO;
    for (var segment : segments) {
      media = media.plus(segment.timing().mediaDuration());
      production = production.plus(segment.timing().productionTime());
    }

    if (production.isZero()) {
      return OptionalDouble.of(Double.POSITIVE_INFINITY);
    }

    return OptionalDouble.of((double) media.toNanos() / production.toNanos());
  }

  private void dropSegmentsBeforeTheWindow() {
    var start = clock.instant().minus(WINDOW);
    while (segments.size() > MINIMUM_SEGMENTS
        && segments.peekFirst().finishedAt().isBefore(start)) {
      segments.removeFirst();
    }
  }

  private record FinishedSegment(Instant finishedAt, ProductionTiming timing) {}
}
