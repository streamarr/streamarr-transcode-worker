package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.annexB;
import static com.streamarr.transcode.probe.FirstReads.extradata;
import static com.streamarr.transcode.probe.FirstReads.frames;
import static com.streamarr.transcode.probe.FirstReads.lengthPrefixed;
import static com.streamarr.transcode.probe.FirstReads.output;
import static com.streamarr.transcode.probe.FirstReads.packets;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ChromaticityCoordinates;
import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.MasteringDisplayColorVolume;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.probe.RecordedFfprobe.Answers;
import java.io.ByteArrayOutputStream;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor SEI Tests")
class FfprobeExecutorSeiTest {

  // FFmpeg rejects a user_data_unregistered message shorter than 16 bytes, and with it every later
  // message of its SEI NAL unit.
  private static final String SHORT_UNREGISTERED = "05 01 00";
  private static final String HDR10_PLUS = "04 08 b5 00 3c 00 01 04 01 40";
  // The same ITU-T T.35 provider and code, but application identifier 5.
  private static final String OTHER_T35 = "04 08 b5 00 3c 00 01 05 01 40";
  // Green, blue and red primaries, the D65 white point, 1000 cd/m^2 and 0.005 cd/m^2.
  private static final String MASTERING_DISPLAY =
      "89 18 33 c2 86 c4 1d 4c 0b b8 84 d0 3e 80 3d 13 40 42 00 98 96 80 00 00 00 32";
  private static final String LIGHT_LEVEL = "90 04 03 e8 01 90";
  private static final MasteringDisplayColorVolume RECORDED_MASTERING_DISPLAY =
      MasteringDisplayColorVolume.newBuilder()
          .setRed(ChromaticityCoordinates.newBuilder().setX(34_000).setY(16_000))
          .setGreen(ChromaticityCoordinates.newBuilder().setX(13_250).setY(34_500))
          .setBlue(ChromaticityCoordinates.newBuilder().setX(7500).setY(3000))
          .setWhitePoint(ChromaticityCoordinates.newBuilder().setX(15_635).setY(16_450))
          .setMaxLuminance(10_000_000)
          .setMinLuminance(50)
          .build();
  private static final String HDR10_PLUS_SIDE_DATA =
      """
      {"side_data_type":"HDR Dynamic Metadata SMPTE2094-40 (HDR10+)"}""";

  @Nested
  @DisplayName("HDR10+")
  class Hdr10PlusTests {

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName("Should report HDR10+ present when a message that FFmpeg rejects precedes it")
    void shouldReportHdr10PlusPresentWhenAMessageThatFfmpegRejectsPrecedesIt(Coding coding) {
      var video = coding.probed(coding.sample(coding.sei(SHORT_UNREGISTERED, HDR10_PLUS)));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName(
        "Should report HDR10+ absent when every SEI message is delimited and none holds it")
    void shouldReportHdr10PlusAbsentWhenEverySeiMessageIsDelimitedAndNoneHoldsIt(Coding coding) {
      var video =
          coding.probed(
              coding.sample(coding.sei(SHORT_UNREGISTERED, OTHER_T35, LIGHT_LEVEL)),
              coding.sample());

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName("Should leave HDR10+ unspecified when an SEI message runs past its NAL unit")
    void shouldLeaveHdr10PlusUnspecifiedWhenAnSeiMessageRunsPastItsNalUnit(Coding coding) {
      var video = coding.probed(coding.sample(coding.sei(LIGHT_LEVEL, "05 10 00")));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"H264, ''", "H264, 05 01 01", "HEVC, ''", "HEVC, 05 01 01"})
    @DisplayName("Should leave HDR10+ unspecified when an SEI NAL unit lacks its trailing bits")
    void shouldLeaveHdr10PlusUnspecifiedWhenAnSeiNalUnitLacksItsTrailingBits(
        Coding coding, String messages) {
      var video = coding.probed(coding.sample((coding.seiHeader + " " + messages).trim()));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName(
        "Should report HDR10+ present when an SEI NAL unit that lacks its trailing bits holds it")
    void shouldReportHdr10PlusPresentWhenAnSeiNalUnitThatLacksItsTrailingBitsHoldsIt(
        Coding coding) {
      var video = coding.probed(coding.sample(coding.seiHeader + " " + HDR10_PLUS));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName("Should leave HDR10+ unspecified when a second trailing byte follows the messages")
    void shouldLeaveHdr10PlusUnspecifiedWhenASecondTrailingByteFollowsTheMessages(Coding coding) {
      var video = coding.probed(coding.sample(coding.sei(LIGHT_LEVEL, "80")));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName(
        "Should read the NAL units after a start code that a NAL unit's length covers, and rule out"
            + " nothing")
    void shouldReadTheNalUnitsAfterAStartCodeThatANalUnitsLengthCoversAndRuleOutNothing(
        Coding coding) {
      // FFmpeg ends the NAL unit at the start code and reads the SEI NAL unit that follows it.
      var video =
          coding.probed(
              coding.sample(
                  coding.sei(SHORT_UNREGISTERED)
                      + " 00 00 01 "
                      + coding.sei(SHORT_UNREGISTERED, HDR10_PLUS)));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName("Should report HDR10+ absent when an SEI NAL unit holds only its trailing bits")
    void shouldReportHdr10PlusAbsentWhenAnSeiNalUnitHoldsOnlyItsTrailingBits(Coding coding) {
      var video = coding.probed(coding.sample(coding.sei()));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName("Should leave HDR10+ unspecified when bytes past the trailing bits follow")
    void shouldLeaveHdr10PlusUnspecifiedWhenBytesPastTheTrailingBitsFollow(Coding coding) {
      var video = coding.probed(coding.sample(coding.sei(LIGHT_LEVEL) + " 01"));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName("Should leave HDR10+ unspecified when a sample's length runs past its end")
    void shouldLeaveHdr10PlusUnspecifiedWhenASamplesLengthRunsPastItsEnd(Coding coding) {
      var video =
          coding.probed(
              coding.sample(coding.sei(LIGHT_LEVEL)) + " 00 00 01 00 02", coding.sample());

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName("Should leave HDR10+ unspecified when the packet read fails")
    void shouldLeaveHdr10PlusUnspecifiedWhenThePacketReadFails(Coding coding) {
      var video = probe(coding.answers().firstFrames(frames("", "", "")).build()).getStreams(0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName("Should leave HDR10+ unspecified when a failed read cuts the frames short")
    void shouldLeaveHdr10PlusUnspecifiedWhenAFailedReadCutsTheFramesShort(Coding coding) {
      var video =
          probe(
                  coding
                      .answers()
                      .firstFrames(frames(""))
                      .failingReadsBeyondPackets(3)
                      .firstPackets(packets(coding.sample(coding.sei(LIGHT_LEVEL))))
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName("Should report HDR10+ present when a suffix SEI NAL unit holds it")
    void shouldReportHdr10PlusPresentWhenASuffixSeiNalUnitHoldsIt() {
      var video =
          Coding.HEVC.probed(
              lengthPrefixed(
                  Coding.HEVC.slice, withEmulationPrevention("50 01 " + HDR10_PLUS + " 80")));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName("Should read the SEI from start codes when MPEG-TS carries the stream")
    void shouldReadTheSeiFromStartCodesWhenMpegTsCarriesTheStream() {
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(
                          output(
                              Coding.HEVC.codecName()
                                  + ","
                                  + extradata("00 00 00 01 40 01 0c 01 ff ff")))
                      .firstFrames(frames("", "", ""))
                      .firstPackets(
                          packets(
                              annexB(Coding.HEVC.sei(SHORT_UNREGISTERED, HDR10_PLUS), "26 01 af")))
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName("Should keep the frames' HDR10+ when the packets show none")
    void shouldKeepTheFramesHdr10PlusWhenThePacketsShowNone() {
      var video =
          probe(
                  Coding.HEVC
                      .answers()
                      .firstFrames(frames(HDR10_PLUS_SIDE_DATA))
                      .firstPackets(packets(Coding.HEVC.sample()))
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }
  }

  @Nested
  @DisplayName("HEVC Configuration Record")
  class HevcConfigurationRecordTests {

    @Test
    @DisplayName("Should report HDR10+ present when the record's SEI holds it")
    void shouldReportHdr10PlusPresentWhenTheRecordsSeiHoldsIt() {
      var video =
          probed(
              recordWithSei(Coding.HEVC.sei(SHORT_UNREGISTERED, HDR10_PLUS)), Coding.HEVC.sample());

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName("Should report HDR10+ absent when neither the record nor the packets hold it")
    void shouldReportHdr10PlusAbsentWhenNeitherTheRecordNorThePacketsHoldIt() {
      var video = probed(recordWithSei(Coding.HEVC.sei(SHORT_UNREGISTERED)), Coding.HEVC.sample());

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName("Should leave HDR10+ unspecified when a NAL unit runs past the record's end")
    void shouldLeaveHdr10PlusUnspecifiedWhenANalUnitRunsPastTheRecordsEnd() {
      var record = recordWithSei(Coding.HEVC.sei(LIGHT_LEVEL));
      var cut = record.substring(0, record.length() - " 80".length());

      var video = probed(cut, Coding.HEVC.sample());

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName(
        "Should read the NAL units after a start code that a record's NAL unit holds, and rule out"
            + " nothing")
    void shouldReadTheNalUnitsAfterAStartCodeThatARecordsNalUnitHoldsAndRuleOutNothing() {
      var video =
          probed(
              recordWithSei(
                  Coding.HEVC.sei(SHORT_UNREGISTERED)
                      + " 00 00 01 "
                      + Coding.HEVC.sei(SHORT_UNREGISTERED, HDR10_PLUS)),
              Coding.HEVC.sample());

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
    }

    @Test
    @DisplayName("Should take the record's static metadata ahead of the packets'")
    void shouldTakeTheRecordsStaticMetadataAheadOfThePackets() {
      var video =
          probed(
              recordWithSei(Coding.HEVC.sei(LIGHT_LEVEL)),
              Coding.HEVC.sample(Coding.HEVC.sei("90 04 00 64 00 32")));

      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(video.getContentLightLevel().getMaxFrameAverageLightLevel()).isEqualTo(400);
    }

    /** An hvcC record whose NAL units take 4-byte lengths, with one array of one SEI NAL unit. */
    private static String recordWithSei(String sei) {
      var length = HexFormat.ofDelimiter(" ").parseHex(sei).length;
      return Coding.HEVC.record.substring(0, Coding.HEVC.record.length() - "00".length())
          + "01 27 00 01 %02x %02x ".formatted(length >> 8, length & 0xFF)
          + sei;
    }

    private static ProbeStreamInfo probed(String record, String... samples) {
      return probe(
              Answers.builder()
                  .ffprobeOutput(output(Coding.HEVC.codecName() + "," + extradata(record)))
                  .firstFrames(frames("", "", ""))
                  .firstPackets(packets(samples))
                  .build())
          .getStreams(0);
    }
  }

  @Nested
  @DisplayName("Nesting Messages")
  class NestingMessageTests {

    @ParameterizedTest(name = "{0} payloadType {1}")
    @CsvSource({
      // all_layer_representations_in_au_flag 1, then alignment.
      "H264, 1e, 80",
      // num_layer_representations_minus1 0, one dependency and quality id, sei_temporal_id 0.
      "H264, 1e, 40 00",
      // operation_point_flag 0, all_view_components_in_au_flag 1.
      "H264, 25, 40",
      // operation_point_flag 1, num_view_components_op_minus1 0, a view id, sei_op_temporal_id.
      "H264, 25, c0 00",
      // MVCD: operation_point_flag 0, all_view_components_in_au_flag 1.
      "H264, 30, 40",
      // MVCD: operation_point_flag 0, all_view_components_in_au_flag 0,
      // num_view_components_minus1 0, a view id and its applicability flag.
      "H264, 30, 20 00",
      // MVCD: operation_point_flag 1, sei_op_texture_only_flag 1, num_view_components_op_minus1 0,
      // a view id, sei_op_temporal_id.
      "H264, 30, e0 00",
      // MVCD: operation_point_flag 1, sei_op_texture_only_flag 0, num_view_components_op_minus1 0,
      // a view id with its depth and texture flags, sei_op_temporal_id.
      "H264, 30, a0 00 00",
      // bitstream_subset_flag 0, nesting_op_flag 0, all_layers_flag 1.
      "HEVC, 85, 20",
      // nesting_op_flag 1, default_op_flag 0, nesting_num_ops_minus1 0, one operation point.
      "HEVC, 85, 51",
      // all_layers_flag 0, a maximum temporal id, nesting_num_layers_minus1 0, one layer id.
      "HEVC, 85, 02 00"
    })
    @DisplayName("Should leave HDR10+ unspecified when only a nesting message holds it")
    void shouldLeaveHdr10PlusUnspecifiedWhenOnlyANestingMessageHoldsIt(
        Coding coding, String payloadType, String header) {
      var video =
          coding.probed(coding.sample(coding.sei(nesting(payloadType, header, HDR10_PLUS))));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @ParameterizedTest(name = "{0} payloadType {1}")
    @CsvSource({
      "H264, 1e, 80",
      "H264, 25, 40",
      "H264, 30, 40",
      "H264, 30, a0 00 00",
      "HEVC, 85, 20"
    })
    @DisplayName("Should report HDR10+ absent when a nesting message holds other messages")
    void shouldReportHdr10PlusAbsentWhenANestingMessageHoldsOtherMessages(
        Coding coding, String payloadType, String header) {
      var video = coding.probed(coding.sample(coding.sei(nesting(payloadType, header, OTHER_T35))));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @ParameterizedTest(name = "{0} payloadType {1}")
    @CsvSource({"H264, 1e, 80", "HEVC, 85, 20"})
    @DisplayName("Should leave HDR10+ unspecified when a nested message runs past its nesting one")
    void shouldLeaveHdr10PlusUnspecifiedWhenANestedMessageRunsPastItsNestingOne(
        Coding coding, String payloadType, String header) {
      var video =
          coding.probed(
              coding.sample(coding.sei(nesting(payloadType, header, "05 08 00 00"), LIGHT_LEVEL)));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @ParameterizedTest(name = "{0} payloadType {1}")
    @CsvSource({"H264, 1e, 80", "H264, 25, 40", "H264, 30, 40", "HEVC, 85, 20"})
    @DisplayName("Should leave HDR10+ unspecified when a nesting message nests another")
    void shouldLeaveHdr10PlusUnspecifiedWhenANestingMessageNestsAnother(
        Coding coding, String payloadType, String header) {
      // No nesting message may nest another, so the probe reads one level and rules out nothing
      // that a deeper one could hold.
      var video =
          coding.probed(
              coding.sample(
                  coding.sei(
                      nesting(payloadType, header, nesting(payloadType, header, OTHER_T35)))));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @ParameterizedTest(name = "{0} payloadType {1}")
    @CsvSource({
      // Regional nesting, MCTS extraction information nesting and bitstream partition nesting.
      "HEVC, 9d",
      "HEVC, 9f",
      "HEVC, a2",
      // SEI processing order nesting, which ITU-T H.274 defines.
      "H264, d6",
      "HEVC, d6"
    })
    @DisplayName(
        "Should leave HDR10+ unspecified when an SEI NAL unit holds a nesting message that the probe"
            + " does not read")
    void shouldLeaveHdr10PlusUnspecifiedWhenAnSeiNalUnitHoldsANestingMessageThatTheProbeDoesNotRead(
        Coding coding, String payloadType) {
      // FFmpeg ignores the messages inside it, and the walk cannot tell where they lie.
      var video = coding.probed(coding.sample(coding.sei(nesting(payloadType, "00", OTHER_T35))));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @Test
    @DisplayName(
        "Should leave HDR10+ unspecified when a scalable nesting message holds a nesting message"
            + " that the probe does not read")
    void
        shouldLeaveHdr10PlusUnspecifiedWhenAScalableNestingMessageHoldsANestingMessageThatTheProbeDoesNotRead() {
      var video =
          Coding.HEVC.probed(
              Coding.HEVC.sample(
                  Coding.HEVC.sei(nesting("85", "20", nesting("d6", "00", OTHER_T35)))));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName("Should leave HDR10+ unspecified when a nesting header runs past its payload")
    void shouldLeaveHdr10PlusUnspecifiedWhenANestingHeaderRunsPastItsPayload() {
      // nesting_op_flag 1 and an Exp-Golomb count whose bits run past the 1-byte payload.
      var video = Coding.HEVC.probed(Coding.HEVC.sample(Coding.HEVC.sei("85 01 40")));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName("Should mark only the nested static value omitted")
    void shouldMarkOnlyTheNestedStaticValueOmitted() {
      var video =
          Coding.HEVC.probed(Coding.HEVC.sample(Coding.HEVC.sei(nesting("85", "20", LIGHT_LEVEL))));

      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
    }

    private static String nesting(String payloadType, String header, String message) {
      var payload = header + " " + message;
      var size = HexFormat.ofDelimiter(" ").parseHex(payload).length;
      return "%s %02x %s".formatted(payloadType, size, payload);
    }
  }

  @Nested
  @DisplayName("Static HDR Metadata")
  class StaticHdrMetadataTests {

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName("Should take the static metadata from the packets when the first frame has none")
    void shouldTakeTheStaticMetadataFromThePacketsWhenTheFirstFrameHasNone(Coding coding) {
      var video =
          coding.probed(
              coding.sample(coding.sei(SHORT_UNREGISTERED, MASTERING_DISPLAY, LIGHT_LEVEL)));

      assertThat(video.getMasteringDisplay().getRed().getX()).isEqualTo(34_000);
      assertThat(video.getMasteringDisplay().getRed().getY()).isEqualTo(16_000);
      assertThat(video.getMasteringDisplay().getGreen().getX()).isEqualTo(13_250);
      assertThat(video.getMasteringDisplay().getGreen().getY()).isEqualTo(34_500);
      assertThat(video.getMasteringDisplay().getBlue().getX()).isEqualTo(7500);
      assertThat(video.getMasteringDisplay().getBlue().getY()).isEqualTo(3000);
      assertThat(video.getMasteringDisplay().getWhitePoint().getX()).isEqualTo(15_635);
      assertThat(video.getMasteringDisplay().getWhitePoint().getY()).isEqualTo(16_450);
      assertThat(video.getMasteringDisplay().getMaxLuminance()).isEqualTo(10_000_000);
      assertThat(video.getMasteringDisplay().getMinLuminance()).isEqualTo(50);
      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(video.getContentLightLevel().getMaxFrameAverageLightLevel()).isEqualTo(400);
    }

    @Test
    @DisplayName("Should keep the first frame's static metadata when the packets hold others")
    void shouldKeepTheFirstFramesStaticMetadataWhenThePacketsHoldOthers() {
      var video =
          probe(
                  Coding.HEVC
                      .answers()
                      .firstFrames(
                          frames(
                              """
                              {"side_data_type":"Content light level metadata","max_content":600,
                               "max_average":200}"""))
                      .firstPackets(packets(Coding.HEVC.sample(Coding.HEVC.sei(LIGHT_LEVEL))))
                      .build())
              .getStreams(0);

      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(600);
    }

    @Test
    @DisplayName("Should take the first message's values when later packets hold others")
    void shouldTakeTheFirstMessagesValuesWhenLaterPacketsHoldOthers() {
      var video =
          Coding.H264.probed(
              Coding.H264.sample(Coding.H264.sei(LIGHT_LEVEL)),
              Coding.H264.sample(Coding.H264.sei("90 04 00 64 00 32")));

      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
    }

    @ParameterizedTest(name = "{1}")
    @CsvSource({
      "'89 04 33 c2 86 c4', too short",
      "'89 18 c3 51 86 c4 1d 4c 0b b8 84 d0 3e 80 3d 13 40 42 00 98 96 80 00 00 00 32',"
          + " a primary beyond 50000"
    })
    @DisplayName("Should mark the mastering display omitted when its message cannot be reported")
    void shouldMarkTheMasteringDisplayOmittedWhenItsMessageCannotBeReported(
        String message, String reason) {
      var video = Coding.HEVC.probed(Coding.HEVC.sample(Coding.HEVC.sei(message, LIGHT_LEVEL)));

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
    }

    @Test
    @DisplayName("Should mark the light level omitted when its message is too short")
    void shouldMarkTheLightLevelOmittedWhenItsMessageIsTooShort() {
      var video = Coding.HEVC.probed(Coding.HEVC.sample(Coding.HEVC.sei("90 03 03 e8 01")));

      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName("Should mark both values omitted when the probe could not read all of the SEI")
    void shouldMarkBothValuesOmittedWhenTheProbeCouldNotReadAllOfTheSei(Coding coding) {
      var video = coding.probed(coding.sample(coding.sei(SHORT_UNREGISTERED, "05 10 00")));

      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @Test
    @DisplayName("Should mark both values omitted when the packet read fails")
    void shouldMarkBothValuesOmittedWhenThePacketReadFails() {
      var video =
          probe(Coding.HEVC.answers().firstFrames(frames("", "", "")).build()).getStreams(0);

      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName("Should leave both values absent and unflagged when no SEI message holds them")
    void shouldLeaveBothValuesAbsentAndUnflaggedWhenNoSeiMessageHoldsThem(Coding coding) {
      var video = coding.probed(coding.sample(coding.sei(SHORT_UNREGISTERED)), coding.sample());

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
        "Should read an HEVC stream's HDR messages from its packets when FFmpeg rejects the SEI"
            + " that holds them")
    void shouldReadAnHevcStreamsHdrMessagesFromItsPacketsWhenFfmpegRejectsTheSeiThatHoldsThem() {
      // reject-hdr-sei.py put a short user_data_unregistered message ahead of each HDR message.
      var video = streamOf("hevc-hdr10-plus-rejected-sei.hevc", 0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(video.getMasteringDisplay()).isEqualTo(RECORDED_MASTERING_DISPLAY);
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
    @DisplayName(
        "Should read an H.264 stream's mastering display from its packets and rule out what they"
            + " lack")
    void shouldReadAnH264StreamsMasteringDisplayFromItsPacketsAndRuleOutWhatTheyLack() {
      var video = streamOf("h264-hdr10-rejected-sei.h264", 0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(video.getMasteringDisplay()).isEqualTo(RECORDED_MASTERING_DISPLAY);
      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "mpegts-pq10.ts",
          "mpegts-hevc-ac3.ts",
          "mkv-pipe-hevc-10bit.mkv",
          "mp4-sdr8-bt709.mp4",
          "mp4-dovi-p9-avc1.mp4"
        })
    @DisplayName(
        "Should rule out HDR10+ and static metadata when a recorded stream's packets hold none")
    void shouldRuleOutHdr10PlusAndStaticMetadataWhenARecordedStreamsPacketsHoldNone(
        String fixture) {
      var video = streamOf(fixture, 0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }
  }

  /**
   * A codec of NAL units with what its tests need: a configuration record whose NAL units take
   * 4-byte lengths, the header of its SEI NAL unit, and a slice.
   */
  enum Coding {
    H264(
        "h264", "01 64 00 28 ff e1 00 04 67 64 00 28 01 00 04 68 ee 3c 80", "06", "65 88 84 00 18"),
    HEVC(
        "hevc",
        "01 01 60 00 00 00 90 00 00 00 00 00 5d f0 00 fc fd f8 f8 00 00 0f 00",
        "4e 01",
        "26 01 af 06 b8");

    private final String name;
    private final String record;
    private final String seiHeader;
    private final String slice;

    Coding(String name, String record, String seiHeader, String slice) {
      this.name = name;
      this.record = record;
      this.seiHeader = seiHeader;
      this.slice = slice;
    }

    String codecName() {
      return "\"codec_name\":\"%s\"".formatted(name);
    }

    /** An SEI NAL unit of the messages, then the RBSP trailing bits, with emulation prevention. */
    String sei(String... messages) {
      return withEmulationPrevention(seiHeader + " " + String.join(" ", messages) + " 80");
    }

    /** A length-prefixed sample of the NAL units, then a slice. */
    String sample(String... units) {
      var all = new String[units.length + 1];
      System.arraycopy(units, 0, all, 0, units.length);
      all[units.length] = slice;
      return lengthPrefixed(all);
    }

    Answers.AnswersBuilder answers() {
      return Answers.builder().ffprobeOutput(output(codecName() + "," + extradata(record)));
    }

    /** The stream, when its 3 first frames carry no side data and its packets hold the samples. */
    ProbeStreamInfo probed(String... samples) {
      return probe(answers().firstFrames(frames("", "", "")).firstPackets(packets(samples)).build())
          .getStreams(0);
    }
  }

  /** The bytes with an emulation prevention byte after each two zero bytes ahead of 0 to 3. */
  private static String withEmulationPrevention(String hex) {
    var output = new ByteArrayOutputStream();
    var zeros = 0;
    for (var value : HexFormat.ofDelimiter(" ").parseHex(hex.trim().replaceAll(" +", " "))) {
      if (zeros >= 2 && Byte.toUnsignedInt(value) <= 3) {
        output.write(3);
        zeros = 0;
      }

      output.write(value);
      zeros = value == 0 ? zeros + 1 : 0;
    }

    return HexFormat.ofDelimiter(" ").formatHex(output.toByteArray());
  }
}
