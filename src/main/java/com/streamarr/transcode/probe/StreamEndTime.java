package com.streamarr.transcode.probe;

import com.google.protobuf.Duration;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The time at which a stream ends, where the container header states the stream's duration: the
 * stream's start time plus that duration, on the probe's timeline. The duration is the stream's
 * duration_ts, or else a Matroska track's DURATION tag.
 */
final class StreamEndTime {

  private static final Pattern MATROSKA_DURATION =
      Pattern.compile("(\\d+):([0-5]\\d):([0-5]\\d(?:\\.\\d+)?)");

  private StreamEndTime() {}

  static Optional<Duration> of(JsonNode stream, ProbeTimeline timeline) {
    var timeBase = ProbeTimeline.timeBase(stream, "time_base");
    var start = ProbeTimeline.ticks(stream, "start_pts");
    if (timeBase.isEmpty() || start.isEmpty()) {
      return Optional.empty();
    }

    var duration = ProbeTimeline.ticks(stream, "duration_ts");
    if (duration.isPresent()) {
      return Optional.of(timeline.at(start.get().add(duration.get()), timeBase.get()));
    }

    return matroskaDuration(stream)
        .map(seconds -> timeline.at(start.get(), timeBase.get(), seconds));
  }

  /** The DURATION tag, with or without a language suffix such as DURATION-eng, in seconds. */
  private static Optional<BigDecimal> matroskaDuration(JsonNode stream) {
    return StreamTags.matroskaStatistic(stream, "DURATION")
        .map(MATROSKA_DURATION::matcher)
        .filter(Matcher::matches)
        .map(
            match ->
                new BigDecimal(match.group(3))
                    .add(BigDecimal.valueOf(Long.parseLong(match.group(2)) * 60))
                    .add(BigDecimal.valueOf(Long.parseLong(match.group(1)) * 3600)));
  }
}
