package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.SourceEnd.EndedEarly;
import com.streamarr.transcode.engine.SourceEnd.StreamEnd;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * Decides where an attempt's source ended from what FFmpeg read of its chosen streams. An attempt
 * that holds no frame delivers only source samples, so its last delivered media segment is the last
 * one that holds source media. Before it delivers any, the last segment is the one whose interval
 * holds the start of the last keyframe that a stream copy reads, as a copy opens its segments only
 * at keyframes, or of the last video frame that an encode reads. An encode that holds the last
 * frame carries every stream's samples to the advertised end, so the latest of them decides: the
 * last instant that the video's or encoded audio's last sample covers, as the encode converts each
 * sample over its whole duration, or the start of the last copied audio packet, which the producer
 * places whole by its start.
 *
 * <p>The segment that holds the video's last keyframe or frame carries every video sample after it,
 * so only the audio can have source media after the last segment: encoded audio whose last sample
 * covers an instant at or after that segment's end, or copied audio whose last packet starts there.
 */
@Builder
record SourceEndRule(
    int periodSeconds, int mediaSegmentCount, @NonNull VideoDecision video, boolean copiesAudio) {

  SourceEnd reportOn(List<ObservedSourceStream> streams, OptionalInt lastDeliveredMediaSegment) {
    var ends =
        streams.stream().map(stream -> new StreamEnd(stream.sourceIndex(), stream.end())).toList();
    var endedEarly =
        lastSegmentWithSourceMedia(streams, lastDeliveredMediaSegment)
            .filter(segment -> segment >= 0 && segment < mediaSegmentCount - 1L)
            .map(segment -> endedEarlyIn(segment, streams));
    return new SourceEnd(ends, endedEarly);
  }

  private Optional<Long> lastSegmentWithSourceMedia(
      List<ObservedSourceStream> streams, OptionalInt lastDeliveredMediaSegment) {
    if (video instanceof VideoDecision.Encode encode && encode.holdsLastFrameToAdvertisedEnd()) {
      return streams.stream()
          .map(this::lastSourceInstant)
          .max(Comparator.naturalOrder())
          .map(this::segmentAt);
    }

    if (lastDeliveredMediaSegment.isPresent()) {
      return Optional.of((long) lastDeliveredMediaSegment.getAsInt());
    }

    var videoStream =
        streams.stream().filter(stream -> stream.sourceIndex() == video.streamIndex()).findFirst();
    return switch (video) {
      case VideoDecision.Copy _ ->
          videoStream.flatMap(ObservedSourceStream::lastKeyframeStart).map(this::segmentAt);
      case VideoDecision.Encode _ ->
          videoStream.map(ObservedSourceStream::lastStart).map(this::segmentAt);
    };
  }

  private EndedEarly endedEarlyIn(long segment, List<ObservedSourceStream> streams) {
    var segmentEnd = Duration.ofSeconds((segment + 1) * periodSeconds);
    var streamsWithMediaAfter =
        streams.stream()
            .filter(stream -> stream.sourceIndex() != video.streamIndex())
            .filter(stream -> lastSourceInstant(stream).compareTo(segmentEnd) >= 0)
            .map(ObservedSourceStream::sourceIndex)
            .sorted()
            .toList();
    return new EndedEarly(Math.toIntExact(segment), streamsWithMediaAfter);
  }

  private Duration lastSourceInstant(ObservedSourceStream stream) {
    if (copiesAudio && stream.sourceIndex() != video.streamIndex()) {
      return stream.lastStart();
    }

    return lastCoveredInstant(stream);
  }

  // The last nanosecond that the stream's last sample covers; its start when it lasts no time.
  private static Duration lastCoveredInstant(ObservedSourceStream stream) {
    var beforeEnd = stream.end().minusNanos(1);
    if (beforeEnd.compareTo(stream.lastStart()) < 0) {
      return stream.lastStart();
    }

    return beforeEnd;
  }

  private long segmentAt(Duration presentationTime) {
    return Math.floorDiv(presentationTime.toNanos(), Duration.ofSeconds(periodSeconds).toNanos());
  }
}
