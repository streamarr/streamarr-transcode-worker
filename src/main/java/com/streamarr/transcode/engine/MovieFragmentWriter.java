package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.NonNull;

/**
 * Writes a media segment's samples as one {@code moof} and the header of the one {@code mdat} that
 * follows it: an {@code mfhd}, then one {@code traf} per track in the order given, each with a
 * {@code tfhd} whose data offsets count from the {@code moof}, a version 1 {@code tfdt} and a
 * version 1 {@code trun} that states every sample's duration, size, flags and composition offset.
 * The {@code mdat} holds each track's samples in turn.
 */
final class MovieFragmentWriter {

  /** The bytes one sample's entry takes in a {@code trun}. */
  static final int SAMPLE_ENTRY_BYTES = 16;

  private static final int BOX_HEADER_BYTES = 8;
  private static final int MFHD_BYTES = 16;
  private static final int TFHD_BYTES = 16;
  private static final int TFDT_BYTES = 20;
  private static final int TRUN_HEADER_BYTES = 20;
  private static final int VERSION_1 = 1 << 24;
  private static final int DEFAULT_BASE_IS_MOOF = 0x02_0000;
  private static final int TRUN_FIELDS = 0x00_0001 | 0x00_0100 | 0x00_0200 | 0x00_0400 | 0x00_0800;
  private static final long MAXIMUM_UNSIGNED_32 = 0xFFFF_FFFFL;

  private MovieFragmentWriter() {}

  /** The bytes of the {@code moof} and of the {@code mdat}'s header for these tracks. */
  static long headerBytes(List<Track> tracks) {
    return moofBytes(tracks) + BOX_HEADER_BYTES;
  }

  /**
   * Writes the {@code moof} and the {@code mdat}'s header.
   *
   * @param sequenceNumber the {@code mfhd} sequence number
   */
  static byte[] header(long sequenceNumber, List<Track> tracks) {
    var moofBytes = moofBytes(tracks);
    var header = ByteBuffer.allocate(Math.toIntExact(moofBytes + BOX_HEADER_BYTES));
    boxHeader(header, moofBytes, "moof");
    fullBoxHeader(header, MFHD_BYTES, "mfhd", 0);
    header.putInt((int) sequenceNumber);
    var dataOffset = moofBytes + BOX_HEADER_BYTES;
    for (var track : tracks) {
      writeTraf(header, track, dataOffset);
      dataOffset += track.sampleBytes();
    }

    boxHeader(
        header, BOX_HEADER_BYTES + tracks.stream().mapToLong(Track::sampleBytes).sum(), "mdat");
    return header.array();
  }

  private static long moofBytes(List<Track> tracks) {
    return BOX_HEADER_BYTES + MFHD_BYTES + tracks.stream().mapToLong(Track::trafBytes).sum();
  }

  private static void writeTraf(ByteBuffer header, Track track, long dataOffset) {
    var samples = track.samples();
    boxHeader(header, track.trafBytes(), "traf");
    fullBoxHeader(header, TFHD_BYTES, "tfhd", DEFAULT_BASE_IS_MOOF);
    header.putInt((int) track.trackId());
    fullBoxHeader(header, TFDT_BYTES, "tfdt", VERSION_1);
    header.putLong(samples.getFirst().decodeTime());
    fullBoxHeader(header, track.trunBytes(), "trun", VERSION_1 | TRUN_FIELDS);
    header.putInt(samples.size());
    header.putInt(Math.toIntExact(dataOffset));
    for (var index = 0; index < samples.size(); index++) {
      var sample = samples.get(index);
      header.putInt((int) track.writtenDuration(index));
      header.putInt((int) sample.size());
      header.putInt(sample.flags());
      header.putInt((int) sample.compositionOffset());
    }
  }

  private static void boxHeader(ByteBuffer header, long size, String type) {
    header.putInt(Math.toIntExact(size));
    header.put(type.getBytes(StandardCharsets.ISO_8859_1));
  }

  private static void fullBoxHeader(ByteBuffer header, long size, String type, int versionFlags) {
    boxHeader(header, size, type);
    header.putInt(versionFlags);
  }

  /**
   * One track's samples of the segment, in decode order, of which there is at least one. Each
   * sample lasts until the next one decodes, as the durations FFmpeg writes within one fragment do,
   * and the last keeps its own.
   */
  static final class Track {

    private final long trackId;
    private final List<Sample> samples;
    private final long[] writtenDurations;

    /**
     * @throws FragmentedMp4Exception when the samples' decode times run backwards, or a sample's
     *     composition offset does not fit a version 1 {@code trun}
     */
    Track(long trackId, @NonNull List<Sample> samples) {
      samples.forEach(sample -> requireSignedCompositionOffset(trackId, sample));
      this.trackId = trackId;
      this.samples = List.copyOf(samples);
      this.writtenDurations = writtenDurationsOf(trackId, this.samples);
    }

    long trackId() {
      return trackId;
    }

    List<Sample> samples() {
      return samples;
    }

    long sampleBytes() {
      return samples.stream().mapToLong(Sample::size).sum();
    }

    private long writtenDuration(int index) {
      return writtenDurations[index];
    }

    private long trunBytes() {
      return TRUN_HEADER_BYTES + (long) SAMPLE_ENTRY_BYTES * samples.size();
    }

    private long trafBytes() {
      return BOX_HEADER_BYTES + TFHD_BYTES + TFDT_BYTES + trunBytes();
    }

    private static long[] writtenDurationsOf(long trackId, List<Sample> samples) {
      var durations = new long[samples.size()];
      for (var index = 1; index < samples.size(); index++) {
        durations[index - 1] = durationBetween(trackId, samples.get(index - 1), samples.get(index));
      }

      durations[samples.size() - 1] = samples.getLast().duration();
      return durations;
    }

    private static long durationBetween(long trackId, Sample sample, Sample next) {
      var duration = next.decodeTime() - sample.decodeTime();
      if (duration < 0 || duration > MAXIMUM_UNSIGNED_32) {
        throw FragmentedMp4Exception.malformed(
            "track "
                + trackId
                + " decodes a sample at "
                + next.decodeTime()
                + " after one at "
                + sample.decodeTime());
      }

      return duration;
    }

    private static void requireSignedCompositionOffset(long trackId, Sample sample) {
      var offset = sample.compositionOffset();
      if (offset < Integer.MIN_VALUE || offset > Integer.MAX_VALUE) {
        throw FragmentedMp4Exception.malformed(
            "track " + trackId + " offsets a sample's composition by " + offset);
      }
    }
  }
}
