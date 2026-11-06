package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.lengthPrefixed;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionEnhancementLayer;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionPresent;
import build.buf.gen.streamarr.transcode.v1.Presence;
import com.streamarr.transcode.probe.RpuBits.ExtensionBlock;
import com.streamarr.transcode.probe.RpuBits.Nlq;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The enhancement layer and the level 6 light levels that probe version 8 reads from the RPUs of an
 * HEVC stream's first packets. The NLQ parameters tell a minimal enhancement layer from a full one
 * as libdovi does, and FFmpeg 8.1's RPU parser decides which RPUs the probe can parse.
 */
@Tag("UnitTest")
@DisplayName("Ffprobe Executor RPU Facts Tests")
class FfprobeExecutorRpuFactsTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("classifiedLayers")
  @DisplayName("Should classify the enhancement layer by the NLQ parameters of the parsed RPUs")
  void shouldClassifyTheEnhancementLayerByTheNlqParametersOfTheParsedRpus(
      RpuBits[] rpus, DolbyVisionEnhancementLayer expected) {
    var present = dolbyVisionOf(rpus[0], rpus[1], rpus[2]);

    assertThat(present.getEnhancementLayer()).isEqualTo(expected);
  }

  static Stream<Arguments> classifiedLayers() {
    var minimal = RpuBits.profile7(Nlq.identity()).build();
    var full = RpuBits.profile7(Nlq.fullEnhancementLayer()).build();
    var withoutResidual = RpuBits.profile81().build();
    var referring = minimal.toBuilder().usePreviousMapping(true).build();
    return Stream.of(
        layer(
            "identity NLQ in every RPU",
            DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_MEL,
            minimal,
            minimal,
            minimal),
        layer(
            "a full enhancement layer's NLQ in every RPU",
            DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_FEL,
            full,
            full,
            full),
        layer(
            "one full RPU among minimal ones",
            DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_FEL,
            minimal,
            full,
            minimal),
        layer(
            "RPUs that disable the residual",
            DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_NONE,
            withoutResidual,
            withoutResidual,
            withoutResidual),
        layer(
            "RPUs that refer to an earlier RPU's mapping",
            DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_UNSPECIFIED,
            referring,
            referring,
            referring),
        layer(
            "a minimal RPU after RPUs that refer to its mapping",
            DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_MEL,
            referring,
            referring,
            minimal),
        layer(
            "a vdr_in_max of one and a fraction",
            DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_FEL,
            single(Nlq.identity().toBuilder().vdrInMaxFraction(1).build())),
        layer(
            "a nonzero NLQ offset",
            DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_FEL,
            single(Nlq.identity().toBuilder().offset(1).build())),
        layer(
            "a nonzero deadzone threshold",
            DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_FEL,
            single(Nlq.identity().toBuilder().thresholdFraction(1).build())),
        layer(
            "floating-point coefficients, which have no integer parts",
            DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_FEL,
            floatingPoint(),
            floatingPoint(),
            floatingPoint()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unparsableRpus")
  @DisplayName(
      "Should report no enhancement layer and no level 6 values when the walk cannot parse the RPU")
  void shouldReportNoEnhancementLayerAndNoLevel6WhenTheWalkCannotParseTheRpu(String rpu) {
    var present = dolbyVisionOf(rpu, rpu, rpu);

    assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
    assertThat(present.getEnhancementLayer())
        .isEqualTo(DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_UNSPECIFIED);
    assertThat(present.hasRpuContentLightLevel()).isFalse();
  }

  static Stream<Named<String>> unparsableRpus() {
    var level6 =
        RpuBits.profile7(Nlq.identity()).extensionBlock(ExtensionBlock.level6(1_000, 400)).build();
    return Stream.of(
        Named.of("an RPU cut inside its NLQ parameters", endingAfter(level6, 30)),
        Named.of(
            "an RPU cut inside its level 6 block",
            endingAfter(level6, level6.payload().length - 8)),
        Named.of(
            "a partition count of 65,536, above the pinned FFmpeg's 65,535",
            level6.toBuilder().xPartitionsMinus1(65_535).build().hevcNalUnit()),
        Named.of(
            "a partition count of 2^32, whose 32 leading zeros FFmpeg reads as 31",
            level6.toBuilder().xPartitionsMinus1(0xFFFF_FFFFL).build().hevcNalUnit()),
        Named.of(
            "an RPU that reuses an earlier RPU's mapping, whose presence the walk does not track",
            level6.toBuilder().usePreviousMapping(true).build().hevcNalUnit()),
        Named.of(
            "an el_bit_depth_minus8 whose upper bits name ext_mapping_idc 256, above FFmpeg's 255",
            level6.toBuilder()
                .header(level6.header().toBuilder().elBitDepthMinus8(0x1_00_02).build())
                .build()
                .hevcNalUnit()),
        Named.of(
            "an RPU that ends before its CRC and terminator",
            endingAfter(level6, level6.payload().length - 5)),
        Named.of(
            "an RPU whose terminator is not 0x80",
            level6.toBuilder().terminator(0x00).build().hevcNalUnit()),
        Named.of(
            "a second group of extension blocks that declares more blocks than the RPU holds",
            level6.toBuilder()
                .secondGroupBlockCount(40)
                .secondGroupBlock(ExtensionBlock.level254())
                .build()
                .hevcNalUnit()),
        Named.of(
            "an RPU whose affected and current DM metadata IDs differ",
            level6.toBuilder().currentDmId(1).build().hevcNalUnit()),
        Named.of(
            "a signal bit depth of 7, below FFmpeg's 8",
            level6.toBuilder().signalBitDepth(7).build().hevcNalUnit()),
        Named.of(
            "a signal bit depth of 17, above FFmpeg's 16",
            level6.toBuilder().signalBitDepth(17).build().hevcNalUnit()),
        Named.of(
            "a level 2 block whose ms_weight is -2, below FFmpeg's -1",
            level6.toBuilder().extensionBlock(ExtensionBlock.level2(-2)).build().hevcNalUnit()),
        Named.of(
            "a level 2 block whose ms_weight is -4096 in a compressed DM payload",
            level6.toBuilder()
                .header(level6.header().toBuilder().dmCompression(1).build())
                .extensionBlock(ExtensionBlock.level2(-4096))
                .build()
                .hevcNalUnit()),
        Named.of(
            "eight static extension blocks, one more than FFmpeg holds",
            withBlocks(level6.toBuilder(), 7, new ExtensionBlock(255, zeros(6))).hevcNalUnit()),
        Named.of(
            "26 dynamic extension blocks, one more than FFmpeg holds",
            withBlocks(level6.toBuilder(), 26, ExtensionBlock.level1()).hevcNalUnit()),
        Named.of(
            "NLQ in an RPU that FFmpeg takes for profile 5",
            level6.toBuilder()
                .header(level6.header().toBuilder().vdrRpuProfile(0).blVideoFullRange(true).build())
                .build()
                .hevcNalUnit()),
        Named.of(
            "DM compression without a DM payload",
            level6.toBuilder()
                .header(level6.header().toBuilder().dmCompression(1).build())
                .hasDisplayManagement(false)
                .build()
                .hevcNalUnit()),
        Named.of(
            "an rpu_format without bit depths",
            level6.toBuilder()
                .header(level6.header().toBuilder().rpuFormat(0x112).build())
                .build()
                .hevcNalUnit()));
  }

  @ParameterizedTest(name = "level {0} in group {1}, at least {2} bytes")
  @MethodSource("blockMinimums")
  @DisplayName(
      "Should parse an extension block of its minimum length and reject one a byte shorter, as"
          + " FFmpeg does")
  void shouldParseAnExtensionBlockOfItsMinimumLengthAndRejectOneAByteShorter(
      int level, int group, int minimumBytes) {
    var whole = dolbyVisionWithBlock(new ExtensionBlock(level, zeros(minimumBytes)), group, 0);
    var shorter =
        dolbyVisionWithBlock(new ExtensionBlock(level, zeros(minimumBytes - 1)), group, 0);

    assertThat(whole.getEnhancementLayer())
        .isEqualTo(DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_MEL);
    assertThat(whole.hasRpuContentLightLevel()).isTrue();
    assertThat(shorter.getEnhancementLayer())
        .isEqualTo(DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_UNSPECIFIED);
    assertThat(shorter.hasRpuContentLightLevel()).isFalse();
  }

  @ParameterizedTest(name = "level {0} in group {1}, at least {2} bytes")
  @MethodSource("blockMinimums")
  @DisplayName(
      "Should reject an extension block a byte shorter than its minimum when the DM payload is"
          + " compressed")
  void shouldRejectAnExtensionBlockAByteShorterThanItsMinimumWhenTheDmPayloadIsCompressed(
      int level, int group, int minimumBytes) {
    var whole = dolbyVisionWithBlock(new ExtensionBlock(level, zeros(minimumBytes)), group, 1);
    var shorter =
        dolbyVisionWithBlock(new ExtensionBlock(level, zeros(minimumBytes - 1)), group, 1);

    assertThat(whole.getEnhancementLayer())
        .isEqualTo(DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_MEL);
    assertThat(whole.hasRpuContentLightLevel()).isFalse();
    assertThat(shorter.getEnhancementLayer())
        .isEqualTo(DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_UNSPECIFIED);
  }

  // FFmpeg 8.1's dovi_rpudec.c: the payload each recognized level needs, by group.
  static Stream<Arguments> blockMinimums() {
    return Stream.of(
        Arguments.of(1, 1, 5),
        Arguments.of(2, 1, 11),
        Arguments.of(4, 1, 3),
        Arguments.of(5, 1, 7),
        Arguments.of(6, 1, 8),
        Arguments.of(255, 1, 6),
        Arguments.of(3, 2, 5),
        Arguments.of(8, 2, 10),
        Arguments.of(9, 2, 1),
        Arguments.of(10, 2, 5),
        Arguments.of(11, 2, 4),
        Arguments.of(254, 2, 2));
  }

  private static DolbyVisionPresent dolbyVisionWithBlock(
      ExtensionBlock block, int group, int dmCompression) {
    var rpu = RpuBits.profile7(Nlq.identity()).extensionBlock(ExtensionBlock.level6(1_000, 400));
    rpu.header(rpu.build().header().toBuilder().dmCompression(dmCompression).build());
    if (group == 1) {
      rpu.extensionBlock(block);
    }

    if (group == 2) {
      rpu.secondGroupBlockCount(1).secondGroupBlock(block);
    }

    var nalUnit = rpu.build().hevcNalUnit();
    return dolbyVisionOf(nalUnit, nalUnit, nalUnit);
  }

  private static List<Integer> zeros(int bytes) {
    return Collections.nCopies(bytes, 0);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("fullBlockArrays")
  @DisplayName("Should parse an RPU when it holds as many extension blocks as FFmpeg holds")
  void shouldParseAnRpuWhenItHoldsAsManyExtensionBlocksAsFfmpegHolds(RpuBits rpu) {
    var present = dolbyVisionOf(rpu, rpu, rpu);

    assertThat(present.getEnhancementLayer())
        .isEqualTo(DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_MEL);
  }

  static Stream<Named<RpuBits>> fullBlockArrays() {
    var level6 =
        RpuBits.profile7(Nlq.identity()).extensionBlock(ExtensionBlock.level6(1_000, 400)).build();
    var compressed =
        level6.toBuilder().header(level6.header().toBuilder().dmCompression(1).build());
    return Stream.of(
        Named.of(
            "seven static blocks",
            withBlocks(level6.toBuilder(), 6, new ExtensionBlock(255, zeros(6)))),
        Named.of("25 dynamic blocks", withBlocks(level6.toBuilder(), 25, ExtensionBlock.level1())),
        Named.of(
            "eight static blocks in a compressed DM payload, where FFmpeg ignores them",
            withBlocks(compressed, 7, new ExtensionBlock(255, zeros(6)))));
  }

  private static RpuBits withBlocks(RpuBits.RpuBitsBuilder rpu, int count, ExtensionBlock block) {
    for (var added = 0; added < count; added++) {
      rpu.extensionBlock(block);
    }

    return rpu.build();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("headerAndMappingValuesThatFfmpegAccepts")
  @DisplayName("Should parse an RPU when its header and mapping hold values that FFmpeg accepts")
  void shouldParseAnRpuWhenItsHeaderAndMappingHoldValuesThatFfmpegAccepts(RpuBits rpu) {
    var present = dolbyVisionOf(rpu, rpu, rpu);

    assertThat(present.getEnhancementLayer())
        .isEqualTo(DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_MEL);
    assertThat(present.hasRpuContentLightLevel()).isTrue();
  }

  static Stream<Named<RpuBits>> headerAndMappingValuesThatFfmpegAccepts() {
    var level6 =
        RpuBits.profile7(Nlq.identity()).extensionBlock(ExtensionBlock.level6(1_000, 400)).build();
    return Stream.of(
        Named.of(
            "a mapping with 65,535 horizontal partitions, the pinned FFmpeg's largest count",
            level6.toBuilder().xPartitionsMinus1(65_534).build()),
        Named.of(
            "an el_bit_depth_minus8 whose upper bits name ext_mapping_idc 255",
            level6.toBuilder()
                .header(level6.header().toBuilder().elBitDepthMinus8(0xFF_02).build())
                .build()));
  }

  @ParameterizedTest(name = "a length field of {0} over {1} bytes")
  @CsvSource({"31, 31, true", "32, 31, true", "32, 32, false"})
  @DisplayName(
      "Should read an extension block's length field as FFmpeg does, which reads 31 to 34 as 31")
  void shouldReadAnExtensionBlocksLengthFieldAsFfmpegDoes(
      int declaredLength, int payloadBytes, boolean isParsed) {
    var payload = new ArrayList<>(ExtensionBlock.level6(1_000, 400).payload());
    payload.addAll(zeros(payloadBytes - payload.size()));
    var rpu =
        RpuBits.profile7(Nlq.identity())
            .extensionBlock(new ExtensionBlock(6, payload, declaredLength))
            .build();

    var present = dolbyVisionOf(rpu, rpu, rpu);

    assertThat(present.hasRpuContentLightLevel()).isEqualTo(isParsed);
  }

  @ParameterizedTest(name = "ms_weight {0}")
  @ValueSource(ints = {-1, 0, 4095})
  @DisplayName("Should parse a level 2 block when its ms_weight lies within FFmpeg's -1 to 4095")
  void shouldParseALevel2BlockWhenItsMsWeightLiesWithinFfmpegsRange(int msWeight) {
    var rpu =
        RpuBits.profile7(Nlq.identity())
            .extensionBlock(ExtensionBlock.level2(msWeight))
            .extensionBlock(ExtensionBlock.level6(1_000, 400))
            .build();

    var present = dolbyVisionOf(rpu, rpu, rpu);

    assertThat(present.getEnhancementLayer())
        .isEqualTo(DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_MEL);
    assertThat(present.hasRpuContentLightLevel()).isTrue();
  }

  @Test
  @DisplayName(
      "Should take the level 6 values from the next parsed RPU when FFmpeg rejects the first one")
  void shouldTakeTheLevel6ValuesFromTheNextParsedRpuWhenFfmpegRejectsTheFirstOne() {
    var rejected =
        RpuBits.profile81()
            .extensionBlock(ExtensionBlock.level6(4_000, 1_000))
            .currentDmId(1)
            .build();
    var parsed = RpuBits.profile81().extensionBlock(ExtensionBlock.level6(1_000, 400)).build();

    var present = dolbyVisionOf(rejected, parsed, parsed);

    assertThat(present.getRpuContentLightLevel())
        .isEqualTo(
            ContentLightLevel.newBuilder()
                .setMaxContentLightLevel(1_000)
                .setMaxFrameAverageLightLevel(400)
                .build());
  }

  @Test
  @DisplayName(
      "Should report MaxCLL and MaxFALL from the first level 6 block when an RPU carries one after"
          + " another block")
  void shouldReportMaxCllAndMaxFallFromTheFirstLevel6BlockWhenAnRpuCarriesOneAfterAnotherBlock() {
    var plain = RpuBits.profile81().build();
    var withLevel6 =
        RpuBits.profile81()
            .extensionBlock(ExtensionBlock.level1())
            .extensionBlock(ExtensionBlock.level6(1_000, 400))
            .build();
    var laterLevel6 =
        RpuBits.profile81().extensionBlock(ExtensionBlock.level6(4_000, 1_000)).build();

    var present = dolbyVisionOf(plain, withLevel6, laterLevel6);

    assertThat(present.getRpuContentLightLevel())
        .isEqualTo(
            ContentLightLevel.newBuilder()
                .setMaxContentLightLevel(1_000)
                .setMaxFrameAverageLightLevel(400)
                .build());
  }

  @Test
  @DisplayName(
      "Should report the level 6 values when a second group of extension blocks follows the first")
  void shouldReportTheLevel6ValuesWhenASecondGroupOfExtensionBlocksFollowsTheFirst() {
    var rpu =
        RpuBits.profile81()
            .extensionBlock(ExtensionBlock.level6(1_000, 400))
            .secondGroupBlockCount(1)
            .secondGroupBlock(ExtensionBlock.level254())
            .build();

    var present = dolbyVisionOf(rpu, rpu, rpu);

    assertThat(present.getRpuContentLightLevel())
        .isEqualTo(
            ContentLightLevel.newBuilder()
                .setMaxContentLightLevel(1_000)
                .setMaxFrameAverageLightLevel(400)
                .build());
  }

  @Test
  @DisplayName("Should report the RPU's facts when zero bytes pad its NAL unit")
  void shouldReportTheRpusFactsWhenZeroBytesPadItsNalUnit() {
    var padded =
        RpuBits.profile7(Nlq.identity())
                .extensionBlock(ExtensionBlock.level6(1_000, 400))
                .build()
                .hevcNalUnit()
            + " 00 00";

    var present = dolbyVisionOf(padded, padded, padded);

    assertThat(present.getEnhancementLayer())
        .isEqualTo(DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_MEL);
    assertThat(present.getRpuContentLightLevel())
        .isEqualTo(
            ContentLightLevel.newBuilder()
                .setMaxContentLightLevel(1_000)
                .setMaxFrameAverageLightLevel(400)
                .build());
  }

  @Test
  @DisplayName("Should keep zero light levels when the level 6 block states that they are unknown")
  void shouldKeepZeroLightLevelsWhenTheLevel6BlockStatesThatTheyAreUnknown() {
    var unknown = RpuBits.profile81().extensionBlock(ExtensionBlock.level6(0, 0)).build();

    var present = dolbyVisionOf(unknown, unknown, unknown);

    assertThat(present.hasRpuContentLightLevel()).isTrue();
    assertThat(present.getRpuContentLightLevel()).isEqualTo(ContentLightLevel.getDefaultInstance());
  }

  @Test
  @DisplayName("Should report no level 6 values when no parsed RPU carries a level 6 block")
  void shouldReportNoLevel6ValuesWhenNoParsedRpuCarriesALevel6Block() {
    var level1Only = RpuBits.profile81().extensionBlock(ExtensionBlock.level1()).build();
    var withoutDisplayManagement = RpuBits.profile81().hasDisplayManagement(false).build();

    var present = dolbyVisionOf(level1Only, withoutDisplayManagement, level1Only);

    assertThat(present.getEnhancementLayer())
        .isEqualTo(DolbyVisionEnhancementLayer.DOLBY_VISION_ENHANCEMENT_LAYER_NONE);
    assertThat(present.hasRpuContentLightLevel()).isFalse();
  }

  private static Arguments layer(
      String description, DolbyVisionEnhancementLayer expected, RpuBits... rpus) {
    var three = rpus.length == 1 ? new RpuBits[] {rpus[0], rpus[0], rpus[0]} : rpus;
    return Arguments.of(Named.of(description, three), expected);
  }

  private static RpuBits single(Nlq nlq) {
    return RpuBits.profile7(nlq).build();
  }

  private static RpuBits floatingPoint() {
    var identity = Nlq.identity();
    var rpu = RpuBits.profile7(identity).build();
    return rpu.toBuilder().header(rpu.header().toBuilder().coefficientDataType(1).build()).build();
  }

  /** The RPU's first bytes as an HEVC RPU NAL unit, with emulation prevention, as hex. */
  private static String endingAfter(RpuBits rpu, int length) {
    return "7c 01 " + RpuDataHeaderBits.escaped(Arrays.copyOf(rpu.payload(), length));
  }

  private static DolbyVisionPresent dolbyVisionOf(RpuBits first, RpuBits second, RpuBits third) {
    return dolbyVisionOf(first.hevcNalUnit(), second.hevcNalUnit(), third.hevcNalUnit());
  }

  private static DolbyVisionPresent dolbyVisionOf(String first, String second, String third) {
    return HevcSamples.probed(
            HevcSamples.RECORD,
            lengthPrefixed(HevcSamples.IDR_SLICE, first),
            lengthPrefixed(HevcSamples.SLICE, second),
            lengthPrefixed(HevcSamples.SLICE, third))
        .getDynamicRange()
        .getDolbyVisionPresent();
  }
}
