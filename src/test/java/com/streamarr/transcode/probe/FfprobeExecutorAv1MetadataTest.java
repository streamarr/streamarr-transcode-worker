package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.extradata;
import static com.streamarr.transcode.probe.FirstReads.frames;
import static com.streamarr.transcode.probe.FirstReads.output;
import static com.streamarr.transcode.probe.FirstReads.packets;
import static com.streamarr.transcode.probe.FirstReads.packetsUpTo;
import static com.streamarr.transcode.probe.FirstReads.withNewExtradata;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ChromaticityCoordinates;
import build.buf.gen.streamarr.transcode.v1.CompatibleBase;
import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuPlacement;
import build.buf.gen.streamarr.transcode.v1.MasteringDisplayColorVolume;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.VideoDynamicRange.DolbyVisionCase;
import com.streamarr.transcode.probe.RecordedFfprobe.Answers;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor AV1 Metadata Tests")
class FfprobeExecutorAv1MetadataTest {

  // An av1C record's four header bytes: marker and version, profile and level, the bit depth and
  // subsampling flags, and no initial presentation delay.
  private static final String AV1C = "81 00 0c 00";
  // A sequence header OBU, whose payload the walk does not read, as bare OBU extradata starts.
  private static final String SEQUENCE_HEADER = obu(1, "00 00 00");
  // A temporal delimiter, and a frame OBU whose payload the walk does not read.
  private static final String TEMPORAL_DELIMITER = "12 00";
  private static final String FRAME = obu(6, "10 00 80");
  private static final String HDR10_PLUS = metadata(4, "b5 00 3c 00 01 04 01 40");
  // The same ITU-T T.35 provider and code, but application identifier 5.
  private static final String OTHER_T35 = metadata(4, "b5 00 3c 00 01 05 01 40");
  private static final String RPU_PREFIX = "b5 00 3b 00 00 08 00";
  private static final long EMDF_HEADER = 0x01BE6841L;
  private static final long EMDF_PROTECTION = 0x400;
  private static final DolbyVisionRpuHeader PROFILE_10_0_HEADER =
      DolbyVisionRpuHeader.newBuilder()
          .setVdrRpuProfile(0)
          .setBaseLayerFullRange(true)
          .setVdrBitDepth(12)
          .setDisableResidual(true)
          .build();
  // Red, green and blue primaries and the D65 white point as fractions over 65536, then 1000 cd/m^2
  // over 256 and 0.005 cd/m^2 over 16384.
  private static final String MASTERING_DISPLAY_VALUES =
      hex(
          new Bits()
              .put(16, 44_564)
              .put(16, 20_972)
              .put(16, 17_367)
              .put(16, 45_220)
              .put(16, 9830)
              .put(16, 3932)
              .put(16, 20_493)
              .put(16, 21_561)
              .put(32, 256_000)
              .put(32, 82)
              .bytes());
  private static final String MASTERING_DISPLAY = metadata(2, MASTERING_DISPLAY_VALUES);
  private static final String LIGHT_LEVEL = metadata(1, "03 e8 01 90");
  private static final MasteringDisplayColorVolume CONVERTED_MASTERING_DISPLAY =
      MasteringDisplayColorVolume.newBuilder()
          .setRed(ChromaticityCoordinates.newBuilder().setX(34_000).setY(16_000))
          .setGreen(ChromaticityCoordinates.newBuilder().setX(13_250).setY(34_500))
          .setBlue(ChromaticityCoordinates.newBuilder().setX(7500).setY(3000))
          .setWhitePoint(ChromaticityCoordinates.newBuilder().setX(15_635).setY(16_450))
          .setMaxLuminance(10_000_000)
          .setMinLuminance(50)
          .build();

  @Nested
  @DisplayName("HDR10+")
  class Hdr10PlusTests {

    @Test
    @DisplayName("Should report HDR10+ present when a packet's metadata OBU holds it")
    void shouldReportHdr10PlusPresentWhenAPacketsMetadataObuHoldsIt() {
      // FFmpeg's AV1 decoders drop a whole frame whose HDR10+ message they cannot parse.
      var video = probed(AV1C, temporalUnit(OTHER_T35, HDR10_PLUS), temporalUnit());

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName("Should report HDR10+ present when the record's configOBUs hold it")
    void shouldReportHdr10PlusPresentWhenTheRecordsConfigObusHoldIt() {
      // FFmpeg's AV1 decoders never apply the record's metadata OBUs to the frames.
      var video = probed(av1cRecord(HDR10_PLUS), temporalUnit());

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName("Should report HDR10+ present when bare OBU extradata holds it")
    void shouldReportHdr10PlusPresentWhenBareObuExtradataHoldsIt() {
      var video = probed(SEQUENCE_HEADER + " " + HDR10_PLUS, temporalUnit());

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName("Should report HDR10+ present when its OBU lacks its trailing bits")
    void shouldReportHdr10PlusPresentWhenItsObuLacksItsTrailingBits() {
      // The OBU holds HDR10+ although the walk cannot delimit it.
      var video = probed(AV1C, temporalUnit(obu(5, "04 b5 00 3c 00 01 04 01 40")));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName("Should report HDR10+ absent when the walk delimits every OBU and none holds it")
    void shouldReportHdr10PlusAbsentWhenTheWalkDelimitsEveryObuAndNoneHoldsIt() {
      var video =
          probed(
              av1cRecord(metadata(3, "01 02")),
              temporalUnit(OTHER_T35),
              temporalUnit(LIGHT_LEVEL) + " " + obu(6, "10"));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName("Should report HDR10+ absent when the last OBU has no size field")
    void shouldReportHdr10PlusAbsentWhenTheLastObuHasNoSizeField() {
      // obu_has_size_field 0: the frame OBU runs to the end of its packet.
      var video = probed(AV1C, TEMPORAL_DELIMITER + " 30 10 00 00 80");

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName(
        "Should report HDR10+ absent when an OBU without a size field runs over the bytes after it")
    void shouldReportHdr10PlusAbsentWhenAnObuWithoutASizeFieldRunsOverTheBytesAfterIt() {
      // The frame OBU's payload takes the rest of the packet, the HDR10+ OBU's bytes included.
      var video = probed(AV1C, TEMPORAL_DELIMITER + " 30 " + HDR10_PLUS);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName(
        "Should report HDR10+ absent when a metadata OBU of a type that the probe does not read"
            + " lacks trailing bits")
    void
        shouldReportHdr10PlusAbsentWhenAMetadataObuOfATypeThatTheProbeDoesNotReadLacksTrailingBits() {
      // metadata_type 3, scalability: the walk needs only its metadata_type to end inside the OBU.
      var video = probed(AV1C, temporalUnit(obu(5, "03 01 02")));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName("Should leave HDR10+ unspecified when the probe reads no first frames")
    void shouldLeaveHdr10PlusUnspecifiedWhenTheProbeReadsNoFirstFrames() {
      // Without a frame read, the probe reads neither the extradata nor the packets.
      var video =
          probe(
                  answers(av1cRecord(HDR10_PLUS))
                      .firstPackets(packetsUpTo(3, temporalUnit()))
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isFalse();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource({
      "'12 00 32 05 10 00', an obu_size that runs past the packet",
      "'12 00 36', an extension byte past the packet",
      "'12 00 34', an extension byte past the packet of an OBU without a size field",
      "'12 00 32 80 80 80 80 80 80 80 80 00', an obu_size of 0 written in 9 bytes",
      "'12 00 2a 06 ff ff ff ff 7f 80', a metadata_type beyond 32 bits",
      "'12 00 2a 00', a metadata OBU without its metadata_type",
      "'12 00 2a 01 81 32 01 00', a metadata_type that runs past its OBU into the next",
      "'12 00 2a 03 01 00 00', an HDR_CLL OBU without its trailing bits",
      "'12 00 2a 03 04 b5 81', a T.35 OBU whose last nonzero byte is not 0x80"
    })
    @DisplayName("Should rule nothing out when the walk cannot delimit an OBU")
    void shouldRuleNothingOutWhenTheWalkCannotDelimitAnObu(String packet, String reason) {
      // Every other packet that the read asks for is a whole temporal unit, so only the named OBU
      // can leave the walk incomplete.
      var video = probed(AV1C, packet);

      assertThat(video.getDynamicRange().getHdr10Plus())
          .as(reason)
          .isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .as(reason)
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
      assertThat(video.getMasteringDisplayOmitted()).as(reason).isTrue();
      assertThat(video.getContentLightLevelOmitted()).as(reason).isTrue();
    }

    @Test
    @DisplayName("Should leave HDR10+ unspecified when an OBU with an extension byte holds it")
    void shouldLeaveHdr10PlusUnspecifiedWhenAnObuWithAnExtensionByteHoldsIt() {
      // FFmpeg's AV1 decoders skip such an OBU when their operating point leaves out its layer.
      var video = probed(AV1C, temporalUnit(layered(4, "b5 00 3c 00 01 04 01 40")));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getMasteringDisplayOmitted()).as("the walk read every OBU").isFalse();
    }

    @Test
    @DisplayName(
        "Should rule out HDR10+ and Dolby Vision when bare OBU extradata and the packets hold"
            + " neither")
    void shouldRuleOutHdr10PlusAndDolbyVisionWhenBareObuExtradataAndThePacketsHoldNeither() {
      // FFmpeg gives an AV1 stream in IVF or a raw .obu file its sequence header and global
      // metadata OBUs as extradata.
      var video = probed(SEQUENCE_HEADER + " " + LIGHT_LEVEL, temporalUnit());

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "a high bit that names neither form, 82 00 0c 00",
      "an av1C marker without the record's 4 bytes, 81 00 0c",
      "a bare OBU whose size runs past the extradata, 0a 05 00 00"
    })
    @DisplayName("Should rule nothing out when the walk cannot read the extradata")
    void shouldRuleNothingOutWhenTheWalkCannotReadTheExtradata(
        String description, String extradata) {
      var video = probed(extradata, temporalUnit());

      assertThat(video.getDynamicRange().getHdr10Plus())
          .as(description)
          .isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).as(description).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).as(description).isTrue();
    }

    @Test
    @DisplayName("Should leave HDR10+ unspecified when a packet carries a replacement record")
    void shouldLeaveHdr10PlusUnspecifiedWhenAPacketCarriesAReplacementRecord() {
      var video =
          probe(
                  answers(AV1C)
                      .firstFrames(frames("", "", ""))
                      .firstPackets(withNewExtradata(packetsUpTo(3, temporalUnit()), 1))
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName("Should leave HDR10+ unspecified when the packet read fails")
    void shouldLeaveHdr10PlusUnspecifiedWhenThePacketReadFails() {
      var video = probe(answers(AV1C).firstFrames(frames("", "", "")).build()).getStreams(0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
    }

    @Test
    @DisplayName("Should leave HDR10+ unspecified when a failed read cuts the frames short")
    void shouldLeaveHdr10PlusUnspecifiedWhenAFailedReadCutsTheFramesShort() {
      var video =
          probe(
                  answers(AV1C)
                      .firstFrames(frames(""))
                      .failingReadsBeyondPackets(3)
                      .firstPackets(packetsUpTo(3, temporalUnit()))
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }
  }

  @Nested
  @DisplayName("Dolby Vision")
  class DolbyVisionTests {

    @Test
    @DisplayName("Should report the walk's RPU and its header when the frames show none")
    void shouldReportTheWalksRpuAndItsHeaderWhenTheFramesShowNone() {
      var video = probed(AV1C, temporalUnit(rpuObu(profile100Emdf().build())));

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getFirstRpuHeader()).isEqualTo(PROFILE_10_0_HEADER);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
    }

    @Test
    @DisplayName(
        "Should report no compatible base when the walk's RPU header has profile 10.0's fields on"
            + " a PQ stream")
    void shouldReportNoCompatibleBaseWhenTheWalksRpuHeaderHasProfile100sFieldsOnAPqStream() {
      // A PQ transfer alone gives a PQ base, so only the profile 10.0 rule can give none.
      var video =
          probe(
                  answersWithFields("\"color_transfer\":\"smpte2084\"," + extradata(AV1C))
                      .firstFrames(frames("", "", ""))
                      .firstPackets(
                          packetsUpTo(
                              3, temporalUnit(), temporalUnit(rpuObu(profile100Emdf().build()))))
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    @Test
    @DisplayName(
        "Should read the RPU header when its EMDF payload size takes a second group of bits")
    void shouldReadTheRpuHeaderWhenItsEmdfPayloadSizeTakesASecondGroupOfBits() {
      // variable_bits(8) reads the groups 1 and 0 as (1 + 1) * 256 + 0: the largest payload that
      // FFmpeg opens.
      var container =
          profile100Emdf().rpu(profile100RpuOf(512)).payloadSizeGroups(List.of(1, 0)).build();

      var video = probed(AV1C, temporalUnit(rpuObu(container)));

      assertThat(video.getDynamicRange().getDolbyVisionPresent().getFirstRpuHeader())
          .isEqualTo(PROFILE_10_0_HEADER);
    }

    static Stream<Named<String>> containersThatFfmpegCannotOpen() {
      return Stream.of(
          Named.of("a header other than EMDF's", profile100Emdf().header(0x01BE6840).build().hex()),
          Named.of(
              "a payload of 513 bytes",
              profile100Emdf()
                  .rpu(profile100RpuOf(513))
                  .payloadSizeGroups(List.of(1, 1))
                  .build()
                  .hex()),
          // Without its bound, the size would wrap past 64 bits to the RPU's 12 bytes.
          Named.of(
              "a payload size of more than 64 bits",
              profile100Emdf()
                  .payloadSizeGroups(List.of(0xFE, 0xFE, 0xFE, 0xFE, 0xFE, 0xFE, 0xFE, 0xFF, 12))
                  .build()
                  .hex()),
          Named.of(
              "an emdf_protection other than 0x400",
              profile100Emdf().protection(0x401).build().hex()),
          Named.of(
              "a container that ends inside its header",
              profile100Emdf().build().hex().substring(0, 8)));
    }

    @ParameterizedTest
    @MethodSource("containersThatFfmpegCannotOpen")
    @DisplayName(
        "Should report the RPU without a header when FFmpeg cannot open its EMDF container")
    void shouldReportTheRpuWithoutAHeaderWhenFfmpegCannotOpenItsEmdfContainer(String container) {
      // FFmpeg drops an RPU whose container it cannot open.
      var video = probed(AV1C, temporalUnit(metadata(4, RPU_PREFIX + " " + container)));

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.hasFirstRpuHeader()).isFalse();
    }

    @Test
    @DisplayName("Should report the RPU and its header when bare OBU extradata holds it")
    void shouldReportTheRpuAndItsHeaderWhenBareObuExtradataHoldsIt() {
      var video = probed(SEQUENCE_HEADER + " " + rpuObu(profile100Emdf().build()), temporalUnit());

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getFirstRpuHeader()).isEqualTo(PROFILE_10_0_HEADER);
    }

    @Test
    @DisplayName(
        "Should take the record's RPU header when the record and a packet both hold an RPU")
    void shouldTakeTheRecordsRpuHeaderWhenTheRecordAndAPacketBothHoldAnRpu() {
      var video =
          probed(
              av1cRecord(rpuObu(profile100Emdf().build())),
              temporalUnit(rpuObu(profile100Emdf().rpu(rpuWithProfile(1)).build())));

      assertThat(video.getDynamicRange().getDolbyVisionPresent().getFirstRpuHeader())
          .isEqualTo(PROFILE_10_0_HEADER);
    }

    @Test
    @DisplayName("Should read the RPU header when the record names another Dolby Vision profile")
    void shouldReadTheRpuHeaderWhenTheRecordNamesAnotherDolbyVisionProfile() {
      // FFmpeg opens the EMDF container only without a record or with one that names profile 10.
      var video =
          probe(
                  answersWithFields(
                          """
                          "side_data_list":[{"side_data_type":"DOVI configuration record",
                            "dv_profile":8,"dv_bl_signal_compatibility_id":1}],"""
                              + extradata(AV1C))
                      .firstFrames(frames("", "", ""))
                      .firstPackets(
                          packetsUpTo(
                              3, temporalUnit(), temporalUnit(rpuObu(profile100Emdf().build()))))
                      .build())
              .getStreams(0);

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getDeclared().getProfile()).isEqualTo(8);
      assertThat(present.getFirstRpuHeader()).isEqualTo(PROFILE_10_0_HEADER);
    }

    @Test
    @DisplayName("Should report the RPU when its OBU lacks its trailing bits")
    void shouldReportTheRpuWhenItsObuLacksItsTrailingBits() {
      // A last byte of 1 leaves the OBU without trailing bits.
      var video =
          probed(
              AV1C,
              temporalUnit(
                  obu(5, "04 " + RPU_PREFIX + " " + profile100Emdf().build().hex() + " 01")));

      assertThat(video.getDynamicRange().getDolbyVisionPresent().getObservedRpu())
          .isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName(
        "Should take no RPU header from an OBU with an extension byte when another OBU holds the"
            + " RPU")
    void shouldTakeNoRpuHeaderFromAnObuWithAnExtensionByteWhenAnotherObuHoldsTheRpu() {
      // The OBU with an extension byte comes first and holds a header that FFmpeg could read.
      var video =
          probed(
              AV1C,
              temporalUnit(
                  layered(4, RPU_PREFIX + " " + profile100Emdf().build().hex()),
                  rpuObu(profile100Emdf().header(0x01BE6840).build())));

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.hasFirstRpuHeader()).isFalse();
    }

    @Test
    @DisplayName(
        "Should report Dolby Vision absent when a T.35 OBU differs from an RPU's identifier only"
            + " in its last byte")
    void shouldReportDolbyVisionAbsentWhenAT35ObuDiffersFromAnRpusIdentifierOnlyInItsLastByte() {
      var video =
          probed(
              AV1C,
              temporalUnit(metadata(4, "b5 00 3b 00 00 08 01 " + profile100Emdf().build().hex())));

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
    }

    @Test
    @DisplayName(
        "Should report Dolby Vision absent when the walk delimits every OBU and finds none")
    void shouldReportDolbyVisionAbsentWhenTheWalkDelimitsEveryObuAndFindsNone() {
      var video = probed(AV1C, temporalUnit(OTHER_T35), temporalUnit());

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
    }

    @Test
    @DisplayName(
        "Should leave Dolby Vision unspecified when an OBU with an extension byte holds an RPU")
    void shouldLeaveDolbyVisionUnspecifiedWhenAnObuWithAnExtensionByteHoldsAnRpu() {
      var video =
          probed(AV1C, temporalUnit(layered(4, RPU_PREFIX + " " + profile100Emdf().build().hex())));

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
      assertThat(video.getDynamicRange().getHdr10Plus())
          .as("the walk read every OBU")
          .isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName("Should keep the frames' RPU header when the frames show an RPU")
    void shouldKeepTheFramesRpuHeaderWhenTheFramesShowAnRpu() {
      var video =
          probe(
                  answers(AV1C)
                      .firstFrames(
                          frames(
                              """
                              {"side_data_type":"Dolby Vision Metadata","vdr_rpu_profile":1,
                               "bl_video_full_range_flag":0,"vdr_bit_depth":10,
                               "el_spatial_resampling_filter_flag":0,"disable_residual_flag":1}"""))
                      .firstPackets(packets(temporalUnit(rpuObu(profile100Emdf().build()))))
                      .build())
              .getStreams(0);

      var header = video.getDynamicRange().getDolbyVisionPresent().getFirstRpuHeader();
      assertThat(header.getVdrRpuProfile()).isEqualTo(1);
      assertThat(header.getVdrBitDepth()).isEqualTo(10);
    }
  }

  @Nested
  @DisplayName("Static HDR Metadata")
  class StaticHdrMetadataTests {

    @Test
    @DisplayName("Should mark both static values omitted when a cut-short read's OBUs hold neither")
    void shouldMarkBothStaticValuesOmittedWhenACutShortReadsObusHoldNeither() {
      var video =
          probe(
                  answers(AV1C)
                      .firstFrames(frames(""))
                      .firstPackets(packets(temporalUnit(), temporalUnit(), temporalUnit()))
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @Test
    @DisplayName("Should convert the first metadata OBUs' values when the first frame has none")
    void shouldConvertTheFirstMetadataObusValuesWhenTheFirstFrameHasNone() {
      // A static OBU after the last kept frame's OBUs reaches no frame.
      var video =
          probed(
              AV1C,
              temporalUnit() + " " + MASTERING_DISPLAY + " " + LIGHT_LEVEL,
              temporalUnit(metadata(1, "00 64 00 32")));

      assertThat(video.getMasteringDisplay()).isEqualTo(CONVERTED_MASTERING_DISPLAY);
      assertThat(video.getContentLightLevel())
          .isEqualTo(
              ContentLightLevel.newBuilder()
                  .setMaxContentLightLevel(1000)
                  .setMaxFrameAverageLightLevel(400)
                  .build());
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName("Should round an AV1 fraction half up when it lies halfway between two units")
    void shouldRoundAnAv1FractionHalfUpWhenItLiesHalfwayBetweenTwoUnits() {
      // 2048/65536 is 1562.5 coordinate units, 8/256 and 512/16384 cd/m^2 are 312.5 luminance
      // units.
      var halfway = new Bits();
      for (var coordinate = 0; coordinate < 8; coordinate++) {
        halfway.put(16, 2048);
      }

      var video =
          probed(AV1C, temporalUnit(metadata(2, hex(halfway.put(32, 8).put(32, 512).bytes()))));

      var coordinates = ChromaticityCoordinates.newBuilder().setX(1563).setY(1563);
      assertThat(video.getMasteringDisplay())
          .isEqualTo(
              MasteringDisplayColorVolume.newBuilder()
                  .setRed(coordinates)
                  .setGreen(coordinates)
                  .setBlue(coordinates)
                  .setWhitePoint(coordinates)
                  .setMaxLuminance(313)
                  .setMinLuminance(313)
                  .build());
    }

    @Test
    @DisplayName(
        "Should take the record's content light level when the record and a packet both hold one")
    void shouldTakeTheRecordsContentLightLevelWhenTheRecordAndAPacketBothHoldOne() {
      var video = probed(av1cRecord(LIGHT_LEVEL), temporalUnit(metadata(1, "00 64 00 32")));

      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
    }

    @Test
    @DisplayName("Should read the content light level when bare OBU extradata holds it")
    void shouldReadTheContentLightLevelWhenBareObuExtradataHoldsIt() {
      var video = probed(SEQUENCE_HEADER + " " + LIGHT_LEVEL, temporalUnit());

      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(video.getContentLightLevelOmitted()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource({
      "'00 01 02 03 04 05 06 07 08 09', too short",
      "'ad f4 51 ec 43 d7 b0 a4 26 66 0f 5c 50 0d 54 39 ff ff ff ff 00 00 00 52',"
          + " a maximum luminance beyond the SEI field"
    })
    @DisplayName("Should mark the mastering display omitted when its OBU cannot be reported")
    void shouldMarkTheMasteringDisplayOmittedWhenItsObuCannotBeReported(
        String values, String reason) {
      var video = probed(AV1C, temporalUnit(metadata(2, values), LIGHT_LEVEL));

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
    }

    @Test
    @DisplayName(
        "Should mark only the content light level omitted when its OBU has an extension byte")
    void shouldMarkOnlyTheContentLightLevelOmittedWhenItsObuHasAnExtensionByte() {
      var video = probed(AV1C, temporalUnit(layered(1, "03 e8 01 90")));

      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
    }

    @Test
    @DisplayName(
        "Should mark only the mastering display omitted when its OBU has an extension byte")
    void shouldMarkOnlyTheMasteringDisplayOmittedWhenItsObuHasAnExtensionByte() {
      var video = probed(AV1C, temporalUnit(layered(2, MASTERING_DISPLAY_VALUES)));

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName("Should read the content light level when zero bytes follow its trailing bits")
    void shouldReadTheContentLightLevelWhenZeroBytesFollowItsTrailingBits() {
      var video = probed(AV1C, temporalUnit(obu(5, "01 03 e8 01 90 80 00 00")));

      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName("Should mark the content light level omitted when its OBU lacks its trailing bits")
    void shouldMarkTheContentLightLevelOmittedWhenItsObuLacksItsTrailingBits() {
      // Its 4 values would convert, but the OBU does not end where they do.
      var video = probed(AV1C, temporalUnit(obu(5, "01 03 e8 01 90")));

      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @Test
    @DisplayName(
        "Should leave the content light level absent and unflagged when the probe reads no first"
            + " frames")
    void shouldLeaveTheContentLightLevelAbsentAndUnflaggedWhenTheProbeReadsNoFirstFrames() {
      var video =
          probe(
                  answers(av1cRecord(LIGHT_LEVEL))
                      .firstPackets(packetsUpTo(3, temporalUnit()))
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isFalse();
      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName("Should leave both values absent and unflagged when no OBU holds them")
    void shouldLeaveBothValuesAbsentAndUnflaggedWhenNoObuHoldsThem() {
      var video = probed(AV1C, temporalUnit(OTHER_T35), temporalUnit());

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }
  }

  @Nested
  @DisplayName("Recorded Sources")
  class RecordedSourceTests {

    @Test
    @DisplayName(
        "Should convert the first frame's static metadata to SEI units when a libaom stream"
            + " carries it in every frame")
    void
        shouldConvertTheFirstFramesStaticMetadataToSeiUnitsWhenALibaomStreamCarriesItInEveryFrame() {
      // FFmpeg's AV1 decoders give the HDR_MDCV values as fractions: red_x as 6554/65536, the
      // maximum luminance as 512/256 and the minimum as 16384/16384.
      var video = streamOf("ivf-av1-hdr-sizeup.ivf", 0);

      assertThat(video.getMasteringDisplay())
          .isEqualTo(
              MasteringDisplayColorVolume.newBuilder()
                  .setRed(ChromaticityCoordinates.newBuilder().setX(5000).setY(10_000))
                  .setGreen(ChromaticityCoordinates.newBuilder().setX(15_000).setY(20_000))
                  .setBlue(ChromaticityCoordinates.newBuilder().setX(25_000).setY(30_000))
                  .setWhitePoint(ChromaticityCoordinates.newBuilder().setX(35_000).setY(40_000))
                  .setMaxLuminance(20_000)
                  .setMinLuminance(10_000)
                  .build());
      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(11_122);
      assertThat(video.getContentLightLevel().getMaxFrameAverageLightLevel()).isEqualTo(22_211);
    }

    @Test
    @DisplayName(
        "Should rule out HDR10+ and Dolby Vision when a libaom stream's OBUs hold only static"
            + " metadata")
    void shouldRuleOutHdr10PlusAndDolbyVisionWhenALibaomStreamsObusHoldOnlyStaticMetadata() {
      var video = streamOf("ivf-av1-hdr-sizeup.ivf", 0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ivf-av1-10bit.ivf", "obu-av1-10bit.obu"})
    @DisplayName(
        "Should rule out HDR10+, Dolby Vision and static metadata when a stream's bare OBU"
            + " extradata and packets hold none")
    void
        shouldRuleOutHdr10PlusDolbyVisionAndStaticMetadataWhenAStreamsBareObuExtradataAndPacketsHoldNone(
            String fixture) {
      // FFmpeg gives the stream in IVF or in a raw .obu file its sequence header OBU as extradata.
      var video = streamOf(fixture, 0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName("Should rule out HDR10+, Dolby Vision and static metadata when no OBU holds them")
    void shouldRuleOutHdr10PlusDolbyVisionAndStaticMetadataWhenNoObuHoldsThem() {
      var video = streamOf("mp4-av1-pq.mp4", 0);

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_PQ);
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName(
        "Should report HDR10+ present when FFmpeg drops the frame of a malformed HDR10+ OBU")
    void shouldReportHdr10PlusPresentWhenFfmpegDropsTheFrameOfAMalformedHdr10PlusObu() {
      var video = streamOf("mp4-av1-hdr10-plus-rejected.mp4", 0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName(
        "Should read the static metadata from the OBUs when only frames after the first carry it")
    void shouldReadTheStaticMetadataFromTheObusWhenOnlyFramesAfterTheFirstCarryIt() {
      var video = streamOf("mp4-av1-static-after-frame.mp4", 0);

      assertThat(video.getMasteringDisplay()).isEqualTo(CONVERTED_MASTERING_DISPLAY);
      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(video.getContentLightLevel().getMaxFrameAverageLightLevel()).isEqualTo(400);
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName(
        "Should report no compatible base when the frames' RPU header names profile 10.0 on a PQ"
            + " stream")
    void shouldReportNoCompatibleBaseWhenTheFramesRpuHeaderNamesProfile100OnAPqStream() {
      var video = streamOf("mp4-av1-rpu.mp4", 0);

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getFirstRpuHeader().getVdrRpuProfile()).isZero();
      assertThat(present.getFirstRpuHeader().getBaseLayerFullRange()).isTrue();
      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    @Test
    @DisplayName("Should report the RPU without a header when FFmpeg rejects its EMDF container")
    void shouldReportTheRpuWithoutAHeaderWhenFfmpegRejectsItsEmdfContainer() {
      var video = streamOf("mp4-av1-rpu-bad-emdf.mp4", 0);

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.hasFirstRpuHeader()).isFalse();
    }
  }

  private static final String PROFILE_10_0_RPU = rpuWithProfile(0);

  /**
   * An RPU whose header holds rpu_type 2, rpu_format 0, the vdr_rpu_profile, vdr_rpu_level 0,
   * sequence information with fixed-point coefficients of log2 denominator 23, the normalized idc
   * 1, a full-range base layer, base and enhancement bit depths of 10, a VDR bit depth of 12, then
   * disable_residual_flag 1, padded to the 6 bytes that FFmpeg's container needs at least.
   */
  private static String rpuWithProfile(int vdrRpuProfile) {
    return hex(
        new Bits()
            .put(6, 2)
            .put(11, 0)
            .put(4, vdrRpuProfile)
            .put(4, 0)
            .put(1, 1)
            .put(1, 0)
            .put(2, 0)
            .put(9, 0b000011000)
            .put(2, 1)
            .put(1, 1)
            .put(3, 0b011)
            .put(3, 0b011)
            .put(5, 0b00101)
            .put(4, 0)
            .put(1, 0)
            .put(1, 1)
            .put(32, 0)
            .bytes());
  }

  /**
   * The stream, when its 3 first frames carry no side data and its 3 first packets hold the
   * temporal units, then temporal units of a frame alone.
   */
  private static ProbeStreamInfo probed(String av1cRecord, String... packets) {
    return probe(
            answers(av1cRecord)
                .firstFrames(frames("", "", ""))
                .firstPackets(packetsUpTo(3, temporalUnit(), packets))
                .build())
        .getStreams(0);
  }

  private static Answers.AnswersBuilder answers(String extradata) {
    return answersWithFields(extradata(extradata));
  }

  /** The answers for an AV1 stream with the given fields. */
  private static Answers.AnswersBuilder answersWithFields(String fields) {
    return Answers.builder().ffprobeOutput(output("\"codec_name\":\"av1\"," + fields));
  }

  /** An av1C record whose configOBUs are the given OBUs. */
  private static String av1cRecord(String... obus) {
    return AV1C + " " + String.join(" ", obus);
  }

  /** A temporal delimiter, the given OBUs, then a frame. */
  private static String temporalUnit(String... obus) {
    return (TEMPORAL_DELIMITER + " " + String.join(" ", obus) + " " + FRAME).replaceAll(" +", " ");
  }

  /** An OBU of the type with a size field. */
  private static String obu(int type, String payload) {
    var length = HexFormat.ofDelimiter(" ").parseHex(payload).length;
    return "%02x %s %s".formatted((type << 3) | 0x02, leb128(length), payload).trim();
  }

  /** A metadata OBU of the type, whose values the trailing bits end. */
  private static String metadata(int metadataType, String values) {
    return obu(5, "%02x %s 80".formatted(metadataType, values));
  }

  /** A metadata OBU with an extension byte that names temporal and spatial layer 0. */
  private static String layered(int metadataType, String values) {
    var payload = "%02x %s 80".formatted(metadataType, values);
    var length = HexFormat.ofDelimiter(" ").parseHex(payload).length;
    return "2e 00 %s %s".formatted(leb128(length), payload);
  }

  /**
   * The value in leb128: 7 bits a byte, least significant first, the high bit set on all but the
   * last.
   */
  private static String leb128(int value) {
    var bytes = new StringBuilder();
    var rest = value;
    while (rest >= 0x80) {
      bytes.append("%02x ".formatted((rest & 0x7F) | 0x80));
      rest >>= 7;
    }

    return bytes.append("%02x".formatted(rest)).toString();
  }

  /** The profile 10.0 RPU, padded with zero bytes to the length. */
  private static String profile100RpuOf(int length) {
    var padding = length - HexFormat.ofDelimiter(" ").parseHex(PROFILE_10_0_RPU).length;
    return PROFILE_10_0_RPU + " 00".repeat(padding);
  }

  /** A profile 10.0 RPU in the EMDF container that FFmpeg opens. */
  private static EmdfContainer.EmdfContainerBuilder profile100Emdf() {
    return EmdfContainer.builder()
        .header(EMDF_HEADER)
        .rpu(PROFILE_10_0_RPU)
        .protection(EMDF_PROTECTION);
  }

  /** An RPU metadata OBU, whose values the trailing bits end. */
  private static String rpuObu(EmdfContainer container) {
    return metadata(4, RPU_PREFIX + " " + container.hex());
  }

  /**
   * An RPU in an EMDF container: the 27-bit header, emdf_payload_size as the groups of
   * variable_bits(8), by default the RPU's length in one group, the RPU's bytes, and the 17-bit
   * emdf_protection.
   */
  @Builder
  private record EmdfContainer(
      long header, @NonNull String rpu, List<Integer> payloadSizeGroups, long protection) {

    String hex() {
      var bytes = HexFormat.ofDelimiter(" ").parseHex(rpu);
      var groups = payloadSizeGroups;
      if (groups == null) {
        groups = List.of(bytes.length);
      }

      var bits = new Bits().put(27, header);
      for (var index = 0; index < groups.size(); index++) {
        var readMore = index < groups.size() - 1;
        bits.put(8, groups.get(index)).put(1, readMore ? 1 : 0);
      }

      for (var value : bytes) {
        bits.put(8, Byte.toUnsignedInt(value));
      }

      return FfprobeExecutorAv1MetadataTest.hex(bits.put(17, protection).bytes());
    }
  }

  private static String hex(byte[] bytes) {
    return HexFormat.ofDelimiter(" ").formatHex(bytes);
  }

  /** Writes fields of up to 32 bits, most significant bit first, and pads the last byte. */
  private static final class Bits {

    private final StringBuilder bits = new StringBuilder();

    Bits put(int count, long value) {
      for (var bit = count - 1; bit >= 0; bit--) {
        bits.append((value >> bit) & 1);
      }

      return this;
    }

    byte[] bytes() {
      while (bits.length() % 8 != 0) {
        bits.append('0');
      }

      var bytes = new byte[bits.length() / 8];
      for (var index = 0; index < bytes.length; index++) {
        bytes[index] = (byte) Integer.parseInt(bits.substring(index * 8, index * 8 + 8), 2);
      }

      return bytes;
    }
  }
}
