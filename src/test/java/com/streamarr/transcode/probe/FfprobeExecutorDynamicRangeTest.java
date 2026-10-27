package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.probeOutput;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.CompatibleBase;
import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionAbsent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionConfiguration;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionPresent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.VideoDynamicRange;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Dynamic Range Tests")
class FfprobeExecutorDynamicRangeTest {

  @Nested
  @DisplayName("Compatible Base")
  class CompatibleBaseTests {

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "bt709",
          "smpte170m",
          "smpte240m",
          "gamma22",
          "gamma28",
          "bt470m",
          "bt470bg",
          "iec61966-2-1",
          "iec61966-2-4",
          "bt1361e",
          "bt2020-10",
          "bt2020-12"
        })
    @DisplayName("Should report an SDR base when the transfer is a known SDR transfer")
    void shouldReportAnSdrBaseWhenTheTransferIsAKnownSdrTransfer(String transfer) {
      var video = videoStream("\"color_transfer\":\"" + transfer + "\"");

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_SDR);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"linear", "log100", "log316", "smpte428", "reserved", "unknown"})
    @DisplayName("Should report an unspecified base when the transfer is neither SDR nor HDR")
    void shouldReportAnUnspecifiedBaseWhenTheTransferIsNeitherSdrNorHdr(String transfer) {
      var video = videoStream("\"color_transfer\":\"" + transfer + "\"");

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_UNSPECIFIED);
    }

    @Test
    @DisplayName(
        "Should report no compatible base when a Dolby Vision stream's transfer is unknown")
    void shouldReportNoCompatibleBaseWhenADolbyVisionStreamsTransferIsUnknown() {
      var video =
          videoStream(
              """
              "color_transfer":"unknown",
              "side_data_list":[{"side_data_type":"DOVI configuration record","dv_profile":5}]
              """);

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    @Test
    @DisplayName("Should keep the PQ base when the Dolby Vision record claims another base")
    void shouldKeepThePqBaseWhenTheDolbyVisionRecordClaimsAnotherBase() {
      // Profile 7 declares compatibility identifier 6, which no transfer matches.
      var video = streamOf("mpegts-dovi-p7.ts", 0);

      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_PQ);
    }

    @Test
    @DisplayName("Should report a dynamic range only when the stream is video")
    void shouldReportADynamicRangeOnlyWhenTheStreamIsVideo() {
      var audio = streamOf("mov-phone-hlg-dovi-p84.mov", 1);

      assertThat(audio.hasDynamicRange()).isFalse();
    }
  }

  @Nested
  @DisplayName("Dolby Vision")
  class DolbyVisionTests {

    @Test
    @DisplayName(
        "Should report Dolby Vision without a record when only the sample entry and the RPUs show"
            + " it")
    void shouldReportDolbyVisionWithoutARecordWhenOnlyTheSampleEntryAndTheRpusShowIt() {
      var video = streamOf("mp4-dovi-p5-dvh1-no-record.mp4", 0);

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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"dvh1", "dvhe", "dav1", "dva1", "dvav"})
    @DisplayName("Should report Dolby Vision when the sample entry is a Dolby Vision entry")
    void shouldReportDolbyVisionWhenTheSampleEntryIsADolbyVisionEntry(String sampleEntry) {
      var video = videoStream("\"codec_tag_string\":\"" + sampleEntry + "\"");

      assertThat(video.getDynamicRange().hasDolbyVisionPresent()).isTrue();
      assertThat(video.getDynamicRange().getCompatibleBase())
          .isEqualTo(CompatibleBase.COMPATIBLE_BASE_NONE);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4-hevc-hdr10.mp4", "mp4-hevc-sdr10-untagged.mp4"})
    @DisplayName(
        "Should report Dolby Vision and HDR10+ absent when neither the stream nor its first frames"
            + " show them")
    void shouldReportDolbyVisionAndHdr10PlusAbsentWhenNeitherTheStreamNorItsFirstFramesShowThem(
        String fixture) {
      var video = streamOf(fixture, 0);

      assertThat(video.getDynamicRange())
          .isEqualTo(
              VideoDynamicRange.newBuilder()
                  .setCompatibleBase(video.getDynamicRange().getCompatibleBase())
                  .setHdr10Plus(Presence.PRESENCE_ABSENT)
                  .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance())
                  .build());
    }

    @Test
    @DisplayName(
        "Should report Dolby Vision absent and leave HDR10+ unspecified when an H.264 stream's"
            + " packets hold no RPU")
    void
        shouldReportDolbyVisionAbsentAndLeaveHdr10PlusUnspecifiedWhenAnH264StreamsPacketsHoldNoRpu() {
      // FFmpeg's H.264 decoder drops HDR10+ metadata, and the packets show the RPUs.
      var video = streamOf("mp4-sdr8-bt709.mp4", 0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.getDynamicRange())
          .isEqualTo(
              VideoDynamicRange.newBuilder()
                  .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
                  .setHdr10Plus(Presence.PRESENCE_UNSPECIFIED)
                  .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance())
                  .build());
    }

    @Test
    @DisplayName(
        "Should leave a record value absent when ffprobe prints it negative, as text or beyond 32"
            + " bits")
    void shouldLeaveARecordValueAbsentWhenFfprobePrintsItNegativeAsTextOrBeyond32Bits() {
      var video =
          videoStream(
              """
              "side_data_list":[{"side_data_type":"DOVI configuration record",
                "dv_profile":-1,"dv_level":"6","dv_bl_signal_compatibility_id":4294967296}]
              """);

      assertThat(video.getDynamicRange().getDolbyVisionPresent().getDeclared())
          .isEqualTo(DolbyVisionConfiguration.getDefaultInstance());
    }

    @Test
    @DisplayName("Should leave each record value absent when ffprobe prints no such value")
    void shouldLeaveEachRecordValueAbsentWhenFfprobePrintsNoSuchValue() {
      var video =
          videoStream(
              """
              "side_data_list":[{"side_data_type":"DOVI configuration record"}]
              """);

      assertThat(video.getDynamicRange().getDolbyVisionPresent().getDeclared())
          .isEqualTo(DolbyVisionConfiguration.getDefaultInstance());
    }
  }

  @Nested
  @DisplayName("Mastering Display")
  class MasteringDisplayTests {

    @Test
    @DisplayName("Should read the first frame's values when only the bitstream carries them")
    void shouldReadTheFirstFramesValuesWhenOnlyTheBitstreamCarriesThem() {
      var video = streamOf("mp4-hdr10-sei-only.mp4", 0);

      assertThat(video.getMasteringDisplay())
          .isEqualTo(streamOf("mp4-hdr10-static.mp4", 0).getMasteringDisplay());
      assertThat(video.getContentLightLevel())
          .isEqualTo(
              ContentLightLevel.newBuilder()
                  .setMaxContentLightLevel(1000)
                  .setMaxFrameAverageLightLevel(400)
                  .build());
    }

    @Test
    @DisplayName("Should report no static metadata when neither the container nor a frame has it")
    void shouldReportNoStaticMetadataWhenNeitherTheContainerNorAFrameHasIt() {
      var video = streamOf("mpegts-pq10.ts", 0);

      assertThat(video.getFirstFramesRead()).isTrue();
      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @Test
    @DisplayName("Should round a half unit up when a value lies halfway between two units")
    void shouldRoundAHalfUnitUpWhenAValueLiesHalfwayBetweenTwoUnits() {
      var video = videoStream(masteringDisplay("1/100000", "1/20000"));

      assertThat(video.getMasteringDisplay().getRed().getX()).isEqualTo(1);
      assertThat(video.getMasteringDisplay().getMinLuminance()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should report the largest values when every value fills its SEI field")
    void shouldReportTheLargestValuesWhenEveryValueFillsItsSeiField() {
      var video = videoStream(masteringDisplay("1/1", "4294967295/10000"));

      assertThat(video.getMasteringDisplay().getRed().getX()).isEqualTo(50000);
      assertThat(Integer.toUnsignedLong(video.getMasteringDisplay().getMinLuminance()))
          .isEqualTo(4294967295L);
      assertThat(video.getMasteringDisplayOmitted()).isFalse();
    }

    @ParameterizedTest(name = "coordinate {0}, luminance {1}")
    @CsvSource({
      "50001/50000, 0/1",
      "-1/50000, 0/1",
      "0/1, 4294967296/10000",
      "0/1, -1/10000",
      "1/0, 0/1",
      "0/0, 0/1",
      "half, 0/1",
    })
    @DisplayName(
        "Should mark the mastering display omitted when a value does not fit its SEI field")
    void shouldMarkTheMasteringDisplayOmittedWhenAValueDoesNotFitItsSeiField(
        String coordinate, String luminance) {
      var video = videoStream(masteringDisplay(coordinate, luminance));

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
    }

    @Test
    @DisplayName("Should mark the mastering display omitted when ffprobe prints only the primaries")
    void shouldMarkTheMasteringDisplayOmittedWhenFfprobePrintsOnlyThePrimaries() {
      var video =
          videoStream(
              """
              "side_data_list":[{"side_data_type":"Mastering display metadata",
                "red_x":"34000/50000","red_y":"16000/50000",
                "green_x":"13250/50000","green_y":"34500/50000",
                "blue_x":"7500/50000","blue_y":"3000/50000",
                "white_point_x":"15635/50000","white_point_y":"16450/50000"}]
              """);

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
    }

    @Test
    @DisplayName("Should mark the mastering display omitted when ffprobe prints only the luminance")
    void shouldMarkTheMasteringDisplayOmittedWhenFfprobePrintsOnlyTheLuminance() {
      var video =
          videoStream(
              """
              "side_data_list":[{"side_data_type":"Mastering display metadata",
                "min_luminance":"50/10000","max_luminance":"10000000/10000"}]
              """);

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
    }

    /** Every coordinate set to {@code coordinate} and both luminances set to {@code luminance}. */
    private static String masteringDisplay(String coordinate, String luminance) {
      var fields = new StringBuilder("\"side_data_type\":\"Mastering display metadata\"");
      for (var name :
          new String[] {
            "red_x",
            "red_y",
            "green_x",
            "green_y",
            "blue_x",
            "blue_y",
            "white_point_x",
            "white_point_y"
          }) {
        fields.append(",\"").append(name).append("\":\"").append(coordinate).append('"');
      }

      fields.append(",\"min_luminance\":\"").append(luminance).append('"');
      fields.append(",\"max_luminance\":\"").append(luminance).append('"');
      return "\"side_data_list\":[{" + fields + "}]";
    }
  }

  @Nested
  @DisplayName("Mastering Display Fields")
  class MasteringDisplayFieldTests {

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {"red_x", "green_y", "blue_x", "white_point_y", "max_luminance", "min_luminance"})
    @DisplayName(
        "Should mark the mastering display omitted when ffprobe prints every field but one")
    void shouldMarkTheMasteringDisplayOmittedWhenFfprobePrintsEveryFieldButOne(String missing) {
      var fields = new StringBuilder("\"side_data_type\":\"Mastering display metadata\"");
      for (var name :
          List.of(
              "red_x",
              "red_y",
              "green_x",
              "green_y",
              "blue_x",
              "blue_y",
              "white_point_x",
              "white_point_y",
              "max_luminance",
              "min_luminance")) {
        if (!name.equals(missing)) {
          fields.append(",\"").append(name).append("\":\"1/2\"");
        }
      }

      var video = videoStream("\"side_data_list\":[{" + fields + "}]");

      assertThat(video.hasMasteringDisplay()).isFalse();
      assertThat(video.getMasteringDisplayOmitted()).isTrue();
    }
  }

  @Nested
  @DisplayName("Content Light Level")
  class ContentLightLevelTests {

    @Test
    @DisplayName("Should read the first frame's light level when Matroska stores a MaxFALL of 0")
    void shouldReadTheFirstFramesLightLevelWhenMatroskaStoresAMaxFallOf0() {
      // ffprobe reports a Matroska light level only when MaxCLL and MaxFALL are both nonzero, and
      // the encode's SEI messages repeat the values.
      var video = streamOf("mkv-hdr10-maxfall-zero.mkv", 0);

      assertThat(video.getContentLightLevel())
          .isEqualTo(
              ContentLightLevel.newBuilder()
                  .setMaxContentLightLevel(1000)
                  .setMaxFrameAverageLightLevel(0)
                  .build());
      assertThat(video.hasMasteringDisplay()).isTrue();
    }

    @Test
    @DisplayName("Should report the largest values when both values fill their SEI fields")
    void shouldReportTheLargestValuesWhenBothValuesFillTheirSeiFields() {
      var video = videoStream(contentLightLevel(65535, 65535));

      assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(65535);
      assertThat(video.getContentLightLevel().getMaxFrameAverageLightLevel()).isEqualTo(65535);
      assertThat(video.getContentLightLevelOmitted()).isFalse();
    }

    @ParameterizedTest(name = "MaxCLL {0}, MaxFALL {1}")
    @CsvSource({"65536, 0", "0, 65536", "-1, 0", "0, -1"})
    @DisplayName("Should mark the light level omitted when a value does not fit its SEI field")
    void shouldMarkTheLightLevelOmittedWhenAValueDoesNotFitItsSeiField(long maxCll, long maxFall) {
      var video = videoStream(contentLightLevel(maxCll, maxFall));

      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @Test
    @DisplayName("Should mark the light level omitted when ffprobe prints only MaxCLL")
    void shouldMarkTheLightLevelOmittedWhenFfprobePrintsOnlyMaxCll() {
      var video =
          videoStream(
              """
              "side_data_list":[{"side_data_type":"Content light level metadata","max_content":1000}]
              """);

      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"\"1000\"", "99999999999999999999"})
    @DisplayName(
        "Should mark the light level omitted when ffprobe prints MaxCLL as text or beyond 64 bits")
    void shouldMarkTheLightLevelOmittedWhenFfprobePrintsMaxCllAsTextOrBeyond64Bits(String maxCll) {
      var video =
          videoStream(
              """
              "side_data_list":[{"side_data_type":"Content light level metadata",
                "max_content":%s,"max_average":400}]
              """
                  .formatted(maxCll));

      assertThat(video.hasContentLightLevel()).isFalse();
      assertThat(video.getContentLightLevelOmitted()).isTrue();
    }

    private static String contentLightLevel(long maxCll, long maxFall) {
      return """
          "side_data_list":[{"side_data_type":"Content light level metadata",
            "max_content":%d,"max_average":%d}]
          """
          .formatted(maxCll, maxFall);
    }
  }

  /** The probe result of one video stream with the given ffprobe fields. */
  private static ProbeStreamInfo videoStream(String fields) {
    return probeOutput(
            """
            {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000",%s}]}
            """
                .formatted(fields))
        .getStreams(0);
  }
}
