package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.annexB;
import static com.streamarr.transcode.probe.FirstReads.extradata;
import static com.streamarr.transcode.probe.FirstReads.frames;
import static com.streamarr.transcode.probe.FirstReads.lengthPrefixed;
import static com.streamarr.transcode.probe.FirstReads.output;
import static com.streamarr.transcode.probe.FirstReads.packetsUpTo;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuPlacement;
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

/**
 * Whether the probe delimits every NAL unit of a stream's first packets, so that the packets can
 * rule an RPU out and place the RPUs. Every test answers each packet that the read asks for, so
 * that only the packet under test can leave a NAL unit undelimited.
 */
@Tag("UnitTest")
@DisplayName("Ffprobe Executor Packet Delimiting Tests")
class FfprobeExecutorPacketDelimitingTest {

  private static final String HEVC_RPU = "7c 01 19 08 09 00 40 61 b6 50 6f 00 3f";

  @Nested
  @DisplayName("Length-Prefixed Packets")
  class LengthPrefixedPacketTests {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "1 to 3 bytes, 00 00 01",
      "only a NAL unit that FFmpeg's splitter drops, 00 00 00 03 c6 01 50"
    })
    @DisplayName(
        "Should rule nothing out when a packet holds no NAL unit that reaches FFmpeg's decoder")
    void shouldRuleNothingOutWhenAPacketHoldsNoNalUnitThatReachesFfmpegsDecoder(
        String description, String packet) {
      var video =
          HevcSamples.probed(
              HevcSamples.RECORD,
              lengthPrefixed(HevcSamples.IDR_SLICE),
              packet,
              lengthPrefixed(HevcSamples.SLICE));

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .as(description)
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    @Test
    @DisplayName(
        "Should leave the RPU placement unspecified when FFmpeg rejects a packet for a length of 0")
    void shouldLeaveTheRpuPlacementUnspecifiedWhenFfmpegRejectsAPacketForALengthOf0() {
      var video =
          HevcSamples.probed(
              HevcSamples.RECORD,
              lengthPrefixed(HevcSamples.IDR_SLICE, HEVC_RPU),
              lengthPrefixed(HevcSamples.SLICE, HEVC_RPU),
              lengthPrefixed(HevcSamples.SLICE)
                  + " 00 00 00 00 "
                  + lengthPrefixed(HevcSamples.AUD));

      var present = video.getDynamicRange().getDolbyVisionPresent();
      assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_UNSPECIFIED);
    }
  }

  @Nested
  @DisplayName("Annex B Packets")
  class AnnexBPacketTests {

    // Annex B extradata: a start code and an access unit delimiter.
    private static final String ANNEX_B_EXTRADATA = annexB(HevcSamples.AUD);

    @Test
    @DisplayName(
        "Should rule Dolby Vision out when zero bytes lead the first start code of each packet")
    void shouldRuleDolbyVisionOutWhenZeroBytesLeadTheFirstStartCodeOfEachPacket() {
      var packet = "00 00 " + annexB(HevcSamples.SLICE);

      var video = annexBProbed(packet, packet, packet);

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"one zero byte and 01, 00 01 46 01 50", "two zero bytes and 02, 00 00 02 46 01 50"})
    @DisplayName("Should rule nothing out when bytes that no start code begins lead a packet")
    void shouldRuleNothingOutWhenBytesThatNoStartCodeBeginsLeadAPacket(
        String description, String leading) {
      var video =
          annexBProbed(
              annexB(HevcSamples.IDR_SLICE),
              leading + " " + annexB(HevcSamples.SLICE),
              annexB(HevcSamples.SLICE));

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .as(description)
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    @Test
    @DisplayName(
        "Should place each RPU after its own picture when an all-zero NAL unit that FFmpeg keeps"
            + " ahead of 0x000001E0 follows the RPU")
    void
        shouldPlaceEachRpuAfterItsOwnPictureWhenAnAllZeroNalUnitThatFfmpegKeepsAheadOf0x000001E0FollowsTheRpu() {
      // FFmpeg keeps the zero bytes that the 4 bytes 0x000001E0 follow as an H.264 NAL unit of type
      // 0, which its decoder ignores. 0xE0 itself sets forbidden_zero_bit, so the splitter drops
      // that NAL unit.
      var rpu = "1c 19 08 09 08 40 61 59 41 bc 03 fe";
      var sample = annexB(AvcSamples.IDR_SLICE, rpu) + " 00 00 01 00 00 00 00 01 e0 01";

      var video =
          probe(
                  Answers.builder()
                      .ffprobeOutput(
                          output(
                              "\"codec_name\":\"h264\","
                                  + extradata(annexB("67 64 00 28", "68 ee 3c 80"))))
                      .firstFrames(frames("", "", ""))
                      .firstPackets(packetsUpTo(3, sample))
                      .build())
              .getStreams(0);

      assertThat(video.getDynamicRange().getDolbyVisionPresent().getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
    }

    private static ProbeStreamInfo annexBProbed(String... packets) {
      return probe(
              Answers.builder()
                  .ffprobeOutput(output("\"codec_name\":\"hevc\"," + extradata(ANNEX_B_EXTRADATA)))
                  .firstFrames(frames("", "", ""))
                  .firstPackets(packetsUpTo(3, annexB(HevcSamples.SLICE), packets))
                  .build())
          .getStreams(0);
    }
  }
}
