package com.streamarr.transcode.engine;

import java.util.Optional;

/** A {@code tfhd}'s track and the default sample flags it declares for its {@code traf}. */
record TrackFragmentHeader(long trackId, Optional<Integer> defaultSampleFlags) {

  private static final int BASE_DATA_OFFSET = 0x000001;
  private static final int SAMPLE_DESCRIPTION_INDEX = 0x000002;
  private static final int DEFAULT_SAMPLE_DURATION = 0x000008;
  private static final int DEFAULT_SAMPLE_SIZE = 0x000010;
  private static final int DEFAULT_SAMPLE_FLAGS = 0x000020;

  static TrackFragmentHeader of(BoxView tfhd) {
    var fields = tfhd.fields().skip(1);
    var flags = fields.u24();
    var trackId = fields.u32();
    var defaultSampleFlags =
        fields
            .skipIf(isSet(flags, BASE_DATA_OFFSET), 8)
            .skipIf(isSet(flags, SAMPLE_DESCRIPTION_INDEX), 4)
            .skipIf(isSet(flags, DEFAULT_SAMPLE_DURATION), 4)
            .skipIf(isSet(flags, DEFAULT_SAMPLE_SIZE), 4)
            .s32If(isSet(flags, DEFAULT_SAMPLE_FLAGS));
    return new TrackFragmentHeader(trackId, defaultSampleFlags);
  }

  private static boolean isSet(int flags, int flag) {
    return (flags & flag) != 0;
  }
}
