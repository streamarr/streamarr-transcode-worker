package com.streamarr.transcode.engine;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.LongSupplier;
import lombok.Builder;

/**
 * A {@code tfhd}'s track, where its {@code traf}'s sample data is based, and the sample defaults it
 * declares for that {@code traf}.
 *
 * @param baseDataOffset the absolute stream position the {@code traf}'s data offsets count from,
 *     when the {@code tfhd} declares one
 * @param defaultBaseIsMoof whether the data offsets count from the {@code moof}'s first byte
 */
@Builder
record TrackFragmentHeader(
    long trackId,
    OptionalLong baseDataOffset,
    boolean defaultBaseIsMoof,
    OptionalLong defaultSampleSize,
    Optional<Integer> defaultSampleFlags) {

  private static final int BASE_DATA_OFFSET = 0x000001;
  private static final int SAMPLE_DESCRIPTION_INDEX = 0x000002;
  private static final int DEFAULT_SAMPLE_DURATION = 0x000008;
  private static final int DEFAULT_SAMPLE_SIZE = 0x000010;
  private static final int DEFAULT_SAMPLE_FLAGS = 0x000020;
  private static final int DEFAULT_BASE_IS_MOOF = 0x020000;

  static TrackFragmentHeader of(BoxView tfhd) {
    var fields = tfhd.fields().skip(1);
    var flags = fields.u24();
    var header =
        builder().trackId(fields.u32()).defaultBaseIsMoof(isSet(flags, DEFAULT_BASE_IS_MOOF));
    header.baseDataOffset(optionalLong(isSet(flags, BASE_DATA_OFFSET), fields::s64));
    fields
        .skipIf(isSet(flags, SAMPLE_DESCRIPTION_INDEX), 4)
        .skipIf(isSet(flags, DEFAULT_SAMPLE_DURATION), 4);
    header.defaultSampleSize(optionalLong(isSet(flags, DEFAULT_SAMPLE_SIZE), fields::u32));
    return header.defaultSampleFlags(fields.s32If(isSet(flags, DEFAULT_SAMPLE_FLAGS))).build();
  }

  private static OptionalLong optionalLong(boolean present, LongSupplier field) {
    if (present) {
      return OptionalLong.of(field.getAsLong());
    }

    return OptionalLong.empty();
  }

  private static boolean isSet(int flags, int flag) {
    return (flags & flag) != 0;
  }
}
