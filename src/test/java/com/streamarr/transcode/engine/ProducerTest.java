package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.engine.FfmpegRecordings.Recording;
import com.streamarr.transcode.engine.FfmpegRecordings.SegmentSummary;
import com.streamarr.transcode.engine.RecordingSegmentSink.Accepted;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcess.ExitTiming;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
class ProducerTest {

  private static final Duration OUTCOME_LIMIT = Duration.ofSeconds(10);
  private static final UUID JOB_ATTEMPT_ID =
      UUID.fromString("0f6a3a9e-4c6b-4f59-9d0e-1c2b3a4d5e6f");
  private static final String WHOLE_RUN = "01-encode-cfr.fmp4";

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

  @Test
  @DisplayName("Should not complete the attempt when the last media segment awaits acceptance")
  void shouldNotCompleteTheAttemptWhenTheLastMediaSegmentAwaitsAcceptance() {
    var recording = recording(WHOLE_RUN);
    var process = ScriptedProcess.builder().output(bytesOf(WHOLE_RUN)).build();
    sink.holding(recording.segments().size());

    var producer = producerFor(process, recording).start();

    await().atMost(OUTCOME_LIMIT).until(sink::isHolding);
    assertThat(process.isAlive()).isFalse();
    assertThat(producer.outcome()).isNotDone();
    sink.release();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
  }

  @Test
  @DisplayName(
      "Should not complete the attempt when the output has ended but FFmpeg has not exited")
  void shouldNotCompleteTheAttemptWhenTheOutputHasEndedButFfmpegHasNotExited() {
    var recording = recording(WHOLE_RUN);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(WHOLE_RUN))
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();

    var producer = producerFor(process, recording).start();

    await().atMost(OUTCOME_LIMIT).until(process::hasReadToEndOfOutput);
    assertThat(sink.accepted()).hasSize(recording.segments().size() + 1);
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
    var recording = recording(WHOLE_RUN);
    var process =
        ScriptedProcess.builder().output(bytesOf(WHOLE_RUN)).exitTiming(exitTiming).build();

    var producer = producerFor(process, recording).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed());
    assertThat(sink.accepted()).containsExactlyElementsOf(expectedDeliveries(recording));
  }

  @Test
  @DisplayName("Should fail the attempt with FFmpeg's last error output when FFmpeg exits non-zero")
  void shouldFailTheAttemptWithFfmpegsLastErrorOutputWhenFfmpegExitsNonZero() {
    var recording = recording(WHOLE_RUN);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(WHOLE_RUN))
            .exitCode(1)
            .stderr("frame=  264 fps=0.0 q=-1.0 size=N/A\nConversion failed!\n")
            .build();

    var producer = producerFor(process, recording).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.PROCESS_EXITED_WITH_ERROR);
    assertThat(failure.detail()).contains("exit code 1").endsWith("Conversion failed!");
  }

  @Test
  @DisplayName("Should fail the attempt and end FFmpeg when its output cannot be read")
  void shouldFailTheAttemptAndEndFfmpegWhenItsOutputCannotBeRead() {
    var recording = recording(WHOLE_RUN);
    var process =
        ScriptedProcess.builder()
            .output(bytesOf(WHOLE_RUN))
            .failReadAfter(recording.initializationSegment().byteLength() + 100)
            .build();

    var producer = producerFor(process, recording).start();

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.OUTPUT_UNREADABLE);
    assertThat(process.wasDestroyedForcibly()).isTrue();
    assertThat(sink.acceptedNames()).containsExactly("init.mp4");
  }

  private Producer.ProducerBuilder producerFor(ScriptedProcess process, Recording recording) {
    return Producer.builder()
        .launcher((command, jobAttemptId) -> process)
        .command(List.of("ffmpeg"))
        .jobAttemptId(JOB_ATTEMPT_ID)
        .periodSeconds(recording.period())
        .startSequenceNumber(recording.startSequenceNumber())
        .sink(sink);
  }

  private static Failed failureOf(Producer producer) {
    return assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .asInstanceOf(InstanceOfAssertFactories.type(Failed.class))
        .actual();
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

  /** The recording without the preroll the producer discards between the two. */
  private static byte[] deliveredBytesOf(Recording recording) {
    var recorded = bytesOf(recording.file());
    var initializationSegmentLength = recording.initializationSegment().byteLength();
    var prerollLength =
        recording.discardedPreroll().stream().mapToLong(SegmentSummary::byteLength).sum();
    var delivered = new byte[Math.toIntExact(recorded.length - prerollLength)];
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
