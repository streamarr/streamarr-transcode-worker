package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Framecrc Timings Tests")
class FramecrcTimingsTest {

  // What FFmpeg 8.1's framecrc muxer writes for a copied HEVC stream in Matroska.
  private static final String HEADER =
      """
      #extradata 0:     2410, 0x9d2f3b49
      #software: Lavf62.3.100
      #tb 0: 1/1000
      #media_type 0: video
      #codec_id 0: hevc
      #dimensions 0: 640x360
      #sar 0: 1/1
      """;

  @Test
  @DisplayName(
      "Should read each record's keyframe flag and duration in the header's time base when side"
          + " data follows them")
  void shouldReadEachRecordsKeyframeFlagAndDurationInTheHeadersTimeBaseWhenSideDataFollowsThem()
      throws IOException {
    var timings =
        timingsOf(
            HEADER
                + """
                0,        -83,          0,       41,     7369, 0x7e1c3b5a
                0,        -42,        375,       41,     6404, 0x0b4f1c2d, F=0x0
                0,          0,        292,       42,     1978, 0x5c3a2e1f, F=0x0, S=1,        8, 0x01b200c3
                0,         41,       1000,        0,     9184, 0x0c6e170a, F=0x5
                """);

    assertThat(timings)
        .containsExactly(packet(true, 41), packet(false, 41), packet(false, 42), packet(true, 0));
  }

  @Test
  @DisplayName("Should end when the framecrc ends")
  void shouldEndWhenTheFramecrcEnds() throws IOException {
    assertThat(timingsOf(HEADER)).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "#tb 0: 0/1000",
        "#tb 0: 1",
        "[hevc @ 0x5581d0c0] 0,          0,          0,       41,     7369, 0x7e1c3b5a",
        "1,          0,          0,       41,     7369, 0x7e1c3b5a",
        "0,          0,          0,       41",
        "0,        abc,          0,       41,     7369, 0x7e1c3b5a",
        "0,          0,          0, 99999999999999999999,     7369, 0x7e1c3b5a"
      })
  @DisplayName(
      "Should fail as unusable video timing when a line is neither a time base nor a record of the"
          + " one video stream")
  void shouldFailAsUnusableVideoTimingWhenALineIsNeitherATimeBaseNorARecordOfTheOneVideoStream(
      String line) {
    var header = line.startsWith("#") ? "" : HEADER;

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> timingsOf(header + line + "\n"))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.UNUSABLE_VIDEO_TIMING);
  }

  @Test
  @DisplayName("Should fail as unusable video timing when a record precedes the time base")
  void shouldFailAsUnusableVideoTimingWhenARecordPrecedesTheTimeBase() {
    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> timingsOf("0,          0,          0,       41,     7369, 0x7e1c3b5a\n"))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.UNUSABLE_VIDEO_TIMING);
  }

  private static VideoPacketTiming packet(boolean keyframe, long duration) {
    return VideoPacketTiming.builder()
        .keyframe(keyframe)
        .duration(duration)
        .timeBase(new TimeBase(1, 1000))
        .build();
  }

  private static List<VideoPacketTiming> timingsOf(String framecrc) throws IOException {
    var timings =
        new FramecrcTimings(new ByteArrayInputStream(framecrc.getBytes(StandardCharsets.US_ASCII)));
    var read = new ArrayList<VideoPacketTiming>();
    for (var packet = timings.next(); packet.isPresent(); packet = timings.next()) {
      read.add(packet.orElseThrow());
    }

    return read;
  }
}
