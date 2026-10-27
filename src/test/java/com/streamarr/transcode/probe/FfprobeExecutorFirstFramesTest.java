package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.annexB;
import static com.streamarr.transcode.probe.FirstReads.extradata;
import static com.streamarr.transcode.probe.FirstReads.frames;
import static com.streamarr.transcode.probe.FirstReads.lengthPrefixed;
import static com.streamarr.transcode.probe.FirstReads.output;
import static com.streamarr.transcode.probe.FirstReads.packets;
import static com.streamarr.transcode.probe.RecordedFfprobe.ffprobeDump;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.CompatibleBase;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionPresent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
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
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor First Frames Tests")
class FfprobeExecutorFirstFramesTest {

  private static final String HDR10_PLUS =
      """
      {"side_data_type":"HDR Dynamic Metadata SMPTE2094-40 (HDR10+)"}""";
  private static final String RPU_DATA =
      """
      {"side_data_type":"Dolby Vision RPU Data"}""";
  private static final String PROFILE_5_RPU =
      """
      {"side_data_type":"Dolby Vision Metadata","vdr_rpu_profile":0,
       "bl_video_full_range_flag":1,"vdr_bit_depth":12,"el_spatial_resampling_filter_flag":0,
       "disable_residual_flag":1}""";
  private static final String PROFILE_8_RPU =
      """
      {"side_data_type":"Dolby Vision Metadata","vdr_rpu_profile":1,
       "bl_video_full_range_flag":0,"vdr_bit_depth":12,"el_spatial_resampling_filter_flag":0,
       "disable_residual_flag":1}""";
  private static final String MASTERING_DISPLAY =
      """
      {"side_data_type":"Mastering display metadata","red_x":"34000/50000",
       "red_y":"16000/50000","green_x":"13250/50000","green_y":"34500/50000",
       "blue_x":"7500/50000","blue_y":"3000/50000","white_point_x":"15635/50000",
       "white_point_y":"16450/50000","min_luminance":"50/10000",
       "max_luminance":"10000000/10000"}""";
  private static final String LIGHT_LEVEL =
      """
      {"side_data_type":"Content light level metadata","max_content":1000,"max_average":400}""";
  private static final String HEVC = "\"codec_name\":\"hevc\"";
  // An HEVC stream with an hvcC record, so that the probe can delimit its packets' NAL units, and
  // packets whose NAL units and SEI messages the probe delimits, so that they rule out HDR10+ and
  // static metadata.
  private static final String HEVC_WITH_RECORD =
      HEVC
          + ","
          + extradata("01 01 60 00 00 00 90 00 00 00 00 00 5d f0 00 fc fd f8 f8 00 00 0f 00");
  private static final String DELIMITED_PACKETS =
      packets(lengthPrefixed("4e 01 05 01 00 80", "26 01 af 06 b8"));

  @Nested
  @DisplayName("Frames Read")
  class FramesReadTests {

    @Test
    @DisplayName("Should report the first frames read when ffprobe prints a frame")
    void shouldReportTheFirstFramesReadWhenFfprobePrintsAFrame() {
      assertThat(video(HEVC, frames("")).getFirstFramesRead()).isTrue();
    }

    @Test
    @DisplayName("Should keep probe version 4's dynamic range when the first-frames read fails")
    void shouldKeepProbeVersion4sDynamicRangeWhenTheFirstFramesReadFails() {
      var video = probe(Answers.builder().ffprobeOutput(output(HEVC)).build()).getStreams(0);

      assertThat(video.getFirstFramesRead()).isFalse();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{\"frames\":[]}", "{}", "not json"})
    @DisplayName("Should report no frames read when ffprobe prints no frame")
    void shouldReportNoFramesReadWhenFfprobePrintsNoFrame(String printed) {
      var video =
          probe(Answers.builder().ffprobeOutput(output(HEVC)).firstFrames(printed).build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isFalse();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName("Should read the first frames when the first packets decode to no frame")
    void shouldReadTheFirstFramesWhenTheFirstPacketsDecodeToNoFrame() {
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC))
                      .prerollPackets(16)
                      .firstFrames(frames(HDR10_PLUS, HDR10_PLUS, HDR10_PLUS))
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName("Should judge only the first 3 frames when a wider read decodes more")
    void shouldJudgeOnlyTheFirst3FramesWhenAWiderReadDecodesMore() {
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC_WITH_RECORD))
                      .prerollPackets(3)
                      .firstFrames(frames("", "", "", HDR10_PLUS))
                      .firstPackets(DELIMITED_PACKETS)
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName("Should keep a narrower read's frames when a wider read fails")
    void shouldKeepANarrowerReadsFramesWhenAWiderReadFails() {
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC))
                      .firstFrames(frames(HDR10_PLUS))
                      .failingReadsBeyondPackets(3)
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName(
        "Should leave HDR10+ unspecified when a wider read fails before the probe decodes 3 frames")
    void shouldLeaveHdr10PlusUnspecifiedWhenAWiderReadFailsBeforeTheProbeDecodes3Frames() {
      // The frames that the failed read would have decoded could carry HDR10+.
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC))
                      .firstFrames(frames(""))
                      .failingReadsBeyondPackets(3)
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName("Should report HDR10+ absent when the widest read decodes fewer than 3 frames")
    void shouldReportHdr10PlusAbsentWhenTheWidestReadDecodesFewerThan3Frames() {
      // Every read succeeds, so the stream holds no more frames than the probe read.
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC_WITH_RECORD))
                      .firstFrames(frames(""))
                      .firstPackets(DELIMITED_PACKETS)
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "the stream holds more packets than the widest read takes, 288, 2147483647",
      "the probe cannot count the packets, 288, 192"
    })
    @DisplayName(
        "Should leave HDR10+ unspecified when the widest read keeps fewer than 3 frames of a longer"
            + " stream")
    void shouldLeaveHdr10PlusUnspecifiedWhenTheWidestReadKeepsFewerThan3FramesOfALongerStream(
        String reason, int streamPackets, int failingReadsBeyondPackets) {
      // An edit list's preroll can discard most of the widest read's packets, so the stream's
      // later frames, which the probe did not read, could carry HDR10+.
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC_WITH_RECORD))
                      .prerollPackets(48)
                      .firstFrames(frames(""))
                      .streamPackets(streamPackets)
                      .failingReadsBeyondPackets(failingReadsBeyondPackets)
                      .firstPackets(DELIMITED_PACKETS)
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName("Should report no frames read when the widest read decodes no frame")
    void shouldReportNoFramesReadWhenTheWidestReadDecodesNoFrame() {
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC))
                      .prerollPackets(Integer.MAX_VALUE)
                      .firstFrames(frames(HDR10_PLUS))
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isFalse();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName("Should read frames of the chosen video stream only")
    void shouldReadFramesOfTheChosenVideoStreamOnly() {
      var media =
          probe(
              Answers.builder()
                  .ffprobeOutput(
                      """
                      {"streams":[
                        {"index":0,"codec_type":"video","codec_name":"hevc","time_base":"1/1000"},
                        {"index":1,"codec_type":"video","codec_name":"hevc","time_base":"1/1000"}]}
                      """)
                  .firstFrames(frames(HDR10_PLUS))
                  .build());

      assertThat(media.getStreams(0).getFirstFramesRead()).isTrue();
      assertThat(media.getStreams(1).getFirstFramesRead()).isFalse();
      assertThat(media.getStreams(1).getDynamicRange().getHdr10Plus())
          .isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }
  }

  @Nested
  @DisplayName("HDR10+")
  class Hdr10PlusTests {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"hevc", "av1"})
    @DisplayName("Should report HDR10+ present when a frame carries HDR10+ metadata")
    void shouldReportHdr10PlusPresentWhenAFrameCarriesHdr10PlusMetadata(String codec) {
      var video = video(codec(codec), frames("", HDR10_PLUS));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName(
        "Should report HDR10+ absent when neither the frames nor the packets' SEI messages hold it")
    void shouldReportHdr10PlusAbsentWhenNeitherTheFramesNorThePacketsSeiMessagesHoldIt() {
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC_WITH_RECORD))
                      .firstFrames(frames("", ""))
                      .firstPackets(DELIMITED_PACKETS)
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName("Should leave HDR10+ unspecified when an AV1 stream's frames show none")
    void shouldLeaveHdr10PlusUnspecifiedWhenAnAv1StreamsFramesShowNone() {
      // FFmpeg's AV1 decoders drop a frame whose HDR10+ message they cannot parse.
      var video = video(codec("av1"), frames("", "", ""));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"vp9", "mpeg2video"})
    @DisplayName("Should leave HDR10+ unspecified when the probe cannot see it")
    void shouldLeaveHdr10PlusUnspecifiedWhenTheProbeCannotSeeIt(String codec) {
      var video = video(codec(codec), frames(""));

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }
  }

  @Nested
  @DisplayName("Dolby Vision")
  class DolbyVisionTests {

    @Test
    @DisplayName("Should leave Dolby Vision unspecified when the decoder cannot show RPUs")
    void shouldLeaveDolbyVisionUnspecifiedWhenTheDecoderCannotShowRpus() {
      var video = video(codec("vp9"), frames(""));

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    @Test
    @DisplayName("Should leave Dolby Vision unspecified when an AV1 stream's frames show no RPU")
    void shouldLeaveDolbyVisionUnspecifiedWhenAnAv1StreamsFramesShowNoRpu() {
      // FFmpeg's AV1 decoders drop an RPU that they cannot parse.
      var video = video(codec("av1"), frames("", ""));

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    @Test
    @DisplayName(
        "Should leave the observed RPU unspecified when an AV1 stream's record claims RPUs its"
            + " frames do not show")
    void shouldLeaveTheObservedRpuUnspecifiedWhenAnAv1StreamsRecordClaimsRpusItsFramesDoNotShow() {
      var video = video(codec("av1") + "," + record(10, 1), frames("", ""));

      assertThat(video.getDynamicRange().getDolbyVisionPresent().getObservedRpu())
          .isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName("Should report the first RPU's header when a record-less stream carries RPUs")
    void shouldReportTheFirstRpusHeaderWhenARecordLessStreamCarriesRpus() {
      var video =
          video(HEVC, frames(RPU_DATA + "," + PROFILE_5_RPU, RPU_DATA + "," + PROFILE_8_RPU));

      assertThat(video.getDynamicRange().getDolbyVisionPresent())
          .isEqualTo(
              DolbyVisionPresent.newBuilder()
                  .setObservedRpu(Presence.PRESENCE_PRESENT)
                  .setFirstRpuHeader(
                      DolbyVisionRpuHeader.newBuilder()
                          .setVdrRpuProfile(0)
                          .setBaseLayerFullRange(true)
                          .setDisableResidual(true)
                          .setVdrBitDepth(12))
                  .setRpuStartsSample(Presence.PRESENCE_UNSPECIFIED)
                  .build());
    }

    @Test
    @DisplayName("Should report the RPU observed without a header when ffprobe parses no header")
    void shouldReportTheRpuObservedWithoutAHeaderWhenFfprobeParsesNoHeader() {
      var video =
          video(
              HEVC,
              frames(
                  RPU_DATA
                      + ","
                      + """
                      {"side_data_type":"Dolby Vision Metadata","vdr_rpu_profile":0}"""));

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.hasFirstRpuHeader()).isFalse();
    }

    @Test
    @DisplayName("Should report the record's RPU present when a frame carries an RPU")
    void shouldReportTheRecordsRpuPresentWhenAFrameCarriesAnRpu() {
      var video = video(HEVC + "," + record(8, 1), frames(RPU_DATA + "," + PROFILE_8_RPU));

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getFirstRpuHeader().getVdrRpuProfile()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("Profiles Without A Compatible Base")
  class ProfilesWithoutACompatibleBaseTests {

    @Test
    @DisplayName(
        "Should report no compatible base when a record-less HEVC stream's RPU means profile 5")
    void shouldReportNoCompatibleBaseWhenARecordLessHevcStreamsRpuMeansProfile5() {
      var video =
          video(HEVC + ",\"color_transfer\":\"bt709\"", frames(RPU_DATA + "," + PROFILE_5_RPU));

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    @Test
    @DisplayName("Should report no compatible base when the record names profile 5")
    void shouldReportNoCompatibleBaseWhenTheRecordNamesProfile5() {
      var video = video(HEVC + ",\"color_transfer\":\"bt709\"," + record(5, 0), frames(""));

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    @Test
    @DisplayName("Should keep the signaled transfer's base when the record names profile 8")
    void shouldKeepTheSignaledTransfersBaseWhenTheRecordNamesProfile8() {
      // The record decides over the RPU header.
      var video =
          video(
              HEVC + ",\"color_transfer\":\"smpte2084\"," + record(8, 1),
              frames(RPU_DATA + "," + PROFILE_5_RPU));

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_PQ);
    }

    @Test
    @DisplayName("Should keep the signaled transfer's base when the RPU means profile 8")
    void shouldKeepTheSignaledTransfersBaseWhenTheRpuMeansProfile8() {
      var video =
          video(
              HEVC + ",\"color_transfer\":\"arib-std-b67\"",
              frames(RPU_DATA + "," + PROFILE_8_RPU));

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_HLG);
    }

    @Test
    @DisplayName(
        "Should report no compatible base when a record-less AV1 stream's RPU means profile 10.0")
    void shouldReportNoCompatibleBaseWhenARecordLessAv1StreamsRpuMeansProfile10Point0() {
      // No AV1 Dolby Vision sample is at hand, so the frames are constructed. Every AV1 RPU means
      // profile 10, and profile 5's fields mean 10.0, which has no compatible base.
      var video =
          video(
              codec("av1") + ",\"color_transfer\":\"bt709\"",
              frames(RPU_DATA + "," + PROFILE_5_RPU));

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    @Test
    @DisplayName("Should keep the signaled transfer's base when an AV1 RPU has profile 8's fields")
    void shouldKeepTheSignaledTransfersBaseWhenAnAv1RpuHasProfile8sFields() {
      var video =
          video(
              codec("av1") + ",\"color_transfer\":\"smpte2084\"",
              frames(RPU_DATA + "," + PROFILE_8_RPU));

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_PQ);
    }

    @Test
    @DisplayName(
        "Should report no compatible base when the record names profile 10 without a compatible"
            + " base layer")
    void shouldReportNoCompatibleBaseWhenTheRecordNamesProfile10WithoutACompatibleBaseLayer() {
      var video =
          video(codec("av1") + ",\"color_transfer\":\"bt709\"," + record(10, 0), frames(""));

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    @Test
    @DisplayName(
        "Should keep the signaled transfer's base when the record names profile 10 with a"
            + " compatible base layer")
    void shouldKeepTheSignaledTransfersBaseWhenTheRecordNamesProfile10WithACompatibleBaseLayer() {
      // The record decides over the RPU header's profile 10.0 fields.
      var video =
          video(
              codec("av1") + ",\"color_transfer\":\"smpte2084\"," + record(10, 1),
              frames(RPU_DATA + "," + PROFILE_5_RPU));

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_PQ);
    }

    @Test
    @DisplayName(
        "Should keep the signaled transfer's base when a profile 10 record names no compatibility"
            + " identifier")
    void shouldKeepTheSignaledTransfersBaseWhenAProfile10RecordNamesNoCompatibilityIdentifier() {
      var record =
          """
          "side_data_list":[{"side_data_type":"DOVI configuration record","dv_profile":10,
            "dv_level":6,"rpu_present_flag":1,"el_present_flag":0,"bl_present_flag":1}]""";
      var video = video(codec("av1") + ",\"color_transfer\":\"bt709\"," + record, frames(""));

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_SDR);
    }
  }

  @Nested
  @DisplayName("Static HDR Metadata")
  class StaticHdrMetadataTests {

    @Test
    @DisplayName("Should take the first frame's values when the container declares none")
    void shouldTakeTheFirstFramesValuesWhenTheContainerDeclaresNone() {
      var video = video(HEVC, frames(MASTERING_DISPLAY + "," + LIGHT_LEVEL));

      assertThat(video.getMasteringDisplay().getMaxLuminance()).isEqualTo(10_000_000);
      assertThat(video.getMasteringDisplay().getMinLuminance()).isEqualTo(50);
      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(video.getContentLightLevel().getMaxFrameAverageLightLevel()).isEqualTo(400);
    }

    @Test
    @DisplayName("Should keep the container's values when the first frame carries others")
    void shouldKeepTheContainersValuesWhenTheFirstFrameCarriesOthers() {
      var video =
          video(
              HEVC
                  + ",\"side_data_list\":[{\"side_data_type\":\"Content light level metadata\","
                  + "\"max_content\":600,\"max_average\":200}]",
              frames(LIGHT_LEVEL));

      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(600);
    }

    @Test
    @DisplayName("Should read only the first frame's values when a later frame carries them")
    void shouldReadOnlyTheFirstFramesValuesWhenALaterFrameCarriesThem() {
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC_WITH_RECORD))
                      .firstFrames(frames("", MASTERING_DISPLAY + "," + LIGHT_LEVEL))
                      .firstPackets(DELIMITED_PACKETS)
                      .build())
              .getStreams(0);

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName(
        "Should mark an AV1 stream's static metadata omitted when its first frame has none")
    void shouldMarkAnAv1StreamsStaticMetadataOmittedWhenItsFirstFrameHasNone() {
      // FFmpeg's AV1 decoders drop a frame whose metadata OBU they cannot parse, and the frames
      // that depend on it, so the first frame that the probe keeps can lack what the stream
      // carries.
      var video = video(codec("av1"), frames("", MASTERING_DISPLAY + "," + LIGHT_LEVEL, ""));

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @Test
    @DisplayName("Should take an AV1 stream's static metadata from its first frame unflagged")
    void shouldTakeAnAv1StreamsStaticMetadataFromItsFirstFrameUnflagged() {
      var video = video(codec("av1"), frames(MASTERING_DISPLAY + "," + LIGHT_LEVEL, "", ""));

      assertThat(video.getMasteringDisplay().getMaxLuminance()).isEqualTo(10_000_000);
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName("Should leave a VP9 stream's missing static metadata unflagged")
    void shouldLeaveAVp9StreamsMissingStaticMetadataUnflagged() {
      var video = video(codec("vp9"), frames("", "", ""));

      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName("Should mark the first frame's values omitted when they do not fit")
    void shouldMarkTheFirstFramesValuesOmittedWhenTheyDoNotFit() {
      var video =
          video(
              HEVC,
              frames(
                  """
                  {"side_data_type":"Mastering display metadata","min_luminance":"50/10000",
                   "max_luminance":"10000000/10000"},
                  {"side_data_type":"Content light level metadata","max_content":70000,
                   "max_average":400}"""));

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }
  }

  @Nested
  @DisplayName("H.264 RPUs")
  class H264RpuTests {

    // An avcC record whose NAL units take 4-byte lengths, with an SPS and a PPS.
    private static final String AVC_RECORD =
        "01 64 00 28 ff e1 00 04 67 64 00 28 01 00 04 68 ee 3c 80";
    private static final String SEI = "06 05 01 00 80";
    private static final String IDR_SLICE = "65 88 84 00 18";
    private static final String SLICE = "41 9a 24 6c 41";
    private static final String AUD = "09 f0";
    // A profile 9 sample's first RPU, cut after its header.
    private static final String RPU = "1c 19 08 09 08 40 61 59 41 bc 03 fe";

    @Test
    @DisplayName("Should report the RPU and its header when each RPU follows its picture")
    void shouldReportTheRpuAndItsHeaderWhenEachRpuFollowsItsPicture() {
      var present =
          h264(packets(
                  lengthPrefixed(SEI, IDR_SLICE, RPU),
                  lengthPrefixed(SLICE, RPU),
                  lengthPrefixed(SLICE, RPU)))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present)
          .isEqualTo(
              DolbyVisionPresent.newBuilder()
                  .setObservedRpu(Presence.PRESENCE_PRESENT)
                  .setFirstRpuHeader(
                      DolbyVisionRpuHeader.newBuilder()
                          .setVdrRpuProfile(1)
                          .setDisableResidual(true)
                          .setVdrBitDepth(12))
                  .setRpuStartsSample(Presence.PRESENCE_ABSENT)
                  .build());
    }

    @Test
    @DisplayName("Should report an RPU starting a sample when an RPU precedes the sample's picture")
    void shouldReportAnRpuStartingASampleWhenAnRpuPrecedesTheSamplesPicture() {
      var present =
          h264(packets(
                  lengthPrefixed(AUD, SEI, IDR_SLICE),
                  lengthPrefixed(AUD, RPU, SLICE),
                  lengthPrefixed(AUD, RPU, SLICE)))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getRpuStartsSample()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName("Should report an RPU starting a sample when a sample holds only an RPU")
    void shouldReportAnRpuStartingASampleWhenASampleHoldsOnlyAnRpu() {
      var present =
          h264(packets(lengthPrefixed(IDR_SLICE), lengthPrefixed(RPU)))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getRpuStartsSample()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName("Should read the RPUs from start codes when MPEG-TS carries the stream")
    void shouldReadTheRpusFromStartCodesWhenMpegTsCarriesTheStream() {
      var present =
          h264(
                  "00 00 00 01 67 64 00 28 01 00 00 00 01 68 ee 3c 80",
                  packets(annexB(AUD, SEI, IDR_SLICE, RPU), annexB(AUD, SLICE, RPU)))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuStartsSample()).isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(present.getFirstRpuHeader().getVdrBitDepth()).isEqualTo(12);
    }

    @Test
    @DisplayName(
        "Should leave Dolby Vision and HDR10+ unspecified when Annex B extradata can hold units the"
            + " probe does not read")
    void
        shouldLeaveDolbyVisionAndHdr10PlusUnspecifiedWhenAnnexBExtradataCanHoldUnitsTheProbeDoesNotRead() {
      var video =
          h264(
              "00 00 00 01 67 64 00 28 01 00 00 00 01 68 ee 3c 80",
              packets(annexB(AUD, SEI, IDR_SLICE), annexB(AUD, SLICE)));

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @Test
    @DisplayName("Should report Dolby Vision and HDR10+ absent when the packets hold neither")
    void shouldReportDolbyVisionAndHdr10PlusAbsentWhenThePacketsHoldNeither() {
      var video =
          h264(
              packets(
                  lengthPrefixed(SEI, IDR_SLICE),
                  lengthPrefixed("1c 18 08 09"),
                  lengthPrefixed(SLICE)));

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName("Should keep the RPU and sample layout unspecified when the packet read fails")
    void shouldKeepTheRpuAndSampleLayoutUnspecifiedWhenThePacketReadFails() {
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(h264Output(AVC_RECORD, ",\"codec_tag_string\":\"dvav\""))
                      .firstFrames(frames(""))
                      .build())
              .getStreams(0);

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(present.getRpuStartsSample()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName("Should leave Dolby Vision unspecified when the probe read no frames")
    void shouldLeaveDolbyVisionUnspecifiedWhenTheProbeReadNoFrames() {
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(h264Output(AVC_RECORD, ""))
                      .firstPackets(packets(lengthPrefixed(SEI, IDR_SLICE)))
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    @Test
    @DisplayName(
        "Should keep the signaled transfer's base when an H.264 RPU has profile 5's fields")
    void shouldKeepTheSignaledTransfersBaseWhenAnH264RpuHasProfile5sFields() {
      // Every H.264 RPU means profile 9.
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(h264Output(AVC_RECORD, ",\"color_transfer\":\"bt709\""))
                      .firstFrames(frames(""))
                      .firstPackets(
                          packets(lengthPrefixed(IDR_SLICE, "1c 19 08 09 00 40 61 b6 50 6f 00 3f")))
                      .build())
              .getStreams(0);

      assertThat(
              video
                  .getDynamicRange()
                  .getDolbyVisionPresent()
                  .getFirstRpuHeader()
                  .getBaseLayerFullRange())
          .isTrue();
      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_SDR);
    }

    @Test
    @DisplayName("Should read the RPU when emulation prevention bytes sit inside it")
    void shouldReadTheRpuWhenEmulationPreventionBytesSitInsideIt() {
      // 00 00 03 01 carries the bytes 00 00 01; the header ends before them.
      var present =
          h264(packets(lengthPrefixed(IDR_SLICE, RPU + " 00 00 03 01 00")))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getFirstRpuHeader().getVdrRpuProfile()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should report the RPU observed when a sample's length runs past its end")
    void shouldReportTheRpuObservedWhenASamplesLengthRunsPastItsEnd() {
      var present =
          h264(packets(lengthPrefixed(IDR_SLICE, RPU) + " 00 00 01 00 41"))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName("Should leave Dolby Vision unspecified when a sample's length runs past its end")
    void shouldLeaveDolbyVisionUnspecifiedWhenASamplesLengthRunsPastItsEnd() {
      var dynamicRange =
          h264(packets(lengthPrefixed(IDR_SLICE) + " 00 00 01 00 41", lengthPrefixed(SLICE)))
              .getDynamicRange();

      assertThat(dynamicRange.hasDolbyVisionAbsent()).isFalse();
      assertThat(dynamicRange.hasDolbyVisionPresent()).isFalse();
    }

    @Test
    @DisplayName(
        "Should leave Dolby Vision unspecified when bytes too short for a length end a sample")
    void shouldLeaveDolbyVisionUnspecifiedWhenBytesTooShortForALengthEndASample() {
      var dynamicRange =
          h264(packets(lengthPrefixed(IDR_SLICE) + " 00 00", lengthPrefixed(SLICE)))
              .getDynamicRange();

      assertThat(dynamicRange.hasDolbyVisionAbsent()).isFalse();
    }

    @Test
    @DisplayName("Should leave the sample layout unspecified when a sample holds no start code")
    void shouldLeaveTheSampleLayoutUnspecifiedWhenASampleHoldsNoStartCode() {
      var present = mpegTs(packets(lengthPrefixed(AUD), annexB(AUD, SLICE, RPU)));

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuStartsSample()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName(
        "Should leave the sample layout unspecified when bytes ahead of the first start code hold"
            + " data")
    void shouldLeaveTheSampleLayoutUnspecifiedWhenBytesAheadOfTheFirstStartCodeHoldData() {
      // A damaged start code ahead of an RPU, then an access unit delimiter.
      var present = mpegTs(packets("00 01 " + RPU + " " + annexB(AUD), annexB(AUD, SLICE, RPU)));

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuStartsSample()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName("Should read the sample layout when zero bytes lead the first start code")
    void shouldReadTheSampleLayoutWhenZeroBytesLeadTheFirstStartCode() {
      var present = mpegTs(packets("00 00 " + annexB(AUD), annexB(AUD, SLICE, RPU)));

      assertThat(present.getRpuStartsSample()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName(
        "Should leave the sample layout unspecified when a sample's length runs past its end before"
            + " its picture")
    void shouldLeaveTheSampleLayoutUnspecifiedWhenASamplesLengthRunsPastItsEndBeforeItsPicture() {
      var present =
          h264(packets(lengthPrefixed(IDR_SLICE, RPU), lengthPrefixed(AUD) + " 00 00 01 00 41"))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuStartsSample()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    private static ProbeStreamInfo h264(String packets) {
      return h264(AVC_RECORD, packets);
    }

    /** The stream as MPEG-TS carries it, with Annex B extradata. */
    private static DolbyVisionPresent mpegTs(String packets) {
      return h264("00 00 00 01 67 64 00 28 01 00 00 00 01 68 ee 3c 80", packets)
          .getDynamicRange()
          .getDolbyVisionPresent();
    }

    private static ProbeStreamInfo h264(String extradata, String packets) {
      return probe(
              Answers.builder()
                  .ffprobeOutput(h264Output(extradata, ""))
                  .firstFrames(frames(""))
                  .firstPackets(packets)
                  .build())
          .getStreams(0);
    }

    private static String h264Output(String extradata, String fields) {
      return output(
          "\"codec_name\":\"h264\",\"extradata\":\"%s\"%s"
              .formatted(ffprobeDump(HexFormat.ofDelimiter(" ").parseHex(extradata)), fields));
    }
  }

  @Nested
  @DisplayName("HEVC RPUs")
  class HevcRpuTests {

    // An hvcC record without parameter sets whose NAL units take 4-byte lengths.
    private static final String HEVC_RECORD =
        "01 01 60 00 00 00 90 00 00 00 00 00 5d f0 00 fc fd f8 f8 00 00 0f 00";
    private static final String ANNEX_B_VPS =
        "00 00 00 01 40 01 0c 01 ff ff 02 20 00 00 03 00 b0 00 00 03 00 00 03 00 7b 15 c0 90";
    private static final String IDR_SLICE = "26 01 af 06 b8";
    private static final String SLICE = "02 01 d0 09 7e";
    private static final String AUD = "46 01 50";
    // RPUs cut after their headers: one with profile 5's fields and one with profile 8's.
    private static final String PROFILE_5_RPU_UNIT = "7c 01 19 08 09 00 40 61 b6 50 6f 00 3f";
    private static final String PROFILE_8_RPU_UNIT = "7c 01 19 08 09 08 40 61 59 41 bc 03 fe";

    @Test
    @DisplayName(
        "Should report an RPU starting a sample when the decoder ignores RPUs ahead of their"
            + " pictures")
    void shouldReportAnRpuStartingASampleWhenTheDecoderIgnoresRpusAheadOfTheirPictures() {
      // FFmpeg's HEVC decoder exports an RPU only when it is the last NAL unit of its sample.
      var dynamicRange =
          probed(
                  hevc(extradata(HEVC_RECORD) + ",\"color_transfer\":\"bt709\"")
                      .firstFrames(frames("", "", ""))
                      .firstPackets(
                          packets(
                              lengthPrefixed(IDR_SLICE),
                              lengthPrefixed(AUD, PROFILE_5_RPU_UNIT, SLICE),
                              lengthPrefixed(AUD, PROFILE_5_RPU_UNIT, SLICE))))
              .getDynamicRange();

      assertThat(dynamicRange.getDolbyVisionPresent())
          .isEqualTo(
              DolbyVisionPresent.newBuilder()
                  .setObservedRpu(Presence.PRESENCE_PRESENT)
                  .setFirstRpuHeader(
                      DolbyVisionRpuHeader.newBuilder()
                          .setVdrRpuProfile(0)
                          .setBaseLayerFullRange(true)
                          .setDisableResidual(true)
                          .setVdrBitDepth(12))
                  .setRpuStartsSample(Presence.PRESENCE_PRESENT)
                  .build());
      assertThat(dynamicRange.getCompatibleBase()).isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    @Test
    @DisplayName(
        "Should find the RPUs in the packets that hold the frames when an edit list discards the"
            + " first packets")
    void shouldFindTheRpusInThePacketsThatHoldTheFramesWhenAnEditListDiscardsTheFirstPackets() {
      // The first 3 packets decode to no frame, so the frames come from a read of 12 packets.
      var present =
          probed(
                  hevc(extradata(HEVC_RECORD))
                      .prerollPackets(3)
                      .firstFrames(frames("", "", ""))
                      .firstPackets(
                          packets(
                              lengthPrefixed(IDR_SLICE),
                              lengthPrefixed(SLICE),
                              lengthPrefixed(SLICE),
                              lengthPrefixed(AUD, PROFILE_5_RPU_UNIT, SLICE))))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuStartsSample()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName(
        "Should take the frames' RPU header and the packets' layout when each RPU follows its"
            + " picture")
    void shouldTakeTheFramesRpuHeaderAndThePacketsLayoutWhenEachRpuFollowsItsPicture() {
      var present =
          probed(
                  hevc(extradata(HEVC_RECORD))
                      .firstFrames(frames(RPU_DATA + "," + PROFILE_8_RPU))
                      .firstPackets(
                          packets(
                              lengthPrefixed(IDR_SLICE, PROFILE_5_RPU_UNIT),
                              lengthPrefixed(SLICE, PROFILE_5_RPU_UNIT))))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getFirstRpuHeader().getVdrRpuProfile()).isEqualTo(1);
      assertThat(present.getRpuStartsSample()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName("Should read the RPUs from start codes when MPEG-TS carries the stream")
    void shouldReadTheRpusFromStartCodesWhenMpegTsCarriesTheStream() {
      var present =
          probed(
                  hevc(extradata(ANNEX_B_VPS))
                      .firstFrames(frames("", ""))
                      .firstPackets(
                          packets(annexB(IDR_SLICE), annexB(AUD, PROFILE_8_RPU_UNIT, SLICE))))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuStartsSample()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getFirstRpuHeader().getVdrRpuProfile()).isEqualTo(1);
    }

    @Test
    @DisplayName(
        "Should leave Dolby Vision unspecified when a wider read fails before the probe decodes 3"
            + " frames")
    void shouldLeaveDolbyVisionUnspecifiedWhenAWiderReadFailsBeforeTheProbeDecodes3Frames() {
      var video =
          probed(
              hevc(extradata(HEVC_RECORD))
                  .firstFrames(frames(""))
                  .failingReadsBeyondPackets(3)
                  .firstPackets(packets(lengthPrefixed(IDR_SLICE), lengthPrefixed(AUD, SLICE))));

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    @Test
    @DisplayName(
        "Should report Dolby Vision absent when the frames, the packets, the record and the"
            + " sample entry show none")
    void shouldReportDolbyVisionAbsentWhenTheFramesThePacketsTheRecordAndTheSampleEntryShowNone() {
      var video =
          probed(
              hevc(extradata(HEVC_RECORD))
                  .firstFrames(frames("", ""))
                  .firstPackets(packets(lengthPrefixed(IDR_SLICE), lengthPrefixed(AUD, SLICE))));

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
    }

    @Test
    @DisplayName("Should report the record's RPU absent when neither frames nor packets hold one")
    void shouldReportTheRecordsRpuAbsentWhenNeitherFramesNorPacketsHoldOne() {
      var present =
          probed(
                  hevc(extradata(HEVC_RECORD) + "," + record(8, 1))
                      .firstFrames(frames(""))
                      .firstPackets(packets(lengthPrefixed(IDR_SLICE), lengthPrefixed(SLICE))))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(present.hasFirstRpuHeader()).isFalse();
      assertThat(present.getRpuStartsSample()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(present.getDeclared().getProfile()).isEqualTo(8);
    }

    @Test
    @DisplayName(
        "Should keep the record's RPU unspecified when the frames show none and the packet read"
            + " fails")
    void shouldKeepTheRecordsRpuUnspecifiedWhenTheFramesShowNoneAndThePacketReadFails() {
      var video = video(HEVC + "," + record(8, 1), frames(""));

      assertThat(video.getDynamicRange().getDolbyVisionPresent().getObservedRpu())
          .isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName(
        "Should leave Dolby Vision unspecified when the frames show no RPU and the packet read"
            + " fails")
    void shouldLeaveDolbyVisionUnspecifiedWhenTheFramesShowNoRpuAndThePacketReadFails() {
      var video = video(HEVC, frames("", ""));

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    @Test
    @DisplayName("Should leave Dolby Vision unspecified when a sample's length runs past its end")
    void shouldLeaveDolbyVisionUnspecifiedWhenASamplesLengthRunsPastItsEnd() {
      var video =
          probed(
              hevc(extradata(HEVC_RECORD))
                  .firstFrames(frames("", ""))
                  .firstPackets(
                      packets(
                          lengthPrefixed(IDR_SLICE) + " 00 00 01 00 02", lengthPrefixed(SLICE))));

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    /** The answers for an HEVC stream with the given fields; the test adds the reads. */
    private static Answers.AnswersBuilder hevc(String fields) {
      return Answers.builder().ffprobeOutput(output(HEVC + "," + fields));
    }

    private static ProbeStreamInfo probed(Answers.AnswersBuilder answers) {
      return probe(answers.build()).getStreams(0);
    }
  }

  @Nested
  @DisplayName("Recorded Sources")
  class RecordedSourceTests {

    @Test
    @DisplayName(
        "Should read HDR10 from the frames of MPEG-TS but never call a PQ stream without it PQ10")
    void shouldReadHdr10FromTheFramesOfMpegTsButNeverCallAPqStreamWithoutItPq10() {
      // MPEG-TS gives the stream Annex B extradata, which can hold SEI that the probe does not
      // read, so a PQ stream whose frames carry no static metadata may still carry it.
      var hdr10 = streamOf("mpegts-hdr10-sei-only.ts", 0);
      var pq = streamOf("mpegts-pq10.ts", 0);

      assertThat(hdr10.getMasteringDisplay().getMaxLuminance()).isEqualTo(10_000_000);
      assertThat(hdr10.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(pq.getFirstFramesRead()).isTrue();
      assertThat(pq.hasMasteringDisplay()).isFalse();
      assertThat(pq.hasContentLightLevel()).isFalse();
      assertThat(pq.getMasteringDisplayOmitted()).isTrue();
      assertThat(pq.getContentLightLevelOmitted()).isTrue();
      assertThat(pq.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_PQ);
    }

    @Test
    @DisplayName("Should report no static metadata when a PQ sample carries none in its frames")
    void shouldReportNoStaticMetadataWhenAPqSampleCarriesNoneInItsFrames() {
      // The local sample named HDR10 holds only a User Data Unregistered SEI message.
      var video = streamOf("mp4-hevc-hdr10.mp4", 0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.hasContentLightLevel()).isFalse();
    }

    @Test
    @DisplayName("Should report HDR10+ when the FATE HDR10+ sample's frame carries it")
    void shouldReportHdr10PlusWhenTheFateHdr10PlusSamplesFrameCarriesIt() {
      var video = streamOf("hevc-hdr10-plus.hevc", 0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(video.getContentLightLevel().getMaxFrameAverageLightLevel()).isEqualTo(200);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "mp4-dovi-p81.mp4, COMPATIBLE_BASE_PQ",
      "mp4-dovi-p84-hlg.mp4, COMPATIBLE_BASE_HLG"
    })
    @DisplayName("Should report a profile 8 RPU header when a profile 8 sample's frames carry RPUs")
    void shouldReportAProfile8RpuHeaderWhenAProfile8SamplesFramesCarryRpus(
        String fixture, CompatibleBase compatibleBase) {
      var dynamicRange = streamOf(fixture, 0).getDynamicRange();

      assertThat(dynamicRange.getDolbyVisionPresent().getObservedRpu())
          .isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(dynamicRange.getDolbyVisionPresent().getFirstRpuHeader())
          .isEqualTo(
              DolbyVisionRpuHeader.newBuilder()
                  .setVdrRpuProfile(1)
                  .setDisableResidual(true)
                  .setVdrBitDepth(12)
                  .build());
      assertThat(dynamicRange.getCompatibleBase()).isEqualTo(compatibleBase);
    }

    @Test
    @DisplayName(
        "Should leave an AV1 sample's frame features unspecified and its static metadata omitted")
    void shouldLeaveAnAv1SamplesFrameFeaturesUnspecifiedAndItsStaticMetadataOmitted() {
      var video = streamOf("mp4-av1-10bit.mp4", 0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @Test
    @DisplayName("Should take a profile 8.1 sample's static metadata from its first frame")
    void shouldTakeAProfile81SamplesStaticMetadataFromItsFirstFrame() {
      var video = streamOf("mp4-dovi-p81.mp4", 0);

      assertThat(video.getMasteringDisplay().getMaxLuminance()).isEqualTo(10_000_000);
      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(244);
      assertThat(video.getContentLightLevel().getMaxFrameAverageLightLevel()).isEqualTo(145);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"mp4-dovi-p5-no-record-bt709.mp4", "mpegts-dovi-p5-no-descriptor.ts"})
    @DisplayName(
        "Should report no compatible base when a record-less profile 5 stream is tagged BT.709 or"
            + " lacks its descriptor")
    void shouldReportNoCompatibleBaseWhenARecordLessProfile5StreamIsTaggedBt709OrLacksItsDescriptor(
        String fixture) {
      var dynamicRange = streamOf(fixture, 0).getDynamicRange();

      assertThat(dynamicRange.getCompatibleBase()).isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
      assertThat(dynamicRange.getDolbyVisionPresent().hasDeclared()).isFalse();
      assertThat(dynamicRange.getDolbyVisionPresent().getFirstRpuHeader())
          .isEqualTo(
              DolbyVisionRpuHeader.newBuilder()
                  .setVdrRpuProfile(0)
                  .setBaseLayerFullRange(true)
                  .setDisableResidual(true)
                  .setVdrBitDepth(12)
                  .build());
    }

    @Test
    @DisplayName(
        "Should report a profile 7 RPU header when a profile 7 sample's frame carries RPUs")
    void shouldReportAProfile7RpuHeaderWhenAProfile7SamplesFrameCarriesRpus() {
      var present = streamOf("mkv-dovi-p7-hvce.mkv", 0).getDynamicRange().getDolbyVisionPresent();

      assertThat(present.getDeclared().getProfile()).isEqualTo(7);
      assertThat(present.getFirstRpuHeader())
          .isEqualTo(
              DolbyVisionRpuHeader.newBuilder()
                  .setVdrRpuProfile(1)
                  .setEnhancementLayerSpatialResamplingFilter(true)
                  .setVdrBitDepth(12)
                  .build());
    }

    @Test
    @DisplayName(
        "Should leave the base layer's Dolby Vision unspecified when MPEG-TS carries the"
            + " enhancement layer in another stream")
    void
        shouldLeaveTheBaseLayersDolbyVisionUnspecifiedWhenMpegTsCarriesTheEnhancementLayerInAnotherStream() {
      // The base layer is the chosen video stream; the record and the RPUs sit in stream 1. The
      // base layer's Annex B extradata can hold units that the probe does not read.
      var media = RecordedFfprobe.probeFixture("mpegts-dovi-p7-two-layers.ts");

      assertThat(media.getStreams(0).getFirstFramesRead()).isTrue();
      assertThat(media.getStreams(0).getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
      assertThat(media.getStreams(1).getFirstFramesRead()).isFalse();
      assertThat(media.getStreams(1).getDynamicRange().getDolbyVisionPresent().getObservedRpu())
          .isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName(
        "Should find a profile 5 sample's RPUs in its packets when they sit ahead of their"
            + " pictures")
    void shouldFindAProfile5SamplesRpusInItsPacketsWhenTheySitAheadOfTheirPictures() {
      // move-rpus-ahead.py moved each RPU of the FATE sample ahead of the next picture, so FFmpeg's
      // HEVC decoder ignores every RPU and the frames show none.
      var video = streamOf("hevc-dovi-p5-rpus-ahead.hevc", 0);

      assertThat(video.getDynamicRange().getDolbyVisionPresent())
          .isEqualTo(
              DolbyVisionPresent.newBuilder()
                  .setObservedRpu(Presence.PRESENCE_PRESENT)
                  .setFirstRpuHeader(
                      DolbyVisionRpuHeader.newBuilder()
                          .setVdrRpuProfile(0)
                          .setBaseLayerFullRange(true)
                          .setDisableResidual(true)
                          .setVdrBitDepth(12))
                  .setRpuStartsSample(Presence.PRESENCE_PRESENT)
                  .build());
      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    @Test
    @DisplayName("Should find a profile 5 sample's RPUs in its packets after each picture")
    void shouldFindAProfile5SamplesRpusInItsPacketsAfterEachPicture() {
      var dynamicRange = streamOf("mp4-dovi-p5-hev1.mp4", 0).getDynamicRange();

      assertThat(dynamicRange.getDolbyVisionPresent().getObservedRpu())
          .isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(dynamicRange.getDolbyVisionPresent().getRpuStartsSample())
          .isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(dynamicRange.getDolbyVisionPresent().getFirstRpuHeader().getVdrRpuProfile())
          .isZero();
      assertThat(dynamicRange.getCompatibleBase()).isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4-dovi-p9-avc1.mp4", "mp4-dovi-p9-dva1.mp4", "mpegts-dovi-p9.ts"})
    @DisplayName("Should find a profile 9 sample's RPUs in its packets after each picture")
    void shouldFindAProfile9SamplesRpusInItsPacketsAfterEachPicture(String fixture) {
      var dynamicRange = streamOf(fixture, 0).getDynamicRange();

      assertThat(dynamicRange.getDolbyVisionPresent().getObservedRpu())
          .isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(dynamicRange.getDolbyVisionPresent().getRpuStartsSample())
          .isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(dynamicRange.getDolbyVisionPresent().getFirstRpuHeader())
          .isEqualTo(
              DolbyVisionRpuHeader.newBuilder()
                  .setVdrRpuProfile(1)
                  .setDisableResidual(true)
                  .setVdrBitDepth(12)
                  .build());
      assertThat(dynamicRange.getCompatibleBase()).isEqualTo(CompatibleBase.COMPATIBLE_BASE_SDR);
    }

    @Test
    @DisplayName(
        "Should observe a phone original's RPUs in the frames after its edit list's preroll")
    void shouldObserveAPhoneOriginalsRpusInTheFramesAfterItsEditListsPreroll() {
      // The recording's edit list discards its first 16 video packets, so the probe read 48.
      var video = streamOf("mov-phone-one-gap.mov", 0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getDolbyVisionPresent().getObservedRpu())
          .isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(video.getDynamicRange().getDolbyVisionPresent().getFirstRpuHeader())
          .isEqualTo(
              DolbyVisionRpuHeader.newBuilder()
                  .setVdrRpuProfile(1)
                  .setDisableResidual(true)
                  .setVdrBitDepth(12)
                  .build());
    }
  }

  private static String codec(String codec) {
    return "\"codec_name\":\"" + codec + "\"";
  }

  private static String record(int profile, int compatibilityId) {
    return """
        "side_data_list":[{"side_data_type":"DOVI configuration record","dv_profile":%d,
          "dv_level":6,"rpu_present_flag":1,"el_present_flag":0,"bl_present_flag":1,
          "dv_bl_signal_compatibility_id":%d}]"""
        .formatted(profile, compatibilityId);
  }

  private static ProbeStreamInfo video(String fields, String firstFrames) {
    return probe(Answers.builder().ffprobeOutput(output(fields)).firstFrames(firstFrames).build())
        .getStreams(0);
  }
}
