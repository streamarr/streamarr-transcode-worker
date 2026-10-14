package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.Listed;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import com.streamarr.transcode.probe.KeyframeIndex.Unusable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("MP4 Sync Sample Reader Tests")
class Mp4SyncSampleReaderTest {

  private static final long TRACK_ID = SyntheticMp4.TRACK_ID;
  private static final Unusable INCOMPLETE =
      new Unusable(KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX);

  @TempDir Path temporaryDirectory;

  @Test
  @DisplayName("Should list every sample when the track has no sync-sample table")
  void shouldListEverySampleWhenTrackHasNoSyncSampleTable() throws Exception {
    var source =
        write(SyntheticMp4.builder().mediaTimescale(1_000).sampleDurations(3, 100).build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID))
        .isEqualTo(
            new Listed(
                new TimeBase(1, 1_000),
                List.of(
                    new KeyframeTime(0, 0),
                    new KeyframeTime(100, 100),
                    new KeyframeTime(200, 200))));
  }

  @Test
  @DisplayName("Should shift every time by the empty edit when the edit list delays the media")
  void shouldShiftEveryTimeByEmptyEditWhenEditListDelaysMedia() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .largeMovieBox(true)
                .mediaTimescale(12_288)
                .sampleDurations(4, 512)
                .compositionOffsets(4, 1_024)
                .syncSamples(List.of(1L, 3L))
                .editListVersion(1)
                .edit(new SyntheticMp4.Edit(500, -1))
                .edit(new SyntheticMp4.Edit(4_000, 1_024))
                .build());

    // 500 ms of empty edit is 6,144 ticks; the media edit starts 1,024 ticks into the media.
    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID))
        .isEqualTo(
            new Listed(
                new TimeBase(1, 12_288),
                List.of(new KeyframeTime(6_144, 5_120), new KeyframeTime(7_168, 6_144))));
  }

  @Test
  @DisplayName("Should drop keyframes the demuxer skips when the edit list trims the start")
  void shouldDropKeyframesDemuxerSkipsWhenEditListTrimsStart() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 1_000)
                .syncSamples(List.of(1L, 3L))
                .edit(new SyntheticMp4.Edit(2_000, 2_100))
                .build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID))
        .isEqualTo(new Listed(new TimeBase(1, 1_000), List.of(new KeyframeTime(-100, -100))));
  }

  @Test
  @DisplayName("Should require a packet scan when the edit list plays the media twice")
  void shouldRequirePacketScanWhenEditListPlaysMediaTwice() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(2, 1_000)
                .edit(new SyntheticMp4.Edit(1_000, 0))
                .edit(new SyntheticMp4.Edit(1_000, 0))
                .build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName("Should keep the decode times when the only composition offset entry is negative")
  void shouldKeepDecodeTimesWhenOnlyCompositionOffsetEntryIsNegative() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(2, 1_000)
                .compositionOffsets(2, -500)
                .build());

    // FFmpeg's mov demuxer leaves the last two ctts entries out of its decode-time shift.
    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID))
        .isEqualTo(
            new Listed(
                new TimeBase(1, 1_000),
                List.of(new KeyframeTime(-500, 0), new KeyframeTime(500, 1_000))));
  }

  @Test
  @DisplayName(
      "Should shift the decode times by the most negative offset when composition offsets are negative")
  void shouldShiftDecodeTimesByMostNegativeOffsetWhenCompositionOffsetsAreNegative()
      throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 1_000)
                .compositionRun(new SyntheticMp4.CompositionRun(1, 1_000))
                .compositionRun(new SyntheticMp4.CompositionRun(1, -500))
                .compositionRun(new SyntheticMp4.CompositionRun(1, 500))
                .compositionRun(new SyntheticMp4.CompositionRun(1, -1_000))
                .build());

    // The shift is 500 ticks: the -1,000 offset is in the last two entries, which FFmpeg skips.
    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID))
        .isEqualTo(
            new Listed(
                new TimeBase(1, 1_000),
                List.of(
                    new KeyframeTime(1_000, -500),
                    new KeyframeTime(500, 500),
                    new KeyframeTime(2_500, 1_500),
                    new KeyframeTime(2_000, 2_500))));
  }

  @Test
  @DisplayName(
      "Should require a packet scan when a composition offset entry counts 2^32 - 1 samples")
  void shouldRequirePacketScanWhenCompositionOffsetEntryCountsMaximumSamples() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(2, 1_000)
                .compositionRun(new SyntheticMp4.CompositionRun(-1, 500))
                .build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName("Should require a packet scan when the sync-sample table is empty")
  void shouldRequirePacketScanWhenSyncSampleTableIsEmpty() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(2, 1_000)
                .syncSamples(List.of())
                .build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName("Should require a packet scan when the track is not a video track")
  void shouldRequirePacketScanWhenTrackIsNotVideoTrack() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .handler("soun")
                .mediaTimescale(1_000)
                .sampleDurations(2, 1_000)
                .build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName("Should require a packet scan when the movie has no track with the stream's id")
  void shouldRequirePacketScanWhenMovieHasNoTrackWithStreamId() throws Exception {
    var source =
        write(SyntheticMp4.builder().mediaTimescale(1_000).sampleDurations(2, 1_000).build());

    assertThat(Mp4SyncSampleReader.read(source, 7)).isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName("Should report no container index when the movie is fragmented")
  void shouldReportNoContainerIndexWhenMovieIsFragmented() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(0, 1_000)
                .fragmented(true)
                .build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID))
        .isEqualTo(
            new Unusable(KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX));
  }

  @Test
  @DisplayName("Should require a packet scan when the file has no movie box")
  void shouldRequirePacketScanWhenFileHasNoMovieBox() throws Exception {
    var source =
        Files.write(
            temporaryDirectory.resolve("source.mp4"),
            SyntheticMp4.box("ftyp", SyntheticMp4.ascii("isom"), SyntheticMp4.int32(0)));

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  @Test
  @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  @DisplayName(
      "Should require a packet scan when the track declares more samples than the reader lists")
  void shouldRequirePacketScanWhenTrackDeclaresMoreSamplesThanReaderLists() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(-1, 1)
                .sampleSizes(-1, 100)
                .build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName(
      "Should require a packet scan when the sample sizes count fewer samples than the durations")
  void shouldRequirePacketScanWhenSampleSizesCountFewerSamplesThanDurations() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(3, 1_000)
                .sampleSizes(2, 100)
                .build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName(
      "Should require a packet scan when a table of sample sizes is shorter than its count")
  void shouldRequirePacketScanWhenTableOfSampleSizesIsShorterThanItsCount() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(3, 1_000)
                .sampleSizes(3, 0)
                .build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName("Should list every sample when the track stores compact sample sizes")
  void shouldListEverySampleWhenTrackStoresCompactSampleSizes() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(2, 100)
                .sampleSizes(2, 7)
                .compactSampleSizes(true)
                .build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID))
        .isEqualTo(
            new Listed(
                new TimeBase(1, 1_000),
                List.of(new KeyframeTime(0, 0), new KeyframeTime(100, 100))));
  }

  @Test
  @DisplayName(
      "Should require a packet scan when compact sample sizes are shorter than their count")
  void shouldRequirePacketScanWhenCompactSampleSizesAreShorterThanTheirCount() throws Exception {
    var bytes =
        SyntheticMp4.builder()
            .mediaTimescale(1_000)
            .sampleDurations(3, 100)
            .sampleSizes(2, 7)
            .compactSampleSizes(true)
            .build()
            .bytes();
    // Claim the third sample's 8-bit field, which the table does not hold.
    var count = indexOf(bytes, "stz2") + 12;
    bytes[count + 3] = 3;

    assertThat(Mp4SyncSampleReader.read(write(bytes), TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  private static int indexOf(byte[] bytes, String type) {
    var pattern = SyntheticMp4.ascii(type);
    for (var i = 0; i + pattern.length <= bytes.length; i++) {
      if (Arrays.equals(bytes, i, i + pattern.length, pattern, 0, pattern.length)) {
        return i;
      }
    }

    throw new AssertionError("no " + type + " box");
  }

  private Path write(byte[] bytes) throws Exception {
    return Files.write(temporaryDirectory.resolve("source.mp4"), bytes);
  }

  private Path write(SyntheticMp4 file) throws Exception {
    return Files.write(temporaryDirectory.resolve("source.mp4"), file.bytes());
  }
}
