package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Source Stream Observation Tests")
class SourceStreamObservationTest {

  // FFmpeg's stream mapping for a command whose fragmented MP4 is output 0 and whose framecrc of
  // the source's streams is output 1, for a source whose video is stream 2 and audio stream 1.
  private static final String MAPPING =
      """
      Stream mapping:
        Stream #0:2 -> #0:0 (h264 (native) -> h264 (libx264))
        Stream #0:1 -> #0:1 (aac (native) -> aac (native))
        Stream #0:2 -> #1:0 (copy)
        Stream #0:1 -> #1:1 (copy)
      """;

  private static final String HEADER =
      """
      #software: Lavf62.3.100
      #tb 0: 1/1000
      #media_type 0: video
      #codec_id 0: h264
      #dimensions 0: 64x36
      #sar 0: 1/1
      #tb 1: 1/48000
      #media_type 1: audio
      #codec_id 1: aac
      #sample_rate 1: 48000
      #channel_layout_name 1: stereo
      """;

  private final SourceStreamObservation observation = new SourceStreamObservation();

  @Test
  @DisplayName(
      "Should observe each copied stream's source index, last start, end and last keyframe when the"
          + " framecrc's packets arrive in decode order")
  void shouldObserveEachCopiedStreamsLastSampleAndKeyframeWhenPacketsArriveInDecodeOrder() {
    readAll(
        MAPPING
            + HEADER
            + """
            0,      -40,          0,       40,     1000, 0x0a1b2c3d
            1,        0,          0,     1024,      300, 0x0a1b2c3d
            0,        0,         80,       40,      400, 0x0a1b2c3d, F=0x0
            0,       40,         40,       40,      300, 0x0a1b2c3d, F=0x0
            0,       80,        120,       40,      900, 0x0a1b2c3d
            0,      120,        160,       40,      200, 0x0a1b2c3d, F=0x0
            1,     1024,       1024,      512,      200, 0x0a1b2c3d
            """);

    assertThat(observation.streams())
        .containsExactly(
            ObservedSourceStream.builder()
                .sourceIndex(2)
                .lastStart(Duration.ofMillis(160))
                .end(Duration.ofMillis(200))
                .lastKeyframeStart(Optional.of(Duration.ofMillis(120)))
                .build(),
            ObservedSourceStream.builder()
                .sourceIndex(1)
                .lastStart(Duration.ofNanos(21_333_333))
                .end(Duration.ofNanos(32_000_000))
                .lastKeyframeStart(Optional.of(Duration.ofNanos(21_333_333)))
                .build());
  }

  @Test
  @DisplayName(
      "Should observe a record when part of a log line precedes it on the same line of the error"
          + " output")
  void shouldObserveARecordWhenPartOfALogLinePrecedesItOnTheSameLine() {
    readAll(MAPPING + HEADER + "0,        0,          0,       40,     1000, 0x0a1b2c3d\n");

    var diverted =
        observation.tryRead(
            "frame=   12 fps=0.0 q=21.0 size=     5KiB elapsed=0:00:00.50    0,       40,"
                + "         40,       40,      300, 0x0a1b2c3d, F=0x0");

    assertThat(diverted).isTrue();
    assertThat(observation.streams())
        .singleElement()
        .extracting(ObservedSourceStream::end)
        .isEqualTo(Duration.ofMillis(80));
  }

  @Test
  @DisplayName(
      "Should read the record's stream index as the one digit before its first comma when a log"
          + " line that ends in digits runs into it")
  void shouldReadTheStreamIndexAsTheDigitBeforeTheFirstCommaWhenALogLineEndingInDigitsRunsIn() {
    readAll(MAPPING + HEADER);

    observation.tryRead(
        "    encoder         : Lavf62.3.1000,      771,        813,       41,  300, 0x0a1b2c3d");

    assertThat(observation.streams())
        .singleElement()
        .satisfies(
            stream -> {
              assertThat(stream.sourceIndex()).isEqualTo(2);
              assertThat(stream.end()).isEqualTo(Duration.ofMillis(854));
            });
  }

  // Raw H.264 and HEVC streams carry decode times alone.
  @Test
  @DisplayName(
      "Should place a packet at its decode time when it carries no presentation time, and skip it"
          + " when it carries neither")
  void shouldPlaceAPacketAtItsDecodeTimeWhenItCarriesNoPresentationTime() {
    readAll(
        MAPPING
            + HEADER
            + """
            0,      120, -9223372036854775808,       40,     1000, 0x0a1b2c3d
            0, -9223372036854775808, -9223372036854775808,       40,      300, 0x0a1b2c3d, F=0x0
            """);

    assertThat(observation.streams())
        .containsExactly(
            ObservedSourceStream.builder()
                .sourceIndex(2)
                .lastStart(Duration.ofMillis(120))
                .end(Duration.ofMillis(160))
                .lastKeyframeStart(Optional.of(Duration.ofMillis(120)))
                .build());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "Stream mapping:",
        "  Stream #0:2 -> #1:0 (copy)",
        "[mp4 @ 0x7f8] Starting second pass: moving the moov atom to the beginning of the file",
        "frame=   12 fps=0.0 q=21.0 size=     5KiB time=00:00:00.50 bitrate= 81.9kbits/s"
      })
  @DisplayName("Should leave a line in the diagnostics when it carries no part of the framecrc")
  void shouldLeaveALineInTheDiagnosticsWhenItCarriesNoPartOfTheFramecrc(String line) {
    assertThat(observation.tryRead(line)).isFalse();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "#software: Lavf62.3.100",
        "#tb 0: 1/1000",
        "0,        0,          0,       40,     1000, 0x0a1b2c3d",
        "1,     1024,       1024,      512,      200, 0x0a1b2c3d, S=1,        8, 0x00010001"
      })
  @DisplayName("Should keep a line out of the diagnostics when it carries part of the framecrc")
  void shouldKeepALineOutOfTheDiagnosticsWhenItCarriesPartOfTheFramecrc(String line) {
    readAll(MAPPING);

    assertThat(observation.tryRead(line)).isTrue();
  }

  // FFmpeg writes the input's metadata to its error output before the stream mapping, and the
  // framecrc only after it.
  @Test
  @DisplayName(
      "Should read nothing of the framecrc before FFmpeg's stream mapping when the input's"
          + " metadata there carries text shaped like it")
  void shouldReadNothingOfTheFramecrcBeforeTheStreamMappingWhenTheInputsMetadataIsShapedLikeIt() {
    var metadata =
        """
        Input #0, mov,mp4,m4a,3gp,3g2,mj2, from '/media/source.mp4':
          Metadata:
            title           : Sample #tb 2: 1/1
            comment         : 0, 999999999, 999999999, 1, 1000, 0x0a1b2c3d
            album           : Stream #0:4 -> #1:2 (copy)
        #tb 2: 1/1
        2,     9999,       9999,        1,      100, 0x0a1b2c3d
        """;

    readAll(
        metadata + MAPPING + HEADER + "0,        0,          0,       40,     1000, 0x0a1b2c3d\n");

    assertThat(observation.streams())
        .containsExactly(
            ObservedSourceStream.builder()
                .sourceIndex(2)
                .lastStart(Duration.ZERO)
                .end(Duration.ofMillis(40))
                .lastKeyframeStart(Optional.of(Duration.ZERO))
                .build());
  }

  @Test
  @DisplayName(
      "Should observe no stream when the mapping names no source for the framecrc's streams, as"
          + " for a command that writes the framecrc to another output")
  void shouldObserveNoStreamWhenTheMappingNamesNoSourceForTheFramecrcsStreams() {
    readAll(
        """
        Stream mapping:
          Stream #0:2 -> #0:0 (copy)
          Stream #0:2 -> #2:0 (copy)
        """
            + HEADER
            + "0,        0,          0,       40,     1000, 0x0a1b2c3d\n");

    assertThat(observation.streams()).isEmpty();
  }

  @Test
  @DisplayName("Should observe no stream when the framecrc names no time base for its packets")
  void shouldObserveNoStreamWhenTheFramecrcNamesNoTimeBaseForItsPackets() {
    readAll(MAPPING + "0,        0,          0,       40,     1000, 0x0a1b2c3d\n");

    assertThat(observation.streams()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should leave a stream out when its packet's time lies beyond what a duration can hold")
  void shouldLeaveAStreamOutWhenItsPacketsTimeLiesBeyondWhatADurationCanHold() {
    readAll(
        MAPPING
            + "#tb 0: 1/1\n#tb 1: 1/48000\n"
            + "0, 9223372036854775000, 9223372036854775000, 1, 1000, 0x0a1b2c3d\n"
            + "1,        0,          0,     1024,      300, 0x0a1b2c3d\n");

    assertThat(observation.streams())
        .extracting(ObservedSourceStream::sourceIndex)
        .containsExactly(1);
  }

  @Test
  @DisplayName("Should observe no keyframe when every packet of a stream carries other flags")
  void shouldObserveNoKeyframeWhenEveryPacketOfAStreamCarriesOtherFlags() {
    readAll(MAPPING + HEADER + "0,        0,          0,       40,     1000, 0x0a1b2c3d, F=0x0\n");

    assertThat(observation.streams())
        .singleElement()
        .extracting(ObservedSourceStream::lastKeyframeStart)
        .isEqualTo(Optional.empty());
  }

  private void readAll(String errorOutput) {
    errorOutput.lines().forEach(observation::tryRead);
  }
}
