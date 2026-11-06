package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.SyntheticSequenceHeader.ColourDescription;
import com.streamarr.transcode.engine.SyntheticSequenceHeader.OperatingPoint;
import com.streamarr.transcode.engine.SyntheticSequenceHeader.ScreenContentTools;
import java.nio.ByteBuffer;
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
 * The av1C fields of a sequence header: its second byte holds seq_profile and seq_level_idx[0], its
 * third seq_tier[0], high_bitdepth, twelve_bit, mono_chrome, subsampling_x, subsampling_y and
 * chroma_sample_position, from the most significant bit down.
 */
@Tag("UnitTest")
@DisplayName("AV1 Sequence Header Tests")
class Av1SequenceHeaderTest {

  private static final ColourDescription BT_709 = new ColourDescription(1, 1, 1);
  private static final ColourDescription SRGB = new ColourDescription(1, 13, 0);
  private static final String FRAME = "3203102030";

  @ParameterizedTest(name = "{0}")
  @MethodSource("headersAndTheirAv1CFields")
  @DisplayName("Should read the av1C fields when the sequence header declares them")
  void shouldReadTheAv1CFieldsWhenTheSequenceHeaderDeclaresThem(
      String description, SyntheticSequenceHeader header, String av1CFields) {
    var read = Av1SequenceHeader.firstIn(ByteBuffer.wrap(header.obu()));

    assertThat(fieldsOf(read)).isEqualTo(av1CFields);
  }

  static Stream<Arguments> headersAndTheirAv1CFields() {
    return Stream.of(
        Arguments.of("8-bit 4:2:0 at level 4.0", header().build(), "080c"),
        Arguments.of(
            "a reduced still picture header at level 3.1",
            header().reducedStillPictureHeader(true).operatingPoints(levelOf(5)).build(),
            "050c"),
        Arguments.of(
            "timing and decoder model info, display delays and a second operating point",
            header()
                .timingInfo(true)
                .decoderModel(true)
                .initialDisplayDelay(true)
                .operatingPoints(List.of(new OperatingPoint(12, 1), new OperatingPoint(4, 0)))
                .build(),
            "0c8c"),
        Arguments.of(
            "timing info without a decoder model",
            header().timingInfo(true).operatingPoints(levelOf(9)).build(),
            "090c"),
        Arguments.of(
            "frame ids, forced screen content tools and no order hint",
            header()
                .frameIdNumbers(true)
                .screenContentTools(ScreenContentTools.FORCED)
                .orderHint(false)
                .build(),
            "080c"),
        Arguments.of(
            "no screen content tools",
            header().screenContentTools(ScreenContentTools.OFF).build(),
            "080c"),
        Arguments.of(
            "10 bits with a BT.709 colour description and chroma sample position 1",
            header()
                .highBitDepth(true)
                .colourDescription(Optional.of(BT_709))
                .chromaSamplePosition(1)
                .build(),
            "084d"),
        Arguments.of(
            "monochrome with a colour description",
            header().monochrome(true).colourDescription(Optional.of(BT_709)).build(),
            "081c"),
        Arguments.of("profile 1, which is 4:4:4", header().profile(1).build(), "2800"),
        Arguments.of(
            "profile 2 at 10 bits, which is 4:2:2",
            header().profile(2).highBitDepth(true).build(),
            "4848"),
        Arguments.of(
            "profile 2 at 10 bits in sRGB, which is 4:4:4",
            header().profile(2).highBitDepth(true).colourDescription(Optional.of(SRGB)).build(),
            "4840"),
        Arguments.of(
            "profile 2 at 12 bits in 4:2:0 with chroma sample position 2",
            twelveBit().subsamplingX(1).subsamplingY(1).chromaSamplePosition(2).build(),
            "486e"),
        Arguments.of("profile 2 at 12 bits in 4:2:2", twelveBit().subsamplingX(1).build(), "4868"),
        Arguments.of("profile 2 at 12 bits in 4:4:4", twelveBit().build(), "4860"));
  }

  @Test
  @DisplayName(
      "Should read the first sequence header and keep its bytes when other OBUs come before it")
  void shouldReadTheFirstSequenceHeaderAndKeepItsBytesWhenOtherObusComeBeforeIt() {
    var first = header().extended(true).build().obu();
    var second = header().operatingPoints(levelOf(9)).build().obu();
    var sample = HexFormat.of().parseHex("1200" + hex(first) + FRAME + hex(second));

    var read = Av1SequenceHeader.firstIn(ByteBuffer.wrap(sample));

    assertThat(read.obu()).isEqualTo(first);
    assertThat(fieldsOf(read)).isEqualTo("080c");
  }

  @Test
  @DisplayName(
      "Should give the OBU a size field when the sample's sequence header runs to its end without"
          + " one")
  void shouldGiveTheObuASizeFieldWhenTheSequenceHeaderRunsToTheSampleEndWithoutOne() {
    var unsized = header().sized(false).build().obu();
    var sample = HexFormat.of().parseHex(FRAME + hex(unsized));

    var read = Av1SequenceHeader.firstIn(ByteBuffer.wrap(sample));

    assertThat(read.obu()).isEqualTo(header().build().obu());
  }

  @Test
  @DisplayName(
      "Should write a two-byte size field when the unsized sequence header's payload exceeds 127"
          + " bytes")
  void shouldWriteATwoByteSizeFieldWhenTheUnsizedPayloadExceeds127Bytes() {
    // A sequence header payload followed by trailing zero bytes, which the parser never reads.
    var payload = new byte[200];
    var minimal = header().sized(false).build().obu();
    System.arraycopy(minimal, 1, payload, 0, minimal.length - 1);
    var sample = ByteBuffer.allocate(201).put((byte) 0x08).put(payload).flip();

    var read = Av1SequenceHeader.firstIn(sample);

    assertThat(hex(read.obu())).startsWith("0ac801");
    assertThat(read.obu()).hasSize(203);
  }

  @Test
  @DisplayName("Should refuse as an output mismatch when the sample holds no sequence header")
  void shouldRefuseAsAnOutputMismatchWhenTheSampleHoldsNoSequenceHeader() {
    var sample = ByteBuffer.wrap(HexFormat.of().parseHex("1200" + FRAME));

    assertThatThrownBy(() -> Av1SequenceHeader.firstIn(sample))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.OUTPUT_MISMATCH));
  }

  @Test
  @DisplayName("Should refuse as a malformed box when the sequence header ends before color_config")
  void shouldRefuseAsAMalformedBoxWhenTheSequenceHeaderEndsBeforeColorConfig() {
    var whole = header().build().obu();
    // The OBU keeps its first 6 payload bytes, which end inside max_frame_width_minus_1.
    var truncated = new byte[8];
    truncated[0] = whole[0];
    truncated[1] = 6;
    System.arraycopy(whole, 2, truncated, 2, 6);

    assertThatThrownBy(() -> Av1SequenceHeader.firstIn(ByteBuffer.wrap(truncated)))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.MALFORMED_BOX));
  }

  private static SyntheticSequenceHeader.SyntheticSequenceHeaderBuilder header() {
    return SyntheticSequenceHeader.builder();
  }

  private static SyntheticSequenceHeader.SyntheticSequenceHeaderBuilder twelveBit() {
    return header().profile(2).highBitDepth(true).twelveBit(true);
  }

  private static List<OperatingPoint> levelOf(int level) {
    return List.of(new OperatingPoint(level, 0));
  }

  private static String fieldsOf(Av1SequenceHeader header) {
    return HexFormat.of()
        .formatHex(
            new byte[] {(byte) header.profileAndLevel(), (byte) header.tierDepthAndChroma()});
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
