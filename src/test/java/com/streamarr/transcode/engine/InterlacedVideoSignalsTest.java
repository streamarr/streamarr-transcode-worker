package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.InitializationSegmentFixtures.initializationSegment;
import static com.streamarr.transcode.engine.IsoBoxes.box;
import static com.streamarr.transcode.engine.IsoBoxes.concat;
import static com.streamarr.transcode.engine.IsoBoxes.ftyp;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Interlaced Video Signals Tests")
class InterlacedVideoSignalsTest {

  // The hvcC bytes before its array count, as CodecConfigurationReaderTest's HVCC_FIELDS, with the
  // constraint byte at offset 6 left for each test to fill.
  private static final String HVCC_BEFORE_CONSTRAINTS = "010160000000";
  private static final String HVCC_AFTER_CONSTRAINTS = "00000000005AF000FCFDF8F800000F00";
  private static final int FRAME_MACROBLOCKS_ONLY = 1;
  private static final int MAY_CODE_FIELDS = 0;

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "libx264-1080i-field-coded.mp4, true",
    "libx265-1080i-woven.mp4, true",
    "libsvtav1-1080i-woven.mp4, true",
    "libx265-1080i-deinterlaced.mp4, false",
    "libx264-1080p30.mp4, false",
    "libx264-high444.mp4, false",
    "libsvtav1-1080p30.mp4, false",
    "copy-hvc1.mp4, false"
  })
  @DisplayName(
      "Should declare interlaced video only when the pinned FFmpeg wrote interlaced video into the"
          + " initialization segment")
  void shouldDeclareInterlacedVideoOnlyWhenThePinnedFfmpegWroteInterlacedVideoIntoTheSegment(
      String file, boolean interlaced) {
    var sampleEntry =
        CodecConfigurationReader.firstVideoSampleEntryOf(initializationSegment(file)).orElseThrow();

    assertThat(InterlacedVideoSignals.declaresInterlacedVideo(sampleEntry)).isEqualTo(interlaced);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sequenceParameterSetLayouts")
  @DisplayName(
      "Should read frame_mbs_only_flag past every field before it when the SPS takes that layout")
  void shouldReadFrameMbsOnlyFlagPastEveryFieldBeforeItWhenTheSpsTakesThatLayout(
      String layout, UnaryOperator<Sps> fieldsBeforeTheFlag) {
    var mayCodeFields = fieldsBeforeTheFlag.apply(new Sps()).frameMbsOnly(MAY_CODE_FIELDS);
    var framesOnly = fieldsBeforeTheFlag.apply(new Sps()).frameMbsOnly(FRAME_MACROBLOCKS_ONLY);

    assertThat(declaresInterlacedVideo(avc1(mayCodeFields.nalUnit()))).isTrue();
    assertThat(declaresInterlacedVideo(avc1(framesOnly.nalUnit()))).isFalse();
  }

  static Stream<Arguments> sequenceParameterSetLayouts() {
    return Stream.of(
        Arguments.of(
            "Baseline with picture order count type 0",
            unary(sps -> sps.header(66).pictureOrderCount(0).dimensions(119, 67))),
        Arguments.of(
            "High with scaling lists, one of which ends early",
            unary(
                sps ->
                    sps.header(100)
                        .chromaFormat(1)
                        .scalingLists(8)
                        .pictureOrderCount(0)
                        .dimensions(119, 33))),
        Arguments.of(
            "High 4:4:4 with twelve scaling lists",
            unary(
                sps ->
                    sps.header(244)
                        .chromaFormat(3)
                        .scalingLists(12)
                        .pictureOrderCount(2)
                        .dimensions(119, 33))),
        Arguments.of(
            "picture order count type 1 with a reference cycle",
            unary(sps -> sps.header(77).pictureOrderCount(1).dimensions(44, 17))),
        Arguments.of(
            "picture order count type 2",
            unary(sps -> sps.header(66).pictureOrderCount(2).dimensions(44, 35))),
        // A width of 4096 macroblocks writes 24 zero bits in a row, which an emulation prevention
        // byte breaks before the flag.
        Arguments.of(
            "an emulation prevention byte before the flag",
            unary(sps -> sps.header(66).pictureOrderCount(2).dimensions(4095, 4095))));
  }

  @Test
  @DisplayName(
      "Should insert an emulation prevention byte before the flag in the SPS that tests it")
  void shouldInsertAnEmulationPreventionByteBeforeTheFlagInTheSpsThatTestsIt() {
    var sps = new Sps().header(66).pictureOrderCount(2).dimensions(4095, 4095).frameMbsOnly(0);

    assertThat(sps.nalUnit()).hasSizeGreaterThan(sps.rbsp().length);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        // The SPS ends inside its constraint flags.
        "6742",
        // The SPS ends before frame_mbs_only_flag.
        "6742000AF4",
        // seq_parameter_set_id begins with more than 31 zero bits.
        "6742000A0000000000"
      })
  @DisplayName("Should declare no interlaced video when the SPS ends before frame_mbs_only_flag")
  void shouldDeclareNoInterlacedVideoWhenTheSpsEndsBeforeFrameMbsOnlyFlag(String nalUnit) {
    assertThat(declaresInterlacedVideo(avc1(HexFormat.of().parseHex(nalUnit)))).isFalse();
  }

  @Test
  @DisplayName("Should declare no interlaced video when an avc3 carries its SPS in the stream")
  void shouldDeclareNoInterlacedVideoWhenAnAvc3CarriesItsSpsInTheStream() {
    var avcC = box("avcC", HexFormat.of().parseHex("0142000AFFE000"));

    assertThat(declaresInterlacedVideo(box("avc3", new byte[78], avcC))).isFalse();
  }

  @ParameterizedTest(name = "constraint byte {0}")
  @CsvSource({"D0, true", "50, true", "90, false", "B0, false"})
  @DisplayName("Should declare interlaced video when the hvcC sets general_interlaced_source_flag")
  void shouldDeclareInterlacedVideoWhenTheHvccSetsGeneralInterlacedSourceFlag(
      String constraintByte, boolean interlaced) {
    var hvcC =
        box(
            "hvcC",
            HexFormat.of()
                .parseHex(HVCC_BEFORE_CONSTRAINTS + constraintByte + HVCC_AFTER_CONSTRAINTS));

    assertThat(declaresInterlacedVideo(box("hvc1", new byte[78], hvcC))).isEqualTo(interlaced);
  }

  @Test
  @DisplayName(
      "Should declare interlaced video when a fiel box counts two fields whatever the codec")
  void shouldDeclareInterlacedVideoWhenAFielBoxCountsTwoFieldsWhateverTheCodec() {
    var twoFields = box("vp09", new byte[78], box("fiel", new byte[] {2, 9}));
    var oneField = box("vp09", new byte[78], box("fiel", new byte[] {1, 0}));

    assertThat(declaresInterlacedVideo(twoFields)).isTrue();
    assertThat(declaresInterlacedVideo(oneField)).isFalse();
  }

  private static byte[] avc1(byte[] spsNalUnit) {
    var avcC = new ByteArrayOutputStream();
    avcC.writeBytes(HexFormat.of().parseHex("0142000AFFE1"));
    avcC.write(spsNalUnit.length >> 8);
    avcC.write(spsNalUnit.length);
    avcC.writeBytes(spsNalUnit);
    avcC.writeBytes(HexFormat.of().parseHex("01000268CE"));
    return box("avc1", new byte[78], box("avcC", avcC.toByteArray()));
  }

  private static boolean declaresInterlacedVideo(byte[] sampleEntry) {
    var stsd = IsoBoxes.fullBox("stsd", 0, IsoBoxes.u32(1), sampleEntry);
    var hdlr =
        IsoBoxes.fullBox(
            "hdlr", 0, IsoBoxes.u32(0), IsoBoxes.fourcc("vide"), new byte[12], new byte[1]);
    var trak = box("trak", box("mdia", hdlr, box("minf", box("stbl", stsd))));
    var segment = new InitializationSegment(concat(ftyp(), box("moov", trak)));
    var entry = CodecConfigurationReader.firstVideoSampleEntryOf(segment).orElseThrow();
    return InterlacedVideoSignals.declaresInterlacedVideo(entry);
  }

  private static UnaryOperator<Sps> unary(UnaryOperator<Sps> operator) {
    return operator;
  }

  /** Writes the ITU-T H.264 7.3.2.1.1 fields that come before frame_mbs_only_flag. */
  static final class Sps {

    private final StringBuilder bits = new StringBuilder();

    Sps header(int profile) {
      return bits(8, 0x67).bits(8, profile).bits(16, 0x000A).unsignedExpGolomb(0);
    }

    Sps chromaFormat(int chromaFormat) {
      unsignedExpGolomb(chromaFormat);
      if (chromaFormat == 3) {
        bits(1, 0);
      }

      return unsignedExpGolomb(0).unsignedExpGolomb(0).bits(1, 0);
    }

    // Lists 0 and 6 run to their full length, list 1 ends after its first delta, and the last list
    // ends early too; every other list is absent.
    Sps scalingLists(int lists) {
      bits(1, 1);
      for (var list = 0; list < lists; list++) {
        var size = list < 6 ? 16 : 64;
        switch (list) {
          case 0, 6 -> fullScalingList(size);
          case 1 -> bits(1, 1).signedExpGolomb(-8);
          default -> scalingListPresentOnlyWhenLast(list == lists - 1);
        }
      }

      return this;
    }

    private void scalingListPresentOnlyWhenLast(boolean last) {
      if (!last) {
        bits(1, 0);
        return;
      }

      bits(1, 1).signedExpGolomb(3).signedExpGolomb(-11);
    }

    private void fullScalingList(int size) {
      bits(1, 1);
      for (var entry = 0; entry < size; entry++) {
        signedExpGolomb(entry % 2 == 0 ? 1 : -1);
      }
    }

    Sps pictureOrderCount(int type) {
      unsignedExpGolomb(0).unsignedExpGolomb(type);
      switch (type) {
        case 0 -> unsignedExpGolomb(2);
        case 1 ->
            bits(1, 0)
                .signedExpGolomb(-2)
                .signedExpGolomb(1)
                .unsignedExpGolomb(3)
                .signedExpGolomb(2)
                .signedExpGolomb(-1)
                .signedExpGolomb(4);
        default -> {
          // pic_order_cnt_type 2 carries no fields.
        }
      }

      return unsignedExpGolomb(4).bits(1, 0);
    }

    Sps dimensions(int widthInMacroblocksMinusOne, int heightInMapUnitsMinusOne) {
      return unsignedExpGolomb(widthInMacroblocksMinusOne)
          .unsignedExpGolomb(heightInMapUnitsMinusOne);
    }

    Sps frameMbsOnly(int flag) {
      return bits(1, flag);
    }

    byte[] rbsp() {
      var stopped = new StringBuilder(bits).append('1');
      while (stopped.length() % 8 != 0) {
        stopped.append('0');
      }

      var bytes = new byte[stopped.length() / 8];
      for (var index = 0; index < bytes.length; index++) {
        bytes[index] = (byte) Integer.parseInt(stopped.substring(index * 8, index * 8 + 8), 2);
      }

      return bytes;
    }

    // ITU-T H.264 7.4.1: an emulation prevention byte follows two zero bytes whenever the next
    // byte is 3 or less.
    byte[] nalUnit() {
      var nalUnit = new ByteArrayOutputStream();
      var zeros = 0;
      for (var value : rbsp()) {
        if (zeros >= 2 && (value & 0xFF) <= 3) {
          nalUnit.write(3);
          zeros = 0;
        }

        nalUnit.write(value);
        zeros = value == 0 ? zeros + 1 : 0;
      }

      return nalUnit.toByteArray();
    }

    private Sps bits(int count, int value) {
      for (var bit = count - 1; bit >= 0; bit--) {
        bits.append((value >> bit) & 1);
      }

      return this;
    }

    private Sps unsignedExpGolomb(int value) {
      var code = Integer.toBinaryString(value + 1);
      bits.append("0".repeat(code.length() - 1)).append(code);
      return this;
    }

    private Sps signedExpGolomb(int value) {
      return unsignedExpGolomb(value > 0 ? 2 * value - 1 : -2 * value);
    }
  }
}
