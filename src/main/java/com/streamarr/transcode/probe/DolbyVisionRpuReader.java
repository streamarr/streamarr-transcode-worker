package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionEnhancementLayer;
import com.streamarr.transcode.probe.BitReader.TruncatedBitstreamException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Walks a whole Dolby Vision RPU in the order that FFmpeg 8.1's RPU parser reads it, for the two
 * facts that probe version 8 reports: the enhancement layer that the RPU's NLQ parameters describe,
 * and the MaxCLL and MaxFALL of its level 6 block. The walk reads the bytes after the RPU's NAL
 * unit header and without emulation prevention, or the bytes that an AV1 stream's EMDF container
 * wraps.
 */
final class DolbyVisionRpuReader {

  private static final int RPU_NAL_PREFIX = 25;
  private static final int RPU_TYPE = 2;
  private static final int FORMAT_WITHOUT_BIT_DEPTHS = 0x700;
  private static final int FIXED_POINT_COEFFICIENTS = 0;
  private static final int FLOATING_POINT_COEFFICIENTS = 1;
  private static final int SMALLEST_LOG2_DENOMINATOR = 13;
  private static final int LARGEST_LOG2_DENOMINATOR = 32;
  private static final int FLOAT_BITS = 32;
  private static final int LARGEST_BIT_DEPTH_MINUS_8 = 8;
  // el_bit_depth_minus8 holds the 8-bit ext_mapping_idc above its low 8 bits.
  private static final int EL_BIT_DEPTH_BITS = 0xFF;
  private static final int LARGEST_EXT_MAPPING_IDC = 0xFF;
  private static final int LARGEST_METADATA_ID = 15;
  private static final int LARGEST_PIVOTS_MINUS_2 = 7;
  private static final int COMPONENTS = 3;
  private static final int POLYNOMIAL = 0;
  private static final int MMR = 1;
  private static final int LARGEST_POLYNOMIAL_ORDER_MINUS_1 = 1;
  private static final int LARGEST_MMR_ORDER_MINUS_1 = 2;
  private static final int MMR_COEFFICIENTS_PER_ORDER = 7;
  private static final int LINEAR_DEADZONE = 0;
  // The color metadata that an uncompressed DM payload carries ahead of its extension blocks: the
  // two 3x3 matrices of 16-bit values, three 32-bit offsets, the signal's EOTF and its parameters,
  // its 5-bit bit depth, which FFmpeg accepts from 8 to 16, then its color space, chroma format and
  // range, and the source's PQ range and diagonal.
  private static final int BITS_BEFORE_SIGNAL_BIT_DEPTH =
      9 * 16 + 3 * 32 + 9 * 16 + 16 + 16 + 16 + 32;
  private static final int BITS_AFTER_SIGNAL_BIT_DEPTH = 2 + 2 + 2 + 12 + 12 + 10;
  private static final int SMALLEST_SIGNAL_BIT_DEPTH = 8;
  private static final int LARGEST_SIGNAL_BIT_DEPTH = 16;
  // A level 2 block's six 12-bit trims ahead of its 13-bit signed ms_weight, which FFmpeg accepts
  // from -1 to 4095: every 13-bit pattern but those of -4096 to -2.
  private static final int LEVEL_2 = 2;
  private static final int LEVEL_2_TRIM_BITS = 6 * 12;
  private static final int MS_WEIGHT_BITS = 13;
  private static final long SMALLEST_NEGATIVE_MS_WEIGHT = 1L << 12;
  private static final long MS_WEIGHT_OF_MINUS_1 = (1L << 13) - 1;
  private static final int LEVEL_6 = 6;
  private static final int LEVEL_6_BYTES = 8;
  // Up to 7 bits of alignment, the 32-bit CRC and the 8-bit terminator.
  private static final int TRAILER_BITS = 48;
  private static final int TERMINATOR = 0x80;

  // The payload bytes that FFmpeg 8.1 reads from each extension block level that it knows, in the
  // first group and in the second. A shorter block makes FFmpeg reject the RPU.
  private static final Map<Long, Long> FIRST_GROUP_BLOCK_BYTES =
      Map.of(1L, 5L, 2L, 11L, 4L, 3L, 5L, 7L, 6L, 8L, 255L, 6L);
  private static final Map<Long, Long> SECOND_GROUP_BLOCK_BYTES =
      Map.of(3L, 5L, 8L, 10L, 9L, 1L, 10L, 5L, 11L, 4L, 254L, 2L);

  private DolbyVisionRpuReader() {}

  /**
   * What the RPU says; empty when the bytes are no type 2 RPU or the walk cannot reach its end
   * through syntax that FFmpeg 8.1 parses.
   */
  static Optional<RpuFacts> read(byte[] rpu) {
    try {
      var reader = new BitReader(withoutTrailingZeros(rpu));
      return reader.bits(8) == RPU_NAL_PREFIX ? facts(reader) : Optional.empty();
    } catch (TruncatedBitstreamException | MalformedRpuException _) {
      return Optional.empty();
    }
  }

  /** What the RPU says when an AV1 stream's EMDF container holds it, without rpu_nal_prefix. */
  static Optional<RpuFacts> readWithoutPrefix(byte[] rpu) {
    try {
      return facts(new BitReader(rpu));
    } catch (TruncatedBitstreamException | MalformedRpuException _) {
      return Optional.empty();
    }
  }

  // FFmpeg drops the zero bytes that can pad a NAL unit before it parses the RPU, whose terminator
  // is never zero.
  private static byte[] withoutTrailingZeros(byte[] rpu) {
    var end = rpu.length;
    while (end > 0 && rpu[end - 1] == 0) {
      end--;
    }

    return Arrays.copyOf(rpu, end);
  }

  private static Optional<RpuFacts> facts(BitReader reader) {
    var header = Header.read(reader);
    if (header.isEmpty()) {
      return Optional.empty();
    }

    var hasDisplayManagement = reader.flag();
    var compression = header.get().displayManagementCompression();
    if (compression > 1 || (compression == 1 && !hasDisplayManagement)) {
      return Optional.empty();
    }

    var layer = ownMappingLayer(reader, header.get());
    if (layer.isEmpty()) {
      return Optional.empty();
    }

    var level6 = Optional.of(Optional.<ContentLightLevel>empty());
    if (hasDisplayManagement) {
      level6 = displayManagementLevel6(reader, header.get());
    }

    if (level6.isEmpty() || !endsWithTerminator(reader)) {
      return Optional.empty();
    }

    return Optional.of(new RpuFacts(layer.get(), level6.get()));
  }

  /**
   * The level 6 block of the DM payload, after which FFmpeg reads a second group of extension
   * blocks when more than the padding, the CRC and the terminator remain.
   */
  private static Optional<Optional<ContentLightLevel>> displayManagementLevel6(
      BitReader reader, Header header) {
    var counts = new BlockCounts(header.displayManagementCompression() != 0);
    var level6 = level6(reader, header, counts);
    if (level6.isPresent() && reader.bitsLeft() > TRAILER_BITS) {
      skipExtensionBlocks(reader, counts);
    }

    return level6;
  }

  private static void skipExtensionBlocks(BitReader reader, BlockCounts counts) {
    var blocks = reader.unsignedExpGolomb();
    reader.alignToByte();
    for (long block = 0; block < blocks; block++) {
      var length = reader.unsignedExpGolomb();
      var level = reader.bits(8);
      counts.add(level);
      requireLength(SECOND_GROUP_BLOCK_BYTES, level, length);
      reader.skip(length * 8);
    }
  }

  private static void requireLength(Map<Long, Long> blockBytes, long level, long length) {
    if (length < blockBytes.getOrDefault(level, 0L)) {
      throw new MalformedRpuException();
    }
  }

  /** The 32-bit CRC after the byte alignment, then the 0x80 terminator that FFmpeg requires. */
  private static boolean endsWithTerminator(BitReader reader) {
    reader.alignToByte();
    reader.skip(32);
    return reader.bits(8) == TERMINATOR;
  }

  /**
   * The layer of the RPU's own mapping; empty when the mapping breaks the syntax or the RPU refers
   * to an earlier RPU's mapping. FFmpeg accepts such an RPU only when the decoder still holds that
   * mapping, which the walk, reading each RPU alone, cannot tell.
   */
  private static Optional<DolbyVisionEnhancementLayer> ownMappingLayer(
      BitReader reader, Header header) {
    if (reader.flag()) {
      return Optional.empty();
    }

    return mappingLayer(reader, header);
  }

  /**
   * The layer that the RPU's own mapping describes; empty when the mapping uses syntax that FFmpeg
   * rejects or that the walk does not cover.
   */
  private static Optional<DolbyVisionEnhancementLayer> mappingLayer(
      BitReader reader, Header header) {
    if (reader.unsignedExpGolomb() > LARGEST_METADATA_ID) {
      return Optional.empty();
    }

    reader.unsignedExpGolomb();
    reader.unsignedExpGolomb();
    var pivots = new int[COMPONENTS];
    for (var component = 0; component < COMPONENTS; component++) {
      var pivotsMinus2 = reader.unsignedExpGolomb();
      if (pivotsMinus2 > LARGEST_PIVOTS_MINUS_2) {
        return Optional.empty();
      }

      pivots[component] = (int) pivotsMinus2 + 2;
      reader.bits(pivots[component] * header.baseLayerBitDepth());
    }

    var usesNlq = !header.disableResidual();
    if (usesNlq && !readNlqMethod(reader, header)) {
      return Optional.empty();
    }

    // FFmpeg reads the partition counts and then one set of curves, whatever the counts say.
    reader.unsignedExpGolomb();
    reader.unsignedExpGolomb();

    for (var component = 0; component < COMPONENTS; component++) {
      for (var piece = 0; piece < pivots[component] - 1; piece++) {
        if (!skipPiece(reader, header)) {
          return Optional.empty();
        }
      }
    }

    if (!usesNlq) {
      return Optional.of(DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_NONE);
    }

    var isIdentity = true;
    for (var component = 0; component < COMPONENTS; component++) {
      isIdentity &= isIdentityNlq(reader, header);
    }

    return Optional.of(
        isIdentity
            ? DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_MEL
            : DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_FEL);
  }

  // FFmpeg parses only the linear deadzone method.
  private static boolean readNlqMethod(BitReader reader, Header header) {
    var method = reader.bits(3);
    reader.bits(2 * header.baseLayerBitDepth());
    return method == LINEAR_DEADZONE;
  }

  /** Skips one piece of a reshaping curve; false for syntax that FFmpeg rejects. */
  private static boolean skipPiece(BitReader reader, Header header) {
    var mapping = reader.unsignedExpGolomb();
    if (mapping == POLYNOMIAL) {
      return skipPolynomial(reader, header);
    }

    return mapping == MMR && skipMmr(reader, header);
  }

  private static boolean skipPolynomial(BitReader reader, Header header) {
    var orderMinus1 = reader.unsignedExpGolomb();
    if (orderMinus1 > LARGEST_POLYNOMIAL_ORDER_MINUS_1) {
      return false;
    }

    // FFmpeg rejects linear interpolation, which only a first-order polynomial can ask for.
    if (orderMinus1 == 0 && reader.flag()) {
      return false;
    }

    skipSignedCoefficients(reader, header, orderMinus1 + 2);
    return true;
  }

  private static boolean skipMmr(BitReader reader, Header header) {
    var orderMinus1 = reader.bits(2);
    if (orderMinus1 > LARGEST_MMR_ORDER_MINUS_1) {
      return false;
    }

    skipSignedCoefficients(reader, header, 1 + (orderMinus1 + 1) * MMR_COEFFICIENTS_PER_ORDER);
    return true;
  }

  private static void skipSignedCoefficients(BitReader reader, Header header, long count) {
    for (long coefficient = 0; coefficient < count; coefficient++) {
      skipSignedCoefficient(reader, header);
    }
  }

  /**
   * Whether one component's NLQ parameters are the identity, as libdovi's minimal enhancement layer
   * rule states it: a zero offset, a vdr_in_max of exactly 1, and a zero deadzone slope and
   * threshold. Floating-point coefficients carry no integer parts, so they are never the identity.
   */
  private static boolean isIdentityNlq(BitReader reader, Header header) {
    var offset = reader.bits(header.enhancementLayerBitDepth());
    var vdrInMax = unsignedCoefficient(reader, header);
    var slope = unsignedCoefficient(reader, header);
    var threshold = unsignedCoefficient(reader, header);
    return header.coefficientDataType() == FIXED_POINT_COEFFICIENTS
        && offset == 0
        && vdrInMax.equals(new Coefficient(1, 0))
        && slope.equals(new Coefficient(0, 0))
        && threshold.equals(new Coefficient(0, 0));
  }

  private static Coefficient unsignedCoefficient(BitReader reader, Header header) {
    if (header.coefficientDataType() == FLOATING_POINT_COEFFICIENTS) {
      return new Coefficient(0, reader.bits(FLOAT_BITS));
    }

    var integer = reader.unsignedExpGolomb();
    return new Coefficient(integer, reader.bits(header.coefficientLog2Denominator()));
  }

  private static void skipSignedCoefficient(BitReader reader, Header header) {
    if (header.coefficientDataType() == FLOATING_POINT_COEFFICIENTS) {
      reader.bits(FLOAT_BITS);
      return;
    }

    reader.unsignedExpGolomb();
    reader.bits(header.coefficientLog2Denominator());
  }

  /**
   * The level 6 block among the first group of extension blocks of the DM payload: present or empty
   * inside the optional, and an empty outer optional when the payload's syntax breaks.
   */
  private static Optional<Optional<ContentLightLevel>> level6(
      BitReader reader, Header header, BlockCounts counts) {
    var affectedId = reader.unsignedExpGolomb();
    var currentId = reader.unsignedExpGolomb();
    if (affectedId > LARGEST_METADATA_ID || affectedId != currentId) {
      return Optional.empty();
    }

    reader.unsignedExpGolomb();
    if (header.displayManagementCompression() == 0) {
      requireValidColorMetadata(reader);
    }

    var blocks = reader.unsignedExpGolomb();
    reader.alignToByte();
    var level6 = Optional.<ContentLightLevel>empty();
    for (long block = 0; block < blocks; block++) {
      var length = reader.unsignedExpGolomb();
      var level = reader.bits(8);
      counts.add(level);
      requireLength(FIRST_GROUP_BLOCK_BYTES, level, length);
      if (level == LEVEL_2) {
        requireValidMsWeight(reader);
        reader.skip(length * 8 - LEVEL_2_TRIM_BITS - MS_WEIGHT_BITS);
        continue;
      }

      // FFmpeg parses a static block, such as level 6, of a compressed DM payload but ignores it.
      var isReadable = header.displayManagementCompression() == 0;
      if (level == LEVEL_6 && isReadable && level6.isEmpty()) {
        reader.bits(16);
        reader.bits(16);
        level6 =
            Optional.of(
                ContentLightLevel.newBuilder()
                    .setMaxContentLightLevel((int) reader.bits(16))
                    .setMaxFrameAverageLightLevel((int) reader.bits(16))
                    .build());
        reader.skip((length - LEVEL_6_BYTES) * 8);
        continue;
      }

      reader.skip(length * 8);
    }

    return Optional.of(level6);
  }

  private static void requireValidColorMetadata(BitReader reader) {
    reader.skip(BITS_BEFORE_SIGNAL_BIT_DEPTH);
    var signalBitDepth = reader.bits(5);
    if (signalBitDepth < SMALLEST_SIGNAL_BIT_DEPTH || signalBitDepth > LARGEST_SIGNAL_BIT_DEPTH) {
      throw new MalformedRpuException();
    }

    reader.skip(BITS_AFTER_SIGNAL_BIT_DEPTH);
  }

  // FFmpeg validates ms_weight in a compressed DM payload too, where it ignores the block.
  private static void requireValidMsWeight(BitReader reader) {
    reader.skip(LEVEL_2_TRIM_BITS);
    var msWeight = reader.bits(MS_WEIGHT_BITS);
    if (msWeight >= SMALLEST_NEGATIVE_MS_WEIGHT && msWeight != MS_WEIGHT_OF_MINUS_1) {
      throw new MalformedRpuException();
    }
  }

  /**
   * The extension blocks of one RPU's two groups, which FFmpeg holds in arrays of 7 static and 25
   * dynamic blocks. A compressed DM payload holds no static block there, as FFmpeg ignores them.
   */
  private static final class BlockCounts {
    private static final Set<Long> STATIC_LEVELS = Set.of(6L, 10L, 32L, 254L, 255L);
    private static final int LARGEST_STATIC_BLOCKS = 7;
    private static final int LARGEST_DYNAMIC_BLOCKS = 25;

    private final boolean isCompressed;
    private int staticBlocks;
    private int dynamicBlocks;

    BlockCounts(boolean isCompressed) {
      this.isCompressed = isCompressed;
    }

    void add(long level) {
      if (!STATIC_LEVELS.contains(level)) {
        dynamicBlocks++;
        requireAtMost(dynamicBlocks, LARGEST_DYNAMIC_BLOCKS);
        return;
      }

      if (!isCompressed) {
        staticBlocks++;
        requireAtMost(staticBlocks, LARGEST_STATIC_BLOCKS);
      }
    }

    private static void requireAtMost(int blocks, int largest) {
      if (blocks > largest) {
        throw new MalformedRpuException();
      }
    }
  }

  /**
   * A DM payload that FFmpeg rejects: more extension blocks than it holds, a block shorter than its
   * level's payload, or a value out of FFmpeg's range.
   */
  private static final class MalformedRpuException extends RuntimeException {
    MalformedRpuException() {
      super(null, null, false, false);
    }
  }

  /**
   * The enhancement layer that one RPU's NLQ parameters describe, UNSPECIFIED when it refers to an
   * earlier RPU's mapping, and the MaxCLL and MaxFALL of its level 6 block.
   */
  record RpuFacts(
      DolbyVisionEnhancementLayer enhancementLayer, Optional<ContentLightLevel> level6) {}

  private record Coefficient(long integer, long fraction) {}

  private record Header(
      int coefficientDataType,
      int coefficientLog2Denominator,
      int baseLayerBitDepth,
      int enhancementLayerBitDepth,
      int displayManagementCompression,
      boolean disableResidual) {

    /** The header's fields that the rest of the RPU depends on, as FFmpeg 8.1 validates them. */
    static Optional<Header> read(BitReader reader) {
      if (reader.bits(6) != RPU_TYPE) {
        return Optional.empty();
      }

      var format = (int) reader.bits(11);
      var vdrRpuProfile = reader.bits(4);
      reader.bits(4);
      if (!reader.flag()) {
        return Optional.empty();
      }

      reader.bits(1);
      var coefficientDataType = (int) reader.bits(2);
      var log2Denominator = log2Denominator(reader, coefficientDataType);
      if (log2Denominator.isEmpty()) {
        return Optional.empty();
      }

      reader.bits(2);
      var isBaseLayerFullRange = reader.flag();
      if ((format & FORMAT_WITHOUT_BIT_DEPTHS) != 0) {
        return Optional.empty();
      }

      var baseLayerBitDepthMinus8 = reader.unsignedExpGolomb();
      var enhancementLayerField = reader.unsignedExpGolomb();
      var enhancementLayerBitDepthMinus8 = enhancementLayerField & EL_BIT_DEPTH_BITS;
      var vdrBitDepthMinus8 = reader.unsignedExpGolomb();
      if ((enhancementLayerField >> 8) > LARGEST_EXT_MAPPING_IDC
          || baseLayerBitDepthMinus8 > LARGEST_BIT_DEPTH_MINUS_8
          || enhancementLayerBitDepthMinus8 > LARGEST_BIT_DEPTH_MINUS_8
          || vdrBitDepthMinus8 > LARGEST_BIT_DEPTH_MINUS_8) {
        return Optional.empty();
      }

      reader.bits(1);
      var compression = (int) reader.bits(3);
      reader.bits(1);
      var disableResidual = reader.flag();
      // FFmpeg rejects NLQ in a profile 5 RPU. The walk sees no configuration record, so it takes
      // the profile as FFmpeg guesses it without one: vdr_rpu_profile 0 with a full-range base.
      if (vdrRpuProfile == 0 && isBaseLayerFullRange && !disableResidual) {
        return Optional.empty();
      }

      return Optional.of(
          new Header(
              coefficientDataType,
              log2Denominator.getAsInt(),
              (int) baseLayerBitDepthMinus8 + 8,
              (int) enhancementLayerBitDepthMinus8 + 8,
              compression,
              disableResidual));
    }

    /**
     * The coefficient_log2_denom of fixed-point coefficients, which FFmpeg accepts from 13 to 32;
     * 32 for floating-point ones, which carry no denominator; empty for another data type.
     */
    private static OptionalInt log2Denominator(BitReader reader, int coefficientDataType) {
      if (coefficientDataType == FLOATING_POINT_COEFFICIENTS) {
        return OptionalInt.of(LARGEST_LOG2_DENOMINATOR);
      }

      if (coefficientDataType != FIXED_POINT_COEFFICIENTS) {
        return OptionalInt.empty();
      }

      var denominator = reader.unsignedExpGolomb();
      if (denominator < SMALLEST_LOG2_DENOMINATOR || denominator > LARGEST_LOG2_DENOMINATOR) {
        return OptionalInt.empty();
      }

      return OptionalInt.of((int) denominator);
    }
  }
}
