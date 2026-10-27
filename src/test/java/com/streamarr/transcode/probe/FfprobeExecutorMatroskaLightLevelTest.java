package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.EbmlElements.concat;
import static com.streamarr.transcode.probe.EbmlElements.document;
import static com.streamarr.transcode.probe.EbmlElements.element;
import static com.streamarr.transcode.probe.EbmlElements.unsigned;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.probe.RecordedFfprobe.Answers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("FFprobe Executor Matroska Light Level Tests")
class FfprobeExecutorMatroskaLightLevelTest {

  private static final long MAX_CLL = 0x55BC;
  private static final long MAX_FALL = 0x55BD;
  private static final String MATROSKA = "matroska,webm";
  private static final String FRAME_LIGHT_LEVEL =
      """
      {"side_data_type":"Content light level metadata","max_content":1000,"max_average":400}""";

  @TempDir Path temporaryDirectory;

  @Test
  @DisplayName("Should report MaxFALL 0 when the video track's Colour holds only MaxCLL")
  void shouldReportMaxFall0WhenTheVideoTracksColourHoldsOnlyMaxCll() throws IOException {
    var video = probeMatroska(MATROSKA, videoTrack(element(MAX_CLL, unsigned(900))));

    assertThat(video.getContentLightLevel())
        .isEqualTo(
            ContentLightLevel.newBuilder()
                .setMaxContentLightLevel(900)
                .setMaxFrameAverageLightLevel(0)
                .build());
  }

  @Test
  @DisplayName("Should report MaxCLL 0 when the video track's Colour holds only MaxFALL")
  void shouldReportMaxCll0WhenTheVideoTracksColourHoldsOnlyMaxFall() throws IOException {
    var video = probeMatroska(MATROSKA, videoTrack(element(MAX_FALL, unsigned(300))));

    assertThat(video.getContentLightLevel())
        .isEqualTo(ContentLightLevel.newBuilder().setMaxFrameAverageLightLevel(300).build());
  }

  @Test
  @DisplayName("Should read the track's light level when a Void element precedes the Segment")
  void shouldReadTheTracksLightLevelWhenAVoidElementPrecedesTheSegment() throws IOException {
    var header = element(0x1A45DFA3, element(0x4282, "matroska".getBytes(StandardCharsets.UTF_8)));
    var segment =
        element(0x18538067, element(0x1654AE6B, trackEntry(1, element(MAX_CLL, unsigned(900)))));
    var video = probeMatroska(MATROSKA, concat(header, element(0xEC, new byte[4]), segment));

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(900);
  }

  @Test
  @DisplayName("Should prefer the track's elements over the first frame's light level")
  void shouldPreferTheTracksElementsOverTheFirstFramesLightLevel() throws IOException {
    var video =
        probeMatroska(
            MATROSKA,
            videoTrack(element(MAX_CLL, unsigned(900)), element(MAX_FALL, unsigned(0))),
            FRAME_LIGHT_LEVEL);

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(900);
  }

  @Test
  @DisplayName("Should read the chosen video track when an audio track comes first")
  void shouldReadTheChosenVideoTrackWhenAnAudioTrackComesFirst() throws IOException {
    var audio = element(0xAE, element(0xD7, unsigned(1)), element(0x83, unsigned(2)));
    var video =
        probeMatroska(
            MATROSKA,
            document(element(0x1654AE6B, audio, trackEntry(2, element(MAX_CLL, unsigned(700))))));

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(700);
  }

  @Test
  @DisplayName("Should mark the light level omitted when MaxCLL does not fit its SEI field")
  void shouldMarkTheLightLevelOmittedWhenMaxCllDoesNotFitItsSeiField() throws IOException {
    var video =
        probeMatroska(MATROSKA, videoTrack(element(MAX_CLL, unsigned(65_536))), FRAME_LIGHT_LEVEL);

    assertThat(video.hasContentLightLevel()).isFalse();
    assertThat(video.getContentLightLevelOmitted()).isTrue();
  }

  @Test
  @DisplayName("Should take the first frame's light level when the Colour holds neither element")
  void shouldTakeTheFirstFramesLightLevelWhenTheColourHoldsNeitherElement() throws IOException {
    var video =
        probeMatroska(MATROSKA, videoTrack(element(0x55B9, unsigned(1))), FRAME_LIGHT_LEVEL);

    assertThat(video.getContentLightLevel().getMaxFrameAverageLightLevel()).isEqualTo(400);
    assertThat(video.getContentLightLevelOmitted()).isFalse();
  }

  @Test
  @DisplayName("Should take the first frame's light level when MaxCLL is longer than 8 bytes")
  void shouldTakeTheFirstFramesLightLevelWhenMaxCllIsLongerThan8Bytes() throws IOException {
    var video =
        probeMatroska(MATROSKA, videoTrack(element(MAX_CLL, new byte[9])), FRAME_LIGHT_LEVEL);

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
  }

  @Test
  @DisplayName("Should take the first frame's light level when the file is no Matroska file")
  void shouldTakeTheFirstFramesLightLevelWhenTheFileIsNoMatroskaFile() throws IOException {
    var video = probeMatroska(MATROSKA, new byte[] {0x00, 0x01}, FRAME_LIGHT_LEVEL);

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(1000);
  }

  @Test
  @DisplayName("Should leave the light level absent when the probe cannot open the source")
  void shouldLeaveTheLightLevelAbsentWhenTheProbeCannotOpenTheSource() {
    var video =
        probe(
                Answers.builder()
                    .ffprobeOutput(output(MATROSKA, ""))
                    .source(temporaryDirectory.resolve("missing.mkv"))
                    .build())
            .getStreams(0);

    assertThat(video.hasContentLightLevel()).isFalse();
    assertThat(video.getContentLightLevelOmitted()).isFalse();
  }

  @Test
  @DisplayName("Should read no track elements when the container is not Matroska")
  void shouldReadNoTrackElementsWhenTheContainerIsNotMatroska() throws IOException {
    var video =
        probeMatroska("mov,mp4,m4a,3gp,3g2,mj2", videoTrack(element(MAX_CLL, unsigned(900))));

    assertThat(video.hasContentLightLevel()).isFalse();
  }

  @Test
  @DisplayName("Should keep ffprobe's light level when ffprobe reports one for the stream")
  void shouldKeepFfprobesLightLevelWhenFfprobeReportsOneForTheStream() throws IOException {
    var source =
        Files.write(
            temporaryDirectory.resolve("source.mkv"),
            videoTrack(element(MAX_CLL, unsigned(900)), element(MAX_FALL, unsigned(100))));
    var video =
        probe(
                Answers.builder()
                    .ffprobeOutput(
                        output(
                            MATROSKA,
                            """
                            ,"side_data_list":[{"side_data_type":"Content light level metadata",
                              "max_content":950,"max_average":120}]"""))
                    .source(source)
                    .build())
            .getStreams(0);

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(950);
  }

  private ProbeStreamInfo probeMatroska(String formatName, byte[] file) throws IOException {
    return probeMatroska(formatName, file, null);
  }

  private ProbeStreamInfo probeMatroska(String formatName, byte[] file, String frameSideData)
      throws IOException {
    var source = Files.write(temporaryDirectory.resolve("source.mkv"), file);
    var answers = Answers.builder().ffprobeOutput(output(formatName, "")).source(source);
    if (frameSideData != null) {
      answers.firstFrames(
          "{\"frames\":[{\"stream_index\":0,\"side_data_list\":[" + frameSideData + "]}]}");
    }

    return probe(answers.build()).getStreams(0);
  }

  /** A Matroska file whose one track is a video track with these Colour children. */
  private static byte[] videoTrack(byte[]... colour) {
    return document(element(0x1654AE6B, trackEntry(1, colour)));
  }

  private static byte[] trackEntry(long number, byte[]... colour) {
    var children = new ArrayList<byte[]>();
    children.add(element(0xD7, unsigned(number)));
    children.add(element(0x83, unsigned(1)));
    children.add(element(0xE0, element(0xB0, unsigned(64)), element(0x55B0, colour)));
    return element(0xAE, children.toArray(byte[][]::new));
  }

  private static String output(String formatName, String fields) {
    return """
        {"streams":[{"index":0,"codec_type":"video","codec_name":"hevc","time_base":"1/1000"%s}],
         "format":{"format_name":"%s"}}
        """
        .formatted(fields, formatName);
  }
}
