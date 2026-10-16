package com.streamarr.transcode.probe;

import com.google.protobuf.Duration;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The time at which a stream ends, where the container header states the stream's duration, on the
 * probe's timeline. The duration is the stream's duration_ts, or else a Matroska track's DURATION
 * tag. The end is the stream's start time plus that duration.
 *
 * <p>FFmpeg's Matroska muxer writes a different value as DURATION: the track's last timestamp plus
 * that packet's duration, which is already the track's end. So when FFmpeg wrote the file, its
 * encoder tag starts with Lavf, and the DURATION tag gives the end without the start time.
 */
final class StreamEndTime {

  private static final Pattern MATROSKA_DURATION =
      Pattern.compile("(\\d+):([0-5]\\d):([0-5]\\d(?:\\.\\d+)?)");

  private final ProbeTimeline timeline;
  private final boolean durationTagsEndTracks;

  private StreamEndTime(ProbeTimeline timeline, boolean durationTagsEndTracks) {
    this.timeline = timeline;
    this.durationTagsEndTracks = durationTagsEndTracks;
  }

  /** The end times of the streams in the container that ffprobe's format section describes. */
  static StreamEndTime in(JsonNode format, ProbeTimeline timeline) {
    var writtenByFfmpeg =
        StreamTags.tag(format, "encoder").filter(encoder -> encoder.startsWith("Lavf")).isPresent();
    return new StreamEndTime(timeline, writtenByFfmpeg);
  }

  Optional<Duration> of(JsonNode stream) {
    var timeBase = ProbeTimeline.timeBase(stream, "time_base");
    var start = ProbeTimeline.ticks(stream, "start_pts");
    if (timeBase.isEmpty() || start.isEmpty()) {
      return Optional.empty();
    }

    var duration = ProbeTimeline.ticks(stream, "duration_ts");
    if (duration.isPresent()) {
      return Optional.of(timeline.at(start.get().add(duration.get()), timeBase.get()));
    }

    var tagStart = durationTagsEndTracks ? BigInteger.ZERO : start.get();
    return matroskaDuration(stream).map(seconds -> timeline.at(tagStart, timeBase.get(), seconds));
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
