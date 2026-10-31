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

  private static final TrackStream VIDEO = new TrackStream(0, SyntheticMp4.TRACK_ID);
  private static final TrackStream AUDIO = new TrackStream(1, SyntheticMp4.SECOND_TRACK_ID);

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

  private static SyntheticMp4.SyntheticMp4Builder audio() {
    return SyntheticMp4.builder().handler("soun").mediaTimescale(48_000);
  }

  private Path write(SyntheticMp4.SyntheticMp4Builder file) throws Exception {
    return Files.write(temporaryDirectory.resolve("source.mp4"), file.build().bytes());
  }
}
