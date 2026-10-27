package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.extradata;
import static com.streamarr.transcode.probe.FirstReads.frames;
import static com.streamarr.transcode.probe.FirstReads.output;
import static com.streamarr.transcode.probe.FirstReads.packets;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor AV1 Metadata Tests")
class FfprobeExecutorAv1MetadataTest {

  // An av1C record's four header bytes: marker and version, profile and level, the bit depth and
  // subsampling flags, and no initial presentation delay.
  private static final String AV1C = "81 00 0c 00";
  // A temporal delimiter, and a frame OBU whose payload the walk does not read.
  private static final String TEMPORAL_DELIMITER = "12 00";
  private static final String FRAME = obu(6, "10 00 80");
  private static final String HDR10_PLUS = metadata(4, "b5 00 3c 00 01 04 01 40");
  // The same ITU-T T.35 provider and code, but application identifier 5.
  private static final String OTHER_T35 = metadata(4, "b5 00 3c 00 01 05 01 40");
  private static final String RPU_PREFIX = "b5 00 3b 00 00 08 00";
  private static final DolbyVisionRpuHeader PROFILE_10_0_HEADER =
      DolbyVisionRpuHeader.newBuilder()
          .setVdrRpuProfile(0)
          .setBaseLayerFullRange(true)
          .setVdrBitDepth(12)
          .setDisableResidual(true)
          .build();
  // Red, green and blue primaries and the D65 white point as fractions over 65536, then 1000 cd/m^2
  // over 256 and 0.005 cd/m^2 over 16384.
  private static final String MASTERING_DISPLAY =
      metadata(
          2,
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
                  .bytes()));
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
      var video = probed(record(HDR10_PLUS), temporalUnit());

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName("Should report HDR10+ absent when the walk delimits every OBU and none holds it")
    void shouldReportHdr10PlusAbsentWhenTheWalkDelimitsEveryObuAndNoneHoldsIt() {
      var video =
          probed(
              record(metadata(3, "01 02")),
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

    @ParameterizedTest(name = "{1}")
    @CsvSource({
      "'12 00 32 05 10 00', an obu_size that runs past the packet",
      "'12 00 36', an extension byte past the packet",
      "'12 00 32 80 80 80 80 80 80 80 80 01 00', an obu_size longer than 8 bytes",
      "'12 00 32 ff ff ff ff 7f', an obu_size beyond 32 bits",
      "'12 00 2a 00', a metadata OBU without its metadata_type",
      "'12 00 2a 01 80', a metadata_type that runs past its OBU",
      "'12 00 2a 03 01 00 00', an HDR_CLL OBU without its trailing bits",
      "'12 00 2a 03 04 b5 81', a T.35 OBU whose last nonzero byte is not 0x80"
    })
    @DisplayName("Should leave HDR10+ unspecified when the walk cannot delimit an OBU")
    void shouldLeaveHdr10PlusUnspecifiedWhenTheWalkCannotDelimitAnObu(
        String packet, String reason) {
      var video = probed(AV1C, packet);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @Test
    @DisplayName("Should leave HDR10+ unspecified when an OBU with an extension byte holds it")
    void shouldLeaveHdr10PlusUnspecifiedWhenAnObuWithAnExtensionByteHoldsIt() {
      // FFmpeg's AV1 decoders skip such an OBU when their operating point leaves out its layer.
      var video = probed(AV1C, temporalUnit(layered(4, "b5 00 3c 00 01 04 01 40")));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName(
        "Should leave HDR10+ unspecified when the extradata is bare OBUs rather than a record")
    void shouldLeaveHdr10PlusUnspecifiedWhenTheExtradataIsBareObusRatherThanARecord() {
      // FFmpeg gives an AV1 stream in IVF its sequence header and global metadata OBUs as
      // extradata.
      var video = probed(obu(1, "00 00 00"), temporalUnit());

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
    }

    @Test
    @DisplayName("Should leave HDR10+ unspecified when a packet carries a replacement record")
    void shouldLeaveHdr10PlusUnspecifiedWhenAPacketCarriesAReplacementRecord() {
      var video =
          probe(
                  answers(AV1C)
                      .firstFrames(frames("", "", ""))
                      .firstPackets(withNewExtradata(packets(temporalUnit(), temporalUnit()), 1))
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
                      .firstPackets(packets(temporalUnit()))
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }
  }

  @Nested
  @DisplayName("Dolby Vision")
  class DolbyVisionTests {

    @Test
    @DisplayName(
        "Should report the walk's RPU and its header when the frames show none, and no compatible"
            + " base for profile 10.0")
    void
        shouldReportTheWalksRpuAndItsHeaderWhenTheFramesShowNoneAndNoCompatibleBaseForProfile100() {
      var video =
          probed(
              AV1C,
              temporalUnit(metadata(4, RPU_PREFIX + " " + emdf(PROFILE_10_0_RPU, 0x01BE6841))));

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getFirstRpuHeader()).isEqualTo(PROFILE_10_0_HEADER);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    @Test
    @DisplayName("Should report the RPU without a header when its EMDF container is malformed")
    void shouldReportTheRpuWithoutAHeaderWhenItsEmdfContainerIsMalformed() {
      // FFmpeg drops an RPU whose container it cannot open.
      var video =
          probed(
              AV1C,
              temporalUnit(metadata(4, RPU_PREFIX + " " + emdf(PROFILE_10_0_RPU, 0x01BE6840))));

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.hasFirstRpuHeader()).isFalse();
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
          probed(
              AV1C,
              temporalUnit(layered(4, RPU_PREFIX + " " + emdf(PROFILE_10_0_RPU, 0x01BE6841))));

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
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
                      .firstPackets(
                          packets(
                              temporalUnit(
                                  metadata(
                                      4, RPU_PREFIX + " " + emdf(PROFILE_10_0_RPU, 0x01BE6841)))))
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
    @DisplayName("Should take the record's values ahead of the packets'")
    void shouldTakeTheRecordsValuesAheadOfThePackets() {
      var video = probed(record(LIGHT_LEVEL), temporalUnit(metadata(1, "00 64 00 32")));

      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
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
    @DisplayName("Should mark only the value of an OBU with an extension byte omitted")
    void shouldMarkOnlyTheValueOfAnObuWithAnExtensionByteOmitted() {
      var video = probed(AV1C, temporalUnit(layered(1, "03 e8 01 90")));

      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
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
    @DisplayName("Should read the static metadata that only frames after the first carry")
    void shouldReadTheStaticMetadataThatOnlyFramesAfterTheFirstCarry() {
      var video = streamOf("mp4-av1-static-after-frame.mp4", 0);

      assertThat(video.getMasteringDisplay()).isEqualTo(CONVERTED_MASTERING_DISPLAY);
      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(video.getContentLightLevel().getMaxFrameAverageLightLevel()).isEqualTo(400);
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName("Should report the frames' RPU header and no compatible base for profile 10.0")
    void shouldReportTheFramesRpuHeaderAndNoCompatibleBaseForProfile100() {
      var video = streamOf("mp4-av1-rpu.mp4", 0);

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getFirstRpuHeader().getVdrRpuProfile()).isZero();
      assertThat(present.getFirstRpuHeader().getBaseLayerFullRange()).isTrue();
      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName("Should report the RPU that FFmpeg drops without a header")
    void shouldReportTheRpuThatFfmpegDropsWithoutAHeader() {
      var video = streamOf("mp4-av1-rpu-bad-emdf.mp4", 0);

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.hasFirstRpuHeader()).isFalse();
    }
  }

  // A profile 10.0 RPU's header: rpu_type 2, rpu_format 0, vdr_rpu_profile 0, vdr_rpu_level 0,
  // sequence information with fixed-point coefficients of log2 denominator 23, the normalized idc
  // 1,
  // a full-range base layer, base and enhancement bit depths of 10, a VDR bit depth of 12, then
  // disable_residual_flag 1, padded to the 6 bytes that FFmpeg's container needs at least.
  private static final String PROFILE_10_0_RPU =
      hex(
          new Bits()
              .put(6, 2)
              .put(11, 0)
              .put(4, 0)
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

  /** The stream, when its 3 first frames carry no side data and its packets hold the samples. */
  private static ProbeStreamInfo probed(String record, String... packets) {
    return probe(
            answers(record).firstFrames(frames("", "", "")).firstPackets(packets(packets)).build())
        .getStreams(0);
  }

  private static Answers.AnswersBuilder answers(String extradata) {
    return Answers.builder()
        .ffprobeOutput(output("\"codec_name\":\"av1\"," + extradata(extradata)));
  }

  /** An av1C record whose configOBUs are the given OBUs. */
  private static String record(String... obus) {
    return AV1C + " " + String.join(" ", obus);
  }

  /** A temporal delimiter, the given OBUs, then a frame. */
  private static String temporalUnit(String... obus) {
    return (TEMPORAL_DELIMITER + " " + String.join(" ", obus) + " " + FRAME).replaceAll(" +", " ");
  }

  /** An OBU of the type with a size field. */
  private static String obu(int type, String payload) {
    var length = HexFormat.ofDelimiter(" ").parseHex(payload).length;
    return "%02x %02x %s".formatted((type << 3) | 0x02, length, payload).trim();
  }

  /** A metadata OBU of the type, whose values the trailing bits end. */
  private static String metadata(int metadataType, String values) {
    return obu(5, "%02x %s 80".formatted(metadataType, values));
  }

  /** A metadata OBU with an extension byte that names temporal and spatial layer 0. */
  private static String layered(int metadataType, String values) {
    var payload = "%02x %s 80".formatted(metadataType, values);
    var length = HexFormat.ofDelimiter(" ").parseHex(payload).length;
    return "2e 00 %02x %s".formatted(length, payload);
  }

  /**
   * The RPU in an EMDF container: the 27-bit header, emdf_payload_size in 8 bits without extension,
   * the RPU's bytes, and the 17-bit emdf_protection.
   */
  private static String emdf(String rpu, int header) {
    var bytes = HexFormat.ofDelimiter(" ").parseHex(rpu);
    var bits = new Bits().put(27, header).put(8, bytes.length).put(1, 0);
    for (var value : bytes) {
      bits.put(8, Byte.toUnsignedInt(value));
    }

    return hex(bits.put(17, 0x400).bytes());
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
