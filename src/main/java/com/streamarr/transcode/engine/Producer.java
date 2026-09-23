package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.Builder;
import lombok.NonNull;

/**
 * Owns one job attempt's FFmpeg process: reads its fragmented MP4 output on a virtual thread,
 * groups the fragments into media segments, delivers the initialization segment and then each media
 * segment to the sink, and settles the attempt's outcome once FFmpeg has exited.
 */
public final class Producer {

  /** The server's segment cap, which bounds the initialization segment and every media segment. */
  public static final long MAXIMUM_SEGMENT_BYTES = 16L * 1024 * 1024;

  private static final Duration ERROR_OUTPUT_WAIT = Duration.ofSeconds(1);
  private static final int ERROR_OUTPUT_DETAIL_LIMIT = 2000;

  private final Process process;
  private final StderrDrainer errorOutput;
  private final FragmentedMp4Reader reader;
  private final SegmentGrouper grouper;
  private final SegmentSink sink;
  private final CompletableFuture<AttemptOutcome> outcome = new CompletableFuture<>();

  private Producer(Process process, SegmentGrouper grouper, SegmentSink sink) {
    this.process = process;
    this.errorOutput = new StderrDrainer(process.getErrorStream());
    this.reader = new FragmentedMp4Reader(process.getInputStream(), MAXIMUM_SEGMENT_BYTES);
    this.grouper = grouper;
    this.sink = sink;
  }

  /**
   * Starts FFmpeg and reads its output until the attempt settles.
   *
   * @throws TranscodeException when FFmpeg cannot be started
   */
  @Builder(buildMethodName = "start")
  private static Producer launch(
      @NonNull ProcessLauncher launcher,
      @NonNull List<String> command,
      @NonNull UUID jobAttemptId,
      int periodSeconds,
      int startSequenceNumber,
      @NonNull SegmentSink sink) {
    var grouper = new SegmentGrouper(periodSeconds, startSequenceNumber);
    Process process;
    try {
      process = launcher.launch(command, jobAttemptId);
    } catch (IOException e) {
      throw new TranscodeException(TranscodeException.GENERIC_MESSAGE, e);
    }

    var producer = new Producer(process, grouper, sink);
    Thread.ofVirtual().name("producer-" + jobAttemptId).start(producer::produce);
    return producer;
  }

  /** Completes once with the attempt's outcome, after FFmpeg has exited. */
  public CompletableFuture<AttemptOutcome> outcome() {
    return outcome.copy();
  }

  private void produce() {
    try {
      switch (readAndDeliver()) {
        case EndOfOutput _ -> settleAtExit();
        case Abandoned(var failure) -> settleAfterEndingProcess(failure);
      }
    } finally {
      errorOutput.close();
    }
  }

  private Ending readAndDeliver() {
    try {
      for (var unit = reader.next(); unit.isPresent(); unit = reader.next()) {
        var closed =
            switch (unit.orElseThrow()) {
              case InitializationSegment initializationSegment ->
                  Optional.of(ProducedSegment.of(initializationSegment));
              case Fragment fragment -> grouper.accept(fragment).map(ProducedSegment::of);
            };
        closed.ifPresent(sink::deliver);
      }

      grouper.finish().map(ProducedSegment::of).ifPresent(sink::deliver);
      return new EndOfOutput();
    } catch (IOException e) {
      return new Abandoned(new Failed(ProducerFailure.OUTPUT_UNREADABLE, e.toString()));
    }
  }

  private void settleAtExit() {
    outcome.complete(outcomeAtExit(process.onExit().join().exitValue()));
  }

  private AttemptOutcome outcomeAtExit(int exitCode) {
    if (exitCode != 0) {
      return new Failed(ProducerFailure.PROCESS_EXITED_WITH_ERROR, exitDetail(exitCode));
    }

    return new Completed();
  }

  /** FFmpeg reports why it failed at the end of its error output. */
  private String exitDetail(int exitCode) {
    var recentErrorOutput = String.join("\n", errorOutput.awaitRecentOutput(ERROR_OUTPUT_WAIT));
    var tailStart = Math.max(0, recentErrorOutput.length() - ERROR_OUTPUT_DETAIL_LIMIT);
    return "FFmpeg exited with exit code "
        + exitCode
        + ": "
        + recentErrorOutput.substring(tailStart);
  }

  private void settleAfterEndingProcess(Failed failure) {
    process.destroyForcibly();
    process.onExit().join();
    outcome.complete(failure);
  }

  /** Why the producer stopped reading FFmpeg's output. */
  private sealed interface Ending {}

  /** The output ended on a box boundary. */
  private record EndOfOutput() implements Ending {}

  /** The attempt failed while FFmpeg may still be writing. */
  private record Abandoned(Failed failure) implements Ending {}
}
