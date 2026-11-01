package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.probe.KeyframeIndex.Listed;
import com.streamarr.transcode.probe.Mp4StreamMeasurer.TrackMeasurement;
import com.streamarr.transcode.probe.Mp4StreamMeasurer.TrackStream;
import com.streamarr.transcode.probe.SyntheticMp4.Edit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("MP4 Stream Measurer Tests")
class Mp4StreamMeasurerTest {

  private static final TrackStream VIDEO = new TrackStream(0, SyntheticMp4.TRACK_ID, true);
  private static final TrackStream AUDIO = new TrackStream(1, SyntheticMp4.SECOND_TRACK_ID, true);
  private static final TrackStream OTHER_VIDEO =
      new TrackStream(1, SyntheticMp4.SECOND_TRACK_ID, false);

  @TempDir Path temporaryDirectory;

  @Test
  @DisplayName(
      "Should count the samples before the first keyframe in the first interval when the track has"
          + " no edit list")
  void shouldCountSamplesBeforeFirstKeyframeInFirstIntervalWhenTrackHasNoEditList()
      throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(6, 100)
                .sampleSizes(6, 10)
                .syncSamples(List.of(3L, 5L)));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO));

    assertThat(index.measured())
        .singleElement()
        .satisfies(
            video -> {
              assertThat(video.measuredBytes()).isEqualTo(60);
              assertThat(video.packetCount()).isEqualTo(6);
              assertThat(video.timescale()).isEqualTo(1_000);
              assertThat(video.measuredDuration()).isEqualTo(600);
              assertThat(video.intervalBytes()).containsExactly(40, 20);
              assertThat(video.intervalPacketCounts()).containsExactly(4, 2);
            });
  }

  @Test
  @DisplayName(
      "Should place each audio sample in the interval that holds its presentation time when the"
          + " tracks have different timescales")
  void shouldPlaceEachAudioSampleInIntervalHoldingItsPresentationTimeWhenTimescalesDiffer()
      throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 500)
                .sampleSizes(4, 100)
                .secondTrack(
                    SyntheticMp4.builder()
                        .handler("soun")
                        .mediaTimescale(48_000)
                        .sampleDurations(9, 12_000)
                        .sampleSizes(9, 7)
                        .build()));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO, AUDIO));

    assertThat(index.measured()).extracting(TrackMeasurement::streamIndex).containsExactly(0, 1);
    var audio = index.measured().get(1);
    assertThat(audio.measuredBytes()).isEqualTo(63);
    assertThat(audio.packetCount()).isEqualTo(9);
    assertThat(audio.timescale()).isEqualTo(48_000);
    assertThat(audio.measuredDuration()).isEqualTo(108_000);
    assertThat(audio.intervalBytes()).containsExactly(14, 14, 14, 21);
    assertThat(audio.intervalPacketCounts()).containsExactly(2, 2, 2, 3);
  }

  @Test
  @DisplayName(
      "Should keep the keyframes but measure nothing when no keyframe presents at or before the"
          + " edit's media time")
  void shouldKeepKeyframesButMeasureNothingWhenNoKeyframePresentsAtOrBeforeEditMediaTime()
      throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(6, 100)
                .syncSamples(List.of(3L))
                .edit(new Edit(550, 50)));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO));

    assertThat(index.keyframes()).isInstanceOf(Listed.class);
    assertThat(index.measured()).isEmpty();
  }

  @Test
  @DisplayName("Should measure nothing when the edit list ends before the last video sample")
  void shouldMeasureNothingWhenEditListEndsBeforeLastVideoSample() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(6, 100)
                .edit(new Edit(300, 0)));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO));

    assertThat(index.keyframes()).isInstanceOf(Listed.class);
    assertThat(index.measured()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should measure the last audio sample in full when only that sample reaches the edit's end")
  void shouldMeasureLastAudioSampleInFullWhenOnlyThatSampleReachesEditEnd() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 500)
                .secondTrack(audio().sampleDurations(4, 24_000).edit(new Edit(1_900, 0)).build()));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO, AUDIO));

    assertThat(index.measured().get(1).measuredDuration()).isEqualTo(96_000);
  }

  @Test
  @DisplayName(
      "Should measure nothing when an audio sample before the last reaches the edit list's end")
  void shouldMeasureNothingWhenAudioSampleBeforeLastReachesEditEnd() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 500)
                .secondTrack(audio().sampleDurations(4, 24_000).edit(new Edit(1_400, 0)).build()));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO, AUDIO));

    assertThat(index.measured()).isEmpty();
  }

  @Test
  @DisplayName("Should measure nothing when the edit list trims more than one second of audio")
  void shouldMeasureNothingWhenEditListTrimsMoreThanOneSecondOfAudio() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 500)
                .secondTrack(
                    audio().sampleDurations(8, 24_000).edit(new Edit(4_000, 48_001)).build()));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO, AUDIO));

    assertThat(index.measured()).isEmpty();
  }

  @Test
  @DisplayName("Should measure nothing when the stream's track is not a video or audio track")
  void shouldMeasureNothingWhenStreamsTrackIsNotVideoOrAudioTrack() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 500)
                .secondTrack(audio().handler("text").sampleDurations(4, 24_000).build()));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO, AUDIO));

    assertThat(index.measured()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should measure nothing when one keyframe interval holds more bytes than an unsigned 32-bit"
          + " count")
  void shouldMeasureNothingWhenKeyframeIntervalHoldsMoreBytesThanUnsigned32BitCount()
      throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(2, 500)
                .sampleSizes(2, -1)
                .syncSamples(List.of(1L)));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO));

    assertThat(index.keyframes()).isInstanceOf(Listed.class);
    assertThat(index.measured()).isEmpty();
  }

  @Test
  @DisplayName("Should measure an interval of exactly the largest unsigned 32-bit count")
  void shouldMeasureIntervalOfExactlyLargestUnsigned32BitCount() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(1, 500)
                .sampleSizes(1, -1));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO));

    assertThat(index.measured().getFirst().measuredBytes()).isEqualTo(0xFFFF_FFFFL);
    assertThat(index.measured().getFirst().intervalBytes()).containsExactly(-1);
  }

  @Test
  @DisplayName(
      "Should place each sample of another video track in the interval that holds its decode time")
  void shouldPlaceEachSampleOfAnotherVideoTrackInIntervalHoldingItsDecodeTime() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 500)
                .secondTrack(
                    SyntheticMp4.builder()
                        .mediaTimescale(90_000)
                        .sampleDurations(8, 22_500)
                        .sampleSizes(8, 5)
                        .build()));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO, OTHER_VIDEO));

    assertThat(index.measured().get(1).intervalBytes()).containsExactly(10, 10, 10, 10);
  }

  @Test
  @DisplayName(
      "Should leave out another video track but measure the required streams when that track"
          + " cannot be measured")
  void shouldLeaveOutAnotherVideoTrackButMeasureRequiredStreamsWhenThatTrackCannotBeMeasured()
      throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 500)
                .secondTrack(
                    SyntheticMp4.builder()
                        .mediaTimescale(90_000)
                        .sampleDurations(8, 22_500)
                        .edit(new Edit(1_000, 0))
                        .edit(new Edit(1_000, 0))
                        .build()));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO, OTHER_VIDEO));

    assertThat(index.measured()).extracting(TrackMeasurement::streamIndex).containsExactly(0);
  }

  @Test
  @DisplayName(
      "Should measure nothing when an audio track gives every sample a duration of one tick")
  void shouldMeasureNothingWhenAudioTrackGivesEverySampleDurationOfOneTick() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 500)
                .secondTrack(audio().sampleDurations(96_000, 1).sampleSizes(96_000, 4).build()));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO, AUDIO));

    assertThat(index.keyframes()).isInstanceOf(Listed.class);
    assertThat(index.measured()).isEmpty();
  }

  @Test
  @DisplayName("Should measure nothing when an audio track has more samples than the reader lists")
  void shouldMeasureNothingWhenAudioTrackHasMoreSamplesThanReaderLists() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 500)
                .secondTrack(
                    audio().sampleDurations(4_194_305, 2).sampleSizes(4_194_305, 4).build()));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO, AUDIO));

    assertThat(index.measured()).isEmpty();
  }

  @Test
  @DisplayName("Should sum the compact sample sizes when the track lists them in stz2")
  void shouldSumCompactSampleSizesWhenTrackListsThemInStz2() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 500)
                .sampleSizes(4, 9)
                .compactSampleSizes(true));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO));

    assertThat(index.measured().getFirst().measuredBytes()).isEqualTo(36);
  }

  @Test
  @DisplayName(
      "Should measure nothing when an audio edit's empty duration moves a sample past the largest"
          + " time")
  void shouldMeasureNothingWhenAudioEditsEmptyDurationMovesSamplePastLargestTime()
      throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .movieTimescale(1)
                .mediaTimescale(1)
                .sampleDurations(4, 1)
                .secondTrack(
                    SyntheticMp4.builder()
                        .handler("soun")
                        .mediaTimescale(1)
                        .sampleDurations(4, 1_024)
                        .editListVersion(1)
                        .edit(new Edit(Long.MAX_VALUE - 2_048, -1))
                        .edit(new Edit(4_096, 0))
                        .build()));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO, AUDIO));

    assertThat(index.keyframes()).isInstanceOf(Listed.class);
    assertThat(index.measured()).isEmpty();
  }

  @Test
  @DisplayName("Should measure nothing when an audio track's edit has no duration")
  void shouldMeasureNothingWhenAudioTracksEditHasNoDuration() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 500)
                .secondTrack(audio().sampleDurations(4, 24_000).edit(new Edit(0, 0)).build()));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO, AUDIO));

    assertThat(index.measured()).isEmpty();
  }

  @Test
  @DisplayName("Should measure nothing when an audio track has composition offsets")
  void shouldMeasureNothingWhenAudioTrackHasCompositionOffsets() throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 500)
                .secondTrack(
                    audio().sampleDurations(4, 24_000).compositionOffsets(4, 1_024).build()));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO, AUDIO));

    assertThat(index.measured()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should leave out another video track when it keeps a sample that presents before its edit's"
          + " start")
  void shouldLeaveOutAnotherVideoTrackWhenItKeepsSampleThatPresentsBeforeItsEditStart()
      throws Exception {
    var source =
        write(
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(4, 500)
                .secondTrack(
                    SyntheticMp4.builder()
                        .mediaTimescale(90_000)
                        .sampleDurations(8, 22_500)
                        .syncSamples(List.of(1L, 5L))
                        .edit(new Edit(2_000, 30_000))
                        .build()));

    var index = Mp4SyncSampleReader.read(source, VIDEO, List.of(VIDEO, OTHER_VIDEO));

    assertThat(index.measured()).extracting(TrackMeasurement::streamIndex).containsExactly(0);
  }

  private static SyntheticMp4.SyntheticMp4Builder audio() {
    return SyntheticMp4.builder().handler("soun").mediaTimescale(48_000);
  }

  private Path write(SyntheticMp4.SyntheticMp4Builder file) throws Exception {
    return Files.write(temporaryDirectory.resolve("source.mp4"), file.build().bytes());
  }
}
