package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.annexB;
import static com.streamarr.transcode.probe.FirstReads.extradata;
import static com.streamarr.transcode.probe.FirstReads.frames;
import static com.streamarr.transcode.probe.FirstReads.lengthPrefixed;
import static com.streamarr.transcode.probe.FirstReads.output;
import static com.streamarr.transcode.probe.FirstReads.packets;
import static com.streamarr.transcode.probe.FirstReads.packetsUpTo;
import static com.streamarr.transcode.probe.FirstReads.withNewExtradata;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ChromaticityCoordinates;
import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.MasteringDisplayColorVolume;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.VideoDynamicRange.DolbyVisionCase;
import com.streamarr.transcode.probe.RecordedFfprobe.Answers;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.HexFormat;
import lombok.Builder;
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
    @DisplayName(
        "Should report HDR10+ absent when only an SEI NAL unit whose forbidden_zero_bit is 1 holds"
            + " it")
    void shouldReportHdr10PlusAbsentWhenOnlyAnSeiNalUnitWhoseForbiddenZeroBitIs1HoldsIt(
        Coding coding) {
      // FFmpeg's splitter drops the NAL unit before any decoder sees it.
      var sei = coding.sei(HDR10_PLUS);
      var dropped =
          "%02x".formatted(Integer.parseInt(sei.substring(0, 2), 16) | 0x80) + sei.substring(2);

      var video = coding.probed(coding.sample(dropped));

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
      var video = coding.probed(coding.sample((coding.units.seiHeader() + " " + messages).trim()));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getMasteringDisplayOmitted()).as("mastering display omitted").isTrue();
      assertThat(video.getContentLightLevelOmitted()).as("light level omitted").isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName(
        "Should report HDR10+ present when an SEI NAL unit that lacks its trailing bits holds it")
    void shouldReportHdr10PlusPresentWhenAnSeiNalUnitThatLacksItsTrailingBitsHoldsIt(
        Coding coding) {
      var video = coding.probed(coding.sample(coding.units.seiHeader() + " " + HDR10_PLUS));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName("Should leave HDR10+ unspecified when a second trailing byte follows the messages")
    void shouldLeaveHdr10PlusUnspecifiedWhenASecondTrailingByteFollowsTheMessages(Coding coding) {
      var video = coding.probed(coding.sample(coding.sei(LIGHT_LEVEL, "80")));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getMasteringDisplayOmitted()).as("mastering display omitted").isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName(
        "Should report HDR10+ present and rule out nothing when a length-prefixed NAL unit holds a"
            + " start code")
    void shouldReportHdr10PlusPresentAndRuleOutNothingWhenALengthPrefixedNalUnitHoldsAStartCode(
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
                  Coding.HEVC.units.slice(),
                  withEmulationPrevention("50 01 " + HDR10_PLUS + " 80")));

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

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName(
        "Should take the static values from Annex B extradata and rule out the rest when every unit"
            + " is delimited")
    void shouldTakeTheStaticValuesFromAnnexBExtradataAndRuleOutTheRestWhenEveryUnitIsDelimited(
        Coding coding) {
      // FFmpeg's HEVC decoder applies the SEI NAL units of Annex B extradata to the frames.
      var video =
          annexBProbed(
              coding,
              annexB(coding.units.parameterSet(), coding.sei(LIGHT_LEVEL)),
              annexB(coding.sei(SHORT_UNREGISTERED), coding.units.slice()));

      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(video.getContentLightLevelOmitted()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName("Should report HDR10+ present when Annex B extradata holds it")
    void shouldReportHdr10PlusPresentWhenAnnexBExtradataHoldsIt(Coding coding) {
      var video =
          annexBProbed(
              coding,
              annexB(coding.units.parameterSet(), coding.sei(HDR10_PLUS)),
              annexB(coding.units.slice()));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName(
        "Should rule nothing out when data ahead of the first start code leaves Annex B extradata"
            + " undelimited")
    void shouldRuleNothingOutWhenDataAheadOfTheFirstStartCodeLeavesAnnexBExtradataUndelimited(
        Coding coding) {
      var video =
          annexBProbed(
              coding, "02 " + annexB(coding.units.parameterSet()), annexB(coding.units.slice()));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "a record too short for lengthSizeMinusOne, 01 64 00 28, true",
      "a record under the 7 bytes that FFmpeg reads, 01 64 00 28 ff e1, false",
      "an SPS that runs past the record, 01 64 00 28 ff e1 00 08 67 64 00 28, false",
      "a PPS that runs past the record, 01 64 00 28 ff e1 00 04 67 64 00 28 01 00 04 68 ee, false"
    })
    @DisplayName("Should rule nothing out when FFmpeg cannot read an H.264 stream's avcC record")
    void shouldRuleNothingOutWhenFfmpegCannotReadAnH264StreamsAvccRecord(
        String description, String record, boolean isAnnexB) {
      // FFmpeg takes the record's NAL unit length size only once its parameter sets end inside it,
      // so it can delimit the packets otherwise than the walk does.
      var sample =
          isAnnexB ? annexB(Coding.H264.units.slice()) : lengthPrefixed(Coding.H264.units.slice());
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(Coding.H264.codecName() + "," + extradata(record)))
                      .firstFrames(frames("", "", ""))
                      .firstPackets(packetsUpTo(3, sample))
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().getHdr10Plus())
          .as(description)
          .isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).as(description).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).as(description).isTrue();
    }

    @Test
    @DisplayName(
        "Should rule out HDR10+ when an H.264 stream's avcC record ends without a PPS count")
    void shouldRuleOutHdr10PlusWhenAnH264StreamsAvccRecordEndsWithoutAPpsCount() {
      // FFmpeg reads the count past the record's end as 0, and so takes the record whole.
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(
                          output(
                              Coding.H264.codecName()
                                  + ","
                                  + extradata("01 64 00 28 ff e1 00 04 67 64 00 28")))
                      .firstFrames(frames("", "", ""))
                      .firstPackets(packetsUpTo(3, Coding.H264.sample()))
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    /** The stream with Annex B extradata, 3 first frames, and 3 first packets of the sample. */
    private static ProbeStreamInfo annexBProbed(Coding coding, String extradata, String sample) {
      return probe(
              Answers.builder()
                  .ffprobeOutput(output(coding.codecName() + "," + extradata(extradata)))
                  .firstFrames(frames("", "", ""))
                  .firstPackets(packetsUpTo(3, annexB(coding.units.slice()), sample))
                  .build())
          .getStreams(0);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName(
        "Should leave HDR10+ unspecified when a packet carries a replacement record that the probe"
            + " cannot read")
    void shouldLeaveHdr10PlusUnspecifiedWhenAPacketCarriesAReplacementRecordThatTheProbeCannotRead(
        Coding coding) {
      // ffprobe prints the side data's type but not its bytes, and FFmpeg's decoders apply it.
      var video =
          probe(
                  coding
                      .answers()
                      .firstFrames(frames("", "", ""))
                      .firstPackets(
                          withNewExtradata(
                              packets(coding.sample(coding.sei(LIGHT_LEVEL)), coding.sample()), 1))
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isFalse();
      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
    }

    @Test
    @DisplayName("Should leave HDR10+ unspecified when the first packets print too much to hold")
    void shouldLeaveHdr10PlusUnspecifiedWhenTheFirstPacketsPrintTooMuchToHold() {
      // A 16 MiB filler NAL unit, whose 69 MiB hex dump runs past the 64 MiB that the probe holds.
      var sample = new byte[16 << 20];
      var length = sample.length - 4;
      sample[0] = (byte) (length >> 24);
      sample[1] = (byte) (length >> 16);
      sample[2] = (byte) (length >> 8);
      sample[3] = (byte) length;
      sample[4] = 0x4c;
      sample[5] = 0x01;
      Arrays.fill(sample, 6, sample.length, (byte) 0xff);
      var video =
          probe(
                  Coding.HEVC
                      .answers()
                      .firstFrames(frames("", "", ""))
                      .firstPackets(
                          "{\"packets\":[{\"data\":\"%s\"}]}"
                              .formatted(RecordedFfprobe.ffprobeDump(sample)))
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
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
        "Should report HDR10+ present and rule out nothing when a record's NAL unit holds a start"
            + " code")
    void shouldReportHdr10PlusPresentAndRuleOutNothingWhenARecordsNalUnitHoldsAStartCode() {
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
    @DisplayName(
        "Should take the record's content light level when the record and the packets both hold"
            + " one")
    void shouldTakeTheRecordsContentLightLevelWhenTheRecordAndThePacketsBothHoldOne() {
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
      return Coding.HEVC
              .units
              .configurationRecord()
              .substring(0, Coding.HEVC.units.configurationRecord().length() - "00".length())
          + "01 27 00 01 %02x %02x ".formatted(length >> 8, length & 0xFF)
          + sei;
    }

    private static ProbeStreamInfo probed(String record, String... samples) {
      return probe(
              Answers.builder()
                  .ffprobeOutput(output(Coding.HEVC.codecName() + "," + extradata(record)))
                  .firstFrames(frames("", "", ""))
                  .firstPackets(packetsUpTo(3, Coding.HEVC.sample(), samples))
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
      // MVCD headers of 8 views, as ITU-T H.264 (08/2024) H.13.1.2 gives them: a bit too few or
      // too many for each view moves the nested messages a whole byte. operation_point_flag 0,
      // all_view_components_in_au_flag 0, then each sei_view_id and sei_view_applicability_flag.
      "H264, 30, 04 7f ff ff ff ff ff ff ff ff ff ff 80",
      // operation_point_flag 1, sei_op_texture_only_flag 0, then each sei_op_view_id,
      // sei_op_depth_flag and sei_op_texture_flag, then sei_op_temporal_id.
      "H264, 30, 84 7f ff ff ff ff ff ff ff ff ff ff ff 80",
      // operation_point_flag 1, sei_op_texture_only_flag 1, then each sei_op_view_id, then
      // sei_op_temporal_id.
      "H264, 30, c4 7f ff ff ff ff ff ff ff ff ff 80",
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
    @DisplayName(
        "Should mark only the light level omitted when a scalable nesting message holds it")
    void shouldMarkOnlyTheLightLevelOmittedWhenAScalableNestingMessageHoldsIt() {
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
    @DisplayName(
        "Should mark both static values omitted when a cut-short read's packets hold neither")
    void shouldMarkBothStaticValuesOmittedWhenACutShortReadsPacketsHoldNeither(Coding coding) {
      // Packets past the kept read can hold any message.
      var video =
          probe(
                  coding
                      .answers()
                      .firstFrames(frames(""))
                      .firstPackets(packets(coding.sample(), coding.sample(), coding.sample()))
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName("Should take a cut-short read's static values from the packets that hold them")
    void shouldTakeACutShortReadsStaticValuesFromThePacketsThatHoldThem(Coding coding) {
      var video =
          probe(
                  coding
                      .answers()
                      .firstFrames(frames(""))
                      .firstPackets(packets(coding.sample(coding.sei(LIGHT_LEVEL))))
                      .build())
              .getStreams(0);

      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(video.getContentLightLevelOmitted()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
    }

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

    @ParameterizedTest(name = "{0}")
    @EnumSource(Coding.class)
    @DisplayName(
        "Should take no static value from the packets and flag none when no read decodes a frame")
    void shouldTakeNoStaticValueFromThePacketsAndFlagNoneWhenNoReadDecodesAFrame(Coding coding) {
      // A read that decodes no frame is not cut short: the probe walks neither the extradata nor
      // the packets, so only the container gives static values.
      var video =
          probe(
                  coding
                      .answers()
                      .prerollPackets(Integer.MAX_VALUE)
                      .firstFrames(frames("", "", ""))
                      .firstPackets(
                          packets(coding.sample(coding.sei(MASTERING_DISPLAY, LIGHT_LEVEL))))
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isFalse();
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
        "Should take an H.264 stream's mastering display from its packets when FFmpeg rejects the"
            + " SEI that holds it")
    void shouldTakeAnH264StreamsMasteringDisplayFromItsPacketsWhenFfmpegRejectsTheSeiThatHoldsIt() {
      var video = streamOf("h264-hdr10-rejected-sei.h264", 0);

      assertThat(video.getMasteringDisplay()).isEqualTo(RECORDED_MASTERING_DISPLAY);
    }

    @Test
    @DisplayName(
        "Should rule out HDR10+ and the light level when an H.264 stream's Annex B extradata and"
            + " packets hold neither")
    void
        shouldRuleOutHdr10PlusAndTheLightLevelWhenAnH264StreamsAnnexBExtradataAndPacketsHoldNeither() {
      var video = streamOf("h264-hdr10-rejected-sei.h264", 0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4-sdr8-bt709.mp4", "mp4-dovi-p9-avc1.mp4"})
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

    @Test
    @DisplayName(
        "Should mark the mastering display omitted when the first frame carries only the part"
            + " that FFmpeg accepts")
    void
        shouldMarkTheMasteringDisplayOmittedWhenTheFirstFrameCarriesOnlyThePartThatFfmpegAccepts() {
      // FFmpeg drops the luminance of the SEI message, whose maximum exceeds 100,000,000, so the
      // first frame carries the primaries alone, and the first frame's value takes precedence over
      // the message's.
      var video = streamOf("h264-sei-1.h264", 0);

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName(
        "Should take the mastering display from the SEI message when the first frame carries none"
            + " of it")
    void shouldTakeTheMasteringDisplayFromTheSeiMessageWhenTheFirstFrameCarriesNoneOfIt() {
      // In the copy of the same stream into MP4, a read of 3 packets decodes fewer than 3 frames,
      // so the probe keeps the read of 12, whose first frame carries no mastering display.
      var video = mp4H264Sei1();

      assertThat(video.getMasteringDisplay())
          .isEqualTo(
              MasteringDisplayColorVolume.newBuilder()
                  .setRed(ChromaticityCoordinates.newBuilder().setX(5678).setY(4321))
                  .setGreen(ChromaticityCoordinates.newBuilder().setX(1234).setY(8765))
                  .setBlue(ChromaticityCoordinates.newBuilder().setX(3456).setY(6543))
                  .setWhitePoint(ChromaticityCoordinates.newBuilder().setX(12_345).setY(34_567))
                  .setMaxLuminance(123_456_789)
                  .setMinLuminance(0)
                  .build());
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
    }

    @Test
    @DisplayName(
        "Should rule nothing out when the first packets read prints fewer packets than it asked for")
    void shouldRuleNothingOutWhenTheFirstPacketsReadPrintsFewerPacketsThanItAskedFor() {
      // The probe keeps the read of 12 frames, and the 5 packets of the file fall short of those
      // 12.
      var video = mp4H264Sei1();

      assertThat(video.getContentLightLevelOmitted()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    private static ProbeStreamInfo mp4H264Sei1() {
      return probe(RecordedFfprobe.recordedAnswers("mp4-h264-sei-1.mp4").prerollPackets(3).build())
          .getStreams(0);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "mov-h264-extradata-reload.mov",
          "mov-hevc-extradata-reload.mov",
          "mp4-h264-mixed-nal-coding.mp4",
          "avi-h264-unknown-extradata.avi"
        })
    @DisplayName(
        "Should rule nothing out when a recorded stream holds units that the probe cannot"
            + " delimit")
    void shouldRuleNothingOutWhenARecordedStreamHoldsUnitsThatTheProbeCannotDelimit(
        String fixture) {
      // A packet carries a sample description's new extradata, an MP4 sample holds start codes, or
      // the AVI extradata is neither a configuration record nor Annex B.
      var video = streamOf(fixture, 0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @Test
    @DisplayName(
        "Should rule out RPUs but not HDR10+ when an encoder leaves out an SEI NAL unit's"
            + " trailing bits")
    void shouldRuleOutRpusButNotHdr10PlusWhenAnEncoderLeavesOutAnSeiNalUnitsTrailingBits() {
      var video = streamOf("flv-h264-sei-without-trailing-bits.flv", 0);

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @Test
    @DisplayName("Should rule HDR10+ out when an HLG stream's only T.35 message is HDR Vivid")
    void shouldRuleHdr10PlusOutWhenAnHlgStreamsOnlyT35MessageIsHdrVivid() {
      // CUVA's HDR Vivid message carries country code 0x26, not HDR10+'s 0xB5.
      var video = streamOf("hevc-hdr-vivid-repeated.hevc", 0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName("Should report a zero light level present when an HLG stream's SEI declares it")
    void shouldReportAZeroLightLevelPresentWhenAnHlgStreamsSeiDeclaresIt() {
      // A declared 0 is still a present value.
      var video = streamOf("hevc-hdr-vivid-repeated.hevc", 0);

      assertThat(video.hasContentLightLevel()).isTrue();
      assertThat(video.getContentLightLevel()).isEqualTo(ContentLightLevel.getDefaultInstance());
    }

    @Test
    @DisplayName("Should read the mastering display when an HLG stream's SEI holds one")
    void shouldReadTheMasteringDisplayWhenAnHlgStreamsSeiHoldsOne() {
      var video = streamOf("hevc-hdr-vivid-repeated.hevc", 0);

      assertThat(video.getMasteringDisplay())
          .isEqualTo(RECORDED_MASTERING_DISPLAY.toBuilder().setMaxLuminance(40_000_000).build());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mpegts-pq10.ts", "mpegts-hevc-ac3.ts"})
    @DisplayName(
        "Should rule out HDR10+, Dolby Vision and static metadata when an MPEG-TS stream's Annex B"
            + " extradata and packets hold none")
    void
        shouldRuleOutHdr10PlusDolbyVisionAndStaticMetadataWhenAnMpegTsStreamsAnnexBExtradataAndPacketsHoldNone(
            String fixture) {
      // MPEG-TS gives the stream its parameter sets as Annex B extradata.
      var video = streamOf(fixture, 0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }
  }

  /**
   * A codec of NAL units with what its tests need: a configuration record whose NAL units take
   * 4-byte lengths, the header of its SEI NAL unit, a slice, and a parameter set.
   */
  enum Coding {
    H264(
        CodingUnits.builder()
            .codecName("h264")
            .configurationRecord("01 64 00 28 ff e1 00 04 67 64 00 28 01 00 04 68 ee 3c 80")
            .seiHeader("06")
            .slice("65 88 84 00 18")
            .parameterSet("67 64 00 28 01")
            .build()),
    HEVC(
        CodingUnits.builder()
            .codecName("hevc")
            .configurationRecord(
                "01 01 60 00 00 00 90 00 00 00 00 00 5d f0 00 fc fd f8 f8 00 00 0f 00")
            .seiHeader("4e 01")
            .slice("26 01 af 06 b8")
            .parameterSet("40 01 0c 01 ff ff")
            .build());

    private final CodingUnits units;

    Coding(CodingUnits units) {
      this.units = units;
    }

    String codecName() {
      return "\"codec_name\":\"%s\"".formatted(units.codecName());
    }

    /** An SEI NAL unit of the messages, then the RBSP trailing bits, with emulation prevention. */
    String sei(String... messages) {
      return withEmulationPrevention(units.seiHeader() + " " + String.join(" ", messages) + " 80");
    }

    /** A length-prefixed sample of the NAL units, then a slice. */
    String sample(String... nalUnits) {
      var all = new String[nalUnits.length + 1];
      System.arraycopy(nalUnits, 0, all, 0, nalUnits.length);
      all[nalUnits.length] = units.slice();
      return lengthPrefixed(all);
    }

    Answers.AnswersBuilder answers() {
      return Answers.builder()
          .ffprobeOutput(output(codecName() + "," + extradata(units.configurationRecord())));
    }

    /**
     * The stream, when its 3 first frames carry no side data and its 3 first packets hold the
     * samples, then slices.
     */
    ProbeStreamInfo probed(String... samples) {
      return probe(
              answers()
                  .firstFrames(frames("", "", ""))
                  .firstPackets(packetsUpTo(3, sample(), samples))
                  .build())
          .getStreams(0);
    }
  }

  @Builder
  private record CodingUnits(
      String codecName,
      String configurationRecord,
      String seiHeader,
      String slice,
      String parameterSet) {}

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
