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
  void shouldReadAWalkedCodecWhenFfprobeNamesIt(String codecName, StreamCodec codec) {
    var stream = JSON.createObjectNode().put("codec_name", codecName);

    assertThat(StreamCodec.of(stream)).isEqualTo(codec);
  }

  @Test
  @DisplayName("Should read another codec when the probe does not walk the named codec")
  void shouldReadAnotherCodecWhenTheProbeDoesNotWalkTheNamedCodec() {
    var stream = JSON.createObjectNode().put("codec_name", "vp9");

    assertThat(StreamCodec.of(stream)).isEqualTo(StreamCodec.OTHER);
  }

  @Test
  @DisplayName("Should read another codec when ffprobe names no codec")
  void shouldReadAnotherCodecWhenFfprobeNamesNoCodec() {
    assertThat(StreamCodec.of(JSON.createObjectNode())).isEqualTo(StreamCodec.OTHER);
  }
}
