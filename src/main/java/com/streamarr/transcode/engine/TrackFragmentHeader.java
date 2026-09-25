package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.BoxFields.isSet;

import java.util.Optional;
import java.util.OptionalLong;
import lombok.Builder;

/**
 * A {@code tfhd}'s track, where its {@code traf}'s sample data is based, and the sample defaults it
 * declares for that {@code traf}.
 *
 * @param baseDataOffset the absolute stream position the {@code traf}'s data offsets count from,
 *     when the {@code tfhd} declares one: an unsigned 64-bit value held in a {@code long}'s bits
 * @param defaultBaseIsMoof whether the data offsets count from the {@code moof}'s first byte
 */
@Builder
record TrackFragmentHeader(
    long trackId,
    OptionalLong baseDataOffset,
    boolean defaultBaseIsMoof,
    OptionalLong defaultSampleDuration,
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
    header.baseDataOffset(fields.u64If(isSet(flags, BASE_DATA_OFFSET)));
    fields.skipIf(isSet(flags, SAMPLE_DESCRIPTION_INDEX), 4);
    header.defaultSampleDuration(fields.u32If(isSet(flags, DEFAULT_SAMPLE_DURATION)));
    header.defaultSampleSize(fields.u32If(isSet(flags, DEFAULT_SAMPLE_SIZE)));
    return header.defaultSampleFlags(fields.s32If(isSet(flags, DEFAULT_SAMPLE_FLAGS))).build();
  }
}
