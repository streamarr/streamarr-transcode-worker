package com.streamarr.transcode.probe;

import com.google.protobuf.Duration;
import java.math.BigInteger;
import java.util.Optional;
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

  private final ProbeTimeline timeline;
  private final boolean durationTagsEndTracks;
  private final boolean hasStreamDurations;

  private StreamEndTime(
      ProbeTimeline timeline, boolean durationTagsEndTracks, boolean hasStreamDurations) {
    this.timeline = timeline;
    this.durationTagsEndTracks = durationTagsEndTracks;
    this.hasStreamDurations = hasStreamDurations;
  }

  /** The end times of the streams in the container that ffprobe's format section describes. */
  static StreamEndTime in(JsonNode format, ProbeTimeline timeline) {
    var writtenByFfmpeg =
        StreamTags.tag(format, "encoder").filter(encoder -> encoder.startsWith("Lavf")).isPresent();
    // ASF sets every stream's duration_ts from the file's global play_time.
    var hasStreamDurations = !"asf".equals(format.path("format_name").asString(""));
    return new StreamEndTime(timeline, writtenByFfmpeg, hasStreamDurations);
  }

  Optional<Duration> of(JsonNode stream) {
    if (!hasStreamDurations) {
      return Optional.empty();
    }

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
    return StreamTags.matroskaDuration(stream)
        .map(seconds -> timeline.at(tagStart, timeBase.get(), seconds));
  }
}
