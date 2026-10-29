package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.ffprobeDump;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeOutput;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.VideoCodecProfile;
import build.buf.gen.streamarr.transcode.v1.VideoCodecTier;
import java.util.HexFormat;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Codec Profile Tests")
class FfprobeExecutorCodecProfileTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordedStreams")
  @DisplayName(
      "Should report the profile, level and tier that ffprobe names when the pinned image probes"
          + " the stream")
  void shouldReportTheProfileLevelAndTierThatFfprobeNamesWhenThePinnedImageProbesTheStream(
      RecordedStream recorded) {
    var video = streamOf(recorded.fixture(), recorded.streamIndex());

    assertThat(video.getCodecProfile()).isEqualTo(recorded.profile());
    assertThat(video.getCodecLevel()).isEqualTo(recorded.level());
    assertThat(video.getCodecTier()).isEqualTo(recorded.tier());
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"mp4-avc-record-level.mp4, 10", "mp4-hevc-record-level.mp4, 10"})
  @DisplayName("Should report the parameter set's level when the record header names another level")
  void shouldReportTheParameterSetsLevelWhenTheRecordHeaderNamesAnotherLevel(
      String fixture, int level) {
    assertThat(streamOf(fixture, 0).getCodecLevel()).isEqualTo(level);
  }

  static Stream<Named<RecordedStream>> recordedStreams() {
    return Stream.of(
            h264Stream()
                .fixture("mp4-sdr8-bt709.mp4")
                .profile(VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_CONSTRAINED_BASELINE)
                .level(10),
            h264Stream()
                .fixture("mov-phone-slow-motion.mov")
                .streamIndex(1)
                .profile(VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_HIGH)
                .level(51),
            h264Stream()
                .fixture("mpegts-avchd-interlaced.mts")
                .profile(VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_HIGH)
                .level(40),
            mainTierStream()
                .fixture("mp4-hevc-hdr10.mp4")
                .profile(VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN_10)
                .level(41),
            RecordedStream.builder()
                .fixture("mpegts-dovi-p7.ts")
                .profile(VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN_10)
                .level(51)
                .tier(VideoCodecTier.VIDEO_CODEC_TIER_HIGH),
            mainTierStream()
                .fixture("mpegts-hevc-ac3.ts")
                .profile(VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN)
                .level(10),
            mainTierStream()
                .fixture("mkv-pipe-hevc-10bit.mkv")
                .profile(VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_FORMAT_RANGE_EXTENSIONS)
                .level(10),
            mainTierStream()
                .fixture("mp4-av1-10bit.mp4")
                .profile(VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN)
                .level(20),
            mainTierStream()
                .fixture("ivf-av1-10bit.ivf")
                .profile(VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN)
                .level(20))
        .map(RecordedStream.RecordedStreamBuilder::build)
        .map(
            recorded ->
                Named.of(recorded.fixture() + " stream " + recorded.streamIndex(), recorded));
  }

  private static RecordedStream.RecordedStreamBuilder h264Stream() {
    return RecordedStream.builder().tier(VideoCodecTier.VIDEO_CODEC_TIER_UNSPECIFIED);
  }

  private static RecordedStream.RecordedStreamBuilder mainTierStream() {
    return RecordedStream.builder().tier(VideoCodecTier.VIDEO_CODEC_TIER_MAIN);
  }

  @Nested
  @DisplayName("H.264")
  class H264Tests {

    @ParameterizedTest(name = "profile_idc {0}, constraint flags {1}")
    @CsvSource({
      "66, 40, VIDEO_CODEC_PROFILE_H264_CONSTRAINED_BASELINE",
      "66, 00, VIDEO_CODEC_PROFILE_H264_BASELINE",
      "77, 40, VIDEO_CODEC_PROFILE_H264_MAIN",
      "88, 00, VIDEO_CODEC_PROFILE_H264_EXTENDED",
      "100, 00, VIDEO_CODEC_PROFILE_H264_HIGH",
      "110, 10, VIDEO_CODEC_PROFILE_H264_HIGH_10",
      "122, 00, VIDEO_CODEC_PROFILE_H264_HIGH_422",
      "244, 00, VIDEO_CODEC_PROFILE_H264_HIGH_444_PREDICTIVE"
    })
    @DisplayName("Should name the profile when the SPS carries a profile the contract names")
    void shouldNameTheProfileWhenTheSpsCarriesAProfileTheContractNames(
        int profileIdc, String constraintFlags, VideoCodecProfile profile) {
      var video =
          h264(
              AvcSps.builder()
                  .profileIdc(profileIdc)
                  .constraintFlags(Integer.parseInt(constraintFlags, 16))
                  .levelIdc(41));

      assertThat(video.getCodecProfile()).isEqualTo(profile);
      assertThat(video.getCodecLevel()).isEqualTo(41);
      assertThat(video.getCodecTier()).isEqualTo(VideoCodecTier.VIDEO_CODEC_TIER_UNSPECIFIED);
    }

    @ParameterizedTest(name = "profile_idc {0}")
    @CsvSource({"44", "118", "128"})
    @DisplayName(
        "Should leave the profile unspecified and the level absent when the contract names no such"
            + " profile")
    void shouldLeaveTheProfileUnspecifiedAndTheLevelAbsentWhenTheContractNamesNoSuchProfile(
        int profileIdc) {
      var video = h264(AvcSps.builder().profileIdc(profileIdc).levelIdc(41));

      assertThat(video.getCodecProfile())
          .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED);
      assertThat(video.hasCodecLevel()).isFalse();
    }

    @ParameterizedTest(name = "profile_idc {0}, constraint flags {1}, level_idc {2}")
    @CsvSource({"66, 50, 11", "77, 10, 11", "88, 10, 11", "100, 00, 9"})
    @DisplayName("Should leave the level absent when the SPS signals level 1b")
    void shouldLeaveTheLevelAbsentWhenTheSpsSignalsLevel1b(
        int profileIdc, String constraintFlags, int levelIdc) {
      var video =
          h264(
              AvcSps.builder()
                  .profileIdc(profileIdc)
                  .constraintFlags(Integer.parseInt(constraintFlags, 16))
                  .levelIdc(levelIdc));

      assertThat(video.hasCodecLevel()).isFalse();
    }

    @ParameterizedTest(name = "profile_idc {0}, constraint flags {1}, level_idc {2}")
    @CsvSource({"100, 10, 11", "77, 00, 11", "66, 10, 30"})
    @DisplayName(
        "Should report level_idc when constraint_set3_flag cannot signal level 1b at that profile"
            + " and level")
    void shouldReportLevelIdcWhenConstraintSet3FlagCannotSignalLevel1bAtThatProfileAndLevel(
        int profileIdc, String constraintFlags, int levelIdc) {
      // Only Baseline, Main and Extended read constraint_set3_flag as level 1b, at level_idc 11.
      var video =
          h264(
              AvcSps.builder()
                  .profileIdc(profileIdc)
                  .constraintFlags(Integer.parseInt(constraintFlags, 16))
                  .levelIdc(levelIdc));

      assertThat(video.getCodecLevel()).isEqualTo(levelIdc);
    }

    @Test
    @DisplayName("Should leave the profile unspecified when the SPS ends inside its profile fields")
    void shouldLeaveTheProfileUnspecifiedWhenTheSpsEndsInsideItsProfileFields() {
      var video = stream("h264", "00 00 00 01 67 64 00");

      assertThat(video.getCodecProfile())
          .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED);
    }

    private static ProbeStreamInfo h264(AvcSps.AvcSpsBuilder sps) {
      return stream("h264", sps.build().annexB());
    }
  }

  @Nested
  @DisplayName("HEVC")
  class HevcTests {

    @ParameterizedTest(name = "general_profile_idc {0}")
    @CsvSource({
      "1, VIDEO_CODEC_PROFILE_HEVC_MAIN",
      "2, VIDEO_CODEC_PROFILE_HEVC_MAIN_10",
      "3, VIDEO_CODEC_PROFILE_HEVC_MAIN_STILL_PICTURE",
      "4, VIDEO_CODEC_PROFILE_HEVC_FORMAT_RANGE_EXTENSIONS"
    })
    @DisplayName("Should name the profile when the SPS carries a profile the contract names")
    void shouldNameTheProfileWhenTheSpsCarriesAProfileTheContractNames(
        int profileIdc, VideoCodecProfile profile) {
      var video = hevc(HevcSps.builder().profileIdc(profileIdc).levelIdc(93));

      assertThat(video.getCodecProfile()).isEqualTo(profile);
    }

    @Test
    @DisplayName("Should report the High tier and the level when the SPS sets the tier flag")
    void shouldReportTheHighTierAndTheLevelWhenTheSpsSetsTheTierFlag() {
      var video = hevc(HevcSps.builder().profileIdc(2).highTier(true).levelIdc(153));

      assertThat(video.getCodecTier()).isEqualTo(VideoCodecTier.VIDEO_CODEC_TIER_HIGH);
      assertThat(video.getCodecLevel()).isEqualTo(51);
    }

    @Test
    @DisplayName(
        "Should leave the profile and the tier unspecified and the level absent when the contract"
            + " names no such profile")
    void
        shouldLeaveTheProfileAndTheTierUnspecifiedAndTheLevelAbsentWhenTheContractNamesNoSuchProfile() {
      var video = hevc(HevcSps.builder().profileIdc(9).highTier(true).levelIdc(153));

      assertThat(video.getCodecProfile())
          .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED);
      assertThat(video.getCodecTier()).isEqualTo(VideoCodecTier.VIDEO_CODEC_TIER_UNSPECIFIED);
      assertThat(video.hasCodecLevel()).isFalse();
    }

    @Test
    @DisplayName("Should leave the profile unspecified when the SPS ends inside its profile fields")
    void shouldLeaveTheProfileUnspecifiedWhenTheSpsEndsInsideItsProfileFields() {
      var video = stream("hevc", "00 00 00 01 42 01 01 22 60");

      assertThat(video.getCodecProfile())
          .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED);
    }

    private static ProbeStreamInfo hevc(HevcSps.HevcSpsBuilder sps) {
      return stream("hevc", sps.build().annexB());
    }
  }

  @Nested
  @DisplayName("AV1")
  class Av1Tests {

    @ParameterizedTest(name = "seq_profile {0}")
    @CsvSource({
      "0, VIDEO_CODEC_PROFILE_AV1_MAIN",
      "1, VIDEO_CODEC_PROFILE_AV1_HIGH",
      "2, VIDEO_CODEC_PROFILE_AV1_PROFESSIONAL"
    })
    @DisplayName("Should name the profile when the record carries a profile the contract names")
    void shouldNameTheProfileWhenTheRecordCarriesAProfileTheContractNames(
        int seqProfile, VideoCodecProfile expected) {
      var video = av1(Av1Record.builder().seqProfile(seqProfile).seqLevelIdx(8));

      assertThat(video.getCodecProfile()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "seq_level_idx {0}")
    @CsvSource({"0, 20", "1, 21", "8, 40", "13, 51", "23, 73"})
    @DisplayName("Should report the level as major and minor when the record names a level")
    void shouldReportTheLevelAsMajorAndMinorWhenTheRecordNamesALevel(int seqLevelIdx, int level) {
      var video = av1(Av1Record.builder().seqLevelIdx(seqLevelIdx));

      assertThat(video.getCodecLevel()).isEqualTo(level);
    }

    @Test
    @DisplayName("Should report the High tier when the record sets seq_tier")
    void shouldReportTheHighTierWhenTheRecordSetsSeqTier() {
      var video = av1(Av1Record.builder().seqLevelIdx(13).highTier(true));

      assertThat(video.getCodecTier()).isEqualTo(VideoCodecTier.VIDEO_CODEC_TIER_HIGH);
    }

    @Test
    @DisplayName("Should leave the level absent when the record names level index 31")
    void shouldLeaveTheLevelAbsentWhenTheRecordNamesLevelIndex31() {
      var video = av1(Av1Record.builder().seqLevelIdx(31));

      assertThat(video.getCodecProfile()).isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN);
      assertThat(video.hasCodecLevel()).isFalse();
    }

    @Test
    @DisplayName("Should leave the profile unspecified when the record names a reserved profile")
    void shouldLeaveTheProfileUnspecifiedWhenTheRecordNamesAReservedProfile() {
      var video = av1(Av1Record.builder().seqProfile(3).seqLevelIdx(8));

      assertThat(video.getCodecProfile())
          .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED);
      assertThat(video.getCodecTier()).isEqualTo(VideoCodecTier.VIDEO_CODEC_TIER_UNSPECIFIED);
      assertThat(video.hasCodecLevel()).isFalse();
    }

    @Test
    @DisplayName("Should leave the profile unspecified when the record ends inside its profile")
    void shouldLeaveTheProfileUnspecifiedWhenTheRecordEndsInsideItsProfile() {
      var video = stream("av1", "81 08");

      assertThat(video.getCodecProfile())
          .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED);
    }

    private static ProbeStreamInfo av1(Av1Record.Av1RecordBuilder record) {
      return stream("av1", record.build().bytes());
    }
  }

  @Test
  @DisplayName(
      "Should leave the profile unspecified when the stream carries no extradata, whatever profile"
          + " ffprobe names")
  void
      shouldLeaveTheProfileUnspecifiedWhenTheStreamCarriesNoExtradataWhateverProfileFfprobeNames() {
    var video =
        probeOutput(
                """
                {"streams":[{"index":0,"codec_type":"video","codec_name":"hevc",
                             "profile":"Main 10","level":120,"time_base":"1/1000"}]}
                """)
            .getStreams(0);

    assertThat(video.getCodecProfile())
        .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED);
    assertThat(video.getCodecTier()).isEqualTo(VideoCodecTier.VIDEO_CODEC_TIER_UNSPECIFIED);
    assertThat(video.hasCodecLevel()).isFalse();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("codecsThatTheProbeDoesNotWalk")
  @DisplayName(
      "Should leave the profile unspecified when the codec is not H.264, HEVC or AV1, whatever its"
          + " extradata holds")
  void shouldLeaveTheProfileUnspecifiedWhenTheCodecIsNotH264HevcOrAv1WhateverItsExtradataHolds(
      String codecField) {
    var sps = AvcSps.builder().profileIdc(100).levelIdc(41).build().annexB();

    var video =
        probeOutput(
                """
                {"streams":[{"index":0,"codec_type":"video",%s"time_base":"1/1000",
                             "extradata":"%s"}]}
                """
                    .formatted(codecField, ffprobeDump(HexFormat.ofDelimiter(" ").parseHex(sps))))
            .getStreams(0);

    assertThat(video.getCodecProfile())
        .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED);
    assertThat(video.hasCodecLevel()).isFalse();
  }

  static Stream<Named<String>> codecsThatTheProbeDoesNotWalk() {
    return Stream.of(Named.of("vp9", "\"codec_name\":\"vp9\","), Named.of("no codec_name", ""));
  }

  /** A video stream of the codec whose extradata ffprobe dumps as the given bytes. */
  private static ProbeStreamInfo stream(String codec, String extradata) {
    var bytes = HexFormat.ofDelimiter(" ").parseHex(extradata);
    return probeOutput(
            """
            {"streams":[{"index":0,"codec_type":"video","codec_name":"%s","time_base":"1/1000",
                         "extradata":"%s"}]}
            """
                .formatted(codec, ffprobeDump(bytes)))
        .getStreams(0);
  }

  @Builder
  private record RecordedStream(
      String fixture, int streamIndex, VideoCodecProfile profile, int level, VideoCodecTier tier) {}

  /** An Annex B H.264 SPS: its profile_idc, constraint flags and level_idc, then one more byte. */
  @Builder
  private record AvcSps(int profileIdc, int constraintFlags, int levelIdc) {

    String annexB() {
      return "00 00 00 01 67 %02x %02x %02x 80".formatted(profileIdc, constraintFlags, levelIdc);
    }
  }

  /** An Annex B HEVC SPS whose general profile, tier and level hold the given values. */
  @Builder
  private record HevcSps(int profileIdc, boolean highTier, int levelIdc) {

    String annexB() {
      var tier = highTier ? 1 : 0;
      return "00 00 00 01 42 01 01 %02x 60 01 02 03 90 01 02 03 04 05 %02x 80"
          .formatted((tier << 5) | profileIdc, levelIdc);
    }
  }

  /** An av1C record: the marker and version, then the profile and level, then the tier. */
  @Builder
  private record Av1Record(int seqProfile, int seqLevelIdx, boolean highTier) {

    String bytes() {
      var tier = highTier ? 1 : 0;
      return "81 %02x %02x 00".formatted((seqProfile << 5) | seqLevelIdx, (tier << 7) | 0x40);
    }
  }
}
