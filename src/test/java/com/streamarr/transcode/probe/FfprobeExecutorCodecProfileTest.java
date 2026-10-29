package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.ffprobeDump;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeOutput;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.VideoCodecProfile;
import build.buf.gen.streamarr.transcode.v1.VideoCodecTier;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Codec Profile Tests")
class FfprobeExecutorCodecProfileTest {

  @ParameterizedTest(name = "{0} stream {1}")
  @CsvSource({
    "mp4-sdr8-bt709.mp4, 0, VIDEO_CODEC_PROFILE_H264_CONSTRAINED_BASELINE, 10,"
        + " VIDEO_CODEC_TIER_UNSPECIFIED",
    "mov-phone-slow-motion.mov, 1, VIDEO_CODEC_PROFILE_H264_HIGH, 51, VIDEO_CODEC_TIER_UNSPECIFIED",
    "mpegts-avchd-interlaced.mts, 0, VIDEO_CODEC_PROFILE_H264_HIGH, 40,"
        + " VIDEO_CODEC_TIER_UNSPECIFIED",
    "mp4-hevc-hdr10.mp4, 0, VIDEO_CODEC_PROFILE_HEVC_MAIN_10, 41, VIDEO_CODEC_TIER_MAIN",
    "mpegts-dovi-p7.ts, 0, VIDEO_CODEC_PROFILE_HEVC_MAIN_10, 51, VIDEO_CODEC_TIER_HIGH",
    "mpegts-hevc-ac3.ts, 0, VIDEO_CODEC_PROFILE_HEVC_MAIN, 10, VIDEO_CODEC_TIER_MAIN",
    "mkv-pipe-hevc-10bit.mkv, 0, VIDEO_CODEC_PROFILE_HEVC_FORMAT_RANGE_EXTENSIONS, 10,"
        + " VIDEO_CODEC_TIER_MAIN",
    "mp4-av1-10bit.mp4, 0, VIDEO_CODEC_PROFILE_AV1_MAIN, 20, VIDEO_CODEC_TIER_MAIN",
    "ivf-av1-10bit.ivf, 0, VIDEO_CODEC_PROFILE_AV1_MAIN, 20, VIDEO_CODEC_TIER_MAIN"
  })
  @DisplayName(
      "Should report the profile, level and tier that ffprobe names when the pinned image probes"
          + " the stream")
  void shouldReportTheProfileLevelAndTierThatFfprobeNamesWhenThePinnedImageProbesTheStream(
      String fixture, int index, VideoCodecProfile profile, int level, VideoCodecTier tier) {
    var video = streamOf(fixture, index);

    assertThat(video.getCodecProfile()).isEqualTo(profile);
    assertThat(video.getCodecLevel()).isEqualTo(level);
    assertThat(video.getCodecTier()).isEqualTo(tier);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"mp4-avc-record-level.mp4, 10", "mp4-hevc-record-level.mp4, 10"})
  @DisplayName("Should report the parameter set's level when the record header names another level")
  void shouldReportTheParameterSetsLevelWhenTheRecordHeaderNamesAnotherLevel(
      String fixture, int level) {
    assertThat(streamOf(fixture, 0).getCodecLevel()).isEqualTo(level);
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
      var video = h264(profileIdc, Integer.parseInt(constraintFlags, 16), 41);

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
      var video = h264(profileIdc, 0, 41);

      assertThat(video.getCodecProfile())
          .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED);
      assertThat(video.hasCodecLevel()).isFalse();
    }

    @ParameterizedTest(name = "profile_idc {0}, constraint flags {1}, level_idc {2}")
    @CsvSource({"66, 50, 11", "77, 10, 11", "88, 10, 11", "100, 00, 9"})
    @DisplayName("Should leave the level absent when the SPS signals level 1b")
    void shouldLeaveTheLevelAbsentWhenTheSpsSignalsLevel1b(
        int profileIdc, String constraintFlags, int levelIdc) {
      var video = h264(profileIdc, Integer.parseInt(constraintFlags, 16), levelIdc);

      assertThat(video.hasCodecLevel()).isFalse();
    }

    @Test
    @DisplayName("Should report level 1.1 when a High profile SPS sets constraint_set3_flag")
    void shouldReportLevel11WhenAHighProfileSpsSetsConstraintSet3Flag() {
      var video = h264(100, 0x10, 11);

      assertThat(video.getCodecLevel()).isEqualTo(11);
    }

    private static ProbeStreamInfo h264(int profileIdc, int constraintFlags, int levelIdc) {
      return stream(
          "h264",
          "00 00 00 01 67 %02x %02x %02x 80".formatted(profileIdc, constraintFlags, levelIdc));
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
      assertThat(hevc(profileIdc, 0, 93).getCodecProfile()).isEqualTo(profile);
    }

    @Test
    @DisplayName("Should report the High tier and the level when the SPS sets the tier flag")
    void shouldReportTheHighTierAndTheLevelWhenTheSpsSetsTheTierFlag() {
      var video = hevc(2, 1, 153);

      assertThat(video.getCodecTier()).isEqualTo(VideoCodecTier.VIDEO_CODEC_TIER_HIGH);
      assertThat(video.getCodecLevel()).isEqualTo(51);
    }

    @Test
    @DisplayName(
        "Should leave the profile and the tier unspecified and the level absent when the contract"
            + " names no such profile")
    void
        shouldLeaveTheProfileAndTheTierUnspecifiedAndTheLevelAbsentWhenTheContractNamesNoSuchProfile() {
      var video = hevc(9, 1, 153);

      assertThat(video.getCodecProfile())
          .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED);
      assertThat(video.getCodecTier()).isEqualTo(VideoCodecTier.VIDEO_CODEC_TIER_UNSPECIFIED);
      assertThat(video.hasCodecLevel()).isFalse();
    }

    /** An Annex B SPS whose general profile, tier and level hold the given values. */
    private static ProbeStreamInfo hevc(int profileIdc, int tier, int levelIdc) {
      return stream(
          "hevc",
          "00 00 00 01 42 01 01 %02x 60 01 02 03 90 01 02 03 04 05 %02x 80"
              .formatted((tier << 5) | profileIdc, levelIdc));
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
        int profile, VideoCodecProfile expected) {
      assertThat(av1(profile, 8, 0).getCodecProfile()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "seq_level_idx {0}")
    @CsvSource({"0, 20", "1, 21", "8, 40", "13, 51", "23, 73"})
    @DisplayName("Should report the level as major and minor when the record names a level")
    void shouldReportTheLevelAsMajorAndMinorWhenTheRecordNamesALevel(int levelIndex, int level) {
      assertThat(av1(0, levelIndex, 0).getCodecLevel()).isEqualTo(level);
    }

    @Test
    @DisplayName("Should report the High tier when the record sets seq_tier")
    void shouldReportTheHighTierWhenTheRecordSetsSeqTier() {
      assertThat(av1(0, 13, 1).getCodecTier()).isEqualTo(VideoCodecTier.VIDEO_CODEC_TIER_HIGH);
    }

    @Test
    @DisplayName("Should leave the level absent when the record names level index 31")
    void shouldLeaveTheLevelAbsentWhenTheRecordNamesLevelIndex31() {
      var video = av1(0, 31, 0);

      assertThat(video.getCodecProfile()).isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN);
      assertThat(video.hasCodecLevel()).isFalse();
    }

    @Test
    @DisplayName("Should leave the profile unspecified when the record names a reserved profile")
    void shouldLeaveTheProfileUnspecifiedWhenTheRecordNamesAReservedProfile() {
      var video = av1(3, 8, 0);

      assertThat(video.getCodecProfile())
          .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED);
      assertThat(video.getCodecTier()).isEqualTo(VideoCodecTier.VIDEO_CODEC_TIER_UNSPECIFIED);
      assertThat(video.hasCodecLevel()).isFalse();
    }

    /** An av1C record: the marker and version, then the profile and level, then the tier. */
    private static ProbeStreamInfo av1(int profile, int levelIndex, int tier) {
      return stream(
          "av1", "81 %02x %02x 00".formatted((profile << 5) | levelIndex, (tier << 7) | 0x40));
    }
  }

  @Test
  @DisplayName("Should leave the profile unspecified when the stream carries no extradata")
  void shouldLeaveTheProfileUnspecifiedWhenTheStreamCarriesNoExtradata() {
    var video =
        probeOutput(
                """
                {"streams":[{"index":0,"codec_type":"video","codec_name":"hevc",
                             "time_base":"1/1000"}]}
                """)
            .getStreams(0);

    assertThat(video.getCodecProfile())
        .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED);
    assertThat(video.getCodecTier()).isEqualTo(VideoCodecTier.VIDEO_CODEC_TIER_UNSPECIFIED);
    assertThat(video.hasCodecLevel()).isFalse();
  }

  @Test
  @DisplayName("Should leave the profile unspecified when the codec is not H.264, HEVC or AV1")
  void shouldLeaveTheProfileUnspecifiedWhenTheCodecIsNotH264HevcOrAv1() {
    var video = stream("mpeg2video", "00 00 01 b3 04 00 30 13");

    assertThat(video.getCodecProfile())
        .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED);
    assertThat(video.hasCodecLevel()).isFalse();
  }

  @Test
  @DisplayName("Should leave the profile unspecified when the SPS ends inside its profile fields")
  void shouldLeaveTheProfileUnspecifiedWhenTheSpsEndsInsideItsProfileFields() {
    var video = stream("hevc", "00 00 00 01 42 01 01 22 60");

    assertThat(video.getCodecProfile())
        .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_UNSPECIFIED);
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
}
