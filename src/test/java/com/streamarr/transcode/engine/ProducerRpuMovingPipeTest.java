package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.engine.HevcCopies.aud;
import static com.streamarr.transcode.engine.HevcCopies.fragmentBuilder;
import static com.streamarr.transcode.engine.HevcCopies.initializationSegment;
import static com.streamarr.transcode.engine.HevcCopies.rpu;
import static com.streamarr.transcode.engine.HevcCopies.sample;
import static com.streamarr.transcode.engine.HevcCopies.slice;
import static com.streamarr.transcode.engine.IsoBoxes.box;
import static com.streamarr.transcode.engine.IsoBoxes.concat;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.engine.AttemptOutcome.Stopped;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcess.ExitTiming;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.awaitility.core.ConditionFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Producer RPU Moving Pipe Tests")
class ProducerRpuMovingPipeTest {

  private static final Duration OUTCOME_LIMIT = Duration.ofSeconds(10);
  private static final UUID JOB_ATTEMPT_ID =
      UUID.fromString("5b0f8a8e-29b5-4c48-9f0c-7f4f3c1a9d21");
  private static final List<String> COPY_COMMAND = List.of("copy-ffmpeg");
  private static final List<String> ENCODE_COMMAND = List.of("encode-ffmpeg");
  private static final byte[] COPIED_SOURCE =
      concat(
          initializationSegment(),
          fragmentBuilder().video(0, sample(aud(), slice(0))).build(),
          fragmentBuilder().video(1001, sample(aud(), rpu(0), slice(1))).build());
  private static final byte[] NOT_HEVC =
      concat(
          initializationSegment(box("avc1", new byte[78], box("avcC", new byte[7]))),
          fragmentBuilder().video(0, sample(slice(0))).build());
  private static final byte[] MOVED_SOURCE =
      concat(
          initializationSegment(),
          fragmentBuilder().video(0, sample(aud(), slice(0), rpu(0))).build(),
          fragmentBuilder().video(1001, sample(aud(), slice(1))).build());

  private final RecordingSegmentSink sink = new RecordingSegmentSink();

  @Test
  @DisplayName(
      "Should pass the copy to the encoding FFmpeg with its RPUs moved, and deliver that FFmpeg's"
          + " segments, when both FFmpegs exit cleanly")
  void
      shouldPassTheCopyToTheEncodingFfmpegWithItsRpusMovedAndDeliverThatFfmpegsSegmentsWhenBothFfmpegsExitCleanly() {
    var copy = ScriptedProcess.builder().output(COPIED_SOURCE).build();
    var encode = encodingFfmpeg().build();

    var producer = producerOf(copy, encode).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Completed(0));
    assertThat(videoSamplesOf(encode.stdinBytes())).isEqualTo(videoSamplesOf(MOVED_SOURCE));
    assertThat(encode.hasInputEnded()).isTrue();
    assertThat(sink.acceptedNames())
        .hasSize(recording(ENCODED_RECORDING).segments().size() + 1)
        .startsWith("init.mp4", "segment0.m4s");
  }

  @Test
  @DisplayName(
      "Should ask the copying FFmpeg to quit, and let the encoding FFmpeg finish on the end of its"
          + " input, when the attempt is stopped")
  void
      shouldAskTheCopyingFfmpegToQuitAndLetTheEncodingFfmpegFinishOnTheEndOfItsInputWhenTheAttemptIsStopped() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .pauseAfter(COPIED_SOURCE.length / 2)
            .resumesOnQuit(true)
            .exitTiming(ExitTiming.AT_END_OF_OUTPUT)
            .build();
    var encode = encodingFfmpeg().pauseAfter(0).build();
    var producer = producerOf(copy, encode).start();
    awaiting().until(copy::hasReachedPause);

    producer.stop();

    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
    assertThat(copy.stdinText()).isEqualTo("q");
    assertThat(videoSamplesOf(encode.stdinBytes())).isEqualTo(videoSamplesOf(MOVED_SOURCE));
    assertThat(encode.hasInputEnded()).isTrue();
    assertThat(copy.wasDestroyedForcibly()).isFalse();
    assertThat(encode.wasDestroyedForcibly()).isFalse();
  }

  @Test
  @DisplayName(
      "Should fail the attempt with the copying FFmpeg's error output when it exits with an error"
          + " and the encoding FFmpeg exits cleanly")
  void
      shouldFailTheAttemptWithTheCopyingFfmpegsErrorOutputWhenItExitsWithAnErrorAndTheEncodingFfmpegExitsCleanly() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .exitCode(1)
            .stderr("Error reading the source: Input/output error\n")
            .build();
    var encode = encodingFfmpeg().build();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.PROCESS_EXITED_WITH_ERROR);
    assertThat(failure.detail())
        .contains("copies the source", "exit code 1")
        .endsWith("Error reading the source: Input/output error");
  }

  @Test
  @DisplayName(
      "Should fail the attempt with the encoding FFmpeg's error output when it exits with an"
          + " error")
  void shouldFailTheAttemptWithTheEncodingFfmpegsErrorOutputWhenItExitsWithAnError() {
    var copy = ScriptedProcess.builder().output(COPIED_SOURCE).build();
    var encode = encodingFfmpeg().exitCode(1).stderr("Conversion failed!\n").build();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.PROCESS_EXITED_WITH_ERROR);
    assertThat(failure.detail())
        .startsWith("FFmpeg exited with exit code 1")
        .endsWith("Conversion failed!");
  }

  @Test
  @DisplayName(
      "Should end the copying FFmpeg when the encoding FFmpeg exits before it has read the copy")
  void shouldEndTheCopyingFfmpegWhenTheEncodingFfmpegExitsBeforeItHasReadTheCopy() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var encode =
        ScriptedProcess.builder()
            .output(new byte[0])
            .exitCode(1)
            .exitTiming(ExitTiming.AT_LAUNCH)
            .stderr("Invalid data found when processing input\n")
            .build();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.detail()).endsWith("Invalid data found when processing input");
    assertThat(copy.wasTerminated() || copy.wasDestroyedForcibly()).isTrue();
  }

  @Test
  @DisplayName("Should terminate both FFmpegs when the encoding FFmpeg writes nothing for too long")
  void shouldTerminateBothFfmpegsWhenTheEncodingFfmpegWritesNothingForTooLong() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .pauseAfter(COPIED_SOURCE.length / 2)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var encode = encodingFfmpeg().pauseAfter(0).build();

    var producer =
        producerOf(copy, encode)
            .stallTimeout(Duration.ofMillis(200))
            .gracePeriod(Duration.ofMillis(200))
            .start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.ENCODER_STALLED);
    assertThat(copy.wasTerminated()).isTrue();
    assertThat(encode.wasTerminated()).isTrue();
  }

  @Test
  @DisplayName("Should end the copying FFmpeg when the encoding FFmpeg cannot be started")
  void shouldEndTheCopyingFfmpegWhenTheEncodingFfmpegCannotBeStarted() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    ProcessLauncher launcher =
        (command, jobAttemptId) -> {
          if (command.equals(COPY_COMMAND)) {
            return copy;
          }

          throw new IOException("Cannot run program encode-ffmpeg");
        };

    assertThatExceptionOfType(TranscodeException.class)
        .isThrownBy(() -> producer(launcher).start());
    assertThat(copy.wasDestroyedForcibly()).isTrue();
  }

  @Test
  @DisplayName(
      "Should fail the attempt for the mover's reason, and end the copying FFmpeg, when the mover"
          + " refuses the copy and the encoding FFmpeg exits cleanly")
  void
      shouldFailTheAttemptForTheMoversReasonAndEndTheCopyingFfmpegWhenTheMoverRefusesTheCopyAndTheEncodingFfmpegExitsCleanly() {
    var copy =
        ScriptedProcess.builder().output(NOT_HEVC).exitTiming(ExitTiming.WHEN_TEST_EXITS).build();
    var encode = encodingFfmpeg().build();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.OUTPUT_MISMATCH);
    assertThat(failure.detail()).contains("copy of the source", "no HEVC video");
    assertThat(copy.wasTerminated()).isTrue();
    assertThat(encode.hasInputEnded()).isTrue();
  }

  @Test
  @DisplayName(
      "Should fail the attempt for the mover's reason when the encoding FFmpeg exits with an error"
          + " at the end of the input that the refusal cut")
  void
      shouldFailTheAttemptForTheMoversReasonWhenTheEncodingFfmpegExitsWithAnErrorAtTheEndOfTheInputThatTheRefusalCut() {
    var copy =
        ScriptedProcess.builder().output(NOT_HEVC).exitTiming(ExitTiming.WHEN_TEST_EXITS).build();
    var encode =
        encodingFfmpeg()
            .output(new byte[0])
            .exitCode(183)
            .stderr("Error opening input: Invalid data found when processing input\n")
            .build();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.OUTPUT_MISMATCH);
    assertThat(failure.detail()).contains("copy of the source", "no HEVC video");
    assertThat(copy.wasTerminated()).isTrue();
  }

  @Test
  @DisplayName(
      "Should fail the attempt as a truncated output when the copy ends inside a box and both"
          + " FFmpegs exit cleanly")
  void shouldFailTheAttemptAsATruncatedOutputWhenTheCopyEndsInsideABoxAndBothFfmpegsExitCleanly() {
    var copy =
        ScriptedProcess.builder()
            .output(Arrays.copyOf(COPIED_SOURCE, COPIED_SOURCE.length - 1))
            .build();
    var encode = encodingFfmpeg().build();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.TRUNCATED_OUTPUT);
    assertThat(failure.detail()).contains("copy of the source");
  }

  @Test
  @DisplayName(
      "Should fail the attempt with the copying FFmpeg's error output when the copy ends inside a"
          + " box and the copying FFmpeg exits with an error")
  void
      shouldFailTheAttemptWithTheCopyingFfmpegsErrorOutputWhenTheCopyEndsInsideABoxAndTheCopyingFfmpegExitsWithAnError() {
    var copy =
        ScriptedProcess.builder()
            .output(Arrays.copyOf(COPIED_SOURCE, COPIED_SOURCE.length - 1))
            .exitCode(1)
            .stderr("Error reading the source: Input/output error\n")
            .build();
    var encode = encodingFfmpeg().build();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.PROCESS_EXITED_WITH_ERROR);
    assertThat(failure.detail()).contains("copies the source", "exit code 1");
  }

  @Test
  @DisplayName(
      "Should fail the attempt as an unexpected error, and end the copying FFmpeg, when passing the"
          + " copy on throws unexpectedly")
  void
      shouldFailTheAttemptAsAnUnexpectedErrorAndEndTheCopyingFfmpegWhenPassingTheCopyOnThrowsUnexpectedly() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .failReadAfter(initializationSegment().length)
            .failReadWith(new IllegalStateException("defect in the copy's transfer"))
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var encode = encodingFfmpeg().build();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.UNEXPECTED_ERROR);
    assertThat(failure.detail()).contains("defect in the copy's transfer");
    assertThat(copy.wasTerminated()).isTrue();
    assertThat(encode.hasInputEnded()).isTrue();
  }

  private static ScriptedProcess.ScriptedProcessBuilder encodingFfmpeg() {
    return ScriptedProcess.builder()
        .output(bytesOf(ENCODED_RECORDING))
        .exitTiming(ExitTiming.AT_END_OF_INPUT);
  }

  private Producer.ProducerBuilder producerOf(ScriptedProcess copy, ScriptedProcess encode) {
    return producer((command, jobAttemptId) -> command.equals(COPY_COMMAND) ? copy : encode);
  }

  private Producer.ProducerBuilder producer(ProcessLauncher launcher) {
    var recording = recording(ENCODED_RECORDING);
    return Producer.builder()
        .launcher(launcher)
        .pipeline(new FfmpegPipeline.RpuMovingPipe(COPY_COMMAND, ENCODE_COMMAND))
        .jobAttemptId(JOB_ATTEMPT_ID)
        .gracePeriod(Duration.ofSeconds(5))
        .stallTimeout(Duration.ofMinutes(1))
        .encodedFrameRate(Optional.of(recording.source().videoFrameRate()))
        .videoDecision(VideoDecisionFixtures.forRecording(recording))
        .periodSeconds(recording.period())
        .startSequenceNumber(recording.startSequenceNumber())
        .memoryBudget(SegmentMemoryBudget.forSlots(1))
        .sink(sink)
        .clock(MonotonicClock.system());
  }

  private static List<String> videoSamplesOf(byte[] stream) {
    try {
      return Mp4Stream.read(Mp4Stream.readerOf(stream)).fragments().stream()
          .flatMap(
              fragment ->
                  fragment.videoSamples().stream()
                      .map(
                          sample ->
                              HexFormat.of()
                                  .formatHex(
                                      fragment.mediaDataBox(),
                                      sample.mediaDataPosition(),
                                      sample.mediaDataPosition() + Math.toIntExact(sample.size()))))
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Failed failureOf(Producer producer) {
    return assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .asInstanceOf(InstanceOfAssertFactories.type(Failed.class))
        .actual();
  }

  private static ConditionFactory awaiting() {
    return await().atMost(OUTCOME_LIMIT).pollInterval(Duration.ofMillis(5));
  }
}
