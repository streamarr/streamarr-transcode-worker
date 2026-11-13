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
import static com.streamarr.transcode.engine.IsoBoxes.u32;
import static com.streamarr.transcode.engine.RpuMovingCopies.videoSamplesOf;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.engine.AttemptOutcome.Stopped;
import com.streamarr.transcode.engine.SourceEnd.StreamEnd;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcess.ExitTiming;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Producer RPU Moving Pipe Tests")
class ProducerRpuMovingPipeTest {

  // A scripted FFmpeg writes no framecrc of the source's streams.
  private static final SourceEnd NOTHING_READ_FROM_THE_SOURCE =
      new SourceEnd(List.of(), Optional.empty());

  private static final Duration OUTCOME_LIMIT = Duration.ofSeconds(10);
  private static final Duration POLL_INTERVAL = Duration.ofMillis(5);
  private static final UUID JOB_ATTEMPT_ID =
      UUID.fromString("5b0f8a8e-29b5-4c48-9f0c-7f4f3c1a9d21");
  private static final List<String> COPY_COMMAND = List.of("copy-ffmpeg");
  private static final List<String> ENCODE_COMMAND = List.of("encode-ffmpeg");
  private static final byte[] COPIED_SOURCE = RpuMovingCopies.copied();
  private static final byte[] NOT_HEVC =
      concat(
          initializationSegment(box("avc1", new byte[78], box("avcC", new byte[7]))),
          fragmentBuilder().video(0, sample(slice(0))).build());
  private static final byte[] MOVED_SOURCE = RpuMovingCopies.moved();

  private final RecordingSegmentSink sink = new RecordingSegmentSink();

  @Test
  @DisplayName(
      "Should pass the copy to the encoding FFmpeg with its RPUs moved, and then end that FFmpeg's"
          + " input, when both FFmpegs exit cleanly")
  void
      shouldPassTheCopyToTheEncodingFfmpegWithItsRpusMovedAndThenEndThatFfmpegsInputWhenBothFfmpegsExitCleanly() {
    var copy = ScriptedProcess.builder().output(COPIED_SOURCE).build();
    var encode = encodingFfmpeg().build();

    var producer = producerOf(copy, encode).start();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT);
    assertThat(videoSamplesOf(encode.stdinBytes())).isEqualTo(videoSamplesOf(MOVED_SOURCE));
    assertThat(encode.hasInputEnded()).isTrue();
  }

  @Test
  @DisplayName(
      "Should pass the copy on with its RPUs moved when the framecrc's keyframe opens a fragment"
          + " whose first sample flags make it a sync sample")
  void
      shouldPassTheCopyOnWithItsRpusMovedWhenTheFramecrcsKeyframeOpensAFragmentWhoseFirstSampleFlagsMakeItASyncSample() {
    var copy =
        ScriptedProcess.builder()
            .output(
                concat(
                    initializationSegment(),
                    fragmentBuilder()
                        .startsWithKeyframe(true)
                        .video(0, sample(aud(), slice(0)))
                        .build(),
                    fragmentBuilder().video(1001, sample(aud(), rpu(0), slice(1))).build()))
            .build();
    var encode = encodingFfmpeg().build();
    var framecrc =
        """
        #tb 0: 1/48000
        0,          0,       4004,     2002,        9, 0x00000000
        0,       2002,       6006,     2002,       20, 0x00000000, F=0x0
        """;

    var producer = producer(copyingWith(copy, framecrc), encode).start();

    assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .isEqualTo(new Completed(NOTHING_READ_FROM_THE_SOURCE));
    assertThat(videoSamplesOf(encode.stdinBytes())).isEqualTo(videoSamplesOf(MOVED_SOURCE));
  }

  @Test
  @DisplayName(
      "Should pass the copy on unchanged when a NAL unit that FFmpeg's splitter drops follows the"
          + " RPU that ends its picture")
  void
      shouldPassTheCopyOnUnchangedWhenANalUnitThatFfmpegsSplitterDropsFollowsTheRpuThatEndsItsPicture() {
    // nuh_temporal_id_plus1 is 0, so FFmpeg's HEVC splitter drops the unit before the decoder.
    var dropped = HexFormat.of().parseHex("460050");
    var copied =
        concat(
            initializationSegment(),
            fragmentBuilder()
                .video(0, sample(aud(), slice(0), rpu(0), concat(u32(dropped.length), dropped)))
                .build(),
            fragmentBuilder().video(1001, sample(aud(), slice(1), rpu(1))).build());
    var copy = ScriptedProcess.builder().output(copied).build();
    var encode = encodingFfmpeg().build();

    var producer = producerOf(copy, encode).start();

    assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .isEqualTo(new Completed(NOTHING_READ_FROM_THE_SOURCE));
    assertThat(videoSamplesOf(encode.stdinBytes())).isEqualTo(videoSamplesOf(copied));
  }

  @Test
  @DisplayName(
      "Should complete the attempt with the encoding FFmpeg's segments when both FFmpegs exit"
          + " cleanly")
  void shouldCompleteTheAttemptWithTheEncodingFfmpegsSegmentsWhenBothFfmpegsExitCleanly() {
    var copy = ScriptedProcess.builder().output(COPIED_SOURCE).build();
    var encode = encodingFfmpeg().build();

    var producer = producerOf(copy, encode).start();

    assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .isEqualTo(new Completed(NOTHING_READ_FROM_THE_SOURCE));
    assertThat(sink.acceptedNames())
        .hasSize(recording(ENCODED_RECORDING).segments().size() + 1)
        .startsWith("init.mp4", "segment0.m4s");
  }

  // The encoding FFmpeg reads the source's audio itself, as its second input, and copies the
  // source's streams to the framecrc there; the copying FFmpeg reads only the source's video.
  @Test
  @DisplayName(
      "Should report where the source's streams end from the encoding FFmpeg's framecrc of its"
          + " second input when both FFmpegs exit cleanly")
  void
      shouldReportWhereTheSourcesStreamsEndFromTheEncodingFfmpegsFramecrcOfItsSecondInputWhenBothFfmpegsExitCleanly() {
    var copy = ScriptedProcess.builder().output(COPIED_SOURCE).build();
    var encode =
        encodingFfmpeg()
            .stderr(
                """
                Stream mapping:
                  Stream #0:0 -> #0:0 (hevc (native) -> h264 (libx264))
                  Stream #1:1 -> #0:1 (aac (native) -> aac (native))
                  Stream #1:0 -> #1:0 (copy)
                  Stream #1:1 -> #1:1 (copy)
                #tb 0: 1/1000
                #tb 1: 1/48000
                0, 20000, 20000, 40, 1000, 0x0a1b2c3d
                1, 1200000, 1200000, 1024, 300, 0x0a1b2c3d
                """)
            .build();

    var producer = producerOf(copy, encode).start();

    assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .asInstanceOf(InstanceOfAssertFactories.type(Completed.class))
        .extracting(completed -> completed.sourceEnd().streamEnds())
        .isEqualTo(
            List.of(
                new StreamEnd(0, Duration.ofMillis(20_040)),
                new StreamEnd(1, Duration.ofNanos(25_021_333_333L))));
  }

  @Test
  @DisplayName(
      "Should pass each video sample to the encoding FFmpeg with the duration of its packet in the"
          + " source when the copying FFmpeg writes that timing beside the copy")
  void
      shouldPassEachVideoSampleToTheEncodingFfmpegWithTheDurationOfItsPacketInTheSourceWhenTheCopyingFfmpegWritesThatTimingBesideTheCopy() {
    var copy = ScriptedProcess.builder().output(COPIED_SOURCE).build();
    var encode = encodingFfmpeg().build();
    var framecrc =
        """
        #tb 0: 1/48000
        0,          0,       4004,     1968,        9, 0x00000000, F=0x0
        0,       2002,       6006,     2952,       20, 0x00000000, F=0x0
        """;

    var producer = producer(copyingWith(copy, framecrc), encode).start();

    assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .isEqualTo(new Completed(NOTHING_READ_FROM_THE_SOURCE));
    assertThat(videoDurationsOf(encode.stdinBytes())).containsExactly(984L, 1476L);
  }

  @Test
  @DisplayName(
      "Should fail the attempt as a truncated output when the copying FFmpeg exits cleanly without"
          + " writing the timing of the copy's video")
  void
      shouldFailTheAttemptAsATruncatedOutputWhenTheCopyingFfmpegExitsCleanlyWithoutWritingTheTimingOfTheCopysVideo() {
    var copy =
        ScriptedProcess.builder().output(COPIED_SOURCE).exitTiming(ExitTiming.AT_LAUNCH).build();
    var encode = encodingFfmpegReadingTheSourcesAudioUntilTerminated();

    var producer = producer(copyingWith(copy, ""), encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.TRUNCATED_OUTPUT);
    assertThat(failure.detail()).contains("END_OF_VIDEO_TIMING");
  }

  // The encoding FFmpeg reads the source's audio itself, so the end of its input from the copy
  // would not end it before the end of the source's audio.
  @Test
  @DisplayName(
      "Should keep counting a held producer as stopped when one of its FFmpegs exits while the"
          + " other survives")
  void shouldKeepCountingAHeldProducerAsStoppedWhenOneOfItsFfmpegsExitsWhileTheOtherSurvives() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var encode = encodingFfmpeg().pauseAfter(0).exitTiming(ExitTiming.WHEN_TEST_EXITS).build();
    var pauses = new ProducerPauses();
    var producer = producerOf(copy, encode).pauses(pauses).start();
    assertThat(encode.pauseReached()).succeedsWithin(OUTCOME_LIMIT);
    assertThat(producer.holdBackFfmpeg()).isTrue();

    encode.exit();

    assertThat(pauses.stoppedProducers()).isOne();
    copy.exit();
    assertThat(pauses.stoppedProducers()).isZero();
    producer.releaseHeldFfmpeg();
    encode.resume();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT);
    assertThat(pauses.stoppedProducers()).isZero();
  }

  // The transfer meets the exited encoding FFmpeg's closed pipe and terminates the copying FFmpeg,
  // which takes no termination while the hold stops it.
  @Test
  @DisplayName(
      "Should keep counting a held producer as stopped until its release when the encoding FFmpeg"
          + " exits while passing the copy on waits for it to read")
  void
      shouldKeepCountingAHeldProducerAsStoppedUntilItsReleaseWhenTheEncodingFfmpegExitsWhilePassingTheCopyOnWaitsForItToRead() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var encode =
        encodingFfmpeg()
            .stdinCapacity(0)
            .pauseAfter(0)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var pauses = new ProducerPauses();
    var producer = producerOf(copy, encode).pauses(pauses).start();
    assertThat(encode.inputBlocked()).succeedsWithin(OUTCOME_LIMIT);
    assertThat(producer.holdBackFfmpeg()).isTrue();

    encode.exit();

    await().atMost(OUTCOME_LIMIT).pollInterval(POLL_INTERVAL).until(copy::wasTerminated);
    assertThat(pauses.stoppedProducers()).isOne();
    producer.releaseHeldFfmpeg();
    assertThat(pauses.stoppedProducers()).isZero();
    encode.resume();
    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT);
  }

  @Test
  @DisplayName(
      "Should ask the copying FFmpeg to quit, and the encoding FFmpeg to terminate, when the attempt"
          + " is stopped")
  void shouldAskTheCopyingFfmpegToQuitAndTheEncodingFfmpegToTerminateWhenTheAttemptIsStopped() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .pauseAfter(COPIED_SOURCE.length / 2)
            .resumesOnQuit(true)
            .exitTiming(ExitTiming.AT_END_OF_OUTPUT)
            .build();
    var encode = encodingFfmpeg().pauseAfter(0).build();
    var producer = producerOf(copy, encode).start();
    assertThat(copy.pauseReached()).succeedsWithin(OUTCOME_LIMIT);

    producer.stop();

    assertThat(producer.outcome()).isCompletedWithValue(new Stopped());
    assertThat(copy.stdinText()).isEqualTo("q");
    assertThat(encode.wasTerminated()).isTrue();
    assertThat(copy.wasDestroyedForcibly()).isFalse();
    assertThat(encode.wasDestroyedForcibly()).isFalse();
  }

  @Test
  @DisplayName(
      "Should fail the attempt with the copying FFmpeg's error output, and terminate the encoding"
          + " FFmpeg, when the copying FFmpeg exits with an error after a copy of whole units")
  void
      shouldFailTheAttemptWithTheCopyingFfmpegsErrorOutputAndTerminateTheEncodingFfmpegWhenTheCopyingFfmpegExitsWithAnErrorAfterACopyOfWholeUnits() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .exitCode(1)
            .stderr("Error reading the source: Input/output error\n")
            .build();
    var encode = encodingFfmpegReadingTheSourcesAudioUntilTerminated();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.PROCESS_EXITED_WITH_ERROR);
    assertThat(failure.detail())
        .startsWith("The FFmpeg that copies the source exited with exit code 1")
        .endsWith("Error reading the source: Input/output error");
    assertThat(encode.wasTerminated()).isTrue();
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
    assertThat(failure.reason()).isEqualTo(ProducerFailure.PROCESS_EXITED_WITH_ERROR);
    assertThat(failure.detail())
        .startsWith("FFmpeg exited with exit code 1")
        .endsWith("Invalid data found when processing input");
    assertThat(copy.wasTerminated()).as("the copying FFmpeg was asked to terminate").isTrue();
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
          if (isCopying(command)) {
            return copy;
          }

          throw new IOException("Cannot run program encode-ffmpeg");
        };
    var producer = producer(launcher);

    assertThatExceptionOfType(TranscodeException.class).isThrownBy(producer::start);
    assertThat(copy.wasDestroyedForcibly()).isTrue();
  }

  @Test
  @DisplayName(
      "Should refuse to start the attempt, leaving no timing socket, when the copying FFmpeg cannot"
          + " be started")
  void shouldRefuseToStartTheAttemptLeavingNoTimingSocketWhenTheCopyingFfmpegCannotBeStarted() {
    var copyCommand = new AtomicReference<List<String>>();
    ProcessLauncher launcher =
        (command, jobAttemptId) -> {
          copyCommand.set(command);
          throw new IOException("Cannot run program copy-ffmpeg");
        };

    var producer = producer(launcher);

    assertThatExceptionOfType(TranscodeException.class).isThrownBy(producer::start);
    assertThat(socketDirectoryOf(copyCommand.get())).doesNotExist();
  }

  @Test
  @DisplayName(
      "Should fail the attempt for the mover's reason, and end both FFmpegs, when the mover refuses"
          + " the copy")
  void shouldFailTheAttemptForTheMoversReasonAndEndBothFfmpegsWhenTheMoverRefusesTheCopy() {
    var copy =
        ScriptedProcess.builder().output(NOT_HEVC).exitTiming(ExitTiming.WHEN_TEST_EXITS).build();
    var encode = encodingFfmpegReadingTheSourcesAudioUntilTerminated();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.OUTPUT_MISMATCH);
    assertThat(failure.detail()).contains("copy of the source", "no HEVC video");
    assertThat(copy.wasTerminated()).isTrue();
    assertThat(encode.hasInputEnded()).isTrue();
    assertThat(encode.wasTerminated()).isTrue();
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

  // The mover's own 32 MiB cap on what it reads of the copy says nothing about the segment cap of
  // the output, which a plain-grid fallback would not change.
  @Test
  @DisplayName(
      "Should fail the attempt as a copy unit too large for the mover, not as output over the"
          + " segment cap, and end the encoding FFmpeg, when the copy writes a fragment larger than"
          + " the mover reads")
  void
      shouldFailTheAttemptAsACopyUnitTooLargeForTheMoverAndEndTheEncodingFfmpegWhenTheCopyWritesAnOversizedFragment() {
    var copy =
        ScriptedProcess.builder()
            .output(concat(initializationSegment(), IsoBoxes.header(32L * 1024 * 1024 + 1, "moof")))
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var encode = encodingFfmpegReadingTheSourcesAudioUntilTerminated();

    var producer = producerOf(copy, encode).start();

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.COPY_UNIT_TOO_LARGE);
    assertThat(encode.wasTerminated())
        .as("the encoding FFmpeg, which would read the source's audio to its end")
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should fail the attempt as a truncated copy, and terminate the encoding FFmpeg, when the copy"
          + " ends inside a box while that FFmpeg still reads the source's audio")
  void
      shouldFailTheAttemptAsATruncatedCopyAndTerminateTheEncodingFfmpegWhenTheCopyEndsInsideABoxWhileThatFfmpegStillReadsTheSourcesAudio() {
    var copy =
        ScriptedProcess.builder()
            .output(Arrays.copyOf(COPIED_SOURCE, COPIED_SOURCE.length - 1))
            .build();
    var encode = encodingFfmpegReadingTheSourcesAudioUntilTerminated();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.TRUNCATED_OUTPUT);
    assertThat(failure.detail()).startsWith("The copy of the source cannot be passed");
    assertThat(encode.wasTerminated()).isTrue();
  }

  // A hung FFmpeg ignores termination and writes nothing, so the reader never reaches the end of
  // its output, where the producer's own grace period starts.
  @Test
  @DisplayName(
      "Should end the encoding FFmpeg forcibly after the grace period, and fail the attempt as a"
          + " truncated copy, when that FFmpeg ignores the termination that the cut copy sends")
  void
      shouldEndTheEncodingFfmpegForciblyAfterTheGracePeriodAndFailTheAttemptAsATruncatedCopyWhenThatFfmpegIgnoresTheTerminationThatTheCutCopySends() {
    var copy =
        ScriptedProcess.builder()
            .output(Arrays.copyOf(COPIED_SOURCE, COPIED_SOURCE.length - 1))
            .build();
    var encode =
        encodingFfmpeg()
            .pauseAfter(0)
            .ignoresTermination(true)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();

    var producer = producerOf(copy, encode).gracePeriod(Duration.ofMillis(200)).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.TRUNCATED_OUTPUT);
    assertThat(failure.detail()).startsWith("The copy of the source cannot be passed");
    assertThat(encode.wasTerminated()).isTrue();
    assertThat(encode.wasDestroyedForcibly()).isTrue();
  }

  @Test
  @DisplayName(
      "Should fail the attempt with the copying FFmpeg's error output, not the terminated encoding"
          + " FFmpeg's exit, when the copy ends inside a box and the copying FFmpeg exits with an"
          + " error")
  void
      shouldFailTheAttemptWithTheCopyingFfmpegsErrorOutputNotTheTerminatedEncodingFfmpegsExitWhenTheCopyEndsInsideABoxAndTheCopyingFfmpegExitsWithAnError() {
    var copy =
        ScriptedProcess.builder()
            .output(Arrays.copyOf(COPIED_SOURCE, COPIED_SOURCE.length - 1))
            .exitCode(1)
            .stderr("Error reading the source: Input/output error\n")
            .build();
    var encode = encodingFfmpegReadingTheSourcesAudioUntilTerminated();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.PROCESS_EXITED_WITH_ERROR);
    assertThat(failure.detail())
        .startsWith("The FFmpeg that copies the source exited with exit code 1");
    assertThat(encode.wasTerminated()).isTrue();
  }

  @Test
  @DisplayName(
      "Should fail the attempt as an unexpected error, and end both FFmpegs, when passing the copy"
          + " on throws unexpectedly")
  void
      shouldFailTheAttemptAsAnUnexpectedErrorAndEndBothFfmpegsWhenPassingTheCopyOnThrowsUnexpectedly() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .failReadAfter(initializationSegment().length)
            .failReadWith(new IllegalStateException("defect in the copy's transfer"))
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var encode = encodingFfmpegReadingTheSourcesAudioUntilTerminated();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.UNEXPECTED_ERROR);
    assertThat(failure.detail()).contains("defect in the copy's transfer");
    assertThat(copy.wasTerminated()).isTrue();
    assertThat(encode.hasInputEnded()).isTrue();
    assertThat(encode.wasTerminated())
        .as("the encoding FFmpeg, which would read the source's audio to its end")
        .isTrue();
  }

  @Test
  @DisplayName(
      "Should fail the attempt as a truncated output, and remove the timing's socket, when the"
          + " copying FFmpeg exits without connecting to it")
  void
      shouldFailTheAttemptAsATruncatedOutputAndRemoveTheTimingsSocketWhenTheCopyingFfmpegExitsWithoutConnectingToIt() {
    var copy =
        ScriptedProcess.builder().output(COPIED_SOURCE).exitTiming(ExitTiming.AT_LAUNCH).build();
    var encode = encodingFfmpegReadingTheSourcesAudioUntilTerminated();
    var copyCommand = new AtomicReference<List<String>>();
    ProcessLauncher neverConnecting =
        (command, jobAttemptId) -> {
          if (!isCopying(command)) {
            return encode;
          }

          copyCommand.set(command);
          return copy;
        };

    var producer = producer(neverConnecting).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.TRUNCATED_OUTPUT);
    assertThat(failure.detail()).contains("never connected");
    assertThat(socketDirectoryOf(copyCommand.get())).doesNotExist();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unusableTimings")
  @DisplayName(
      "Should fail the attempt as malformed output when the copy's video timing is unusable")
  void shouldFailTheAttemptAsMalformedOutputWhenTheCopysVideoTimingIsUnusable(String framecrc) {
    var copy = ScriptedProcess.builder().output(COPIED_SOURCE).build();
    var encode = encodingFfmpegReadingTheSourcesAudioUntilTerminated();
    ProcessLauncher writingTiming =
        (command, jobAttemptId) -> {
          if (!isCopying(command)) {
            return encode;
          }

          writeTimingAndAwaitHangUp(command, framecrc);
          return copy;
        };

    var producer = producer(writingTiming).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.MALFORMED_OUTPUT);
    assertThat(failure.detail()).contains("UNUSABLE_VIDEO_TIMING");
  }

  // ADR 0013: a timing more than 65,536 packets ahead of the copy fails as malformed output.
  static Stream<Arguments> unusableTimings() {
    return Stream.of(
        Arguments.of(Named.of("no framecrc", "frame=   48 fps=0.0 q=-1.0 size=     138kB\n")),
        Arguments.of(
            Named.of(
                "the timing of more than 65,536 packets ahead of the copy",
                "#tb 0: 1/24000\n" + "0, 0, 0, 1001, 9, 0x00000000\n".repeat(65_536 + 1))));
  }

  @Test
  @DisplayName(
      "Should fail the attempt as an unexpected error when passing the copy on throws unexpectedly"
          + " and the encoding FFmpeg exits with an error")
  void
      shouldFailTheAttemptAsAnUnexpectedErrorWhenPassingTheCopyOnThrowsUnexpectedlyAndTheEncodingFfmpegExitsWithAnError() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .failReadAfter(initializationSegment().length)
            .failReadWith(new IllegalStateException("defect in the copy's transfer"))
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var encode =
        encodingFfmpeg().output(new byte[0]).exitCode(1).stderr("Conversion failed!\n").build();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.UNEXPECTED_ERROR);
    assertThat(failure.detail()).contains("defect in the copy's transfer");
  }

  // The copy reached the encoding FFmpeg whole, so the copying FFmpeg failed while the encoding one
  // still read the source's audio.
  @Test
  @DisplayName(
      "Should fail the attempt with the copying FFmpeg's error output when both FFmpegs exit with"
          + " an error after a copy of whole units")
  void
      shouldFailTheAttemptWithTheCopyingFfmpegsErrorOutputWhenBothFfmpegsExitWithAnErrorAfterACopyOfWholeUnits() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .exitCode(1)
            .stderr("Error reading the source: Input/output error\n")
            .build();
    var encode =
        encodingFfmpeg().output(new byte[0]).exitCode(1).stderr("Conversion failed!\n").build();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.PROCESS_EXITED_WITH_ERROR);
    assertThat(failure.detail())
        .startsWith("The FFmpeg that copies the source exited with exit code 1")
        .endsWith("Error reading the source: Input/output error");
  }

  @Test
  @DisplayName(
      "Should fail the attempt as unreadable output, and terminate the encoding FFmpeg, when reading"
          + " the copy fails")
  void
      shouldFailTheAttemptAsUnreadableOutputAndTerminateTheEncodingFfmpegWhenReadingTheCopyFails() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .failReadAfter(initializationSegment().length)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var encode = encodingFfmpegReadingTheSourcesAudioUntilTerminated();

    var producer = producerOf(copy, encode).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.OUTPUT_UNREADABLE);
    assertThat(failure.detail())
        .contains("did not reach the FFmpeg that encodes it", "scripted read failure");
    assertThat(encode.hasInputEnded()).isTrue();
    assertThat(encode.wasTerminated()).isTrue();
  }

  // A stop terminates the encoding FFmpeg, and a transfer that then meets its closed pipe
  // terminates the copying FFmpeg. A copy that ignores termination is still running whenever the
  // interrupt arrives.
  @Test
  @DisplayName(
      "Should destroy the copying FFmpeg at once when the thread stopping the attempt is"
          + " interrupted while it waits for that FFmpeg to exit")
  void
      shouldDestroyTheCopyingFfmpegAtOnceWhenTheThreadStoppingTheAttemptIsInterruptedWhileItWaitsForThatFfmpegToExit() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .ignoresTermination(true)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var encode = encodingFfmpeg().build();
    var producer = producerOf(copy, encode).gracePeriod(Duration.ofMinutes(10)).start();
    var stopping = Thread.ofVirtual().start(producer::stop);
    assertThat(copy.exitAwaited()).succeedsWithin(OUTCOME_LIMIT);

    stopping.interrupt();

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isEqualTo(new Stopped());
    assertThat(copy.wasDestroyedForcibly()).isTrue();
  }

  @Test
  @DisplayName(
      "Should fail the attempt as a process that did not exit, and terminate the copying FFmpeg,"
          + " when it outlives the grace period after the encoding FFmpeg exits")
  void
      shouldFailTheAttemptAsAProcessThatDidNotExitAndTerminateTheCopyingFfmpegWhenItOutlivesTheGracePeriodAfterTheEncodingFfmpegExits() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var encode = encodingFfmpeg().build();

    var producer = producerOf(copy, encode).gracePeriod(Duration.ofMillis(200)).start();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.PROCESS_DID_NOT_EXIT);
    assertThat(copy.wasTerminated()).isTrue();
  }

  @Test
  @DisplayName(
      "Should fail the attempt as a process that did not exit when passing the copy on outlasts"
          + " the grace period after both FFmpegs exit")
  void
      shouldFailTheAttemptAsAProcessThatDidNotExitWhenPassingTheCopyOnOutlastsTheGracePeriodAfterBothFfmpegsExit() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .pauseAfter(initializationSegment().length)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var encodedOutput = bytesOf(ENCODED_RECORDING);
    var encode =
        encodingFfmpeg()
            .pauseAfter(encodedOutput.length - 1)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var producer = producerOf(copy, encode).gracePeriod(Duration.ofMillis(200)).start();
    assertThat(copy.pauseReached()).succeedsWithin(OUTCOME_LIMIT);

    copy.exit();
    encode.exit();
    encode.resume();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.PROCESS_DID_NOT_EXIT);
  }

  @Test
  @DisplayName(
      "Should hang up on the copying FFmpeg's timing connection when the attempt fails while that"
          + " connection stays open")
  void
      shouldHangUpOnTheCopyingFfmpegsTimingConnectionWhenTheAttemptFailsWhileThatConnectionStaysOpen()
          throws IOException {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var encode = encodingFfmpeg().pauseAfter(0).build();
    var timingConnection = new AtomicReference<SocketChannel>();
    var copyCommand = new AtomicReference<List<String>>();
    ProcessLauncher connectingAndStayingOpen =
        (command, jobAttemptId) -> {
          if (!isCopying(command)) {
            return encode;
          }

          copyCommand.set(command);
          timingConnection.set(connectTo(command));
          write(timingConnection.get(), "#tb 0: 1/24000\n");
          return copy;
        };

    var producer =
        producer(connectingAndStayingOpen)
            .stallTimeout(Duration.ofMillis(200))
            .gracePeriod(Duration.ofMillis(200))
            .start();

    assertThat(failureOf(producer).reason()).isEqualTo(ProducerFailure.ENCODER_STALLED);
    try (var held = timingConnection.get()) {
      assertThat(CompletableFuture.runAsync(() -> readUntilHangUp(held)))
          .succeedsWithin(OUTCOME_LIMIT);
    }

    assertThat(socketDirectoryOf(copyCommand.get())).doesNotExist();
  }

  @Test
  @DisplayName(
      "Should end both FFmpegs when they ignore termination while passing the copy on waits for"
          + " the encoding FFmpeg to read")
  void
      shouldEndBothFfmpegsWhenTheyIgnoreTerminationWhilePassingTheCopyOnWaitsForTheEncodingFfmpegToRead() {
    var copy =
        ScriptedProcess.builder()
            .output(COPIED_SOURCE)
            .ignoresTermination(true)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var encode =
        encodingFfmpeg()
            .stdinCapacity(0)
            .pauseAfter(0)
            .ignoresTermination(true)
            .exitTiming(ExitTiming.WHEN_TEST_EXITS)
            .build();
    var producer = producerOf(copy, encode).gracePeriod(Duration.ofMillis(200)).start();
    assertThat(encode.inputBlocked()).succeedsWithin(OUTCOME_LIMIT);

    encode.resume();

    var failure = failureOf(producer);
    assertThat(failure.reason()).isEqualTo(ProducerFailure.PROCESS_DID_NOT_EXIT);
    assertThat(copy.wasDestroyedForcibly()).isTrue();
    assertThat(encode.wasDestroyedForcibly()).isTrue();
  }

  private static ScriptedProcess.ScriptedProcessBuilder encodingFfmpeg() {
    return ScriptedProcess.builder()
        .output(bytesOf(ENCODED_RECORDING))
        .readsMediaFromStdin(true)
        .exitTiming(ExitTiming.AT_END_OF_INPUT);
  }

  // The encoding FFmpeg reads the source's audio itself, so the end of the copy does not end it.
  // It writes nothing, because a termination that cut its output inside a box would fail the
  // attempt as malformed output before its exit decides.
  private static ScriptedProcess encodingFfmpegReadingTheSourcesAudioUntilTerminated() {
    return encodingFfmpeg().output(new byte[0]).exitTiming(ExitTiming.WHEN_TEST_EXITS).build();
  }

  // The copying FFmpeg's stand-in writes the framecrc of the copied source's video, whatever the
  // output it replays.
  private Producer.ProducerBuilder producerOf(ScriptedProcess copy, ScriptedProcess encode) {
    return producer(copyingWith(copy, RpuMovingCopies.copiedTiming()), encode);
  }

  // The scripted launcher stops both stand-ins when the producer pauses or holds its FFmpeg.
  private Producer.ProducerBuilder producer(CopyingFfmpeg copying, ScriptedProcess encode) {
    return producer(
        ScriptedProcessLauncher.forCommands(
            (command, _) -> {
              if (!isCopying(command)) {
                return encode;
              }

              writeTimingAndAwaitHangUp(command, copying.framecrc());
              return copying.process();
            }));
  }

  private static CopyingFfmpeg copyingWith(ScriptedProcess process, String framecrc) {
    return new CopyingFfmpeg(process, framecrc);
  }

  // The stand-in of the copying FFmpeg and the framecrc of the copy's video that it writes.
  private record CopyingFfmpeg(ScriptedProcess process, String framecrc) {}

  private static List<Long> videoDurationsOf(byte[] stream) {
    try {
      return Mp4Stream.read(Mp4Stream.readerOf(stream)).fragments().stream()
          .flatMap(fragment -> fragment.videoSamples().stream())
          .map(Sample::duration)
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static boolean isCopying(List<String> command) {
    return command.getFirst().equals(COPY_COMMAND.getFirst());
  }

  private Producer.ProducerBuilder producer(ProcessLauncher launcher) {
    var recording = recording(ENCODED_RECORDING);
    return Producer.builder()
        .launcher(launcher)
        .pipeline(new FfmpegPipeline.RpuMovingPipe(COPY_COMMAND, ENCODE_COMMAND, false))
        .jobAttemptId(JOB_ATTEMPT_ID)
        .gracePeriod(Duration.ofSeconds(5))
        .stallTimeout(Duration.ofMinutes(1))
        .encodedFrameRate(Optional.of(recording.source().videoFrameRate()))
        .videoDecision(VideoDecisionFixtures.forRecording(recording))
        .paddedAudioEnd(Optional.empty())
        .intervals(SegmentIntervals.onPlainGrid(recording.period()))
        .startSequenceNumber(recording.startSequenceNumber())
        .memoryBudget(SegmentMemoryBudget.forSlots(1))
        .pauses(new ProducerPauses())
        .sink(sink)
        .clock(MonotonicClock.system());
  }

  private static Failed failureOf(Producer producer) {
    return assertThat(producer.outcome())
        .succeedsWithin(OUTCOME_LIMIT)
        .asInstanceOf(InstanceOfAssertFactories.type(Failed.class))
        .actual();
  }

  private static SocketChannel connectTo(List<String> copyCommand) throws IOException {
    return SocketChannel.open(
        UnixDomainSocketAddress.of(Path.of(timingUrlOf(copyCommand).substring("unix:".length()))));
  }

  private static String timingUrlOf(List<String> copyCommand) {
    var url = copyCommand.getLast();
    assertThat(url).as("the copying FFmpeg's last output").startsWith("unix:");
    return url;
  }

  private static Path socketDirectoryOf(List<String> copyCommand) {
    return Path.of(timingUrlOf(copyCommand).substring("unix:".length())).getParent();
  }

  // The worker closes its end of the connection only after the timing's end is published, so the
  // transfer meets that end at its first wait for a packet's timing.
  private static void writeTimingAndAwaitHangUp(List<String> copyCommand, String framecrc)
      throws IOException {
    try (var ffmpeg = connectTo(copyCommand)) {
      write(ffmpeg, framecrc);
      ffmpeg.shutdownOutput();
      readUntilHangUp(ffmpeg);
    }
  }

  private static void write(SocketChannel ffmpeg, String framecrc) throws IOException {
    var bytes = ByteBuffer.wrap(framecrc.getBytes(StandardCharsets.US_ASCII));
    while (bytes.hasRemaining()) {
      ffmpeg.write(bytes);
    }
  }

  private static void readUntilHangUp(SocketChannel ffmpeg) {
    try {
      var discarded = ByteBuffer.allocate(64);
      while (ffmpeg.read(discarded) >= 0) {
        discarded.clear();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
