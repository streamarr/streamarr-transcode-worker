package com.streamarr.transcode.engine;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import lombok.Builder;

/** Writes the ISOBMFF boxes of a fragmented MP4 stream for reader tests. */
final class IsoBoxes {

  static final int VIDEO_TRACK_ID = 1;
  static final int AUDIO_TRACK_ID = 2;
  static final int SYNC_SAMPLE_FLAGS = 0x0200_0000;
  static final int NON_SYNC_SAMPLE_FLAGS = 0x0101_0000;

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

  static byte[] moof(TrackFragment... trackFragments) {
    var trafs = Arrays.stream(trackFragments).map(TrackFragment::traf).toArray(byte[][]::new);
    return box("moof", fullBox("mfhd", 0, u32(1)), concat(trafs));
  }

  static byte[] mdat(int payloadBytes) {
    var payload = new byte[payloadBytes];
    Arrays.fill(payload, (byte) 0x5A);
    return box("mdat", payload);
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
      int trackId, String handler, long timescale, int defaultSampleFlags, int headerVersion) {

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
      return fullBox("trex", 0, u32(trackId), u32(1), u32(0), u32(0), u32(defaultSampleFlags));
    }

    private byte[] tkhd() {
      var versionAndFlags = headerVersion << 24 | 0x000003;
      if (headerVersion == 1) {
        return fullBox("tkhd", versionAndFlags, u64(0), u64(0), u32(trackId), new byte[68]);
      }

      return fullBox("tkhd", versionAndFlags, u32(0), u32(0), u32(trackId), new byte[64]);
    }

    private byte[] mdhd() {
      if (headerVersion == 1) {
        return fullBox("mdhd", 1 << 24, u64(0), u64(0), u32(timescale), u64(0), u32(0));
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
  record TrackFragment(
      int trackId,
      Integer defaultSampleFlags,
      Long baseMediaDecodeTime,
      int decodeTimeVersion,
      int runVersion,
      Integer sampleCount,
      Integer firstSampleFlags,
      Integer sampleFlags,
      Integer compositionOffset) {

    byte[] traf() {
      return box("traf", tfhd(), tfdt(), trun());
    }

    private byte[] tfhd() {
      var defaultBaseIsMoof = 0x020000;
      if (defaultSampleFlags == null) {
        return fullBox("tfhd", defaultBaseIsMoof, u32(trackId));
      }

      return fullBox("tfhd", defaultBaseIsMoof | 0x000020, u32(trackId), u32(defaultSampleFlags));
    }

    private byte[] tfdt() {
      if (baseMediaDecodeTime == null) {
        return new byte[0];
      }

      if (decodeTimeVersion == 0) {
        return fullBox("tfdt", 0, u32(baseMediaDecodeTime));
      }

      return fullBox("tfdt", 1 << 24, u64(baseMediaDecodeTime));
    }

    private byte[] trun() {
      var samples = sampleCount == null ? 1 : sampleCount;
      var flags = 0x000001 | 0x000200;
      var header = new ByteArrayOutputStream();
      header.writeBytes(u32(samples));
      header.writeBytes(u32(0));
      if (firstSampleFlags != null) {
        flags |= 0x000004;
        header.writeBytes(u32(firstSampleFlags));
      }

      flags |= sampleFlags == null ? 0 : 0x000400;
      flags |= compositionOffset == null ? 0 : 0x000800;
      for (var sample = 0; sample < samples; sample++) {
        header.writeBytes(sampleEntry(sample));
      }

      return fullBox("trun", runVersion << 24 | flags, header.toByteArray());
    }

    private byte[] sampleEntry(int sample) {
      var entry = new ByteArrayOutputStream();
      entry.writeBytes(u32(100));
      if (sampleFlags != null) {
        entry.writeBytes(u32(sampleFlags));
      }

      if (compositionOffset != null) {
        entry.writeBytes(u32(sample == 0 ? compositionOffset : 0));
      }

      return entry.toByteArray();
    }
  }
}
