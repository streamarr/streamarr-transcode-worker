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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("FFprobe Executor Matroska Colour Tests")
class FfprobeExecutorMatroskaColourTest {

  private static final long MAX_CLL = 0x55BC;
  private static final long MAX_FALL = 0x55BD;
  private static final long MASTERING_METADATA = 0x55D0;
  private static final long LUMINANCE_MAX = 0x55D9;
  private static final String MATROSKA = "matroska,webm";
  private static final String FRAME_MASTERING_DISPLAY =
      """
      {"side_data_type":"Mastering display metadata","red_x":"34000/50000",
       "red_y":"16000/50000","green_x":"13250/50000","green_y":"34500/50000",
       "blue_x":"7500/50000","blue_y":"3000/50000","white_point_x":"15635/50000",
       "white_point_y":"16450/50000","min_luminance":"50/10000",
       "max_luminance":"10000000/10000"}""";
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

  @Test
  @DisplayName(
      "Should mark the mastering display omitted when ffprobe drops the track's incomplete"
          + " MasteringMetadata")
  void shouldMarkTheMasteringDisplayOmittedWhenFfprobeDropsTheTracksIncompleteMasteringMetadata()
      throws IOException {
    // FFmpeg's Matroska demuxer drops a MasteringMetadata element without all of its primaries.
    var video = probeMatroska(MATROSKA, videoTrack(maxLuminanceOnly()));

    assertThat(video.hasMasteringDisplay()).isFalse();
    assertThat(video.getMasteringDisplayOmitted()).isTrue();
  }

  @Test
  @DisplayName("Should take the first frame's mastering display when the track's is incomplete")
  void shouldTakeTheFirstFramesMasteringDisplayWhenTheTracksIsIncomplete() throws IOException {
    var video = probeMatroska(MATROSKA, videoTrack(maxLuminanceOnly()), FRAME_MASTERING_DISPLAY);

    assertThat(video.getMasteringDisplay().getMaxLuminance()).isEqualTo(10_000_000);
    assertThat(video.getMasteringDisplayOmitted()).isFalse();
  }

  @Test
  @DisplayName("Should keep ffprobe's mastering display when ffprobe reports one for the stream")
  void shouldKeepFfprobesMasteringDisplayWhenFfprobeReportsOneForTheStream() throws IOException {
    var source =
        Files.write(temporaryDirectory.resolve("source.mkv"), videoTrack(maxLuminanceOnly()));
    var video =
        probe(
                Answers.builder()
                    .ffprobeOutput(
                        output(MATROSKA, ",\"side_data_list\":[" + FRAME_MASTERING_DISPLAY + "]"))
                    .source(source)
                    .build())
            .getStreams(0);

    assertThat(video.getMasteringDisplay().getMaxLuminance()).isEqualTo(10_000_000);
    assertThat(video.getMasteringDisplayOmitted()).isFalse();
  }

  @Test
  @DisplayName(
      "Should leave the mastering display unmarked when the track holds no MasteringMetadata")
  void shouldLeaveTheMasteringDisplayUnmarkedWhenTheTrackHoldsNoMasteringMetadata()
      throws IOException {
    var video = probeMatroska(MATROSKA, videoTrack(element(MAX_CLL, unsigned(900))));

    assertThat(video.getMasteringDisplayOmitted()).isFalse();
  }

  @Test
  @DisplayName(
      "Should read the track's Colour when the file ends inside the Cues that its SeekHead names")
  void shouldReadTheTracksColourWhenTheFileEndsInsideTheCuesThatItsSeekHeadNames()
      throws IOException {
    // A partly copied file: the Cues come after the Clusters, and the copy stopped inside them.
    var tracks =
        element(0x1654AE6B, trackEntry(1, element(MAX_CLL, unsigned(900)), maxLuminanceOnly()));
    var cluster = element(0x1F43B675, element(0xE7, unsigned(0)));
    var cuesPosition = seekHead(0x1C53BB6B, 0).length + tracks.length + cluster.length;
    var whole =
        document(
            seekHead(0x1C53BB6B, cuesPosition), tracks, cluster, element(0x1C53BB6B, new byte[64]));

    var video = probeMatroska(MATROSKA, Arrays.copyOf(whole, whole.length - 32));

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(900);
    assertThat(video.getMasteringDisplayOmitted()).isTrue();
  }

  @Test
  @DisplayName("Should leave the mastering display unmarked when the container is not Matroska")
  void shouldLeaveTheMasteringDisplayUnmarkedWhenTheContainerIsNotMatroska() throws IOException {
    var video = probeMatroska("mov,mp4,m4a,3gp,3g2,mj2", videoTrack(maxLuminanceOnly()));

    assertThat(video.getMasteringDisplayOmitted()).isFalse();
  }

  /** A MasteringMetadata element that holds only LuminanceMax, 1000 cd/m^2 as a 4-byte float. */
  private static byte[] maxLuminanceOnly() {
    return element(
        MASTERING_METADATA, element(LUMINANCE_MAX, ByteBuffer.allocate(4).putFloat(1000).array()));
  }

  /** A SeekHead that names one element's position in the Segment, as an 8-byte number. */
  private static byte[] seekHead(long id, long position) {
    var seek =
        element(
            0x4DBB,
            element(0x53AB, unsigned(id)),
            element(0x53AC, ByteBuffer.allocate(8).putLong(position).array()));
    return element(0x114D9B74, seek);
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
