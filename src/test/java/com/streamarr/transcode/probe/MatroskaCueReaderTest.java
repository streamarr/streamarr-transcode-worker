package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.Listed;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import com.streamarr.transcode.probe.KeyframeIndex.Unusable;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Singular;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Matroska Cue Reader Tests")
class MatroskaCueReaderTest {

  private static final long VIDEO_TRACK = 1;
  private static final long AUDIO_TRACK = 2;
  private static final long SECOND_VIDEO_TRACK = 3;

  @TempDir Path temporaryDirectory;

  @Test
  @DisplayName("Should list every cue time of the video track when libavformat wrote the Cues")
  void shouldListEveryCueTimeOfVideoTrackWhenLibavformatWroteCues() throws Exception {
    var source =
        write(
            MatroskaFile.builder()
                .muxingApp("Lavf61.7.100")
                .cue(cue(2_000, VIDEO_TRACK))
                .cue(cue(0, VIDEO_TRACK, AUDIO_TRACK))
                .cue(cue(1_000, AUDIO_TRACK))
                .build());

    assertThat(MatroskaCueReader.read(source, 0))
        .isEqualTo(
            new Listed(
                new TimeBase(1, 1_000),
                List.of(new KeyframeTime(0, 0), new KeyframeTime(2_000, 2_000))));
  }

  @Test
  @DisplayName("Should trust the Cues when mkvmerge wrote the file")
  void shouldTrustCuesWhenMkvmergeWroteFile() throws Exception {
    var source =
        write(
            MatroskaFile.builder()
                .muxingApp("libebml v1.4.5 + libmatroska v1.7.1")
                .writingApp("mkvmerge v88.0 ('All I Know') 64-bit")
                .timestampScale(100_000L)
                .cue(cue(41, VIDEO_TRACK))
                .build());

    assertThat(MatroskaCueReader.read(source, 0))
        .isEqualTo(new Listed(new TimeBase(1, 10_000), List.of(new KeyframeTime(41, 41))));
  }

  @Test
  @DisplayName("Should read the cues of the chosen track when the file has two video tracks")
  void shouldReadCuesOfChosenTrackWhenFileHasTwoVideoTracks() throws Exception {
    var source =
        write(
            MatroskaFile.builder()
                .muxingApp("Lavf")
                .cue(cue(0, VIDEO_TRACK, SECOND_VIDEO_TRACK))
                .cue(cue(500, SECOND_VIDEO_TRACK))
                .build());

    assertThat(MatroskaCueReader.read(source, 1))
        .isEqualTo(
            new Listed(
                new TimeBase(1, 1_000),
                List.of(new KeyframeTime(0, 0), new KeyframeTime(500, 500))));
  }

  @Test
  @DisplayName("Should require a packet scan when the Cues are sparse and skip the video track")
  void shouldRequirePacketScanWhenCuesAreSparseAndSkipVideoTrack() throws Exception {
    var source =
        write(
            MatroskaFile.builder()
                .muxingApp("Lavf61.7.100")
                .cue(cue(0, AUDIO_TRACK))
                .cue(cue(1_000, AUDIO_TRACK))
                .build());

    assertThat(MatroskaCueReader.read(source, 0))
        .isEqualTo(
            new Unusable(
                KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX));
  }

  @Test
  @DisplayName("Should require a packet scan when an unknown muxing application wrote the Cues")
  void shouldRequirePacketScanWhenUnknownMuxingApplicationWroteCues() throws Exception {
    var source =
        write(
            MatroskaFile.builder()
                .muxingApp("Example Muxer 1.0")
                .writingApp("Example Writer 1.0")
                .cue(cue(0, VIDEO_TRACK))
                .build());

    assertThat(MatroskaCueReader.read(source, 0))
        .isEqualTo(
            new Unusable(
                KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX));
  }

  @Test
  @DisplayName(
      "Should report no container index when the Cues follow a Cluster that the SeekHead skips")
  void shouldReportNoContainerIndexWhenCuesFollowClusterThatSeekHeadSkips() throws Exception {
    var source =
        write(
            MatroskaFile.builder()
                .muxingApp("Lavf61.7.100")
                .cue(cue(0, VIDEO_TRACK))
                .cuesAfterCluster(true)
                .build());

    assertThat(MatroskaCueReader.read(source, 0))
        .isEqualTo(
            new Unusable(KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX));
  }

  @Test
  @DisplayName("Should find the Cues through the SeekHead when they follow the Clusters")
  void shouldFindCuesThroughSeekHeadWhenTheyFollowClusters() throws Exception {
    var source =
        write(
            MatroskaFile.builder()
                .muxingApp("Lavf61.7.100")
                .cue(cue(0, VIDEO_TRACK))
                .cuesAfterCluster(true)
                .seekHeadListsCues(true)
                .build());

    assertThat(MatroskaCueReader.read(source, 0))
        .isEqualTo(new Listed(new TimeBase(1, 1_000), List.of(new KeyframeTime(0, 0))));
  }

  @Test
  @DisplayName("Should require a packet scan when the video track scales its own timestamps")
  void shouldRequirePacketScanWhenVideoTrackScalesItsOwnTimestamps() throws Exception {
    var source =
        write(
            MatroskaFile.builder()
                .muxingApp("Lavf61.7.100")
                .trackTimestampScale(2.0)
                .cue(cue(0, VIDEO_TRACK))
                .build());

    assertThat(MatroskaCueReader.read(source, 0))
        .isEqualTo(
            new Unusable(
                KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX));
  }

  @Test
  @DisplayName("Should require a packet scan when the file is not a readable EBML document")
  void shouldRequirePacketScanWhenFileIsNotReadableEbmlDocument() throws Exception {
    var source = Files.write(temporaryDirectory.resolve("broken.mkv"), new byte[] {0x1A, 0x45});

    assertThat(MatroskaCueReader.read(source, 0))
        .isEqualTo(
            new Unusable(
                KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX));
  }

  private Path write(MatroskaFile file) throws Exception {
    return Files.write(temporaryDirectory.resolve("source.mkv"), file.bytes());
  }

  private static CuePoint cue(long time, long... tracks) {
    return new CuePoint(time, tracks);
  }

  private record CuePoint(long time, long[] tracks) {}

  /**
   * A minimal Matroska file: the EBML header, then a Segment with a SeekHead, Info, Tracks (video,
   * audio, video), Cues and one empty Cluster.
   */
  @Builder
  private record MatroskaFile(
      String muxingApp,
      String writingApp,
      Long timestampScale,
      Double trackTimestampScale,
      @Singular List<CuePoint> cues,
      boolean cuesAfterCluster,
      boolean seekHeadListsCues) {

    byte[] bytes() {
      var info = new ArrayList<byte[]>();
      if (timestampScale != null) {
        info.add(element(0x2AD7B1, unsigned(timestampScale)));
      }

      info.add(element(0x4D80, muxingApp.getBytes(StandardCharsets.UTF_8)));
      if (writingApp != null) {
        info.add(element(0x5741, writingApp.getBytes(StandardCharsets.UTF_8)));
      }

      var videoEntry = new ArrayList<byte[]>();
      videoEntry.add(element(0xD7, unsigned(VIDEO_TRACK)));
      videoEntry.add(element(0x83, unsigned(1)));
      if (trackTimestampScale != null) {
        videoEntry.add(
            element(0x23314F, ByteBuffer.allocate(8).putDouble(trackTimestampScale).array()));
      }

      var tracks =
          element(
              0x1654AE6B,
              element(0xAE, videoEntry.toArray(byte[][]::new)),
              element(0xAE, element(0xD7, unsigned(AUDIO_TRACK)), element(0x83, unsigned(2))),
              element(
                  0xAE, element(0xD7, unsigned(SECOND_VIDEO_TRACK)), element(0x83, unsigned(1))));
      var cueElement =
          element(0x1C53BB6B, cues.stream().map(this::cuePoint).toArray(byte[][]::new));
      var cluster = element(0x1F43B675, element(0xE7, unsigned(0)));
      var infoElement = element(0x1549A966, info.toArray(byte[][]::new));

      // The SeekHead has a fixed size, so the Cues position is known before it is written.
      var seekHeadSize = element(0x114D9B74, seek(0x1C53BB6B, 0)).length;
      var cuesPosition =
          seekHeadSize
              + infoElement.length
              + tracks.length
              + (cuesAfterCluster ? cluster.length : 0);
      var seekEntry = seek(0x1549A966, seekHeadSize);
      if (seekHeadListsCues) {
        seekEntry = seek(0x1C53BB6B, cuesPosition);
      }

      var seekHead = element(0x114D9B74, seekEntry);
      var body =
          cuesAfterCluster
              ? List.of(seekHead, infoElement, tracks, cluster, cueElement)
              : List.of(seekHead, infoElement, tracks, cueElement, cluster);
      var header =
          element(0x1A45DFA3, element(0x4282, "matroska".getBytes(StandardCharsets.UTF_8)));
      return concat(header, element(0x18538067, body.toArray(byte[][]::new)));
    }

    private byte[] cuePoint(CuePoint point) {
      var children = new ArrayList<byte[]>();
      children.add(element(0xB3, unsigned(point.time())));
      for (var track : point.tracks()) {
        children.add(element(0xB7, element(0xF7, unsigned(track)), element(0xF1, unsigned(0))));
      }

      return element(0xBB, children.toArray(byte[][]::new));
    }

    private static byte[] seek(long id, long position) {
      return element(
          0x4DBB, element(0x53AB, unsigned(id)), element(0x53AC, fixedUnsigned(position)));
    }
  }

  private static byte[] element(long id, byte[]... children) {
    var payload = concat(children);
    var size = ByteBuffer.allocate(8).putLong(payload.length).array();
    size[0] = 0x01;
    return concat(unsigned(id), size, payload);
  }

  private static byte[] unsigned(long value) {
    var bytes = ByteBuffer.allocate(8).putLong(value).array();
    var first = 0;
    while (first < 7 && bytes[first] == 0) {
      first++;
    }

    var trimmed = new byte[8 - first];
    System.arraycopy(bytes, first, trimmed, 0, trimmed.length);
    return trimmed;
  }

  private static byte[] fixedUnsigned(long value) {
    return ByteBuffer.allocate(8).putLong(value).array();
  }

  private static byte[] concat(byte[]... parts) {
    var output = new ByteArrayOutputStream();
    for (var part : parts) {
      output.writeBytes(part);
    }

    return output.toByteArray();
  }
}
