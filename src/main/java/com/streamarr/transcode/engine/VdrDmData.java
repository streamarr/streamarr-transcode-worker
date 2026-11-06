package com.streamarr.transcode.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.NonNull;

/**
 * The display management metadata of a Dolby Vision RPU: its ids, the signal's description unless
 * the header compresses the metadata, the CM v2.9 extension blocks, and the CM v4.0 blocks when
 * enough bits follow the CM v2.9 ones for a block list ahead of the CRC32. Each block list is a
 * count, alignment to a byte, then each block's length in bytes, its level and its bytes.
 */
@Builder(toBuilder = true)
record VdrDmData(
    long affectedDmMetadataId,
    long currentDmMetadataId,
    long sceneRefreshFlag,
    @NonNull Optional<SignalDescription> signal,
    @NonNull List<ExtensionBlock> cmv29Blocks,
    @NonNull Optional<List<ExtensionBlock>> cmv40Blocks) {

  // The fewest bits that a CM v4.0 block list and the CRC32 with the final byte take together: a
  // level 254 block of two bytes, the CRC32 and the final byte.
  private static final int FEWEST_BITS_WITH_CMV40_BLOCKS = 56;
  // The pinned decoder reads each list's count as an Exp-Golomb code of at most 31 and keeps at
  // most 32 blocks, so a longer list never plays; the bound also keeps a list of empty blocks, nine
  // bits each, from allocating a block per nine bits of input.
  private static final long MOST_BLOCKS_IN_A_LIST = 31;
  private static final int MATRIX_COEFFICIENTS = 9;
  private static final int OFFSETS = 3;

  VdrDmData {
    cmv29Blocks = List.copyOf(cmv29Blocks);
    cmv40Blocks = cmv40Blocks.map(List::copyOf);
  }

  /** One extension metadata block: its level and its bytes, which the conversion never reads. */
  record ExtensionBlock(int level, byte @NonNull [] bytes) {

    @Override
    public boolean equals(Object other) {
      return other instanceof ExtensionBlock(var otherLevel, var otherBytes)
          && level == otherLevel
          && Arrays.equals(bytes, otherBytes);
    }

    @Override
    public int hashCode() {
      return 31 * level + Arrays.hashCode(bytes);
    }

    @Override
    public String toString() {
      return "ExtensionBlock[level=" + level + ", " + bytes.length + " bytes]";
    }
  }

  /**
   * The signal's description: the YCC to RGB and RGB to LMS matrices, which a profile decides, the
   * EOTF and its parameters, the bit depth, colour space, chroma format and range, and the source's
   * PQ range and diagonal.
   */
  @Builder(toBuilder = true)
  record SignalDescription(
      @NonNull List<Integer> yccToRgbCoefficients,
      @NonNull List<Long> yccToRgbOffsets,
      @NonNull List<Integer> rgbToLmsCoefficients,
      int eotf,
      int eotfParameter0,
      int eotfParameter1,
      long eotfParameter2,
      int bitDepth,
      int colorSpace,
      int chromaFormat,
      int fullRangeFlag,
      int sourceMinPq,
      int sourceMaxPq,
      int sourceDiagonal) {

    // The matrices of every profile 8.1 RPU, as libdovi writes them: BT.2020 YCC to RGB in 1/8192
    // with offsets in 1/2^28, and RGB to LMS in 1/16384.
    private static final List<Integer> PROFILE_81_YCC_TO_RGB =
        List.of(9574, 0, 13802, 9574, -1540, -5348, 9574, 17610, 0);
    private static final List<Long> PROFILE_81_YCC_TO_RGB_OFFSETS =
        List.of(16_777_216L, 134_217_728L, 134_217_728L);
    private static final List<Integer> PROFILE_81_RGB_TO_LMS =
        List.of(7222, 8771, 390, 2654, 12430, 1300, 0, 422, 15962);

    SignalDescription {
      yccToRgbCoefficients = List.copyOf(yccToRgbCoefficients);
      yccToRgbOffsets = List.copyOf(yccToRgbOffsets);
      rgbToLmsCoefficients = List.copyOf(rgbToLmsCoefficients);
    }

    private static SignalDescription parse(RpuBitReader reader) {
      var yccToRgb = signedCoefficients(reader);
      var offsets = new ArrayList<Long>();
      for (var offset = 0; offset < OFFSETS; offset++) {
        offsets.add(reader.bits(32));
      }

      return SignalDescription.builder()
          .yccToRgbCoefficients(yccToRgb)
          .yccToRgbOffsets(offsets)
          .rgbToLmsCoefficients(signedCoefficients(reader))
          .eotf((int) reader.bits(16))
          .eotfParameter0((int) reader.bits(16))
          .eotfParameter1((int) reader.bits(16))
          .eotfParameter2(reader.bits(32))
          .bitDepth((int) reader.bits(5))
          .colorSpace((int) reader.bits(2))
          .chromaFormat((int) reader.bits(2))
          .fullRangeFlag((int) reader.bits(2))
          .sourceMinPq((int) reader.bits(12))
          .sourceMaxPq((int) reader.bits(12))
          .sourceDiagonal((int) reader.bits(10))
          .build();
    }

    private static List<Integer> signedCoefficients(RpuBitReader reader) {
      var coefficients = new ArrayList<Integer>();
      for (var coefficient = 0; coefficient < MATRIX_COEFFICIENTS; coefficient++) {
        coefficients.add((int) (short) reader.bits(16));
      }

      return coefficients;
    }

    private void write(RpuBitWriter writer) {
      yccToRgbCoefficients.forEach(coefficient -> writer.bits(coefficient, 16));
      yccToRgbOffsets.forEach(offset -> writer.bits(offset, 32));
      rgbToLmsCoefficients.forEach(coefficient -> writer.bits(coefficient, 16));
      writer.bits(eotf, 16).bits(eotfParameter0, 16).bits(eotfParameter1, 16);
      writer.bits(eotfParameter2, 32);
      writer.bits(bitDepth, 5).bits(colorSpace, 2).bits(chromaFormat, 2).bits(fullRangeFlag, 2);
      writer.bits(sourceMinPq, 12).bits(sourceMaxPq, 12).bits(sourceDiagonal, 10);
    }

    /** This description with the matrices and colour space of a profile 8.1 RPU. */
    SignalDescription withProfile81Matrices() {
      return toBuilder()
          .yccToRgbCoefficients(PROFILE_81_YCC_TO_RGB)
          .yccToRgbOffsets(PROFILE_81_YCC_TO_RGB_OFFSETS)
          .rgbToLmsCoefficients(PROFILE_81_RGB_TO_LMS)
          .colorSpace(0)
          .build();
    }
  }

  static VdrDmData parse(RpuBitReader reader, RpuDataHeader header) {
    var dmData =
        VdrDmData.builder()
            .affectedDmMetadataId(reader.unsignedExpGolomb())
            .currentDmMetadataId(reader.unsignedExpGolomb())
            .sceneRefreshFlag(reader.unsignedExpGolomb());
    var signal = Optional.<SignalDescription>empty();
    if (!header.compressesDmMetadata()) {
      signal = Optional.of(SignalDescription.parse(reader));
    }

    dmData.signal(signal).cmv29Blocks(parseBlocks(reader));
    var cmv40Blocks = Optional.<List<ExtensionBlock>>empty();
    if (reader.bitsRemaining() >= FEWEST_BITS_WITH_CMV40_BLOCKS) {
      cmv40Blocks = Optional.of(parseBlocks(reader));
    }

    return dmData.cmv40Blocks(cmv40Blocks).build();
  }

  private static List<ExtensionBlock> parseBlocks(RpuBitReader reader) {
    var count = reader.unsignedExpGolomb();
    if (count > MOST_BLOCKS_IN_A_LIST) {
      throw new DolbyVisionRpuException(
          "an extension block list of "
              + count
              + " blocks where the decoder reads at most "
              + MOST_BLOCKS_IN_A_LIST);
    }

    reader.alignmentZeroBits();
    var blocks = new ArrayList<ExtensionBlock>();
    for (var block = 0; block < count; block++) {
      var length = reader.unsignedExpGolomb();
      var level = (int) reader.bits(8);
      blocks.add(new ExtensionBlock(level, reader.bytes(length)));
    }

    return blocks;
  }

  void write(RpuBitWriter writer) {
    writer
        .unsignedExpGolomb(affectedDmMetadataId)
        .unsignedExpGolomb(currentDmMetadataId)
        .unsignedExpGolomb(sceneRefreshFlag);
    signal.ifPresent(description -> description.write(writer));
    writeBlocks(writer, cmv29Blocks);
    cmv40Blocks.ifPresent(blocks -> writeBlocks(writer, blocks));
  }

  private static void writeBlocks(RpuBitWriter writer, List<ExtensionBlock> blocks) {
    writer.unsignedExpGolomb(blocks.size()).alignToByte();
    for (var block : blocks) {
      writer.unsignedExpGolomb(block.bytes().length).bits(block.level(), 8).bytes(block.bytes());
    }
  }

  /** This metadata with the matrices of a profile 8.1 RPU; compressed metadata holds none. */
  VdrDmData withProfile81Matrices() {
    return toBuilder().signal(signal.map(SignalDescription::withProfile81Matrices)).build();
  }
}
