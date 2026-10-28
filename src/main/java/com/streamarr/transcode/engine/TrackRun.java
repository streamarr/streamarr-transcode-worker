package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import lombok.Builder;

/**
 * A {@code trun}'s header and its sample table, which {@link #of} checks fits inside the box before
 * anything reads an entry.
 */
final class TrackRun {

  private static final int DATA_OFFSET = 0x000001;
  private static final int FIRST_SAMPLE_FLAGS = 0x000004;
  private static final int SAMPLE_DURATION = 0x000100;
  private static final int SAMPLE_SIZE = 0x000200;
  private static final int SAMPLE_FLAGS = 0x000400;
  private static final int SAMPLE_COMPOSITION_TIME_OFFSET = 0x000800;
  private static final int PER_SAMPLE_FIELDS =
      SAMPLE_DURATION | SAMPLE_SIZE | SAMPLE_FLAGS | SAMPLE_COMPOSITION_TIME_OFFSET;
  private static final int HEADER_BYTES = 8;
  private static final int BOX_HEADER_BYTES = 8;
  private static final byte[] TRUN_TYPE = "trun".getBytes(StandardCharsets.ISO_8859_1);

  private final BoxView box;
  private final Header header;

  private TrackRun(BoxView box, Header header) {
    this.box = box;
    this.header = header;
  }

  static TrackRun of(BoxView trun) {
    var fields = trun.fields();
    var run = new TrackRun(trun, new Header(fields.u8(), fields.u24(), fields.u32()));
    run.header.requireSampleTable(run.sampleTable());
    return run;
  }

  /** The run's data offset from its {@code traf}'s base, when the run declares one. */
  Optional<Integer> dataOffset() {
    return optionalFields().s32If(header.has(DATA_OFFSET));
  }

  /**
   * The bytes the run's samples occupy: each sample's own size, else the default size.
   *
   * @throws ArithmeticException when the sizes add up beyond a signed 64-bit count
   */
  long sampleBytes(LongSupplier defaultSampleSize) {
    if (header.sampleCount() == 0) {
      return 0;
    }

    if (!header.has(SAMPLE_SIZE)) {
      return Math.multiplyExact(header.sampleCount(), defaultSampleSize.getAsLong());
    }

    var fields = sampleTable();
    var afterSize =
        4 * Integer.bitCount(header.flags() & (SAMPLE_FLAGS | SAMPLE_COMPOSITION_TIME_OFFSET));
    var bytes = 0L;
    for (var sample = 0L; sample < header.sampleCount(); sample++) {
      bytes = Math.addExact(bytes, fields.skipIf(header.has(SAMPLE_DURATION), 4).u32());
      fields.skip(afterSize);
    }

    return bytes;
  }

  long sampleCount() {
    return header.sampleCount();
  }

  /**
   * Each sample's duration, size, flags and composition offset in order, with the defaults for the
   * fields the run leaves out; a default is asked for only when a sample needs it.
   */
  List<Entry> entries(Defaults defaults) {
    var entries = new ArrayList<Entry>(Math.toIntExact(header.sampleCount()));
    var fields = sampleTable();
    var firstSampleFlags = firstSampleFlags();
    for (var sample = 0L; sample < header.sampleCount(); sample++) {
      var duration = fields.u32If(header.has(SAMPLE_DURATION)).orElseGet(defaults.duration());
      var size = fields.u32If(header.has(SAMPLE_SIZE)).orElseGet(defaults.size());
      var ownFlags = fields.s32If(header.has(SAMPLE_FLAGS));
      var compositionOffset = header.compositionOffsetOf(fields);
      var flags = ownFlags;
      if (sample == 0) {
        flags = firstSampleFlags.or(() -> ownFlags);
      }

      entries.add(
          Entry.builder()
              .duration(duration)
              .size(size)
              .flags(flags.orElseGet(() -> defaults.flags().getAsInt()))
              .compositionOffset(compositionOffset)
              .build());
    }

    return entries;
  }

  /**
   * This run as a whole {@code trun} box whose samples start at the data offset and take the given
   * sizes in order; its version, its first-sample flags and each sample's duration, flags and
   * composition offset stay as read. The box declares a data offset and every sample's size,
   * whatever the run declared.
   *
   * @param sampleSizes one size for each of the run's samples
   */
  byte[] rewritten(int dataOffset, long[] sampleSizes) {
    var flags = header.flags() | DATA_OFFSET | SAMPLE_SIZE;
    var hasFirstSampleFlags = header.has(FIRST_SAMPLE_FLAGS);
    var entryBytes = 4 * Integer.bitCount(flags & PER_SAMPLE_FIELDS);
    var boxBytes =
        BOX_HEADER_BYTES
            + HEADER_BYTES
            + 4
            + (hasFirstSampleFlags ? 4 : 0)
            + Math.multiplyExact(entryBytes, sampleSizes.length);
    var trun = ByteBuffer.allocate(boxBytes);
    trun.putInt(boxBytes).put(TRUN_TYPE).putInt(header.version() << 24 | flags);
    trun.putInt(sampleSizes.length).putInt(dataOffset);
    firstSampleFlags().ifPresent(trun::putInt);
    var fields = sampleTable();
    for (var size : sampleSizes) {
      copyIf(header.has(SAMPLE_DURATION), fields, trun);
      fields.skipIf(header.has(SAMPLE_SIZE), 4);
      trun.putInt((int) size);
      copyIf(header.has(SAMPLE_FLAGS), fields, trun);
      copyIf(header.has(SAMPLE_COMPOSITION_TIME_OFFSET), fields, trun);
    }

    return trun.array();
  }

  private static void copyIf(boolean present, BoxFields fields, ByteBuffer trun) {
    if (present) {
      trun.putInt(fields.s32());
    }
  }

  private Optional<Integer> firstSampleFlags() {
    return optionalFields()
        .skipIf(header.has(DATA_OFFSET), 4)
        .s32If(header.has(FIRST_SAMPLE_FLAGS));
  }

  /** The fields after the sample count: the optional data offset and first-sample flags. */
  private BoxFields optionalFields() {
    return box.fields().skip(HEADER_BYTES);
  }

  /** The fields from the first sample's entry on. */
  private BoxFields sampleTable() {
    return optionalFields()
        .skipIf(header.has(DATA_OFFSET), 4)
        .skipIf(header.has(FIRST_SAMPLE_FLAGS), 4);
  }

  /** The defaults a {@code traf} or its track declares for the fields a run leaves out. */
  record Defaults(LongSupplier duration, LongSupplier size, IntSupplier flags) {}

  /** One sample's fields as the run and the defaults give them. */
  @Builder
  record Entry(long duration, long size, int flags, long compositionOffset) {}

  private record Header(int version, int flags, long sampleCount) {

    boolean has(int flag) {
      return BoxFields.isSet(flags, flag);
    }

    /** Every declared sample's entry must fit in the box. */
    void requireSampleTable(BoxFields fields) {
      var entryBytes = 4L * Integer.bitCount(flags & PER_SAMPLE_FIELDS);
      var tableBytes = sampleCount * entryBytes;
      if (tableBytes > fields.remaining()) {
        throw FragmentedMp4Exception.malformed(
            "trun declares "
                + sampleCount
                + " samples of "
                + entryBytes
                + " bytes in "
                + fields.remaining()
                + " bytes");
      }
    }

    long compositionOffsetOf(BoxFields fields) {
      if (!has(SAMPLE_COMPOSITION_TIME_OFFSET)) {
        return 0;
      }

      if (version == 0) {
        return fields.u32();
      }

      return fields.s32();
    }
  }
}
