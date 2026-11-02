package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.SourceEnd.EndedEarly;
import com.streamarr.transcode.engine.SourceEnd.StreamEnd;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * Decides where an attempt's source ended from what FFmpeg read of its chosen streams. The last
 * media segment that holds a source sample is the one whose interval holds the start of the last
 * video sample that an encode reads, or of the last keyframe that a stream copy reads, as a copy
 * opens its segments only at keyframes. An encode that holds the last frame carries every stream's
 * samples to the advertised end, so the last of them decides.
 */
@Builder
record SourceEndRule(int periodSeconds, int mediaSegmentCount, @NonNull VideoDecision video) {

  SourceEnd reportOn(List<ObservedSourceStream> streams) {
    var ends =
        streams.stream().map(stream -> new StreamEnd(stream.sourceIndex(), stream.end())).toList();
    var endedEarly =
        lastSegmentWithSourceMedia(streams)
            .filter(segment -> segment >= 0 && segment < mediaSegmentCount - 1L)
            .map(segment -> endedEarlyIn(segment, streams));
    return new SourceEnd(ends, endedEarly);
  }

  private Optional<Long> lastSegmentWithSourceMedia(List<ObservedSourceStream> streams) {
    var videoStream =
        streams.stream().filter(stream -> stream.sourceIndex() == video.streamIndex()).findFirst();
    return switch (video) {
      case VideoDecision.Copy _ ->
          videoStream.flatMap(ObservedSourceStream::lastKeyframeStart).map(this::segmentAt);
      case VideoDecision.Encode encode when encode.holdsLastFrameToAdvertisedEnd() ->
          streams.stream()
              .map(ObservedSourceStream::lastStart)
              .max(Comparator.naturalOrder())
              .map(this::segmentAt);
      case VideoDecision.Encode _ ->
          videoStream.map(ObservedSourceStream::lastStart).map(this::segmentAt);
    };
  }

  private EndedEarly endedEarlyIn(long segment, List<ObservedSourceStream> streams) {
    var segmentEnd = Duration.ofSeconds((segment + 1) * periodSeconds);
    var streamsWithMediaAfter =
        streams.stream()
            .filter(stream -> stream.lastStart().compareTo(segmentEnd) >= 0)
            .map(ObservedSourceStream::sourceIndex)
            .sorted()
            .toList();
    return new EndedEarly(Math.toIntExact(segment), streamsWithMediaAfter);
  }

  private long segmentAt(Duration presentationTime) {
    return Math.floorDiv(presentationTime.toNanos(), Duration.ofSeconds(periodSeconds).toNanos());
  }
}
