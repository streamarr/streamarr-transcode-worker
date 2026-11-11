package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.KeyframeScan.Keyframe;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * The search for the keyframe that an encode from a later segment reads from when the server stores
 * none of the source's keyframes (ADR 0037, seek): the last keyframe of the chosen video that
 * presents at or before the search time. The search scans intervals that run back from that time,
 * the first one a segment period long and each one twice as long as the one after it, until one
 * holds such a keyframe. An encode that holds the last picture past the video's end reads from the
 * last keyframe at least 0.5 s before the video's last keyframe instead, so that a deinterlacer
 * sees the history before the held picture that an attempt from an earlier segment gives it.
 */
final class LastKeyframeSearch {

  private static final Duration HISTORY_BEFORE_THE_LAST_KEYFRAME = Duration.ofMillis(500);
  // How far past the first segment's start the first scan of a held encode reads, to see whether
  // the video goes on there.
  private static final Duration PAST_THE_FIRST_SEGMENTS_START = Duration.ofSeconds(1);
  // FFmpeg reads times in whole microseconds.
  private static final Duration ONE_MICROSECOND = Duration.ofNanos(1_000);
  private static final Comparator<Keyframe> BY_PRESENTATION_TIME =
      Comparator.comparing(Keyframe::presentationTime);

  private LastKeyframeSearch() {}

  /** What the search looks for, on the attempt's zero-based timeline. */
  @Builder
  record Target(
      @NonNull Duration searchTime,
      @NonNull Duration firstSegmentStart,
      @NonNull Duration firstInterval,
      boolean holdsTheLastPicture) {

    Target {
      if (!firstInterval.isPositive()) {
        throw new IllegalArgumentException(
            "The search's first interval must be positive, got: " + firstInterval);
      }
    }
  }

  /**
   * Where the attempt reads from: a seek position at which the attempt's own seek lands at or
   * before the keyframe that the search finds, or zero, which reads from the source's start, when
   * no keyframe presents early enough or no seek to the keyframe lands at or before it.
   */
  static Duration readPosition(Target target, KeyframeScan scan) throws InterruptedException {
    var found = new ArrayList<Keyframe>();
    var until = firstScanEnd(target);
    var from = atOrAfterZero(target.searchTime().minus(target.firstInterval()));
    var length = target.firstInterval();
    var scanned = scan.scan(from, until);
    var latest = Optional.of(target.searchTime());
    if (target.holdsTheLastPicture() && !reaches(scanned, target.firstSegmentStart())) {
      latest = Optional.empty();
    }

    while (true) {
      found.addAll(scanned.keyframes());
      if (latest.isEmpty() && !found.isEmpty()) {
        latest = Optional.of(historyBeforeTheLastKeyframe(found, target.searchTime()));
      }

      var keyframe = latest.flatMap(time -> lastPresentingAtOrBefore(found, time));
      if (keyframe.isPresent()) {
        return readPositionOf(keyframe.get(), scan);
      }

      if (from.isZero()) {
        return Duration.ZERO;
      }

      until = from;
      length = length.multipliedBy(2);
      from = atOrAfterZero(until.minus(length));
      scanned = scan.scan(from, until);
    }
  }

  // A held encode's first scan reads past the first segment's start, to see whether the video
  // goes on there; any other scan ends a microsecond after the search time, so that a keyframe that
  // decodes at that time is read.
  private static Duration firstScanEnd(Target target) {
    if (target.holdsTheLastPicture()) {
      return target.firstSegmentStart().plus(PAST_THE_FIRST_SEGMENTS_START);
    }

    return target.searchTime().plus(ONE_MICROSECOND);
  }

  private static boolean reaches(KeyframeScan.IntervalScan scanned, Duration time) {
    return scanned.lastDecodeTime().filter(last -> last.compareTo(time) >= 0).isPresent();
  }

  // The scans run back from the search time, so the latest keyframe found first is the video's
  // last one.
  private static Duration historyBeforeTheLastKeyframe(List<Keyframe> found, Duration searchTime) {
    var history =
        found.stream()
            .max(BY_PRESENTATION_TIME)
            .orElseThrow()
            .presentationTime()
            .minus(HISTORY_BEFORE_THE_LAST_KEYFRAME);
    if (history.compareTo(searchTime) < 0) {
      return history;
    }

    return searchTime;
  }

  private static Optional<Keyframe> lastPresentingAtOrBefore(List<Keyframe> found, Duration time) {
    return found.stream()
        .filter(keyframe -> keyframe.presentationTime().compareTo(time) <= 0)
        .max(BY_PRESENTATION_TIME);
  }

  private static Duration atOrAfterZero(Duration time) {
    if (time.isNegative()) {
      return Duration.ZERO;
    }

    return time;
  }

  // An MP4 demuxer seeks by presentation time, so a seek to the keyframe's decode time reads from
  // the keyframe before it, a whole GOP earlier. An MPEG-TS or MPEG-PS seek lands on decode times,
  // so a seek to the keyframe's presentation time can read past it. A raw H.264 stream's demuxer
  // cannot seek at all. The attempt reads from the first of the keyframe's presentation and decode
  // times where a seek lands at or before the keyframe, and from zero when neither does.
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
