package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.CompatibleBase;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionAbsent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionPresent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.VideoDynamicRange.DolbyVisionCase;
import com.streamarr.transcode.probe.RecordedFfprobe.Answers;
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"hevc", "av1"})
    @DisplayName("Should report HDR10+ absent when no frame carries HDR10+ metadata")
    void shouldReportHdr10PlusAbsentWhenNoFrameCarriesHdr10PlusMetadata(String codec) {
      var video = video(codec(codec), frames("", ""));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"h264", "vp9", "mpeg2video"})
    @DisplayName("Should leave HDR10+ unspecified when the decoder cannot show it")
    void shouldLeaveHdr10PlusUnspecifiedWhenTheDecoderCannotShowIt(String codec) {
      // FFmpeg 8.1's H.264 decoder logs HDR10+ SEI messages as unsupported and exports none.
      var video = video(codec(codec), frames(""));

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }
  }

  @Nested
  @DisplayName("Dolby Vision")
  class DolbyVisionTests {

    @Test
    @DisplayName(
        "Should report Dolby Vision absent when the frames, the record and the sample entry show"
            + " none")
    void shouldReportDolbyVisionAbsentWhenTheFramesTheRecordAndTheSampleEntryShowNone() {
      var video = video(HEVC, frames("", ""));

      assertThat(video.getDynamicRange().getDolbyVisionAbsent())
          .isEqualTo(DolbyVisionAbsent.getDefaultInstance());
    }

    @Test
    @DisplayName("Should leave Dolby Vision unspecified when the decoder cannot show RPUs")
    void shouldLeaveDolbyVisionUnspecifiedWhenTheDecoderCannotShowRpus() {
      var video = video(codec("vp9"), frames(""));

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
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
    @DisplayName("Should report the record's RPU absent when no frame carries an RPU")
    void shouldReportTheRecordsRpuAbsentWhenNoFrameCarriesAnRpu() {
      var video = video(HEVC + "," + record(8, 1), frames(""));

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_ABSENT);
      assertThat(present.hasFirstRpuHeader()).isFalse();
      assertThat(present.getDeclared().getProfile()).isEqualTo(8);
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
  @DisplayName("Profile 5 Compatible Base")
  class Profile5CompatibleBaseTests {

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
    @DisplayName("Should keep the signaled transfer's base when an AV1 RPU has profile 5's fields")
    void shouldKeepTheSignaledTransfersBaseWhenAnAv1RpuHasProfile5sFields() {
      // Every AV1 RPU means profile 10.
      var video =
          video(
              codec("av1") + ",\"color_transfer\":\"bt709\"",
              frames(RPU_DATA + "," + PROFILE_5_RPU));

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
      var video = video(HEVC, frames("", MASTERING_DISPLAY + "," + LIGHT_LEVEL));

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.hasContentLightLevel()).isFalse();
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
  @DisplayName("Recorded Sources")
  class RecordedSourceTests {

    @Test
    @DisplayName("Should tell HDR10 from PQ10 when only the bitstream carries the static metadata")
    void shouldTellHdr10FromPq10WhenOnlyTheBitstreamCarriesTheStaticMetadata() {
      var hdr10 = streamOf("mpegts-hdr10-sei-only.ts", 0);
      var pq10 = streamOf("mpegts-pq10.ts", 0);

      assertThat(hdr10.getMasteringDisplay().getMaxLuminance()).isEqualTo(10_000_000);
      assertThat(hdr10.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(pq10.getFirstFramesRead()).isTrue();
      assertThat(pq10.hasMasteringDisplay()).isFalse();
      assertThat(pq10.hasContentLightLevel()).isFalse();
      assertThat(pq10.getDynamicRange().getCompatibleBase())
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
      assertThat(video.getDynamicRange().getDolbyVisionAbsent())
          .isEqualTo(DolbyVisionAbsent.getDefaultInstance());
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
        "Should report the base layer's Dolby Vision absent when MPEG-TS carries the enhancement"
            + " layer in another stream")
    void
        shouldReportTheBaseLayersDolbyVisionAbsentWhenMpegTsCarriesTheEnhancementLayerInAnotherStream() {
      // The base layer is the chosen video stream; the record and the RPUs sit in stream 1.
      var media = RecordedFfprobe.probeFixture("mpegts-dovi-p7-two-layers.ts");

      assertThat(media.getStreams(0).getDynamicRange().getDolbyVisionAbsent())
          .isEqualTo(DolbyVisionAbsent.getDefaultInstance());
      assertThat(media.getStreams(1).getFirstFramesRead()).isFalse();
      assertThat(media.getStreams(1).getDynamicRange().getDolbyVisionPresent().getObservedRpu())
          .isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName("Should read no frames when every packet read lies ahead of the edit list")
    void shouldReadNoFramesWhenEveryPacketReadLiesAheadOfTheEditList() {
      // The recording's edit list discards its first 16 video packets.
      var video = streamOf("mov-phone-one-gap.mov", 0);

      assertThat(video.getFirstFramesRead()).isFalse();
      assertThat(video.getDynamicRange().getDolbyVisionPresent().getObservedRpu())
          .isEqualTo(Presence.PRESENCE_UNSPECIFIED);
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

  /** The first-frames read's output: one frame for each argument, holding that side data. */
  private static String frames(String... sideData) {
    var frames = new StringBuilder();
    for (var data : sideData) {
      if (!frames.isEmpty()) {
        frames.append(',');
      }

      frames.append("{\"stream_index\":0,\"side_data_list\":[").append(data).append("]}");
    }

    return "{\"frames\":[" + frames + "]}";
  }

  private static String output(String fields) {
    return """
        {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000",%s}]}
        """
        .formatted(fields);
  }

  private static ProbeStreamInfo video(String fields, String firstFrames) {
    return probe(Answers.builder().ffprobeOutput(output(fields)).firstFrames(firstFrames).build())
        .getStreams(0);
  }
}
