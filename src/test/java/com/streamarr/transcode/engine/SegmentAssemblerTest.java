package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.fixtures.Mp4Samples;
import com.streamarr.transcode.fixtures.Mp4Samples.Sample;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
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

  // The reader admitted the initialization segment within the cap before the rewrite added the
  // planned static HDR metadata to its record.
  @Test
  @DisplayName(
      "Should refuse the initialization segment as exceeding the segment cap when the rewrite makes"
          + " it longer than the cap")
  void shouldRefuseTheInitializationSegmentWhenTheRewriteMakesItLongerThanTheCap() {
    var sampleEntry =
        IsoBoxes.box(
            "hvc1",
            new byte[78],
            IsoBoxes.box(
                "hvcC",
                HexFormat.of()
                    .parseHex(
                        "0101600000009000000000005AF000FCFDF8F800000F03"
                            + "A0000100024001A1000100024201A2000100024401")));
    var written = HevcCopies.initializationSegment(sampleEntry);
    var copy =
        VideoDecisionFixtures.copyBuilder()
            .codecString("hvc1.1.6.L90.90")
            .plannedOutput(
                Optional.of(
                    PlannedCopyOutputs.pqOutputBuilder()
                        .contentLightLevel(Optional.of(PlannedCopyOutputs.CONTENT_LIGHT_LEVEL))
                        .build()))
            .build();
    var assembler =
        SegmentAssembler.builder()
            .intervals(SegmentIntervals.onPlainGrid(1))
            .startSequenceNumber(0)
            .maximumSegmentBytes(written.length)
            .encodedFrameRate(Optional.empty())
            .videoDecision(copy)
            .paddedAudioEnd(Optional.empty())
            .build();

    assertThatThrownBy(() -> assembler.accept(new InitializationSegment(written)))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.EXCEEDS_SEGMENT_CAP));
  }

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

  // Segment 1 starts at 1 s, where the encode's first frame at 24 fps presents; its keyframe
  // presents at 1.5 s.
  @Test
  @DisplayName(
      "Should fail with a first segment that opened late when an encode from a keyframe seek opens"
          + " its first segment after the first frame at or after its start")
  void shouldFailWithAFirstSegmentThatOpenedLateWhenAnEncodeFromASeekOpensItLate()
      throws IOException {
    var output = lateFirstSegment();
    var assembler = encodeFromSegment1().readsFromAKeyframeSeek(true).build();

    assertThatThrownBy(() -> assembleEveryFragment(assembler, output))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception ->
                assertThat(exception.getReason()).isEqualTo(Reason.FIRST_SEGMENT_OPENED_LATE));
  }

  // An attempt that reads from the source's start decodes every picture, so a first segment that
  // opens late holds no picture less than an attempt from segment 0.
  @Test
  @DisplayName(
      "Should deliver a first segment that opens after its start when the encode reads from the"
          + " source's start")
  void shouldDeliverAFirstSegmentThatOpensAfterItsStartWhenTheEncodeReadsFromTheSourcesStart()
      throws IOException {
    var output = lateFirstSegment();
    var assembler = encodeFromSegment1().build();

    var closed = assembleEvery(assembler, output);

    var movie =
        Mp4Samples.read(
            IsoBoxes.concat(output.initializationSegment().bytes(), bytesOf(closed.getLast())));
    assertThat(movie.samplesOf(movie.videoTrack()).getFirst().presentationTime())
        .as("segment 1 opens at 1.5 s")
        .isEqualTo(VIDEO_TICKS_PER_SECOND * 3 / 2);
  }

  private static Mp4Stream lateFirstSegment() throws IOException {
    return Mp4Stream.read(
        Mp4Stream.readerOf(
            IsoBoxes.concat(
                IsoBoxes.ftyp(),
                IsoBoxes.videoAndAudioMoov(),
                keyframeWithAudio(0, 0, 24_000),
                keyframeWithAudio(VIDEO_TICKS_PER_SECOND * 3 / 2, 24_000, 24_000))));
  }

  private static SegmentAssembler.SegmentAssemblerBuilder encodeFromSegment1() {
    return assemblerOfOneSecondSegments()
        .startSequenceNumber(1)
        .encodedFrameRate(Optional.of(new OutputFrameRate(24, 1)));
  }

  @Test
  @DisplayName(
      "Should give a merged segment the audio of every slot it spans when the segment list merges"
          + " a slot without a keyframe")
  void shouldGiveAMergedSegmentTheAudioOfEverySlotItSpansWhenTheSegmentListMergesASlot()
      throws IOException {
    var output =
        Mp4Stream.read(
            Mp4Stream.readerOf(
                IsoBoxes.concat(
                    IsoBoxes.ftyp(),
                    IsoBoxes.videoAndAudioMoov(),
                    keyframeWithAudio(0, 0, 24_000, 24_000),
                    IsoBoxes.concat(
                        IsoBoxes.moof(
                            IsoBoxes.videoTraf()
                                .baseMediaDecodeTime(24_000L)
                                .sampleDurations(List.of(Math.toIntExact(VIDEO_TICKS_PER_SECOND)))
                                .firstSampleFlags(IsoBoxes.NON_SYNC_SAMPLE_FLAGS)
                                .build(),
                            IsoBoxes.audioTraf()
                                .baseMediaDecodeTime(48_000L)
                                .sampleDurations(List.of(24_000, 24_000))
                                .build()),
                        IsoBoxes.mdat(3)),
                    keyframeWithAudio(48_000, 96_000, 24_000, 24_000))));
    // 1 s slots: slot 1 holds no keyframe, so segment 0 runs from 0 s to 2 s.
    var assembler =
        assemblerOfOneSecondSegments()
            .intervals(SegmentIntervals.onSegmentList(1, List.of(0L, 2L)))
            .build();

    var closed = assembleEvery(assembler, output);

    assertThat(closed).as("the initialization segment and segments 0 and 1").hasSize(3);
    assertThat(audioStartsOf(output, closed.get(1)))
        .as("segment 0 holds 0 s to 2 s")
        .containsExactly(0L, 24_000L, 48_000L, 72_000L);
    assertThat(audioStartsOf(output, closed.get(2)))
        .as("segment 1 holds 2 s onwards")
        .containsExactly(96_000L, 120_000L);
  }

  @Test
  @DisplayName(
      "Should close the last listed segment with only the audio up to the advertised end, after"
          + " the held picture opens the listed segments past the video's end, when audio outlasts"
          + " the video and that end")
  void shouldCloseTheLastListedSegmentWithOnlyTheAudioUpToTheAdvertisedEndWhenAudioOutlastsIt()
      throws IOException {
    // The source's video ends at 2 s; the held picture's keyframes open segments 1 and 2 at 2 s
    // and 3 s, and the copied audio runs on to 5 s.
    var output =
        Mp4Stream.read(
            Mp4Stream.readerOf(
                IsoBoxes.concat(
                    IsoBoxes.ftyp(),
                    IsoBoxes.videoAndAudioMoov(),
                    keyframeWithAudio(0, 0, 24_000, 24_000),
                    IsoBoxes.concat(
                        IsoBoxes.moof(
                            IsoBoxes.videoTraf()
                                .baseMediaDecodeTime(24_000L)
                                .sampleDurations(List.of(Math.toIntExact(VIDEO_TICKS_PER_SECOND)))
                                .firstSampleFlags(IsoBoxes.NON_SYNC_SAMPLE_FLAGS)
                                .build(),
                            IsoBoxes.audioTraf()
                                .baseMediaDecodeTime(48_000L)
                                .sampleDurations(List.of(24_000, 24_000))
                                .build()),
                        IsoBoxes.mdat(3)),
                    keyframeWithAudio(48_000, 96_000, 24_000, 24_000),
                    keyframeWithAudio(72_000, 144_000, 24_000, 24_000),
                    audioOnly(192_000, 24_000, 24_000))));
    // 1 s slots: slot 1 holds no keyframe, so segment 0 runs from 0 s to 2 s, and the last listed
    // segment, 2, runs from 3 s to the advertised end at 4 s.
    var assembler =
        assemblerOfOneSecondSegments()
            .intervals(
                SegmentIntervals.onSegmentList(1, List.of(0L, 2L, 3L))
                    .withLastListedSegmentEndingAt(Duration.ofSeconds(4)))
            .build();

    var closed = assembleEvery(assembler, output);

    assertThat(closed).as("the initialization segment and segments 0, 1 and 2").hasSize(4);
    assertThat(audioStartsOf(output, closed.get(2)))
        .as("segment 1 holds 2 s to 3 s")
        .containsExactly(96_000L, 120_000L);
    assertThat(audioStartsOf(output, closed.get(3)))
        .as("segment 2 holds 3 s up to the advertised end")
        .containsExactly(144_000L, 168_000L);
  }

  @Test
  @DisplayName(
      "Should keep the video past the advertised end in the last listed segment when a copied"
          + " video runs past that end")
  void shouldKeepTheVideoPastTheAdvertisedEndInTheLastListedSegmentWhenACopiedVideoRunsPastIt()
      throws IOException {
    var output =
        Mp4Stream.read(
            Mp4Stream.readerOf(
                IsoBoxes.concat(
                    IsoBoxes.ftyp(),
                    IsoBoxes.videoAndAudioMoov(),
                    keyframeWithAudio(0, 0, 24_000, 24_000),
                    keyframeWithAudio(24_000, 48_000, 24_000, 24_000),
                    keyframeWithAudio(48_000, 96_000, 24_000, 24_000))));
    // 1 s slots, one listed segment that ends at the advertised end at 2 s; the source's keyframe
    // at 2 s starts no segment that the playlist advertises.
    var assembler =
        assemblerOfOneSecondSegments()
            .intervals(
                SegmentIntervals.onSegmentList(1, List.of(0L))
                    .withLastListedSegmentEndingAt(Duration.ofSeconds(2)))
            .build();

    var closed = assembleEvery(assembler, output);

    assertThat(closed).as("the initialization segment and segment 0").hasSize(2);
    var segment =
        Mp4Samples.read(
            IsoBoxes.concat(output.initializationSegment().bytes(), bytesOf(closed.getLast())));
    assertThat(segment.samplesOf(segment.videoTrack()))
        .extracting(Sample::presentationTime)
        .as("segment 0 holds every video sample")
        .containsExactly(0L, 24_000L, 48_000L);
    assertThat(audioStartsOf(output, closed.getLast()))
        .as("segment 0's audio stops at the advertised end")
        .containsExactly(0L, 24_000L, 48_000L, 72_000L);
  }

  @Test
  @DisplayName(
      "Should drop the padded audio that starts at or after the advertised end, without counting"
          + " it late, when an attempt pads its audio with silence")
  void shouldDropThePaddedAudioThatStartsAtOrAfterTheAdvertisedEndWhenAnAttemptPadsItsAudio()
      throws IOException {
    var output =
        Mp4Stream.read(
            Mp4Stream.readerOf(
                IsoBoxes.concat(
                    IsoBoxes.ftyp(),
                    IsoBoxes.videoAndAudioMoov(),
                    keyframeWithAudio(0, 0, 24_000, 24_000),
                    keyframeWithAudio(24_000, 48_000, 12_000, 12_000, 12_000, 12_000))));
    var assembler =
        assemblerOfOneSecondSegments()
            .paddedAudioEnd(Optional.of(Duration.ofMillis(1_500)))
            .build();

    var closed = assembleEvery(assembler, output);

    assertThat(audioStartsOf(output, closed.getLast()))
        .as("segment 1 up to the advertised end at 1.5 s; the last sample keeps its full length")
        .containsExactly(48_000L, 60_000L);
    assertThat(assembler.droppedLateAudioSampleCount()).isZero();
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
            "videoDecision", assembler().encodedFrameRate(Optional.<OutputFrameRate>empty())),
        Arguments.of(
            "paddedAudioEnd",
            assembler()
                .encodedFrameRate(Optional.<OutputFrameRate>empty())
                .videoDecision(VideoDecisionFixtures.encodeBuilder().build())));
  }

  private static SegmentAssembler.SegmentAssemblerBuilder assemblerOfOneSecondSegments() {
    return SegmentAssembler.builder()
        .intervals(SegmentIntervals.onPlainGrid(1))
        .startSequenceNumber(0)
        .maximumSegmentBytes(Mp4Stream.SEGMENT_CAP)
        .encodedFrameRate(Optional.empty())
        .videoDecision(VideoDecisionFixtures.copyOf("avc1.64000A"))
        .paddedAudioEnd(Optional.empty());
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
        .intervals(SegmentIntervals.onPlainGrid(6))
        .startSequenceNumber(0)
        .maximumSegmentBytes(1024);
  }
}
