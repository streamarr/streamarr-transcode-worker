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
import java.util.HexFormat;
import java.util.List;
import lombok.Builder;
import lombok.Singular;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Matroska Cue Reader Tests")
class MatroskaCueReaderTest {

  private static final long VIDEO_TRACK = 1;
  private static final long AUDIO_TRACK = 2;
  private static final long SECOND_VIDEO_TRACK = 3;
  private static final Unusable INCOMPLETE =
      new Unusable(KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX);
  private static final Unusable NO_INDEX =
      new Unusable(KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX);

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
                .trackTimestampScale(ByteBuffer.allocate(Double.BYTES).putDouble(2.0).array())
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

  @Test
  @DisplayName(
      "Should list the cue times when the video track stores its timestamp scale in 4 bytes")
  void shouldListCueTimesWhenVideoTrackStoresItsTimestampScaleInFourBytes() throws Exception {
    var source =
        write(
            MatroskaFile.builder()
                .muxingApp("Lavf61.7.100")
                .trackTimestampScale(ByteBuffer.allocate(Float.BYTES).putFloat(1.0f).array())
                .cue(cue(0, VIDEO_TRACK))
                .build());

    assertThat(MatroskaCueReader.read(source, 0))
        .isEqualTo(new Listed(new TimeBase(1, 1_000), List.of(new KeyframeTime(0, 0))));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "'a TrackEntry of unknown size', 'AE FF'",
    "'a TrackEntry longer than the Tracks', 'AE 85'",
    "'an element ID that starts with a zero byte', '00 80'",
    "'an element ID five bytes long', '08 00 00 00 00 80'",
    "'a TrackType nine bytes long', 'AE 8B 83 89 00 00 00 00 00 00 00 00 01'",
    "'a TrackTimestampScale three bytes long', 'AE 8D 83 81 01 D7 81 01 23 31 4F 83 00 00 00'"
  })
  @DisplayName("Should require a packet scan when the Tracks hold a malformed element")
  void shouldRequirePacketScanWhenTracksHoldMalformedElement(String malformation, String tracks)
      throws Exception {
    var source = write(document(lavfInfo(), element(0x1654AE6B, hex(tracks)), videoCueAt(0)));

    assertThat(MatroskaCueReader.read(source, 0)).as(malformation).isEqualTo(INCOMPLETE);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "'an empty file', ''",
    "'a Segment without an EBML header', '18 53 80 67 80'",
    "'an EBML header of unknown size', '1A 45 DF A3 FF'",
    "'an EBML header followed by Info instead of a Segment', '1A 45 DF A3 80 15 49 A9 66 80'",
    "'an EBML header and nothing after it', '1A 45 DF A3 80'",
    "'Tracks that declare 65 MiB', '1A 45 DF A3 80 18 53 80 67 FF 16 54 AE 6B 01 00 00 00 04 10 00 00'",
    "'Tracks that declare more bytes than the file holds', '1A 45 DF A3 80 18 53 80 67 FF 16 54 AE 6B 90 AE 80'"
  })
  @DisplayName("Should require a packet scan when the file's top-level structure is malformed")
  void shouldRequirePacketScanWhenFilesTopLevelStructureIsMalformed(String file, String bytes)
      throws Exception {
    var source = write(hex(bytes));

    assertThat(MatroskaCueReader.read(source, 0)).as(file).isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName("Should list the cue times when the Segment ends without a Cluster")
  void shouldListCueTimesWhenSegmentEndsWithoutCluster() throws Exception {
    var source = write(document(lavfInfo(), oneVideoTrack(), videoCueAt(0)));

    assertThat(MatroskaCueReader.read(source, 0))
        .isEqualTo(new Listed(new TimeBase(1, 1_000), List.of(new KeyframeTime(0, 0))));
  }

  @Test
  @DisplayName("Should list the cue times when an element of unknown size follows the Cues")
  void shouldListCueTimesWhenElementOfUnknownSizeFollowsCues() throws Exception {
    var tagsOfUnknownSize = hex("12 54 C3 67 FF");
    var source = write(document(lavfInfo(), oneVideoTrack(), videoCueAt(0), tagsOfUnknownSize));

    assertThat(MatroskaCueReader.read(source, 0))
        .isEqualTo(new Listed(new TimeBase(1, 1_000), List.of(new KeyframeTime(0, 0))));
  }

  @Test
  @DisplayName("Should report no container index when the SeekHead points past the end of the file")
  void shouldReportNoContainerIndexWhenSeekHeadPointsPastEndOfFile() throws Exception {
    var source = write(cuesSeekPastCluster(1L << 20, videoCueAt(0)));

    assertThat(MatroskaCueReader.read(source, 0)).isEqualTo(NO_INDEX);
  }

  @Test
  @DisplayName("Should report no container index when the SeekHead points at the Cluster")
  void shouldReportNoContainerIndexWhenSeekHeadPointsAtCluster() throws Exception {
    var source = write(cuesSeekPastCluster(clusterPositionAfterCuesSeekHead(), videoCueAt(0)));

    assertThat(MatroskaCueReader.read(source, 0)).isEqualTo(NO_INDEX);
  }

  @Test
  @DisplayName("Should report no container index when the SeekHead points at Cues of unknown size")
  void shouldReportNoContainerIndexWhenSeekHeadPointsAtCuesOfUnknownSize() throws Exception {
    var cuesPosition = clusterPositionAfterCuesSeekHead() + cluster().length;
    var source = write(cuesSeekPastCluster(cuesPosition, hex("1C 53 BB 6B FF")));

    assertThat(MatroskaCueReader.read(source, 0)).isEqualTo(NO_INDEX);
  }

  @Test
  @DisplayName("Should ignore a Seek that lacks its SeekID or its SeekPosition")
  void shouldIgnoreSeekThatLacksItsSeekIdOrItsSeekPosition() throws Exception {
    var cuesPosition =
        incompleteSeekHead(0).length
            + lavfInfo().length
            + oneVideoTrack().length
            + cluster().length;
    var source =
        write(
            document(
                incompleteSeekHead(cuesPosition),
                lavfInfo(),
                oneVideoTrack(),
                cluster(),
                videoCueAt(0)));

    assertThat(MatroskaCueReader.read(source, 0)).isEqualTo(NO_INDEX);
  }

  private Path write(MatroskaFile file) throws Exception {
    return write(file.bytes());
  }

  private Path write(byte[] bytes) throws Exception {
    return Files.write(temporaryDirectory.resolve("source.mkv"), bytes);
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
      byte[] trackTimestampScale,
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
        videoEntry.add(element(0x23314F, trackTimestampScale));
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
      var seekHeadSize = element(0x114D9B74, seekEntry(0x1C53BB6B, 0)).length;
      var cuesPosition =
          seekHeadSize
              + infoElement.length
              + tracks.length
              + (cuesAfterCluster ? cluster.length : 0);
      var seekEntry = seekEntry(0x1549A966, seekHeadSize);
      if (seekHeadListsCues) {
        seekEntry = seekEntry(0x1C53BB6B, cuesPosition);
      }

      var seekHead = element(0x114D9B74, seekEntry);
      var body =
          cuesAfterCluster
              ? List.of(seekHead, infoElement, tracks, cluster, cueElement)
              : List.of(seekHead, infoElement, tracks, cueElement, cluster);
      return document(body.toArray(byte[][]::new));
    }

    private byte[] cuePoint(CuePoint point) {
      var children = new ArrayList<byte[]>();
      children.add(element(0xB3, unsigned(point.time())));
      for (var track : point.tracks()) {
        children.add(element(0xB7, element(0xF7, unsigned(track)), element(0xF1, unsigned(0))));
      }

      return element(0xBB, children.toArray(byte[][]::new));
    }
  }

  /** The EBML header, then a Segment that holds {@code segmentChildren}. */
  private static byte[] document(byte[]... segmentChildren) {
    var header = element(0x1A45DFA3, element(0x4282, "matroska".getBytes(StandardCharsets.UTF_8)));
    return concat(header, element(0x18538067, segmentChildren));
  }

  private static byte[] lavfInfo() {
    return element(0x1549A966, element(0x4D80, "Lavf61.7.100".getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] oneVideoTrack() {
    return element(
        0x1654AE6B,
        element(0xAE, element(0xD7, unsigned(VIDEO_TRACK)), element(0x83, unsigned(1))));
  }

  private static byte[] videoCueAt(long time) {
    return element(
        0x1C53BB6B,
        element(0xBB, element(0xB3, unsigned(time)), element(0xB7, element(0xF7, unsigned(1)))));
  }

  private static byte[] cluster() {
    return element(0x1F43B675, element(0xE7, unsigned(0)));
  }

  /** A SeekHead that lists the Cues, then Info, Tracks and a Cluster, then {@code afterCluster}. */
  private static byte[] cuesSeekPastCluster(long cuesPosition, byte[] afterCluster) {
    return document(
        element(0x114D9B74, seekEntry(0x1C53BB6B, cuesPosition)),
        lavfInfo(),
        oneVideoTrack(),
        cluster(),
        afterCluster);
  }

  /** The position of the Cluster in {@link #cuesSeekPastCluster}, from the Segment's data. */
  private static long clusterPositionAfterCuesSeekHead() {
    return element(0x114D9B74, seekEntry(0x1C53BB6B, 0)).length
        + lavfInfo().length
        + oneVideoTrack().length;
  }

  /** A SeekHead whose one Seek has no SeekID and whose other Seek has no SeekPosition. */
  private static byte[] incompleteSeekHead(long cuesPosition) {
    return element(
        0x114D9B74,
        element(0x4DBB, element(0x53AC, fixedUnsigned(cuesPosition))),
        element(0x4DBB, element(0x53AB, unsigned(0x1C53BB6B))));
  }

  /** A Seek whose SeekPosition always takes eight bytes, so its size never depends on the value. */
  private static byte[] seekEntry(long id, long position) {
    return element(0x4DBB, element(0x53AB, unsigned(id)), element(0x53AC, fixedUnsigned(position)));
  }

  private static byte[] hex(String bytes) {
    return HexFormat.of().parseHex(bytes.replace(" ", ""));
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
