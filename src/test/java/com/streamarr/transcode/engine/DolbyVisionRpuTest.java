package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.engine.DolbyVisionRpu.EnhancementLayer;
import com.streamarr.transcode.engine.RpuReshapingCurve.Polynomial;
import com.streamarr.transcode.engine.SyntheticRpus.Nlq;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The RPU parser and writer port the subset of the Dolby Vision RPU syntax that converting a
 * profile 7 RPU to profile 8.1 needs. The synthetic RPUs follow the field layout that libdovi
 * reads, and the reference bytes come from dovi_tool 2.3.4's mode 2 on the same synthetic RPU.
 */
@Tag("UnitTest")
@DisplayName("Dolby Vision RPU Tests")
class DolbyVisionRpuTest {

  private static final HexFormat HEX = HexFormat.of().withUpperCase();
  // The DM colour coefficients that every profile 8.1 RPU carries, as libdovi writes them.
  private static final List<Integer> PROFILE_81_YCC_TO_RGB =
      List.of(9574, 0, 13802, 9574, -1540, -5348, 9574, 17610, 0);
  private static final List<Long> PROFILE_81_YCC_TO_RGB_OFFSETS =
      List.of(16_777_216L, 134_217_728L, 134_217_728L);
  private static final List<Integer> PROFILE_81_RGB_TO_LMS =
      List.of(7222, 8771, 390, 2654, 12430, 1300, 0, 422, 15962);

  @ParameterizedTest(name = "{0}")
  @MethodSource("enhancementLayers")
  @DisplayName(
      "Should classify the enhancement layer as minimal only when every component's NLQ is the"
          + " identity")
  void shouldClassifyTheEnhancementLayerAsMinimalOnlyWhenEveryComponentsNlqIsTheIdentity(
      String name, SyntheticRpus.Builder source, Optional<EnhancementLayer> expected) {
    var rpu = DolbyVisionRpu.parse(ByteBuffer.wrap(source.escaped()));

    assertThat(rpu.enhancementLayer()).isEqualTo(expected);
  }

  static Stream<Arguments> enhancementLayers() {
    var minimal = Nlq.minimal();
    return Stream.of(
        Arguments.of(
            "identity NLQ on every component",
            SyntheticRpus.profile7().nlq(minimal),
            Optional.of(EnhancementLayer.MEL)),
        Arguments.of(
            "the public FEL sample's NLQ",
            SyntheticRpus.profile7(),
            Optional.of(EnhancementLayer.FEL)),
        Arguments.of(
            "an offset on one chroma component",
            SyntheticRpus.profile7().nlq(minimal, minimal, new Nlq(1, 1, 0, 0, 0, 0, 0)),
            Optional.of(EnhancementLayer.FEL)),
        Arguments.of(
            "a clamp below 1.0",
            SyntheticRpus.profile7().nlq(new Nlq(0, 0, 1, 0, 0, 0, 0)),
            Optional.of(EnhancementLayer.FEL)),
        Arguments.of(
            "a dead zone slope",
            SyntheticRpus.profile7().nlq(new Nlq(0, 1, 0, 0, 1, 0, 0)),
            Optional.of(EnhancementLayer.FEL)),
        Arguments.of(
            "a dead zone threshold",
            SyntheticRpus.profile7().nlq(new Nlq(0, 1, 0, 0, 0, 1, 0)),
            Optional.of(EnhancementLayer.FEL)),
        Arguments.of(
            "no residual, as profile 8 carries none",
            SyntheticRpus.profile7().withoutResidual(),
            Optional.empty()),
        Arguments.of(
            "no mapping of its own", SyntheticRpus.profile7().previousRpu(3), Optional.empty()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("roundTrips")
  @DisplayName("Should write the bytes it parsed when nothing changes")
  void shouldWriteTheBytesItParsedWhenNothingChanges(String name, SyntheticRpus.Builder source) {
    var escaped = source.escaped();

    var rpu = DolbyVisionRpu.parse(ByteBuffer.wrap(escaped));

    assertThat(HEX.formatHex(rpu.write())).isEqualTo(HEX.formatHex(escaped));
  }

  static Stream<Arguments> roundTrips() {
    return Stream.of(
        Arguments.of("a FEL RPU with MMR chroma curves", SyntheticRpus.profile7()),
        Arguments.of(
            "a MEL RPU with eight luma pieces and CM v4.0 blocks",
            SyntheticRpus.profile7().nlq(Nlq.minimal()).lumaPieces(8).cmv40()),
        Arguments.of("polynomial chroma curves", SyntheticRpus.profile7().polynomialChroma()),
        Arguments.of("compressed DM metadata", SyntheticRpus.profile7().compressedDm()),
        Arguments.of("no DM metadata", SyntheticRpus.profile7().withoutDmMetadata()),
        Arguments.of("the previous RPU's mapping", SyntheticRpus.profile7().previousRpu(0)),
        Arguments.of(
            "a previous RPU id of 15, the largest the decoder reads",
            SyntheticRpus.profile7().previousRpu(15)),
        Arguments.of(
            "every id, partition count and bit depth at the largest value the decoder reads",
            SyntheticRpus.profile7()
                .vdrRpuId(15)
                .partitionsMinus1(65_534, 65_534)
                .enhancementLayerBitDepthField(0xFF02)
                .vdrBitDepthMinus8(8)
                .affectedDmMetadataId(15)
                .currentDmMetadataId(15)
                .signalBitDepth(16)),
        Arguments.of(
            "a block list of 31 extension blocks, as many as the decoder reads, six static and 25"
                + " dynamic",
            SyntheticRpus.profile7().emptyBlocks(6, 25)),
        Arguments.of(
            "seven static and 25 dynamic extension blocks across both lists, as many as the decoder"
                + " keeps",
            SyntheticRpus.profile7().emptyBlocks(7, 24).emptyCmv40Blocks(0, 1)),
        Arguments.of(
            "eight static extension blocks under compressed DM metadata, which the decoder parses"
                + " without keeping",
            SyntheticRpus.profile7().compressedDm().emptyBlocks(8, 0)),
        Arguments.of(
            "a scene_refresh_flag, mapping_color_space and mapping_chroma_format_idc of 30 and an"
                + " extension block of 30 bytes, the largest Exp-Golomb values the decoder reads",
            SyntheticRpus.profile7()
                .sceneRefreshFlag(30)
                .mappingColorSpace(30)
                .mappingChromaFormatIdc(30)
                .singleBlockOf(30)),
        Arguments.of("a profile 8 RPU", SyntheticRpus.profile7().withoutResidual()));
  }

  @Test
  @DisplayName("Should drop trailing zero bytes when it writes an RPU that carried them")
  void shouldDropTrailingZeroBytesWhenItWritesAnRpuThatCarriedThem() {
    var padded = SyntheticRpus.profile7().trailingZeroBytes(3).escaped();

    var rpu = DolbyVisionRpu.parse(ByteBuffer.wrap(padded));

    assertThat(rpu.write()).isEqualTo(SyntheticRpus.profile7().escaped());
  }

  // dovi_tool's mode 2: no residual, no enhancement-layer filter, no NLQ, one partition, identity
  // curves in place of a FEL's mapping, the profile 8.1 colour coefficients, and the extension
  // blocks as they were.
  @Test
  @DisplayName(
      "Should remove the mapping and the residual when it converts a FEL RPU to profile 8.1")
  void shouldRemoveTheMappingAndTheResidualWhenItConvertsAFelRpuToProfile81() {
    var source = DolbyVisionRpu.parse(ByteBuffer.wrap(SyntheticRpus.profile7().cmv40().escaped()));

    var converted = DolbyVisionRpu.parse(ByteBuffer.wrap(source.convertedToProfile81().write()));

    assertThat(converted.profile()).isEqualTo(8);
    assertThat(converted.enhancementLayer()).isEmpty();
    assertThat(converted.header().enhancementLayerSpatialResamplingFilter()).isFalse();
    assertThat(converted.header().disableResidual()).isTrue();
    var mapping = converted.mapping().orElseThrow();
    assertThat(mapping.nlq()).isEmpty();
    assertThat(mapping.xPartitionsMinus1()).isZero();
    assertThat(mapping.yPartitionsMinus1()).isZero();
    assertThat(mapping.curves())
        .allSatisfy(
            curve -> {
              assertThat(curve.pivots()).containsExactly(0, 1023);
              assertThat(curve.pieces())
                  .containsExactly(new Polynomial(0, List.of(0L, 1L), List.of(0L, 0L)));
            });
    var signal = converted.dmData().orElseThrow().signal().orElseThrow();
    assertThat(signal.yccToRgbCoefficients()).isEqualTo(PROFILE_81_YCC_TO_RGB);
    assertThat(signal.yccToRgbOffsets()).isEqualTo(PROFILE_81_YCC_TO_RGB_OFFSETS);
    assertThat(signal.rgbToLmsCoefficients()).isEqualTo(PROFILE_81_RGB_TO_LMS);
    assertThat(signal.colorSpace()).isZero();
    assertThat(converted.dmData().orElseThrow().cmv29Blocks())
        .isEqualTo(source.dmData().orElseThrow().cmv29Blocks());
    assertThat(converted.dmData().orElseThrow().cmv40Blocks())
        .isEqualTo(source.dmData().orElseThrow().cmv40Blocks());
  }

  @Test
  @DisplayName("Should write the bytes that dovi_tool's mode 2 writes when it converts a FEL RPU")
  void shouldWriteTheBytesThatDoviToolsMode2WritesWhenItConvertsAFelRpu() {
    var source = DolbyVisionRpu.parse(ByteBuffer.wrap(SyntheticRpus.profile7().escaped()));

    var converted = source.convertedToProfile81().write();

    assertThat(HEX.formatHex(converted))
        .isEqualTo(HEX.formatHex(SyntheticRpus.convertedProfile7()));
  }

  @Test
  @DisplayName("Should keep the mapping when it converts a MEL RPU to profile 8.1")
  void shouldKeepTheMappingWhenItConvertsAMelRpuToProfile81() {
    var source =
        DolbyVisionRpu.parse(
            ByteBuffer.wrap(SyntheticRpus.profile7().nlq(Nlq.minimal()).lumaPieces(4).escaped()));

    var converted = DolbyVisionRpu.parse(ByteBuffer.wrap(source.convertedToProfile81().write()));

    assertThat(converted.profile()).isEqualTo(8);
    assertThat(converted.mapping().orElseThrow().nlq()).isEmpty();
    assertThat(converted.mapping().orElseThrow().curves())
        .isEqualTo(source.mapping().orElseThrow().curves());
  }

  @Test
  @DisplayName(
      "Should write a longer RPU than the source when the profile 8.1 coefficients need emulation"
          + " prevention")
  void shouldWriteALongerRpuThanTheSourceWhenTheProfile81CoefficientsNeedEmulationPrevention() {
    var escaped = SyntheticRpus.profile7().previousRpu(0).escaped();

    var converted = DolbyVisionRpu.parse(ByteBuffer.wrap(escaped)).convertedToProfile81().write();

    assertThat(converted).hasSizeGreaterThan(escaped.length);
    assertThat(HEX.formatHex(converted))
        .isEqualTo(HEX.formatHex(SyntheticRpus.convertedPreviousRpu()));
  }

  // The refusal covers the write as well: a value the parser keeps is one the writer codes, so a
  // malformed RPU never fails the copy as anything but a conversion failure.
  @ParameterizedTest(name = "{0}")
  @MethodSource("unconvertibleRpus")
  @DisplayName("Should refuse an RPU that it cannot parse, convert and write")
  void shouldRefuseAnRpuThatItCannotParseConvertAndWrite(String name, byte[] escaped) {
    var wrapped = ByteBuffer.wrap(escaped);

    assertThatThrownBy(() -> parsedConvertedAndWritten(wrapped))
        .isInstanceOf(DolbyVisionRpuException.class);
  }

  private static byte[] parsedConvertedAndWritten(ByteBuffer escaped) {
    return DolbyVisionRpu.parse(escaped).convertedToProfile81().write();
  }

  static Stream<Arguments> unconvertibleRpus() {
    var fel = SyntheticRpus.profile7().escaped();
    var wrongCrc = fel.clone();
    wrongCrc[wrongCrc.length - 3] ^= 0x01;
    var wrongPrefix = fel.clone();
    wrongPrefix[0] = 0x18;
    var noFinalByte = fel.clone();
    noFinalByte[noFinalByte.length - 1] = 0x7F;
    return Stream.of(
        Arguments.of("an RPU cut in half", Arrays.copyOf(fel, fel.length / 2)),
        Arguments.of("an RPU of five bytes", Arrays.copyOf(fel, 5)),
        Arguments.of("a CRC32 that does not match", wrongCrc),
        Arguments.of("a NAL prefix other than 25", wrongPrefix),
        Arguments.of("a last byte other than 0x80", noFinalByte),
        Arguments.of(
            "an extension block that claims more bytes than the RPU holds",
            SyntheticRpus.profile7().firstBlockClaiming(1_000).escaped()),
        Arguments.of(
            "an extension block that claims 2147483648 bytes",
            SyntheticRpus.profile7().firstBlockClaiming(2_147_483_648L).escaped()),
        Arguments.of(
            "a coefficient denominator of 24, above the 23 that the syntax allows",
            SyntheticRpus.profile7().coefficientLog2Denom(24).fractionBits(24).escaped()),
        Arguments.of(
            "a coefficient denominator of 2147483648 beside fraction fields of no bits",
            SyntheticRpus.profile7()
                .coefficientLog2Denom(2_147_483_648L)
                .fractionBits(0)
                .escaped()),
        Arguments.of(
            "a block list of 32 extension blocks, above the 31 that the decoder reads",
            SyntheticRpus.profile7().emptyBlocks(32).escaped()),
        Arguments.of(
            "a coefficient denominator of 12, below the 13 that the decoder reads",
            SyntheticRpus.profile7().coefficientLog2Denom(12).fractionBits(12).escaped()),
        Arguments.of(
            "a previous RPU id of 16, above the 15 that the decoder reads",
            SyntheticRpus.profile7().previousRpu(16).escaped()),
        Arguments.of(
            "a vdr_rpu_id of 16, above the 15 that the decoder reads",
            SyntheticRpus.profile7().vdrRpuId(16).escaped()),
        Arguments.of(
            "num_x_partitions_minus1 of 65535, above the 65534 that the decoder reads",
            SyntheticRpus.profile7().partitionsMinus1(65_535, 1).escaped()),
        Arguments.of(
            "num_y_partitions_minus1 of 65535, above the 65534 that the decoder reads",
            SyntheticRpus.profile7().partitionsMinus1(2, 65_535).escaped()),
        Arguments.of(
            "a base layer of 17 bits, above the 16 that the decoder reads",
            SyntheticRpus.profile7().baseLayerBitDepthMinus8(9).escaped()),
        Arguments.of(
            "an enhancement layer of 17 bits, above the 16 that the decoder reads",
            SyntheticRpus.profile7().enhancementLayerBitDepthField(9).escaped()),
        Arguments.of(
            "an ext_mapping_idc of 256 above the enhancement layer's bit depth, past the 255 that"
                + " the decoder reads",
            SyntheticRpus.profile7().enhancementLayerBitDepthField(0x10002).escaped()),
        Arguments.of(
            "a VDR bit depth of 17 bits, above the 16 that the decoder reads",
            SyntheticRpus.profile7().vdrBitDepthMinus8(9).escaped()),
        Arguments.of(
            "an affected DM metadata id of 16, above the 15 that the decoder reads",
            SyntheticRpus.profile7().affectedDmMetadataId(16).escaped()),
        Arguments.of(
            "a current DM metadata id of 16, above the 15 that the decoder reads",
            SyntheticRpus.profile7().currentDmMetadataId(16).escaped()),
        Arguments.of(
            "a signal bit depth of 7, below the 8 that the decoder reads",
            SyntheticRpus.profile7().signalBitDepth(7).escaped()),
        Arguments.of(
            "a signal bit depth of 17, above the 16 that the decoder reads",
            SyntheticRpus.profile7().signalBitDepth(17).escaped()),
        Arguments.of(
            "floating-point coefficients, whose identity curve the conversion does not write",
            SyntheticRpus.profile7().floatingPointCoefficients().escaped()),
        Arguments.of(
            "an affected DM metadata id of 1 beside a current id of 0, which the decoder rejects",
            SyntheticRpus.profile7().affectedDmMetadataId(1).escaped()),
        Arguments.of(
            "a metadata compression of 2, which the decoder does not implement",
            SyntheticRpus.profile7().dmMetadataCompression(2).escaped()),
        Arguments.of(
            "a metadata compression of 7, which the decoder does not implement",
            SyntheticRpus.profile7().dmMetadataCompression(7).escaped()),
        Arguments.of(
            "compressed DM metadata that the RPU does not carry, which the decoder rejects",
            SyntheticRpus.profile7().compressedDm().withoutDmMetadata().escaped()),
        Arguments.of(
            "compressed DM metadata, which the converted copy's record declares uncompressed",
            SyntheticRpus.profile7().compressedDm().escaped()),
        Arguments.of(
            "two bytes between the metadata and the CRC32, where the decoder reads the CRC32",
            SyntheticRpus.profile7().withoutDmMetadata().bytesBeforeCrc32(0xFF, 0x01).escaped()),
        Arguments.of(
            "eight static extension blocks, above the seven that the decoder keeps",
            SyntheticRpus.profile7().emptyBlocks(8, 0).escaped()),
        Arguments.of(
            "26 dynamic extension blocks across both lists, above the 25 that the decoder keeps",
            SyntheticRpus.profile7().emptyBlocks(0, 25).emptyCmv40Blocks(0, 1).escaped()),
        Arguments.of(
            "an eighth static extension block in the CM v4.0 list after seven in the CM v2.9 list",
            SyntheticRpus.profile7().emptyBlocks(7, 0).emptyCmv40Blocks(1, 0).escaped()),
        Arguments.of(
            "a scene_refresh_flag of 31, above the 30 that the decoder's Exp-Golomb read decodes",
            SyntheticRpus.profile7().sceneRefreshFlag(31).escaped()),
        Arguments.of(
            "a mapping_color_space of 31, above the 30 that the decoder's Exp-Golomb read decodes",
            SyntheticRpus.profile7().mappingColorSpace(31).escaped()),
        Arguments.of(
            "a mapping_chroma_format_idc of 31, above the 30 that the decoder's Exp-Golomb read"
                + " decodes",
            SyntheticRpus.profile7().mappingChromaFormatIdc(31).escaped()),
        Arguments.of(
            "an extension block of 31 bytes, above the 30 that the decoder's Exp-Golomb read decodes",
            SyntheticRpus.profile7().singleBlockOf(31).escaped()),
        Arguments.of(
            "a previous RPU id of 4294967295, above the largest value a ue(v) codes",
            SyntheticRpus.profile7().previousRpu(4_294_967_295L).escaped()),
        Arguments.of(
            "a piece of mapping_idc 2", SyntheticRpus.profile7().lumaMappingIdc(2).escaped()),
        Arguments.of(
            "a piece of mapping_idc 4294967296, which is 0 in 32 bits",
            SyntheticRpus.profile7().lumaMappingIdc(4_294_967_296L).escaped()),
        Arguments.of(
            "a piece of mapping_idc 4294967297, which is 1 in 32 bits",
            SyntheticRpus.profile7().lumaMappingIdc(4_294_967_297L).escaped()),
        Arguments.of("a profile 5 RPU", SyntheticRpus.profile7().profile5().escaped()));
  }
}
