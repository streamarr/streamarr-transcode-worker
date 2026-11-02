package com.streamarr.transcode.engine;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import lombok.NonNull;

/**
 * Where the chosen source streams ended in a completed attempt, as FFmpeg read them from the
 * source.
 *
 * @param streamEnds the end of each chosen stream whose last source sample the attempt read
 * @param endedEarly present exactly when the last media segment that holds a source sample comes
 *     before the variant's last advertised media segment
 */
public record SourceEnd(
    @NonNull List<StreamEnd> streamEnds, @NonNull Optional<EndedEarly> endedEarly) {

  public SourceEnd {
    streamEnds = List.copyOf(streamEnds);
  }

  /** Whether the source's media ends in a segment before the attempt's first one. */
  boolean endsBefore(int startSequenceNumber) {
    return endedEarly
        .filter(early -> early.lastSegmentWithSourceMedia() < startSequenceNumber)
        .isPresent();
  }

  /**
   * Where one chosen source stream ended.
   *
   * @param streamIndex the stream's index within the source container
   * @param endTime the end of its last sample, its presentation time plus its duration, on the
   *     attempt's zero-based timeline
   */
  public record StreamEnd(int streamIndex, @NonNull Duration endTime) {}

  /**
   * The source's media ends before the variant's last advertised media segment.
   *
   * @param lastSegmentWithSourceMedia the number of the last media segment that holds a source
   *     sample, which lies before the attempt's first segment when the attempt starts after every
   *     source sample
   * @param streamIndexesWithMediaAfterLastSegment the index of each chosen stream with a source
   *     sample at or after the end of that segment's interval, which no delivered media segment
   *     carries
   */
  public record EndedEarly(
      int lastSegmentWithSourceMedia,
      @NonNull List<Integer> streamIndexesWithMediaAfterLastSegment) {

    public EndedEarly {
      streamIndexesWithMediaAfterLastSegment = List.copyOf(streamIndexesWithMediaAfterLastSegment);
    }
  }
}
