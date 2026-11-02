package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.transcode.fixtures.Mp4Samples;
import com.streamarr.transcode.fixtures.Mp4Samples.Sample;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Segment Assembler Tests")
class SegmentAssemblerTest {

  private static final long AUDIO_TICKS_PER_SECOND = 48_000;
  private static final long VIDEO_TICKS_PER_SECOND = 24_000;

  @Test
  @DisplayName(
      "Should close the last media segment with only its interval's audio when audio past that"
          + " interval arrives before the output ends")
  void shouldCloseTheLastMediaSegmentWithOnlyItsIntervalsAudioWhenAudioPastThatIntervalArrives()
      throws IOException {
    var output =
        Mp4Stream.read(
            Mp4Stream.readerOf(
                IsoBoxes.concat(
                    IsoBoxes.ftyp(),
                    IsoBoxes.videoAndAudioMoov(),
                    keyframeWithAudio(0, 0, 24_000, 24_000),
                    keyframeWithAudio(24_000, 48_000, 24_000, 24_000),
                    audioOnly(96_000, 24_000, 24_000),
                    audioOnly(144_000, 24_000, 24_000))));
    var assembler = assemblerOfOneSecondSegments().build();

    var closed = assembleEvery(assembler, output);
    var last = closed.getLast();

    assertThat(audioStartsOf(output, last))
        .as("segment 1 holds 1 s to 2 s")
        .containsExactly(48_000L, 72_000L);
  }

  @Test
  @DisplayName(
      "Should close the last media segment within the segment cap when audio past its interval"
          + " outgrows the cap")
  void shouldCloseTheLastMediaSegmentWithinTheSegmentCapWhenAudioPastItsIntervalOutgrowsTheCap()
      throws IOException {
    var segmentCap = 4096;
    var tail = new ArrayList<byte[]>();
    for (var second = 1; second <= 8; second++) {
      tail.add(audioOnlyOfSampleSize(second * AUDIO_TICKS_PER_SECOND, segmentCap / 4));
    }

    var output =
        Mp4Stream.read(
            Mp4Stream.readerOf(
                IsoBoxes.concat(
                    IsoBoxes.ftyp(),
                    IsoBoxes.videoAndAudioMoov(),
                    keyframeWithAudio(0, 0, 24_000, 24_000),
                    IsoBoxes.concat(tail.toArray(byte[][]::new)))));
    var assembler = assemblerOfOneSecondSegments().maximumSegmentBytes(segmentCap).build();

    var closed = assembleEvery(assembler, output);

    assertThat(closed).hasSize(2);
    assertThat(audioStartsOf(output, closed.getLast())).containsExactly(0L, 24_000L);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("assemblersLackingARequiredArgument")
  @DisplayName("Should refuse to build an assembler when it lacks a required argument")
  void shouldRefuseToBuildAnAssemblerWhenItLacksARequiredArgument(
      String argument, SegmentAssembler.SegmentAssemblerBuilder builder) {
    assertThatNullPointerException().isThrownBy(builder::build).withMessageContaining(argument);
  }

  // The builder's setters refuse null themselves, so each assembler leaves its argument unset.
  static Stream<Arguments> assemblersLackingARequiredArgument() {
    return Stream.of(
        Arguments.of(
            "encodedFrameRate",
            assembler().videoDecision(VideoDecisionFixtures.encodeBuilder().build())),
        Arguments.of(
            "videoDecision", assembler().encodedFrameRate(Optional.<OutputFrameRate>empty())));
  }

  private static SegmentAssembler.SegmentAssemblerBuilder assemblerOfOneSecondSegments() {
    return SegmentAssembler.builder()
        .periodSeconds(1)
        .startSequenceNumber(0)
        .maximumSegmentBytes(Mp4Stream.SEGMENT_CAP)
        .encodedFrameRate(Optional.empty())
        .videoDecision(VideoDecisionFixtures.copyOf("avc1.64000A"));
  }

  // Every segment the units close, the initialization segment first, then those the end closes.
  private static List<ClosedSegment> assembleEvery(SegmentAssembler assembler, Mp4Stream output) {
    var closed = new ArrayList<>(assembler.accept(output.initializationSegment()).closedSegments());
    output
        .fragments()
        .forEach(fragment -> closed.addAll(assembler.accept(fragment).closedSegments()));
    closed.addAll(assembler.finish());
    return closed;
  }

  private static List<Long> audioStartsOf(Mp4Stream output, ClosedSegment segment) {
    var movie =
        Mp4Samples.read(IsoBoxes.concat(output.initializationSegment().bytes(), bytesOf(segment)));
    return movie.samplesOf(movie.audioTrack()).stream().map(Sample::presentationTime).toList();
  }

  private static byte[] bytesOf(ClosedSegment segment) {
    var bytes = new ByteArrayOutputStream();
    for (ByteBuffer part : segment.write(new ProductionTimer(MonotonicClock.system())).content()) {
      var copy = new byte[part.remaining()];
      part.get(copy);
      bytes.writeBytes(copy);
    }

    return bytes.toByteArray();
  }

  // A fragment of one second of video from a keyframe and the audio samples it carries.
  private static byte[] keyframeWithAudio(
      long videoTime, long audioTime, Integer... audioDurations) {
    return IsoBoxes.concat(
        IsoBoxes.moof(
            IsoBoxes.videoTraf()
                .baseMediaDecodeTime(videoTime)
                .sampleDurations(List.of(Math.toIntExact(VIDEO_TICKS_PER_SECOND)))
                .firstSampleFlags(IsoBoxes.SYNC_SAMPLE_FLAGS)
                .build(),
            IsoBoxes.audioTraf()
                .baseMediaDecodeTime(audioTime)
                .sampleDurations(List.of(audioDurations))
                .build()),
        IsoBoxes.mdat(1 + audioDurations.length));
  }

  private static byte[] audioOnly(long audioTime, Integer... audioDurations) {
    return IsoBoxes.concat(
        IsoBoxes.moof(
            IsoBoxes.audioTraf()
                .baseMediaDecodeTime(audioTime)
                .sampleDurations(List.of(audioDurations))
                .build()),
        IsoBoxes.mdat(audioDurations.length));
  }

  // A fragment of one second-long audio sample of this many bytes.
  private static byte[] audioOnlyOfSampleSize(long audioTime, int sampleSize) {
    return IsoBoxes.concat(
        IsoBoxes.moof(
            IsoBoxes.audioTraf()
                .baseMediaDecodeTime(audioTime)
                .sampleDurations(List.of(Math.toIntExact(AUDIO_TICKS_PER_SECOND)))
                .sampleSize(sampleSize)
                .build()),
        IsoBoxes.mdat(sampleSize));
  }

  private static SegmentAssembler.SegmentAssemblerBuilder assembler() {
    return SegmentAssembler.builder()
        .periodSeconds(6)
        .startSequenceNumber(0)
        .maximumSegmentBytes(1024);
  }
}
