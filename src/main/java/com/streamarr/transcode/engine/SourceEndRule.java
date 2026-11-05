package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.SourceEnd.EndedEarly;
import com.streamarr.transcode.engine.SourceEnd.StreamEnd;
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
 * at keyframes, or the last instant that the last video frame an encode reads covers, as the encode
 * repeats that frame at the output rate up to its end. An encode that holds the last frame carries
 * every stream's samples to the advertised end, so the latest of them decides: the last instant
 * that the video's or encoded audio's last sample covers, as the encode converts each sample over
 * its whole duration, or the start of the last copied audio packet, which the producer places whole
 * by its start.
 *
 * <p>The segment that holds the video's last keyframe or frame carries every video sample after it,
 * so only the audio can have source media after the last segment: encoded audio whose last sample
 * covers an instant at or after that segment's end, or copied audio whose last packet starts there.
 */
@Builder
record SourceEndRule(
    @NonNull SegmentIntervals intervals,
    int mediaSegmentCount,
    @NonNull VideoDecision video,
    boolean copiesAudio) {

  SourceEnd reportOn(List<ObservedSourceStream> streams, OptionalInt lastDeliveredMediaSegment) {
    var ends =
        streams.stream()
            .map(
                stream ->
                    new StreamEnd(stream.sourceIndex(), stream.end().truncatedToNanoseconds()))
            .toList();
    var endedEarly =
        lastSegmentWithSourceMedia(streams, lastDeliveredMediaSegment)
            .filter(segment -> segment >= 0 && segment < mediaSegmentCount - 1L)
            .map(segment -> endedEarlyIn(segment, streams));
    return new SourceEnd(ends, endedEarly);
  }

  private Optional<Long> lastSegmentWithSourceMedia(
      List<ObservedSourceStream> streams, OptionalInt lastDeliveredMediaSegment) {
    if (video instanceof VideoDecision.Encode encode && encode.holdsLastFrameToAdvertisedEnd()) {
      return streams.stream().map(this::segmentOfLastSourceInstant).max(Comparator.naturalOrder());
    }

    if (lastDeliveredMediaSegment.isPresent()) {
      return Optional.of((long) lastDeliveredMediaSegment.getAsInt());
    }

    var videoStream =
        streams.stream().filter(stream -> stream.sourceIndex() == video.streamIndex()).findFirst();
    return switch (video) {
      case VideoDecision.Copy _ ->
          videoStream.flatMap(ObservedSourceStream::lastKeyframeStart).map(intervals::numberAt);
      case VideoDecision.Encode _ -> videoStream.map(this::segmentOfLastCoveredInstant);
    };
  }

  private EndedEarly endedEarlyIn(long segment, List<ObservedSourceStream> streams) {
    var segmentEnd = intervals.endTimeOf(segment).orElseThrow();
    var streamsWithMediaAfter =
        streams.stream()
            .filter(stream -> stream.sourceIndex() != video.streamIndex())
            .filter(stream -> hasSourceMediaFrom(stream, segmentEnd))
            .map(ObservedSourceStream::sourceIndex)
            .sorted()
            .toList();
    return new EndedEarly(Math.toIntExact(segment), streamsWithMediaAfter);
  }

  private long segmentOfLastSourceInstant(ObservedSourceStream stream) {
    if (placesWholeByStart(stream)) {
      return intervals.numberAt(stream.lastStart());
    }

    return segmentOfLastCoveredInstant(stream);
  }

  // The last instant that the stream's last sample covers lies just before its end, or at its
  // start when it lasts no time.
  private long segmentOfLastCoveredInstant(ObservedSourceStream stream) {
    return Math.max(
        intervals.numberJustBefore(stream.end()), intervals.numberAt(stream.lastStart()));
  }

  private boolean hasSourceMediaFrom(ObservedSourceStream stream, MediaTime time) {
    if (placesWholeByStart(stream)) {
      return stream.lastStart().compareTo(time) >= 0;
    }

    return stream.end().compareTo(time) > 0 || stream.lastStart().compareTo(time) >= 0;
  }

  // The producer places a copied audio packet whole by its start.
  private boolean placesWholeByStart(ObservedSourceStream stream) {
    return copiesAudio && stream.sourceIndex() != video.streamIndex();
  }
}
