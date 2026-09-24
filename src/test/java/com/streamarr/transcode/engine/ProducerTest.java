package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.deliveredBytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.fixtures.Races.awaitStart;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.engine.AttemptOutcome.Stopped;
import com.streamarr.transcode.engine.FfmpegRecordings.Recording;
import com.streamarr.transcode.engine.FfmpegRecordings.SegmentSummary;
import com.streamarr.transcode.engine.RecordingSegmentSink.Accepted;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcess.ExitTiming;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
class ProducerTest {

  private static final Duration OUTCOME_LIMIT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(5);
  private static final UUID JOB_ATTEMPT_ID =
      UUID.fromString("0f6a3a9e-4c6b-4f59-9d0e-1c2b3a4d5e6f");
  private static final long SERVER_SEGMENT_CAP_BYTES = 16L * 1024 * 1024;
  private static final int NEARLY_CAPPED_FRAGMENT_PAYLOAD = 5 * 1024 * 1024;
  private static final int RACE_ITERATIONS = 200;

  // Where the reader of nearlyCappedSegments() stops while the first segment awaits acceptance:
  // before the body of the third segment's first mdat, which the budget cannot admit.
  private static final int NEARLY_CAPPED_BUDGET_STOP =
      IsoBoxes.ftyp().length
          + IsoBoxes.videoAndAudioMoov().length
          + 6 * keyframeFragment(0).length
          + keyframeMoof(48_000).length
          + 8;

  private final RecordingSegmentSink sink = new RecordingSegmentSink();

  static Stream<Recording> recordingsThatGroupWithoutFailure() {
    return FfmpegRecordings.recordings().stream()
        .filter(recording -> recording.failure().isEmpty());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("recordingsThatGroupWithoutFailure")
  @DisplayName(
      "Should deliver the initialization segment and then each media segment in order when FFmpeg"
          + " exits cleanly")
  void shouldDeliverTheInitializationSegmentAndThenEachMediaSegmentInOrderWhenFfmpegExitsCleanly(
      Recording recording) {
    var process = ScriptedProcess.builder().output(bytesOf(recording.file())).build();

    var producer = producerFor(process, recording).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.accepted()).containsExactlyElementsOf(expectedDeliveries(recording));
    assertThat(sink.acceptedBytes()).isEqualTo(deliveredBytesOf(recording));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"01-encode-cfr-seek30.fmp4", "09-svtav1-vfr-seek30.fmp4"})
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
    var closingFragmentEnd =
        firstThirdSegmentFragment + fragmentLengthAt(recorded, firstThirdSegmentFragment);

    var producer = producerFor(process, recording).start();

    assertReaderStopsAt(process, closingFragmentEnd);
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
    sink.release();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.accepted()).containsExactlyElementsOf(expectedDeliveries(recording));
  }

  @Test
  @DisplayName("Should hold no more than two segment caps and block FFmpeg when the budget is full")
  void shouldHoldNoMoreThanTwoSegmentCapsAndBlockFfmpegWhenTheBudgetIsFull() {
    var process = ScriptedProcess.builder().output(nearlyCappedSegments()).build();
    sink.holding(1);

    var producer = producerOfOneSecondSegments(process).start();

    assertReaderStopsAt(process, NEARLY_CAPPED_BUDGET_STOP);
    assertThat(
            (long) process.bytesTaken()
                - IsoBoxes.ftyp().length
                - IsoBoxes.videoAndAudioMoov().length)
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
    var stoppedProcess =
        ScriptedProcess.builder()
            .output(nearlyCappedSegments())
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var stoppedSink = new RecordingSegmentSink().holdingPastCancellation(1);
    var stopped =
        producerOfOneSecondSegments(stoppedProcess)
            .sink(stoppedSink)
            .memoryBudget(workerBudget)
            .gracePeriod(Duration.ofMinutes(10))
            .start();
    awaiting().until(() -> stoppedProcess.bytesTaken() == NEARLY_CAPPED_BUDGET_STOP);
    stopped.requestStop();
    awaiting().until(stoppedSink::wasCancelled);
    var nextProcess = ScriptedProcess.builder().output(nearlyCappedSegments()).build();

    var next = producerOfOneSecondSegments(nextProcess).memoryBudget(workerBudget).start();

    // The stop released the segment the stopped attempt was assembling, but its cancelled delivery
    // of segment 0 still holds three fragments; the next reader admits three fragments of its own
    // and the next moof, but not the mdat that follows.
    var workerBudgetStop =
        IsoBoxes.ftyp().length
            + IsoBoxes.videoAndAudioMoov().length
            + 3 * keyframeFragment(0).length
            + keyframeMoof(24_000).length
            + 8;
    assertReaderStopsAt(nextProcess, workerBudgetStop);
    stoppedSink.release();
    assertThat(next.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(stoppedProcess.isAlive()).isTrue();
    stoppedProcess.exit();
    assertThat(stopped.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Stopped());
  }

  @Test
  @DisplayName(
      "Should free the segment it was assembling at the stop when a hung FFmpeg leaves the reader"
          + " waiting inside a fragment")
  void
      shouldFreeTheSegmentItWasAssemblingAtTheStopWhenAHungFfmpegLeavesTheReaderWaitingInsideAFragment() {
    // Segment 0's three 5 MiB fragments and the first MiB of the next fragment's mdat, after which
    // FFmpeg writes nothing more and ignores the quit.
    var hangOffset =
        IsoBoxes.ftyp().length
            + IsoBoxes.videoAndAudioMoov().length
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
        .isGreaterThan(heldBeforeReading + 18L * 1024 * 1024);

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
    // Four 5 MiB keyframe fragments in segment 0, which an attempt from segment 1 discards.
    var preroll =
        IsoBoxes.concat(
            keyframeFragment(0),
            keyframeFragment(6_000),
            keyframeFragment(12_000),
            keyframeFragment(18_000));
    var output =
        IsoBoxes.concat(
            IsoBoxes.ftyp(),
            IsoBoxes.videoAndAudioMoov(),
            preroll,
            nearlyCappedFragmentsFrom(24_000));
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
    assertThat(sink.accepted()).containsExactlyElementsOf(expectedDeliveries(recording));
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
      "Should deliver the closed segment, then fail the attempt and end FFmpeg, when source"
          + " keyframes are further apart than the period")
  void
      shouldDeliverTheClosedSegmentThenFailTheAttemptAndEndFfmpegWhenSourceKeyframesAreFurtherApartThanThePeriod() {
    var recording = recording("10-copy-gop-exceeds-period.fmp4");
    var process = ScriptedProcess.builder().output(bytesOf(recording.file())).build();

    var producer = producerFor(process, recording).start();

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.SKIPPED_SEGMENT_NUMBER);
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(sink.accepted()).containsExactlyElementsOf(expectedDeliveries(recording));
    assertThat(sink.acceptedBytes()).isEqualTo(deliveredBytesOf(recording));
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
    // FFmpeg is still encoding, so it exits when asked to quit, not at the end of its output. The
    // reader drains that output once the stop is recorded, before or after FFmpeg exits.
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(ENCODED_RECORDING))
            .exitTiming(ExitTiming.AT_QUIT)
            .build();
    sink.holding(2);
    var producer = producerFor(process, recording).gracePeriod(Duration.ofMinutes(10)).start();
    awaiting().until(sink::isHolding);

    var stopping = Thread.ofVirtual().start(producer::stop);

    assertThat(stopping.join(OUTCOME_LIMIT)).isTrue();
    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
    assertThat(process.stdinText()).isEqualTo("q");
    awaiting().until(process::hasReadToEndOfOutput);
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

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.PROCESS_DID_NOT_EXIT);
    assertThat(process.wasTerminated()).isTrue();
    assertThat(process.wasDestroyedForcibly()).isFalse();
    assertThat(sink.accepted()).containsExactlyElementsOf(expectedDeliveries(recording));
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
        .memoryBudget(SegmentMemoryBudget.forSlots(1))
        .sink(sink);
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
    return firstPrerollFragment + fragmentLengthAt(recorded, firstPrerollFragment) + 10;
  }

  // The length of the moof at this offset and the mdat that follows it.
  private static int fragmentLengthAt(byte[] output, int offset) {
    var buffer = ByteBuffer.wrap(output);
    var moofLength = buffer.getInt(offset);
    return moofLength + buffer.getInt(offset + moofLength);
  }

  // Three 1 s segments of 23.976 fps video in which the second segment's first fragment holds a
  // 1-tick sample, as FFmpeg writes after SVT-AV1 emits packets out of decode order.
  private static byte[] outputWithAOneTickVideoSample() {
    return IsoBoxes.oneSecondKeyframeFragments(
        List.of(List.of(1001, 1001), List.of(1001, 1, 1001), List.of(1001)));
  }

  // Three 1 s segments of three 5 MiB keyframe fragments each, nearly the segment cap: while the
  // first awaits acceptance, the second fills the rest of the budget.
  private static byte[] nearlyCappedSegments() {
    return IsoBoxes.concat(
        IsoBoxes.ftyp(), IsoBoxes.videoAndAudioMoov(), nearlyCappedFragmentsFrom(0));
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

  private static List<Accepted> expectedDeliveries(Recording recording) {
    var initializationSegmentLength = recording.initializationSegment().byteLength();
    var mediaSegments =
        recording.segments().stream()
            .map(
                segment ->
                    new Accepted(
                        "segment" + segment.number() + ".m4s",
                        segment.byteLength(),
                        segment.byteLength()));
    return Stream.concat(
            Stream.of(
                new Accepted("init.mp4", initializationSegmentLength, initializationSegmentLength)),
            mediaSegments)
        .toList();
  }
}
