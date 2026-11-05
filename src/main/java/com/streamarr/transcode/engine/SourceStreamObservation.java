package com.streamarr.transcode.engine;

import java.math.BigInteger;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What FFmpeg's error output shows of the chosen source streams. The FFmpeg that reads the source
 * copies them, as its second output, to a framecrc that it writes there: a header whose {@code #tb}
 * lines name each stream's time base, then one record for each packet with its presentation time
 * and duration on the attempt's zero-based timeline. FFmpeg's stream mapping names the source
 * stream of each of that output's streams. FFmpeg writes each record at once, but its log can write
 * part of a line before it, so a record is read wherever it ends a line. FFmpeg writes the input's
 * metadata, which the source controls, before the stream mapping and the framecrc only after it.
 * That metadata can print the mapping's header itself, so each header discards what was read before
 * it, and the last one, FFmpeg's own, leaves only what follows the real mapping.
 */
final class SourceStreamObservation {

  private static final Pattern TIME_BASE = Pattern.compile("#tb (\\d+): (\\d+)/(\\d+)$");
  // The framecrc carries at most the video and one audio stream, so a record's stream index is the
  // one digit before its first comma, even when a log line that ends in digits runs into it.
  private static final Pattern RECORD =
      Pattern.compile(
          "(\\d), *(-?\\d+), *(-?\\d+), *(-?\\d+), *\\d+, 0x[0-9a-f]{8}"
              + "(?:, F=0x([0-9A-F]{1,8}))?(?:, S=.*)?$");
  // What framecrc prints for a time the packet does not carry: FFmpeg's AV_NOPTS_VALUE.
  private static final long NO_TIME = Long.MIN_VALUE;
  private static final Pattern MAPPING =
      Pattern.compile("Stream #0:(\\d{1,9}) -> #(\\d{1,9}):(\\d{1,9})\\b");
  private static final int KEYFRAME = 0x1;
  // AV_PKT_FLAG_DISCARD: the demuxer marks a packet that no decoder presents, as an MP4 edit list
  // cuts it away.
  private static final int DISCARD = 0x4;
  private static final long NANOS_PER_SECOND = 1_000_000_000L;
  private static final int SOURCE_STREAMS_OUTPUT = 1;
  private static final String MAPPING_HEADER = "Stream mapping:";

  private final Map<Integer, TimeBase> timeBases = new HashMap<>();
  private final Map<Integer, Integer> sourceIndexes = new HashMap<>();
  private final Map<Integer, Packets> packets = new HashMap<>();
  private boolean mappingStarted;

  /**
   * Reads one line of FFmpeg's error output, and whether it carried part of the framecrc, which
   * then stays out of the diagnostics. A mapping line stays in them.
   */
  synchronized boolean tryRead(String line) {
    if (line.equals(MAPPING_HEADER)) {
      timeBases.clear();
      sourceIndexes.clear();
      packets.clear();
      mappingStarted = true;
      return false;
    }

    if (!mappingStarted) {
      return false;
    }

    var mapping = MAPPING.matcher(line);
    if (mapping.find()) {
      readMapping(mapping);
      return false;
    }

    var timeBase = TIME_BASE.matcher(line);
    if (timeBase.find()) {
      readTimeBase(timeBase);
      return true;
    }

    var recordMatch = RECORD.matcher(line);
    if (recordMatch.find()) {
      readRecord(recordMatch);
      return true;
    }

    return line.startsWith("#");
  }

  /**
   * Each copied stream whose packets and source the output showed, in the order the command maps
   * them.
   */
  synchronized List<ObservedSourceStream> streams() {
    return new TreeMap<>(packets)
        .entrySet().stream()
            .filter(entry -> timeBases.containsKey(entry.getKey()))
            .filter(entry -> sourceIndexes.containsKey(entry.getKey()))
            .flatMap(
                entry ->
                    observed(
                        sourceIndexes.get(entry.getKey()),
                        timeBases.get(entry.getKey()),
                        entry.getValue())
                        .stream())
            .toList();
  }

  private void readMapping(Matcher mapping) {
    if (Integer.parseInt(mapping.group(2)) == SOURCE_STREAMS_OUTPUT) {
      sourceIndexes.put(Integer.parseInt(mapping.group(3)), Integer.parseInt(mapping.group(1)));
    }
  }

  private void readTimeBase(Matcher timeBase) {
    try {
      timeBases.put(
          Integer.parseInt(timeBase.group(1)),
          new TimeBase(Long.parseLong(timeBase.group(2)), Long.parseLong(timeBase.group(3))));
    } catch (IllegalArgumentException _) {
      // A time base that no stream can have leaves its stream unobserved.
    }
  }

  // A packet without a presentation time, as a raw H.264 or HEVC stream's, counts at its decode
  // time, which lies at most the reordering delay before it. A packet flagged for discarding is
  // no source sample.
  private void readRecord(Matcher recordMatch) {
    try {
      var flags =
          Optional.ofNullable(recordMatch.group(5))
              .map(value -> Integer.parseUnsignedInt(value, 16))
              .orElse(KEYFRAME);
      if ((flags & DISCARD) != 0) {
        return;
      }

      var stream = Integer.parseInt(recordMatch.group(1));
      var start = Long.parseLong(recordMatch.group(3));
      if (start == NO_TIME) {
        start = Long.parseLong(recordMatch.group(2));
      }

      if (start == NO_TIME) {
        return;
      }

      var end = Math.addExact(start, Long.parseLong(recordMatch.group(4)));
      packets.merge(stream, Packets.of(start, end, (flags & KEYFRAME) != 0), Packets::andThen);
    } catch (ArithmeticException | NumberFormatException _) {
      // A packet whose times do not fit 64 bits names no position on the attempt's timeline.
    }
  }

  private static Optional<ObservedSourceStream> observed(
      int sourceIndex, TimeBase timeBase, Packets read) {
    try {
      return Optional.of(
          ObservedSourceStream.builder()
              .sourceIndex(sourceIndex)
              .lastStart(durationOf(timeBase, read.lastStart()))
              .end(durationOf(timeBase, read.end()))
              .lastKeyframeStart(read.lastKeyframeStart().map(start -> durationOf(timeBase, start)))
              .build());
    } catch (ArithmeticException _) {
      return Optional.empty();
    }
  }

  // Truncates to the nanosecond; throws ArithmeticException when the time does not fit.
  private static Duration durationOf(TimeBase timeBase, long ticks) {
    var nanos =
        BigInteger.valueOf(ticks)
            .multiply(BigInteger.valueOf(timeBase.numerator()))
            .multiply(BigInteger.valueOf(NANOS_PER_SECOND))
            .divide(BigInteger.valueOf(timeBase.denominator()));
    return Duration.ofNanos(nanos.longValueExact());
  }

  private record Packets(long lastStart, long end, Optional<Long> lastKeyframeStart) {

    static Packets of(long start, long end, boolean keyframe) {
      return new Packets(start, end, keyframe ? Optional.of(start) : Optional.empty());
    }

    Packets andThen(Packets next) {
      return new Packets(
          Math.max(lastStart, next.lastStart()),
          Math.max(end, next.end()),
          next.lastKeyframeStart().or(this::lastKeyframeStart));
    }
  }
}
