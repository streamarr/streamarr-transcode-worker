package com.streamarr.transcode.probe;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The frame times that a scan of the first 30 s of packets finds, and the motion rate that they
 * show: the cadence at the 90th percentile of instantaneous rate. The scan reads ffprobe's packet
 * lines, each {@code pts,dts,size,flags}.
 */
final class PacketCadence {

  private static final String NO_TIME = "N/A";
  private static final int MINIMUM_RECURRENCES = 3;
  // An interval sets the timestamp step only when it makes up at least 0.1 % of the intervals.
  private static final int RECURRENCE_SHARE_DIVISOR = 1000;

  private final List<Long> frameTimes;
  private final ExactFraction timeBase;

  private PacketCadence(List<Long> frameTimes, ExactFraction timeBase) {
    this.frameTimes = frameTimes;
    this.timeBase = timeBase;
  }

  /**
   * The frames of the packet lines: packets without the discard flag and with data, at their
   * presentation time or else their decode time, sorted and without repeats. On an interlaced
   * stream, a packet and the next one form one frame when the next is not a keyframe and starts
   * half a frame later, within 1 tick.
   */
  static PacketCadence of(List<String> lines, ExactFraction timeBase, FrameTiming timing) {
    var packets = new ArrayList<Packet>();
    for (var line : lines) {
      Packet.parse(line).ifPresent(packets::add);
    }

    var frames =
        timing.halfFrameTicks(timeBase).map(half -> mergeFieldPairs(packets, half)).orElse(packets);
    var times = new TreeSet<Long>();
    frames.forEach(frame -> times.add(frame.time()));
    return new PacketCadence(List.copyOf(times), timeBase);
  }

  int frameCount() {
    return frameTimes.size();
  }

  /** The media time from the first frame to the last. */
  ExactFraction scannedSeconds() {
    if (frameTimes.size() < 2) {
      return ExactFraction.ZERO;
    }

    return ExactFraction.of(frameTimes.getLast() - frameTimes.getFirst()).times(timeBase);
  }

  /** The motion rate in frames per second; empty when the scan found fewer than 2 frames. */
  Optional<ExactFraction> motionRate() {
    if (frameTimes.size() < 2) {
      return Optional.empty();
    }

    var intervals = new ArrayList<Long>();
    for (var index = 1; index < frameTimes.size(); index++) {
      intervals.add(frameTimes.get(index) - frameTimes.get(index - 1));
    }

    Collections.sort(intervals);
    var tenthPercentile = ExactFraction.of(intervals.get(nearestRank(intervals.size())));
    var step = ExactFraction.of(timestampStep(intervals));
    if (step.times(ExactFraction.of(10)).isGreaterThan(tenthPercentile)) {
      step = ExactFraction.of(1);
    }

    var tolerance =
        ExactFraction.min(
            ExactFraction.max(
                step.times(ExactFraction.of(3, 2)),
                tenthPercentile.dividedBy(ExactFraction.of(100))),
            tenthPercentile.dividedBy(ExactFraction.of(4)));
    var cluster =
        intervals.stream()
            .filter(
                interval ->
                    !ExactFraction.of(interval)
                        .minus(tenthPercentile)
                        .abs()
                        .isGreaterThan(tolerance))
            .toList();
    var exactRate = ExactFraction.of(mostCommon(cluster)).times(timeBase).reciprocal();
    if (exactRate.isInteger()) {
      return Optional.of(exactRate);
    }

    var sum = cluster.stream().mapToLong(Long::longValue).sum();
    return Optional.of(
        ExactFraction.of(cluster.size()).dividedBy(ExactFraction.of(sum).times(timeBase)));
  }

  /** The index round(0.1 x (n - 1)) of the sorted intervals; a half rounds to the even index. */
  private static int nearestRank(int count) {
    return BigDecimal.valueOf(count - 1L)
        .divide(BigDecimal.TEN, 0, RoundingMode.HALF_EVEN)
        .intValueExact();
  }

  /**
   * The greatest common divisor of the distinct intervals that recur at least 3 times and make up
   * at least 0.1 % of all intervals; 1 when none does.
   */
  private static long timestampStep(List<Long> intervals) {
    var counts = counts(intervals);
    var step = BigInteger.ZERO;
    for (var entry : counts.entrySet()) {
      var count = entry.getValue();
      if (count >= MINIMUM_RECURRENCES && count * RECURRENCE_SHARE_DIVISOR >= intervals.size()) {
        step = step.gcd(BigInteger.valueOf(entry.getKey()));
      }
    }

    return step.signum() == 0 ? 1 : step.longValueExact();
  }

  /** The most common interval; a tie takes the shorter interval. */
  private static long mostCommon(List<Long> intervals) {
    var counts = counts(intervals);
    var best = intervals.getFirst();
    for (var entry : counts.entrySet()) {
      var count = entry.getValue();
      var bestCount = counts.get(best);
      if (count > bestCount || (count.equals(bestCount) && entry.getKey() < best)) {
        best = entry.getKey();
      }
    }

    return best;
  }

  private static Map<Long, Integer> counts(List<Long> intervals) {
    var counts = new HashMap<Long, Integer>();
    intervals.forEach(interval -> counts.merge(interval, 1, Integer::sum));
    return counts;
  }

  private static List<Packet> mergeFieldPairs(List<Packet> packets, ExactFraction halfFrame) {
    var frames = new ArrayList<Packet>();
    var index = 0;
    while (index < packets.size()) {
      var packet = packets.get(index);
      if (index + 1 < packets.size() && isSecondField(packet, packets.get(index + 1), halfFrame)) {
        frames.add(packet.withTime(Math.min(packet.time(), packets.get(index + 1).time())));
        index += 2;
        continue;
      }

      frames.add(packet);
      index++;
    }

    return frames;
  }

  private static boolean isSecondField(Packet first, Packet next, ExactFraction halfFrame) {
    if (next.keyframe()) {
      return false;
    }

    var gap = ExactFraction.of(Math.abs(next.time() - first.time()));
    return !gap.minus(halfFrame).abs().isGreaterThan(ExactFraction.of(1));
  }

  /** A packet that holds a picture: its time in ticks and whether it is a keyframe. */
  private record Packet(long time, boolean keyframe) {

    /** The packet of a line; empty for a discarded or empty packet, or one without a time. */
    static Optional<Packet> parse(String line) {
      var fields = line.strip().split(",");
      if (fields.length < 4) {
        return Optional.empty();
      }

      var flags = fields[3];
      var discarded = flags.length() > 1 && flags.charAt(1) == 'D';
      if (discarded || !hasData(fields[2])) {
        return Optional.empty();
      }

      return time(fields[0])
          .or(() -> time(fields[1]))
          .map(time -> new Packet(time, flags.startsWith("K")));
    }

    Packet withTime(long frameTime) {
      return new Packet(frameTime, keyframe);
    }

    private static boolean hasData(String size) {
      try {
        return Long.parseLong(size) > 0;
      } catch (NumberFormatException _) {
        return false;
      }
    }

    private static Optional<Long> time(String field) {
      if (NO_TIME.equals(field)) {
        return Optional.empty();
      }

      try {
        return Optional.of(Long.parseLong(field));
      } catch (NumberFormatException _) {
        return Optional.empty();
      }
    }
  }
}
