package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Dolby Vision Record Tests")
class DolbyVisionRecordTest {

  // Dolby's ISO BMFF specification 3.2: dvcC up to profile 7, dvvC for profiles 8 to 10, and dvwC
  // above; each body is version 1.0, the profile, level and flags, the compatibility and
  // compression, then 19 reserved zero bytes.
  @ParameterizedTest(name = "profile {0} at level {1}")
  @CsvSource({
    "5, 6, 0, dvcC, 01000A3500",
    "7, 13, 6, dvcC, 01000E6D60",
    "8, 6, 1, dvvC, 0100103510",
    "10, 9, 4, dvvC, 0100144D40",
    "11, 1, 1, dvwC, 0100160D10"
  })
  @DisplayName(
      "Should write the box type and fields of Dolby's specification when a copy normalizes the"
          + " record of a profile")
  void shouldWriteTheBoxTypeAndFieldsOfDolbysSpecificationWhenACopyNormalizesTheRecord(
      int profile, int level, int compatibility, String boxType, String firstFields) {
    var output =
        PlannedCopyOutput.DolbyVisionOutput.builder()
            .profile(profile)
            .level(level)
            .baseLayerCompatibilityId(compatibility)
            .sampleEntry(PlannedCopyOutput.SampleEntry.HVC1)
            .build();

    var box = DolbyVisionRecord.normalizedFor(output).box();

    assertThat(new String(box, 4, 4, StandardCharsets.US_ASCII)).isEqualTo(boxType);
    assertThat(HexFormat.of().withUpperCase().formatHex(Arrays.copyOfRange(box, 8, box.length)))
        .isEqualTo(firstFields + "00".repeat(19));
  }
}
