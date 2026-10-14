package com.streamarr.transcode.probe;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.Builder;
import lombok.Singular;

/** A minimal MP4 file: ftyp, then a moov with track 1, whose movie timescale is 1,000. */
@Builder
record SyntheticMp4(
    boolean largeMovieBox,
    String handler,
    long mediaTimescale,
    int sampleCount,
    int sampleDuration,
    Integer sampleSizeCount,
    int sampleSize,
    boolean compactSampleSizes,
    int compositionOffsetCount,
    int compositionOffset,
    List<Long> syncSamples,
    int editListVersion,
    @Singular List<Edit> edits,
    boolean fragmented) {

  static final long TRACK_ID = 1;

  record Edit(long duration, long mediaTime) {}

  byte[] bytes() {
    var sampleTable = new ArrayList<byte[]>();
    sampleTable.add(fullBox("stts", 0, int32(1), int32(sampleCount), int32(sampleDuration)));
    sampleTable.add(sampleSizeBox());
    if (compositionOffsetCount > 0) {
      sampleTable.add(
          fullBox("ctts", 1, int32(1), int32(compositionOffsetCount), int32(compositionOffset)));
    }

    if (syncSamples != null) {
      var entries = new ArrayList<byte[]>();
      entries.add(int32(syncSamples.size()));
      syncSamples.forEach(sample -> entries.add(int32(sample.intValue())));
      sampleTable.add(fullBox("stss", 0, entries.toArray(byte[][]::new)));
    }

    var media =
        box(
            "mdia",
            fullBox("mdhd", 0, int32(0), int32(0), int32((int) mediaTimescale), int32(0)),
            fullBox("hdlr", 0, int32(0), ascii(handler == null ? "vide" : handler), new byte[12]),
            box("minf", box("stbl", sampleTable.toArray(byte[][]::new))));
    var track = new ArrayList<byte[]>();
    track.add(fullBox("tkhd", 0, int32(0), int32(0), int32((int) TRACK_ID)));
    if (!edits.isEmpty()) {
      track.add(box("edts", editList()));
    }

    track.add(media);
    var movie = new ArrayList<byte[]>();
    movie.add(fullBox("mvhd", 0, int32(0), int32(0), int32(1_000), int32(0)));
    movie.add(box("trak", track.toArray(byte[][]::new)));
    if (fragmented) {
      movie.add(box("mvex", fullBox("trex", 0, new byte[20])));
    }

    var moov = concat(movie.toArray(byte[][]::new));
    return concat(
        box("ftyp", ascii("isom"), int32(0)),
        largeMovieBox ? largeBox("moov", moov) : box("moov", moov));
  }

  static class SyntheticMp4Builder {
    SyntheticMp4Builder sampleDurations(int count, int duration) {
      return sampleCount(count).sampleDuration(duration);
    }

    /** Writes an stsz with one size for every sample; without it, each sample has size 1. */
    SyntheticMp4Builder sampleSizes(int count, int size) {
      return sampleSizeCount(count).sampleSize(size);
    }

    SyntheticMp4Builder compositionOffsets(int count, int offset) {
      return compositionOffsetCount(count).compositionOffset(offset);
    }
  }

  private byte[] sampleSizeBox() {
    var count = sampleSizeCount == null ? sampleCount : sampleSizeCount;
    var size = sampleSizeCount == null ? 1 : sampleSize;
    if (compactSampleSizes) {
      // stz2 with 8-bit fields: three reserved bytes, the field size, the count, then the fields.
      var fields = new byte[count];
      Arrays.fill(fields, (byte) size);
      return fullBox("stz2", 0, new byte[] {0, 0, 0, 8}, int32(count), fields);
    }

    return fullBox("stsz", 0, int32(size), int32(count));
  }

  private byte[] editList() {
    var entries = new ArrayList<byte[]>();
    entries.add(int32(edits.size()));
    for (var edit : edits) {
      if (editListVersion == 1) {
        entries.add(int64(edit.duration()));
        entries.add(int64(edit.mediaTime()));
      } else {
        entries.add(int32((int) edit.duration()));
        entries.add(int32((int) edit.mediaTime()));
      }

      entries.add(int32(0x0001_0000));
    }

    return fullBox("elst", editListVersion, entries.toArray(byte[][]::new));
  }

  static byte[] box(String type, byte[]... children) {
    var payload = concat(children);
    return concat(int32(payload.length + 8), ascii(type), payload);
  }

  private static byte[] largeBox(String type, byte[] payload) {
    return concat(int32(1), ascii(type), int64(payload.length + 16L), payload);
  }

  private static byte[] fullBox(String type, int version, byte[]... children) {
    return box(type, concat(new byte[] {(byte) version, 0, 0, 0}, concat(children)));
  }

  static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.ISO_8859_1);
  }

  static byte[] int32(int value) {
    return ByteBuffer.allocate(4).putInt(value).array();
  }

  private static byte[] int64(long value) {
    return ByteBuffer.allocate(8).putLong(value).array();
  }

  private static byte[] concat(byte[]... parts) {
    var output = new ByteArrayOutputStream();
    for (var part : parts) {
      output.writeBytes(part);
    }

    return output.toByteArray();
  }
}
