package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.Listed;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import com.streamarr.transcode.probe.KeyframeIndex.Unusable;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads the keyframes of one MP4 or MOV track from its sample tables: the media timescale (mdhd),
 * the decode durations (stts), the composition offsets (ctts), the sync samples (stss) and the edit
 * list (elst). The times match the ones FFmpeg's mov demuxer gives the track's packets.
 */
final class Mp4SyncSampleReader {

  private static final long MAXIMUM_MOVIE_BOX_BYTES = 256L << 20;
  private static final int BOX_HEADER_BYTES = 8;
  private static final int LARGE_BOX_HEADER_BYTES = 16;

  /**
   * The reader lists at most this many samples. A 24-hour source at 48 fps has 4,147,200 samples. A
   * track with more samples gets a packet scan.
   */
  private static final long MAXIMUM_SAMPLES = 1L << 22;

  private Mp4SyncSampleReader() {}

  static KeyframeIndex read(Path source, long trackId) throws IOException {
    try (var channel = FileChannel.open(source, StandardOpenOption.READ)) {
      var movie = movieBox(channel);
      if (child(movie, "mvex").isPresent()) {
        return new Unusable(
            KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX);
      }

      return read(movie, trackId);
    } catch (ContainerIndexException | BufferUnderflowException _) {
      return incomplete();
    }
  }

  private static KeyframeIndex read(ByteBuffer movie, long trackId) {
    var movieTimescale = timescale(child(movie, "mvhd").orElseThrow(() -> malformed("mvhd")));
    var track =
        children(movie, "trak").stream()
            .filter(candidate -> trackId(candidate) == trackId)
            .findFirst()
            .orElseThrow(() -> malformed("trak"));
    var media = child(track, "mdia").orElseThrow(() -> malformed("mdia"));
    if (!isVideoHandler(child(media, "hdlr").orElseThrow(() -> malformed("hdlr")))) {
      return incomplete();
    }

    var mediaTimescale = timescale(child(media, "mdhd").orElseThrow(() -> malformed("mdhd")));
    var sampleTable =
        child(media, "minf")
            .flatMap(info -> child(info, "stbl"))
            .orElseThrow(() -> malformed("stbl"));
    var editList = child(track, "edts").flatMap(edits -> child(edits, "elst"));
    var edit = editList.isPresent() ? Edit.read(editList.get()) : Optional.of(Edit.NONE);
    var durations = runs(child(sampleTable, "stts").orElseThrow(() -> malformed("stts")));
    var sampleCount = sampleCount(sampleTable);
    if (sampleCount > MAXIMUM_SAMPLES || sampleCount != totalCount(durations)) {
      return incomplete();
    }

    var keyframes = rawKeyframes(sampleTable, durations);
    if (edit.isEmpty() || keyframes.isEmpty()) {
      return incomplete();
    }

    return new Listed(
        new TimeBase(1, mediaTimescale),
        edit.get().apply(keyframes, movieTimescale, mediaTimescale));
  }

  /** The number of samples in the track, from the sample sizes (stsz or stz2). */
  private static long sampleCount(ByteBuffer sampleTable) {
    return child(sampleTable, "stsz")
        .map(Mp4SyncSampleReader::sizeTableCount)
        .orElseGet(
            () ->
                compactSizeTableCount(
                    child(sampleTable, "stz2").orElseThrow(() -> malformed("stsz"))));
  }

  private static long sizeTableCount(ByteBuffer box) {
    skip(box, 4);
    var constantSize = box.getInt();
    var count = Integer.toUnsignedLong(box.getInt());
    if (constantSize == 0 && box.remaining() / Integer.BYTES < count) {
      throw malformed("stsz shorter than its sample count");
    }

    return count;
  }

  private static long compactSizeTableCount(ByteBuffer box) {
    skip(box, 7);
    var fieldBits = box.get() & 0xFF;
    var count = Integer.toUnsignedLong(box.getInt());
    if (fieldBits == 0 || box.remaining() * 8L / fieldBits < count) {
      throw malformed("stz2 shorter than its sample count");
    }

    return count;
  }

  private static long totalCount(List<Run> runs) {
    return runs.stream().mapToLong(Run::count).sum();
  }

  private static List<KeyframeTime> rawKeyframes(ByteBuffer sampleTable, List<Run> durations) {
    var offsets = child(sampleTable, "ctts").map(Mp4SyncSampleReader::runs).orElse(List.of());
    var syncSamples = child(sampleTable, "stss").map(Mp4SyncSampleReader::syncSamples);
    if (syncSamples.isPresent() && syncSamples.get().isEmpty()) {
      return List.of();
    }

    var decodeShift = decodeShift(offsets);
    var keyframes = new ArrayList<KeyframeTime>();
    var offsetRuns = new RunCursor(offsets);
    var sync = syncSamples.<SyncSamples>map(ListedSyncSamples::new).orElseGet(EverySample::new);
    long sample = 1;
    long decodeTime = 0;
    for (var run : durations) {
      for (long i = 0; i < run.count(); i++, sample++) {
        var offset = offsetRuns.next();
        if (sync.isSync(sample)) {
          keyframes.add(new KeyframeTime(decodeTime + offset, decodeTime - decodeShift));
        }

        decodeTime += run.value();
      }
    }

    return keyframes;
  }

  /**
   * FFmpeg's mov demuxer moves every decode time earlier by the most negative composition offset,
   * so that no decode time follows its presentation time. It skips the last two ctts entries and
   * the entries that count no samples. It drops an entry whose count exceeds a signed 32-bit
   * integer, so the reader treats that entry as damaged.
   */
  private static long decodeShift(List<Run> offsets) {
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

  private static List<Run> runs(ByteBuffer box) {
    // ctts version 0 declares unsigned offsets; FFmpeg reads every version as signed.
    box.getInt();
    var entries = Integer.toUnsignedLong(box.getInt());
    var runs = new ArrayList<Run>();
    for (long i = 0; i < entries; i++) {
      runs.add(new Run(Integer.toUnsignedLong(box.getInt()), box.getInt()));
    }

    return runs;
  }

  private static List<Long> syncSamples(ByteBuffer box) {
    box.getInt();
    var entries = Integer.toUnsignedLong(box.getInt());
    var samples = new ArrayList<Long>();
    for (long i = 0; i < entries; i++) {
      samples.add(Integer.toUnsignedLong(box.getInt()));
    }

    return samples;
  }

  private static long trackId(ByteBuffer track) {
    var header = child(track, "tkhd").orElseThrow(() -> malformed("tkhd"));
    var version = header.get() & 0xFF;
    skipFlags(header);
    skip(header, version == 1 ? 16 : 8);
    return Integer.toUnsignedLong(header.getInt());
  }

  private static long timescale(ByteBuffer header) {
    var version = header.get() & 0xFF;
    skipFlags(header);
    skip(header, version == 1 ? 16 : 8);
    var timescale = Integer.toUnsignedLong(header.getInt());
    if (timescale == 0) {
      throw malformed("zero timescale");
    }

    return timescale;
  }

  private static boolean isVideoHandler(ByteBuffer handler) {
    skip(handler, 8);
    return "vide".equals(fourCharacterCode(handler));
  }

  private static ByteBuffer movieBox(FileChannel channel) throws IOException {
    var size = channel.size();
    long position = 0;
    while (position + BOX_HEADER_BYTES <= size) {
      var header =
          ContainerFileBytes.read(
              channel, position, (int) Math.min(LARGE_BOX_HEADER_BYTES, size - position));
      var box = BoxHeader.read(header, size - position);
      if ("moov".equals(box.type())) {
        return payload(channel, position, box);
      }

      position += box.size();
    }

    throw malformed("moov");
  }

  private static ByteBuffer payload(FileChannel channel, long position, BoxHeader box)
      throws IOException {
    if (box.payloadSize() > MAXIMUM_MOVIE_BOX_BYTES) {
      throw malformed("oversized moov");
    }

    return ContainerFileBytes.read(channel, position + box.headerSize(), (int) box.payloadSize());
  }

  private static Optional<ByteBuffer> child(ByteBuffer parent, String type) {
    return boxes(parent).stream()
        .filter(box -> type.equals(box.type()))
        .map(Box::payload)
        .findFirst();
  }

  private static List<ByteBuffer> children(ByteBuffer parent, String type) {
    return boxes(parent).stream().filter(box -> type.equals(box.type())).map(Box::payload).toList();
  }

  private static List<Box> boxes(ByteBuffer parent) {
    var boxes = new ArrayList<Box>();
    var position = parent.position();
    while (parent.limit() - position >= BOX_HEADER_BYTES) {
      var view = parent.duplicate().position(position);
      var header = BoxHeader.read(view, parent.limit() - position);
      var payload =
          parent
              .duplicate()
              .position(position + header.headerSize())
              .limit(Math.toIntExact(position + header.size()))
              .slice();
      boxes.add(new Box(header.type(), payload));
      position = Math.toIntExact(position + header.size());
    }

    return boxes;
  }

  private static String fourCharacterCode(ByteBuffer buffer) {
    var bytes = new byte[4];
    buffer.get(bytes);
    return new String(bytes, StandardCharsets.ISO_8859_1);
  }

  private static void skipFlags(ByteBuffer box) {
    skip(box, 3);
  }

  private static void skip(ByteBuffer buffer, int bytes) {
    if (bytes > buffer.remaining()) {
      throw malformed("box shorter than its fields");
    }

    buffer.position(buffer.position() + bytes);
  }

  private static Unusable incomplete() {
    return new Unusable(
        KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX);
  }

  private static ContainerIndexException malformed(String detail) {
    return new ContainerIndexException("Unusable MP4 sample tables: " + detail);
  }

  private record Box(String type, ByteBuffer payload) {}

  private record BoxHeader(String type, long size, int headerSize) {

    static BoxHeader read(ByteBuffer buffer, long available) {
      var size = Integer.toUnsignedLong(buffer.getInt());
      var type = fourCharacterCode(buffer);
      var headerSize = BOX_HEADER_BYTES;
      if (size == 1) {
        size = buffer.getLong();
        headerSize = LARGE_BOX_HEADER_BYTES;
      }

      if (size == 0) {
        size = available;
      }

      if (size < headerSize || size > available) {
        throw malformed("box size");
      }

      return new BoxHeader(type, size, headerSize);
    }

    long payloadSize() {
      return size - headerSize;
    }
  }

  private record Run(long count, int value) {}

  /** Walks the composition offset runs one sample at a time; a track without ctts has none. */
  private static final class RunCursor {
    private final List<Run> runs;
    private int run;
    private long used;

    private RunCursor(List<Run> runs) {
      this.runs = runs;
    }

    private int next() {
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

  /** Answers, for each sample number in increasing order, whether it is a sync sample. */
  private sealed interface SyncSamples permits EverySample, ListedSyncSamples {
    boolean isSync(long sample);
  }

  /** A track without stss has only sync samples. */
  private record EverySample() implements SyncSamples {
    @Override
    public boolean isSync(long sample) {
      return true;
    }
  }

  /** Walks the stss entries in order. */
  private static final class ListedSyncSamples implements SyncSamples {
    private final List<Long> samples;
    private int next;

    private ListedSyncSamples(List<Long> samples) {
      this.samples = samples;
    }

    @Override
    public boolean isSync(long sample) {
      while (next < samples.size() && samples.get(next) < sample) {
        next++;
      }

      return next < samples.size() && samples.get(next) == sample;
    }
  }

  /**
   * An edit list FFmpeg applies as one shift: empty edits, then one edit that plays the media at
   * normal rate from a media time. Other edit lists change which samples the demuxer returns, so
   * the reader leaves them to a packet scan.
   */
  private record Edit(long emptyDuration, long mediaTime) {

    private static final Edit NONE = new Edit(0, 0);

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

        return Optional.of(new Edit(emptyDuration, mediaTime));
      }

      return Optional.empty();
    }

    /**
     * FFmpeg keeps the samples from the last keyframe that presents at or before the edit's media
     * time, and shifts every time by the empty edits minus that media time.
     */
    List<KeyframeTime> apply(List<KeyframeTime> keyframes, long movieTimescale, long timescale) {
      var shift = rescale(emptyDuration, timescale, movieTimescale) - mediaTime;
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
