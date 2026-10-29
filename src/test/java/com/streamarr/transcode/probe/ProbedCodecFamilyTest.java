package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("UnitTest")
@DisplayName("Probed Codec Family Tests")
class ProbedCodecFamilyTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @ParameterizedTest(name = "{0}")
  @CsvSource({"h264, H264", "hevc, HEVC", "av1, AV1"})
  @DisplayName("Should read a walked codec family when ffprobe names its codec")
  void shouldReadAWalkedCodecFamilyWhenFfprobeNamesItsCodec(
      String codecName, ProbedCodecFamily codecFamily) {
    var stream = JSON.createObjectNode().put("codec_name", codecName);

    assertThat(ProbedCodecFamily.of(stream)).isEqualTo(codecFamily);
  }

  @Test
  @DisplayName("Should read another codec family when the probe does not walk the named codec")
  void shouldReadAnotherCodecFamilyWhenTheProbeDoesNotWalkTheNamedCodec() {
    var stream = JSON.createObjectNode().put("codec_name", "vp9");

    assertThat(ProbedCodecFamily.of(stream)).isEqualTo(ProbedCodecFamily.OTHER);
  }

  @Test
  @DisplayName("Should read another codec family when ffprobe names no codec")
  void shouldReadAnotherCodecFamilyWhenFfprobeNamesNoCodec() {
    assertThat(ProbedCodecFamily.of(JSON.createObjectNode())).isEqualTo(ProbedCodecFamily.OTHER);
  }
}
