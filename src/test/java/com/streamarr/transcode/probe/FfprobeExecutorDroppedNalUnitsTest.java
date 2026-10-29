package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.lengthPrefixed;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionPresent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuPlacement;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.VideoDynamicRange.DolbyVisionCase;
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

    private static final String HEVC_RECORD = HevcSamples.RECORD;
    // The same record, whose NAL units take 1-byte lengths.
    private static final String ONE_BYTE_LENGTHS_RECORD =
        HevcSamples.RECORD_HEADER.replaceFirst("0f$", "0c") + " 00";
    private static final String IDR_SLICE = HevcSamples.IDR_SLICE;
    private static final String SLICE = HevcSamples.SLICE;
    private static final String AUD = HevcSamples.AUD;
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

    @Test
    @DisplayName(
        "Should rule Dolby Vision out when every NAL unit that looks like an RPU sets its"
            + " forbidden_zero_bit")
    void shouldRuleDolbyVisionOutWhenEveryNalUnitThatLooksLikeAnRpuSetsItsForbiddenZeroBit() {
      // Only the splitter rules this unit out: its type, layer and TemporalId name an RPU.
      var dropped = RPU.replaceFirst("^7c 01", "fc 01");

      var video =
          probed(
              HEVC_RECORD,
              lengthPrefixed(IDR_SLICE, dropped),
              lengthPrefixed(SLICE, dropped),
              lengthPrefixed(SLICE, dropped));

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "one byte, 05",
      "a length and a NAL unit of 2 bytes, 02 46 01",
      "a length of 0, 00 46",
      "a length that runs past the end, ff 46 01"
    })
    @DisplayName(
        "Should place each RPU after its own picture when 1 to 3 bytes end a sample, which FFmpeg's"
            + " splitter drops whatever they hold")
    void shouldPlaceEachRpuAfterItsOwnPictureWhen1To3BytesEndASample(
        String description, String tail) {
      var sample = oneByteLengths(IDR_SLICE, RPU) + " " + tail;

      var present = placed(ONE_BYTE_LENGTHS_RECORD, sample, sample, sample);

      assertThat(present.getRpuPlacement())
          .as(description)
          .isEqualTo(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
    }

    @Test
    @DisplayName(
        "Should place each RPU after its own picture when a sample ends with bytes too few for a"
            + " 4-byte length")
    void shouldPlaceEachRpuAfterItsOwnPictureWhenASampleEndsWithBytesTooFewForA4ByteLength() {
      var sample = lengthPrefixed(IDR_SLICE, RPU) + " 00 00 00";

      var present = placed(HEVC_RECORD, sample, sample, sample);

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

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "a length of 0, 00 00 00 00 00 00 00 03 46 01 50",
      "a length that runs past the end, 00 00 00 04 46 01 50",
      "a 4-byte length with no byte after it, 00 00 00 01"
    })
    @DisplayName(
        "Should take no RPU from a sample that FFmpeg rejects for a length that it reads after the"
            + " RPU")
    void shouldTakeNoRpuFromASampleThatFfmpegRejectsForALengthThatItReadsAfterTheRpu(
        String description, String rejecting) {
      var video =
          probed(
              HEVC_RECORD,
              lengthPrefixed(IDR_SLICE),
              lengthPrefixed(SLICE, RPU) + " " + rejecting,
              lengthPrefixed(SLICE));

      assertThat(video.getDynamicRange().getDolbyVisionCase())
          .as(description)
          .isEqualTo(DolbyVisionCase.DOLBYVISION_NOT_SET);
    }

    @Test
    @DisplayName(
        "Should take no content light level from a sample that FFmpeg rejects for a length of 0"
            + " after its SEI")
    void shouldTakeNoContentLightLevelFromASampleThatFfmpegRejectsForALengthOf0AfterItsSei() {
      // A prefix SEI NAL unit with a content light level of 1000 and 400.
      var sei = "4e 01 90 04 03 e8 01 90 80";

      var video =
          probed(
              HEVC_RECORD,
              lengthPrefixed(sei, IDR_SLICE) + " 00 00 00 00 " + lengthPrefixed(AUD),
              lengthPrefixed(SLICE),
              lengthPrefixed(SLICE));

      assertThat(video.hasContentLightLevel()).isFalse();
    }

    @Test
    @DisplayName(
        "Should rule Dolby Vision out when the hvcC record holds an empty NAL unit, which FFmpeg's"
            + " splitter drops")
    void shouldRuleDolbyVisionOutWhenTheHvcCRecordHoldsAnEmptyNalUnitWhichFfmpegsSplitterDrops() {
      // One array of type 32 that holds one NAL unit of length 0.
      var hvccRecord =
          HEVC_RECORD.substring(0, HEVC_RECORD.length() - "00".length()) + "01 20 00 01 00 00";

      var video =
          probed(
              hvccRecord, lengthPrefixed(IDR_SLICE), lengthPrefixed(SLICE), lengthPrefixed(SLICE));

      assertThat(video.getDynamicRange().hasDolbyVisionAbsent()).isTrue();
    }

    private static DolbyVisionPresent placed(String hvccRecord, String... samples) {
      return probed(hvccRecord, samples).getDynamicRange().getDolbyVisionPresent();
    }

    private static ProbeStreamInfo probed(String hvccRecord, String... samples) {
      return HevcSamples.probed(hvccRecord, samples);
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

    private static final String IDR_SLICE = AvcSamples.IDR_SLICE;
    private static final String SLICE = AvcSamples.SLICE;
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
      return AvcSamples.probed(AvcSamples.RECORD, samples);
    }
  }
}
