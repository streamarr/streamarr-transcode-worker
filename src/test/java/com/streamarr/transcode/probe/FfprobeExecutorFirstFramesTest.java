package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.annexB;
import static com.streamarr.transcode.probe.FirstReads.extradata;
import static com.streamarr.transcode.probe.FirstReads.frames;
import static com.streamarr.transcode.probe.FirstReads.lengthPrefixed;
import static com.streamarr.transcode.probe.FirstReads.output;
import static com.streamarr.transcode.probe.FirstReads.packets;
import static com.streamarr.transcode.probe.FirstReads.packetsUpTo;
import static com.streamarr.transcode.probe.FirstReads.withNewExtradata;
import static com.streamarr.transcode.probe.RecordedFfprobe.ffprobeDump;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.CompatibleBase;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionPresent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuPlacement;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.VideoDynamicRange.DolbyVisionCase;
import com.streamarr.transcode.probe.RecordedFfprobe.Answers;
import java.util.Collections;
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
  private static final String DELIMITED_SAMPLE =
      lengthPrefixed("4e 01 05 01 00 80", "26 01 af 06 b8");
  private static final String SLICE_SAMPLE = lengthPrefixed("02 01 d0 09 7e");
  private static final String DELIMITED_PACKETS = packetsUpTo(3, SLICE_SAMPLE, DELIMITED_SAMPLE);

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
                      .firstPackets(packetsUpTo(12, SLICE_SAMPLE, DELIMITED_SAMPLE))
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
    @DisplayName(
        "Should leave HDR10+ unspecified when every read succeeds but decodes fewer than 3 frames")
    void shouldLeaveHdr10PlusUnspecifiedWhenEveryReadSucceedsButDecodesFewerThan3Frames() {
      // ffprobe ends a read without an error when the file fails, so a short read does not show
      // that the stream ends there.
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC_WITH_RECORD))
                      .firstFrames(frames(""))
                      .firstPackets(DELIMITED_PACKETS)
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName(
        "Should leave HDR10+ unspecified when the widest read keeps fewer than 3 frames after an"
            + " edit list's preroll")
    void
        shouldLeaveHdr10PlusUnspecifiedWhenTheWidestReadKeepsFewerThan3FramesAfterAnEditListsPreroll() {
      // An edit list's preroll can discard most of the widest read's packets, so the stream's
      // later frames, which the probe did not read, could carry HDR10+.
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC_WITH_RECORD))
                      .prerollPackets(48)
                      .firstFrames(frames(""))
                      .firstPackets(DELIMITED_PACKETS)
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName("Should report no frames read when the frames' read prints more than it holds")
    void shouldReportNoFramesReadWhenTheFramesReadPrintsMoreThanItHolds() {
      // One AV1 packet can show an earlier frame thousands of times, so a read of a few packets
      // can print millions of frames.
      var manyFrames =
          "{\"frames\":["
              + String.join(",", Collections.nCopies(250_000, "{\"stream_index\":0}"))
              + "]}";
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC_WITH_RECORD))
                      .firstFrames(manyFrames)
                      .firstPackets(DELIMITED_PACKETS)
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isFalse();
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
    @ValueSource(strings = {"hevc", "av1", "vp9"})
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
                      .firstFrames(frames("", "", ""))
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
                      .firstFrames(frames("", MASTERING_DISPLAY + "," + LIGHT_LEVEL, ""))
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
                  .setRpuPlacement(
                      DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE)
                  .build());
    }

    @Test
    @DisplayName(
        "Should report that every RPU starts its sample when the first picture's sample holds none"
            + " and later samples start with one")
    void
        shouldReportThatEveryRpuStartsItsSampleWhenTheFirstPicturesSampleHoldsNoneAndLaterSamplesStartWithOne() {
      var present =
          h264(packets(
                  lengthPrefixed(AUD, SEI, IDR_SLICE),
                  lengthPrefixed(AUD, RPU, SLICE),
                  lengthPrefixed(AUD, RPU, SLICE)))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName("Should report that every RPU starts its sample when a sample holds only an RPU")
    void shouldReportThatEveryRpuStartsItsSampleWhenASampleHoldsOnlyAnRpu() {
      var present =
          h264(packetsUpTo(
                  3, lengthPrefixed(SLICE), lengthPrefixed(IDR_SLICE), lengthPrefixed(RPU)))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);
    }

    @Test
    @DisplayName("Should read the RPUs from start codes when MPEG-TS carries the stream")
    void shouldReadTheRpusFromStartCodesWhenMpegTsCarriesTheStream() {
      var present =
          h264(
                  "00 00 00 01 67 64 00 28 01 00 00 00 01 68 ee 3c 80",
                  packetsUpTo(
                      3,
                      annexB(AUD, SLICE),
                      annexB(AUD, SEI, IDR_SLICE, RPU),
                      annexB(AUD, SLICE, RPU)))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
      assertThat(present.getFirstRpuHeader().getVdrBitDepth()).isEqualTo(12);
    }

    @Test
    @DisplayName(
        "Should report Dolby Vision and HDR10+ absent when Annex B extradata and the packets hold"
            + " neither")
    void shouldReportDolbyVisionAndHdr10PlusAbsentWhenAnnexBExtradataAndThePacketsHoldNeither() {
      var video =
          h264(
              "00 00 00 01 67 64 00 28 01 00 00 00 01 68 ee 3c 80",
              packets(annexB(AUD, SEI, IDR_SLICE), annexB(AUD, SLICE), annexB(AUD, SLICE)));

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
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
    @DisplayName("Should keep the RPU and its placement unspecified when the packet read fails")
    void shouldKeepTheRpuAndItsPlacementUnspecifiedWhenThePacketReadFails() {
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
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
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
    @DisplayName("Should leave the RPU placement unspecified when a sample holds no start code")
    void shouldLeaveTheRpuPlacementUnspecifiedWhenASampleHoldsNoStartCode() {
      var present = mpegTs(packets(lengthPrefixed(AUD), annexB(AUD, SLICE, RPU)));

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
    }

    @Test
    @DisplayName(
        "Should leave the RPU placement unspecified when bytes ahead of the first start code hold"
            + " data")
    void shouldLeaveTheRpuPlacementUnspecifiedWhenBytesAheadOfTheFirstStartCodeHoldData() {
      // A damaged start code ahead of an RPU, then an access unit delimiter.
      var present = mpegTs(packets("00 01 " + RPU + " " + annexB(AUD), annexB(AUD, SLICE, RPU)));

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
    }

    @Test
    @DisplayName("Should read the RPU placement when zero bytes lead the first start code")
    void shouldReadTheRpuPlacementWhenZeroBytesLeadTheFirstStartCode() {
      var present =
          mpegTs(
              packetsUpTo(3, annexB(AUD, SLICE), "00 00 " + annexB(AUD), annexB(AUD, SLICE, RPU)));

      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
    }

    @Test
    @DisplayName(
        "Should leave the RPU placement unspecified when a sample's length runs past its end before"
            + " its picture")
    void shouldLeaveTheRpuPlacementUnspecifiedWhenASamplesLengthRunsPastItsEndBeforeItsPicture() {
      var present =
          h264(packets(lengthPrefixed(IDR_SLICE, RPU), lengthPrefixed(AUD) + " 00 00 01 00 41"))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
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
                  .firstFrames(frames("", "", ""))
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
    // A suffix SEI NAL unit that holds a decoded picture hash message.
    private static final String SUFFIX_SEI = "50 01 84 01 00 80";
    // RPUs cut after their headers: one with profile 5's fields and one with profile 8's.
    private static final String PROFILE_5_RPU_UNIT = "7c 01 19 08 09 00 40 61 b6 50 6f 00 3f";
    private static final String PROFILE_8_RPU_UNIT = "7c 01 19 08 09 08 40 61 59 41 bc 03 fe";
    // Profile 5's RPU on TemporalId 1, which FFmpeg's HEVC decoder never reads as an RPU.
    private static final String OFF_TEMPORAL_ID_0_UNIT = "7c 02 19 08 09 00 40 61 b6 50 6f 00 3f";

    @Test
    @DisplayName(
        "Should report that every RPU starts its sample when the decoder ignores RPUs ahead of"
            + " their pictures")
    void shouldReportThatEveryRpuStartsItsSampleWhenTheDecoderIgnoresRpusAheadOfTheirPictures() {
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
                  .setRpuPlacement(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE)
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
                          packetsUpTo(
                              12,
                              lengthPrefixed(AUD, PROFILE_5_RPU_UNIT, SLICE),
                              lengthPrefixed(IDR_SLICE),
                              lengthPrefixed(SLICE),
                              lengthPrefixed(SLICE))))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);
    }

    @Test
    @DisplayName(
        "Should take the frames' RPU header and the packets' RPU placement when each RPU follows"
            + " its picture")
    void shouldTakeTheFramesRpuHeaderAndThePacketsRpuPlacementWhenEachRpuFollowsItsPicture() {
      var present =
          probed(
                  hevc(extradata(HEVC_RECORD))
                      .firstFrames(frames(RPU_DATA + "," + PROFILE_8_RPU))
                      .firstPackets(
                          packetsUpTo(
                              3,
                              lengthPrefixed(SLICE, PROFILE_5_RPU_UNIT),
                              lengthPrefixed(IDR_SLICE, PROFILE_5_RPU_UNIT),
                              lengthPrefixed(SLICE, PROFILE_5_RPU_UNIT))))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getFirstRpuHeader().getVdrRpuProfile()).isEqualTo(1);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
    }

    @Test
    @DisplayName("Should read the RPUs from start codes when MPEG-TS carries the stream")
    void shouldReadTheRpusFromStartCodesWhenMpegTsCarriesTheStream() {
      var present =
          probed(
                  hevc(extradata(ANNEX_B_VPS))
                      .firstFrames(frames("", ""))
                      .firstPackets(
                          packetsUpTo(
                              3,
                              annexB(AUD, SLICE),
                              annexB(IDR_SLICE),
                              annexB(AUD, PROFILE_8_RPU_UNIT, SLICE))))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);
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
                  .firstFrames(frames("", "", ""))
                  .firstPackets(
                      packets(
                          lengthPrefixed(IDR_SLICE),
                          lengthPrefixed(AUD, SLICE),
                          lengthPrefixed(AUD, SLICE))));

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
    }

    @Test
    @DisplayName("Should report the record's RPU absent when neither frames nor packets hold one")
    void shouldReportTheRecordsRpuAbsentWhenNeitherFramesNorPacketsHoldOne() {
      var present =
          probed(
                  hevc(extradata(HEVC_RECORD) + "," + record(8, 1))
                      .firstFrames(frames("", "", ""))
                      .firstPackets(
                          packets(
                              lengthPrefixed(IDR_SLICE),
                              lengthPrefixed(SLICE),
                              lengthPrefixed(SLICE))))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(present.hasFirstRpuHeader()).isFalse();
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
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
    @DisplayName("Should leave Dolby Vision unspecified when the hvcC record runs past its end")
    void shouldLeaveDolbyVisionUnspecifiedWhenTheHvccRecordRunsPastItsEnd() {
      // The record's one array counts a NAL unit of 255 bytes that it does not hold.
      var video =
          probed(
              hevc(extradata(HEVC_RECORD.replace("0f 00", "0f 01 20 00 01 00 ff")))
                  .firstFrames(frames("", "", ""))
                  .firstPackets(packetsUpTo(3, lengthPrefixed(SLICE), lengthPrefixed(IDR_SLICE))));

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

    @Test
    @DisplayName(
        "Should report that every RPU starts its sample when the first picture's sample starts with"
            + " an RPU")
    void shouldReportThatEveryRpuStartsItsSampleWhenTheFirstPicturesSampleStartsWithAnRpu() {
      var present =
          placed(
              lengthPrefixed(AUD, PROFILE_5_RPU_UNIT, IDR_SLICE),
              lengthPrefixed(AUD, PROFILE_5_RPU_UNIT, SLICE),
              lengthPrefixed(PROFILE_5_RPU_UNIT, SLICE));

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);
    }

    @Test
    @DisplayName(
        "Should report that every RPU starts its sample when the first picture's sample starts with"
            + " an RPU and the last sample holds only an RPU")
    void
        shouldReportThatEveryRpuStartsItsSampleWhenTheFirstPicturesSampleStartsWithAnRpuAndTheLastSampleHoldsOnlyAnRpu() {
      // A shifted stream whose start was cut away: its first picture's sample starts with the RPU
      // of a picture that the cut removed.
      var present =
          placed(
              lengthPrefixed(AUD, PROFILE_5_RPU_UNIT, IDR_SLICE),
              lengthPrefixed(AUD, PROFILE_5_RPU_UNIT, SLICE),
              lengthPrefixed(PROFILE_5_RPU_UNIT));

      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);
    }

    @Test
    @DisplayName(
        "Should report that every RPU starts its sample when the last sample holds only an RPU")
    void shouldReportThatEveryRpuStartsItsSampleWhenTheLastSampleHoldsOnlyAnRpu() {
      var present =
          placed(
              lengthPrefixed(IDR_SLICE),
              lengthPrefixed(PROFILE_5_RPU_UNIT, SLICE),
              lengthPrefixed(PROFILE_5_RPU_UNIT));

      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);
    }

    @Test
    @DisplayName("Should place each RPU after its own picture when every RPU ends its sample")
    void shouldPlaceEachRpuAfterItsOwnPictureWhenEveryRpuEndsItsSample() {
      var present =
          placed(
              lengthPrefixed(AUD, IDR_SLICE, PROFILE_5_RPU_UNIT),
              lengthPrefixed(AUD, SLICE, SUFFIX_SEI, PROFILE_5_RPU_UNIT));

      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = {
          "an RPU follows its picture without ending the sample | 26 01 af 06 b8, 7c 01 19 08 09 00"
              + " 40 61 b6 50 6f 00 3f, 50 01 84 01 00 80 | 02 01 d0 09 7e, 7c 01 19 08 09 00 40 61"
              + " b6 50 6f 00 3f",
          "one RPU starts its sample and another ends its sample | 26 01 af 06 b8, 7c 01 19 08 09"
              + " 00 40 61 b6 50 6f 00 3f | 46 01 50, 7c 01 19 08 09 00 40 61 b6 50 6f 00 3f, 02 01"
              + " d0 09 7e",
          "a sample holds two RPUs | 26 01 af 06 b8 | 7c 01 19 08 09 00 40 61 b6 50 6f 00 3f, 02 01"
              + " d0 09 7e, 7c 01 19 08 09 00 40 61 b6 50 6f 00 3f",
          "a sample ahead of the first picture holds an RPU | 46 01 50, 7c 01 19 08 09 00 40 61 b6"
              + " 50 6f 00 3f | 26 01 af 06 b8"
        })
    @DisplayName("Should leave the RPU placement unspecified when the samples match no placement")
    void shouldLeaveTheRpuPlacementUnspecifiedWhenTheSamplesMatchNoPlacement(
        String description, String first, String second) {
      var present = placed(lengthPrefixed(first.split(", ")), lengthPrefixed(second.split(", ")));

      assertThat(present.getObservedRpu()).as(description).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .as(description)
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
    }

    @Test
    @DisplayName(
        "Should leave the RPU placement unspecified when a packet carries a replacement record")
    void shouldLeaveTheRpuPlacementUnspecifiedWhenAPacketCarriesAReplacementRecord() {
      var present =
          probed(
                  hevc(extradata(HEVC_RECORD))
                      .firstFrames(frames("", "", ""))
                      .firstPackets(
                          withNewExtradata(
                              packets(
                                  lengthPrefixed(IDR_SLICE),
                                  lengthPrefixed(AUD, PROFILE_5_RPU_UNIT, SLICE)),
                              1)))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
    }

    @Test
    @DisplayName(
        "Should rule nothing out and leave the RPU placement unspecified when the packet read"
            + " returns fewer packets than the frames' read took")
    void
        shouldRuleNothingOutAndLeaveTheRpuPlacementUnspecifiedWhenThePacketReadReturnsFewerPacketsThanTheFramesReadTook() {
      // ffprobe ends a read without an error when the file fails, so the packets past the last
      // one that it printed can hold any unit.
      var video =
          probed(
              hevc(extradata(HEVC_RECORD) + "," + record(8, 1))
                  .firstFrames(frames("", "", ""))
                  .firstPackets(
                      packets(
                          lengthPrefixed(IDR_SLICE),
                          lengthPrefixed(AUD, PROFILE_8_RPU_UNIT, SLICE))));

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @Test
    @DisplayName(
        "Should leave Dolby Vision unspecified when the packet read returns fewer packets than the"
            + " frames' read took")
    void
        shouldLeaveDolbyVisionUnspecifiedWhenThePacketReadReturnsFewerPacketsThanTheFramesReadTook() {
      var video =
          probed(
              hevc(extradata(HEVC_RECORD))
                  .firstFrames(frames("", "", ""))
                  .firstPackets(packets(lengthPrefixed(IDR_SLICE), lengthPrefixed(AUD, SLICE))));

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "TemporalId 1, 7c 02",
      "layer 1, 7c 09",
      "layer 32, 7d 01",
      "nuh_temporal_id_plus1 0, 7c 00"
    })
    @DisplayName(
        "Should rule Dolby Vision out when every type 62 unit lies off layer 0 or TemporalId 0")
    void shouldRuleDolbyVisionOutWhenEveryType62UnitLiesOffLayer0OrTemporalId0(
        String description, String header) {
      // FFmpeg's HEVC decoder reads a type 62 NAL unit as an RPU only on layer 0 and TemporalId 0.
      var unit = PROFILE_5_RPU_UNIT.replaceFirst("^7c 01", header);
      var dynamicRange =
          probed(
                  hevc(extradata(HEVC_RECORD))
                      .firstFrames(frames("", "", ""))
                      .firstPackets(
                          packets(
                              lengthPrefixed(IDR_SLICE, unit),
                              lengthPrefixed(SLICE, unit),
                              lengthPrefixed(SLICE, unit))))
              .getDynamicRange();

      assertThat(dynamicRange.hasDolbyVisionAbsent()).as(description).isTrue();
      assertThat(dynamicRange.getCompatibleBase())
          .as(description)
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_UNSPECIFIED);
    }

    @Test
    @DisplayName(
        "Should report the record's RPU absent when the only type 62 units lie off TemporalId 0")
    void shouldReportTheRecordsRpuAbsentWhenTheOnlyType62UnitsLieOffTemporalId0() {
      var present =
          probed(
                  hevc(extradata(HEVC_RECORD) + "," + record(8, 1))
                      .firstFrames(frames("", "", ""))
                      .firstPackets(
                          packets(
                              lengthPrefixed(IDR_SLICE, OFF_TEMPORAL_ID_0_UNIT),
                              lengthPrefixed(SLICE, OFF_TEMPORAL_ID_0_UNIT),
                              lengthPrefixed(SLICE, OFF_TEMPORAL_ID_0_UNIT))))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(present.hasFirstRpuHeader()).isFalse();
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = {
          "a picture's sample holds it ahead of its slice | 46 01 50, 7c 02 19 08 09 00 40 61 b6 50"
              + " 6f 00 3f, 26 01 af 06 b8, 7c 01 19 08 09 00 40 61 b6 50 6f 00 3f | 02 01 d0 09"
              + " 7e, 7c 01 19 08 09 00 40 61 b6 50 6f 00 3f",
          "a sample holds it and no slice | 26 01 af 06 b8, 7c 01 19 08 09 00 40 61 b6 50 6f 00 3f"
              + " | 7c 02 19 08 09 00 40 61 b6 50 6f 00 3f"
        })
    @DisplayName(
        "Should place each RPU after its own picture when a type 62 unit off TemporalId 0 sits"
            + " elsewhere")
    void shouldPlaceEachRpuAfterItsOwnPictureWhenAType62UnitOffTemporalId0SitsElsewhere(
        String description, String first, String second) {
      var present = placed(lengthPrefixed(first.split(", ")), lengthPrefixed(second.split(", ")));

      assertThat(present.getObservedRpu()).as(description).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .as(description)
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
    }

    @Test
    @DisplayName(
        "Should leave the RPU placement unspecified when a type 62 unit off TemporalId 0 follows"
            + " the RPU")
    void shouldLeaveTheRpuPlacementUnspecifiedWhenAType62UnitOffTemporalId0FollowsTheRpu() {
      // The unit is no RPU, but as any other NAL unit it keeps the RPU from ending its sample.
      var present = placed(lengthPrefixed(IDR_SLICE, PROFILE_5_RPU_UNIT, OFF_TEMPORAL_ID_0_UNIT));

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
    }

    private static DolbyVisionPresent placed(String... samples) {
      return probed(
              hevc(extradata(HEVC_RECORD))
                  .firstFrames(frames("", "", ""))
                  .firstPackets(packetsUpTo(3, lengthPrefixed(SLICE), samples)))
          .getDynamicRange()
          .getDolbyVisionPresent();
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
        "Should read HDR10 from the frames of MPEG-TS and rule static metadata out of a PQ stream"
            + " without it")
    void shouldReadHdr10FromTheFramesOfMpegTsAndRuleStaticMetadataOutOfAPqStreamWithoutIt() {
      // MPEG-TS gives the stream its parameter sets as Annex B extradata, which the walk reads with
      // the packets, so a PQ stream whose extradata, frames and packets carry no static metadata is
      // PQ10.
      var hdr10 = streamOf("mpegts-hdr10-sei-only.ts", 0);
      var pq = streamOf("mpegts-pq10.ts", 0);

      assertThat(hdr10.getMasteringDisplay().getMaxLuminance()).isEqualTo(10_000_000);
      assertThat(hdr10.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(hdr10.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(pq.getFirstFramesRead()).isTrue();
      assertThat(pq.hasMasteringDisplay()).isFalse();
      assertThat(pq.hasContentLightLevel()).isFalse();
      assertThat(pq.getMasteringDisplayOmitted()).isFalse();
      assertThat(pq.getContentLightLevelOmitted()).isFalse();
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
      "mp4-dovi-p84-hlg.mp4, COMPATIBLE_BASE_HLG",
      "mp4-dovi-p81-fate.mp4, COMPATIBLE_BASE_PQ",
      "hevc-dovi-p81-fate.hevc, COMPATIBLE_BASE_PQ"
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mpegts-dovi-p7-two-layers.ts", "mp4-dovi-p7-two-tracks.mp4"})
    @DisplayName(
        "Should report the base layer's Dolby Vision absent when another stream carries the"
            + " enhancement layer and the RPUs")
    void
        shouldReportTheBaseLayersDolbyVisionAbsentWhenAnotherStreamCarriesTheEnhancementLayerAndTheRpus(
            String fixture) {
      // The base layer is the chosen video stream; the record and the RPUs sit in stream 1, so the
      // base layer's extradata, frames and packets hold none.
      var media = RecordedFfprobe.probeFixture(fixture);
      var enhancementLayer = media.getStreams(1).getDynamicRange().getDolbyVisionPresent();

      assertThat(media.getStreams(0).getFirstFramesRead()).isTrue();
      assertThat(media.getStreams(0).getDynamicRange().hasDolbyVisionAbsent()).isTrue();
      assertThat(media.getStreams(1).getFirstFramesRead()).isFalse();
      assertThat(enhancementLayer.getObservedRpu()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(enhancementLayer.getDeclared().getProfile()).isEqualTo(7);
      assertThat(enhancementLayer.getDeclared().getEnhancementLayer()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"mp4-dovi-p81-fate.mp4, true", "hevc-dovi-p81-fate.hevc, false"})
    @DisplayName(
        "Should place a profile 8.1 sample's RPUs after their own pictures whether or not its"
            + " record survives the container")
    void
        shouldPlaceAProfile81SamplesRpusAfterTheirOwnPicturesWhetherOrNotItsRecordSurvivesTheContainer(
            String fixture, boolean declared) {
      // FFmpeg's raw HEVC muxer drops the dvcC record, so only the RPUs show Dolby Vision.
      var dynamicRange = streamOf(fixture, 0).getDynamicRange();

      assertThat(dynamicRange.getDolbyVisionPresent().hasDeclared()).isEqualTo(declared);
      assertThat(dynamicRange.getDolbyVisionPresent().getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
      assertThat(dynamicRange.getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName("Should report a VP9 stream's HDR10+ present when its first frame carries it")
    void shouldReportAVp9StreamsHdr10PlusPresentWhenItsFirstFrameCarriesIt() {
      // FFmpeg copies the WebM BlockAdditional that holds the HDR10+ metadata to the frame. The
      // probe walks no VP9 packets, so no feature that the frames do not show is ruled out.
      var video = streamOf("webm-vp9-hdr10-plus.webm", 0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
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
                  .setRpuPlacement(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE)
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
      assertThat(dynamicRange.getDolbyVisionPresent().getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
      assertThat(dynamicRange.getDolbyVisionPresent().getFirstRpuHeader().getVdrRpuProfile())
          .isZero();
      assertThat(dynamicRange.getCompatibleBase()).isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    @Test
    @DisplayName(
        "Should report that every RPU of a profile 5 sample starts its sample when each RPU starts"
            + " its own picture's sample")
    void
        shouldReportThatEveryRpuOfAProfile5SampleStartsItsSampleWhenEachRpuStartsItsOwnPicturesSample() {
      // move-rpus-ahead.py moved each RPU of the FATE sample ahead of its own picture's first
      // slice.
      var present =
          streamOf("hevc-dovi-p5-rpus-before-own-picture.hevc", 0)
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);
      assertThat(present.getFirstRpuHeader().getVdrRpuProfile()).isZero();
    }

    @Test
    @DisplayName("Should rule Dolby Vision out when a profile 5 sample's RPUs lie on TemporalId 1")
    void shouldRuleDolbyVisionOutWhenAProfile5SamplesRpusLieOnTemporalId1() {
      // raise-rpu-temporal-id.py put each RPU of the FATE sample on TemporalId 1, so FFmpeg's HEVC
      // decoder reads none of them, and neither the frames nor the packets hold an RPU.
      var dynamicRange = streamOf("hevc-dovi-p5-rpus-temporal-id-1.hevc", 0).getDynamicRange();

      assertThat(dynamicRange.hasDolbyVisionAbsent()).isTrue();
      assertThat(dynamicRange.getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_UNSPECIFIED);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4-dovi-p9-avc1.mp4", "mp4-dovi-p9-dva1.mp4", "mpegts-dovi-p9.ts"})
    @DisplayName("Should find a profile 9 sample's RPUs in its packets after each picture")
    void shouldFindAProfile9SamplesRpusInItsPacketsAfterEachPicture(String fixture) {
      var dynamicRange = streamOf(fixture, 0).getDynamicRange();

      assertThat(dynamicRange.getDolbyVisionPresent().getObservedRpu())
          .isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(dynamicRange.getDolbyVisionPresent().getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
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
