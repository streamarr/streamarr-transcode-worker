package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.Listed;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import com.streamarr.transcode.probe.KeyframeIndex.Unusable;
import com.streamarr.transcode.probe.SyntheticMp4.GroupRun;
import com.streamarr.transcode.probe.SyntheticMp4.SampleToGroup;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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

  @Test
  @DisplayName("Should list every sample when the movie uses version 1 headers")
  void shouldListEverySampleWhenMovieUsesVersionOneHeaders() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .version1Headers(true)
                .mediaTimescale(1_000)
                .sampleDurations(2, 100)
                .build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID))
        .isEqualTo(
            new Listed(
                new TimeBase(1, 1_000),
                List.of(new KeyframeTime(0, 0), new KeyframeTime(100, 100))));
  }

  @Test
  @DisplayName("Should require a packet scan when the media timescale is zero")
  void shouldRequirePacketScanWhenMediaTimescaleIsZero() throws Exception {
    var source = write(SyntheticMp4.builder().mediaTimescale(0).sampleDurations(2, 100).build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName("Should require a packet scan when a header box is shorter than its fields")
  void shouldRequirePacketScanWhenHeaderBoxIsShorterThanItsFields() throws Exception {
    var movieHeaderWithoutTimes = SyntheticMp4.box("mvhd", SyntheticMp4.int32(0));
    var source =
        write(
            SyntheticMp4.concat(
                SyntheticMp4.box("ftyp", SyntheticMp4.ascii("isom"), SyntheticMp4.int32(0)),
                SyntheticMp4.box("moov", movieHeaderWithoutTimes)));

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName("Should list every sample when the movie box size extends to the end of the file")
  void shouldListEverySampleWhenMovieBoxSizeExtendsToEndOfFile() throws Exception {
    var bytes =
        SyntheticMp4.builder().mediaTimescale(1_000).sampleDurations(2, 100).build().bytes();
    // A box size of zero means that the box runs to the end of the file.
    putInt32(bytes, indexOf(bytes, "moov") - Integer.BYTES, 0);

    assertThat(Mp4SyncSampleReader.read(write(bytes), TRACK_ID))
        .isEqualTo(
            new Listed(
                new TimeBase(1, 1_000),
                List.of(new KeyframeTime(0, 0), new KeyframeTime(100, 100))));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"'smaller than its header', 4", "'larger than the file', 2147483647"})
  @DisplayName("Should require a packet scan when the movie box size is impossible")
  void shouldRequirePacketScanWhenMovieBoxSizeIsImpossible(String description, int size)
      throws Exception {
    var bytes =
        SyntheticMp4.builder().mediaTimescale(1_000).sampleDurations(2, 100).build().bytes();
    putInt32(bytes, indexOf(bytes, "moov") - Integer.BYTES, size);

    assertThat(Mp4SyncSampleReader.read(write(bytes), TRACK_ID))
        .as(description)
        .isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName("Should require a packet scan when the movie box is larger than the reader accepts")
  void shouldRequirePacketScanWhenMovieBoxIsLargerThanReaderAccepts() throws Exception {
    // The reader accepts a movie box of up to 256 MiB; this one declares 257 MiB.
    var payloadSize = 257 << 20;
    var source =
        write(
            SyntheticMp4.concat(
                SyntheticMp4.box("ftyp", SyntheticMp4.ascii("isom"), SyntheticMp4.int32(0)),
                SyntheticMp4.int32(payloadSize + 8),
                SyntheticMp4.ascii("moov")));
    try (var file = new RandomAccessFile(source.toFile(), "rw")) {
      // The file system stores the extension as a hole, so the test writes no payload bytes.
      file.setLength(file.length() + payloadSize);
    }

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName(
      "Should require a packet scan when the sync-sample table counts more entries than it holds")
  void shouldRequirePacketScanWhenSyncSampleTableCountsMoreEntriesThanItHolds() throws Exception {
    var bytes =
        SyntheticMp4.builder()
            .mediaTimescale(1_000)
            .sampleDurations(2, 100)
            .syncSamples(List.of(1L))
            .build()
            .bytes();
    // The entry count follows the type, the version and the flags.
    putInt32(bytes, indexOf(bytes, "stss") + 8, 5);

    assertThat(Mp4SyncSampleReader.read(write(bytes), TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName("Should require a packet scan when compact sample sizes declare zero-bit fields")
  void shouldRequirePacketScanWhenCompactSampleSizesDeclareZeroBitFields() throws Exception {
    var bytes =
        SyntheticMp4.builder()
            .mediaTimescale(1_000)
            .sampleDurations(2, 100)
            .sampleSizes(2, 7)
            .compactSampleSizes(true)
            .build()
            .bytes();
    // The field size follows the type, the version, the flags and three reserved bytes.
    bytes[indexOf(bytes, "stz2") + 11] = 0;

    assertThat(Mp4SyncSampleReader.read(write(bytes), TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName(
      "Should leave the decode times unshifted when the negative composition offset counts no samples")
  void shouldLeaveDecodeTimesUnshiftedWhenNegativeCompositionOffsetCountsNoSamples()
      throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 1_000)
                .compositionRun(new SyntheticMp4.CompositionRun(0, -700))
                .compositionRun(new SyntheticMp4.CompositionRun(2, 500))
                .compositionRun(new SyntheticMp4.CompositionRun(1, 0))
                .compositionRun(new SyntheticMp4.CompositionRun(1, 0))
                .build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID))
        .isEqualTo(
            new Listed(
                new TimeBase(1, 1_000),
                List.of(
                    new KeyframeTime(500, 0),
                    new KeyframeTime(1_500, 1_000),
                    new KeyframeTime(2_000, 2_000),
                    new KeyframeTime(3_000, 3_000))));
  }

  @Test
  @DisplayName("Should require a packet scan when the edit list holds only empty edits")
  void shouldRequirePacketScanWhenEditListHoldsOnlyEmptyEdits() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(2, 1_000)
                .edit(new SyntheticMp4.Edit(1_000, -1))
                .build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  @ParameterizedTest(name = "rate {0}, media time {1}")
  @CsvSource({"131072, 0", "65536, -2"})
  @DisplayName(
      "Should require a packet scan when the media edit changes the rate or starts before the media")
  void shouldRequirePacketScanWhenMediaEditChangesRateOrStartsBeforeMedia(int rate, long mediaTime)
      throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(2, 1_000)
                .edit(new SyntheticMp4.Edit(2_000, mediaTime, rate))
                .build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  @Test
  @DisplayName(
      "Should require a packet scan when the empty edit is too long to convert to the media timescale")
  void shouldRequirePacketScanWhenEmptyEditIsTooLongToConvertToMediaTimescale() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(2, 1_000)
                .editListVersion(1)
                .edit(new SyntheticMp4.Edit(Long.MAX_VALUE / 2, -1))
                .edit(new SyntheticMp4.Edit(2_000, 0))
                .build());

    assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(INCOMPLETE);
  }

  // FFmpeg's mov demuxer reads the sbgp boxes of grouping type 'rap ' (ISO/IEC 14496-12, 10.4) and
  // gives the keyframe flag to every sample whose entry has a group description index other than
  // 0, beside the samples that the stss lists. It reads no sgpd for that grouping type.
  @Nested
  @DisplayName("Random-Access Sample Group Tests")
  class RandomAccessSampleGroupTests {

    @Test
    @DisplayName(
        "Should list the random-access samples as keyframes when the sync-sample table lists only"
            + " the first sample")
    void shouldListRandomAccessSamplesAsKeyframesWhenSyncSampleTableListsOnlyFirstSample()
        throws Exception {
      // The group covers 8 of the 9 samples; the 9th sample belongs to no group.
      var source =
          write(
              nineSamplesSyncingFirst()
                  .sampleToGroup(
                      randomAccessGroup()
                          .run(new GroupRun(3, 0))
                          .run(new GroupRun(1, 1))
                          .run(new GroupRun(2, 0))
                          .run(new GroupRun(1, 1))
                          .run(new GroupRun(1, 0))
                          .build())
                  .build());

      assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(keyframesAt(0, 300, 600));
    }

    @Test
    @DisplayName(
        "Should skip the grouping type parameter when the sample-to-group box is version 1")
    void shouldSkipGroupingTypeParameterWhenSampleToGroupBoxIsVersionOne() throws Exception {
      var source =
          write(
              nineSamplesSyncingFirst()
                  .sampleToGroup(
                      randomAccessGroup()
                          .version(1)
                          .run(new GroupRun(3, 0))
                          .run(new GroupRun(1, 1))
                          .build())
                  .build());

      assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(keyframesAt(0, 300));
    }

    // ISO/IEC 14496-12 defines versions 0 and 1. FFmpeg reads a box of any other version as
    // version 0.
    @Test
    @DisplayName("Should read no grouping type parameter when the sample-to-group box is version 2")
    void shouldReadNoGroupingTypeParameterWhenSampleToGroupBoxIsVersionTwo() throws Exception {
      var source =
          write(
              nineSamplesSyncingFirst()
                  .sampleToGroup(
                      randomAccessGroup()
                          .version(2)
                          .run(new GroupRun(3, 0))
                          .run(new GroupRun(1, 1))
                          .build())
                  .build());

      assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(keyframesAt(0, 300));
    }

    @Test
    @DisplayName(
        "Should list only the sync samples when the sample-to-group box has another grouping type")
    void shouldListOnlySyncSamplesWhenSampleToGroupBoxHasAnotherGroupingType() throws Exception {
      var source =
          write(
              nineSamplesSyncingFirst()
                  .sampleToGroup(
                      SampleToGroup.builder().groupingType("roll").run(new GroupRun(9, 1)).build())
                  .build());

      assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(keyframesAt(0));
    }

    // FFmpeg replaces the table of an earlier 'rap ' box with each later one, except one that has
    // no entries.
    @Test
    @DisplayName(
        "Should read the last random-access box that has entries when the track holds several")
    void shouldReadLastRandomAccessBoxThatHasEntriesWhenTrackHoldsSeveral() throws Exception {
      var source =
          write(
              nineSamplesSyncingFirst()
                  .sampleToGroup(
                      randomAccessGroup()
                          .run(new GroupRun(3, 0))
                          .run(new GroupRun(1, 1))
                          .run(new GroupRun(2, 0))
                          .run(new GroupRun(1, 1))
                          .build())
                  .sampleToGroup(
                      randomAccessGroup().run(new GroupRun(6, 0)).run(new GroupRun(1, 1)).build())
                  .sampleToGroup(randomAccessGroup().build())
                  .build());

      assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(keyframesAt(0, 600));
    }

    // FFmpeg moves to the next entry only after it has counted as many samples as the entry
    // declares, so an entry that declares no samples covers every later sample.
    @Test
    @DisplayName(
        "Should list every later sample as a keyframe when a random-access entry counts no samples")
    void shouldListEveryLaterSampleAsKeyframeWhenRandomAccessEntryCountsNoSamples()
        throws Exception {
      var source =
          write(
              nineSamplesSyncingFirst()
                  .sampleToGroup(
                      randomAccessGroup()
                          .run(new GroupRun(6, 0))
                          .run(new GroupRun(0, 1))
                          .run(new GroupRun(1, 0))
                          .build())
                  .build());

      assertThat(Mp4SyncSampleReader.read(source, TRACK_ID))
          .isEqualTo(keyframesAt(0, 600, 700, 800));
    }

    // The index is an unsigned 32-bit number. This one is above 2^31, and also above 65,536, where
    // an index inside a movie fragment names a description of that fragment.
    @Test
    @DisplayName(
        "Should list a random-access sample as a keyframe when the track has no group description")
    void shouldListRandomAccessSampleAsKeyframeWhenTrackHasNoGroupDescription() throws Exception {
      var source =
          write(
              nineSamplesSyncingFirst()
                  .randomAccessDescription(false)
                  .sampleToGroup(
                      randomAccessGroup()
                          .run(new GroupRun(3, 0))
                          .run(new GroupRun(1, 0x8001_0001))
                          .build())
                  .build());

      assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(keyframesAt(0, 300));
    }

    @Test
    @DisplayName(
        "Should require a packet scan when the sample-to-group box counts more entries than it"
            + " holds")
    void shouldRequirePacketScanWhenSampleToGroupBoxCountsMoreEntriesThanItHolds()
        throws Exception {
      var bytes =
          nineSamplesSyncingFirst()
              .sampleToGroup(randomAccessGroup().run(new GroupRun(3, 0)).build())
              .build()
              .bytes();
      // The entry count follows the type, the version, the flags and the grouping type.
      putInt32(bytes, indexOf(bytes, "sbgp") + 12, 2);

      assertThat(Mp4SyncSampleReader.read(write(bytes), TRACK_ID)).isEqualTo(INCOMPLETE);
    }

    @Test
    @DisplayName(
        "Should list every sample when the track has a random-access group and no sync-sample"
            + " table")
    void shouldListEverySampleWhenTrackHasRandomAccessGroupAndNoSyncSampleTable() throws Exception {
      var source =
          write(
              SyntheticMp4.builder()
                  .mediaTimescale(1_000)
                  .sampleDurations(3, 100)
                  .sampleToGroup(
                      randomAccessGroup().run(new GroupRun(1, 0)).run(new GroupRun(2, 1)).build())
                  .build());

      assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(keyframesAt(0, 100, 200));
    }

    // FFmpeg parses the packets of a video track whose stss is empty, and the parser decides which
    // of them are keyframes.
    @Test
    @DisplayName(
        "Should require a packet scan when the sync-sample table is empty beside a random-access"
            + " group")
    void shouldRequirePacketScanWhenSyncSampleTableIsEmptyBesideRandomAccessGroup()
        throws Exception {
      var source =
          write(
              nineSamplesSyncingFirst()
                  .syncSamples(List.of())
                  .sampleToGroup(randomAccessGroup().run(new GroupRun(9, 1)).build())
                  .build());

      assertThat(Mp4SyncSampleReader.read(source, TRACK_ID)).isEqualTo(INCOMPLETE);
    }

    /**
     * Nine samples of 100 ms each, of which the stss lists only the first, and one 'rap ' group
     * description.
     */
    private static SyntheticMp4.SyntheticMp4Builder nineSamplesSyncingFirst() {
      return SyntheticMp4.builder()
          .mediaTimescale(1_000)
          .sampleDurations(9, 100)
          .syncSamples(List.of(1L))
          .randomAccessDescription(true);
    }

    private static SampleToGroup.SampleToGroupBuilder randomAccessGroup() {
      return SampleToGroup.builder().groupingType(SyntheticMp4.RANDOM_ACCESS);
    }

    /** Keyframes in milliseconds, each of which presents at its decode time. */
    private static Listed keyframesAt(long... times) {
      return new Listed(
          new TimeBase(1, 1_000),
          Arrays.stream(times).mapToObj(time -> new KeyframeTime(time, time)).toList());
    }
  }

  private static void putInt32(byte[] bytes, int offset, int value) {
    ByteBuffer.wrap(bytes).putInt(offset, value);
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
