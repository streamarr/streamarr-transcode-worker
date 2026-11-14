package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.IsoBoxes.box;
import static com.streamarr.transcode.engine.IsoBoxes.concat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("AVC Sequence Parameter Set Tests")
class AvcSequenceParameterSetTest {

  @ParameterizedTest
  @ValueSource(ints = {1, 2, 4})
  @DisplayName("Should rewrite each in-band SPS and preserve slices when a sample names its matrix")
  void shouldRewriteEachInBandSpsAndPreserveSlicesWhenASampleNamesItsMatrix(int lengthSize) {
    var first = Sps.builder().build();
    var second =
        Sps.builder().vui(true).videoSignal(true).colourDescription(true).matrix(9).build();
    var slice = new byte[] {0x65, 0, 0, 3, 1, 99};
    var sample = sample(lengthSize, first.bytes(), slice, second.bytes());
    var rewrite = new NalUnitSampleRewrite(NalSyntax.AVC, lengthSize, matrixCopy());

    var result = rewrite.rewrite(sample);

    var taggedFirst =
        first.toBuilder().vui(true).videoSignal(true).colourDescription(true).matrix(6).build();
    assertThat(result)
        .isEqualTo(
            sample(
                lengthSize,
                taggedFirst.bytes(),
                slice,
                second.toBuilder().matrix(6).build().bytes()));
  }

  @Test
  @DisplayName("Should fail as an output mismatch when an in-band SPS cannot be read")
  void shouldFailAsAnOutputMismatchWhenAnInBandSpsCannotBeRead() {
    var rewrite = new NalUnitSampleRewrite(NalSyntax.AVC, 4, matrixCopy());

    assertThatThrownBy(() -> rewrite.rewrite(sample(4, new byte[] {0x67, 0x42})))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            error ->
                assertThat(error.getReason())
                    .isEqualTo(FragmentedMp4Exception.Reason.OUTPUT_MISMATCH));
  }

  private static VideoDecision.Copy matrixCopy() {
    return VideoDecision.Copy.builder()
        .codecString("avc1.42001E")
        .bitstreamRewrites(Set.of(BitstreamRewrite.NAME_ASSUMED_SOURCE_MATRIX))
        .assumedSourceMatrix(Optional.of(AssumedSourceMatrix.BT601))
        .plannedOutput(
            Optional.of(PlannedCopyOutput.builder().dynamicRange(VideoDynamicRange.sdr()).build()))
        .build();
  }

  private static ByteBuffer sample(int lengthSize, byte[]... units) {
    var bytes = new ByteArrayOutputStream();
    for (var unit : units) {
      for (var index = lengthSize - 1; index >= 0; index--) {
        bytes.write(unit.length >>> (8 * index));
      }

      bytes.writeBytes(unit);
    }

    return ByteBuffer.wrap(bytes.toByteArray());
  }

  @Test
  @DisplayName(
      "Should rewrite every configured SPS and replace colour boxes when naming a copied matrix")
  void shouldRewriteEveryConfiguredSpsAndReplaceColourBoxesWhenNamingACopiedMatrix() {
    var first =
        Sps.builder()
            .vui(true)
            .videoSignal(true)
            .colourDescription(true)
            .primaries(6)
            .transfer(2)
            .fullRange(true)
            .matrix(9)
            .build();
    var second = first.toBuilder().primaries(1).transfer(7).fullRange(false).build();
    var unknown = box("free", new byte[] {11, 12});
    var source =
        entry(
            record(first.bytes(), second.bytes()),
            box("colr", new byte[] {1}),
            unknown,
            box("colr", new byte[] {2}));

    var rewritten =
        new BoxView(
            "avc1",
            ByteBuffer.wrap(
                AvcCopyColour.rewriteSampleEntry(source.body(), AssumedSourceMatrix.BT601)));

    var children = rewritten.after(78).children();
    assertThat(children).extracting(BoxView::type).containsExactly("avcC", "free", "colr");
    var expectedRecord =
        record(
            first.toBuilder().matrix(6).build().bytes(),
            second.toBuilder().matrix(6).build().bytes());
    assertThat(children.getFirst().body())
        .isEqualTo(ByteBuffer.wrap(expectedRecord, 8, expectedRecord.length - 8).slice());
    assertThat(children.getLast().body())
        .isEqualTo(ByteBuffer.wrap(HexFormat.of().parseHex("6e636c7800060001000680")));
    AvcCopyColour.requireMatch(rewritten, AssumedSourceMatrix.BT601);
  }

  @Test
  @DisplayName(
      "Should fail as an output mismatch when the AVC record declares a reserved NAL length")
  void shouldFailAsAnOutputMismatchWhenTheAvcRecordDeclaresAReservedNalLength() {
    var avcC = record(Sps.builder().build().bytes());
    avcC[12] = (byte) 0xFE;
    var source = entry(avcC);

    assertThatThrownBy(
            () -> AvcCopyColour.rewriteSampleEntry(source.body(), AssumedSourceMatrix.BT601))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            error ->
                assertThat(error.getReason())
                    .isEqualTo(FragmentedMp4Exception.Reason.OUTPUT_MISMATCH));
  }

  @ParameterizedTest
  @ValueSource(ints = {0, 4, 6, 8, 10})
  @DisplayName("Should reject a colour box when any planned colour value differs")
  void shouldRejectAColourBoxWhenAnyPlannedColourValueDiffers(int offset) {
    var sps =
        Sps.builder().vui(true).videoSignal(true).colourDescription(true).matrix(6).build().bytes();
    var colour = HexFormat.of().parseHex("6e636c7800010001000600");
    colour[offset] ^= (byte) 0x80;

    assertMismatch(entry(record(sps), box("colr", colour)));
  }

  @Test
  @DisplayName("Should reject missing or duplicate colour boxes when a copy names its matrix")
  void shouldRejectMissingOrDuplicateColourBoxesWhenACopyNamesItsMatrix() {
    var sps =
        Sps.builder().vui(true).videoSignal(true).colourDescription(true).matrix(6).build().bytes();
    var colr = box("colr", HexFormat.of().parseHex("6e636c7800010001000600"));

    assertMismatch(entry(record(sps)));
    assertMismatch(entry(record(sps), colr, colr));
  }

  @Test
  @DisplayName("Should reject a different matrix when a later configured SPS names it")
  void shouldRejectADifferentMatrixWhenALaterConfiguredSpsNamesIt() {
    var sps = Sps.builder().vui(true).videoSignal(true).colourDescription(true).matrix(6).build();
    var colr = box("colr", HexFormat.of().parseHex("6e636c7800010001000600"));

    assertMismatch(entry(record(sps.bytes(), sps.toBuilder().matrix(1).build().bytes()), colr));
  }

  private static void assertMismatch(BoxView entry) {
    assertThatThrownBy(() -> AvcCopyColour.requireMatch(entry, AssumedSourceMatrix.BT601))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            error ->
                assertThat(error.getReason())
                    .isEqualTo(FragmentedMp4Exception.Reason.OUTPUT_MISMATCH));
  }

  private static BoxView entry(byte[]... children) {
    return new BoxView("avc1", ByteBuffer.wrap(concat(new byte[78], concat(children))));
  }

  private static byte[] record(byte[]... parameterSets) {
    var content = new ByteArrayOutputStream();
    content.writeBytes(HexFormat.of().parseHex("0142001eff"));
    content.write(0xE0 | parameterSets.length);
    for (var sps : parameterSets) {
      content.write(sps.length >> 8);
      content.write(sps.length);
      content.writeBytes(sps);
    }

    content.writeBytes(HexFormat.of().parseHex("01000268ce"));
    return box("avcC", content.toByteArray());
  }

  @ParameterizedTest
  @MethodSource("colourLayouts")
  @DisplayName("Should change only the matrix when an SPS has a supported colour layout")
  void shouldChangeOnlyTheMatrixWhenAnSpsHasASupportedColourLayout(
      Sps source, AssumedSourceMatrix matrix) {
    var expected =
        source.toBuilder()
            .vui(true)
            .videoSignal(true)
            .colourDescription(true)
            .matrix(matrix == AssumedSourceMatrix.BT709 ? 1 : 6)
            .build();

    var rewritten = AvcSequenceParameterSet.withMatrix(source.bytes(), matrix);

    assertThat(rewritten).isEqualTo(expected.bytes());
    assertThat(AvcSequenceParameterSet.withMatrix(rewritten, matrix)).isEqualTo(rewritten);
  }

  static Stream<Arguments> colourLayouts() {
    return Stream.of(
            Sps.builder().build(),
            Sps.builder().vui(true).build(),
            Sps.builder().vui(true).videoSignal(true).fullRange(true).build(),
            Sps.builder()
                .vui(true)
                .videoSignal(true)
                .colourDescription(true)
                .primaries(6)
                .transfer(6)
                .matrix(9)
                .fullRange(true)
                .build(),
            Sps.builder()
                .vui(true)
                .videoSignal(true)
                .colourDescription(true)
                .primaries(1)
                .transfer(2)
                .matrix(1)
                .build(),
            Sps.builder().vui(true).extendedAspectAndOverscan(true).build(),
            Sps.builder().vui(true).remainingVui(extendedVui(false, true)).build(),
            Sps.builder().vui(true).remainingVui(extendedVui(true, false)).build(),
            Sps.builder().vui(true).remainingVui(extendedVui(true, true)).build())
        .flatMap(
            source ->
                Arrays.stream(AssumedSourceMatrix.values())
                    .map(matrix -> Arguments.of(source, matrix)));
  }

  @ParameterizedTest
  @EnumSource(AssumedSourceMatrix.class)
  @DisplayName("Should report the SPS colour values when the rewrite names its matrix")
  void shouldReportTheSpsColourValuesWhenTheRewriteNamesItsMatrix(AssumedSourceMatrix matrix) {
    var source =
        Sps.builder()
            .vui(true)
            .videoSignal(true)
            .colourDescription(true)
            .primaries(6)
            .transfer(7)
            .fullRange(true)
            .build();

    var colour =
        AvcSequenceParameterSet.colourDescriptionOf(
            AvcSequenceParameterSet.withMatrix(source.bytes(), matrix));

    assertThat(colour.primaries()).isEqualTo(6);
    assertThat(colour.transfer()).isEqualTo(7);
    assertThat(colour.matrix()).isEqualTo(matrix == AssumedSourceMatrix.BT709 ? 1 : 6);
    assertThat(colour.fullRange()).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "6742", "6742001e0000000000", "6842001e", "e742001ef4"})
  @DisplayName("Should fail as an output mismatch when the SPS cannot be rewritten")
  void shouldFailAsAnOutputMismatchWhenTheSpsCannotBeRewritten(String hex) {
    assertThatThrownBy(
            () ->
                AvcSequenceParameterSet.withMatrix(
                    HexFormat.of().parseHex(hex), AssumedSourceMatrix.BT601))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            error ->
                assertThat(error.getReason())
                    .isEqualTo(FragmentedMp4Exception.Reason.OUTPUT_MISMATCH));
  }

  // Chroma locations, timing, optional one-entry HRDs, and all six bitstream restrictions.
  private static String extendedVui(boolean nalHrd, boolean vclHrd) {
    var hrd = "1" + "1" + "00000000" + "11" + "1" + "00001000100001100100";
    return "111"
        + "1"
        + "00000000000000000000001111101001"
        + "00000000000000001110101001100000"
        + "1"
        + (nalHrd ? hrd : "0")
        + (vclHrd ? hrd : "0")
        + "0"
        + "1"
        + "11"
        + "111111";
  }

  @Builder(toBuilder = true)
  private record Sps(
      boolean vui,
      boolean videoSignal,
      boolean colourDescription,
      int primaries,
      int transfer,
      int matrix,
      boolean fullRange,
      boolean extendedAspectAndOverscan,
      String remainingVui) {

    byte[] bytes() {
      // Baseline SPS: POC type 2, one reference frame, one progressive macroblock, no crop.
      var syntax = new StringBuilder("0110011101000010000000000001111011011010011110");
      syntax.append(vui ? '1' : '0');
      if (vui) {
        appendVui(syntax);
      }

      syntax.append('1');
      while (syntax.length() % 8 != 0) {
        syntax.append('0');
      }
      var bytes = new ByteArrayOutputStream();
      var zeros = 0;
      for (var position = 0; position < syntax.length(); position += 8) {
        var value = Integer.parseInt(syntax.substring(position, position + 8), 2);
        if (zeros == 2 && value <= 3) {
          bytes.write(3);
          zeros = 0;
        }
        bytes.write(value);
        zeros = value == 0 ? zeros + 1 : 0;
      }
      return bytes.toByteArray();
    }

    private void appendVui(StringBuilder syntax) {
      syntax.append(
          extendedAspectAndOverscan ? "1111111110000000000000001000000000000000111" : "00");
      syntax.append(videoSignal ? '1' : '0');
      if (videoSignal) {
        appendVideoSignal(syntax);
      }

      syntax.append(remainingVui);
    }

    private void appendVideoSignal(StringBuilder syntax) {
      syntax.append("101").append(fullRange ? '1' : '0').append(colourDescription ? '1' : '0');
      if (colourDescription) {
        syntax.append(bits(primaries)).append(bits(transfer)).append(bits(matrix));
      }
    }

    private static String bits(int value) {
      return String.format("%8s", Integer.toBinaryString(value)).replace(' ', '0');
    }

    @SuppressWarnings({"java:S1068", "java:S9438"})
    static class SpsBuilder {
      private String remainingVui = "000000";
      private int primaries = 2;
      private int transfer = 2;
      private int matrix = 2;
    }
  }
}
