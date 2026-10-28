package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.extradata;
import static com.streamarr.transcode.probe.FirstReads.frames;
import static com.streamarr.transcode.probe.FirstReads.lengthPrefixed;
import static com.streamarr.transcode.probe.FirstReads.output;
import static com.streamarr.transcode.probe.FirstReads.packets;
import static com.streamarr.transcode.probe.FirstReads.packetsUpTo;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionPresent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuPlacement;
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

/**
 * FFmpeg's ff_h2645_packet_split drops some NAL units before any decoder sees them, so the walk
 * skips them: they are no evidence, and they neither count as slices nor keep an RPU from ending
 * its sample.
 */
@Tag("UnitTest")
@DisplayName("Ffprobe Executor Dropped NAL Units Tests")
class FfprobeExecutorDroppedNalUnitsTest {

  @Nested
  @DisplayName("HEVC")
  class HevcTests {

    // An hvcC record without arrays whose NAL units take 4-byte lengths.
    private static final String HEVC_RECORD =
        "01 01 60 00 00 00 90 00 00 00 00 00 5d f0 00 fc fd f8 f8 00 00 0f 00";
    // The same record, whose NAL units take 1-byte lengths.
    private static final String ONE_BYTE_LENGTHS_RECORD =
        "01 01 60 00 00 00 90 00 00 00 00 00 5d f0 00 fc fd f8 f8 00 00 0c 00";
    private static final String IDR_SLICE = "26 01 af 06 b8";
    private static final String SLICE = "02 01 d0 09 7e";
    private static final String AUD = "46 01 50";
    private static final String RPU = "7c 01 19 08 09 00 40 61 b6 50 6f 00 3f";

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "forbidden_zero_bit 1, c6 01 50",
      "nuh_temporal_id_plus1 0, 46 00 50",
      "nuh_layer_id 63, 47 f9 50",
      "one byte, 46",
      "zero bytes after a header, 00 00 00"
    })
    @DisplayName(
        "Should place each RPU after its own picture when a NAL unit that FFmpeg's splitter drops"
            + " follows the RPU")
    void shouldPlaceEachRpuAfterItsOwnPictureWhenANalUnitThatFfmpegsSplitterDropsFollowsTheRpu(
        String description, String dropped) {
      var present =
          placed(
              HEVC_RECORD,
              lengthPrefixed(IDR_SLICE, RPU, dropped),
              lengthPrefixed(SLICE, RPU, dropped),
              lengthPrefixed(SLICE, RPU));

      assertThat(present.getObservedRpu()).as(description).isEqualTo(Presence.PRESENCE_PRESENT);
      assertThat(present.getRpuPlacement())
          .as(description)
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "forbidden_zero_bit 1, 82 01 d0 09 7e",
      "nuh_temporal_id_plus1 0, 02 00 d0 09 7e",
      "nuh_layer_id 63, 03 f9 d0 09 7e"
    })
    @DisplayName(
        "Should report that every RPU starts its sample when a slice that FFmpeg's splitter drops"
            + " comes ahead of the RPU")
    void
        shouldReportThatEveryRpuStartsItsSampleWhenASliceThatFfmpegsSplitterDropsComesAheadOfTheRpu(
            String description, String dropped) {
      var present =
          placed(
              HEVC_RECORD,
              lengthPrefixed(IDR_SLICE),
              lengthPrefixed(dropped, RPU, SLICE),
              lengthPrefixed(dropped, RPU, SLICE));

      assertThat(present.getRpuPlacement())
          .as(description)
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "forbidden_zero_bit 1, fc 01",
      "nuh_temporal_id_plus1 0, 7c 00",
      "nuh_layer_id 63, 7d f9"
    })
    @DisplayName(
        "Should rule Dolby Vision out when FFmpeg's splitter drops every NAL unit that looks like an"
            + " RPU")
    void shouldRuleDolbyVisionOutWhenFfmpegsSplitterDropsEveryNalUnitThatLooksLikeAnRpu(
        String description, String header) {
      var dropped = RPU.replaceFirst("^7c 01", header);

      var video =
          probed(
              HEVC_RECORD,
              lengthPrefixed(IDR_SLICE, dropped),
              lengthPrefixed(SLICE, dropped),
              lengthPrefixed(SLICE, dropped));

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).as(description).isTrue();
    }

    @Test
    @DisplayName(
        "Should place each RPU after its own picture when the last NAL unit of a sample takes fewer"
            + " than 4 bytes with its length")
    void
        shouldPlaceEachRpuAfterItsOwnPictureWhenTheLastNalUnitOfASampleTakesFewerThan4BytesWithItsLength() {
      // FFmpeg's splitter stops when fewer than 4 bytes remain, so it never reads the 2-byte unit.
      var sample = oneByteLengths(IDR_SLICE, RPU, "46 01");

      var present = placed(ONE_BYTE_LENGTHS_RECORD, sample, sample, sample);

      assertThat(present.getRpuPlacement())
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
    }

    @Test
    @DisplayName(
        "Should leave Dolby Vision unspecified when a sample holds a length of 0, which makes FFmpeg"
            + " reject the whole packet")
    void
        shouldLeaveDolbyVisionUnspecifiedWhenASampleHoldsALengthOf0WhichMakesFfmpegRejectTheWholePacket() {
      var video =
          probed(
              HEVC_RECORD,
              lengthPrefixed(IDR_SLICE),
              lengthPrefixed(SLICE) + " 00 00 00 00 " + lengthPrefixed(AUD),
              lengthPrefixed(SLICE));

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    @Test
    @DisplayName(
        "Should rule Dolby Vision out when the hvcC record holds an empty NAL unit, which FFmpeg's"
            + " splitter drops")
    void shouldRuleDolbyVisionOutWhenTheHvcCRecordHoldsAnEmptyNalUnitWhichFfmpegsSplitterDrops() {
      // One array of type 32 that holds one NAL unit of length 0.
      var record =
          HEVC_RECORD.substring(0, HEVC_RECORD.length() - "00".length()) + "01 20 00 01 00 00";

      var video =
          probed(record, lengthPrefixed(IDR_SLICE), lengthPrefixed(SLICE), lengthPrefixed(SLICE));

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
    }

    private static DolbyVisionPresent placed(String record, String... samples) {
      return probed(record, samples).getDynamicRange().getDolbyVisionPresent();
    }

    private static ProbeStreamInfo probed(String record, String... samples) {
      return probe(
              Answers.builder()
                  .ffprobeOutput(output("\"codec_name\":\"hevc\"," + extradata(record)))
                  .firstFrames(frames("", "", ""))
                  .firstPackets(packetsUpTo(3, lengthPrefixed(SLICE), samples))
                  .build())
          .getStreams(0);
    }

    /** One sample whose NAL units each take a 1-byte length. */
    private static String oneByteLengths(String... units) {
      var sample = new StringBuilder();
      for (var unit : units) {
        var length = HexFormat.ofDelimiter(" ").parseHex(unit).length;
        sample.append(" %02x ".formatted(length)).append(unit);
      }

      return sample.toString().trim().replaceAll(" +", " ");
    }
  }

  @Nested
  @DisplayName("H.264")
  class H264Tests {

    // An avcC record whose NAL units take 4-byte lengths, with an SPS and a PPS.
    private static final String AVC_RECORD =
        "01 64 00 28 ff e1 00 04 67 64 00 28 01 00 04 68 ee 3c 80";
    private static final String IDR_SLICE = "65 88 84 00 18";
    private static final String SLICE = "41 9a 24 6c 41";
    private static final String RPU = "1c 19 08 09 08 40 61 59 41 bc 03 fe";

    @ParameterizedTest(name = "{0}")
    @CsvSource({"forbidden_zero_bit 1, 89 f0", "bytes all 0, 00 00", "one byte of 0, 00"})
    @DisplayName(
        "Should place each RPU after its own picture when a NAL unit that FFmpeg's splitter drops"
            + " follows the RPU")
    void shouldPlaceEachRpuAfterItsOwnPictureWhenANalUnitThatFfmpegsSplitterDropsFollowsTheRpu(
        String description, String dropped) {
      var present =
          probed(
                  lengthPrefixed(IDR_SLICE, RPU, dropped),
                  lengthPrefixed(SLICE, RPU, dropped),
                  lengthPrefixed(SLICE, RPU))
              .getDynamicRange()
              .getDolbyVisionPresent();

      assertThat(present.getRpuPlacement())
          .as(description)
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
    }

    @Test
    @DisplayName(
        "Should rule Dolby Vision out when every NAL unit that looks like an RPU sets its"
            + " forbidden_zero_bit")
    void shouldRuleDolbyVisionOutWhenEveryNalUnitThatLooksLikeAnRpuSetsItsForbiddenZeroBit() {
      var dropped = RPU.replaceFirst("^1c", "9c");

      var video =
          probed(
              lengthPrefixed(IDR_SLICE, dropped),
              lengthPrefixed(SLICE, dropped),
              lengthPrefixed(SLICE, dropped));

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
    }

    private static ProbeStreamInfo probed(String... samples) {
      return probe(
              Answers.builder()
                  .ffprobeOutput(output("\"codec_name\":\"h264\"," + extradata(AVC_RECORD)))
                  .firstFrames(frames("", "", ""))
                  .firstPackets(packets(samples))
                  .build())
          .getStreams(0);
    }
  }
}
