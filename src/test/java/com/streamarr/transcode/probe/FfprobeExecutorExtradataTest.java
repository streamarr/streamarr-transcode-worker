package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.annexB;
import static com.streamarr.transcode.probe.FirstReads.extradata;
import static com.streamarr.transcode.probe.FirstReads.frames;
import static com.streamarr.transcode.probe.FirstReads.lengthPrefixed;
import static com.streamarr.transcode.probe.FirstReads.output;
import static com.streamarr.transcode.probe.FirstReads.packetsUpTo;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.VideoDynamicRange.DolbyVisionCase;
import com.streamarr.transcode.probe.RecordedFfprobe.Answers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * How a stream's extradata decides whether the probe can delimit the units of its extradata and of
 * its first packets. A stream whose extradata the walk cannot delimit, and an H.264 or HEVC stream
 * without extradata, rule nothing out. Every test answers each packet that the read asks for with
 * NAL units that the probe delimits, so that only the extradata can leave a unit undelimited.
 */
@Tag("UnitTest")
@DisplayName("Ffprobe Executor Extradata Tests")
class FfprobeExecutorExtradataTest {

  @Nested
  @DisplayName("HEVC")
  class HevcTests {

    private static final String RPU = "7c 01 19 08 09 00 40 61 b6 50 6f 00 3f";

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "a record that ends before its count of arrays, ''",
      "an array whose header ends early, 01 20 00",
      "a NAL unit whose length ends early, 01 20 00 01 00"
    })
    @DisplayName("Should rule nothing out when the hvcC record ends before what it counts")
    void shouldRuleNothingOutWhenTheHvcCRecordEndsBeforeWhatItCounts(
        String description, String afterHeader) {
      var record = (HevcSamples.RECORD_HEADER + " " + afterHeader).trim();

      var video =
          HevcSamples.probed(
              record,
              lengthPrefixed(HevcSamples.IDR_SLICE),
              lengthPrefixed(HevcSamples.SLICE),
              lengthPrefixed(HevcSamples.SLICE));

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .as(description)
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    @Test
    @DisplayName(
        "Should rule nothing out when Annex B extradata holds a start code and no NAL unit")
    void shouldRuleNothingOutWhenAnnexBExtradataHoldsAStartCodeAndNoNalUnit() {
      var video = annexBProbed("00 00 01");

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    @Test
    @DisplayName(
        "Should take no content light level from an Annex B extradata SEI NAL unit that FFmpeg's"
            + " splitter drops")
    void shouldTakeNoContentLightLevelFromAnAnnexBExtradataSeiNalUnitThatFfmpegsSplitterDrops() {
      // A prefix SEI NAL unit with a content light level of 1000 and 400, whose forbidden_zero_bit
      // is 1.
      var video = annexBProbed(annexB("ce 01 90 04 03 e8 01 90 80"));

      assertThat(video.hasContentLightLevel()).isFalse();
    }

    @Test
    @DisplayName("Should take no RPU from the packets when the stream carries no extradata")
    void shouldTakeNoRpuFromThePacketsWhenTheStreamCarriesNoExtradata() {
      var sample = annexB(HevcSamples.IDR_SLICE, RPU);

      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output("\"codec_name\":\"hevc\""))
                      .firstFrames(frames("", "", ""))
                      .firstPackets(packetsUpTo(3, sample))
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    private static ProbeStreamInfo annexBProbed(String extradata) {
      var sample = annexB(HevcSamples.SLICE);
      return probe(
              Answers.builder()
                  .ffprobeOutput(output("\"codec_name\":\"hevc\"," + extradata(extradata)))
                  .firstFrames(frames("", "", ""))
                  .firstPackets(packetsUpTo(3, sample))
                  .build())
          .getStreams(0);
    }
  }

  @Nested
  @DisplayName("H.264")
  class H264Tests {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "an SPS whose length runs past the record's end, 01 64 00 28 ff e1 00 05",
      "an SPS count whose length the record's end cuts off, 01 64 00 28 ff e1 00"
    })
    @DisplayName("Should rule nothing out when FFmpeg cannot read the avcC record whole")
    void shouldRuleNothingOutWhenFfmpegCannotReadTheAvcCRecordWhole(
        String description, String record) {
      var video =
          AvcSamples.probed(
              record,
              lengthPrefixed(AvcSamples.IDR_SLICE),
              lengthPrefixed(AvcSamples.SLICE),
              lengthPrefixed(AvcSamples.SLICE));

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .as(description)
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }
  }

  @Nested
  @DisplayName("AV1")
  class Av1Tests {

    @Test
    @DisplayName("Should rule Dolby Vision out when a stream without extradata shows no RPU")
    void shouldRuleDolbyVisionOutWhenAStreamWithoutExtradataShowsNoRpu() {
      // A temporal delimiter, then a frame OBU whose payload the walk does not read.
      var temporalUnit = "12 00 32 03 10 00 80";

      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(output("\"codec_name\":\"av1\""))
                      .firstFrames(frames("", "", ""))
                      .firstPackets(packetsUpTo(3, temporalUnit))
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
    }
  }
}
