package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.KeyframeScan.Keyframe;
import com.streamarr.transcode.engine.KeyframeScan.ReadCheck;
import com.streamarr.transcode.engine.KeyframeScan.SeekRead;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import lombok.Builder;
import lombok.NonNull;

/**
 * Where an encode from a later segment reads the source from (ADR 0037, seek): from the last
 * keyframe of the chosen video that presents at or before the search time and from which the
 * attempt's own read shows a picture at or before that time. A check decodes the read from a seek
 * position up to its first picture. The attempt keeps the server's stored seek position when its
 * read shows a picture in time. Otherwise the search scans intervals that run back from the search
 * time, the first one a segment period long and each one twice as long as the one after it, until
 * one holds a keyframe that presents early enough. The attempt reads from the first of that
 * keyframe's presentation time, its decode time and FFmpeg's B-frame read-back before its decode
 * time whose read shows a picture in time. When none does, as when a decoder that starts at a
 * recovery point shows its first picture later, the search goes back to the keyframe before. An
 * encode that holds the last picture past the video's end reads from the last keyframe at least 0.5
 * s before the video's last keyframe instead, so that a deinterlacer sees the history before the
 * held picture that an attempt from an earlier segment gives it.
 */
final class LastKeyframeSearch {

  private static final Duration HISTORY_BEFORE_THE_LAST_KEYFRAME = Duration.ofMillis(500);
  // How far past the first segment's start the first scan of a held encode reads, to see whether
  // the video goes on there.
  private static final Duration PAST_THE_FIRST_SEGMENTS_START = Duration.ofSeconds(1);
  // FFmpeg reads times in whole microseconds.
  private static final Duration ONE_MICROSECOND = Duration.ofNanos(1_000);
  // FFmpeg's command line tool moves a seek 3/23 s earlier when its probe saw a video stream's
  // B-frames. A probe that decodes no picture of such a stream misses them, and a seek to a
  // keyframe's exact decode time then reads from the packet after the keyframe.
  private static final Duration B_FRAME_READ_BACK = Duration.ofNanos(130_434_783);
  // A source whose decoder shows no picture in time from this many keyframes in a row reads from
  // its start.
  private static final int MOST_KEYFRAMES_CHECKED = 4;
  private static final Comparator<Keyframe> BY_PRESENTATION_TIME =
      Comparator.comparing(Keyframe::presentationTime);

  private LastKeyframeSearch() {}

  /**
   * What the search looks for, on the attempt's zero-based timeline, and the server's stored seek
   * position, which lies on the source's timeline.
   */
  @Builder
  record Target(
      @NonNull Duration searchTime,
      @NonNull Duration firstSegmentStart,
      @NonNull Duration firstInterval,
      @NonNull Optional<Duration> storedSeekPosition,
      boolean holdsTheLastPicture) {

    Target {
      if (!firstInterval.isPositive()) {
        throw new IllegalArgumentException(
            "The search's first interval must be positive, got: " + firstInterval);
      }
    }
  }

  /**
   * Where the attempt reads from: the stored seek position when its read shows a picture in time,
   * else a seek position whose read shows a picture in time from a keyframe that the search finds,
   * or zero, which reads from the source's start.
   */
  static Duration readPosition(Target target, KeyframeScan scan) throws InterruptedException {
    var stored = target.storedSeekPosition();
    if (stored.isPresent() && showsAPictureInTime(stored.get(), target, scan)) {
      return stored.get();
    }

    return new Search(target, scan).readPosition();
  }

  // A read whose decoder shows a picture by the search time decodes every picture of the first
  // segment, wherever it lands. The stored position's read almost always does, so it runs without
  // the landing check, which only ends a late read sooner, and starts the attempt one FFmpeg
  // sooner.
  private static boolean showsAPictureInTime(Duration stored, Target target, KeyframeScan scan)
      throws InterruptedException {
    if (!stored.isPositive()) {
      return true;
    }

    var read = scan.firstDecodedPicture(stored, target.searchTime());
    return read instanceof SeekRead.FirstPicture(var time)
        && time.compareTo(target.searchTime()) <= 0;
  }

  private static final class Search {

    private final Target target;
    private final KeyframeScan scan;
    private final List<Keyframe> found = new ArrayList<>();
    private Duration from;
    private Duration length;

    private Search(Target target, KeyframeScan scan) {
      this.target = target;
      this.scan = scan;
      this.from = atOrAfterZero(target.searchTime().minus(target.firstInterval()));
      this.length = target.firstInterval();
    }

    Duration readPosition() throws InterruptedException {
      var scanned = scan.scan(from, firstScanEnd());
      found.addAll(scanned.keyframes());
      if (target.holdsTheLastPicture() && !reaches(scanned, target.firstSegmentStart())) {
        return readPositionBeforeTheVideosEnd();
      }

      return checkedReadPosition(target.searchTime());
    }

    private Duration readPositionBeforeTheVideosEnd() throws InterruptedException {
      if (!scanBackUntil(keyframes -> !keyframes.isEmpty())) {
        return Duration.ZERO;
      }

      return checkedReadPosition(historyBeforeTheLastKeyframe(target.searchTime()));
    }

    // A held encode's first scan reads past the first segment's start, to see whether the video
    // goes on there; any other scan ends a microsecond after the search time, so that a keyframe
    // that decodes at that time is read.
    private Duration firstScanEnd() {
      if (target.holdsTheLastPicture()) {
        return target.firstSegmentStart().plus(PAST_THE_FIRST_SEGMENTS_START);
      }

      return target.searchTime().plus(ONE_MICROSECOND);
    }

    private Duration checkedReadPosition(Duration latest) throws InterruptedException {
      var latestKeyframe = latest;
      for (var checked = 0; checked < MOST_KEYFRAMES_CHECKED; checked++) {
        var bound = latestKeyframe;
        if (!scanBackUntil(keyframes -> lastPresentingAtOrBefore(keyframes, bound).isPresent())) {
          return Duration.ZERO;
        }

        var keyframe = lastPresentingAtOrBefore(found, bound).orElseThrow();
        var position = readPositionFrom(keyframe, latest);
        if (position.isPresent()) {
          return position.get();
        }

        latestKeyframe = keyframe.presentationTime().minusNanos(1);
      }

      return Duration.ZERO;
    }

    // An MP4 demuxer seeks by presentation time, so a seek to the keyframe's decode time reads
    // from the keyframe before it, a whole GOP earlier. An MPEG-TS or MPEG-PS seek lands on decode
    // times, so a seek to the keyframe's presentation time can read past it. A raw H.264 stream's
    // demuxer cannot seek at all. A first picture that comes late from a read that reaches the
    // keyframe comes as late from every read of it, so the search tries the keyframe before.
    private Optional<Duration> readPositionFrom(Keyframe keyframe, Duration latest)
        throws InterruptedException {
      var candidates =
          List.of(
              keyframe.presentationTime(),
              keyframe.decodeTime(),
              keyframe.decodeTime().minus(B_FRAME_READ_BACK));
      for (var candidate : candidates.stream().distinct().toList()) {
        if (!candidate.isPositive()) {
          return Optional.of(Duration.ZERO);
        }

        var read =
            scan.firstPicture(
                ReadCheck.builder()
                    .seekPosition(candidate)
                    .latestLanding(keyframe.decodeTime())
                    .latestPicture(latest)
                    .build());
        if (!(read instanceof SeekRead.NoPictureInTime)) {
          return positionOf(read, candidate, latest);
        }
      }

      return Optional.empty();
    }

    private static Optional<Duration> positionOf(
        SeekRead read, Duration candidate, Duration latest) {
      return switch (read) {
        case SeekRead.NoVideo() -> Optional.of(Duration.ZERO);
        case SeekRead.FirstPicture(var time) when time.compareTo(latest) <= 0 ->
            Optional.of(candidate);
        case SeekRead.FirstPicture _, SeekRead.NoPictureInTime() -> Optional.empty();
      };
    }

    // The scans run back from the search time, so the latest keyframe found first is the video's
    // last one.
    private Duration historyBeforeTheLastKeyframe(Duration searchTime) {
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

    // Scans the interval before the earliest one scanned, twice as long, until the keyframes found
    // are enough or the scans reach the source's start.
    private boolean scanBackUntil(Predicate<List<Keyframe>> enough) throws InterruptedException {
      while (!enough.test(found)) {
        if (from.isZero()) {
          return false;
        }

        var until = from;
        length = length.multipliedBy(2);
        from = atOrAfterZero(until.minus(length));
        found.addAll(scan.scan(from, until).keyframes());
      }

      return true;
    }
  }

  private static boolean reaches(KeyframeScan.IntervalScan scanned, Duration time) {
    return scanned.lastDecodeTime().filter(last -> last.compareTo(time) >= 0).isPresent();
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
}
