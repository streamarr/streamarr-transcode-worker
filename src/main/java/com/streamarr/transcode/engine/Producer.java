package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.engine.AttemptOutcome.Stopped;
import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns one job attempt's FFmpeg process: reads its fragmented MP4 output on a virtual thread,
 * groups the fragments into media segments, delivers the initialization segment and then each media
 * segment to the sink, and settles the attempt's outcome once FFmpeg has exited.
 */
@Slf4j
public final class Producer {

  // The server's segment cap; the reader admits no initialization segment or fragment above it.
  private static final long MAXIMUM_SEGMENT_BYTES = 16L * 1024 * 1024;

  private static final Duration ERROR_OUTPUT_WAIT = Duration.ofSeconds(1);
  private static final int ERROR_OUTPUT_DETAIL_LIMIT = 2000;

  private final Process process;
  private final StderrDrainer errorOutput;
  private final FragmentedMp4Reader reader;
  private final SegmentGrouper grouper;
  private final SegmentSink sink;
  private final Duration gracePeriod;
  private final CompletableFuture<AttemptOutcome> outcome = new CompletableFuture<>();
  private final Object lock = new Object();

  // Guarded by lock.
  private boolean stopRequested;
  private boolean settled;

  // Confined to the reader thread.
  private boolean mediaSegmentDelivered;

  private Producer(Process process, Settings settings) {
    this.process = process;
    this.errorOutput = new StderrDrainer(process.getErrorStream());
    this.reader = new FragmentedMp4Reader(process.getInputStream(), MAXIMUM_SEGMENT_BYTES);
    this.grouper = settings.grouper();
    this.sink = settings.sink();
    this.gracePeriod = settings.gracePeriod();
  }

  /**
   * Starts FFmpeg and reads its output until the attempt settles.
   *
   * @param gracePeriod how long a stop waits for FFmpeg to exit after asking it to quit
   * @throws TranscodeException when FFmpeg cannot be started
   */
  @Builder(buildMethodName = "start")
  private static Producer launch(
      @NonNull ProcessLauncher launcher,
      @NonNull List<String> command,
      @NonNull UUID jobAttemptId,
      int periodSeconds,
      int startSequenceNumber,
      @NonNull Duration gracePeriod,
      @NonNull SegmentSink sink) {
    var settings =
        new Settings(new SegmentGrouper(periodSeconds, startSequenceNumber), sink, gracePeriod);
    Process process;
    try {
      process = launcher.launch(command, jobAttemptId);
    } catch (IOException e) {
      throw new TranscodeException(TranscodeException.GENERIC_MESSAGE, e);
    }

    var producer = new Producer(process, settings);
    Thread.ofVirtual()
        .name("producer-" + jobAttemptId)
        .uncaughtExceptionHandler(producer::failUnexpectedly)
        .start(producer::produce);
    return producer;
  }

  long pid() {
    return process.pid();
  }

  /** Completes once with the attempt's outcome, after FFmpeg has exited. */
  public CompletableFuture<AttemptOutcome> outcome() {
    return outcome.copy();
  }

  /**
   * Ends the attempt for good and returns once it has an outcome. Unless the attempt has already
   * settled, the producer starts no further delivery, asks FFmpeg to quit, discards the rest of its
   * output, destroys FFmpeg when it has not exited within the grace period, and settles the stop
   * after FFmpeg has exited; a failure observed after the stop is never reported.
   */
  public void stop() {
    if (tryRecordStop()) {
      requestQuit();
      awaitExitWithinGracePeriod();
      settle(new Stopped());
    }

    outcome.join();
  }

  private boolean tryRecordStop() {
    synchronized (lock) {
      if (settled || stopRequested) {
        return false;
      }

      stopRequested = true;
      return true;
    }
  }

  private boolean isStopRequested() {
    synchronized (lock) {
      return stopRequested;
    }
  }

  private void requestQuit() {
    try {
      var input = process.getOutputStream();
      input.write('q');
      input.flush();
    } catch (IOException _) {
      // FFmpeg has already closed its input; the exit wait observes it ending.
    }
  }

  private void awaitExitWithinGracePeriod() {
    try {
      if (process.waitFor(gracePeriod.toNanos(), TimeUnit.NANOSECONDS)) {
        return;
      }
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }

    process.destroyForcibly();
    process.onExit().join();
  }

  private void settle(AttemptOutcome settledOutcome) {
    if (tryClaimSettlement(settledOutcome)) {
      outcome.complete(settledOutcome);
    }
  }

  private boolean tryClaimSettlement(AttemptOutcome settledOutcome) {
    synchronized (lock) {
      // Once a stop is recorded, only the stop settles the attempt.
      var preemptedByStop = stopRequested && !(settledOutcome instanceof Stopped);
      if (settled || preemptedByStop) {
        return false;
      }

      settled = true;
      return true;
    }
  }

  private void produce() {
    try {
      outcomeOf(readAndDeliver()).ifPresent(this::settle);
    } finally {
      errorOutput.close();
    }
  }

  // Whatever escapes the reader thread still ends FFmpeg and settles the attempt.
  private void failUnexpectedly(Thread readerThread, Throwable error) {
    log.error("{} failed unexpectedly", readerThread.getName(), error);
    outcomeAfterEndingProcess(new Failed(ProducerFailure.UNEXPECTED_ERROR, error.toString()))
        .ifPresent(this::settle);
  }

  // Empty when the stop settles the attempt.
  private Optional<AttemptOutcome> outcomeOf(Ending ending) {
    return switch (ending) {
      case EndOfOutput _ -> Optional.of(outcomeAtExit(outcomeOfCompleteOutput()));
      case TruncatedOutput(var failure) -> Optional.of(outcomeAtExit(failure));
      case Abandoned(var failure) -> outcomeAfterEndingProcess(failure);
      case StopObserved _ -> discardRemainingOutput();
    };
  }

  private Ending readAndDeliver() {
    try {
      return deliverEachSegment();
    } catch (FragmentedMp4Exception e) {
      return endingOf(e);
    } catch (IOException e) {
      return new Abandoned(new Failed(ProducerFailure.OUTPUT_UNREADABLE, e.toString()));
    }
  }

  private Ending deliverEachSegment() throws IOException {
    for (var unit = reader.next(); unit.isPresent(); unit = reader.next()) {
      var interruption = deliverClosedSegment(unit.orElseThrow());
      if (interruption.isPresent()) {
        return interruption.orElseThrow();
      }
    }

    return grouper
        .finish()
        .map(ProducedSegment::of)
        .flatMap(this::deliver)
        .orElseGet(EndOfOutput::new);
  }

  // Empty unless the unit closed a segment whose delivery ends reading.
  private Optional<Ending> deliverClosedSegment(Mp4Unit unit) {
    var closed =
        switch (unit) {
          case InitializationSegment initializationSegment ->
              Optional.of(ProducedSegment.of(initializationSegment));
          case Fragment fragment -> grouper.accept(fragment).map(ProducedSegment::of);
        };
    return closed.flatMap(this::deliver);
  }

  // Empty once the sink has accepted the segment; otherwise why reading ends.
  private Optional<Ending> deliver(ProducedSegment segment) {
    if (isStopRequested()) {
      return Optional.of(new StopObserved());
    }

    try {
      sink.deliver(segment);
    } catch (RuntimeException e) {
      return Optional.of(
          new Abandoned(new Failed(ProducerFailure.SEGMENT_NOT_ACCEPTED, segment + ": " + e)));
    }

    if (segment instanceof ProducedSegment.Media) {
      mediaSegmentDelivered = true;
    }

    return Optional.empty();
  }

  // The output ends where it cannot be delivered; a truncated output has already ended.
  private static Ending endingOf(FragmentedMp4Exception exception) {
    var failure = new Failed(failureOf(exception.getReason()), exception.getMessage());
    if (failure.reason() == ProducerFailure.TRUNCATED_OUTPUT) {
      return new TruncatedOutput(failure);
    }

    return new Abandoned(failure);
  }

  private static ProducerFailure failureOf(Reason reason) {
    return switch (reason) {
      case END_OF_FILE_IN_BOX_HEADER, END_OF_FILE_IN_BOX_BODY, END_OF_FILE_AFTER_MOVIE_FRAGMENT ->
          ProducerFailure.TRUNCATED_OUTPUT;
      case EXCEEDS_SEGMENT_CAP -> ProducerFailure.SEGMENT_CAP_EXCEEDED;
      case SKIPPED_SEGMENT_NUMBER -> ProducerFailure.SKIPPED_SEGMENT_NUMBER;
      case UNSIZED_BOX,
          MALFORMED_BOX,
          MISSING_INITIALIZATION_SEGMENT,
          MISPLACED_INITIALIZATION_SEGMENT,
          UNEXPECTED_BOX,
          MULTIPLE_VIDEO_TRACKS,
          PRESENTATION_TIME_REGRESSED ->
          ProducerFailure.MALFORMED_OUTPUT;
    };
  }

  private AttemptOutcome outcomeOfCompleteOutput() {
    if (!mediaSegmentDelivered) {
      return new Failed(
          ProducerFailure.NO_MEDIA_SEGMENT, "FFmpeg exited cleanly without a media segment");
    }

    return new Completed();
  }

  // A non-zero exit explains whatever the output lacks, so it takes precedence.
  private AttemptOutcome outcomeAtExit(AttemptOutcome outcomeOnCleanExit) {
    var exitCode = process.onExit().join().exitValue();
    if (exitCode != 0) {
      return new Failed(ProducerFailure.PROCESS_EXITED_WITH_ERROR, exitDetail(exitCode));
    }

    return outcomeOnCleanExit;
  }

  // FFmpeg reports why it failed at the end of its error output.
  private String exitDetail(int exitCode) {
    var recentErrorOutput = String.join("\n", errorOutput.awaitRecentOutput(ERROR_OUTPUT_WAIT));
    var tailStart = Math.max(0, recentErrorOutput.length() - ERROR_OUTPUT_DETAIL_LIMIT);
    return "FFmpeg exited with exit code "
        + exitCode
        + ": "
        + recentErrorOutput.substring(tailStart);
  }

  // A stop in progress lets FFmpeg flush and quit rather than destroying it.
  private Optional<AttemptOutcome> outcomeAfterEndingProcess(Failed failure) {
    if (isStopRequested()) {
      return discardRemainingOutput();
    }

    process.destroyForcibly();
    process.onExit().join();
    return Optional.of(failure);
  }

  // Reading to the end lets FFmpeg flush its last fragment and exit after a quit; the stop, not
  // the reader, settles the attempt.
  private Optional<AttemptOutcome> discardRemainingOutput() {
    try {
      process.getInputStream().transferTo(OutputStream.nullOutputStream());
    } catch (IOException _) {
      // Nothing read after a stop matters.
    }

    return Optional.empty();
  }

  // Why the producer stopped reading FFmpeg's output.
  private sealed interface Ending {}

  // The output ended on a box boundary.
  private record EndOfOutput() implements Ending {}

  // The output ended inside a box or after a moof with no mdat.
  private record TruncatedOutput(Failed failure) implements Ending {}

  // The attempt failed while FFmpeg may still be writing.
  private record Abandoned(Failed failure) implements Ending {}

  // A stop was recorded before the next delivery.
  private record StopObserved() implements Ending {}

  private record Settings(SegmentGrouper grouper, SegmentSink sink, Duration gracePeriod) {}
}
