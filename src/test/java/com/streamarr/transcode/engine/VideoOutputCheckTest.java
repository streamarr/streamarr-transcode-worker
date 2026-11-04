package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.InitializationSegmentFixtures.initializationSegment;
import static com.streamarr.transcode.engine.IsoBoxes.box;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Video Output Check Tests")
class VideoOutputCheckTest {

  // Main at level 3.0, 8-bit 4:2:0, with one VPS, SPS and PPS in the record.
  private static final String SYNTHETIC_HEVC_CODEC_STRING = "hvc1.1.6.L90.90";
  private static final String HVCC_FIELDS = "0101600000009000000000005AF000FCFDF8F800000F";
  private static final String HVCC_PARAMETER_SET_ARRAYS =
      "A0000100024001" + "A1000100024201" + "A2000100024401";
  private static final String HVCC = HVCC_FIELDS + "03" + HVCC_PARAMETER_SET_ARRAYS;

  @Test
  @DisplayName(
      "Should accept an encode when its initialization segment declares deinterlaced video")
  void shouldAcceptAnEncodeWhenItsInitializationSegmentDeclaresDeinterlacedVideo() {
    var encode = VideoDecisionFixtures.encodeBuilder().codecString("hvc1.1.6.L123.90").build();
    var segment = initializationSegment("libx265-1080i-deinterlaced.mp4");

    assertThatNoException().isThrownBy(() -> VideoOutputCheck.requireMatch(encode, segment));
  }

  // The server never copies an interlaced source, and a copy's sample entry repeats what the
  // source declared, so only an encode must declare progressive video.
  @Test
  @DisplayName("Should accept a copy when its initialization segment declares interlaced video")
  void shouldAcceptACopyWhenItsInitializationSegmentDeclaresInterlacedVideo() {
    var copy = VideoDecisionFixtures.copyOf("hvc1.1.6.L120.90");
    var segment = initializationSegment("libx265-1080i-woven.mp4");

    assertThatNoException().isThrownBy(() -> VideoOutputCheck.requireMatch(copy, segment));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"dvcC", "dvvC", "dvwC"})
  @DisplayName(
      "Should refuse the copy as an output mismatch when it strips Dolby Vision and its sample"
          + " entry still holds a Dolby Vision configuration record")
  void shouldRefuseTheCopyWhenItStripsDolbyVisionAndItsSampleEntryStillHoldsARecord(
      String recordType) {
    var copy = copyStrippingDolbyVision();
    var segment = hevcSegmentWith(box(recordType, new byte[24]));

    assertThatThrownBy(() -> VideoOutputCheck.requireMatch(copy, segment))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.OUTPUT_MISMATCH));
  }

  @Test
  @DisplayName(
      "Should accept the copy when it strips Dolby Vision and its sample entry holds no Dolby"
          + " Vision configuration record")
  void shouldAcceptTheCopyWhenItStripsDolbyVisionAndItsSampleEntryHoldsNoRecord() {
    var copy = copyStrippingDolbyVision();
    var segment = hevcSegmentWith(new byte[0]);

    assertThatNoException().isThrownBy(() -> VideoOutputCheck.requireMatch(copy, segment));
  }

  @Test
  @DisplayName(
      "Should accept a copy that keeps Dolby Vision when its sample entry holds the configuration"
          + " record")
  void shouldAcceptACopyThatKeepsDolbyVisionWhenItsSampleEntryHoldsTheRecord() {
    var copy = VideoDecisionFixtures.copyOf(SYNTHETIC_HEVC_CODEC_STRING);
    var segment = hevcSegmentWith(box("dvcC", new byte[24]));

    assertThatNoException().isThrownBy(() -> VideoOutputCheck.requireMatch(copy, segment));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordsWithOtherStaticMetadata")
  @DisplayName(
      "Should refuse the copy as an output mismatch when its hvc1 record holds other static HDR"
          + " metadata than the copy plans")
  void shouldRefuseTheCopyWhenItsRecordHoldsOtherStaticHdrMetadataThanTheCopyPlans(
      StaticMetadataCase staticMetadataCase) {
    var copy = copyPlanning(staticMetadataCase.planned());
    var segment = hevcSegmentWithSei(staticMetadataCase.seiUnits());

    assertThatThrownBy(() -> VideoOutputCheck.requireMatch(copy, segment))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.OUTPUT_MISMATCH));
  }

  static Stream<StaticMetadataCase> recordsWithOtherStaticMetadata() {
    var otherLevel = PlannedCopyOutputs.CONTENT_LIGHT_LEVEL_SEI.clone();
    otherLevel[5] = 0x01;
    return Stream.of(
        new StaticMetadataCase(
            "a planned message missing", both(), List.of(PlannedCopyOutputs.MASTERING_DISPLAY_SEI)),
        new StaticMetadataCase(
            "a message that the copy does not plan",
            PlannedCopyOutputs.pqOutputBuilder().build(),
            List.of(PlannedCopyOutputs.CONTENT_LIGHT_LEVEL_SEI)),
        new StaticMetadataCase(
            "a message with other values",
            PlannedCopyOutputs.pqOutputBuilder()
                .contentLightLevel(Optional.of(PlannedCopyOutputs.CONTENT_LIGHT_LEVEL))
                .build(),
            List.of(otherLevel)));
  }

  @Test
  @DisplayName(
      "Should accept the copy when its hvc1 record holds exactly the static HDR messages that it"
          + " plans")
  void shouldAcceptTheCopyWhenItsRecordHoldsExactlyThePlannedStaticHdrMessages() {
    var copy = copyPlanning(both());
    var segment =
        hevcSegmentWithSei(
            List.of(
                PlannedCopyOutputs.MASTERING_DISPLAY_SEI,
                PlannedCopyOutputs.CONTENT_LIGHT_LEVEL_SEI));

    assertThatNoException().isThrownBy(() -> VideoOutputCheck.requireMatch(copy, segment));
  }

  @ParameterizedTest(name = "{0} with transfer {1}")
  @CsvSource({"PQ, 18", "PQ, 1", "HLG, 16", "SDR, 16", "SDR, 18"})
  @DisplayName(
      "Should refuse the copy as an output mismatch when its colr box declares another transfer"
          + " than the planned range")
  void shouldRefuseTheCopyWhenItsColrBoxDeclaresAnotherTransferThanThePlannedRange(
      VideoDynamicRange.CompatibleBase base, int transfer) {
    var copy = copyPlanning(outputOn(base));
    var segment = hevcSegmentWith(colr(transfer));

    assertThatThrownBy(() -> VideoOutputCheck.requireMatch(copy, segment))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.OUTPUT_MISMATCH));
  }

  // BT.2100 PQ is transfer 16 and HLG is 18 (ITU-T H.273 table 3); NONE, the base of profile 5,
  // has no transfer that a player reads from colr.
  @ParameterizedTest(name = "{0} with transfer {1}")
  @CsvSource({"PQ, 16", "HLG, 18", "SDR, 1", "SDR, 2", "NONE, 16", "NONE, 2"})
  @DisplayName(
      "Should accept the copy when its colr box declares the transfer of the planned range")
  void shouldAcceptTheCopyWhenItsColrBoxDeclaresTheTransferOfThePlannedRange(
      VideoDynamicRange.CompatibleBase base, int transfer) {
    var copy = copyPlanning(outputOn(base));
    var segment = hevcSegmentWith(colr(transfer));

    assertThatNoException().isThrownBy(() -> VideoOutputCheck.requireMatch(copy, segment));
  }

  // FFmpeg writes no colr box for a source that signals no colour; the SPS then tells the player.
  @Test
  @DisplayName("Should accept a copy with a planned PQ output when it has no colr box")
  void shouldAcceptACopyWithAPlannedPqOutputWhenItHasNoColrBox() {
    var copy = copyPlanning(PlannedCopyOutputs.pqOutputBuilder().build());
    var segment = hevcSegmentWith(new byte[0]);

    assertThatNoException().isThrownBy(() -> VideoOutputCheck.requireMatch(copy, segment));
  }

  private static PlannedCopyOutput outputOn(VideoDynamicRange.CompatibleBase base) {
    return PlannedCopyOutput.builder()
        .dynamicRange(
            new VideoDynamicRange(
                base,
                VideoDynamicRange.Presence.ABSENT,
                new VideoDynamicRange.DolbyVision.Absent()))
        .build();
  }

  // An nclx colr box: BT.2020 primaries and matrix, the transfer, and limited range.
  private static byte[] colr(int transfer) {
    return box(
        "colr",
        IsoBoxes.fourcc("nclx"),
        IsoBoxes.u16(9),
        IsoBoxes.u16(transfer),
        IsoBoxes.u16(9),
        new byte[] {0});
  }

  // The planned output, and the SEI NAL units that the record holds in one prefix SEI array.
  record StaticMetadataCase(String name, PlannedCopyOutput planned, List<byte[]> seiUnits) {

    @Override
    public String toString() {
      return name;
    }
  }

  private static PlannedCopyOutput both() {
    return PlannedCopyOutputs.pqOutputBuilder()
        .masteringDisplay(Optional.of(PlannedCopyOutputs.MASTERING_DISPLAY))
        .contentLightLevel(Optional.of(PlannedCopyOutputs.CONTENT_LIGHT_LEVEL))
        .build();
  }

  private static VideoDecision.Copy copyPlanning(PlannedCopyOutput planned) {
    return VideoDecisionFixtures.copyBuilder()
        .codecString(SYNTHETIC_HEVC_CODEC_STRING)
        .plannedOutput(Optional.of(planned))
        .build();
  }

  private static InitializationSegment hevcSegmentWithSei(List<byte[]> seiUnits) {
    var array = new ByteArrayOutputStream();
    array.write(39);
    array.writeBytes(IsoBoxes.u16(seiUnits.size()));
    for (var unit : seiUnits) {
      array.writeBytes(IsoBoxes.u16(unit.length));
      array.writeBytes(unit);
    }

    var hvcC =
        IsoBoxes.concat(
            HexFormat.of().parseHex(HVCC_FIELDS + "04" + HVCC_PARAMETER_SET_ARRAYS),
            array.toByteArray());
    var sampleEntry = box("hvc1", new byte[78], box("hvcC", hvcC));
    return new InitializationSegment(HevcCopies.initializationSegment(sampleEntry));
  }

  private static VideoDecision.Copy copyStrippingDolbyVision() {
    return VideoDecisionFixtures.copyBuilder()
        .codecString(SYNTHETIC_HEVC_CODEC_STRING)
        .bitstreamRewrites(Set.of(BitstreamRewrite.STRIP_DOLBY_VISION))
        .build();
  }

  // An initialization segment whose hvc1 sample entry holds the record and then the given boxes.
  private static InitializationSegment hevcSegmentWith(byte[] boxesAfterTheRecord) {
    var sampleEntry =
        box("hvc1", new byte[78], box("hvcC", HexFormat.of().parseHex(HVCC)), boxesAfterTheRecord);
    return new InitializationSegment(HevcCopies.initializationSegment(sampleEntry));
  }
}
