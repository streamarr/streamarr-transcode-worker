package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.Mp4MovieBox.child;
import static com.streamarr.transcode.probe.Mp4MovieBox.children;
import static com.streamarr.transcode.probe.Mp4MovieBox.fourCharacterCode;
import static com.streamarr.transcode.probe.Mp4MovieBox.skip;
import static com.streamarr.transcode.probe.Mp4MovieBox.trackId;
import static com.streamarr.transcode.probe.Mp4SampleTables.decodeShift;
import static com.streamarr.transcode.probe.Mp4SampleTables.malformed;
import static com.streamarr.transcode.probe.Mp4SampleTables.runs;
import static com.streamarr.transcode.probe.Mp4SampleTables.timescale;

import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.Listed;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import com.streamarr.transcode.probe.KeyframeIndex.Unusable;
import com.streamarr.transcode.probe.Mp4SampleTables.Edit;
import com.streamarr.transcode.probe.Mp4SampleTables.Run;
import com.streamarr.transcode.probe.Mp4SampleTables.RunCursor;
import com.streamarr.transcode.probe.Mp4StreamMeasurer.TrackStream;
import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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

  /**
   * The reader lists at most this many samples. A 24-hour source at 48 fps has 4,147,200 samples. A
   * track with more samples gets a packet scan.
   */
  private static final long MAXIMUM_SAMPLES = 1L << 22;

  private Mp4SyncSampleReader() {}

  static KeyframeIndex read(Path source, long trackId) throws IOException {
    return read(source, new TrackStream(0, trackId, true), List.of()).keyframes();
  }

  /**
   * Reads the chosen track's keyframes and, from the same movie box, measures the {@code measured}
   * streams. The measurement is empty when {@code measured} is.
   */
  static ContainerIndex read(Path source, TrackStream chosen, List<TrackStream> measured)
      throws IOException {
    try (var channel = FileChannel.open(source, StandardOpenOption.READ)) {
      var movie = Mp4MovieBox.read(channel);
      if (child(movie, "mvex").isPresent()) {
        return ContainerIndex.keyframesOnly(
            new Unusable(KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX));
      }

      var keyframes = read(movie, chosen.trackId());
      if (!(keyframes instanceof Listed listed) || measured.isEmpty()) {
        return ContainerIndex.keyframesOnly(keyframes);
      }

      return new ContainerIndex(
          listed,
          Mp4StreamMeasurer.measure(movie, chosen, measured, listed.keyframes().size())
              .orElse(List.of()));
    } catch (ContainerIndexException | BufferUnderflowException _) {
      return ContainerIndex.keyframesOnly(incomplete());
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
    var offsets = child(sampleTable, "ctts").map(Mp4SampleTables::runs).orElse(List.of());
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

  private static List<Long> syncSamples(ByteBuffer box) {
    box.getInt();
    var entries = Integer.toUnsignedLong(box.getInt());
    var samples = new ArrayList<Long>();
    for (long i = 0; i < entries; i++) {
      samples.add(Integer.toUnsignedLong(box.getInt()));
    }

    return samples;
  }

  private static boolean isVideoHandler(ByteBuffer handler) {
    skip(handler, 8);
    return "vide".equals(fourCharacterCode(handler));
  }

  private static Unusable incomplete() {
    return new Unusable(
        KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX);
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
}
