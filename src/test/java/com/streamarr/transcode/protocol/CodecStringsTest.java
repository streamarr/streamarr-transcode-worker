package com.streamarr.transcode.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Codec Strings Tests")
class CodecStringsTest {

  @Test
  @DisplayName("Should keep the constraint byte when an AVC profile sets constraint flags")
  void shouldKeepTheConstraintByteWhenAnAvcProfileSetsConstraintFlags() {
    // Constrained Baseline: profile_idc 66, constraint_set0 and constraint_set1, level 3.0.
    assertThat(CodecStrings.avc("avc1", bytes("42 c0 1e"))).isEqualTo("avc1.42C01E");
  }

  @ParameterizedTest(name = "{1}")
  @CsvSource({
    "01 60 00 00 00 90 00 00 00 00 00 5d, hvc1.1.6.L93.90",
    "22 20 00 00 00 b0 00 00 00 00 00 99, hvc1.2.4.H153.B0",
    "44 08 00 00 00 98 00 00 00 00 00 5d, hvc1.A4.10.L93.98",
    "84 08 00 00 00 98 00 00 00 00 00 5d, hvc1.B4.10.L93.98",
    "c4 08 00 00 00 98 00 00 00 00 00 5d, hvc1.C4.10.L93.98",
    "01 60 00 00 00 90 00 00 00 00 01 5d, hvc1.1.6.L93.90.0.0.0.0.1",
    "01 60 00 00 00 00 00 00 00 00 00 5d, hvc1.1.6.L93",
  })
  @DisplayName("Should spell each HEVC field as ISO/IEC 14496-15 E.3 orders it")
  void shouldSpellEachHevcFieldAsIso1449615E3OrdersIt(String generalProfile, String expected) {
    assertThat(CodecStrings.hevc("hvc1", bytes(generalProfile))).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{2}")
  @CsvSource({
    "0x08, 0x00, av01.0.08M.08",
    "0x0d, 0xc0, av01.0.13H.10",
    "0x4d, 0x60, av01.2.13M.12",
    "0x2d, 0x60, av01.1.13M.10",
  })
  @DisplayName("Should state the bit depth that the AV1 profile allows when the record flags it")
  void shouldStateTheBitDepthThatTheAv1ProfileAllowsWhenTheRecordFlagsIt(
      String profileAndLevel, String flags, String expected) {
    assertThat(CodecStrings.av1(Integer.decode(profileAndLevel), Integer.decode(flags)))
        .isEqualTo(expected);
  }

  private static byte[] bytes(String hex) {
    return HexFormat.ofDelimiter(" ").parseHex(hex);
  }

  @Test
  @DisplayName("Should refuse a missing argument when a caller builds an AVC or HEVC string")
  void shouldRefuseAMissingArgumentWhenACallerBuildsAnAvcOrHevcString() {
    var profile = bytes("42 c0 1e");
    var generalProfile = bytes("01 60 00 00 00 90 00 00 00 00 00 5d");

    assertThatNullPointerException().isThrownBy(() -> CodecStrings.avc(null, profile));
    assertThatNullPointerException().isThrownBy(() -> CodecStrings.avc("avc1", null));
    assertThatNullPointerException().isThrownBy(() -> CodecStrings.hevc(null, generalProfile));
    assertThatNullPointerException().isThrownBy(() -> CodecStrings.hevc("hvc1", null));
  }
}
