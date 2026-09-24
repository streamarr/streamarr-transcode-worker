package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
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
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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
  void shouldDiscardThePeriodBeforeTheFirstSegmentWhenAnEncodedReplacementAttemptSeeksEarly(
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
      "Should deliver the closed segment, then fail the attempt and end FFmpeg, when source"
          + " keyframes are further apart than the period")
  void shouldDeliverTheClosedSegmentThenFailWhenSourceKeyframesAreFurtherApartThanThePeriod() {
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
      "Should settle only the stop when stopped while the sink holds the segment a skipping"
          + " keyframe closed")
  void shouldSettleOnlyTheStopWhenStoppedWhileTheSinkHoldsTheSegmentASkippingKeyframeClosed() {
    var recording = recording("10-copy-gop-exceeds-period.fmp4");
    var process = ScriptedProcess.builder().output(bytesOf(recording.file())).build();
    sink.holding(2);
    var producer = producerFor(process, recording).gracePeriod(Duration.ofMillis(100)).start();
    awaiting().until(sink::isHolding);

    producer.stop();
    sink.release();

    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
    await()
        .during(Duration.ofMillis(200))
        .atMost(OUTCOME_LIMIT)
        .until(
            () -> sink.acceptedNames().equals(List.of("init.mp4", "segment0.m4s", "segment1.m4s")));
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
      "Should deliver nothing after the delivery in flight when stopped while the sink holds a"
          + " segment")
  void shouldDeliverNothingAfterTheDeliveryInFlightWhenStoppedWhileTheSinkHoldsASegment() {
    var recording = recording(ENCODED_RECORDING);
    var process = ScriptedProcess.builder().output(bytesOf(ENCODED_RECORDING)).build();
    sink.holding(2);
    var producer = producerFor(process, recording).gracePeriod(Duration.ofMillis(100)).start();
    awaiting().until(sink::isHolding);

    producer.stop();
    sink.release();

    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
    await()
        .during(Duration.ofMillis(200))
        .atMost(OUTCOME_LIMIT)
        .until(
            () -> sink.acceptedNames().equals(List.of("init.mp4", "segment0.m4s", "segment1.m4s")));
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

  private static ConditionFactory awaiting() {
    return await().atMost(OUTCOME_LIMIT).pollInterval(POLL_INTERVAL);
  }

  private Producer.ProducerBuilder producerFor(ScriptedProcess process, Recording recording) {
    return Producer.builder()
        .launcher((command, jobAttemptId) -> process)
        .command(List.of("ffmpeg"))
        .jobAttemptId(JOB_ATTEMPT_ID)
        .periodSeconds(recording.period())
        .startSequenceNumber(recording.startSequenceNumber())
        .gracePeriod(Duration.ofSeconds(5))
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
    var firstTwoSegments =
        recording.segments().stream().limit(2).mapToLong(SegmentSummary::byteLength).sum();
    return Math.toIntExact(recording.initializationSegment().byteLength() + firstTwoSegments + 10);
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

  // The recording's initialization segment and media segments, without the preroll the producer
  // discards between the two or anything after the last media segment.
  private static byte[] deliveredBytesOf(Recording recording) {
    var recorded = bytesOf(recording.file());
    var initializationSegmentLength = recording.initializationSegment().byteLength();
    var prerollLength =
        recording.discardedPreroll().stream().mapToLong(SegmentSummary::byteLength).sum();
    var mediaSegmentsLength =
        recording.segments().stream().mapToLong(SegmentSummary::byteLength).sum();
    var delivered = new byte[Math.toIntExact(initializationSegmentLength + mediaSegmentsLength)];
    System.arraycopy(recorded, 0, delivered, 0, initializationSegmentLength);
    System.arraycopy(
        recorded,
        Math.toIntExact(initializationSegmentLength + prerollLength),
        delivered,
        initializationSegmentLength,
        delivered.length - initializationSegmentLength);
    return delivered;
  }
}
