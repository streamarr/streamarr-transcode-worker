package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FirstReads.lengthPrefixed;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionPresent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import build.buf.gen.streamarr.transcode.v1.Presence;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The first RPU header that the probe takes from the RPUs of an HEVC stream's first packets, when
 * its first frames show no RPU. The probe reads the profile-guessing fields as FFmpeg 8.1's RPU
 * parser does, and reports the RPU without a header when that parser rejects the header.
 */
@Tag("UnitTest")
@DisplayName("Ffprobe Executor Packet RPU Header Tests")
class FfprobeExecutorPacketRpuHeaderTest {

  @ParameterizedTest(name = "{0}")
  @MethodSource("readableHeaders")
  @DisplayName("Should report the header's fields when the first packet RPU carries a header")
  void shouldReportTheHeadersFieldsWhenTheFirstPacketRpuCarriesAHeader(ReadableHeader header) {
    var present = firstRpuOf(header.rpu().hevcNalUnit());

    assertThat(present.getFirstRpuHeader()).isEqualTo(header.fields());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("rejectedHeaders")
  @DisplayName(
      "Should report the RPU present without a header when FFmpeg's RPU parser rejects the header")
  void shouldReportTheRpuPresentWithoutAHeaderWhenFfmpegsRpuParserRejectsTheHeader(String rpu) {
    var present = firstRpuOf(rpu);

    assertThat(present.getObservedRpu()).isEqualTo(Presence.PRESENCE_PRESENT);
    assertThat(present.hasFirstRpuHeader()).isFalse();
  }

  static Stream<Named<ReadableHeader>> readableHeaders() {
    return Stream.of(
        Named.of(
            "profile 8.1's fields",
            ReadableHeader.builder()
                .rpu(RpuDataHeaderBits.profile81().build())
                .fields(
                    DolbyVisionRpuHeader.newBuilder()
                        .setVdrRpuProfile(1)
                        .setDisableResidual(true)
                        .setVdrBitDepth(12)
                        .build())
                .build()),
        Named.of(
            "profile 5's fields",
            ReadableHeader.builder()
                .rpu(RpuDataHeaderBits.profile81().vdrRpuProfile(0).blVideoFullRange(true).build())
                .fields(
                    DolbyVisionRpuHeader.newBuilder()
                        .setBaseLayerFullRange(true)
                        .setDisableResidual(true)
                        .setVdrBitDepth(12)
                        .build())
                .build()),
        Named.of(
            "profile 7's enhancement layer fields",
            ReadableHeader.builder()
                .rpu(
                    RpuDataHeaderBits.profile81()
                        .elSpatialResamplingFilter(true)
                        .disableResidual(false)
                        .build())
                .fields(
                    DolbyVisionRpuHeader.newBuilder()
                        .setVdrRpuProfile(1)
                        .setEnhancementLayerSpatialResamplingFilter(true)
                        .setVdrBitDepth(12)
                        .build())
                .build()),
        Named.of(
            "floating-point coefficients, which carry no coefficient_log2_denom",
            ReadableHeader.builder()
                .rpu(RpuDataHeaderBits.profile81().coefficientDataType(1).build())
                .fields(
                    DolbyVisionRpuHeader.newBuilder()
                        .setVdrRpuProfile(1)
                        .setDisableResidual(true)
                        .setVdrBitDepth(12)
                        .build())
                .build()));
  }

  static Stream<Named<String>> rejectedHeaders() {
    return Stream.of(
        Named.of("rpu_type 3", RpuDataHeaderBits.profile81().rpuType(3).build().hevcNalUnit()),
        Named.of(
            "no vdr_seq_info, which refers the header to an earlier RPU's",
            RpuDataHeaderBits.profile81().vdrSeqInfoPresent(false).build().hevcNalUnit()),
        Named.of(
            "an rpu_format whose 0x700 bits leave out the bit depths",
            RpuDataHeaderBits.profile81().rpuFormat(0x112).build().hevcNalUnit()),
        Named.of(
            "vdr_bit_depth_minus8 9",
            RpuDataHeaderBits.profile81().vdrBitDepthMinus8(9).build().hevcNalUnit()),
        Named.of(
            "a coefficient_log2_denom whose code runs past 32 bits",
            RpuDataHeaderBits.profile81()
                .coefficientLog2Denom((1L << 33) - 1)
                .build()
                .hevcNalUnit()),
        Named.of(
            "a header that ends inside its bit depths",
            endingAfter(RpuDataHeaderBits.profile81(), 7)));
  }

  /** The HEVC RPU NAL unit of the header's first {@code length} bytes. */
  private static String endingAfter(RpuDataHeaderBits.RpuDataHeaderBitsBuilder header, int length) {
    var payload = Arrays.copyOf(header.build().payload(), length);
    return "7c 01 " + HexFormat.ofDelimiter(" ").formatHex(payload);
  }

  private static DolbyVisionPresent firstRpuOf(String rpu) {
    return HevcSamples.probed(
            HevcSamples.RECORD,
            lengthPrefixed(HevcSamples.IDR_SLICE, rpu),
            lengthPrefixed(HevcSamples.SLICE, rpu),
            lengthPrefixed(HevcSamples.SLICE, rpu))
        .getDynamicRange()
        .getDolbyVisionPresent();
  }

  @Builder
  private record ReadableHeader(RpuDataHeaderBits rpu, DolbyVisionRpuHeader fields) {}
}
