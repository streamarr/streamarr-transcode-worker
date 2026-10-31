package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.Mp4MovieBox.skip;
import static com.streamarr.transcode.probe.Mp4MovieBox.skipFlags;

import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the sample-table values that the times of an MP4 or MOV track's samples depend on: the
 * decode durations and composition offsets, and the edit list that FFmpeg's mov demuxer applies.
 */
final class Mp4SampleTables {

  private Mp4SampleTables() {}

  /**
   * FFmpeg's mov demuxer moves every decode time earlier by the most negative composition offset,
   * so that no decode time follows its presentation time. It skips the last two ctts entries and
   * the entries that count no samples. It drops an entry whose count exceeds a signed 32-bit
   * integer, so the reader treats that entry as damaged.
   */
  static long decodeShift(List<Run> offsets) {
    if (offsets.stream().anyMatch(run -> run.count() > Integer.MAX_VALUE)) {
      throw malformed("ctts count");
    }

    long shift = 0;
    for (var i = 0; i + 2 < offsets.size(); i++) {
      var run = offsets.get(i);
      if (run.count() > 0) {
        shift = Math.max(shift, -Math.max(run.value(), Integer.MIN_VALUE + 1L));
      }
    }

    return shift;
  }

  static List<Run> runs(ByteBuffer box) {
    // ctts version 0 declares unsigned offsets; FFmpeg reads every version as signed.
    box.getInt();
    var entries = Integer.toUnsignedLong(box.getInt());
    var runs = new ArrayList<Run>();
    for (long i = 0; i < entries; i++) {
      runs.add(new Run(Integer.toUnsignedLong(box.getInt()), box.getInt()));
    }

    return runs;
  }

  static long timescale(ByteBuffer header) {
    var version = header.get() & 0xFF;
    skipFlags(header);
    skip(header, version == 1 ? 16 : 8);
    var timescale = Integer.toUnsignedLong(header.getInt());
    if (timescale == 0) {
      throw malformed("zero timescale");
    }

    return timescale;
  }

  static ContainerIndexException malformed(String detail) {
    return new ContainerIndexException("Unusable MP4 sample tables: " + detail);
  }

  record Run(long count, int value) {}

  /** Walks the composition offset runs one sample at a time; a track without ctts has none. */
  static final class RunCursor {
    private final List<Run> runs;
    private int run;
    private long used;

    RunCursor(List<Run> runs) {
      this.runs = runs;
    }

    int next() {
      while (run < runs.size() && used == runs.get(run).count()) {
        run++;
        used = 0;
      }

      if (run == runs.size()) {
        return 0;
      }

      used++;
      return runs.get(run).value();
    }
  }

  /**
   * An edit list FFmpeg applies as one shift: empty edits, then one edit that plays the media at
   * normal rate from a media time. Other edit lists change which samples the demuxer returns, so
   * the reader leaves them to a packet scan.
   */
  record Edit(long emptyDuration, long mediaTime, long duration) {

    static final Edit NONE = new Edit(0, 0, 0);

    static Optional<Edit> read(ByteBuffer box) {
      var version = box.get() & 0xFF;
      skipFlags(box);
      var entries = Integer.toUnsignedLong(box.getInt());
      long emptyDuration = 0;
      for (long i = 0; i < entries; i++) {
        var duration = version == 1 ? box.getLong() : Integer.toUnsignedLong(box.getInt());
        var mediaTime = version == 1 ? box.getLong() : box.getInt();
        var rate = box.getInt();
        if (mediaTime == -1) {
          emptyDuration += duration;
          continue;
        }

        var isLast = i == entries - 1;
        if (!isLast || rate != 0x0001_0000 || mediaTime < 0) {
          return Optional.empty();
        }

        return Optional.of(new Edit(emptyDuration, mediaTime, duration));
      }

      return Optional.empty();
    }

    /**
     * FFmpeg keeps the samples from the last keyframe that presents at or before the edit's media
     * time, and shifts every time by the empty edits minus that media time.
     */
    List<KeyframeTime> apply(List<KeyframeTime> keyframes, long movieTimescale, long timescale) {
      var shift = shift(movieTimescale, timescale);
      var first = 0;
      for (var i = 0; i < keyframes.size(); i++) {
        if (keyframes.get(i).presentationTime() <= mediaTime) {
          first = i;
        }
      }

      return keyframes.subList(first, keyframes.size()).stream()
          .map(
              keyframe ->
                  new KeyframeTime(
                      keyframe.presentationTime() + shift, keyframe.decodeTime() + shift))
          .toList();
    }

    /** What FFmpeg adds to every media time of the track: the empty edits minus the media time. */
    long shift(long movieTimescale, long timescale) {
      return rescale(emptyDuration, timescale, movieTimescale) - mediaTime;
    }

    /**
     * The media time at which the edit stops playing the media, or empty when the edit list names
     * no duration.
     */
    Optional<Long> mediaEnd(long movieTimescale, long timescale) {
      if (duration == 0) {
        return Optional.empty();
      }

      return Optional.of(Math.addExact(mediaTime, rescale(duration, timescale, movieTimescale)));
    }

    private static long rescale(long value, long multiplier, long divisor) {
      try {
        return Math.floorDiv(
            Math.addExact(Math.multiplyExact(value, multiplier), divisor / 2), divisor);
      } catch (ArithmeticException _) {
        throw malformed("edit duration out of range");
      }
    }
  }
}
