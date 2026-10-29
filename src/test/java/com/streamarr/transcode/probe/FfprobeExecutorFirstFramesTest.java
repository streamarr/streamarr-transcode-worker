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
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

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
  private static final DolbyVisionRpuHeader PROFILE_5_HEADER =
      DolbyVisionRpuHeader.newBuilder()
          .setVdrRpuProfile(0)
          .setBaseLayerFullRange(true)
          .setDisableResidual(true)
          .setVdrBitDepth(12)
          .build();
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
      lengthPrefixed("4e 01 05 01 00 80", HevcSamples.IDR_SLICE);
  private static final String SLICE_SAMPLE = lengthPrefixed(HevcSamples.SLICE);
  private static final String DELIMITED_PACKETS = packetsUpTo(3, SLICE_SAMPLE, DELIMITED_SAMPLE);
  // A sample whose prefix SEI NAL unit holds an HDR10+ message.
  private static final String HDR10_PLUS_SAMPLE =
      lengthPrefixed("4e 01 04 08 b5 00 3c 00 01 04 01 40 80", HevcSamples.SLICE);
  // An av1C record without configOBUs, and a temporal unit of a temporal delimiter and a frame.
  private static final String AV1_RECORD = "81 00 0c 00";
  private static final String AV1_TEMPORAL_UNIT = "12 00 32 03 10 00 80";
  // The most that a first-frames read may print: 4 MiB.
  private static final int FRAMES_READ_LIMIT = 4 << 20;

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

    @Test
    @DisplayName(
        "Should report no frames read when the first-frames read exits with an error after printing"
            + " frames")
    void shouldReportNoFramesReadWhenTheFirstFramesReadExitsWithAnErrorAfterPrintingFrames() {
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC))
                      .firstFrames(frames(HDR10_PLUS, HDR10_PLUS, HDR10_PLUS))
                      .firstFramesExitCode(1)
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isFalse();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
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
    @DisplayName(
        "Should walk only the packets of the first read that decodes 3 frames when a wider read"
            + " decodes more")
    void shouldWalkOnlyThePacketsOfTheFirstReadThatDecodes3FramesWhenAWiderReadDecodesMore() {
      // The probe stops widening at the read of 3 packets, so the HDR10+ message in packet 5 lies
      // past the packets that it walks.
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC_WITH_RECORD))
                      .prerollPackets(3)
                      .prerollFrames(frames("", "", ""))
                      .firstFrames(frames("", "", "", ""))
                      .firstPackets(
                          packetsUpTo(
                              12,
                              SLICE_SAMPLE,
                              DELIMITED_SAMPLE,
                              SLICE_SAMPLE,
                              SLICE_SAMPLE,
                              HDR10_PLUS_SAMPLE))
                      .build())
              .getStreams(0);

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
                      .ffprobeOutput(output(HEVC_WITH_RECORD))
                      .firstFrames(frames(""))
                      .failingReadsBeyondPackets(3)
                      .firstPackets(DELIMITED_PACKETS)
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
                      .firstPackets(packetsUpTo(192, SLICE_SAMPLE, DELIMITED_SAMPLE))
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @Timeout(60)
    @DisplayName("Should report no frames read when the frames' read prints more than 4 MiB")
    void shouldReportNoFramesReadWhenTheFramesReadPrintsMoreThan4MiB() {
      // One AV1 packet can show an earlier frame thousands of times, so a read of a few packets
      // can print millions of frames.
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC_WITH_RECORD))
                      .firstFrames(framesPrintedIn(FRAMES_READ_LIMIT + 1))
                      .firstPackets(DELIMITED_PACKETS)
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isFalse();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @Timeout(60)
    @DisplayName("Should read the frames when the frames' read prints exactly 4 MiB")
    void shouldReadTheFramesWhenTheFramesReadPrintsExactly4MiB() {
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC_WITH_RECORD))
                      .firstFrames(framesPrintedIn(FRAMES_READ_LIMIT))
                      .firstPackets(DELIMITED_PACKETS)
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    /** Three frames that carry HDR10+, padded with whitespace to the byte count. */
    private static String framesPrintedIn(int bytes) {
      var printed = frames(HDR10_PLUS, HDR10_PLUS, HDR10_PLUS);
      var withoutEnd = printed.substring(0, printed.length() - 1);
      return withoutEnd + " ".repeat(bytes - printed.length()) + "}";
    }

    @Test
    @DisplayName("Should report no frames read when the read of 192 packets decodes no frame")
    void shouldReportNoFramesReadWhenTheReadOf192PacketsDecodesNoFrame() {
      // A read of more than 192 packets would decode the frame.
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC))
                      .prerollPackets(192)
                      .firstFrames(frames(HDR10_PLUS))
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isFalse();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName(
        "Should read frames of the chosen video stream only when the source has two video streams")
    void shouldReadFramesOfTheChosenVideoStreamOnlyWhenTheSourceHasTwoVideoStreams() {
      // The scripted ffprobe prints the frames only for a read that selects stream 0.
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
    @DisplayName(
        "Should leave HDR10+ unspecified when the probe walks no packets of the stream's codec")
    void shouldLeaveHdr10PlusUnspecifiedWhenTheProbeWalksNoPacketsOfTheStreamsCodec(String codec) {
      // The frames show no HDR10+, and only a walk of the packets can rule it out.
      var video = video(codec(codec), frames("", "", ""));

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
      var video = video(codec("av1"), frames("", "", ""));

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    @Test
    @DisplayName(
        "Should leave the observed RPU unspecified when an AV1 stream's record claims RPUs its"
            + " frames do not show")
    void shouldLeaveTheObservedRpuUnspecifiedWhenAnAv1StreamsRecordClaimsRpusItsFramesDoNotShow() {
      var video = video(codec("av1") + "," + record(10, 1), frames("", "", ""));

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
                  .setFirstRpuHeader(PROFILE_5_HEADER)
                  .build());
    }

    @Test
    @DisplayName(
        "Should report the header of the first frame that carries an RPU when the first frame"
            + " carries none")
    void shouldReportTheHeaderOfTheFirstFrameThatCarriesAnRpuWhenTheFirstFrameCarriesNone() {
      var video =
          video(HEVC, frames("", RPU_DATA + "," + PROFILE_5_RPU, RPU_DATA + "," + PROFILE_8_RPU));

      assertThat(video.getDynamicRange().getDolbyVisionPresent().getFirstRpuHeader())
          .isEqualTo(PROFILE_5_HEADER);
    }

    @Test
    @DisplayName(
        "Should report the RPU observed when a frame carries only RPU data that ffprobe cannot"
            + " parse")
    void shouldReportTheRpuObservedWhenAFrameCarriesOnlyRpuDataThatFfprobeCannotParse() {
      var video = video(HEVC, frames(RPU_DATA, "", ""));

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.hasFirstRpuHeader()).isFalse();
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("rpusWithoutProfile10Point0sFields")
    @DisplayName(
        "Should keep the signaled transfer's base when an AV1 RPU lacks either of profile 10.0's"
            + " fields")
    void shouldKeepTheSignaledTransfersBaseWhenAnAv1RpuLacksEitherOfProfile10Point0sFields(
        String rpu) {
      // Profile 10.0 needs vdr_rpu_profile 0 and a full-range base layer.
      var video =
          video(codec("av1") + ",\"color_transfer\":\"smpte2084\"", frames(RPU_DATA + "," + rpu));

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_PQ);
    }

    static Stream<Named<String>> rpusWithoutProfile10Point0sFields() {
      return Stream.of(
          Named.of("profile 8's fields", PROFILE_8_RPU),
          Named.of("vdr_rpu_profile 0 with a limited-range base layer", rpu(0, 0)),
          Named.of("vdr_rpu_profile 1 with a full-range base layer", rpu(1, 1)));
    }

    @Test
    @DisplayName(
        "Should keep the signaled transfer's base when a record-less HEVC stream's RPU header names"
            + " no profile")
    void shouldKeepTheSignaledTransfersBaseWhenARecordLessHevcStreamsRpuHeaderNamesNoProfile() {
      // FFmpeg guesses profile 5 only from vdr_rpu_profile 0 with a full-range base layer.
      var video = video(HEVC + ",\"color_transfer\":\"bt709\"", frames(RPU_DATA + "," + rpu(0, 0)));

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_SDR);
    }

    @Test
    @DisplayName(
        "Should report no compatible base when a record that names no profile goes with an RPU"
            + " that means profile 5")
    void shouldReportNoCompatibleBaseWhenARecordThatNamesNoProfileGoesWithAnRpuThatMeansProfile5() {
      // As FFmpeg does, the probe guesses the profile from the first RPU header.
      var record =
          """
          "side_data_list":[{"side_data_type":"DOVI configuration record","dv_level":6,
            "rpu_present_flag":1,"el_present_flag":0,"bl_present_flag":1}]""";
      var video =
          video(
              HEVC + ",\"color_transfer\":\"bt709\"," + record,
              frames(RPU_DATA + "," + PROFILE_5_RPU));

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    @Test
    @DisplayName(
        "Should report no compatible base when the record names profile 5 and the probe read no"
            + " frames")
    void shouldReportNoCompatibleBaseWhenTheRecordNamesProfile5AndTheProbeReadNoFrames() {
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(HEVC + ",\"color_transfer\":\"bt709\"," + record(5, 0)))
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isFalse();
      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    @Test
    @DisplayName(
        "Should report no compatible base when an unchosen video stream's record names profile 5")
    void shouldReportNoCompatibleBaseWhenAnUnchosenVideoStreamsRecordNamesProfile5() {
      var media =
          probe(
              Answers.builder()
                  .ffprobeOutput(
                      """
                      {"streams":[
                        {"index":0,"codec_type":"video","codec_name":"hevc","time_base":"1/1000"},
                        {"index":1,"codec_type":"video","codec_name":"hevc","time_base":"1/1000",
                         "color_transfer":"bt709",%s}]}
                      """
                          .formatted(record(5, 0)))
                  .firstFrames(frames("", "", ""))
                  .build());

      assertThat(media.getStreams(1).getFirstFramesRead()).isFalse();
      assertThat(media.getStreams(1).getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
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
                  + ",\"side_data_list\":["
                  + MASTERING_DISPLAY.replace("10000000/10000", "40000000/10000")
                  + ",{\"side_data_type\":\"Content light level metadata\","
                  + "\"max_content\":600,\"max_average\":200}]",
              frames(MASTERING_DISPLAY + "," + LIGHT_LEVEL));

      assertThat(video.getMasteringDisplay().getMaxLuminance()).isEqualTo(40_000_000);
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
        "Should mark an AV1 stream's static metadata omitted when its first frame has none and its"
            + " packet read fails")
    void
        shouldMarkAnAv1StreamsStaticMetadataOmittedWhenItsFirstFrameHasNoneAndItsPacketReadFails() {
      // Without the packets, the walk cannot rule out a metadata OBU that holds either value.
      var video = video(codec("av1"), frames("", MASTERING_DISPLAY + "," + LIGHT_LEVEL, ""));

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @Test
    @DisplayName(
        "Should mark no AV1 static value omitted when its first frame has none and its packets hold"
            + " no metadata OBU")
    void shouldMarkNoAv1StaticValueOmittedWhenItsFirstFrameHasNoneAndItsPacketsHoldNoMetadataObu() {
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output(codec("av1") + "," + extradata(AV1_RECORD)))
                      .firstFrames(frames("", MASTERING_DISPLAY + "," + LIGHT_LEVEL, ""))
                      .firstPackets(packetsUpTo(3, AV1_TEMPORAL_UNIT))
                      .build())
              .getStreams(0);

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName(
        "Should report an AV1 stream's static metadata unflagged when its first frame carries it")
    void shouldReportAnAv1StreamsStaticMetadataUnflaggedWhenItsFirstFrameCarriesIt() {
      var video = video(codec("av1"), frames(MASTERING_DISPLAY + "," + LIGHT_LEVEL, "", ""));

      assertThat(video.getMasteringDisplay().getMaxLuminance()).isEqualTo(10_000_000);
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @ParameterizedTest(name = "{0} frames")
    @ValueSource(ints = {1, 3})
    @DisplayName(
        "Should leave static metadata unflagged when a VP9 stream's frames carry none, even when"
            + " the read is cut short")
    void shouldLeaveStaticMetadataUnflaggedWhenAVp9StreamsFramesCarryNone(int frameCount) {
      // The probe walks no VP9 packets, so only the container and the first frame can give a value.
      var video =
          video(codec("vp9"), frames(Collections.nCopies(frameCount, "").toArray(String[]::new)));

      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName(
        "Should mark the first frame's mastering display omitted when ffprobe prints only its"
            + " luminance")
    void shouldMarkTheFirstFramesMasteringDisplayOmittedWhenFfprobePrintsOnlyItsLuminance() {
      // The probe walks no VP9 packets, so only the value that does not fit can flag it.
      var video =
          video(
              codec("vp9"),
              frames(
                  """
                  {"side_data_type":"Mastering display metadata","min_luminance":"50/10000",
                   "max_luminance":"10000000/10000"}""",
                  "",
                  ""));

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName(
        "Should mark the first frame's light level omitted when its MaxCLL does not fit its SEI"
            + " field")
    void shouldMarkTheFirstFramesLightLevelOmittedWhenItsMaxCllDoesNotFitItsSeiField() {
      var video =
          video(
              codec("vp9"),
              frames(
                  """
                  {"side_data_type":"Content light level metadata","max_content":70000,
                   "max_average":400}""",
                  "",
                  ""));

      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
    }

    @Test
    @DisplayName(
        "Should take the packets' mastering display when a chromaticity of the SEI message is"
            + " exactly 50000")
    void shouldTakeThePacketsMasteringDisplayWhenAChromaticityOfTheSeiMessageIsExactly50000() {
      var masteringDisplay = packetsMasteringDisplay(seiMasteringDisplay(Map.of(2, 50_000)));

      assertThat(masteringDisplay.getMasteringDisplay().getGreen().getY()).isEqualTo(50_000);
      assertThat(masteringDisplay.getMasteringDisplayOmitted()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("seiValuesBeyond50000")
    @DisplayName(
        "Should mark the packets' mastering display omitted when a chromaticity of the SEI message"
            + " lies beyond 50000")
    void shouldMarkThePacketsMasteringDisplayOmittedWhenAChromaticityOfTheSeiMessageLiesBeyond50000(
        String message) {
      var masteringDisplay = packetsMasteringDisplay(message);

      assertThat(masteringDisplay.hasMasteringDisplay()).isFalse();
      assertThat(masteringDisplay.getMasteringDisplayOmitted()).isTrue();
    }

    static Stream<Named<String>> seiValuesBeyond50000() {
      return Stream.of(
          Named.of("green y", seiMasteringDisplay(Map.of(2, 50_001))),
          Named.of("white point x", seiMasteringDisplay(Map.of(12, 50_001))));
    }

    /**
     * The HEVC stream whose first frames carry no static metadata and whose packets hold the
     * mastering display SEI message.
     */
    private static ProbeStreamInfo packetsMasteringDisplay(String message) {
      return probe(
              Answers.builder()
                  .ffprobeOutput(output(HEVC_WITH_RECORD))
                  .firstFrames(frames("", "", ""))
                  .firstPackets(
                      packetsUpTo(3, SLICE_SAMPLE, lengthPrefixed(message, HevcSamples.IDR_SLICE)))
                  .build())
          .getStreams(0);
    }

    /**
     * A prefix SEI NAL unit with a mastering display message (payload type 137): green, blue, red
     * and white point coordinates, then 1000 and 6.5586 cd/m^2, with the 2-byte values at the given
     * payload offsets replaced. No two zero bytes follow each other, so the message needs no
     * emulation prevention.
     */
    private static String seiMasteringDisplay(Map<Integer, Integer> values) {
      var payload =
          ByteBuffer.allocate(24)
              .putShort((short) 13_250)
              .putShort((short) 34_500)
              .putShort((short) 7500)
              .putShort((short) 3000)
              .putShort((short) 34_000)
              .putShort((short) 16_000)
              .putShort((short) 15_635)
              .putShort((short) 16_450)
              .putInt(10_000_000)
              .putInt(65_586);
      values.forEach((offset, value) -> payload.putShort(offset, (short) (int) value));
      return "4e 01 89 18 " + HexFormat.ofDelimiter(" ").formatHex(payload.array()) + " 80";
    }
  }

  @Nested
  @DisplayName("H.264 RPUs")
  class H264RpuTests {

    private static final String AVC_RECORD = AvcSamples.RECORD;
    private static final String SEI = "06 05 01 00 80";
    private static final String IDR_SLICE = AvcSamples.IDR_SLICE;
    private static final String SLICE = AvcSamples.SLICE;
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
    }

    @Test
    @DisplayName(
        "Should mark no static value omitted when Annex B extradata and the packets hold neither")
    void shouldMarkNoStaticValueOmittedWhenAnnexBExtradataAndThePacketsHoldNeither() {
      var video =
          h264(
              "00 00 00 01 67 64 00 28 01 00 00 00 01 68 ee 3c 80",
              packets(annexB(AUD, SEI, IDR_SLICE), annexB(AUD, SLICE), annexB(AUD, SLICE)));

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName(
        "Should report Dolby Vision and HDR10+ absent when the packets' only type 28 unit lacks the"
            + " RPU prefix")
    void shouldReportDolbyVisionAndHdr10PlusAbsentWhenThePacketsOnlyType28UnitLacksTheRpuPrefix() {
      // The type 28 unit's payload starts with 24, not with the RPU prefix 25.
      var video =
          h264(
              packets(
                  lengthPrefixed(SEI, IDR_SLICE),
                  lengthPrefixed("1c 18 08 09"),
                  lengthPrefixed(SLICE)));

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @ParameterizedTest(name = "nal_ref_idc {0}")
    @ValueSource(strings = {"3c", "5c", "7c"})
    @DisplayName("Should observe the RPU when its type 28 unit has a nonzero nal_ref_idc")
    void shouldObserveTheRpuWhenItsType28UnitHasANonzeroNalRefIdc(String header) {
      // The type and the prefix alone decide an H.264 RPU.
      var rpu = RPU.replaceFirst("^1c", header);

      var present =
          h264(packetsUpTo(3, lengthPrefixed(SLICE, rpu), lengthPrefixed(IDR_SLICE, rpu)))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
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
                      // Packets that would rule RPUs out, if the probe walked them.
                      .firstPackets(
                          packetsUpTo(192, lengthPrefixed(SLICE), lengthPrefixed(SEI, IDR_SLICE)))
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
    @DisplayName(
        "Should place each RPU after its own picture when emulation prevention bytes sit inside the"
            + " RPU")
    void shouldPlaceEachRpuAfterItsOwnPictureWhenEmulationPreventionBytesSitInsideTheRpu() {
      // 00 00 03 01 carries the bytes 00 00 01, which a start code would otherwise split.
      var sample = lengthPrefixed(IDR_SLICE, RPU + " 00 00 03 01 00");

      var present = h264(packets(sample, sample, sample)).getDynamicRange().getDolbyVisionPresent();

      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
    }

    @Test
    @DisplayName(
        "Should read the RPU header when an emulation prevention byte sits inside the header")
    void shouldReadTheRpuHeaderWhenAnEmulationPreventionByteSitsInsideTheHeader() {
      // A profile 5 header whose rpu_format of 0 puts two zero bytes ahead of its vdr_bit_depth, so
      // that 03 follows them.
      var rpu = "1c 19 08 00 00 03 40 61 b6 50 6f 00 3f";

      var present =
          h264(packetsUpTo(3, lengthPrefixed(SLICE), lengthPrefixed(IDR_SLICE, rpu)))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getFirstRpuHeader()).isEqualTo(PROFILE_5_HEADER);
    }

    @Test
    @DisplayName("Should take no RPU from a sample when a length runs past the sample's end")
    void shouldTakeNoRpuFromASampleWhenALengthRunsPastTheSamplesEnd() {
      // FFmpeg's splitter rejects the whole sample.
      var dynamicRange =
          h264(packets(lengthPrefixed(IDR_SLICE, RPU) + " 00 00 01 00 41")).getDynamicRange();

      assertThat(dynamicRange.getDolbyVisionCase()).isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    @Test
    @DisplayName("Should leave Dolby Vision unspecified when a sample's length runs past its end")
    void shouldLeaveDolbyVisionUnspecifiedWhenASamplesLengthRunsPastItsEnd() {
      var dynamicRange =
          h264(packetsUpTo(3, lengthPrefixed(SLICE), lengthPrefixed(IDR_SLICE) + " 00 00 01 00 41"))
              .getDynamicRange();

      assertThat(dynamicRange.hasDolbyVisionAbsent()).isFalse();
      assertThat(dynamicRange.hasDolbyVisionPresent()).isFalse();
    }

    @Test
    @DisplayName("Should rule Dolby Vision out when no length runs past the end of a sample")
    void shouldRuleDolbyVisionOutWhenNoLengthRunsPastTheEndOfASample() {
      var dynamicRange =
          h264(packetsUpTo(3, lengthPrefixed(SLICE), lengthPrefixed(IDR_SLICE))).getDynamicRange();

      assertThat(dynamicRange.hasDolbyVisionAbsent()).isTrue();
    }

    @Test
    @DisplayName("Should rule Dolby Vision out when bytes too few for a length end a sample")
    void shouldRuleDolbyVisionOutWhenBytesTooFewForALengthEndASample() {
      // FFmpeg's splitter drops 1 to 3 bytes too few for a length, and the NAL units ahead of them
      // still count.
      var dynamicRange =
          h264(packetsUpTo(3, lengthPrefixed(SLICE), lengthPrefixed(IDR_SLICE) + " 00 00"))
              .getDynamicRange();

      assertThat(dynamicRange.hasDolbyVisionAbsent()).isTrue();
    }

    @Test
    @DisplayName("Should leave the RPU placement unspecified when a sample holds no start code")
    void shouldLeaveTheRpuPlacementUnspecifiedWhenASampleHoldsNoStartCode() {
      var present =
          mpegTs(packetsUpTo(3, annexB(AUD, SLICE), lengthPrefixed(AUD), annexB(AUD, SLICE, RPU)));

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
    }

    @Test
    @DisplayName(
        "Should leave the RPU placement unspecified when bytes ahead of the first start code hold"
            + " data")
    void shouldLeaveTheRpuPlacementUnspecifiedWhenBytesAheadOfTheFirstStartCodeHoldData() {
      // A damaged start code ahead of an RPU, then an access unit delimiter. FFmpeg's splitter
      // skips
      // the bytes ahead of the first start code, which ADR 0036 leaves open, so the walk rules
      // nothing out from such a sample.
      var present =
          mpegTs(
              packetsUpTo(
                  3,
                  annexB(AUD, SLICE),
                  "00 01 " + RPU + " " + annexB(AUD),
                  annexB(AUD, SLICE, RPU)));

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
          h264(packetsUpTo(
                  3,
                  lengthPrefixed(AUD, SLICE),
                  lengthPrefixed(IDR_SLICE, RPU),
                  lengthPrefixed(AUD) + " 00 00 01 00 41"))
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
    private static final String IDR_SLICE = HevcSamples.IDR_SLICE;
    private static final String SLICE = HevcSamples.SLICE;
    private static final String AUD = HevcSamples.AUD;
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
      var dynamicRange = withRpusAheadOfTheirPictures().getDynamicRange();

      assertThat(dynamicRange.getDolbyVisionPresent())
          .isEqualTo(
              DolbyVisionPresent.newBuilder()
                  .setObservedRpu(Presence.PRESENCE_PRESENT)
                  .setFirstRpuHeader(PROFILE_5_HEADER)
                  .setRpuPlacement(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE)
                  .build());
    }

    @Test
    @DisplayName(
        "Should report no compatible base when the packets' RPU header means profile 5 on a BT.709"
            + " stream")
    void shouldReportNoCompatibleBaseWhenThePacketsRpuHeaderMeansProfile5OnABt709Stream() {
      var dynamicRange = withRpusAheadOfTheirPictures().getDynamicRange();

      assertThat(dynamicRange.getCompatibleBase()).isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    /** A BT.709 stream whose frames show no RPU and whose profile 5 RPUs start their samples. */
    private static ProbeStreamInfo withRpusAheadOfTheirPictures() {
      return probed(
          hevc(extradata(HEVC_RECORD) + ",\"color_transfer\":\"bt709\"")
              .firstFrames(frames("", "", ""))
              .firstPackets(
                  packets(
                      lengthPrefixed(IDR_SLICE),
                      lengthPrefixed(AUD, PROFILE_5_RPU_UNIT, SLICE),
                      lengthPrefixed(AUD, PROFILE_5_RPU_UNIT, SLICE))));
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
                      .firstFrames(frames(RPU_DATA + "," + PROFILE_8_RPU, "", ""))
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
                      .firstFrames(frames("", "", ""))
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
                  .firstPackets(
                      packets(
                          lengthPrefixed(IDR_SLICE),
                          lengthPrefixed(AUD, SLICE),
                          lengthPrefixed(AUD, SLICE))));

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
        "Should keep the record's RPU unspecified when neither frames nor packets hold one but the"
            + " frames' read is cut short")
    void
        shouldKeepTheRecordsRpuUnspecifiedWhenNeitherFramesNorPacketsHoldOneButTheFramesReadIsCutShort() {
      // The packets past the kept read, which the walk does not cover, can hold an RPU.
      var present =
          probed(
                  hevc(extradata(HEVC_RECORD) + "," + record(8, 1))
                      .firstFrames(frames(""))
                      .firstPackets(
                          packets(
                              lengthPrefixed(IDR_SLICE),
                              lengthPrefixed(SLICE),
                              lengthPrefixed(SLICE))))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(present.hasFirstRpuHeader()).isFalse();
    }

    @Test
    @DisplayName(
        "Should keep the record's RPU unspecified when the frames show none and the packet read"
            + " fails")
    void shouldKeepTheRecordsRpuUnspecifiedWhenTheFramesShowNoneAndThePacketReadFails() {
      var video =
          probed(hevc(extradata(HEVC_RECORD) + "," + record(8, 1)).firstFrames(frames("", "", "")));

      assertThat(video.getDynamicRange().getDolbyVisionPresent().getObservedRpu())
          .isEqualTo(Presence.PRESENCE_UNSPECIFIED);
    }

    @Test
    @DisplayName(
        "Should leave Dolby Vision unspecified when the frames show no RPU and the packet read"
            + " fails")
    void shouldLeaveDolbyVisionUnspecifiedWhenTheFramesShowNoRpuAndThePacketReadFails() {
      var video = probed(hevc(extradata(HEVC_RECORD)).firstFrames(frames("", "", "")));

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
                  .firstFrames(frames("", "", ""))
                  .firstPackets(
                      packetsUpTo(
                          3,
                          lengthPrefixed(SLICE),
                          lengthPrefixed(IDR_SLICE) + " 00 00 01 00 02")));

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
    @MethodSource("samplesThatMatchNoPlacement")
    @DisplayName("Should leave the RPU placement unspecified when the samples match no placement")
    void shouldLeaveTheRpuPlacementUnspecifiedWhenTheSamplesMatchNoPlacement(List<String> samples) {
      var present = placed(samples.toArray(String[]::new));

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
    }

    static Stream<Named<List<String>>> samplesThatMatchNoPlacement() {
      return Stream.of(
          Named.of(
              "an RPU follows its picture without ending the sample",
              List.of(
                  lengthPrefixed(IDR_SLICE, PROFILE_5_RPU_UNIT, SUFFIX_SEI),
                  lengthPrefixed(SLICE, PROFILE_5_RPU_UNIT))),
          Named.of(
              "one RPU starts its sample and another ends its sample",
              List.of(
                  lengthPrefixed(IDR_SLICE, PROFILE_5_RPU_UNIT),
                  lengthPrefixed(AUD, PROFILE_5_RPU_UNIT, SLICE))),
          Named.of(
              "a sample holds two RPUs",
              List.of(
                  lengthPrefixed(IDR_SLICE),
                  lengthPrefixed(PROFILE_5_RPU_UNIT, SLICE, PROFILE_5_RPU_UNIT))),
          Named.of(
              "a sample ahead of the first picture holds an RPU",
              List.of(lengthPrefixed(AUD, PROFILE_5_RPU_UNIT), lengthPrefixed(IDR_SLICE))));
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
                                  lengthPrefixed(AUD, SLICE),
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
    @MethodSource("samplesWithAType62UnitOffTemporalId0Elsewhere")
    @DisplayName(
        "Should place each RPU after its own picture when a type 62 unit off TemporalId 0 sits"
            + " elsewhere")
    void shouldPlaceEachRpuAfterItsOwnPictureWhenAType62UnitOffTemporalId0SitsElsewhere(
        List<String> samples) {
      var present = placed(samples.toArray(String[]::new));

      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
    }

    static Stream<Named<List<String>>> samplesWithAType62UnitOffTemporalId0Elsewhere() {
      return Stream.of(
          Named.of(
              "a picture's sample holds it ahead of its slice",
              List.of(
                  lengthPrefixed(AUD, OFF_TEMPORAL_ID_0_UNIT, IDR_SLICE, PROFILE_5_RPU_UNIT),
                  lengthPrefixed(SLICE, PROFILE_5_RPU_UNIT))),
          Named.of(
              "a sample holds it and no slice",
              List.of(
                  lengthPrefixed(IDR_SLICE, PROFILE_5_RPU_UNIT),
                  lengthPrefixed(OFF_TEMPORAL_ID_0_UNIT))));
    }

    @Test
    @DisplayName(
        "Should place each RPU after its own picture when a unit with nuh_temporal_id_plus1 0"
            + " follows the RPU")
    void shouldPlaceEachRpuAfterItsOwnPictureWhenAUnitWithNuhTemporalIdPlus1Of0FollowsTheRpu() {
      // FFmpeg's splitter drops the unit, so the RPU still ends what reaches the decoder.
      var dropped = PROFILE_5_RPU_UNIT.replaceFirst("^7c 01", "7c 00");

      var present =
          placed(
              lengthPrefixed(IDR_SLICE, PROFILE_5_RPU_UNIT, dropped),
              lengthPrefixed(SLICE, PROFILE_5_RPU_UNIT, dropped));

      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
    }

    @Test
    @DisplayName(
        "Should leave HDR10+ unspecified when the stream has no extradata to delimit its packets")
    void shouldLeaveHdr10PlusUnspecifiedWhenTheStreamHasNoExtradataToDelimitItsPackets() {
      var video =
          probed(
              Answers.builder()
                  .ffprobeOutput(output(HEVC))
                  .firstFrames(frames("", "", ""))
                  .firstPackets(packetsUpTo(3, annexB(SLICE), annexB(AUD, IDR_SLICE))));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    @Test
    @DisplayName("Should rule nothing out when the probe cannot parse one packet's hex dump")
    void shouldRuleNothingOutWhenTheProbeCannotParseOnePacketsHexDump() {
      var printed =
          (ObjectNode)
              new ObjectMapper()
                  .readTree(packetsUpTo(3, lengthPrefixed(SLICE), lengthPrefixed(AUD, IDR_SLICE)));
      ((ObjectNode) printed.path("packets").get(1)).put("data", "");

      var video =
          probed(
              hevc(extradata(HEVC_RECORD))
                  .firstFrames(frames("", "", ""))
                  .firstPackets(printed.toString()));

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
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
        "Should read HDR10 static metadata when an MPEG-TS stream's first frame carries it")
    void shouldReadHdr10StaticMetadataWhenAnMpegTsStreamsFirstFrameCarriesIt() {
      var hdr10 = streamOf("mpegts-hdr10-sei-only.ts", 0);

      assertThat(hdr10.getMasteringDisplay().getMaxLuminance()).isEqualTo(10_000_000);
      assertThat(hdr10.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
      assertThat(hdr10.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_ABSENT);
    }

    @Test
    @DisplayName("Should rule PQ10 in when an MPEG-TS PQ stream carries no static metadata")
    void shouldRulePq10InWhenAnMpegTsPqStreamCarriesNoStaticMetadata() {
      // MPEG-TS gives the stream its parameter sets as Annex B extradata, which the walk reads with
      // the packets, so a PQ stream whose extradata, frames and packets carry no static metadata is
      // PQ10.
      var pq = streamOf("mpegts-pq10.ts", 0);

      assertThat(pq.getFirstFramesRead()).isTrue();
      assertThat(pq.hasMasteringDisplay()).isFalse();
      assertThat(pq.hasContentLightLevel()).isFalse();
      assertThat(pq.getMasteringDisplayOmitted()).isFalse();
      assertThat(pq.getContentLightLevelOmitted()).isFalse();
      assertThat(pq.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_PQ);
    }

    @Test
    @DisplayName(
        "Should mark a PQ sample's static metadata omitted when its frames carry none and its"
            + " packet read fails")
    void shouldMarkAPqSamplesStaticMetadataOmittedWhenItsFramesCarryNoneAndItsPacketReadFails() {
      // The local sample named HDR10 holds only a User Data Unregistered SEI message. Its packets
      // are not recorded, so the walk cannot rule out the SEI messages of the packets.
      var fixture = "mp4-hevc-hdr10.mp4";
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(RecordedFfprobe.fixture(fixture))
                      .firstFrames(RecordedFfprobe.recordedFirstFrames(fixture).orElseThrow())
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @Test
    @DisplayName("Should report HDR10+ when the FATE HDR10+ sample's frame carries it")
    void shouldReportHdr10PlusWhenTheFateHdr10PlusSamplesFrameCarriesIt() {
      var video = streamOf("hevc-hdr10-plus.hevc", 0);

      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_PRESENT);
    }

    @Test
    @DisplayName(
        "Should take the first frame's light level when the FATE HDR10+ sample's read is cut"
            + " short")
    void shouldTakeTheFirstFramesLightLevelWhenTheFateHdr10PlusSamplesReadIsCutShort() {
      // The sample decodes to 1 frame.
      var video = streamOf("hevc-hdr10-plus.hevc", 0);

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
        "Should leave an AV1 stream's features unspecified and static metadata omitted when the"
            + " first-packets read fails")
    void
        shouldLeaveAnAv1StreamsFeaturesUnspecifiedAndStaticMetadataOmittedWhenTheFirstPacketsReadFails() {
      // Without the packets, the walk of the metadata OBUs rules nothing out.
      var fixture = "mp4-av1-10bit.mp4";
      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(RecordedFfprobe.fixture(fixture))
                      .firstFrames(RecordedFfprobe.recordedFirstFrames(fixture).orElseThrow())
                      .build())
              .getStreams(0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
      assertThat(video.getDynamicRange().getHdr10Plus()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @Test
    @DisplayName(
        "Should take static metadata from the first frame when a profile 8.1 sample's container"
            + " declares none")
    void shouldTakeStaticMetadataFromTheFirstFrameWhenAProfile81SamplesContainerDeclaresNone() {
      // The sample has no mdcv or clli box and no SEI message in its hvcC record.
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

      assertThat(media.getStreams(0).getFirstFramesRead()).isTrue();
      assertThat(media.getStreams(0).getDynamicRange().hasDolbyVisionAbsent()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mpegts-dovi-p7-two-layers.ts", "mp4-dovi-p7-two-tracks.mp4"})
    @DisplayName(
        "Should keep probe version 4's Dolby Vision for the enhancement layer when the probe reads"
            + " no frames of that stream")
    void shouldKeepProbeVersion4sDolbyVisionForTheEnhancementLayerWhenTheProbeReadsNoFramesOfIt(
        String fixture) {
      var enhancementLayer = RecordedFfprobe.probeFixture(fixture).getStreams(1);
      var present = enhancementLayer.getDynamicRange().getDolbyVisionPresent();

      assertThat(enhancementLayer.getFirstFramesRead()).isFalse();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_UNSPECIFIED);
      assertThat(present.getDeclared().getProfile()).isEqualTo(7);
      assertThat(present.getDeclared().getEnhancementLayer()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"mp4-dovi-p81-fate.mp4, true", "hevc-dovi-p81-fate.hevc, false"})
    @DisplayName(
        "Should place a profile 8.1 sample's RPUs after their own pictures when each RPU ends its"
            + " picture's sample")
    void shouldPlaceAProfile81SamplesRpusAfterTheirOwnPicturesWhenEachRpuEndsItsPicturesSample(
        String fixture, boolean declared) {
      // FFmpeg's raw HEVC muxer drops the dvcC record, so only the RPUs show Dolby Vision.
      var dynamicRange = streamOf(fixture, 0).getDynamicRange();

      assertThat(dynamicRange.getDolbyVisionPresent().hasDeclared()).isEqualTo(declared);
      assertThat(dynamicRange.getDolbyVisionPresent().getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4-dovi-p81-fate.mp4", "hevc-dovi-p81-fate.hevc"})
    @DisplayName(
        "Should rule HDR10+ out when a profile 8.1 sample's packets hold no HDR10+ message")
    void shouldRuleHdr10PlusOutWhenAProfile81SamplesPacketsHoldNoHdr10PlusMessage(String fixture) {
      assertThat(streamOf(fixture, 0).getDynamicRange().getHdr10Plus())
          .isEqualTo(Presence.PRESENCE_ABSENT);
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
    @DisplayName(
        "Should place a profile 5 sample's RPUs after their own pictures when each RPU follows its"
            + " picture")
    void shouldPlaceAProfile5SamplesRpusAfterTheirOwnPicturesWhenEachRpuFollowsItsPicture() {
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
    @DisplayName(
        "Should leave the RPU placement unspecified when a suffix SEI NAL unit follows each RPU of"
            + " a profile 8.1 stream")
    void
        shouldLeaveTheRpuPlacementUnspecifiedWhenASuffixSeiNalUnitFollowsEachRpuOfAProfile81Stream() {
      // insert-rpus-before-suffix-sei.py put each RPU after its picture's slice and ahead of the
      // decoded picture hash that x265 writes in a suffix SEI NAL unit, so each RPU neither starts
      // nor ends its sample. Raw HEVC keeps no record, so only the RPUs show Dolby Vision.
      var dynamicRange = streamOf("hevc-dovi-p81-rpus-before-suffix-sei.hevc", 0).getDynamicRange();

      assertThat(dynamicRange.getDolbyVisionPresent())
          .isEqualTo(
              DolbyVisionPresent.newBuilder()
                  .setObservedRpu(Presence.PRESENCE_PRESENT)
                  .setFirstRpuHeader(
                      DolbyVisionRpuHeader.newBuilder()
                          .setVdrRpuProfile(1)
                          .setDisableResidual(true)
                          .setVdrBitDepth(12))
                  .setRpuPlacement(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED)
                  .build());
      assertThat(dynamicRange.getCompatibleBase()).isEqualTo(CompatibleBase.COMPATIBLE_BASE_PQ);
    }

    @Test
    @DisplayName(
        "Should find no Dolby Vision in a profile 8.1 stream's frames alone when a suffix SEI NAL"
            + " unit follows each RPU")
    void
        shouldFindNoDolbyVisionInAProfile81StreamsFramesAloneWhenASuffixSeiNalUnitFollowsEachRpu() {
      // FFmpeg's HEVC decoder reads an RPU only from the last NAL unit of a packet, so the frames
      // carry none. Without the first-packets read, the probe has only the frames to go on.
      var fixture = "hevc-dovi-p81-rpus-before-suffix-sei.hevc";
      var framesOnly =
          probe(
                  Answers.builder()
                      .ffprobeOutput(RecordedFfprobe.fixture(fixture))
                      .firstFrames(RecordedFfprobe.recordedFirstFrames(fixture).orElseThrow())
                      .build())
              .getStreams(0);

      assertThat(framesOnly.getFirstFramesRead()).isTrue();
      assertThat(framesOnly.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
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
    @DisplayName(
        "Should find a profile 9 sample's RPUs in its packets when the H.264 decoder drops every"
            + " RPU")
    void shouldFindAProfile9SamplesRpusInItsPacketsWhenTheH264DecoderDropsEveryRpu(String fixture) {
      // Each RPU follows its picture.
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
        "Should observe a phone original's RPUs when its edit list discards the first packets")
    void shouldObserveAPhoneOriginalsRpusWhenItsEditListDiscardsTheFirstPackets() {
      // The recording's edit list discards its first 16 video packets, so the reads of 3 and 12
      // packets decode no frame and the probe reads 48.
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

  /** The side data of an RPU whose header has the profile-guessing fields, at 12 bits. */
  private static String rpu(int vdrRpuProfile, int baseLayerFullRange) {
    return """
        {"side_data_type":"Dolby Vision Metadata","vdr_rpu_profile":%d,
         "bl_video_full_range_flag":%d,"vdr_bit_depth":12,"el_spatial_resampling_filter_flag":0,
         "disable_residual_flag":1}"""
        .formatted(vdrRpuProfile, baseLayerFullRange);
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
