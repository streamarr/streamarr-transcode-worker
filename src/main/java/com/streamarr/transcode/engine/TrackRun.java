package com.streamarr.transcode.engine;

import java.util.Optional;

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
    var header = new Header(fields.u8(), fields.u24(), fields.u32());
    fields.skipIf(header.has(DATA_OFFSET), 4).skipIf(header.has(FIRST_SAMPLE_FLAGS), 4);
    header.requireSampleTable(fields);
    return new TrackRun(trun, header);
  }

  /**
   * The first sample's flags (the run's first-sample flags, else its own) and composition offset;
   * empty when the run holds no sample.
   */
  Optional<FirstSample> firstSample() {
    if (header.sampleCount() == 0) {
      return Optional.empty();
    }

    var fields = box.fields().skip(HEADER_BYTES).skipIf(header.has(DATA_OFFSET), 4);
    var firstSampleFlags = fields.s32If(header.has(FIRST_SAMPLE_FLAGS));
    var sampleFlags =
        fields
            .skipIf(header.has(SAMPLE_DURATION), 4)
            .skipIf(header.has(SAMPLE_SIZE), 4)
            .s32If(header.has(SAMPLE_FLAGS));
    var compositionOffset = header.compositionOffsetOf(fields);
    return Optional.of(new FirstSample(firstSampleFlags.or(() -> sampleFlags), compositionOffset));
  }

  /** A run's first sample: its flags, when the run declares any, and its composition offset. */
  record FirstSample(Optional<Integer> flags, long compositionOffset) {}

  private record Header(int version, int flags, long sampleCount) {

    boolean has(int flag) {
      return (flags & flag) != 0;
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
