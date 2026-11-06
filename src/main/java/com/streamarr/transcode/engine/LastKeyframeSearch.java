package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.KeyframeScan.Keyframe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * A held encode's search for the video's last keyframe (ADR 0037, early ends). A seek position past
 * the video's last keyframe decodes no picture to hold, so when no keyframe presents between the
 * seek position and the first segment's start, the search reads intervals that run back from the
 * seek position, each twice as long as the one after it, the first one as long as the distance from
 * the seek position to the first segment's start. The first interval that holds a keyframe holds
 * the video's last keyframe. The search goes on the same way to the last keyframe at least 0.5 s
 * before that one, or to the video's first keyframe when none lies that far back, so that a
 * deinterlacer sees the same history before the held picture as in an attempt that reads from a
 * stored keyframe.
 */
final class LastKeyframeSearch {

  private static final Duration HISTORY_BEFORE_THE_LAST_KEYFRAME = Duration.ofMillis(500);
  private static final Comparator<Keyframe> BY_PRESENTATION_TIME =
      Comparator.comparing(Keyframe::presentationTime);

  private LastKeyframeSearch() {}

  /**
   * Where the attempt reads from: a seek position that reads the keyframe that the search finds,
   * never before zero; empty when the seek position is zero, which reads every keyframe, or when a
   * keyframe presents between the seek position and the first segment's start.
   *
   * @throws NoKeyframeBeforeReadBackException when no keyframe presents before the seek position
   */
  static Optional<Duration> readPosition(
      Duration seekPosition, Duration firstSegmentStart, KeyframeScan scan)
      throws InterruptedException {
    if (!seekPosition.isPositive()
        || firstSegmentStart.compareTo(seekPosition) <= 0
        || scan.firstKeyframe(seekPosition, firstSegmentStart).isPresent()) {
      return Optional.empty();
    }

    var found = new ArrayList<Keyframe>();
    var length = firstSegmentStart.minus(seekPosition);
    var end = seekPosition;
    while (end.isPositive()) {
      var start = end.minus(length);
      if (start.isNegative()) {
        start = Duration.ZERO;
      }

      found.addAll(scan.keyframes(start, end));
      var history = historyBeforeTheLastKeyframe(found);
      if (history.isPresent()) {
        return Optional.of(readPositionOf(history.get(), scan));
      }

      end = start;
      length = length.multipliedBy(2);
    }

    var first =
        found.stream()
            .min(BY_PRESENTATION_TIME)
            .orElseThrow(NoKeyframeBeforeReadBackException::new);
    return Optional.of(readPositionOf(first, scan));
  }

  // The latest keyframe found is the video's last one: each interval lies before the one before it.
  private static Optional<Keyframe> historyBeforeTheLastKeyframe(List<Keyframe> found) {
    return found.stream()
        .max(BY_PRESENTATION_TIME)
        .map(last -> last.presentationTime().minus(HISTORY_BEFORE_THE_LAST_KEYFRAME))
        .flatMap(
            latest ->
                found.stream()
                    .filter(keyframe -> keyframe.presentationTime().compareTo(latest) <= 0)
                    .max(BY_PRESENTATION_TIME));
  }

  // An MP4 demuxer seeks by presentation time, so a seek to the keyframe's decode time reads from
  // the keyframe before it, a whole GOP earlier. An MPEG-TS seek lands on decode times, so a seek
  // to the keyframe's presentation time can read past it. A raw H.264 stream's demuxer cannot seek
  // at all. The attempt reads from the first of the keyframe's presentation and decode times where
  // a seek lands at or before the keyframe, and from zero when neither does.
  private static Duration readPositionOf(Keyframe keyframe, KeyframeScan scan)
      throws InterruptedException {
    var candidates = List.of(keyframe.presentationTime(), keyframe.decodeTime());
    for (var candidate : candidates.stream().distinct().toList()) {
      if (!candidate.isPositive()) {
        return Duration.ZERO;
      }

      if (seekLandsAtOrBefore(keyframe, candidate, scan)) {
        return candidate;
      }
    }

    return Duration.ZERO;
  }

  private static boolean seekLandsAtOrBefore(Keyframe keyframe, Duration seek, KeyframeScan scan)
      throws InterruptedException {
    return scan.landingDecodeTime(seek)
        .filter(landing -> landing.compareTo(keyframe.decodeTime()) <= 0)
        .isPresent();
  }
}
