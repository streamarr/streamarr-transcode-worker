package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

@Tag("UnitTest")
@DisplayName("Stream Codec Tests")
class StreamCodecTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @ParameterizedTest(name = "{0}")
  @CsvSource({"h264, H264", "hevc, HEVC", "av1, AV1"})
  @DisplayName("Should read a walked codec when ffprobe names it")
  void shouldReadAWalkedCodecWhenFfprobeNamesIt(String codecName, StreamCodec.Walked codec) {
    var stream = JSON.createObjectNode().put("codec_name", codecName);

    assertThat(StreamCodec.of(stream)).isEqualTo(codec);
  }

  @Test
  @DisplayName("Should keep the name of a codec when the probe does not walk it")
  void shouldKeepTheNameOfACodecWhenTheProbeDoesNotWalkIt() {
    var stream = JSON.createObjectNode().put("codec_name", "vp9");

    assertThat(StreamCodec.of(stream)).isEqualTo(new StreamCodec.Other("vp9"));
  }

  @Test
  @DisplayName("Should read an unnamed codec when ffprobe names no codec")
  void shouldReadAnUnnamedCodecWhenFfprobeNamesNoCodec() {
    assertThat(StreamCodec.of(JSON.createObjectNode())).isEqualTo(new StreamCodec.Other(""));
  }
}
