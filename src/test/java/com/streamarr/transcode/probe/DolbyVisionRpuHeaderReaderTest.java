package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuHeader;
import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Dolby Vision RPU Header Reader Tests")
class DolbyVisionRpuHeaderReaderTest {

  // The first RPU of each local sample after its NAL unit header. ffprobe printed each header's
  // fields as the side data of the sample's first frame.
  private static final String PROFILE_81_RPU = "19 08 09 08 40 61 36 50 6f 00 3f f8";
  private static final String PROFILE_5_RPU = "19 08 09 00 40 61 b6 50 6f 00 3f f8";
  private static final String PROFILE_7_RPU = "19 08 09 08 40 61 36 50 ae 20 00 20";

  @Test
  @DisplayName("Should read the fields that ffprobe prints when the RPU is a profile 8.1 RPU")
  void shouldReadTheFieldsThatFfprobePrintsWhenTheRpuIsAProfile81Rpu() {
    assertThat(read(PROFILE_81_RPU))
        .hasValue(
            DolbyVisionRpuHeader.newBuilder()
                .setVdrRpuProfile(1)
                .setDisableResidual(true)
                .setVdrBitDepth(12)
                .build());
  }

  @Test
  @DisplayName("Should read the fields that ffprobe prints when the RPU is a profile 5 RPU")
  void shouldReadTheFieldsThatFfprobePrintsWhenTheRpuIsAProfile5Rpu() {
    assertThat(read(PROFILE_5_RPU))
        .hasValue(
            DolbyVisionRpuHeader.newBuilder()
                .setVdrRpuProfile(0)
                .setBaseLayerFullRange(true)
                .setDisableResidual(true)
                .setVdrBitDepth(12)
                .build());
  }

  @Test
  @DisplayName("Should read the fields that ffprobe prints when the RPU is a profile 7 RPU")
  void shouldReadTheFieldsThatFfprobePrintsWhenTheRpuIsAProfile7Rpu() {
    assertThat(read(PROFILE_7_RPU))
        .hasValue(
            DolbyVisionRpuHeader.newBuilder()
                .setVdrRpuProfile(1)
                .setEnhancementLayerSpatialResamplingFilter(true)
                .setVdrBitDepth(12)
                .build());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        // Another prefix than rpu_nal_prefix 25.
        "18 08 09 08 40 61 36 50 6f",
        // rpu_type 3.
        "19 0c 09 08 40 61 36 50 6f",
        // No vdr_seq_info: the header refers to an earlier RPU's.
        "19 08 09 08 00 00 00 00 00",
        // An rpu_format whose 0x700 bits leave out the bit depths.
        "19 08 89 08 40 61 36 50 6f",
        // vdr_bit_depth_minus8 9.
        "19 08 09 08 40 61 36 28 00 6f",
        // A vdr_bit_depth_minus8 whose code runs past 32 bits.
        "19 08 09 08 40 61 36 00 00 00 00 00 7f",
        // The RPU ends inside its header.
        "19 08 09 08 40",
        ""
      })
  @DisplayName("Should read no header when the RPU names no profile-guessing fields")
  void shouldReadNoHeaderWhenTheRpuNamesNoProfileGuessingFields(String rpu) {
    assertThat(read(rpu)).isEmpty();
  }

  private static Optional<DolbyVisionRpuHeader> read(String rpu) {
    return DolbyVisionRpuHeaderReader.read(HexFormat.ofDelimiter(" ").parseHex(rpu));
  }
}
