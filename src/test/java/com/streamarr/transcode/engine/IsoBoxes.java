package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongFunction;
import lombok.Builder;

/** Writes the ISOBMFF boxes of a fragmented MP4 stream for reader tests. */
final class IsoBoxes {

  static final int VIDEO_TRACK_ID = 1;
  static final int AUDIO_TRACK_ID = 2;
  static final int SYNC_SAMPLE_FLAGS = 0x0200_0000;
  static final int NON_SYNC_SAMPLE_FLAGS = 0x0101_0000;

  /** A full box's version 1, to combine with its flags. */
  static final int VERSION_1 = 1 << 24;

  static final int TFHD_BASE_DATA_OFFSET = 0x000001;
  static final int TFHD_SAMPLE_DESCRIPTION_INDEX = 0x000002;
  static final int TFHD_DEFAULT_SAMPLE_DURATION = 0x000008;
  static final int TFHD_DEFAULT_SAMPLE_SIZE = 0x000010;
  static final int TFHD_DEFAULT_SAMPLE_FLAGS = 0x000020;
  static final int TFHD_DEFAULT_BASE_IS_MOOF = 0x020000;

  static final int TRUN_DATA_OFFSET = 0x000001;
  static final int TRUN_FIRST_SAMPLE_FLAGS = 0x000004;
  static final int TRUN_SAMPLE_DURATION = 0x000100;
  static final int TRUN_SAMPLE_SIZE = 0x000200;
  static final int TRUN_SAMPLE_FLAGS = 0x000400;
  static final int TRUN_SAMPLE_COMPOSITION_TIME_OFFSET = 0x000800;

  private static final int TKHD_ENABLED_IN_MOVIE = 0x000003;
  private static final int SAMPLE_BYTES = 1;
  private static final int COMPACT_HEADER_BYTES = 8;

  private IsoBoxes() {}

  static byte[] box(String type, byte[]... payloads) {
    var body = concat(payloads);
    return concat(u32(8L + body.length), fourcc(type), body);
  }

  static byte[] fullBox(String type, int versionAndFlags, byte[]... payloads) {
    return box(type, u32(Integer.toUnsignedLong(versionAndFlags)), concat(payloads));
  }

  static byte[] largeSizeBox(String type, byte[] body) {
    return concat(u32(1), fourcc(type), u64(16L + body.length), body);
  }

  static byte[] header(long size, String type) {
    return concat(u32(size), fourcc(type));
  }

  static byte[] largeSizeHeader(long size, String type) {
    return concat(u32(1), fourcc(type), u64(size));
  }

  static byte[] ftyp() {
    return box("ftyp", fourcc("iso6"), u32(512), fourcc("iso6"), fourcc("cmfc"), fourcc("mp41"));
  }

  static byte[] moov(Track... tracks) {
    var traks = Arrays.stream(tracks).map(Track::trak).toArray(byte[][]::new);
    var trexes = Arrays.stream(tracks).map(Track::trex).toArray(byte[][]::new);
    return box("moov", fullBox("mvhd", 0, new byte[96]), concat(traks), box("mvex", trexes));
  }

  static byte[] videoAndAudioMoov() {
    return moov(Track.video().build(), Track.audio().build());
  }

  /**
   * A moof whose runs point at their samples, one byte each and track by track, in an mdat that
   * starts right after it with a compact header.
   */
  static byte[] moof(TrackFragment... trackFragments) {
    return moofFollowedBy(COMPACT_HEADER_BYTES, trackFragments);
  }

  /** A moof whose runs point at their samples in an mdat whose header has the given length. */
  static byte[] moofFollowedBy(int mdatHeaderBytes, TrackFragment... trackFragments) {
    return pointedAtMdatBody(
        0, mdatHeaderBytes, dataStart -> moofWithDataAt(dataStart, trackFragments));
  }

  /**
   * The moof a builder writes for the position where the body of the mdat after it starts, when
   * that mdat has a compact header: pass the moof's stream position for a tfhd base-data-offset, or
   * 0 for data offsets that count from the moof.
   */
  static byte[] moofPointingAtItsMdat(long moofPosition, LongFunction<byte[]> moofWithDataAt) {
    return pointedAtMdatBody(moofPosition, COMPACT_HEADER_BYTES, moofWithDataAt);
  }

  private static byte[] pointedAtMdatBody(
      long moofPosition, int mdatHeaderBytes, LongFunction<byte[]> moofWithDataAt) {
    var moofBytes = moofWithDataAt.apply(0).length;
    return moofWithDataAt.apply(moofPosition + moofBytes + mdatHeaderBytes);
  }

  private static byte[] moofWithDataAt(long dataStart, TrackFragment... trackFragments) {
    var trafs = new byte[trackFragments.length][];
    var dataOffset = Math.toIntExact(dataStart);
    for (var index = 0; index < trackFragments.length; index++) {
      trafs[index] = trackFragments[index].traf(dataOffset);
      dataOffset += trackFragments[index].sampleBytes();
    }

    return box("moof", fullBox("mfhd", 0, u32(1)), concat(trafs));
  }

  /**
   * An initialization segment and one keyframe fragment a frame past each second, at 24000/1001
   * frames per second in the 24 kHz video timescale, whose video samples last as given, so that
   * fragment {@code n} opens segment {@code n} of a 1 s period.
   */
  static byte[] oneSecondKeyframeFragments(List<List<Integer>> sampleDurationsOfEachFragment) {
    var output = new ByteArrayOutputStream();
    output.writeBytes(ftyp());
    output.writeBytes(videoAndAudioMoov());
    for (var index = 0; index < sampleDurationsOfEachFragment.size(); index++) {
      var durations = sampleDurationsOfEachFragment.get(index);
      output.writeBytes(
          moof(
              videoTraf()
                  .baseMediaDecodeTime(index * 24_024L)
                  .firstSampleFlags(SYNC_SAMPLE_FLAGS)
                  .sampleDurations(durations)
                  .build()));
      output.writeBytes(mdat(durations.size() * SAMPLE_BYTES));
    }

    return output.toByteArray();
  }

  static byte[] mdat(int payloadBytes) {
    var payload = new byte[payloadBytes];
    Arrays.fill(payload, (byte) 0x5A);
    return box("mdat", payload);
  }

  /** A {@code tfhd} whose data offsets are relative to the {@code moof}, with no default fields. */
  static byte[] tfhd(int trackId) {
    return fullBox("tfhd", TFHD_DEFAULT_BASE_IS_MOOF, u32(trackId));
  }

  /** A version 1 {@code tfdt}, whose decode time is a signed 64-bit value. */
  static byte[] tfdt(long baseMediaDecodeTime) {
    return fullBox("tfdt", VERSION_1, u64(baseMediaDecodeTime));
  }

  static TrackFragment.TrackFragmentBuilder videoTraf() {
    return TrackFragment.builder().trackId(VIDEO_TRACK_ID);
  }

  static TrackFragment.TrackFragmentBuilder audioTraf() {
    return TrackFragment.builder().trackId(AUDIO_TRACK_ID).defaultSampleFlags(SYNC_SAMPLE_FLAGS);
  }

  static byte[] u32(long value) {
    return ByteBuffer.allocate(4).putInt((int) value).array();
  }

  static byte[] u64(long value) {
    return ByteBuffer.allocate(8).putLong(value).array();
  }

  static byte[] fourcc(String type) {
    return type.getBytes(StandardCharsets.ISO_8859_1);
  }

  static byte[] concat(byte[]... parts) {
    var out = new ByteArrayOutputStream();
    for (var part : parts) {
      out.writeBytes(part);
    }

    return out.toByteArray();
  }

  /** One {@code trak} of the {@code moov} and its {@code trex} defaults. */
  @Builder
  record Track(
      int trackId,
      String handler,
      long timescale,
      int defaultSampleDuration,
      int defaultSampleFlags,
      int headerVersion) {

    static TrackBuilder video() {
      return builder()
          .trackId(VIDEO_TRACK_ID)
          .handler("vide")
          .timescale(24_000)
          .defaultSampleFlags(NON_SYNC_SAMPLE_FLAGS);
    }

    static TrackBuilder audio() {
      return builder().trackId(AUDIO_TRACK_ID).handler("soun").timescale(48_000);
    }

    byte[] trak() {
      return box("trak", tkhd(), box("mdia", mdhd(), hdlr(), box("minf")));
    }

    byte[] trex() {
      return fullBox(
          "trex",
          0,
          u32(trackId),
          u32(1),
          u32(defaultSampleDuration),
          u32(0),
          u32(defaultSampleFlags));
    }

    private byte[] tkhd() {
      var versionAndFlags = headerVersion << 24 | TKHD_ENABLED_IN_MOVIE;
      if (headerVersion == 1) {
        return fullBox("tkhd", versionAndFlags, u64(0), u64(0), u32(trackId), new byte[68]);
      }

      return fullBox("tkhd", versionAndFlags, u32(0), u32(0), u32(trackId), new byte[64]);
    }

    private byte[] mdhd() {
      if (headerVersion == 1) {
        return fullBox("mdhd", VERSION_1, u64(0), u64(0), u32(timescale), u64(0), u32(0));
      }

      return fullBox("mdhd", 0, u32(0), u32(0), u32(timescale), u32(0), u32(0));
    }

    private byte[] hdlr() {
      return fullBox("hdlr", 0, u32(0), fourcc(handler), new byte[12], fourcc("name"), new byte[1]);
    }
  }

  /**
   * One {@code traf} of a {@code moof}. Optional fields left unset are omitted from the box, as a
   * muxer omits them when the flag that announces them is clear.
   */
  @Builder
  static final class TrackFragment {

    private final int trackId;
    private final Integer defaultSampleDuration;
    private final Integer defaultSampleFlags;
    private final Long baseMediaDecodeTime;
    private final int decodeTimeVersion;
    private final int runVersion;
    private final Integer sampleCount;
    private final Integer firstSampleFlags;
    private final Integer sampleFlags;
    private final Integer compositionOffset;
    private final List<Integer> sampleDurations;

    byte[] traf(int dataOffset) {
      return box("traf", tfhd(), tfdt(), trun(dataOffset));
    }

    int sampleBytes() {
      return samples() * SAMPLE_BYTES;
    }

    private int samples() {
      if (sampleCount != null) {
        return sampleCount;
      }

      if (sampleDurations != null) {
        return sampleDurations.size();
      }

      return 1;
    }

    private byte[] tfhd() {
      var flags = TFHD_DEFAULT_BASE_IS_MOOF;
      var fields = new ByteArrayOutputStream();
      fields.writeBytes(u32(trackId));
      if (defaultSampleDuration != null) {
        flags |= TFHD_DEFAULT_SAMPLE_DURATION;
        fields.writeBytes(u32(defaultSampleDuration));
      }

      if (defaultSampleFlags != null) {
        flags |= TFHD_DEFAULT_SAMPLE_FLAGS;
        fields.writeBytes(u32(defaultSampleFlags));
      }

      return fullBox("tfhd", flags, fields.toByteArray());
    }

    private byte[] tfdt() {
      if (baseMediaDecodeTime == null) {
        return new byte[0];
      }

      if (decodeTimeVersion == 0) {
        return fullBox("tfdt", 0, u32(baseMediaDecodeTime));
      }

      return IsoBoxes.tfdt(baseMediaDecodeTime);
    }

    private byte[] trun(int dataOffset) {
      var samples = samples();
      var flags = TRUN_DATA_OFFSET | TRUN_SAMPLE_SIZE;
      var header = new ByteArrayOutputStream();
      header.writeBytes(u32(samples));
      header.writeBytes(u32(dataOffset));
      if (firstSampleFlags != null) {
        flags |= TRUN_FIRST_SAMPLE_FLAGS;
        header.writeBytes(u32(firstSampleFlags));
      }

      if (sampleFlags != null) {
        flags |= TRUN_SAMPLE_FLAGS;
      }

      if (compositionOffset != null) {
        flags |= TRUN_SAMPLE_COMPOSITION_TIME_OFFSET;
      }

      if (sampleDurations != null) {
        flags |= TRUN_SAMPLE_DURATION;
      }

      for (var sample = 0; sample < samples; sample++) {
        header.writeBytes(sampleEntry(sample));
      }

      return fullBox("trun", runVersion << 24 | flags, header.toByteArray());
    }

    private byte[] sampleEntry(int sample) {
      var entry = new ByteArrayOutputStream();
      if (sampleDurations != null) {
        entry.writeBytes(u32(sampleDurations.get(sample)));
      }

      entry.writeBytes(u32(SAMPLE_BYTES));
      if (sampleFlags != null) {
        entry.writeBytes(u32(sampleFlags));
      }

      if (compositionOffset == null) {
        return entry.toByteArray();
      }

      var offset = 0;
      if (sample == 0) {
        offset = compositionOffset;
      }

      entry.writeBytes(u32(offset));
      return entry.toByteArray();
    }
  }
}
