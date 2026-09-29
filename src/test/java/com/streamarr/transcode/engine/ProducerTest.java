package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.fixtures.DeliveredSamples.assertCarriesTheRecordedSamples;
import static com.streamarr.transcode.fixtures.DeliveredSamples.audioSamplesOfTheDeliveredSegments;
import static com.streamarr.transcode.fixtures.Races.awaitStart;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static com.streamarr.transcode.fixtures.RecordingFixtures.uploadNames;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.awaitility.Awaitility.await;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.engine.AttemptOutcome.Stopped;
import com.streamarr.transcode.engine.FfmpegRecordings.DroppedAudioSample;
import com.streamarr.transcode.engine.FfmpegRecordings.Recording;
import com.streamarr.transcode.engine.FfmpegRecordings.SegmentSummary;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcess.ExitTiming;
import com.streamarr.transcode.fixtures.AudioInterval;
import com.streamarr.transcode.fixtures.Mp4Samples;
import com.streamarr.transcode.fixtures.Mp4Samples.Movie;
import com.streamarr.transcode.fixtures.Mp4Samples.MovieFragment;
import com.streamarr.transcode.fixtures.Mp4Samples.Sample;
import com.streamarr.transcode.fixtures.RecordingFixtures;
import com.streamarr.transcode.fixtures.TopLevelBoxes;
import com.streamarr.transcode.fixtures.TopLevelBoxes.Box;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
class ProducerTest {

  private static final Duration OUTCOME_LIMIT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(5);
  private static final UUID JOB_ATTEMPT_ID =
      UUID.fromString("0f6a3a9e-4c6b-4f59-9d0e-1c2b3a4d5e6f");
  private static final long SERVER_SEGMENT_CAP_BYTES = 128L * 1024 * 1024;
  private static final int NEARLY_CAPPED_FRAGMENT_PAYLOAD = 40 * 1024 * 1024;
  private static final long ONE_SLOT_CAPACITY = 2 * SERVER_SEGMENT_CAP_BYTES;
  private static final int RACE_ITERATIONS = 200;
  private static final int BURSTS = 12;
  private static final int BURST_SLOTS = 3;

  // The fields of an hvcC up to its array count, for HEVC Main at level 3.0: hvc1.1.6.L90.90.
  private static final String HVCC_FIELDS = "0101600000009000000000005AF000FCFDF8F800000F";
  private static final String HVCC_VPS_ARRAY = "A0000100024001";
  private static final String HVCC_SPS_PPS_ARRAYS = "A1000100024201" + "A2000100024401";
  private static final String HVC1_RECORD_CODEC_STRING = "hvc1.1.6.L90.90";
  private static final byte[] HVC1_PARAMETER_SET_RECORD =
      HexFormat.of().parseHex(HVCC_FIELDS + "03" + HVCC_VPS_ARRAY + HVCC_SPS_PPS_ARRAYS);

  // Where the reader of nearlyCappedSegments() stops while the first segment awaits acceptance:
  // before the body of the third segment's first mdat, which the budget cannot admit.
  private static final int NEARLY_CAPPED_BUDGET_STOP =
      IsoBoxes.ftyp().length
          + videoOnlyMoov().length
          + 6 * keyframeFragment(0).length
          + keyframeMoof(48_000).length
          + 8;

  // Where the reader of nearlyCappedSegments() stops on a one-slot worker while a stopped attempt's
  // cancelled delivery of its first segment still holds three fragments: before the body of the
  // second segment's first mdat, which the worker's budget cannot admit.
  private static final int BEHIND_A_HELD_STOP =
      IsoBoxes.ftyp().length
          + videoOnlyMoov().length
          + 3 * keyframeFragment(0).length
          + keyframeMoof(24_000).length
          + 8;

  private final RecordingSegmentSink sink = new RecordingSegmentSink();

  @ParameterizedTest(name = "{0}")
  @MethodSource("producersLackingARequiredArgument")
  @DisplayName("Should refuse to start a producer when it lacks a required argument")
  void shouldRefuseToStartAProducerWhenItLacksARequiredArgument(
      String argument, Producer.ProducerBuilder builder) {
    assertThatNullPointerException().isThrownBy(builder::start).withMessageContaining(argument);
  }

  // The builder's setters refuse null themselves, so each producer leaves its argument unset.
  static Stream<Arguments> producersLackingARequiredArgument() {
    return Stream.of(
        Arguments.of(
            "encodedFrameRate",
            producerThatMustNotLaunch()
                .videoDecision(VideoDecisionFixtures.encodeBuilder().build())),
        Arguments.of(
            "videoDecision", producerThatMustNotLaunch().encodedFrameRate(OptionalDouble.empty())));
  }

  private static Producer.ProducerBuilder producerThatMustNotLaunch() {
    return Producer.builder()
        .launcher(
            (command, jobAttemptId) -> {
              throw new AssertionError("FFmpeg must not start");
            })
        .command(List.of("ffmpeg"))
        .jobAttemptId(JOB_ATTEMPT_ID)
        .gracePeriod(Duration.ofSeconds(5))
        .stallTimeout(Duration.ofMinutes(1))
        .memoryBudget(SegmentMemoryBudget.forSlots(1))
        .sink(new RecordingSegmentSink());
  }

  static Stream<Recording> recordingsThatComplete() {
    return recordings().filter(recording -> recording.failure().isEmpty());
  }

  static Stream<Recording> recordingsThatDropAudio() {
    return recordings().filter(recording -> !recording.droppedAudioSamples().isEmpty());
  }

  static Stream<Recording> recordings() {
    return FfmpegRecordings.recordings().stream();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordings")
  @DisplayName(
      "Should deliver each media segment as one moof and one mdat when FFmpeg writes several"
          + " fragments for it")
  void shouldDeliverEachMediaSegmentAsOneMoofAndOneMdatWhenFfmpegWritesSeveralFragmentsForIt(
      Recording recording) {
    assertThat(recording.segments())
        .anySatisfy(segment -> assertThat(segment.fragmentCount()).isGreaterThan(1));

    deliverEverySegmentOf(recording);

    assertThat(mediaSegmentNames())
        .isNotEmpty()
        .allSatisfy(
            name ->
                assertThat(TopLevelBoxes.of(sink.contentOf(name)))
                    .extracting(Box::type)
                    .containsExactly("moof", "mdat"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordings")
  @DisplayName("Should deliver FFmpeg's own samples when it rebuilds the media segments")
  void shouldDeliverFfmpegsOwnSamplesWhenItRebuildsTheMediaSegments(Recording recording) {
    deliverEverySegmentOf(recording);

    assertCarriesTheRecordedSamples(sink.acceptedBytes(), recording);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordings")
  @DisplayName(
      "Should deliver each audio sample in the media segment whose interval holds its start, apart"
          + " from segment zero's priming frame and the last segment's tail, when FFmpeg writes it in"
          + " another segment's fragment")
  void
      shouldDeliverEachAudioSampleInTheMediaSegmentWhoseIntervalHoldsItsStartApartFromSegmentZerosPrimingFrameAndTheLastSegmentsTailWhenFfmpegWritesItInAnotherSegmentsFragment(
          Recording recording) {
    var outcome = deliverEverySegmentOf(recording);

    var names = mediaSegmentNames();
    assertThat(names)
        .isNotEmpty()
        .allSatisfy(
            name -> {
              var segment = mediaSegmentOf(sink, name);
              var periodTicks = recording.period() * segment.audioTrack().timescale();
              var interval =
                  AudioInterval.ofDeliveredSegment(
                      numberOf(name),
                      periodTicks,
                      name.equals(names.getLast()) && outcome instanceof Completed);

              assertThat(segment.samplesOf(segment.audioTrack()))
                  .extracting(Sample::presentationTime)
                  .allMatch(
                      interval::holds, name + " starts every audio sample inside " + interval);
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordings")
  @DisplayName(
      "Should deliver exactly once every audio sample FFmpeg writes before its media segment was"
          + " delivered when it rebuilds the media segments")
  void shouldDeliverEveryAudioSampleFfmpegWritesInTimeExactlyOnceWhenItRebuildsTheMediaSegments(
      Recording recording) {
    var outcome = deliverEverySegmentOf(recording);

    var delivered = Mp4Samples.read(sink.acceptedBytes());
    assertThat(delivered.samplesOf(delivered.audioTrack()))
        .isNotEmpty()
        .containsExactlyElementsOf(
            audioSamplesOfTheDeliveredSegments(recording, outcome instanceof Completed));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordings")
  @DisplayName(
      "Should number each media segment's movie fragment one past the segment's sequence number"
          + " when it rebuilds the media segments")
  void shouldNumberEachMediaSegmentsMovieFragmentOnePastItsSequenceNumberWhenItRebuildsTheSegments(
      Recording recording) {
    deliverEverySegmentOf(recording);

    assertThat(mediaSegmentNames())
        .isNotEmpty()
        .allSatisfy(
            name ->
                assertThat(mediaSegmentOf(sink, name).fragments())
                    .extracting(MovieFragment::sequenceNumber)
                    .containsExactly(numberOf(name) + 1L));
  }

  // Stream copies of an MP4 source: FFmpeg writes the same packets at the same decode times in
  // both attempts, so only the producer could make a shared segment's bytes differ.
  @Test
  @DisplayName(
      "Should deliver the start-0 attempt's bytes for every media segment a stream-copy replacement"
          + " attempt delivers when both copy the same packets")
  void shouldDeliverTheStartZeroAttemptsBytesForEveryMediaSegmentWhenBothCopyTheSamePackets() {
    var startZero = deliveredBy(recording("07-copy-start0.fmp4"));
    var replacement = deliveredBy(recording("07-copy-seek30.fmp4"));

    assertThat(replacement.acceptedNames())
        .filteredOn(name -> name.startsWith("segment"))
        .isNotEmpty()
        .allSatisfy(
            name ->
                assertThat(replacement.contentOf(name))
                    .as(name)
                    .isEqualTo(startZero.contentOf(name)));
  }

  // A stream copy writes the same video packets at the same decode times whatever its seek; the
  // encoded audio of a replacement attempt starts at the seek point, and FFmpeg writes nothing
  // until the audio has a sample, so its first fragment runs past the keyframe at 30.030 s.
  @Test
  @DisplayName(
      "Should deliver the start-0 attempt's video samples for every media segment when a"
          + " stream-copy replacement attempt that encodes its audio holds its first segment's"
          + " keyframe inside FFmpeg's first fragment")
  void
      shouldDeliverTheStartZeroAttemptsVideoSamplesWhenAReplacementAttemptHoldsItsFirstSegmentsKeyframeInsideFfmpegsFirstFragment() {
    var startZero = deliveredBy(recording("16-copy-encode-audio.fmp4"));
    var replacement = deliveredBy(recording("16-copy-encode-audio-seek30.fmp4"));

    assertThat(videoSamplesOf(replacement, "segment5.m4s").getFirst().presentationTime())
        .isEqualTo(720_720);
    assertThat(replacement.acceptedNames())
        .filteredOn(name -> name.startsWith("segment"))
        .startsWith("segment5.m4s")
        .allSatisfy(
            name ->
                assertThat(videoSamplesOf(replacement, name))
                    .as(name)
                    .isEqualTo(videoSamplesOf(startZero, name)));
  }

  @ParameterizedTest(name = "{1} after {0}")
  @CsvSource({
    "05-copy-late-start.fmp4, 05-copy-late-start-seek12.fmp4",
    "07-copy-start0.fmp4, 07-copy-seek30.fmp4"
  })
  @DisplayName(
      "Should start a stream-copy replacement attempt's audio where the start-0 attempt's segment"
          + " ends when its first segment follows that segment")
  void
      shouldStartAStreamCopyReplacementAttemptsAudioWhereTheStartZeroAttemptsSegmentEndsWhenItsFirstSegmentFollowsThatSegment(
          String startZeroAttempt, String replacementAttempt) {
    var replacement = recording(replacementAttempt);
    var junction = replacement.startSequenceNumber();

    var before =
        audioSamplesOf(
            deliveredBy(recording(startZeroAttempt)), "segment" + (junction - 1) + ".m4s");
    var after = audioSamplesOf(deliveredBy(replacement), "segment" + junction + ".m4s");

    assertThat(after.getFirst().decodeTime()).isEqualTo(before.getLast().end());
  }

  @Test
  @DisplayName(
      "Should hold a media segment until an audio sample at or after its end arrives when the audio"
          + " lags the keyframe that opens the next segment")
  void shouldHoldAMediaSegmentUntilAnAudioSampleAtOrAfterItsEndArrivesWhenTheAudioLags() {
    var output =
        OneSecondSegments.of(
            IsoBoxes.videoAndAudioMoov(),
            TimedFragment.keyframeAt(0).videoDuration(12_000).audioFrom(0, 12_000, 12_000).build(),
            TimedFragment.keyframeAt(24_000)
                .videoDuration(12_000)
                .audioFrom(24_000, 12_000, 9_600)
                .build(),
            TimedFragment.frameAt(36_000)
                .videoDuration(12_000)
                .audioFrom(45_600, 2_400, 48_000)
                .build());
    var process =
        ScriptedProcess.builder().output(output.bytes()).pauseAfter(output.endOf(1)).build();
    sink.holding(0);

    var producer = producerOfOneSecondSegments(process).start();

    // Closing segment 0 would keep the reader from reading on until the held initialization
    // segment returns.
    awaiting().until(process::hasReachedPause);
    sink.release();
    process.resume();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.acceptedNames()).containsExactly("init.mp4", "segment0.m4s", "segment1.m4s");
    assertThat(audioDecodeTimesOf("segment0.m4s"))
        .containsExactly(0L, 12_000L, 24_000L, 36_000L, 45_600L);
    assertThat(audioDecodeTimesOf("segment1.m4s")).containsExactly(48_000L);
  }

  @Test
  @DisplayName(
      "Should hold a media segment until an audio sample at or after its end arrives when the"
          + " keyframe that opens the next segment carries no audio")
  void
      shouldHoldAMediaSegmentUntilAnAudioSampleAtOrAfterItsEndArrivesWhenTheKeyframeOpeningTheNextCarriesNoAudio() {
    var output =
        OneSecondSegments.of(
            IsoBoxes.videoAndAudioMoov(),
            TimedFragment.keyframeAt(0)
                .videoDuration(12_000)
                .audioFrom(0, 12_000, 12_000, 12_000, 10_000)
                .build(),
            TimedFragment.keyframeAt(24_000).videoDuration(12_000).build(),
            TimedFragment.frameAt(36_000)
                .videoDuration(12_000)
                .audioFrom(46_000, 2_000, 48_000)
                .build());
    var process = ScriptedProcess.builder().output(output.bytes()).build();

    var producer = producerOfOneSecondSegments(process).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.acceptedNames()).containsExactly("init.mp4", "segment0.m4s", "segment1.m4s");
    assertThat(audioDecodeTimesOf("segment0.m4s"))
        .containsExactly(0L, 12_000L, 24_000L, 36_000L, 46_000L);
    assertThat(audioDecodeTimesOf("segment1.m4s")).containsExactly(48_000L);
  }

  @Test
  @DisplayName(
      "Should deliver the video samples before a keyframe inside a fragment in the segment that"
          + " keyframe closes and the rest in the segment it opens when both segments are delivered")
  void
      shouldDeliverTheVideoSamplesBeforeAKeyframeInsideAFragmentInTheSegmentItClosesAndTheRestInTheSegmentItOpens() {
    var workerBudget = SegmentMemoryBudget.forSlots(1);
    var output =
        OneSecondSegments.of(
            IsoBoxes.videoAndAudioMoov(),
            TimedFragment.keyframeAt(0).videoDuration(12_000).audioFrom(0, 24_000).build(),
            TimedFragment.frameAt(12_000)
                .videoDuration(12_000)
                .videoSampleCount(3)
                .keyframeInside(OptionalInt.of(1))
                .audioFrom(24_000, 24_000, 24_000, 24_000)
                .build(),
            TimedFragment.keyframeAt(48_000).audioFrom(96_000, 48_000).build());
    var process = ScriptedProcess.builder().output(output.bytes()).build();

    var producer = producerOfOneSecondSegments(process).memoryBudget(workerBudget).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.acceptedNames())
        .containsExactly("init.mp4", "segment0.m4s", "segment1.m4s", "segment2.m4s");
    assertThat(videoDecodeTimesOf("segment0.m4s")).containsExactly(0L, 12_000L);
    assertThat(videoDecodeTimesOf("segment1.m4s")).containsExactly(24_000L, 36_000L);
    assertThat(videoDecodeTimesOf("segment2.m4s")).containsExactly(48_000L);
    assertThat(audioDecodeTimesOf("segment0.m4s")).containsExactly(0L, 24_000L);
    assertThat(audioDecodeTimesOf("segment1.m4s")).containsExactly(48_000L, 72_000L);
    assertReleasesEveryReservedByte(workerBudget);
  }

  @Test
  @DisplayName(
      "Should deliver a media segment after the audio's end once the video runs a second past its"
          + " end when the audio ends before the video")
  void shouldDeliverAMediaSegmentAfterTheAudiosEndOnceTheVideoRunsASecondPastItsEnd() {
    var output =
        OneSecondSegments.of(
            IsoBoxes.videoAndAudioMoov(),
            TimedFragment.keyframeAt(0).audioFrom(0, 12_000, 12_000).build(),
            TimedFragment.keyframeAt(24_000).build(),
            TimedFragment.keyframeAt(48_000).build(),
            TimedFragment.keyframeAt(72_000).build());
    var process =
        ScriptedProcess.builder().output(output.bytes()).pauseAfter(output.endOf(2)).build();

    var producer = producerOfOneSecondSegments(process).start();

    awaiting().until(process::hasReachedPause);
    awaiting()
        .untilAsserted(
            () ->
                assertThat(sink.acceptedNames())
                    .containsExactly("init.mp4", "segment0.m4s", "segment1.m4s"));
    process.resume();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.acceptedNames())
        .containsExactly(
            "init.mp4", "segment0.m4s", "segment1.m4s", "segment2.m4s", "segment3.m4s");
    assertThat(audioDecodeTimesOf("segment0.m4s")).containsExactly(0L, 12_000L);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "17-copy-audio-ends-long-before-video.fmp4",
        "17-encode-audio-ends-long-before-video.fmp4"
      })
  @DisplayName(
      "Should deliver each media segment after the audio's end while FFmpeg's output continues when"
          + " the audio ends long before the video")
  void shouldDeliverEachMediaSegmentAfterTheAudiosEndWhileFfmpegsOutputContinues(String file) {
    var recording = recording(file);
    // Inside segment 10's first fragment: the video has reached 60 s, a second past segment 8's end
    // and not yet past segment 9's, and the audio ended at 20 s.
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(file))
            .pauseAfter(offsetOfMediaSegment(recording, 10) + 10)
            .resumesOnQuit(true)
            .exitCode(255)
            .build();

    var producer = producerFor(process, recording).start();

    awaiting().until(process::hasReachedPause);
    awaiting()
        .untilAsserted(
            () ->
                assertThat(sink.acceptedNames())
                    .containsExactly(
                        "init.mp4",
                        "segment0.m4s",
                        "segment1.m4s",
                        "segment2.m4s",
                        "segment3.m4s",
                        "segment4.m4s",
                        "segment5.m4s",
                        "segment6.m4s",
                        "segment7.m4s",
                        "segment8.m4s"));
    producer.stop();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Stopped());
  }

  // FFmpeg flushes an audio encoder only once the whole input has ended, so the encoder's last
  // frames of audio that ends long before the video arrive after their segment was delivered.
  @ParameterizedTest(name = "{0}")
  @MethodSource("recordingsThatDropAudio")
  @DisplayName(
      "Should deliver every media segment without the audio samples that arrive after their segment"
          + " was delivered when the audio ends long before the video")
  void shouldDeliverEveryMediaSegmentWithoutTheLateAudioSamplesWhenTheAudioEndsLongBeforeTheVideo(
      Recording recording) {
    var outcome = deliverEverySegmentOf(recording);

    assertThat(outcome).isEqualTo(new Completed());
    assertThat(mediaSegmentNames())
        .extracting(ProducerTest::numberOf)
        .containsExactlyElementsOf(
            recording.segments().stream().map(SegmentSummary::number).toList());
    var delivered = Mp4Samples.read(sink.acceptedBytes());
    assertThat(delivered.samplesOf(delivered.audioTrack()))
        .extracting(Sample::presentationTime)
        .doesNotContainAnyElementsOf(
            recording.droppedAudioSamples().stream()
                .map(DroppedAudioSample::presentationTime)
                .toList());
  }

  @Test
  @DisplayName(
      "Should deliver every media segment within the budget when segments after the audio's end"
          + " would outgrow it together")
  void shouldDeliverEveryMediaSegmentWithinTheBudgetWhenSegmentsAfterTheAudiosEndWouldOutgrowIt() {
    var workerBudget = SegmentMemoryBudget.forSlots(1);
    var seventyMebibytes = 70 * 1024 * 1024;
    // Held until the output ends, segments 1 to 3 and segment 4's fragment would take 280 MiB.
    var output =
        OneSecondSegments.of(
            IsoBoxes.videoAndAudioMoov(),
            TimedFragment.keyframeAt(0).audioFrom(0, 1024).build(),
            TimedFragment.keyframeAt(24_000).paddingBytes(seventyMebibytes).build(),
            TimedFragment.keyframeAt(48_000).paddingBytes(seventyMebibytes).build(),
            TimedFragment.keyframeAt(72_000).paddingBytes(seventyMebibytes).build(),
            TimedFragment.keyframeAt(96_000).paddingBytes(seventyMebibytes).build());
    var process = ScriptedProcess.builder().output(output.bytes()).build();

    var producer = producerOfOneSecondSegments(process).memoryBudget(workerBudget).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.acceptedNames())
        .containsExactly(
            "init.mp4",
            "segment0.m4s",
            "segment1.m4s",
            "segment2.m4s",
            "segment3.m4s",
            "segment4.m4s");
    assertReleasesEveryReservedByte(workerBudget);
  }

  @Test
  @DisplayName(
      "Should close a media segment at the keyframe that opens the next one when the movie has no"
          + " sound track")
  void shouldCloseAMediaSegmentAtTheKeyframeThatOpensTheNextOneWhenTheMovieHasNoSoundTrack() {
    var output =
        OneSecondSegments.of(
            videoOnlyMoov(),
            TimedFragment.keyframeAt(0).build(),
            TimedFragment.keyframeAt(24_000).build(),
            TimedFragment.keyframeAt(48_000).build());
    var process = ScriptedProcess.builder().output(output.bytes()).build();
    sink.holding(0);

    var producer = producerOfOneSecondSegments(process).start();

    // Segment 0 closes at the second fragment, and its delivery waits for the held initialization
    // segment, so the reader reads nothing further meanwhile.
    assertReaderStopsAt(process, output.endOf(1));
    sink.release();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.acceptedNames())
        .containsExactly("init.mp4", "segment0.m4s", "segment1.m4s", "segment2.m4s");
  }

  @Test
  @DisplayName("Should deliver a media segment still waiting for audio when the output ends")
  void shouldDeliverAMediaSegmentStillWaitingForAudioWhenTheOutputEnds() {
    var output =
        OneSecondSegments.of(
            IsoBoxes.videoAndAudioMoov(),
            TimedFragment.keyframeAt(0).audioFrom(0, 12_000, 12_000).build(),
            TimedFragment.keyframeAt(24_000).audioFrom(24_000, 12_000, 9_600).build());
    var process = ScriptedProcess.builder().output(output.bytes()).build();

    var producer = producerOfOneSecondSegments(process).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.acceptedNames()).containsExactly("init.mp4", "segment0.m4s", "segment1.m4s");
    assertThat(audioDecodeTimesOf("segment0.m4s")).containsExactly(0L, 12_000L, 24_000L, 36_000L);
    assertThat(audioDecodeTimesOf("segment1.m4s")).isEmpty();
  }

  @Test
  @DisplayName(
      "Should drop an audio sample and complete the attempt when the sample arrives after the"
          + " segment whose interval holds its start was delivered")
  void shouldDropAnAudioSampleAndCompleteTheAttemptWhenItArrivesAfterItsSegmentWasDelivered() {
    var output =
        OneSecondSegments.of(
            IsoBoxes.videoAndAudioMoov(),
            TimedFragment.keyframeAt(0).audioFrom(0, 12_000, 12_000).build(),
            TimedFragment.keyframeAt(24_000).build(),
            TimedFragment.keyframeAt(48_000).audioFrom(24_000, 12_000).build());
    var process = ScriptedProcess.builder().output(output.bytes()).build();

    var producer = producerOfOneSecondSegments(process).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.acceptedNames())
        .containsExactly("init.mp4", "segment0.m4s", "segment1.m4s", "segment2.m4s");
    assertThat(audioDecodeTimesOf("segment0.m4s")).containsExactly(0L, 12_000L);
    assertThat(audioDecodeTimesOf("segment1.m4s")).isEmpty();
    assertThat(audioDecodeTimesOf("segment2.m4s")).isEmpty();
  }

  @Test
  @DisplayName(
      "Should fail the attempt as malformed output when an audio sample's presentation time runs"
          + " back into a segment that was delivered")
  void shouldFailTheAttemptAsMalformedOutputWhenAnAudioSampleRunsBackIntoADeliveredSegment() {
    var output =
        OneSecondSegments.of(
            IsoBoxes.videoAndAudioMoov(),
            TimedFragment.keyframeAt(0).audioFrom(0, 12_000, 12_000).build(),
            TimedFragment.keyframeAt(24_000).audioFrom(48_000, 12_000).build(),
            TimedFragment.frameAt(48_000).audioFrom(24_000, 12_000).build());
    var process = ScriptedProcess.builder().output(output.bytes()).build();

    var producer = producerOfOneSecondSegments(process).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.MALFORMED_OUTPUT);
    assertThat(failure.detail()).contains("PRESENTATION_TIME_REGRESSED");
    assertThat(sink.acceptedNames()).containsExactly("init.mp4", "segment0.m4s");
  }

  @Test
  @DisplayName(
      "Should fail the attempt as malformed output when an audio sample's presentation time runs"
          + " back inside a segment still being assembled")
  void shouldFailTheAttemptAsMalformedOutputWhenAnAudioSampleRunsBackInsideAnOpenSegment() {
    var output =
        OneSecondSegments.of(
            IsoBoxes.videoAndAudioMoov(),
            TimedFragment.keyframeAt(0).audioFrom(12_000, 12_000).build(),
            TimedFragment.frameAt(24_000).audioFrom(0, 12_000).build(),
            TimedFragment.keyframeAt(48_000).audioFrom(48_000, 12_000).build());
    var process = ScriptedProcess.builder().output(output.bytes()).build();

    var producer = producerOfOneSecondSegments(process).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.MALFORMED_OUTPUT);
    assertThat(failure.detail()).contains("PRESENTATION_TIME_REGRESSED");
    assertThat(mediaSegmentNames()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordingsThatComplete")
  @DisplayName(
      "Should deliver the initialization segment and then each media segment in order when FFmpeg"
          + " exits cleanly")
  void shouldDeliverTheInitializationSegmentAndThenEachMediaSegmentInOrderWhenFfmpegExitsCleanly(
      Recording recording) {
    var process = ScriptedProcess.builder().output(bytesOf(recording.file())).build();

    var producer = producerFor(process, recording).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertDeliveredInOrder(recording);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "01-encode-cfr-seek30.fmp4",
        "09-svtav1-vfr-seek30.fmp4",
        "13-x265-cfr-seek30.fmp4"
      })
  @DisplayName(
      "Should discard the period before the first segment and deliver that segment from its"
          + " boundary when an encoded replacement attempt seeks one period early")
  void
      shouldDiscardThePeriodBeforeTheFirstSegmentAndDeliverThatSegmentFromItsBoundaryWhenAnEncodedReplacementAttemptSeeksOnePeriodEarly(
          String file) throws IOException {
    var recording = recording(file);
    var process = ScriptedProcess.builder().output(bytesOf(file)).build();

    var producer = producerFor(process, recording).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(recording.discardedPreroll())
        .extracting(SegmentSummary::number)
        .containsExactly(recording.startSequenceNumber() - 1);
    assertThat(sink.acceptedNames())
        .startsWith("init.mp4", "segment5.m4s")
        .doesNotContain("segment4.m4s");
    var delivered = Mp4Stream.read(Mp4Stream.readerOf(sink.acceptedBytes()));
    // 30.030 s: frame 720, the first frame at or after 30 s on the 23.976 fps grid from zero.
    assertThat(delivered.fragments().getFirst().videoStart())
        .contains(new VideoStart(720_720, 24_000, true));
  }

  @Test
  @DisplayName("Should not complete the attempt when the last media segment awaits acceptance")
  void shouldNotCompleteTheAttemptWhenTheLastMediaSegmentAwaitsAcceptance() {
    var recording = recording(ENCODED_RECORDING);
    var process = ScriptedProcess.builder().output(bytesOf(ENCODED_RECORDING)).build();
    sink.holding(recording.segments().size());

    var producer = producerFor(process, recording).start();

    awaiting().until(sink::isHolding);
    assertThat(process.isAlive()).isFalse();
    assertThat(producer.outcome()).isNotDone();
    sink.release();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
  }

  @Test
  @DisplayName(
      "Should assemble the next segment while one awaits acceptance and stop reading when that"
          + " segment closes")
  void shouldAssembleTheNextSegmentWhileOneAwaitsAcceptanceAndStopReadingWhenThatSegmentCloses() {
    var recording = recording(ENCODED_RECORDING);
    var recorded = bytesOf(ENCODED_RECORDING);
    var process = ScriptedProcess.builder().output(recorded).build();
    sink.holding(1);
    var firstThirdSegmentFragment = offsetOfMediaSegment(recording, 2);
    var closingFragmentEnd = endOfFragmentAt(recorded, firstThirdSegmentFragment);

    var producer = producerFor(process, recording).start();

    assertReaderStopsAt(process, closingFragmentEnd);
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
    sink.release();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertDeliveredInOrder(recording);
  }

  @Test
  @DisplayName("Should hold no more than two segment caps and block FFmpeg when the budget is full")
  void shouldHoldNoMoreThanTwoSegmentCapsAndBlockFfmpegWhenTheBudgetIsFull() {
    var process = ScriptedProcess.builder().output(nearlyCappedSegments()).build();
    sink.holding(1);

    var producer = producerOfOneSecondSegments(process).start();

    assertReaderStopsAt(process, NEARLY_CAPPED_BUDGET_STOP);
    assertThat((long) process.bytesTaken() - IsoBoxes.ftyp().length - videoOnlyMoov().length)
        .isLessThanOrEqualTo(2 * SERVER_SEGMENT_CAP_BYTES);
    sink.release();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.acceptedNames())
        .containsExactly("init.mp4", "segment0.m4s", "segment1.m4s", "segment2.m4s");
  }

  @Test
  @DisplayName(
      "Should discard the rest of the output and let FFmpeg exit when stopped while the budget is"
          + " full")
  void shouldDiscardTheRestOfTheOutputAndLetFfmpegExitWhenStoppedWhileTheBudgetIsFull() {
    var process = ScriptedProcess.builder().output(nearlyCappedSegments()).build();
    sink.holding(1);
    var producer = producerOfOneSecondSegments(process).gracePeriod(Duration.ofMinutes(10)).start();
    awaiting().until(() -> process.bytesTaken() == NEARLY_CAPPED_BUDGET_STOP);

    producer.stop();

    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
    assertThat(process.hasReadToEndOfOutput()).isTrue();
    assertThat(process.wasDestroyedForcibly()).isFalse();
    assertThat(sink.wasCancelled()).isTrue();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
  }

  @Test
  @DisplayName(
      "Should hold another attempt's reader only until a stopped attempt's cancelled delivery"
          + " returns when both share the worker's budget")
  void
      shouldHoldAnotherAttemptsReaderOnlyUntilAStoppedAttemptsCancelledDeliveryReturnsWhenBothShareTheWorkersBudget() {
    var workerBudget = SegmentMemoryBudget.forSlots(1);
    var stopped = stopHoldingItsCancelledDelivery(workerBudget);
    var nextProcess = ScriptedProcess.builder().output(nearlyCappedSegments()).build();

    var next = producerOfOneSecondSegments(nextProcess).memoryBudget(workerBudget).start();

    // The stop released the segment the stopped attempt was assembling, but not its cancelled
    // delivery.
    assertReaderStopsAt(nextProcess, BEHIND_A_HELD_STOP);
    stopped.sink().release();
    assertThat(next.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(stopped.process().isAlive()).isTrue();
    stopped.process().exit();
    assertThat(stopped.producer().outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Stopped());
  }

  @Test
  @DisplayName(
      "Should admit a reader waiting for the worker's memory before a later attempt's reader whose"
          + " boxes would fit when both share the worker's budget")
  void
      shouldAdmitAReaderWaitingForTheWorkersMemoryBeforeALaterAttemptsReaderWhoseBoxesWouldFitWhenBothShareTheWorkersBudget() {
    var workerBudget = SegmentMemoryBudget.forSlots(1);
    var stopped = stopHoldingItsCancelledDelivery(workerBudget);
    var waitingProcess = ScriptedProcess.builder().output(nearlyCappedSegments()).build();
    var waiting = producerOfOneSecondSegments(waitingProcess).memoryBudget(workerBudget).start();
    assertReaderStopsAt(waitingProcess, BEHIND_A_HELD_STOP);
    var laterProcess = ScriptedProcess.builder().output(bytesOf(ENCODED_RECORDING)).build();
    var laterSink = new RecordingSegmentSink();

    var later =
        producerFor(laterProcess, recording(ENCODED_RECORDING))
            .sink(laterSink)
            .memoryBudget(workerBudget)
            .start();

    // The whole recording would fit in what the waiting reader leaves free, but the later reader
    // takes only its first box header until the waiting reader has its memory.
    assertReaderStopsAt(laterProcess, 8);
    stopped.sink().release();
    assertThat(waiting.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(later.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(laterSink.acceptedNames()).contains("init.mp4");
    stopped.process().exit();
    assertThat(stopped.producer().outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Stopped());
  }

  @Test
  @DisplayName(
      "Should keep within the worker's budget and let every new attempt proceed when stops and"
          + " starts arrive in bursts")
  void
      shouldKeepWithinTheWorkersBudgetAndLetEveryNewAttemptProceedWhenStopsAndStartsArriveInBursts()
          throws InterruptedException {
    var output = nearlyCappedSegments();
    var layout = TopLevelBoxes.of(output);
    var workerBudget = SegmentMemoryBudget.forSlots(BURST_SLOTS);
    var capacity = BURST_SLOTS * 2 * SERVER_SEGMENT_CAP_BYTES;
    var attempts = new CopyOnWriteArrayList<BurstAttempt>();
    var highestCharge = new AtomicLong();

    try (var sampler = Executors.newSingleThreadScheduledExecutor()) {
      var sampling =
          sampler.scheduleAtFixedRate(
              () -> highestCharge.accumulateAndGet(chargedAtLeast(attempts, layout), Math::max),
              0,
              1,
              TimeUnit.MILLISECONDS);
      var running =
          startBurst(BurstStart.builder().output(output).budget(workerBudget).burst(0).build());
      attempts.addAll(running);
      awaitEachReaderAtItsBudget(running);
      for (var burst = 1; burst <= BURSTS; burst++) {
        var stopped = running;
        var next = BurstStart.builder().output(output).budget(workerBudget).burst(burst).build();
        running = stopAndStartTogether(stopped, () -> startBurst(next));
        attempts.addAll(running);

        awaitReadersWaiting(running);
        assertThat(chargedAtLeast(attempts, layout)).isLessThanOrEqualTo(capacity);
        assertThat(running)
            .as("a cancelled delivery still held keeps some new reader waiting for memory")
            .anySatisfy(
                attempt ->
                    assertThat(attempt.process().bytesTaken())
                        .isLessThan(NEARLY_CAPPED_BUDGET_STOP));
        stopped.forEach(attempt -> attempt.sink().release());
        awaitEachReaderAtItsBudget(running);
        assertThat(stopped).allSatisfy(attempt -> assertThat(attempt.process().isAlive()).isTrue());
        endStoppedAttempts(stopped);
        attempts.removeAll(stopped);
      }

      running.forEach(BurstAttempt::stop);
      running.forEach(attempt -> attempt.sink().release());
      endStoppedAttempts(running);
      assertThat(sampling.isDone()).as("the sampler never failed").isFalse();
    }

    assertThat(highestCharge.get()).isPositive().isLessThanOrEqualTo(capacity);
  }

  @Test
  @DisplayName(
      "Should start a media segment's delivery only once the worker's budget also holds the moof"
          + " it writes when another attempt's reservations leave room for the fragments alone")
  void
      shouldStartAMediaSegmentsDeliveryOnlyOnceTheWorkersBudgetAlsoHoldsTheMoofItWritesWhenOnlyTheFragmentsFit() {
    var workerBudget = SegmentMemoryBudget.forSlots(1);
    var output = OneKeyframePerSecond.of(3);
    var process = ScriptedProcess.builder().output(output.bytes()).build();
    var otherAttempts = output.leavingRoomForTheFirstTwoFragmentsIn(workerBudget);

    var producer = producerOfOneSecondSegments(process).memoryBudget(workerBudget).start();

    assertReaderStopsAt(process, output.endOf(1));
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
    workerBudget.release(otherAttempts);
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.acceptedNames())
        .containsExactly("init.mp4", "segment0.m4s", "segment1.m4s", "segment2.m4s");
    assertReleasesEveryReservedByte(workerBudget);
  }

  @Test
  @DisplayName(
      "Should release every byte it reserved in the worker's budget when stopped while waiting for"
          + " that budget to hold the moof it writes")
  void
      shouldReleaseEveryByteItReservedInTheWorkersBudgetWhenStoppedWhileWaitingForItToHoldTheMoofItWrites() {
    var workerBudget = SegmentMemoryBudget.forSlots(1);
    var output = OneKeyframePerSecond.of(3);
    var process = ScriptedProcess.builder().output(output.bytes()).build();
    var otherAttempts = output.leavingRoomForTheFirstTwoFragmentsIn(workerBudget);
    var producer = producerOfOneSecondSegments(process).memoryBudget(workerBudget).start();
    assertReaderStopsAt(process, output.endOf(1));

    producer.stop();

    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
    workerBudget.release(otherAttempts);
    assertReleasesEveryReservedByte(workerBudget);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordings")
  @DisplayName(
      "Should release every byte it reserved in the worker's budget when the attempt settles")
  void shouldReleaseEveryByteItReservedInTheWorkersBudgetWhenTheAttemptSettles(
      Recording recording) {
    var workerBudget = SegmentMemoryBudget.forSlots(1);
    var process = ScriptedProcess.builder().output(bytesOf(recording.file())).build();

    var producer = producerFor(process, recording).memoryBudget(workerBudget).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT);
    assertReleasesEveryReservedByte(workerBudget);
  }

  @Test
  @DisplayName(
      "Should free the segment it was assembling at the stop when a hung FFmpeg leaves the reader"
          + " waiting inside a fragment")
  void
      shouldFreeTheSegmentItWasAssemblingAtTheStopWhenAHungFfmpegLeavesTheReaderWaitingInsideAFragment() {
    // Segment 0's three 40 MiB fragments and the first MiB of the next fragment's mdat, after which
    // FFmpeg writes nothing more and ignores the quit.
    var hangOffset =
        IsoBoxes.ftyp().length
            + videoOnlyMoov().length
            + 3 * keyframeFragment(0).length
            + keyframeMoof(24_000).length
            + 8
            + 1024 * 1024;
    var process =
        ScriptedProcess.builder()
            .output(nearlyCappedSegments())
            .pauseAfter(hangOffset)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var heldBeforeReading = liveHeapBytes();
    var producer = producerOfOneSecondSegments(process).gracePeriod(Duration.ofMinutes(10)).start();
    awaiting().until(process::hasReachedPause);
    assertThat(liveHeapBytes())
        .as("the reader holds three fragments and all of the next fragment's mdat")
        .isGreaterThan(heldBeforeReading + 150L * 1024 * 1024);

    producer.requestStop();

    await()
        .atMost(OUTCOME_LIMIT)
        .pollInterval(Duration.ofMillis(100))
        .until(() -> liveHeapBytes() <= heldBeforeReading + 2L * 1024 * 1024);
    assertThat(process.bytesTaken()).isEqualTo(hangOffset);
    assertThat(process.isAlive()).isTrue();
    process.resume();
    process.exit();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Stopped());
  }

  @Test
  @DisplayName(
      "Should hold none of the discarded preroll against the budget when the preroll of a"
          + " replacement attempt outgrows the segment cap")
  void
      shouldHoldNoneOfTheDiscardedPrerollAgainstTheBudgetWhenThePrerollOfAReplacementAttemptOutgrowsTheSegmentCap() {
    // Four 40 MiB keyframe fragments in segment 0, which an attempt from segment 1 discards.
    var preroll =
        IsoBoxes.concat(
            keyframeFragment(0),
            keyframeFragment(6_000),
            keyframeFragment(12_000),
            keyframeFragment(18_000));
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(), videoOnlyMoov(), preroll, nearlyCappedFragmentsFrom(24_000));
    var process = ScriptedProcess.builder().output(output).build();
    sink.holding(1);

    var producer = producerOfOneSecondSegments(process).startSequenceNumber(1).start();

    var budgetStop = preroll.length + NEARLY_CAPPED_BUDGET_STOP;
    assertReaderStopsAt(process, budgetStop);
    sink.release();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.acceptedNames())
        .containsExactly("init.mp4", "segment1.m4s", "segment2.m4s", "segment3.m4s");
  }

  @Test
  @DisplayName(
      "Should not complete the attempt when the output has ended but FFmpeg has not exited")
  void shouldNotCompleteTheAttemptWhenTheOutputHasEndedButFfmpegHasNotExited() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();

    var producer = producerFor(process, recording).start();

    awaiting().until(() -> sink.accepted().size() == recording.segments().size() + 1);
    assertThat(process.hasReadToEndOfOutput()).isTrue();
    assertThat(producer.outcome()).isNotDone();
    process.exit();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = ExitTiming.class,
      names = {"AT_LAUNCH", "AT_END_OF_OUTPUT"})
  @DisplayName(
      "Should deliver every media segment when FFmpeg exits before or after its output ends")
  void shouldDeliverEveryMediaSegmentWhenFfmpegExitsBeforeOrAfterItsOutputEnds(
      ExitTiming exitTiming) {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder().output(bytesOf(ENCODED_RECORDING)).exitTiming(exitTiming).build();

    var producer = producerFor(process, recording).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertDeliveredInOrder(recording);
  }

  @Test
  @DisplayName("Should fail the attempt with FFmpeg's last error output when FFmpeg exits non-zero")
  void shouldFailTheAttemptWithFfmpegsLastErrorOutputWhenFfmpegExitsNonZero() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .exitCode(1)
            .stderr("frame=  264 fps=0.0 q=-1.0 size=N/A\nConversion failed!\n")
            .build();

    var producer = producerFor(process, recording).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.PROCESS_EXITED_WITH_ERROR);
    assertThat(failure.detail()).contains("exit code 1").endsWith("Conversion failed!");
  }

  @Test
  @DisplayName("Should fail the attempt when the output ends inside a box and FFmpeg exits cleanly")
  void shouldFailTheAttemptWhenTheOutputEndsInsideABoxAndFfmpegExitsCleanly() {
    var recording = recording(ENCODED_RECORDING);
    var process = ScriptedProcess.builder().output(truncated(bytesOf(ENCODED_RECORDING))).build();

    var producer = producerFor(process, recording).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.TRUNCATED_OUTPUT);
    assertThat(failure.detail()).contains("END_OF_FILE_IN_BOX_BODY");
    assertThat(sink.acceptedNames())
        .hasSize(recording.segments().size())
        .doesNotContain("segment10.m4s");
  }

  @Test
  @DisplayName(
      "Should report FFmpeg's exit when the output ends inside a box and FFmpeg exits non-zero")
  void shouldReportFfmpegsExitWhenTheOutputEndsInsideABoxAndFfmpegExitsNonZero() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(truncated(bytesOf(ENCODED_RECORDING)))
            .exitCode(234)
            .stderr("Error writing trailer of pipe:1: Broken pipe")
            .build();

    var producer = producerFor(process, recording).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.PROCESS_EXITED_WITH_ERROR);
    assertThat(failure.detail()).contains("exit code 234");
  }

  @Test
  @DisplayName("Should fail the attempt and end FFmpeg when a box exceeds the segment cap")
  void shouldFailTheAttemptAndEndFfmpegWhenABoxExceedsTheSegmentCap() {
    var recording = recording(ENCODED_RECORDING);
    var recorded = bytesOf(ENCODED_RECORDING);
    var output =
        IsoBoxes.concat(
            Arrays.copyOf(recorded, recording.initializationSegment().byteLength()),
            IsoBoxes.header(SERVER_SEGMENT_CAP_BYTES + 1, "moof"),
            new byte[64]);
    var process = ScriptedProcess.builder().output(output).build();

    var producer = producerFor(process, recording).start();

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.SEGMENT_CAP_EXCEEDED);
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
  }

  @Test
  @DisplayName(
      "Should fail the attempt and end FFmpeg when fragments under the segment cap add up to a"
          + " media segment over it")
  void shouldFailTheAttemptAndEndFfmpegWhenFragmentsUnderTheSegmentCapAddUpToAMediaSegmentOverIt() {
    var mediaDataBytes = Math.toIntExact(SERVER_SEGMENT_CAP_BYTES / 3 + 1);
    // The encoded recording's producer checks sample durations, so each video sample lasts a
    // frame at 24000/1001 frames per second.
    var oneFrameVideo = IsoBoxes.Track.video().defaultSampleDuration(1001).build();
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(),
            IsoBoxes.moov(oneFrameVideo, IsoBoxes.Track.audio().build()),
            IsoBoxes.moof(
                IsoBoxes.videoTraf()
                    .baseMediaDecodeTime(0L)
                    .firstSampleFlags(IsoBoxes.SYNC_SAMPLE_FLAGS)
                    .build()),
            IsoBoxes.mdat(mediaDataBytes),
            IsoBoxes.moof(IsoBoxes.videoTraf().baseMediaDecodeTime(24_000L).build()),
            IsoBoxes.mdat(mediaDataBytes),
            IsoBoxes.moof(IsoBoxes.videoTraf().baseMediaDecodeTime(48_000L).build()),
            IsoBoxes.mdat(mediaDataBytes));
    var process = ScriptedProcess.builder().output(output).build();

    var producer = producerFor(process, recording(ENCODED_RECORDING)).start();

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.SEGMENT_CAP_EXCEEDED);
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
  }

  @Test
  @DisplayName(
      "Should fail the attempt with the segment cap and release its memory when segments awaiting"
          + " their audio leave the producer's budget no room for the next box")
  void
      shouldFailTheAttemptWithTheSegmentCapWhenSegmentsAwaitingTheirAudioLeaveTheBudgetNoRoomForTheNextBox() {
    var workerBudget = SegmentMemoryBudget.forSlots(1);
    var fiftyMebibytes = 50 * 1024 * 1024;
    // Segment 0's 100 MiB awaits audio at or after its end while the video stays within a second
    // of it, and segment 1's 100 MiB leave no room for its next 60 MiB fragment.
    var output =
        OneSecondSegments.of(
            IsoBoxes.videoAndAudioMoov(),
            TimedFragment.keyframeAt(0)
                .videoDuration(12_000)
                .audioFrom(0, 1024)
                .paddingBytes(fiftyMebibytes)
                .build(),
            TimedFragment.frameAt(12_000)
                .videoDuration(12_000)
                .paddingBytes(fiftyMebibytes)
                .build(),
            TimedFragment.keyframeAt(24_000)
                .videoDuration(6_000)
                .paddingBytes(fiftyMebibytes)
                .build(),
            TimedFragment.frameAt(30_000).videoDuration(6_000).paddingBytes(fiftyMebibytes).build(),
            TimedFragment.frameAt(36_000)
                .videoDuration(6_000)
                .paddingBytes(60 * 1024 * 1024)
                .build());
    var process = ScriptedProcess.builder().output(output.bytes()).build();

    var producer = producerOfOneSecondSegments(process).memoryBudget(workerBudget).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.SEGMENT_CAP_EXCEEDED);
    assertThat(failure.detail()).contains("no delivery in flight can free");
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
    assertReleasesEveryReservedByte(workerBudget);
  }

  @Test
  @DisplayName(
      "Should fail the attempt with the segment cap when a media segment rebuilt as one moof and"
          + " mdat outgrows the cap although its fragments fit")
  void
      shouldFailTheAttemptWithTheSegmentCapWhenAMediaSegmentRebuiltAsOneMoofAndMdatOutgrowsTheCap() {
    // Each sample of this size takes 4 bytes more in a fragment and 16 more in the rebuilt segment,
    // so two fragments of this many samples fit the cap and their rebuilt segment does not.
    var sampleSize = 200;
    var samplesPerFragment = Math.toIntExact(SERVER_SEGMENT_CAP_BYTES / (2 * (sampleSize + 10)));
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(),
            IsoBoxes.moov(IsoBoxes.Track.video().build()),
            manySampleFragment(
                IsoBoxes.videoTraf()
                    .baseMediaDecodeTime(0L)
                    .firstSampleFlags(IsoBoxes.SYNC_SAMPLE_FLAGS)
                    .sampleCount(samplesPerFragment)
                    .sampleSize(sampleSize)),
            manySampleFragment(
                IsoBoxes.videoTraf()
                    .baseMediaDecodeTime(12_000L)
                    .firstSampleFlags(IsoBoxes.NON_SYNC_SAMPLE_FLAGS)
                    .sampleCount(samplesPerFragment)
                    .sampleSize(sampleSize)),
            keyframeFragment(24_000, 1));
    assertThat((long) output.length).isLessThan(SERVER_SEGMENT_CAP_BYTES);
    var process = ScriptedProcess.builder().output(output).build();

    var producer = producerOfOneSecondSegments(process).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.SEGMENT_CAP_EXCEEDED);
    assertThat(failure.detail()).contains("as one moof and mdat");
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
  }

  @Test
  @DisplayName(
      "Should fail the attempt with the segment cap when a fragment holds more samples than a"
          + " media segment under the cap can describe")
  void
      shouldFailTheAttemptWithTheSegmentCapWhenAFragmentHoldsMoreSamplesThanAMediaSegmentCanDescribe() {
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(),
            IsoBoxes.moov(IsoBoxes.Track.video().build()),
            manySampleFragment(
                0, Math.toIntExact(SERVER_SEGMENT_CAP_BYTES / 16 + 1), IsoBoxes.SYNC_SAMPLE_FLAGS));
    var process = ScriptedProcess.builder().output(output).build();

    var producer = producerOfOneSecondSegments(process).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.SEGMENT_CAP_EXCEEDED);
    assertThat(failure.detail()).contains("samples needs more than the segment cap");
  }

  @Test
  @DisplayName(
      "Should fail the attempt with the segment cap when the producer's budget cannot also hold the"
          + " moof it writes for a media segment")
  void shouldFailTheAttemptWithTheSegmentCapWhenTheProducersBudgetCannotAlsoHoldTheMoofItWrites() {
    // A capped segment 0 and a capped fragment that opens segment 1 fill the budget exactly, so
    // nothing is left for segment 0's new moof.
    var cappedFragmentPayload =
        Math.toIntExact(SERVER_SEGMENT_CAP_BYTES - keyframeMoof(0).length - 8);
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(),
            videoOnlyMoov(),
            keyframeFragment(0, cappedFragmentPayload),
            keyframeFragment(24_000, cappedFragmentPayload));
    var process = ScriptedProcess.builder().output(output).build();

    var producer = producerOfOneSecondSegments(process).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.SEGMENT_CAP_EXCEEDED);
    assertThat(failure.detail()).contains("writing a media segment needs");
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("outputsThatCannotBeRebuilt")
  @DisplayName(
      "Should fail the attempt as malformed output when a media segment's samples cannot be"
          + " rebuilt as one moof and mdat")
  void shouldFailTheAttemptAsMalformedOutputWhenAMediaSegmentsSamplesCannotBeRebuilt(
      String description, byte[] output, String detail) {
    var process = ScriptedProcess.builder().output(output).build();

    var producer = producerOfOneSecondSegments(process).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.MALFORMED_OUTPUT);
    assertThat(failure.detail()).contains(detail);
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
  }

  static Stream<Arguments> outputsThatCannotBeRebuilt() {
    var videoOnly = videoOnlyMoov();
    return Stream.of(
        Arguments.of(
            "decode times that run backwards",
            IsoBoxes.concat(
                IsoBoxes.ftyp(),
                videoOnly,
                keyframeFragment(6_000, 1),
                IsoBoxes.moof(IsoBoxes.videoTraf().baseMediaDecodeTime(3_000L).build()),
                IsoBoxes.mdat(1),
                keyframeFragment(24_000, 1)),
            "decodes a sample at 3000 after one at 6000"),
        Arguments.of(
            "a composition offset beyond a signed 32-bit value",
            IsoBoxes.concat(
                IsoBoxes.ftyp(),
                videoOnly,
                keyframeFragment(0, 1),
                IsoBoxes.moof(
                    IsoBoxes.videoTraf()
                        .baseMediaDecodeTime(6_000L)
                        .compositionOffset(Integer.MIN_VALUE)
                        .build()),
                IsoBoxes.mdat(1),
                keyframeFragment(24_000, 1)),
            "offsets a sample's composition by 2147483648"),
        Arguments.of(
            "a decode time beyond a signed 64-bit value",
            IsoBoxes.concat(
                IsoBoxes.ftyp(),
                videoOnly,
                IsoBoxes.moof(
                    IsoBoxes.videoTraf()
                        .baseMediaDecodeTime(Long.MAX_VALUE)
                        .decodeTimeVersion(1)
                        .firstSampleFlags(IsoBoxes.SYNC_SAMPLE_FLAGS)
                        .sampleDurations(List.of(1))
                        .build()),
                IsoBoxes.mdat(1)),
            "a decode time overflows a signed 64-bit value"),
        Arguments.of(
            "samples of a track the movie does not declare",
            IsoBoxes.concat(
                IsoBoxes.ftyp(),
                videoOnly,
                IsoBoxes.moof(
                    IsoBoxes.videoTraf()
                        .baseMediaDecodeTime(0L)
                        .firstSampleFlags(IsoBoxes.SYNC_SAMPLE_FLAGS)
                        .build(),
                    IsoBoxes.audioTraf()
                        .baseMediaDecodeTime(0L)
                        .sampleDurations(List.of(1024))
                        .build()),
                IsoBoxes.mdat(2)),
            "neither the movie's video track nor its sound track"),
        Arguments.of(
            "an audio traf with samples but no tfdt",
            IsoBoxes.concat(
                IsoBoxes.ftyp(),
                IsoBoxes.videoAndAudioMoov(),
                IsoBoxes.moof(
                    IsoBoxes.videoTraf()
                        .baseMediaDecodeTime(0L)
                        .firstSampleFlags(IsoBoxes.SYNC_SAMPLE_FLAGS)
                        .build(),
                    IsoBoxes.audioTraf().build()),
                IsoBoxes.mdat(2)),
            "traf holds no tfdt"),
        Arguments.of(
            "an audio sample that needs a default duration no box declares",
            IsoBoxes.concat(
                IsoBoxes.ftyp(),
                IsoBoxes.box(
                    "moov",
                    IsoBoxes.fullBox("mvhd", 0, new byte[96]),
                    IsoBoxes.Track.video().build().trak(),
                    IsoBoxes.Track.audio().build().trak(),
                    IsoBoxes.box("mvex", IsoBoxes.Track.video().build().trex())),
                IsoBoxes.moof(
                    IsoBoxes.videoTraf()
                        .baseMediaDecodeTime(0L)
                        .firstSampleFlags(IsoBoxes.SYNC_SAMPLE_FLAGS)
                        .build(),
                    IsoBoxes.audioTraf().baseMediaDecodeTime(0L).build()),
                IsoBoxes.mdat(2)),
            "needs a default sample field, and no trex declares one"));
  }

  @Test
  @DisplayName(
      "Should fail a replacement attempt as malformed output when an audio sample's presentation"
          + " time overflows a signed 64-bit value")
  void shouldFailAReplacementAttemptAsMalformedOutputWhenAnAudioSamplesPresentationTimeOverflows() {
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(),
            IsoBoxes.videoAndAudioMoov(),
            IsoBoxes.moof(
                IsoBoxes.videoTraf()
                    .baseMediaDecodeTime(24_000L)
                    .firstSampleFlags(IsoBoxes.SYNC_SAMPLE_FLAGS)
                    .build(),
                IsoBoxes.audioTraf()
                    .decodeTimeVersion(1)
                    .baseMediaDecodeTime(Long.MAX_VALUE - 1024)
                    .sampleDurations(List.of(1024))
                    .compositionOffset(2048)
                    .build()),
            IsoBoxes.mdat(2),
            keyframeFragment(48_000, 1));
    var process = ScriptedProcess.builder().output(output).build();

    var producer = producerOfOneSecondSegments(process).startSequenceNumber(1).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.MALFORMED_OUTPUT);
    assertThat(failure.detail()).contains("presentation time overflows");
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
  }

  @Test
  @DisplayName(
      "Should deliver the closed segment, then fail the attempt and end FFmpeg, when source"
          + " keyframes are further apart than the period")
  void
      shouldDeliverTheClosedSegmentThenFailTheAttemptAndEndFfmpegWhenSourceKeyframesAreFurtherApartThanThePeriod() {
    var recording = recording("10-copy-gop-exceeds-period.fmp4");
    var process = ScriptedProcess.builder().output(bytesOf(recording.file())).build();

    var producer = producerFor(process, recording).start();

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.SKIPPED_SEGMENT_NUMBER);
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertDeliveredInOrder(recording);
  }

  // ADR 0037: a skip lets the segment it closed finish delivery before the skip is recorded, but
  // a failed upload is recorded at once.
  @Test
  @DisplayName(
      "Should report the refused segment when the sink does not accept the segment a skipping"
          + " keyframe closed")
  void shouldReportTheRefusedSegmentWhenTheSinkDoesNotAcceptTheSegmentASkippingKeyframeClosed() {
    var recording = recording("10-copy-gop-exceeds-period.fmp4");
    var process = ScriptedProcess.builder().output(bytesOf(recording.file())).build();
    sink.refusing(2);

    var producer = producerFor(process, recording).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.SEGMENT_NOT_ACCEPTED);
    assertThat(failure.detail()).contains("segment1.m4s");
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4", "segment0.m4s");
  }

  @Test
  @DisplayName(
      "Should settle only the stop and cancel the delivery when stopped while the sink holds the"
          + " segment a skipping keyframe closed")
  void
      shouldSettleOnlyTheStopAndCancelTheDeliveryWhenStoppedWhileTheSinkHoldsTheSegmentASkippingKeyframeClosed() {
    var recording = recording("10-copy-gop-exceeds-period.fmp4");
    var process = ScriptedProcess.builder().output(bytesOf(recording.file())).build();
    sink.holding(2);
    var producer = producerFor(process, recording).gracePeriod(Duration.ofMillis(100)).start();
    awaiting().until(sink::isHolding);

    producer.stop();

    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
    assertThat(sink.wasCancelled()).isTrue();
    await()
        .during(Duration.ofMillis(200))
        .atMost(OUTCOME_LIMIT)
        .until(() -> sink.acceptedNames().equals(List.of("init.mp4", "segment0.m4s")));
  }

  @Test
  @DisplayName("Should fail the attempt and end FFmpeg when the output does not begin with a movie")
  void shouldFailTheAttemptAndEndFfmpegWhenTheOutputDoesNotBeginWithAMovie() {
    var output = IsoBoxes.concat(IsoBoxes.box("free", new byte[16]), bytesOf(ENCODED_RECORDING));
    var process = ScriptedProcess.builder().output(output).build();

    var producer = producerFor(process, recording(ENCODED_RECORDING)).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.MALFORMED_OUTPUT);
    assertThat(failure.detail()).contains("MISSING_INITIALIZATION_SEGMENT");
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(sink.acceptedNames()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should fail the attempt as an output mismatch, deliver nothing and end FFmpeg when an"
          + " encode's initialization segment declares another level")
  void
      shouldFailTheAttemptAsAnOutputMismatchDeliverNothingAndEndFfmpegWhenAnEncodesInitializationSegmentDeclaresAnotherLevel() {
    var recording = recording(ENCODED_RECORDING);
    var process = ScriptedProcess.builder().output(bytesOf(ENCODED_RECORDING)).build();
    var levelFourTwo = VideoDecisionFixtures.encodeBuilder().level(42).codecString("avc1.64002A");

    var producer = producerFor(process, recording).videoDecision(levelFourTwo.build()).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.OUTPUT_MISMATCH);
    assertThat(failure.detail()).contains("avc1.64000A", "avc1.64002A");
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(sink.acceptedNames()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should fail the attempt as an output mismatch and deliver nothing when a copy's codec"
          + " string differs from the job's")
  void
      shouldFailTheAttemptAsAnOutputMismatchAndDeliverNothingWhenACopysCodecStringDiffersFromTheJobs() {
    var recording = recording("07-copy-start0.fmp4");
    var process = ScriptedProcess.builder().output(bytesOf(recording.file())).build();

    var producer =
        producerFor(process, recording)
            .videoDecision(new VideoDecision.Copy("avc1.640028"))
            .start();

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.OUTPUT_MISMATCH);
    assertThat(sink.acceptedNames()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should fail the attempt as an output mismatch when an encode's output is not 8-bit 4:2:0")
  void shouldFailTheAttemptAsAnOutputMismatchWhenAnEncodesOutputIsNotEightBitFourTwoZero() {
    var recording = recording("07-copy-start0.fmp4");
    var process = ScriptedProcess.builder().output(bytesOf(recording.file())).build();
    var highFourFourFour =
        VideoDecisionFixtures.encodeBuilder().codecString(recording.videoCodecString());

    var producer = producerFor(process, recording).videoDecision(highFourFourFour.build()).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.OUTPUT_MISMATCH);
    assertThat(failure.detail()).contains("4:4:4");
    assertThat(sink.acceptedNames()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "10-bit 4:2:0, 6E000A, FDFAFA00",
    "10-bit 4:2:2, 7A000A, FEFAFA00",
    "8-bit 4:0:0, 64000A, FCF8F800"
  })
  @DisplayName(
      "Should name the declared picture format in the output mismatch when an encode's output is"
          + " not 8-bit 4:2:0")
  void
      shouldNameTheDeclaredPictureFormatInTheOutputMismatchWhenAnEncodesOutputIsNotEightBitFourTwoZero(
          String pictureFormat, String profileConstraintsAndLevel, String formatExtension) {
    var avcC =
        IsoBoxes.box(
            "avcC",
            HexFormat.of()
                .parseHex(
                    "01"
                        + profileConstraintsAndLevel
                        + "FFE100026742010002"
                        + "68CE"
                        + formatExtension));
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(),
            IsoBoxes.moov(
                IsoBoxes.Track.video()
                    .sampleEntry(IsoBoxes.box("avc1", new byte[78], avcC))
                    .build()));
    var process = ScriptedProcess.builder().output(output).build();
    var encode =
        VideoDecisionFixtures.encodeBuilder().codecString("avc1." + profileConstraintsAndLevel);

    var producer =
        producerLaunching(process).periodSeconds(6).videoDecision(encode.build()).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.OUTPUT_MISMATCH);
    assertThat(failure.detail()).contains("declares " + pictureFormat + " where");
    assertThat(sink.acceptedNames()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should deliver a copy's initialization segment whatever its picture format when its codec"
          + " string matches the job's")
  void
      shouldDeliverACopysInitializationSegmentWhateverItsPictureFormatWhenItsCodecStringMatchesTheJobs() {
    var recording = recording("07-copy-start0.fmp4");
    var process = ScriptedProcess.builder().output(bytesOf(recording.file())).build();

    var producer = producerFor(process, recording).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.acceptedNames()).first().isEqualTo("init.mp4");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"13-x265-cfr.fmp4", "09-svtav1-vfr.fmp4", ENCODED_RECORDING})
  @ResourceLock(Resources.LOCALE)
  @DisplayName(
      "Should deliver every segment of an encode that matches the job when the default format"
          + " locale writes other digits")
  void
      shouldDeliverEverySegmentOfAnEncodeThatMatchesTheJobWhenTheDefaultFormatLocaleWritesOtherDigits(
          String file) {
    var recording = recording(file);
    var process = ScriptedProcess.builder().output(bytesOf(file)).build();
    var formatLocale = Locale.getDefault(Locale.Category.FORMAT);
    Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"));
    try {
      var producer = producerFor(process, recording).start();

      assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
      assertThat(sink.acceptedNames()).first().isEqualTo("init.mp4");
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, formatLocale);
    }
  }

  @Test
  @DisplayName(
      "Should fail the attempt as an output mismatch when the initialization segment's video"
          + " codec configuration cannot be read")
  void
      shouldFailTheAttemptAsAnOutputMismatchWhenTheInitializationSegmentsVideoCodecConfigurationCannotBeRead() {
    var emptyHvcc = IsoBoxes.box("hvc1", new byte[78], IsoBoxes.box("hvcC"));
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(), IsoBoxes.moov(IsoBoxes.Track.video().sampleEntry(emptyHvcc).build()));
    var process = ScriptedProcess.builder().output(output).build();

    var producer =
        producerLaunching(process)
            .periodSeconds(6)
            .videoDecision(new VideoDecision.Copy("hvc1.1.6.L93.90"))
            .start();

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.OUTPUT_MISMATCH);
    assertThat(sink.acceptedNames()).isEmpty();
  }

  // Records that declare the copy's codec string but none of the parameter sets that their sample
  // entry promises, as FFmpeg writes them when the source carries its parameter sets only in its
  // samples.
  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "hvc1, hvcC, 0101600000009000000000005AF000FCFDF8F800000F00, hvc1.1.6.L90.90",
    "avc1, avcC, 0142C00AFFE000, avc1.42C00A"
  })
  @DisplayName(
      "Should fail the attempt as an output mismatch when the record holds none of the parameter"
          + " sets that its sample entry promises")
  void
      shouldFailTheAttemptAsAnOutputMismatchWhenTheRecordHoldsNoneOfTheParameterSetsThatItsSampleEntryPromises(
          String sampleEntryType, String recordType, String recordHex, String codecString) {
    var sampleEntry =
        IsoBoxes.box(
            sampleEntryType,
            new byte[78],
            IsoBoxes.box(recordType, HexFormat.of().parseHex(recordHex)));
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(),
            IsoBoxes.moov(IsoBoxes.Track.video().sampleEntry(sampleEntry).build()));
    var process = ScriptedProcess.builder().output(output).build();

    var producer =
        producerLaunching(process)
            .periodSeconds(6)
            .videoDecision(new VideoDecision.Copy(codecString))
            .start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.OUTPUT_MISMATCH);
    assertThat(failure.detail()).contains("parameter sets");
    assertThat(sink.acceptedNames()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should fail the attempt as an output mismatch when the record ends inside a parameter set")
  void shouldFailTheAttemptAsAnOutputMismatchWhenTheRecordEndsInsideAParameterSet() {
    var truncatedArray =
        HexFormat.of().parseHex("0101600000009000000000005AF000FCFDF8F800000F01A0000100094001");
    var sampleEntry = IsoBoxes.box("hvc1", new byte[78], IsoBoxes.box("hvcC", truncatedArray));
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(),
            IsoBoxes.moov(IsoBoxes.Track.video().sampleEntry(sampleEntry).build()));
    var process = ScriptedProcess.builder().output(output).build();

    var producer =
        producerLaunching(process)
            .periodSeconds(6)
            .videoDecision(new VideoDecision.Copy("hvc1.1.6.L90.90"))
            .start();

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.OUTPUT_MISMATCH);
    assertThat(sink.acceptedNames()).isEmpty();
  }

  // FFmpeg copies every SEI message of the first sample into the record when it builds the record
  // from the samples, so two attempts of one copy can write different records.
  @ParameterizedTest(name = "NAL unit type {0}")
  @ValueSource(ints = {39, 40})
  @DisplayName(
      "Should deliver the hvc1 record without its SEI messages when FFmpeg writes SEI messages into"
          + " the record")
  void shouldDeliverTheHvc1RecordWithoutItsSeiMessagesWhenFfmpegWritesSeiMessagesIntoTheRecord(
      int seiNalUnitType) {
    var fragment = keyframeFragment(0, 1);
    var output =
        IsoBoxes.concat(
            hevcInitializationSegment(hvc1Entry(recordWithSeiArray(seiNalUnitType))), fragment);
    var process = ScriptedProcess.builder().output(output).build();

    var producer =
        producerOfOneSecondSegments(process)
            .videoDecision(new VideoDecision.Copy(HVC1_RECORD_CODEC_STRING))
            .start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.contentOf("init.mp4"))
        .isEqualTo(hevcInitializationSegment(hvc1Entry(HVC1_PARAMETER_SET_RECORD)));
  }

  @Test
  @DisplayName(
      "Should keep the 64-bit header of the box that holds the hvc1 record when the producer"
          + " removes the record's SEI messages")
  void
      shouldKeepThe64BitHeaderOfTheBoxThatHoldsTheHvc1RecordWhenTheProducerRemovesTheRecordsSeiMessages() {
    var largeEntry =
        IsoBoxes.largeSizeBox(
            "hvc1", IsoBoxes.concat(new byte[78], IsoBoxes.box("hvcC", recordWithSeiArray(39))));
    var fragment = keyframeFragment(0, 1);
    var process =
        ScriptedProcess.builder()
            .output(IsoBoxes.concat(hevcInitializationSegment(largeEntry), fragment))
            .build();

    var producer =
        producerOfOneSecondSegments(process)
            .videoDecision(new VideoDecision.Copy(HVC1_RECORD_CODEC_STRING))
            .start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    var expectedEntry =
        IsoBoxes.largeSizeBox(
            "hvc1", IsoBoxes.concat(new byte[78], IsoBoxes.box("hvcC", HVC1_PARAMETER_SET_RECORD)));
    assertThat(sink.contentOf("init.mp4")).isEqualTo(hevcInitializationSegment(expectedEntry));
  }

  // The audio sample entry declares fewer bytes than its own header, or more than its stsd holds.
  @ParameterizedTest(name = "{0} bytes")
  @ValueSource(longs = {4, 10_000})
  @DisplayName(
      "Should fail the attempt as malformed output when a box in another track declares a size that"
          + " its parent cannot hold")
  void
      shouldFailTheAttemptAsMalformedOutputWhenABoxInAnotherTrackDeclaresASizeThatItsParentCannotHold(
          long declaredBytes) {
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(),
            IsoBoxes.moov(
                IsoBoxes.Track.video().sampleEntry(hvc1Entry(HVC1_PARAMETER_SET_RECORD)).build(),
                IsoBoxes.Track.audio()
                    .sampleEntry(IsoBoxes.header(declaredBytes, "mp4a"))
                    .build()));
    var process = ScriptedProcess.builder().output(output).build();

    var producer =
        producerOfOneSecondSegments(process)
            .videoDecision(new VideoDecision.Copy(HVC1_RECORD_CODEC_STRING))
            .start();

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.MALFORMED_OUTPUT);
    assertThat(sink.acceptedNames()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should deliver the attempt when the reader cannot parse the codec configuration of an audio"
          + " track")
  void shouldDeliverTheAttemptWhenTheReaderCannotParseTheCodecConfigurationOfAnAudioTrack() {
    var misplacedDescriptor =
        IsoBoxes.box(
            "mp4a",
            new byte[28],
            IsoBoxes.fullBox("esds", 0, HexFormat.of().parseHex("0480808000")));
    var moov =
        IsoBoxes.moov(
            IsoBoxes.Track.video().build(),
            IsoBoxes.Track.audio().sampleEntry(misplacedDescriptor).build());
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(),
            moov,
            IsoBoxes.moof(
                IsoBoxes.videoTraf()
                    .baseMediaDecodeTime(0L)
                    .firstSampleFlags(IsoBoxes.SYNC_SAMPLE_FLAGS)
                    .sampleDurations(List.of(1001))
                    .build()),
            IsoBoxes.mdat(1));
    var process = ScriptedProcess.builder().output(output).build();

    var producer = producerOfOneSecondSegments(process).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.acceptedNames()).containsExactly("init.mp4", "segment0.m4s");
  }

  @Test
  @DisplayName(
      "Should fail the attempt as malformed output when the video track's stsd holds no sample"
          + " entry")
  void shouldFailTheAttemptAsMalformedOutputWhenTheVideoTracksStsdHoldsNoSampleEntry() {
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(),
            IsoBoxes.moov(IsoBoxes.Track.video().sampleEntry(new byte[0]).build()));
    var process = ScriptedProcess.builder().output(output).build();

    var producer = producerLaunching(process).periodSeconds(6).start();

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.MALFORMED_OUTPUT);
    assertThat(sink.acceptedNames()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should fail the attempt as an output mismatch when the initialization segment declares no"
          + " video track")
  void shouldFailTheAttemptAsAnOutputMismatchWhenTheInitializationSegmentDeclaresNoVideoTrack() {
    var output = IsoBoxes.concat(IsoBoxes.ftyp(), IsoBoxes.moov(IsoBoxes.Track.audio().build()));
    var process = ScriptedProcess.builder().output(output).build();

    var producer = producerLaunching(process).periodSeconds(6).start();

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.OUTPUT_MISMATCH);
    assertThat(sink.acceptedNames()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("outputsWithoutAMediaSegment")
  @DisplayName("Should fail the attempt when FFmpeg exits cleanly without a media segment")
  void shouldFailTheAttemptWhenFfmpegExitsCleanlyWithoutAMediaSegment(
      String description, byte[] output, Recording recording) {
    var process = ScriptedProcess.builder().output(output).build();

    var producer = producerFor(process, recording).start();

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.NO_MEDIA_SEGMENT);
    assertThat(sink.accepted()).noneMatch(accepted -> accepted.name().startsWith("segment"));
  }

  static Stream<Arguments> outputsWithoutAMediaSegment() {
    var encoded = recording(ENCODED_RECORDING);
    var replacementAttempt = recording("07-copy-seek30.fmp4");
    var prerollEnd =
        replacementAttempt.initializationSegment().byteLength()
            + replacementAttempt.discardedPreroll().stream()
                .mapToLong(SegmentSummary::byteLength)
                .sum();
    return Stream.of(
        Arguments.of("no output", new byte[0], encoded),
        Arguments.of(
            "an initialization segment alone",
            Arrays.copyOf(bytesOf(ENCODED_RECORDING), encoded.initializationSegment().byteLength()),
            encoded),
        Arguments.of(
            "preroll alone",
            Arrays.copyOf(bytesOf(replacementAttempt.file()), Math.toIntExact(prerollEnd)),
            replacementAttempt));
  }

  @Test
  @DisplayName(
      "Should fail the attempt and deliver nothing further when the sink does not accept a"
          + " segment")
  void shouldFailTheAttemptAndDeliverNothingFurtherWhenTheSinkDoesNotAcceptASegment() {
    var recording = recording(ENCODED_RECORDING);
    var process = ScriptedProcess.builder().output(bytesOf(ENCODED_RECORDING)).build();
    sink.refusing(3);

    var producer = producerFor(process, recording).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.SEGMENT_NOT_ACCEPTED);
    assertThat(failure.detail()).contains("segment2.m4s");
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4", "segment0.m4s", "segment1.m4s");
  }

  @Test
  @DisplayName("Should fail the attempt and end FFmpeg when its output cannot be read")
  void shouldFailTheAttemptAndEndFfmpegWhenItsOutputCannotBeRead() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .failReadAfter(recording.initializationSegment().byteLength() + 100)
            .build();

    var producer = producerFor(process, recording).start();

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.OUTPUT_UNREADABLE);
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
  }

  @Test
  @DisplayName("Should fail the attempt and end FFmpeg when reading its output throws unexpectedly")
  void shouldFailTheAttemptAndEndFfmpegWhenReadingItsOutputThrowsUnexpectedly() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .failReadAfter(recording.initializationSegment().byteLength() + 100)
            .failReadWith(new IllegalStateException("scripted defect"))
            .build();

    var producer = producerFor(process, recording).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.UNEXPECTED_ERROR);
    assertThat(failure.detail()).contains("scripted defect");
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
  }

  @Test
  @DisplayName("Should fail the attempt and end FFmpeg when the sink throws an error")
  void shouldFailTheAttemptAndEndFfmpegWhenTheSinkThrowsAnError() {
    var recording = recording(ENCODED_RECORDING);
    var process = ScriptedProcess.builder().output(bytesOf(ENCODED_RECORDING)).build();
    sink.failing(2, new OutOfMemoryError("scripted exhaustion"));

    var producer = producerFor(process, recording).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.UNEXPECTED_ERROR);
    assertThat(failure.detail()).contains("scripted exhaustion");
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4", "segment0.m4s");
  }

  @Test
  @DisplayName(
      "Should settle only the stop and deliver nothing further when stopped while FFmpeg is"
          + " writing")
  void shouldSettleOnlyTheStopAndDeliverNothingFurtherWhenStoppedWhileFfmpegIsWriting() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(truncated(bytesOf(ENCODED_RECORDING)))
            .pauseAfter(insideThirdMediaSegment(recording))
            .resumesOnQuit(true)
            .exitCode(255)
            .build();
    var producer = producerFor(process, recording).start();
    awaiting().until(process::hasReachedPause);

    producer.stop();

    assertThat(process.stdinText()).isEqualTo("q");
    assertThat(process.hasReadToEndOfOutput()).isTrue();
    assertThat(process.wasDestroyedForcibly()).isFalse();
    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
    assertThat(sink.acceptedNames()).containsExactly("init.mp4", "segment0.m4s");
  }

  @Test
  @DisplayName("Should let FFmpeg finish quitting when its output breaks after a stop")
  void shouldLetFfmpegFinishQuittingWhenItsOutputBreaksAfterAStop() {
    var recording = recording(ENCODED_RECORDING);
    var recorded = bytesOf(ENCODED_RECORDING);
    var firstTwoSegmentsEnd = insideThirdMediaSegment(recording) - 10;
    var output =
        IsoBoxes.concat(
            Arrays.copyOf(recorded, firstTwoSegmentsEnd),
            IsoBoxes.box("free", new byte[16]),
            Arrays.copyOfRange(recorded, firstTwoSegmentsEnd, recorded.length));
    var process =
        ScriptedProcess.builder()
            .output(output)
            .pauseAfter(firstTwoSegmentsEnd)
            .resumesOnQuit(true)
            .build();
    var producer = producerFor(process, recording).start();
    awaiting().until(process::hasReachedPause);

    producer.stop();

    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
    assertThat(process.hasReadToEndOfOutput()).isTrue();
    assertThat(process.wasDestroyedForcibly()).isFalse();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4", "segment0.m4s");
  }

  @Test
  @DisplayName("Should settle only the stop when the output cannot be read after a stop")
  void shouldSettleOnlyTheStopWhenTheOutputCannotBeReadAfterAStop() {
    var recording = recording(ENCODED_RECORDING);
    var pause = insideThirdMediaSegment(recording);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .pauseAfter(pause)
            .failReadAfter(pause + 100)
            .resumesOnQuit(true)
            .build();
    var producer = producerFor(process, recording).gracePeriod(Duration.ofMillis(100)).start();
    awaiting().until(process::hasReachedPause);

    producer.stop();

    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
    assertThat(process.wasDestroyedForcibly()).isTrue();
  }

  @Test
  @DisplayName(
      "Should destroy FFmpeg after the grace period when stopped and FFmpeg ignores the quit")
  void shouldDestroyFfmpegAfterTheGracePeriodWhenStoppedAndFfmpegIgnoresTheQuit() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .pauseAfter(insideThirdMediaSegment(recording))
            .build();
    var producer = producerFor(process, recording).gracePeriod(Duration.ofMillis(100)).start();
    awaiting().until(process::hasReachedPause);

    producer.stop();

    assertThat(process.stdinText()).isEqualTo("q");
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
    assertThat(sink.acceptedNames()).containsExactly("init.mp4", "segment0.m4s");
  }

  @Test
  @DisplayName("Should destroy FFmpeg at once when the thread stopping the attempt is interrupted")
  void shouldDestroyFfmpegAtOnceWhenTheThreadStoppingTheAttemptIsInterrupted() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .pauseAfter(insideThirdMediaSegment(recording))
            .build();
    var producer = producerFor(process, recording).gracePeriod(Duration.ofMinutes(10)).start();
    awaiting().until(process::hasReachedPause);
    var stopping = Thread.ofVirtual().start(producer::stop);
    awaiting().until(() -> process.stdinText().equals("q"));

    stopping.interrupt();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Stopped());
    assertThat(process.wasDestroyedForcibly()).isTrue();
  }

  @Test
  @DisplayName(
      "Should cancel the delivery in flight and let FFmpeg exit when stopped while the sink holds a"
          + " segment")
  void shouldCancelTheDeliveryInFlightAndLetFfmpegExitWhenStoppedWhileTheSinkHoldsASegment()
      throws InterruptedException {
    var recording = recording(ENCODED_RECORDING);
    var output = bytesOf(ENCODED_RECORDING);
    // FFmpeg writes its last byte only once asked to quit and exits only once the reader has read
    // everything it wrote, as FFmpeg blocked on a full pipe does.
    var process =
        ScriptedProcess.builder()
            .output(output)
            .pauseAfter(output.length - 1)
            .resumesOnQuit(true)
            .build();
    sink.holding(2);
    var producer = producerFor(process, recording).gracePeriod(Duration.ofMinutes(10)).start();
    awaiting().until(sink::isHolding);

    var stopping = Thread.ofVirtual().start(producer::stop);

    assertThat(stopping.join(OUTCOME_LIMIT)).isTrue();
    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
    assertThat(process.stdinText()).isEqualTo("q");
    assertThat(process.hasReadToEndOfOutput()).isTrue();
    assertThat(process.wasDestroyedForcibly()).isFalse();
    assertThat(sink.wasCancelled()).isTrue();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4", "segment0.m4s");
  }

  @RepeatedTest(50)
  @DisplayName(
      "Should settle only the stop when FFmpeg exits after its output ended while a stop awaits"
          + " the exit")
  void shouldSettleOnlyTheStopWhenFfmpegExitsAfterItsOutputEndedWhileAStopAwaitsTheExit()
      throws InterruptedException {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var producer = producerFor(process, recording).start();
    awaiting().until(process::hasReadToEndOfOutput);
    var stopping = Thread.ofVirtual().start(producer::stop);
    awaiting().until(() -> process.stdinText().equals("q"));

    process.exit();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Stopped());
    assertThat(stopping.join(OUTCOME_LIMIT)).isTrue();
  }

  @Test
  @DisplayName("Should keep the completed outcome when stopped after the attempt completed")
  void shouldKeepTheCompletedOutcomeWhenStoppedAfterTheAttemptCompleted() {
    var recording = recording(ENCODED_RECORDING);
    var process = ScriptedProcess.builder().output(bytesOf(ENCODED_RECORDING)).build();
    var producer = producerFor(process, recording).start();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT);

    producer.stop();

    assertThat(process.stdinText()).isEmpty();
    assertThat(producer.outcome()).isCompletedWithValue(new Completed());
  }

  @Test
  @DisplayName("Should ask FFmpeg to quit once when stopped twice")
  void shouldAskFfmpegToQuitOnceWhenStoppedTwice() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .pauseAfter(insideThirdMediaSegment(recording))
            .resumesOnQuit(true)
            .build();
    var producer = producerFor(process, recording).start();
    awaiting().until(process::hasReachedPause);

    producer.stop();
    producer.stop();

    assertThat(process.stdinText()).isEqualTo("q");
    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
  }

  @Test
  @DisplayName(
      "Should record the stop at once and settle it only once FFmpeg exits when a stop is"
          + " requested")
  void shouldRecordTheStopAtOnceAndSettleItOnlyOnceFfmpegExitsWhenAStopIsRequested()
      throws InterruptedException {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    sink.holding(2);
    var producer = producerFor(process, recording).gracePeriod(Duration.ofMinutes(10)).start();
    awaiting().until(sink::isHolding);

    var requesting = Thread.ofVirtual().start(producer::requestStop);

    assertThat(requesting.join(OUTCOME_LIMIT)).isTrue();
    awaiting().until(() -> sink.wasCancelled() && process.hasReadToEndOfOutput());
    assertThat(producer.outcome()).isNotDone();
    process.exit();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Stopped());
    assertThat(process.stdinText()).isEqualTo("q");
    assertThat(process.wasDestroyedForcibly()).isFalse();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4", "segment0.m4s");
  }

  @Test
  @DisplayName(
      "Should settle the stop and end FFmpeg when cancelling the delivery in flight throws"
          + " unexpectedly")
  void shouldSettleTheStopAndEndFfmpegWhenCancellingTheDeliveryInFlightThrowsUnexpectedly() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var held = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    SegmentSink brokenCancellation =
        (segment, cancellation) -> {
          if (!segment.name().equals("segment0.m4s")) {
            return;
          }

          cancellation.onCancel(
              () -> {
                throw new IllegalStateException("the cancellation broke");
              });
          held.countDown();
          awaitLatch(release);
        };
    var producer =
        producerFor(process, recording)
            .sink(brokenCancellation)
            .gracePeriod(Duration.ofMinutes(10))
            .start();
    awaitLatch(held);

    producer.requestStop();

    awaiting().until(process::wasDestroyedForcibly);
    release.countDown();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Stopped());
  }

  @Test
  @DisplayName(
      "Should keep the recorded failure without asking FFmpeg to quit when stopped before the"
          + " failure settles")
  void shouldKeepTheRecordedFailureWithoutAskingFfmpegToQuitWhenStoppedBeforeTheFailureSettles()
      throws InterruptedException {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .lingersAfterKill(true)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    sink.refusing(1);
    var producer = producerFor(process, recording).start();
    awaiting().until(process::wasDestroyedForcibly);

    var stopping = Thread.ofVirtual().start(producer::stop);

    assertThat(stopping.join(Duration.ofMillis(200))).isFalse();
    process.exit();
    assertThat(stopping.join(OUTCOME_LIMIT)).isTrue();
    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.SEGMENT_NOT_ACCEPTED);
    assertThat(process.stdinText()).isEmpty();
  }

  @Test
  @DisplayName("Should settle whichever of a stop and a failure is recorded first when they race")
  void shouldSettleWhicheverOfAStopAndAFailureIsRecordedFirstWhenTheyRace() throws Exception {
    var recording = recording(ENCODED_RECORDING);

    for (var iteration = 0; iteration < RACE_ITERATIONS; iteration++) {
      var process =
          ScriptedProcess.builder()
              .output(bytesOf(ENCODED_RECORDING))
              .pauseAfter(insideThirdMediaSegment(recording))
              .resumesOnQuit(true)
              .build();
      var start = new CyclicBarrier(2);
      var producer =
          producerFor(process, recording).sink(refusingTheFirstMediaSegmentAt(start)).start();

      awaitStart(start);
      producer.stop();

      var outcome = producer.outcome().get(OUTCOME_LIMIT.toSeconds(), TimeUnit.SECONDS);
      var stoppedFirst = outcome instanceof Stopped;
      assertThat(process.stdinText().equals("q"))
          .as("the stop asked FFmpeg to quit")
          .isEqualTo(stoppedFirst);
      assertThat(process.wasDestroyedForcibly())
          .as("the failure destroyed FFmpeg")
          .isEqualTo(!stoppedFirst);
      if (!stoppedFirst) {
        assertThat(outcome)
            .asInstanceOf(InstanceOfAssertFactories.type(Failed.class))
            .extracting(Failed::reason)
            .isEqualTo(ProducerFailure.SEGMENT_NOT_ACCEPTED);
      }
    }
  }

  // A sink that refuses the first media segment once the race's other side is ready.
  private static SegmentSink refusingTheFirstMediaSegmentAt(CyclicBarrier start) {
    return (segment, _) -> {
      if (!segment.name().equals("segment0.m4s")) {
        return;
      }

      awaitStart(start);
      throw new IllegalStateException("the server refused " + segment.name());
    };
  }

  @Test
  @DisplayName(
      "Should fail an encoded attempt as a short video sample when a video sample lasts less than"
          + " half a frame")
  void shouldFailAnEncodedAttemptAsAShortVideoSampleWhenAVideoSampleLastsLessThanHalfAFrame() {
    var process = ScriptedProcess.builder().output(outputWithAOneTickVideoSample()).build();

    var producer =
        producerOfOneSecondSegments(process)
            .encodedFrameRate(OptionalDouble.of(24_000.0 / 1001))
            .start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.SHORT_VIDEO_SAMPLE);
    assertThat(failure.detail()).contains("sample of 1 ticks");
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
  }

  @Test
  @DisplayName("Should deliver a short video sample when the attempt copies the video")
  void shouldDeliverAShortVideoSampleWhenTheAttemptCopiesTheVideo() {
    var process = ScriptedProcess.builder().output(outputWithAOneTickVideoSample()).build();

    var producer = producerOfOneSecondSegments(process).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.acceptedNames())
        .containsExactly("init.mp4", "segment0.m4s", "segment1.m4s", "segment2.m4s");
  }

  @Test
  @DisplayName(
      "Should complete an encoded attempt when its shortest video sample lasts half a frame")
  void shouldCompleteAnEncodedAttemptWhenItsShortestVideoSampleLastsHalfAFrame() {
    var output =
        IsoBoxes.oneSecondKeyframeFragments(
            List.of(List.of(1001, 1001), List.of(1001, 501, 1001), List.of(1001)));
    var process = ScriptedProcess.builder().output(output).build();

    var producer =
        producerOfOneSecondSegments(process)
            .encodedFrameRate(OptionalDouble.of(24_000.0 / 1001))
            .start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
  }

  @ParameterizedTest(name = "FFmpeg ignores termination: {0}")
  @ValueSource(booleans = {false, true})
  @DisplayName(
      "Should fail the attempt as an encoder stall when FFmpeg writes nothing while the producer"
          + " reads")
  void shouldFailTheAttemptAsAnEncoderStallWhenFfmpegWritesNothingWhileTheProducerReads(
      boolean ignoresTermination) {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .pauseAfter(insideThirdMediaSegment(recording))
            .ignoresTermination(ignoresTermination)
            .build();

    var producer =
        producerFor(process, recording)
            .stallTimeout(Duration.ofMillis(200))
            .gracePeriod(Duration.ofMillis(200))
            .start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.ENCODER_STALLED);
    assertThat(process.wasTerminated()).isTrue();
    assertThat(process.wasDestroyedForcibly()).isEqualTo(ignoresTermination);
    assertThat(sink.acceptedNames()).containsExactly("init.mp4", "segment0.m4s");
  }

  @Test
  @DisplayName(
      "Should fail the attempt as an encoder stall when FFmpeg writes nothing while the producer"
          + " discards the preroll of an encoded replacement attempt")
  void
      shouldFailTheAttemptAsAnEncoderStallWhenFfmpegWritesNothingWhileTheProducerDiscardsThePrerollOfAnEncodedReplacementAttempt() {
    var recording = recording("09-svtav1-vfr-seek30.fmp4");
    var recorded = bytesOf(recording.file());
    var process =
        ScriptedProcess.builder()
            .output(recorded)
            .pauseAfter(insideSecondPrerollFragment(recording, recorded))
            .build();

    var producer =
        producerFor(process, recording)
            .stallTimeout(Duration.ofMillis(200))
            .gracePeriod(Duration.ofMillis(200))
            .start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.ENCODER_STALLED);
    assertThat(process.wasTerminated()).isTrue();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
  }

  @Test
  @DisplayName(
      "Should cancel the delivery in flight before FFmpeg exits when FFmpeg stalls while the next"
          + " segment assembles")
  void
      shouldCancelTheDeliveryInFlightBeforeFfmpegExitsWhenFfmpegStallsWhileTheNextSegmentAssembles() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .pauseAfter(insideThirdMediaSegment(recording))
            .ignoresTermination(true)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    sink.holding(1);
    var producer =
        producerFor(process, recording)
            .stallTimeout(Duration.ofMillis(200))
            .gracePeriod(Duration.ofMinutes(10))
            .start();

    awaiting().until(sink::wasCancelled);

    assertThat(process.wasTerminated()).isTrue();
    assertThat(process.isAlive()).isTrue();
    process.exit();
    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.ENCODER_STALLED);
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
  }

  @Test
  @DisplayName(
      "Should cancel the delivery in flight before FFmpeg exits when reading its output throws"
          + " unexpectedly")
  void shouldCancelTheDeliveryInFlightBeforeFfmpegExitsWhenReadingItsOutputThrowsUnexpectedly() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .failReadAfter(insideThirdMediaSegment(recording))
            .failReadWith(new IllegalStateException("scripted defect"))
            .lingersAfterKill(true)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    sink.holding(1);
    var producer = producerFor(process, recording).start();

    awaiting().until(sink::wasCancelled);

    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(process.isAlive()).isTrue();
    process.exit();
    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.UNEXPECTED_ERROR);
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
  }

  @Test
  @DisplayName("Should not fail the attempt as an encoder stall while a segment awaits acceptance")
  void shouldNotFailTheAttemptAsAnEncoderStallWhileASegmentAwaitsAcceptance() {
    var recording = recording(ENCODED_RECORDING);
    var process = ScriptedProcess.builder().output(bytesOf(ENCODED_RECORDING)).build();
    sink.holding(1);
    var producer = producerFor(process, recording).stallTimeout(Duration.ofMillis(100)).start();
    awaiting().until(sink::isHolding);

    await()
        .during(Duration.ofMillis(500))
        .atMost(OUTCOME_LIMIT)
        .until(() -> !producer.outcome().isDone() && !process.wasTerminated());
    sink.release();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
  }

  @Test
  @DisplayName(
      "Should complete the attempt when FFmpeg exits later than the stall timeout after its output"
          + " ends")
  void shouldCompleteTheAttemptWhenFfmpegExitsLaterThanTheStallTimeoutAfterItsOutputEnds() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var producer = producerFor(process, recording).stallTimeout(Duration.ofMillis(100)).start();
    awaiting().until(process::hasReadToEndOfOutput);

    await()
        .during(Duration.ofMillis(500))
        .atMost(OUTCOME_LIMIT)
        .until(() -> !producer.outcome().isDone() && !process.wasTerminated());
    process.exit();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
  }

  @Test
  @DisplayName(
      "Should fail the attempt and terminate FFmpeg when FFmpeg does not exit within the grace"
          + " period after its output ends")
  void
      shouldFailTheAttemptAndTerminateFfmpegWhenFfmpegDoesNotExitWithinTheGracePeriodAfterItsOutputEnds() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();

    var producer = producerFor(process, recording).gracePeriod(Duration.ofMillis(100)).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.PROCESS_DID_NOT_EXIT);
    assertThat(failure.detail())
        .contains("after its output ended and its last segment was accepted");
    assertThat(process.wasTerminated()).isTrue();
    assertThat(process.wasDestroyedForcibly()).isFalse();
    assertDeliveredInOrder(recording);
  }

  @Test
  @DisplayName(
      "Should fail the attempt as a truncated output and terminate FFmpeg when FFmpeg does not"
          + " exit within the grace period after its output ends inside a box")
  void
      shouldFailTheAttemptAsATruncatedOutputAndTerminateFfmpegWhenFfmpegDoesNotExitWithinTheGracePeriodAfterItsOutputEndsInsideABox() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(truncated(bytesOf(ENCODED_RECORDING)))
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();

    var producer = producerFor(process, recording).gracePeriod(Duration.ofMillis(100)).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.TRUNCATED_OUTPUT);
    assertThat(failure.detail()).contains("END_OF_FILE_IN_BOX_BODY");
    assertThat(process.wasTerminated()).isTrue();
    assertThat(process.wasDestroyedForcibly()).isFalse();
  }

  @Test
  @DisplayName(
      "Should destroy FFmpeg and settle the failure only once it has exited when FFmpeg ignores"
          + " termination after its output ends")
  void
      shouldDestroyFfmpegAndSettleTheFailureOnlyOnceItHasExitedWhenFfmpegIgnoresTerminationAfterItsOutputEnds() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .ignoresTermination(true)
            .lingersAfterKill(true)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();

    var producer = producerFor(process, recording).gracePeriod(Duration.ofMillis(100)).start();

    awaiting().until(process::wasDestroyedForcibly);
    assertThat(process.wasTerminated()).isTrue();
    assertThat(producer.outcome()).isNotDone();
    process.exit();
    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.PROCESS_DID_NOT_EXIT);
  }

  @Test
  @DisplayName(
      "Should settle only the stop without terminating FFmpeg when a stop arrives while FFmpeg"
          + " outlives the grace period after its output ended")
  void
      shouldSettleOnlyTheStopWithoutTerminatingFfmpegWhenAStopArrivesWhileFfmpegOutlivesTheGracePeriodAfterItsOutputEnded() {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .lingersAfterKill(true)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var producer = producerFor(process, recording).gracePeriod(Duration.ofMillis(200)).start();
    awaiting().until(process::hasReadToEndOfOutput);

    producer.requestStop();

    // The exit wait that began when the output ended expires before the stop's forced kill.
    awaiting().until(process::wasDestroyedForcibly);
    await()
        .atMost(OUTCOME_LIMIT)
        .during(Duration.ofMillis(200))
        .until(() -> !process.wasTerminated());
    process.exit();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Stopped());
    assertThat(process.stdinText()).isEqualTo("q");
  }

  @Test
  @DisplayName("Should not fail the attempt as an encoder stall once it is stopped")
  void shouldNotFailTheAttemptAsAnEncoderStallOnceItIsStopped() throws InterruptedException {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .pauseAfter(insideThirdMediaSegment(recording))
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var producer =
        producerFor(process, recording)
            .stallTimeout(Duration.ofMillis(300))
            .gracePeriod(Duration.ofMinutes(10))
            .start();
    awaiting().until(process::hasReachedPause);
    var stopping = Thread.ofVirtual().start(producer::stop);
    awaiting().until(() -> process.stdinText().equals("q"));

    await()
        .during(Duration.ofMillis(600))
        .atMost(OUTCOME_LIMIT)
        .until(() -> !process.wasTerminated() && !process.wasDestroyedForcibly());
    process.exit();

    assertThat(stopping.join(OUTCOME_LIMIT)).isTrue();
    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
  }

  @Test
  @DisplayName("Should settle the stop only once FFmpeg has exited after a forced kill")
  void shouldSettleTheStopOnlyOnceFfmpegHasExitedAfterAForcedKill() throws InterruptedException {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .pauseAfter(insideThirdMediaSegment(recording))
            .lingersAfterKill(true)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var producer = producerFor(process, recording).gracePeriod(Duration.ofMillis(100)).start();
    awaiting().until(process::hasReachedPause);

    var stopping = Thread.ofVirtual().start(producer::stop);

    awaiting().until(process::wasDestroyedForcibly);
    assertThat(stopping.join(Duration.ofMillis(200))).isFalse();
    assertThat(producer.outcome()).isNotDone();
    process.exit();
    assertThat(stopping.join(OUTCOME_LIMIT)).isTrue();
    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
  }

  @Test
  @DisplayName(
      "Should return from a second stop only after the first settles when stopped twice"
          + " concurrently")
  void shouldReturnFromASecondStopOnlyAfterTheFirstSettlesWhenStoppedTwiceConcurrently()
      throws InterruptedException {
    var recording = recording(ENCODED_RECORDING);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .pauseAfter(insideThirdMediaSegment(recording))
            .build();
    var producer = producerFor(process, recording).gracePeriod(Duration.ofMinutes(10)).start();
    awaiting().until(process::hasReachedPause);
    var firstStop = Thread.ofVirtual().start(producer::stop);
    awaiting().until(() -> process.stdinText().equals("q"));
    var secondStop = Thread.ofVirtual().start(producer::stop);

    assertThat(secondStop.join(Duration.ofMillis(200))).isFalse();
    firstStop.interrupt();

    assertThat(secondStop.join(OUTCOME_LIMIT)).isTrue();
    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
    assertThat(process.stdinText()).isEqualTo("q");
  }

  @Test
  @DisplayName(
      "Should settle the stop when stopped while the sink holds the last segment of an exited"
          + " FFmpeg")
  void shouldSettleTheStopWhenStoppedWhileTheSinkHoldsTheLastSegmentOfAnExitedFfmpeg() {
    var recording = recording(ENCODED_RECORDING);
    var process = ScriptedProcess.builder().output(bytesOf(ENCODED_RECORDING)).build();
    sink.holding(recording.segments().size());
    var producer = producerFor(process, recording).start();
    awaiting().until(sink::isHolding);

    producer.stop();
    sink.release();

    assertThat(process.stdinText()).isEmpty();
    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
  }

  private static void awaitLatch(CountDownLatch latch) {
    try {
      assertThat(latch.await(OUTCOME_LIMIT.toSeconds(), TimeUnit.SECONDS))
          .as("the latch opened")
          .isTrue();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("interrupted while awaiting a latch", e);
    }
  }

  // The reader takes FFmpeg's output up to this offset and no further, so FFmpeg blocks on the
  // pipe.
  private static void assertReaderStopsAt(ScriptedProcess process, int offset) {
    awaiting().until(() -> process.bytesTaken() == offset);
    await()
        .during(Duration.ofMillis(200))
        .atMost(OUTCOME_LIMIT)
        .until(() -> process.bytesTaken() == offset);
  }

  // Stops an attempt of nearlyCappedSegments() that has filled its own budget while its sink holds
  // its first media segment past the cancellation, as an upload slow to abandon would.
  private HeldStop stopHoldingItsCancelledDelivery(SegmentMemoryBudget workerBudget) {
    var process =
        ScriptedProcess.builder()
            .output(nearlyCappedSegments())
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var heldSink = new RecordingSegmentSink().holdingPastCancellation(1);
    var producer =
        producerOfOneSecondSegments(process)
            .sink(heldSink)
            .memoryBudget(workerBudget)
            .gracePeriod(Duration.ofMinutes(10))
            .start();
    awaiting().until(() -> process.bytesTaken() == NEARLY_CAPPED_BUDGET_STOP);
    producer.requestStop();
    awaiting().until(heldSink::wasCancelled);
    return new HeldStop(process, heldSink, producer);
  }

  // Starts one attempt per slot, each filling its own budget while its sink holds its first media
  // segment.
  private List<BurstAttempt> startBurst(BurstStart burst) {
    return IntStream.range(0, BURST_SLOTS).mapToObj(slot -> startAttempt(burst, slot)).toList();
  }

  private BurstAttempt startAttempt(BurstStart burst, int slot) {
    var process =
        ScriptedProcess.builder()
            .output(burst.output())
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var attemptSink = sinkHoldingTheFirstMediaSegment(burst, slot);
    var producer =
        producerOfOneSecondSegments(process)
            .sink(attemptSink)
            .memoryBudget(burst.budget())
            .gracePeriod(Duration.ofMinutes(10))
            .start();
    return BurstAttempt.builder().process(process).sink(attemptSink).producer(producer).build();
  }

  // Alternate attempts hold that delivery past its cancellation, as an upload slow to abandon
  // would, when a later burst stops them.
  private static RecordingSegmentSink sinkHoldingTheFirstMediaSegment(BurstStart burst, int slot) {
    var attemptSink = new RecordingSegmentSink();
    if ((burst.burst() + slot) % 2 == 0) {
      return attemptSink.holdingPastCancellation(1);
    }

    return attemptSink.holding(1);
  }

  // Stops the attempts on another thread while the next ones start, as a burst of seeks does.
  private static List<BurstAttempt> stopAndStartTogether(
      List<BurstAttempt> stopping, Supplier<List<BurstAttempt>> starting)
      throws InterruptedException {
    var start = new CyclicBarrier(2);
    var stops =
        Thread.ofVirtual()
            .start(
                () -> {
                  awaitStart(start);
                  stopping.forEach(BurstAttempt::stop);
                });
    awaitStart(start);
    var started = starting.get();
    assertThat(stops.join(OUTCOME_LIMIT)).isTrue();
    return started;
  }

  // Each reader takes FFmpeg's output up to its own budget while its first media segment awaits
  // acceptance.
  private static void awaitEachReaderAtItsBudget(List<BurstAttempt> attempts) {
    attempts.forEach(
        attempt ->
            awaiting().until(() -> attempt.process().bytesTaken() == NEARLY_CAPPED_BUDGET_STOP));
  }

  // Returns once no reader has taken a byte for a while, as when each waits for memory.
  private static void awaitReadersWaiting(List<BurstAttempt> attempts) {
    var lastTaken = new AtomicReference<List<Integer>>(List.of());
    await()
        .atMost(OUTCOME_LIMIT)
        .pollInterval(POLL_INTERVAL)
        .during(Duration.ofMillis(200))
        .until(
            () -> {
              var taken = attempts.stream().map(attempt -> attempt.process().bytesTaken()).toList();
              return taken.equals(lastTaken.getAndSet(taken));
            });
  }

  private static void endStoppedAttempts(List<BurstAttempt> stopped) {
    stopped.forEach(attempt -> attempt.process().exit());
    assertThat(stopped)
        .allSatisfy(
            attempt ->
                assertThat(attempt.producer().outcome())
                    .succeedsWithin(OUTCOME_LIMIT)
                    .isEqualTo(new Stopped()));
  }

  // A lower bound of what the attempts charge the worker's budget at the moment between reading
  // every reader's progress and reading any stop flag. An active attempt's reader holds every box
  // it has begun to read that no returned delivery carried; a stopped attempt holds only its
  // deliveries that have not returned. A flag still clear after that moment means the attempt was
  // active at it, and returns counted later only lower the bound.
  private static long chargedAtLeast(List<BurstAttempt> attempts, List<Box> layout) {
    var progress =
        List.copyOf(attempts).stream()
            .map(attempt -> new ReaderProgress(attempt, attempt.process().bytesTaken()))
            .toList();
    var charged = progress.stream().mapToLong(read -> read.chargedBefore(layout)).sum();
    var released =
        progress.stream()
            .mapToLong(read -> heldByDeliveries(read.attempt().sink().returnedNames(), layout))
            .sum();
    return charged - released;
  }

  // The bytes of FFmpeg's output that deliveries of these segments of nearlyCappedSegments() hold:
  // each rebuilt media segment views the bytes of the fragments it was assembled from.
  private static long heldByDeliveries(List<String> names, List<Box> layout) {
    var boxesOfEachDelivery =
        Map.of(
            "init.mp4", layout.subList(0, 2),
            "segment0.m4s", layout.subList(2, 8),
            "segment1.m4s", layout.subList(8, 14),
            "segment2.m4s", layout.subList(14, 16));
    return names.stream()
        .flatMap(name -> boxesOfEachDelivery.get(name).stream())
        .mapToLong(Box::size)
        .sum();
  }

  // Nothing the producer reserved in the worker's budget is still reserved, and it released
  // nothing it did not reserve: the whole capacity is reservable at once, and not one byte more.
  // The last delivery releases its bytes just after the outcome can settle, hence the wait.
  private static void assertReleasesEveryReservedByte(SegmentMemoryBudget workerBudget) {
    awaiting().until(() -> workerBudget.tryReserveAtOnce(ONE_SLOT_CAPACITY));
    assertThat(workerBudget.tryReserveAtOnce(1)).as("a byte beyond the capacity").isFalse();
    workerBudget.release(ONE_SLOT_CAPACITY);
  }

  // The heap that live objects occupy once a full collection has freed every unreachable one.
  private static long liveHeapBytes() {
    var memory = ManagementFactory.getMemoryMXBean();
    memory.gc();
    return memory.getHeapMemoryUsage().getUsed();
  }

  private static ConditionFactory awaiting() {
    return await().atMost(OUTCOME_LIMIT).pollInterval(POLL_INTERVAL);
  }

  // An encoded recording's producer checks its video samples against the frame rate it forced.
  private Producer.ProducerBuilder producerFor(ScriptedProcess process, Recording recording) {
    var encodedFrameRate = OptionalDouble.empty();
    if (recording.encoder().isPresent()) {
      encodedFrameRate = OptionalDouble.of(recording.source().videoFrameRate());
    }

    return producerLaunching(process)
        .encodedFrameRate(encodedFrameRate)
        .videoDecision(VideoDecisionFixtures.forRecording(recording))
        .periodSeconds(recording.period())
        .startSequenceNumber(recording.startSequenceNumber());
  }

  // A producer of this process's output, with its own budget and generous bounds, to adjust.
  private Producer.ProducerBuilder producerLaunching(ScriptedProcess process) {
    return Producer.builder()
        .launcher((command, jobAttemptId) -> process)
        .command(List.of("ffmpeg"))
        .jobAttemptId(JOB_ATTEMPT_ID)
        .gracePeriod(Duration.ofSeconds(5))
        .stallTimeout(Duration.ofMinutes(1))
        .encodedFrameRate(OptionalDouble.empty())
        .videoDecision(new VideoDecision.Copy(RecordingFixtures.ENCODED_RECORDING_CODEC_STRING))
        .memoryBudget(SegmentMemoryBudget.forSlots(1))
        .sink(sink);
  }

  // Replays the recording through a producer and waits for its outcome, whichever it is.
  private AttemptOutcome deliverEverySegmentOf(Recording recording) {
    var process = ScriptedProcess.builder().output(bytesOf(recording.file())).build();
    var producer = producerFor(process, recording).start();
    return assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).actual();
  }

  // Replays the recording through a producer of its own and returns what its sink accepted.
  private RecordingSegmentSink deliveredBy(Recording recording) {
    var deliveries = new RecordingSegmentSink();
    var process = ScriptedProcess.builder().output(bytesOf(recording.file())).build();
    var producer = producerFor(process, recording).sink(deliveries).start();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    return deliveries;
  }

  // The samples of the delivered media segment, read after the initialization segment.
  private static Movie mediaSegmentOf(RecordingSegmentSink deliveries, String name) {
    return Mp4Samples.read(
        IsoBoxes.concat(deliveries.contentOf("init.mp4"), deliveries.contentOf(name)));
  }

  private static List<Sample> videoSamplesOf(RecordingSegmentSink deliveries, String name) {
    var segment = mediaSegmentOf(deliveries, name);
    return segment.samplesOf(segment.videoTrack());
  }

  private static List<Sample> audioSamplesOf(RecordingSegmentSink deliveries, String name) {
    var segment = mediaSegmentOf(deliveries, name);
    if (!segment.hasAudio()) {
      return List.of();
    }

    return segment.samplesOf(segment.audioTrack());
  }

  private List<Long> videoDecodeTimesOf(String name) {
    return videoSamplesOf(sink, name).stream().map(Sample::decodeTime).toList();
  }

  private List<Long> audioDecodeTimesOf(String name) {
    return audioSamplesOf(sink, name).stream().map(Sample::decodeTime).toList();
  }

  private static int numberOf(String mediaSegmentName) {
    return Integer.parseInt(
        mediaSegmentName.substring("segment".length(), mediaSegmentName.indexOf(".")));
  }

  private List<String> mediaSegmentNames() {
    return sink.acceptedNames().stream().filter(name -> name.startsWith("segment")).toList();
  }

  private static Failed failureOf(Producer producer) {
    return assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .asInstanceOf(InstanceOfAssertFactories.type(Failed.class))
        .actual();
  }

  // An offset inside the first fragment of the third media segment, before it is complete.
  private static int insideThirdMediaSegment(Recording recording) {
    return offsetOfMediaSegment(recording, 2) + 10;
  }

  // Where the media segment at this position starts in a recording without preroll.
  private static int offsetOfMediaSegment(Recording recording, int position) {
    var earlierSegments =
        recording.segments().stream().limit(position).mapToLong(SegmentSummary::byteLength).sum();
    return Math.toIntExact(recording.initializationSegment().byteLength() + earlierSegments);
  }

  // An offset inside the second fragment of a recording whose preroll follows its initialization
  // segment.
  private static int insideSecondPrerollFragment(Recording recording, byte[] recorded) {
    assertThat(recording.discardedPreroll())
        .singleElement()
        .satisfies(
            preroll -> {
              assertThat(preroll.firstFragmentIndex()).isZero();
              assertThat(preroll.fragmentCount()).isGreaterThan(2);
            });
    var firstPrerollFragment = Math.toIntExact(recording.initializationSegment().byteLength());
    return endOfFragmentAt(recorded, firstPrerollFragment) + 10;
  }

  // Where the mdat that follows the moof at this offset ends.
  private static int endOfFragmentAt(byte[] output, int moofOffset) {
    return TopLevelBoxes.of(output).stream()
        .filter(box -> box.start() > moofOffset && box.type().equals("mdat"))
        .findFirst()
        .orElseThrow()
        .end();
  }

  // Three 1 s segments of 23.976 fps video in which the second segment's first fragment holds a
  // 1-tick sample, as FFmpeg writes after SVT-AV1 emits packets out of decode order.
  private static byte[] outputWithAOneTickVideoSample() {
    return IsoBoxes.oneSecondKeyframeFragments(
        List.of(List.of(1001, 1001), List.of(1001, 1, 1001), List.of(1001)));
  }

  // A movie without a sound track, whose segments close at the keyframe that opens the next.
  private static byte[] videoOnlyMoov() {
    return IsoBoxes.moov(IsoBoxes.Track.video().build());
  }

  // Three 1 s segments of three 40 MiB keyframe fragments each, nearly the segment cap: while the
  // first awaits acceptance, the second fills the rest of the budget.
  private static byte[] nearlyCappedSegments() {
    return IsoBoxes.concat(IsoBoxes.ftyp(), videoOnlyMoov(), nearlyCappedFragmentsFrom(0));
  }

  // The fragments of nearlyCappedSegments() from this media time in the 24 kHz video timescale.
  private static byte[] nearlyCappedFragmentsFrom(long startTime) {
    return IsoBoxes.concat(
        keyframeFragment(startTime),
        keyframeFragment(startTime + 6_000),
        keyframeFragment(startTime + 12_000),
        keyframeFragment(startTime + 24_000),
        keyframeFragment(startTime + 30_000),
        keyframeFragment(startTime + 36_000),
        keyframeFragment(startTime + 48_000));
  }

  private Producer.ProducerBuilder producerOfOneSecondSegments(ScriptedProcess process) {
    return producerLaunching(process).periodSeconds(1).startSequenceNumber(0);
  }

  // The parameter-set record with an array of one SEI message between its VPS and its SPS.
  private static byte[] recordWithSeiArray(int seiNalUnitType) {
    var seiArray = "%02X0001000%d%02X0105".formatted(seiNalUnitType, 3, seiNalUnitType << 1);
    return HexFormat.of()
        .parseHex(HVCC_FIELDS + "04" + HVCC_VPS_ARRAY + seiArray + HVCC_SPS_PPS_ARRAYS);
  }

  // An hvc1 sample entry whose record is followed by a pasp box.
  private static byte[] hvc1Entry(byte[] hvcCBody) {
    return IsoBoxes.box(
        "hvc1",
        new byte[78],
        IsoBoxes.box("hvcC", hvcCBody),
        IsoBoxes.box("pasp", IsoBoxes.u32(1), IsoBoxes.u32(1)));
  }

  private static byte[] hevcInitializationSegment(byte[] videoSampleEntry) {
    return IsoBoxes.concat(
        IsoBoxes.ftyp(),
        IsoBoxes.moov(
            IsoBoxes.Track.video().sampleEntry(videoSampleEntry).build(),
            IsoBoxes.Track.audio().build()));
  }

  /**
   * One fragment of a 1 s period: one-byte video samples back to back from this time in the 24 kHz
   * video timescale, one unless given, each lasting one second unless given, the first a keyframe
   * or not, and a keyframe at the given position after it, if any; then one-byte audio samples back
   * to back from their start in the 48 kHz audio timescale, lasting as given, followed in the mdat
   * by any padding given.
   */
  @Builder
  private record TimedFragment(
      long videoTime,
      long videoDuration,
      int videoSampleCount,
      boolean keyframe,
      OptionalInt keyframeInside,
      long audioStart,
      List<Integer> audioDurations,
      int paddingBytes) {

    static TimedFragmentBuilder keyframeAt(long videoTime) {
      return frameAt(videoTime).keyframe(true);
    }

    static TimedFragmentBuilder frameAt(long videoTime) {
      return builder()
          .videoTime(videoTime)
          .videoDuration(24_000)
          .videoSampleCount(1)
          .keyframe(false)
          .keyframeInside(OptionalInt.empty())
          .audioDurations(List.of());
    }

    byte[] bytes() {
      var video =
          IsoBoxes.videoTraf()
              .baseMediaDecodeTime(videoTime)
              .sampleDurations(
                  Collections.nCopies(videoSampleCount, Math.toIntExact(videoDuration)));
      if (keyframeInside.isPresent()) {
        video.flagsOfEachSample(
            IntStream.range(0, videoSampleCount).mapToObj(this::flagsOfVideoSample).toList());
      }

      if (keyframeInside.isEmpty() && keyframe) {
        video.firstSampleFlags(IsoBoxes.SYNC_SAMPLE_FLAGS);
      }

      if (audioDurations.isEmpty()) {
        return IsoBoxes.concat(
            IsoBoxes.moof(video.build()), IsoBoxes.mdat(videoSampleCount + paddingBytes));
      }

      var audio =
          IsoBoxes.audioTraf().baseMediaDecodeTime(audioStart).sampleDurations(audioDurations);
      return IsoBoxes.concat(
          IsoBoxes.moof(video.build(), audio.build()),
          IsoBoxes.mdat(videoSampleCount + audioDurations.size() + paddingBytes));
    }

    private int flagsOfVideoSample(int sample) {
      if ((sample == 0 && keyframe) || keyframeInside.equals(OptionalInt.of(sample))) {
        return IsoBoxes.SYNC_SAMPLE_FLAGS;
      }

      return IsoBoxes.NON_SYNC_SAMPLE_FLAGS;
    }

    static final class TimedFragmentBuilder {

      TimedFragmentBuilder audioFrom(long start, Integer... durations) {
        return audioStart(start).audioDurations(List.of(durations));
      }
    }
  }

  /**
   * An output of a movie without a sound track whose fragments each hold one keyframe a second, so
   * that each fragment opens a segment of a 1 s period.
   */
  private record OneKeyframePerSecond(byte[] bytes, int initializationSegmentBytes, int fragments) {

    private static final int PAYLOAD_BYTES = 4096;

    static OneKeyframePerSecond of(int fragments) {
      var initializationSegment = IsoBoxes.concat(IsoBoxes.ftyp(), videoOnlyMoov());
      var output = new ByteArrayOutputStream();
      output.writeBytes(initializationSegment);
      for (var fragment = 0; fragment < fragments; fragment++) {
        output.writeBytes(keyframeFragment(fragment * 24_000L, PAYLOAD_BYTES));
      }

      return new OneKeyframePerSecond(
          output.toByteArray(), initializationSegment.length, fragments);
    }

    int endOf(int fragment) {
      return initializationSegmentBytes + (fragment + 1) * fragmentBytes();
    }

    // Reserves, as other attempts would, all of the budget but room for the first two fragments,
    // whose second closes segment 0; returns the bytes to release for those attempts.
    long leavingRoomForTheFirstTwoFragmentsIn(SegmentMemoryBudget workerBudget) {
      var reserved = ONE_SLOT_CAPACITY - 2L * fragmentBytes();
      assertThat(workerBudget.tryReserveAtOnce(reserved)).isTrue();
      return reserved;
    }

    private int fragmentBytes() {
      return (bytes.length - initializationSegmentBytes) / fragments;
    }

    @Override
    public String toString() {
      return "OneKeyframePerSecond[" + fragments + " fragments]";
    }
  }

  /** An output of 1 s segments and where each of its fragments ends. */
  private record OneSecondSegments(byte[] bytes, List<Integer> fragmentEnds) {

    static OneSecondSegments of(byte[] moov, TimedFragment... fragments) {
      var output = new ByteArrayOutputStream();
      output.writeBytes(IsoBoxes.ftyp());
      output.writeBytes(moov);
      var ends = new ArrayList<Integer>();
      for (var fragment : fragments) {
        output.writeBytes(fragment.bytes());
        ends.add(output.size());
      }

      return new OneSecondSegments(output.toByteArray(), List.copyOf(ends));
    }

    int endOf(int fragment) {
      return fragmentEnds.get(fragment);
    }

    @Override
    public String toString() {
      return "OneSecondSegments[" + bytes.length + " bytes]";
    }
  }

  @Builder
  private record BurstStart(byte[] output, SegmentMemoryBudget budget, int burst) {}

  private record HeldStop(ScriptedProcess process, RecordingSegmentSink sink, Producer producer) {}

  // How many bytes of FFmpeg's output an attempt's reader had taken when the sampler read it.
  private record ReaderProgress(BurstAttempt attempt, int bytesTaken) {

    // What the attempt charges before its returned deliveries are subtracted.
    long chargedBefore(List<Box> layout) {
      if (attempt.isStopped()) {
        return heldByDeliveries(attempt.sink().startedNames(), layout);
      }

      return layout.stream()
          .filter(box -> box.hasBodyBegunAfter(bytesTaken))
          .mapToLong(Box::size)
          .sum();
    }
  }

  // An attempt of the burst test. The test marks it stopped before it asks the producer to stop, so
  // that the lower bound stops counting the reader's share no later than the producer releases it.
  private static final class BurstAttempt {

    private final ScriptedProcess process;
    private final RecordingSegmentSink sink;
    private final Producer producer;
    private final AtomicBoolean stopped = new AtomicBoolean();

    @Builder
    private BurstAttempt(ScriptedProcess process, RecordingSegmentSink sink, Producer producer) {
      this.process = process;
      this.sink = sink;
      this.producer = producer;
    }

    ScriptedProcess process() {
      return process;
    }

    RecordingSegmentSink sink() {
      return sink;
    }

    Producer producer() {
      return producer;
    }

    void stop() {
      stopped.set(true);
      producer.requestStop();
    }

    boolean isStopped() {
      return stopped.get();
    }
  }

  // A fragment of one-byte video samples of one tick each, the first with these flags, at this
  // time in the 24 kHz video timescale.
  private static byte[] manySampleFragment(long decodeTime, int samples, int firstSampleFlags) {
    return manySampleFragment(
        IsoBoxes.videoTraf()
            .baseMediaDecodeTime(decodeTime)
            .firstSampleFlags(firstSampleFlags)
            .sampleCount(samples));
  }

  // A fragment of the traf's video samples, of one tick each, and an mdat that holds their bytes.
  private static byte[] manySampleFragment(IsoBoxes.TrackFragment.TrackFragmentBuilder videoTraf) {
    var trackFragment = videoTraf.build();
    return IsoBoxes.concat(
        IsoBoxes.moof(trackFragment), IsoBoxes.mdat(trackFragment.sampleBytes()));
  }

  private static byte[] keyframeFragment(long presentationTime) {
    return keyframeFragment(presentationTime, NEARLY_CAPPED_FRAGMENT_PAYLOAD);
  }

  private static byte[] keyframeFragment(long presentationTime, int mediaDataPayload) {
    return IsoBoxes.concat(keyframeMoof(presentationTime), IsoBoxes.mdat(mediaDataPayload));
  }

  // A moof whose single video sample is a keyframe at this time in the 24 kHz video timescale.
  private static byte[] keyframeMoof(long presentationTime) {
    return IsoBoxes.moof(
        IsoBoxes.videoTraf()
            .baseMediaDecodeTime(presentationTime)
            .firstSampleFlags(IsoBoxes.SYNC_SAMPLE_FLAGS)
            .build());
  }

  // The output without the last bytes of its final box.
  private static byte[] truncated(byte[] output) {
    return Arrays.copyOf(output, output.length - 100);
  }

  // Every segment the recording groups, in order, each with the length its delivery declared.
  private void assertDeliveredInOrder(Recording recording) {
    assertThat(sink.acceptedNames()).containsExactlyElementsOf(uploadNames(recording));
    assertThat(sink.accepted())
        .allSatisfy(
            accepted -> assertThat(accepted.contentLength()).isEqualTo(accepted.declaredLength()));
  }
}
