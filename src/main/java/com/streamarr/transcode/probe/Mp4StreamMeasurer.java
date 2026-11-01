package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.Mp4MovieBox.child;
import static com.streamarr.transcode.probe.Mp4MovieBox.fourCharacterCode;
import static com.streamarr.transcode.probe.Mp4MovieBox.skip;
import static com.streamarr.transcode.probe.Mp4SampleTables.decodeShift;
import static com.streamarr.transcode.probe.Mp4SampleTables.malformed;
import static com.streamarr.transcode.probe.Mp4SampleTables.runs;
import static com.streamarr.transcode.probe.Mp4SampleTables.timescale;
import static com.streamarr.transcode.probe.Mp4SampleTables.track;

import com.streamarr.transcode.probe.Mp4SampleTables.Edit;
import com.streamarr.transcode.probe.Mp4SampleTables.Run;
import com.streamarr.transcode.probe.Mp4SampleTables.RunCursor;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.Builder;

/**
 * Measures each video and audio track of an MP4 or MOV file from its sample tables (ADR 0054): the
 * sum of its sample sizes, its sample count, the time from its first presentation time to the end
 * of its last sample, and its bytes in each keyframe interval of the chosen video track. The times
 * and the kept samples match the packets that FFmpeg's mov demuxer returns, so the sums equal the
 * sizes of the packets that ffprobe lists. A video sample belongs to the interval that its decode
 * order places it in, and an audio sample to the interval that holds its presentation time.
 *
 * <p>The measurer reads no media data. It measures nothing when one track's samples cannot be
 * listed exactly, such as under an edit list that FFmpeg applies by dropping samples the measurer
 * does not model, or when a stream copy would deliver a required track's samples at other sizes
 * than its sample table lists ({@link CopiedSampleSizes}), because the server counts the measured
 * bytes as the copy's delivered bytes.
 */
final class Mp4StreamMeasurer {

  /** The measurer lists at most this many samples in one track, as the keyframe reader does. */
  private static final long MAXIMUM_SAMPLES = 1L << 22;

  private static final long LARGEST_INTERVAL_BYTES = 0xFFFF_FFFFL;

  private Mp4StreamMeasurer() {}

  /**
   * One video or audio stream that ffprobe listed, with the MP4 track that holds it, the codec that
   * ffprobe names, empty when it names none, and the audio sample rate, 0 when it names none. A
   * measurement holds every required stream. A stream that is not required, such as a second video
   * stream, is left out when it cannot be measured, or when a copy would change its samples' sizes.
   */
  @Builder
  record TrackStream(
      int streamIndex, long trackId, boolean isRequired, String codecName, long sampleRate) {

    TrackStream {
      codecName = codecName == null ? "" : codecName;
    }
  }

  /**
   * Measures the listed streams, or nothing when a required stream cannot be measured exactly.
   * {@code keyframeCount} is the number of keyframes that the keyframe reader listed for the chosen
   * track, which the measurement's intervals must match.
   */
  static Optional<List<TrackMeasurement>> measure(
      ByteBuffer movie, TrackStream chosen, List<TrackStream> streams, int keyframeCount) {
    try {
      var movieTimescale = timescale(child(movie, "mvhd").orElseThrow(() -> malformed("mvhd")));
      var chosenTrack = Track.read(movie, chosen.trackId(), movieTimescale);
      if (chosenTrack.isEmpty() || chosenTrack.get().keyframes().size() != keyframeCount) {
        return Optional.empty();
      }

      var measuring = new Measuring(movie, movieTimescale, chosen.trackId(), chosenTrack.get());
      var measured = new ArrayList<TrackMeasurement>();
      for (var stream : streams) {
        var measurement = measuring.measure(stream);
        if (measurement.isEmpty() && stream.isRequired()) {
          return Optional.empty();
        }

        measurement.ifPresent(measured::add);
      }

      return Optional.of(List.copyOf(measured));
    } catch (ContainerIndexException | BufferUnderflowException | ArithmeticException _) {
      return Optional.empty();
    }
  }

  /** The movie box and the chosen track, whose keyframe intervals every measured stream follows. */
  private record Measuring(
      ByteBuffer movie, long movieTimescale, long chosenTrackId, Track chosen) {

    /**
     * FFmpeg rewrites the decode times of the video samples that it keeps before an edit's start,
     * and another video track's intervals follow those times. The chosen track's intervals follow
     * its decode order, which the rewrite keeps.
     */
    private static boolean isMeasurableBesideChosen(Track track) {
      return !track.isVideo() || !track.presentsBeforeEditStart();
    }

    Optional<TrackMeasurement> measure(TrackStream stream) {
      try {
        if (!CopiedSampleSizes.areKept(movie, stream)) {
          return Optional.empty();
        }

        var track =
            stream.trackId() == chosenTrackId
                ? Optional.of(chosen)
                : Track.read(movie, stream.trackId(), movieTimescale)
                    .filter(Measuring::isMeasurableBesideChosen);
        return track.flatMap(
            candidate -> candidate.measure(stream.streamIndex(), chosen, chosen.keyframes()));
      } catch (ContainerIndexException | BufferUnderflowException | ArithmeticException _) {
        return Optional.empty();
      }
    }
  }

  /**
   * One stream's measurement. {@code measuredDuration} counts ticks of 1 / {@code timescale}
   * seconds. {@code intervalBytes} and {@code intervalPacketCounts} each hold one unsigned 32-bit
   * count for each keyframe interval: the stream's bytes and its samples in that interval.
   */
  static final class TrackMeasurement {
    private final int streamIndex;
    private final long measuredBytes;
    private final long packetCount;
    private final long timescale;
    private final long measuredDuration;
    private final int[] intervalBytes;
    private final int[] intervalPacketCounts;

    @Builder
    private TrackMeasurement(
        int streamIndex,
        long measuredBytes,
        long packetCount,
        long timescale,
        long measuredDuration,
        int[] intervalBytes,
        int[] intervalPacketCounts) {
      this.streamIndex = streamIndex;
      this.measuredBytes = measuredBytes;
      this.packetCount = packetCount;
      this.timescale = timescale;
      this.measuredDuration = measuredDuration;
      this.intervalBytes = intervalBytes;
      this.intervalPacketCounts = intervalPacketCounts;
    }

    int streamIndex() {
      return streamIndex;
    }

    long measuredBytes() {
      return measuredBytes;
    }

    long packetCount() {
      return packetCount;
    }

    long timescale() {
      return timescale;
    }

    long measuredDuration() {
      return measuredDuration;
    }

    /** The unsigned 32-bit byte counts, in interval order. The array is a copy. */
    int[] intervalBytes() {
      return intervalBytes.clone();
    }

    /** The unsigned 32-bit sample counts, in interval order. The array is a copy. */
    int[] intervalPacketCounts() {
      return intervalPacketCounts.clone();
    }

    @Override
    public String toString() {
      return "TrackMeasurement[streamIndex=%d, measuredBytes=%d, intervals=%d]"
          .formatted(streamIndex, measuredBytes, intervalBytes.length);
    }
  }

  /** A kept keyframe of the chosen track: its decode-order sample and its demuxer times. */
  private record Keyframe(int sample, long presentationTime, long decodeTime) {}

  /**
   * The samples of one track that FFmpeg's mov demuxer returns, from {@code first} on, with their
   * demuxer times in ticks of 1 / {@code timescale} seconds.
   */
  private record Track(
      boolean isVideo,
      long timescale,
      int[] sizes,
      long[] presentationTimes,
      long[] decodeTimes,
      long[] durations,
      boolean[] sync,
      int first,
      boolean presentsBeforeEditStart) {

    static Optional<Track> read(ByteBuffer movie, long trackId, long movieTimescale) {
      var trak = track(movie, trackId);
      var media = child(trak, "mdia").orElseThrow(() -> malformed("mdia"));
      var handler = handlerType(child(media, "hdlr").orElseThrow(() -> malformed("hdlr")));
      if (!"vide".equals(handler) && !"soun".equals(handler)) {
        return Optional.empty();
      }

      var timescale =
          Mp4SampleTables.timescale(child(media, "mdhd").orElseThrow(() -> malformed("mdhd")));
      var sampleTable =
          child(media, "minf")
              .flatMap(info -> child(info, "stbl"))
              .orElseThrow(() -> malformed("stbl"));
      var editList = child(trak, "edts").flatMap(edits -> child(edits, "elst"));
      var edit = editList.isPresent() ? Edit.read(editList.get()) : Optional.of(Edit.NONE);
      if (edit.isEmpty()) {
        return Optional.empty();
      }

      var durationRuns = runs(child(sampleTable, "stts").orElseThrow(() -> malformed("stts")));
      if ("soun".equals(handler)
          && (isChunkedAudio(durationRuns) || child(sampleTable, "ctts").isPresent())) {
        return Optional.empty();
      }

      var sizes = sampleSizes(sampleTable);
      if (sizes.length == 0 || sizes.length != totalCount(durationRuns)) {
        return Optional.empty();
      }

      return Optional.of(
          timed(
              Timing.builder()
                  .isVideo("vide".equals(handler))
                  .timescale(timescale)
                  .movieTimescale(movieTimescale)
                  .hasEditList(editList.isPresent())
                  .edit(edit.get())
                  .sampleTable(sampleTable)
                  .durationRuns(durationRuns)
                  .sizes(sizes)
                  .build()));
    }

    /** The kept keyframes of this track, in decode order. */
    List<Keyframe> keyframes() {
      var keyframes = new ArrayList<Keyframe>();
      for (var sample = first; sample < sizes.length; sample++) {
        if (sync[sample]) {
          keyframes.add(new Keyframe(sample, presentationTimes[sample], decodeTimes[sample]));
        }
      }

      return keyframes;
    }

    Optional<TrackMeasurement> measure(int streamIndex, Track chosen, List<Keyframe> keyframes) {
      var intervals = new long[keyframes.size()];
      var packets = new int[keyframes.size()];
      var boundaries = boundaries(chosen, keyframes);
      var interval = 0;
      long bytes = 0;
      var firstPresentation = Long.MAX_VALUE;
      var lastEnd = Long.MIN_VALUE;
      for (var sample = first; sample < sizes.length; sample++) {
        var size = Integer.toUnsignedLong(sizes[sample]);
        var position = this == chosen ? sample : time(sample);
        while (interval + 1 < boundaries.length && position >= boundaries[interval + 1]) {
          interval++;
        }

        intervals[interval] += size;
        packets[interval]++;
        bytes += size;
        firstPresentation = Math.min(firstPresentation, presentationTimes[sample]);
        lastEnd = Math.max(lastEnd, Math.addExact(presentationTimes[sample], durations[sample]));
      }

      if (Arrays.stream(intervals).anyMatch(count -> count > LARGEST_INTERVAL_BYTES)) {
        return Optional.empty();
      }

      var counts = new int[intervals.length];
      for (var i = 0; i < intervals.length; i++) {
        counts[i] = (int) intervals[i];
      }

      return Optional.of(
          TrackMeasurement.builder()
              .streamIndex(streamIndex)
              .measuredBytes(bytes)
              .packetCount(sizes.length - (long) first)
              .timescale(timescale)
              .measuredDuration(Math.subtractExact(lastEnd, firstPresentation))
              .intervalBytes(counts)
              .intervalPacketCounts(packets)
              .build());
    }

    /**
     * Where each keyframe interval starts, in what this track orders its samples by: the chosen
     * track's decode-order sample numbers, another video track's decode times, or an audio track's
     * presentation times. Each start is the first value at or after the keyframe's time.
     */
    private long[] boundaries(Track chosen, List<Keyframe> keyframes) {
      var boundaries = new long[keyframes.size()];
      for (var i = 0; i < keyframes.size(); i++) {
        var keyframe = keyframes.get(i);
        if (this == chosen) {
          boundaries[i] = keyframe.sample();
          continue;
        }

        var time = isVideo ? keyframe.decodeTime() : keyframe.presentationTime();
        boundaries[i] = rescaleUp(time, timescale, chosen.timescale());
      }

      return boundaries;
    }

    private long time(int sample) {
      return isVideo ? decodeTimes[sample] : presentationTimes[sample];
    }

    // The smallest tick of this track's timescale at or after the time.
    private static long rescaleUp(long ticks, long timescale, long fromTimescale) {
      return Math.ceilDiv(Math.multiplyExact(ticks, timescale), fromTimescale);
    }
  }

  @Builder
  private record Timing(
      boolean isVideo,
      long timescale,
      long movieTimescale,
      boolean hasEditList,
      Edit edit,
      ByteBuffer sampleTable,
      List<Run> durationRuns,
      int[] sizes) {}

  private static Track timed(Timing timing) {
    var count = timing.sizes().length;
    var offsets =
        timing.isVideo()
            ? child(timing.sampleTable(), "ctts").map(Mp4SampleTables::runs).orElse(List.<Run>of())
            : List.<Run>of();
    var shift = timing.edit().shift(timing.movieTimescale(), timing.timescale());
    var decodeShift = decodeShift(offsets);
    var sync = syncSamples(timing.sampleTable(), count);
    var presentationTimes = new long[count];
    var decodeTimes = new long[count];
    var durations = new long[count];
    var offsetRuns = new RunCursor(offsets);
    var sample = 0;
    long decodeTime = 0;
    for (var run : timing.durationRuns()) {
      if (run.value() < 0) {
        throw malformed("negative stts duration");
      }

      for (long i = 0; i < run.count(); i++, sample++) {
        var offset = offsetRuns.next();
        presentationTimes[sample] = decodeTime + offset;
        decodeTimes[sample] = decodeTime - decodeShift;
        durations[sample] = run.value();
        decodeTime += run.value();
      }
    }

    var first = firstKept(timing, presentationTimes, sync);
    var presentsBeforeEditStart = false;
    for (var i = first; timing.hasEditList() && i < count; i++) {
      presentsBeforeEditStart |= presentationTimes[i] < timing.edit().mediaTime();
    }

    if (timing.hasEditList() && dropsSamplesAfterEnd(timing, presentationTimes, durations)) {
      throw malformed("edit list ends before the track's last sample");
    }

    for (var i = 0; i < count; i++) {
      presentationTimes[i] = Math.addExact(presentationTimes[i], shift);
      decodeTimes[i] = Math.addExact(decodeTimes[i], shift);
    }

    return new Track(
        timing.isVideo(),
        timing.timescale(),
        timing.sizes(),
        presentationTimes,
        decodeTimes,
        durations,
        sync,
        first,
        presentsBeforeEditStart);
  }

  /**
   * Without an edit list FFmpeg keeps every sample. Under one it keeps a video track's samples from
   * the last keyframe that presents at or before the edit's media time, and an audio track's
   * samples from one second before the media time, so the measurer measures an audio track only
   * while that second reaches back to its first sample.
   */
  private static int firstKept(Timing timing, long[] presentationTimes, boolean[] sync) {
    if (!timing.hasEditList()) {
      return 0;
    }

    if (!timing.isVideo()) {
      return firstKeptAudioSample(timing);
    }

    return lastSyncSampleAtOrBefore(timing.edit().mediaTime(), presentationTimes, sync);
  }

  private static int firstKeptAudioSample(Timing timing) {
    if (timing.edit().mediaTime() > timing.timescale()) {
      throw malformed("edit list trims more than one second of audio");
    }

    return 0;
  }

  private static int lastSyncSampleAtOrBefore(
      long mediaTime, long[] presentationTimes, boolean[] sync) {
    var first = -1;
    for (var sample = 0; sample < presentationTimes.length; sample++) {
      if (sync[sample] && presentationTimes[sample] <= mediaTime) {
        first = sample;
      }
    }

    if (first < 0) {
      throw malformed("no sync sample presents at or before the edit's media time");
    }

    return first;
  }

  /**
   * FFmpeg stops a track at the first audio sample that reaches the edit's end, and after the
   * keyframes that follow a video track's end. The measurer models neither, so it measures a track
   * only when no sample starts at or after that end, or, for audio, only the last sample reaches
   * it.
   */
  private static boolean dropsSamplesAfterEnd(
      Timing timing, long[] presentationTimes, long[] durations) {
    var mediaEnd = timing.edit().mediaEnd(timing.movieTimescale(), timing.timescale());
    var last = presentationTimes.length - 1;
    for (var sample = 0; sample < presentationTimes.length; sample++) {
      if (timing.isVideo() && presentationTimes[sample] >= mediaEnd) {
        return true;
      }

      if (!timing.isVideo()
          && sample < last
          && presentationTimes[sample] + durations[sample] >= mediaEnd) {
        return true;
      }
    }

    return false;
  }

  private static boolean[] syncSamples(ByteBuffer sampleTable, int count) {
    var sync = new boolean[count];
    var table = child(sampleTable, "stss");
    if (table.isEmpty()) {
      Arrays.fill(sync, true);
      return sync;
    }

    var box = table.get();
    box.getInt();
    var entries = Integer.toUnsignedLong(box.getInt());
    for (long i = 0; i < entries; i++) {
      var sample = Integer.toUnsignedLong(box.getInt());
      if (sample >= 1 && sample <= count) {
        sync[(int) (sample - 1)] = true;
      }
    }

    return sync;
  }

  /** Every sample's size, from stsz or stz2, as unsigned 32-bit values. */
  private static int[] sampleSizes(ByteBuffer sampleTable) {
    var sizes = child(sampleTable, "stsz");
    if (sizes.isPresent()) {
      return sizeTable(sizes.get());
    }

    return compactSizeTable(child(sampleTable, "stz2").orElseThrow(() -> malformed("stsz")));
  }

  private static int[] sizeTable(ByteBuffer box) {
    skip(box, 4);
    var constantSize = box.getInt();
    var count = sampleCount(Integer.toUnsignedLong(box.getInt()));
    var sizes = new int[count];
    if (constantSize != 0) {
      Arrays.fill(sizes, constantSize);
      return sizes;
    }

    if (box.remaining() / Integer.BYTES < count) {
      throw malformed("stsz shorter than its sample count");
    }

    for (var i = 0; i < count; i++) {
      sizes[i] = box.getInt();
    }

    return sizes;
  }

  private static int[] compactSizeTable(ByteBuffer box) {
    skip(box, 7);
    var fieldBits = box.get() & 0xFF;
    var count = sampleCount(Integer.toUnsignedLong(box.getInt()));
    if (fieldBits != 4 && fieldBits != 8 && fieldBits != 16) {
      throw malformed("stz2 field size");
    }

    if (box.remaining() * 8L / fieldBits < count) {
      throw malformed("stz2 shorter than its sample count");
    }

    var sizes = new int[count];
    for (var i = 0; i < count; i++) {
      sizes[i] =
          switch (fieldBits) {
            case 4 -> (box.get(box.position() + i / 2) >> (i % 2 == 0 ? 4 : 0)) & 0x0F;
            case 8 -> box.get(box.position() + i) & 0xFF;
            default -> box.getShort(box.position() + 2 * i) & 0xFFFF;
          };
    }

    return sizes;
  }

  private static int sampleCount(long count) {
    if (count > MAXIMUM_SAMPLES) {
      throw malformed("more samples than the measurer lists");
    }

    return (int) count;
  }

  /**
   * FFmpeg's mov demuxer returns an audio track whose only stts entry gives every sample a duration
   * of 1, as uncompressed audio often has, as packets of whole chunks rather than one packet for
   * each sample. The measurer measures samples, so it leaves such a track out. It also leaves out
   * an audio track with composition offsets, whose times it does not model.
   */
  private static boolean isChunkedAudio(List<Run> durationRuns) {
    return durationRuns.size() == 1 && durationRuns.getFirst().value() == 1;
  }

  private static long totalCount(List<Run> runs) {
    return runs.stream().mapToLong(Run::count).sum();
  }

  private static String handlerType(ByteBuffer handler) {
    skip(handler, 8);
    return fourCharacterCode(handler);
  }
}
