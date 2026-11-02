package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.awaitility.Awaitility.await;

import com.streamarr.transcode.fixtures.Mp4Samples;
import com.streamarr.transcode.fixtures.Mp4Samples.Sample;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.time.Duration;
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
  private static final int LATE_AUDIO_SAMPLE_BYTES = 1024 * 1024;

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

  @Test
  @DisplayName(
      "Should neither charge nor keep a delivered segment's audio fragment when a later segment"
          + " awaiting its audio grouped that fragment")
  void
      shouldNeitherChargeNorKeepADeliveredSegmentsAudioFragmentWhenALaterSegmentAwaitingItsAudioGroupedThatFragment()
          throws IOException {
    var assembler = assemblerOfOneSecondSegments().build();

    var lateAudio = new WeakReference<>(deliverSegmentZeroWhileSegmentOneAwaitsAudio(assembler));

    assertThat(assembler.heldBytes())
        .as("what the assembler charges, without the delivered audio")
        .isLessThan(LATE_AUDIO_SAMPLE_BYTES);
    await()
        .alias("the assembler no longer references the delivered audio's fragment")
        .atMost(Duration.ofSeconds(5))
        .pollInterval(Duration.ofMillis(50))
        .until(
            () -> {
              System.gc();
              return lateAudio.refersTo(null);
            });
    // A collected assembler would free the fragment too.
    Reference.reachabilityFence(assembler);
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
    var closed = assembleEveryFragment(assembler, output);
    closed.addAll(assembler.finish());
    return closed;
  }

  // Segment 0's second audio sample arrives in an audio-only fragment after segment 1's keyframe,
  // so that segment 1 holds that fragment but takes nothing from it. Segment 1's first audio
  // sample then closes segment 0 while segment 1 awaits its audio. Returns that fragment, which
  // nothing outside the assembler references once this returns.
  private static Fragment deliverSegmentZeroWhileSegmentOneAwaitsAudio(SegmentAssembler assembler)
      throws IOException {
    var output =
        Mp4Stream.read(
            Mp4Stream.readerOf(
                IsoBoxes.concat(
                    IsoBoxes.ftyp(),
                    IsoBoxes.videoAndAudioMoov(),
                    keyframeWithAudio(0, 0, 24_000),
                    halfSecondKeyframe(24_000),
                    audioOnlyOfSampleSize(24_000, LATE_AUDIO_SAMPLE_BYTES),
                    halfSecondKeyframe(48_000),
                    audioOnly(48_000, 24_000))));

    var closed = assembleEveryFragment(assembler, output);

    assertThat(closed).as("the initialization segment and segment 0").hasSize(2);
    return output.fragments().get(2);
  }

  private static List<ClosedSegment> assembleEveryFragment(
      SegmentAssembler assembler, Mp4Stream output) {
    var closed = new ArrayList<>(assembler.accept(output.initializationSegment()).closedSegments());
    output
        .fragments()
        .forEach(fragment -> closed.addAll(assembler.accept(fragment).closedSegments()));
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

  private static byte[] halfSecondKeyframe(long videoTime) {
    return IsoBoxes.concat(
        IsoBoxes.moof(
            IsoBoxes.videoTraf()
                .baseMediaDecodeTime(videoTime)
                .sampleDurations(List.of(Math.toIntExact(VIDEO_TICKS_PER_SECOND / 2)))
                .firstSampleFlags(IsoBoxes.SYNC_SAMPLE_FLAGS)
                .build()),
        IsoBoxes.mdat(1));
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
