package com.streamarr.transcode.probe;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import lombok.Builder;
import lombok.Singular;

/**
 * A minimal MP4 file: ftyp, then a moov with track 1, whose movie timescale is 1,000. With {@code
 * version1Headers}, the mvhd, tkhd and mdhd boxes use their version 1 layout with 64-bit times.
 */
@Builder
record SyntheticMp4(
    boolean largeMovieBox,
    boolean version1Headers,
    String handler,
    long mediaTimescale,
    int sampleCount,
    int sampleDuration,
    Integer sampleSizeCount,
    int sampleSize,
    boolean compactSampleSizes,
    int compositionOffsetCount,
    int compositionOffset,
    @Singular List<CompositionRun> compositionRuns,
    List<Long> syncSamples,
    int editListVersion,
    @Singular List<Edit> edits,
    boolean fragmented) {

  static final long TRACK_ID = 1;

  /** One elst entry; {@code rate} is a 16.16 fixed-point number. */
  record Edit(long duration, long mediaTime, int rate) {
    static final int NORMAL_RATE = 0x0001_0000;

    Edit(long duration, long mediaTime) {
      this(duration, mediaTime, NORMAL_RATE);
    }
  }

  /** One ctts entry: {@code count} samples whose composition offset is {@code offset}. */
  record CompositionRun(int count, int offset) {}

  byte[] bytes() {
    var sampleTable = new ArrayList<byte[]>();
    sampleTable.add(fullBox("stts", 0, int32(1), int32(sampleCount), int32(sampleDuration)));
    sampleTable.add(sampleSizeBox());
    if (compositionOffsetCount > 0) {
      sampleTable.add(
          fullBox("ctts", 1, int32(1), int32(compositionOffsetCount), int32(compositionOffset)));
    }

    if (!compositionRuns.isEmpty()) {
      var entries = new ArrayList<byte[]>();
      entries.add(int32(compositionRuns.size()));
      compositionRuns.forEach(run -> entries.add(concat(int32(run.count()), int32(run.offset()))));
      sampleTable.add(fullBox("ctts", 1, entries.toArray(byte[][]::new)));
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
            timedHeader("mdhd", mediaTimescale),
            fullBox("hdlr", 0, int32(0), ascii(handler == null ? "vide" : handler), new byte[12]),
            box("minf", box("stbl", sampleTable.toArray(byte[][]::new))));
    var track = new ArrayList<byte[]>();
    track.add(
        version1Headers
            ? fullBox("tkhd", 1, int64(0), int64(0), int32((int) TRACK_ID))
            : fullBox("tkhd", 0, int32(0), int32(0), int32((int) TRACK_ID)));
    if (!edits.isEmpty()) {
      track.add(box("edts", editList()));
    }

    track.add(media);
    var movie = new ArrayList<byte[]>();
    movie.add(timedHeader("mvhd", 1_000));
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

  /** An mvhd or mdhd: creation and modification times, the timescale, then the duration. */
  private byte[] timedHeader(String type, long timescale) {
    if (version1Headers) {
      return fullBox(type, 1, int64(0), int64(0), int32((int) timescale), int64(0));
    }

    return fullBox(type, 0, int32(0), int32(0), int32((int) timescale), int32(0));
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

      entries.add(int32(edit.rate()));
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

  static byte[] concat(byte[]... parts) {
    var output = new ByteArrayOutputStream();
    for (var part : parts) {
      output.writeBytes(part);
    }

    return output.toByteArray();
  }
}
