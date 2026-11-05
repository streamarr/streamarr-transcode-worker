package com.streamarr.transcode.engine;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * What FFmpeg's error output shows of the chosen source streams. The FFmpeg that reads the source
 * copies them, as its second output, to a framecrc that it writes there: a header whose {@code #tb}
 * lines name each stream's time base, then one record for each packet with its presentation time
 * and duration on the attempt's zero-based timeline. FFmpeg's stream mapping names the source
 * stream of each of that output's streams, from whichever input of that FFmpeg reads the source.
 * FFmpeg writes each record at once, but its log can write part of a line before it, so a record is
 * read wherever it ends a line. FFmpeg writes the inputs' metadata, which the source controls,
 * before the stream mapping and the framecrc only after it. That metadata can print the mapping's
 * header itself, so each header discards what was read before it, and the last one, FFmpeg's own,
 * leaves only what follows the real mapping.
 *
 * <p>When the command encodes the audio, the framecrc also carries that stream decoded to PCM, and
 * the stream ends where the earlier of its packets and its decoded samples end. A decoder discards
 * trailing padding that only side data records, as Matroska's DiscardPadding, while a packet's
 * duration keeps it; an MP4 edit list shortens the last packet's duration, while the decoder still
 * writes the whole frame.
 */
final class SourceStreamObservation {

  private static final Pattern TIME_BASE = Pattern.compile("#tb (\\d+): (\\d+)/(\\d+)$");
  // The framecrc carries at most the video, one audio stream and that stream decoded, so a record's
  // stream index is the one digit before its first comma, even when a log line that ends in digits
  // runs into it.
  private static final Pattern RECORD =
      Pattern.compile(
          "(\\d), *(-?\\d+), *(-?\\d+), *(-?\\d+), *\\d+, 0x[0-9a-f]{8}"
              + "(?:, F=0x([0-9A-F]{1,8}))?(?:, S=.*)?$");
  // What framecrc prints for a time the packet does not carry: FFmpeg's AV_NOPTS_VALUE.
  private static final long NO_TIME = Long.MIN_VALUE;
  private static final Pattern MAPPING =
      Pattern.compile("Stream #\\d{1,9}:(\\d{1,9}) -> #(\\d{1,9}):(\\d{1,9})\\b( \\(copy\\))?");
  private static final int KEYFRAME = 0x1;
  // AV_PKT_FLAG_DISCARD: the demuxer marks a packet that no decoder presents, as an MP4 edit list
  // cuts it away.
  private static final int DISCARD = 0x4;
  private static final int SOURCE_STREAMS_OUTPUT = 1;
  private static final String MAPPING_HEADER = "Stream mapping:";

  private final Map<Integer, TimeBase> timeBases = new HashMap<>();
  private final Map<Integer, Integer> sourceIndexes = new HashMap<>();
  private final Map<Integer, Packets> packets = new HashMap<>();
  private final Set<Integer> decodedStreams = new HashSet<>();
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
      decodedStreams.clear();
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
    var decoded =
        observedStreams(decodedStreams::contains)
            .collect(
                Collectors.toMap(
                    ObservedSourceStream::sourceIndex, Function.identity(), (first, _) -> first));
    return observedStreams(stream -> !decodedStreams.contains(stream))
        .map(
            copied ->
                Optional.ofNullable(decoded.get(copied.sourceIndex()))
                    .map(samples -> endingWithDecodedSamples(copied, samples))
                    .orElse(copied))
        .toList();
  }

  private Stream<ObservedSourceStream> observedStreams(IntPredicate outputStream) {
    return new TreeMap<>(packets)
        .entrySet().stream()
            .filter(entry -> outputStream.test(entry.getKey()))
            .filter(entry -> timeBases.containsKey(entry.getKey()))
            .filter(entry -> sourceIndexes.containsKey(entry.getKey()))
            .flatMap(
                entry ->
                    observed(
                        sourceIndexes.get(entry.getKey()),
                        timeBases.get(entry.getKey()),
                        entry.getValue())
                        .stream());
  }

  private static ObservedSourceStream endingWithDecodedSamples(
      ObservedSourceStream copied, ObservedSourceStream decoded) {
    return ObservedSourceStream.builder()
        .sourceIndex(copied.sourceIndex())
        .lastStart(earlier(copied.lastStart(), decoded.lastStart()))
        .end(earlier(copied.end(), decoded.end()))
        .lastKeyframeStart(copied.lastKeyframeStart())
        .build();
  }

  private static MediaTime earlier(MediaTime first, MediaTime second) {
    if (second.compareTo(first) < 0) {
      return second;
    }

    return first;
  }

  // A mapping that FFmpeg does not mark as a copy decodes its stream.
  private void readMapping(Matcher mapping) {
    if (Integer.parseInt(mapping.group(2)) != SOURCE_STREAMS_OUTPUT) {
      return;
    }

    var outputStream = Integer.parseInt(mapping.group(3));
    sourceIndexes.put(outputStream, Integer.parseInt(mapping.group(1)));
    if (mapping.group(4) == null) {
      decodedStreams.add(outputStream);
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

  // A stream whose times do not fit a signed 64-bit count of nanoseconds stays unobserved.
  private static Optional<ObservedSourceStream> observed(
      int sourceIndex, TimeBase timeBase, Packets read) {
    var stream =
        ObservedSourceStream.builder()
            .sourceIndex(sourceIndex)
            .lastStart(new MediaTime(read.lastStart(), timeBase))
            .end(new MediaTime(read.end(), timeBase))
            .lastKeyframeStart(
                read.lastKeyframeStart().map(start -> new MediaTime(start, timeBase)))
            .build();
    try {
      stream.lastStart().truncatedToNanoseconds();
      stream.end().truncatedToNanoseconds();
      stream.lastKeyframeStart().ifPresent(MediaTime::truncatedToNanoseconds);
      return Optional.of(stream);
    } catch (ArithmeticException _) {
      return Optional.empty();
    }
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
