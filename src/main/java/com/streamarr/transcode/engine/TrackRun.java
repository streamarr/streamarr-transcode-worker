package com.streamarr.transcode.engine;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongSupplier;

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

  /**
   * The first sample's flags (the run's first-sample flags, else its own) and composition offset;
   * empty when the run holds no sample.
   */
  Optional<FirstSample> firstSample() {
    if (header.sampleCount() == 0) {
      return Optional.empty();
    }

    var fields =
        sampleTable().skipIf(header.has(SAMPLE_DURATION), 4).skipIf(header.has(SAMPLE_SIZE), 4);
    var sampleFlags = fields.s32If(header.has(SAMPLE_FLAGS));
    var compositionOffset = header.compositionOffsetOf(fields);
    return Optional.of(
        new FirstSample(firstSampleFlags().or(() -> sampleFlags), compositionOffset));
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

  /**
   * The shortest duration among the run's samples: each sample's own duration, else the default;
   * empty when the run holds no sample.
   */
  OptionalLong shortestSampleDuration(LongSupplier defaultSampleDuration) {
    if (header.sampleCount() == 0) {
      return OptionalLong.empty();
    }

    if (!header.has(SAMPLE_DURATION)) {
      return OptionalLong.of(defaultSampleDuration.getAsLong());
    }

    var fields = sampleTable();
    var afterDuration =
        4
            * Integer.bitCount(
                header.flags() & (SAMPLE_SIZE | SAMPLE_FLAGS | SAMPLE_COMPOSITION_TIME_OFFSET));
    var shortest = Long.MAX_VALUE;
    for (var sample = 0L; sample < header.sampleCount(); sample++) {
      shortest = Math.min(shortest, fields.u32());
      fields.skip(afterDuration);
    }

    return OptionalLong.of(shortest);
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

  /** A run's first sample: its flags, when the run declares any, and its composition offset. */
  record FirstSample(Optional<Integer> flags, long compositionOffset) {}

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
