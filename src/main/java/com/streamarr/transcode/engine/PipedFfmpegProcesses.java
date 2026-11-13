package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.engine.FfmpegProcess.ProcessStarter;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;

/**
 * A job attempt's FFmpeg that runs as two processes: one copies the source's video into fragmented
 * MP4, and one encodes that copy, which a virtual thread passes from the first to the second
 * through {@link FragmentedMp4RpuMover}, with the source's audio, which it reads from the source
 * itself. The copying FFmpeg also writes the framecrc of the copy's video packets to a {@link
 * VideoTimingChannel}, from which the mover restores each video sample's duration. The encoding
 * FFmpeg writes the attempt's output and the framecrc of the source's streams. It reads the
 * source's audio up to the source's end whatever happens to the copy, so a stop, a copy that the
 * mover refuses or that ends inside a unit, a copying FFmpeg that exits with an error after a copy
 * of whole units, a failed read of the copy and a transfer that fails unexpectedly terminate it. A
 * stop also asks the copying FFmpeg to quit; termination and destruction end both.
 */
@Slf4j
final class PipedFfmpegProcesses implements FfmpegProcess {

  private static final String COPYING_FFMPEG = "The FFmpeg that copies the source";

  private final Process copy;
  private final Process encode;
  private final StderrDrainer copyErrorOutput;
  private final StderrDrainer encodeErrorOutput;
  private final VideoTimingChannel videoTimings;
  private final Duration gracePeriod;
  private final AtomicReference<Optional<IOException>> failedReadOfTheCopy =
      new AtomicReference<>(Optional.empty());
  private final AtomicReference<Optional<IOException>> transferFailure =
      new AtomicReference<>(Optional.empty());
  private final AtomicReference<Optional<FragmentedMp4Exception>> refusedCopy =
      new AtomicReference<>(Optional.empty());
  private final AtomicReference<Optional<Throwable>> unexpectedTransferFailure =
      new AtomicReference<>(Optional.empty());
  private final AtomicBoolean copiedWhole = new AtomicBoolean();
  private final Thread transfer;

  @Builder(access = AccessLevel.PRIVATE)
  private PipedFfmpegProcesses(
      Process copy, Process encode, VideoTimingChannel videoTimings, Duration gracePeriod) {
    this.copy = copy;
    this.encode = encode;
    this.copyErrorOutput = new StderrDrainer(copy.getErrorStream());
    this.encodeErrorOutput = new StderrDrainer(encode.getErrorStream());
    this.videoTimings = videoTimings;
    this.gracePeriod = gracePeriod;
    this.transfer =
        Thread.ofVirtual()
            .name("ffmpeg-copy-transfer")
            .uncaughtExceptionHandler((_, failure) -> failTransferUnexpectedly(failure))
            .start(this::transferCopy);
  }

  /**
   * Opens the channel of the copy's video timing, starts the copying FFmpeg and then the encoding
   * FFmpeg, and destroys the copying one when the encoding one cannot be started.
   */
  static PipedFfmpegProcesses start(
      ProcessStarter starter, FfmpegPipeline.RpuMovingPipe pipe, Duration gracePeriod) {
    var videoTimings = openVideoTimingChannel();
    var copy = startCopying(starter, pipe, videoTimings);
    try {
      return PipedFfmpegProcesses.builder()
          .copy(copy)
          .encode(starter.start(pipe.encodeCommand()))
          .videoTimings(videoTimings)
          .gracePeriod(gracePeriod)
          .build();
    } catch (RuntimeException e) {
      copy.destroyForcibly();
      throw e;
    }
  }

  private static VideoTimingChannel openVideoTimingChannel() {
    try {
      return VideoTimingChannel.open();
    } catch (IOException e) {
      throw new TranscodeException(TranscodeException.GENERIC_MESSAGE, e);
    }
  }

  private static Process startCopying(
      ProcessStarter starter, FfmpegPipeline.RpuMovingPipe pipe, VideoTimingChannel videoTimings) {
    try {
      var copy = starter.start(pipe.copyCommandWritingVideoTimingTo(videoTimings.url()));
      copy.onExit().thenRun(videoTimings::copyExited);
      return copy;
    } catch (RuntimeException e) {
      videoTimings.close();
      throw e;
    }
  }

  // The copy ends when the copying FFmpeg exits, and the end of the encoding FFmpeg's input lets it
  // finish. Once the encoding FFmpeg stops reading, or the mover refuses the copy, the copying
  // FFmpeg could only block on a full pipe, so it is asked to terminate. A failure is recorded
  // before the encoding FFmpeg's input ends, so that its exit never settles the attempt first. A
  // refusal, a copy that ends inside a unit included, and a failed read of the copy also terminate
  // the encoding FFmpeg, whose exit then decides nothing. A failed write means that the encoding
  // FFmpeg closed its input, so its own exit decides.
  private void transferCopy() {
    var encoded = encode.getOutputStream();
    var copied = new CopyReading(copy.getInputStream());
    try (copied) {
      FragmentedMp4RpuMover.copy(copied, videoTimings, encoded);
      endTheEncodingFfmpegIfTheCopyingFfmpegFails();
    } catch (IOException e) {
      failTransfer(e, copied.hasFailed());
    } catch (FragmentedMp4Exception e) {
      refusedCopy.set(Optional.of(e));
      copy.destroy();
      endTheEncodingFfmpeg();
    }

    endInput(encoded);
  }

  private void failTransfer(IOException failure, boolean readingTheCopyFailed) {
    copy.destroy();
    if (!readingTheCopyFailed) {
      transferFailure.set(Optional.of(failure));
      return;
    }

    failedReadOfTheCopy.set(Optional.of(failure));
    endTheEncodingFfmpeg();
  }

  // A copy of whole units says nothing of how the copying FFmpeg exits, and the encoding FFmpeg
  // goes on reading the source's audio, so an exit with an error terminates it. The copy is marked
  // whole before the encoding FFmpeg's input ends, so that its exit never decides first.
  private void endTheEncodingFfmpegIfTheCopyingFfmpegFails() {
    copiedWhole.set(true);
    copy.onExit()
        .thenAccept(
            exited -> {
              if (exited.exitValue() != 0) {
                endTheEncodingFfmpeg();
              }
            });
  }

  // As in transferCopy, the failure is set before the encoding FFmpeg's input ends, and decides
  // the attempt.
  private void failTransferUnexpectedly(Throwable failure) {
    unexpectedTransferFailure.set(Optional.of(failure));
    copy.destroy();
    endTheEncodingFfmpeg();
    endInput(encode.getOutputStream());
    log.error("The transfer of the copy of the source failed unexpectedly", failure);
  }

  // A failed copy ends the encoding FFmpeg while the producer may still wait on its output, which
  // a hung FFmpeg that ignores termination keeps open. So the copy's failure gives it the grace
  // period and then ends it forcibly, as the producer does after its own termination.
  private void endTheEncodingFfmpeg() {
    var handle = encode.toHandle();
    handle.destroy();
    handle
        .onExit()
        .completeOnTimeout(handle, gracePeriod.toNanos(), TimeUnit.NANOSECONDS)
        .thenAccept(
            ended -> {
              if (ended.isAlive()) {
                ended.destroyForcibly();
              }
            });
  }

  private static void endInput(OutputStream encoded) {
    try {
      encoded.close();
    } catch (IOException _) {
      // The encoding FFmpeg has already closed its input; its exit settles what it read.
    }
  }

  @Override
  public long pid() {
    return encode.pid();
  }

  @Override
  public List<Process> processes() {
    return List.of(copy, encode);
  }

  @Override
  public Duration gracePeriod() {
    return gracePeriod;
  }

  @Override
  public InputStream output() {
    return encode.getInputStream();
  }

  // The encoding FFmpeg reads media from its standard input, so only the copying FFmpeg takes q.
  // FFmpeg treats termination as it treats q, and the attempt discards what follows a stop.
  @Override
  public void requestQuit() {
    try {
      var input = copy.getOutputStream();
      input.write('q');
      input.flush();
    } catch (IOException _) {
      // The copying FFmpeg has already closed its input; the exit wait observes it ending.
    } finally {
      encode.toHandle().destroy();
    }
  }

  // Process.destroy closes the process's standard input after signalling it, and that close waits
  // for any write in progress: the transfer's write to an encoding FFmpeg that reads nothing. Its
  // handle only signals it, so the grace period and the forced end that follow stay reachable.
  @Override
  public void requestTermination() {
    copy.destroy();
    encode.toHandle().destroy();
  }

  @Override
  public void awaitExitEndingItOnInterrupt() {
    try {
      encode.waitFor();
      copy.waitFor();
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      copy.destroyForcibly();
      encode.destroyForcibly();
    }
  }

  // Both FFmpegs and the transfer between them end within one grace period.
  @Override
  public boolean tryAwaitExitWithinGracePeriod() {
    var deadline = System.nanoTime() + gracePeriod.toNanos();
    try {
      return encode.waitFor(remainingNanos(deadline), TimeUnit.NANOSECONDS)
          && copy.waitFor(remainingNanos(deadline), TimeUnit.NANOSECONDS)
          && transfer.join(Duration.ofNanos(remainingNanos(deadline)));
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  private static long remainingNanos(long deadline) {
    return Math.max(0, deadline - System.nanoTime());
  }

  @Override
  public void endForcibly() {
    copy.destroyForcibly();
    encode.destroyForcibly();
    copy.onExit().join();
    encode.onExit().join();
  }

  /**
   * The copy's failures that end the encoding FFmpeg decide first, because that FFmpeg's exit then
   * only follows its termination: a transfer that failed unexpectedly, a copy that the mover
   * refused, a failed read of the copy, then a copying FFmpeg that exited with an error after a
   * copy of whole units. The copying FFmpeg's error explains a copy that ended inside a unit, so it
   * decides before that end. The encoding FFmpeg's exit decides next, as a single FFmpeg's would. A
   * copy that did not reach the encoding FFmpeg whole, or a copying FFmpeg that exited with an
   * error, fails an attempt whose encoding FFmpeg exited cleanly, since its output then lacks the
   * rest of the source.
   */
  @Override
  public Optional<Failed> failureAtExit() {
    var encodeExitCode = encode.onExit().join().exitValue();
    return failureOfTheCopyThatEndedTheEncodingFfmpeg()
        .or(() -> failureOfTheEncodingFfmpeg(encodeExitCode))
        .or(this::failureOfTheCopyAfterACleanEncode);
  }

  private Optional<Failed> failureOfTheCopyThatEndedTheEncodingFfmpeg() {
    return unexpectedTransferFailure
        .get()
        .map(failure -> new Failed(ProducerFailure.UNEXPECTED_ERROR, failure.toString()))
        .or(() -> refusedCopy.get().map(this::failureOfTheRefusedCopy))
        .or(() -> failedReadOfTheCopy.get().map(PipedFfmpegProcesses::unreadableCopy))
        .or(this::failureOfTheCopyingFfmpegAfterAWholeCopy);
  }

  private Optional<Failed> failureOfTheCopyingFfmpegAfterAWholeCopy() {
    if (!copiedWhole.get()) {
      return Optional.empty();
    }

    return failureOfTheCopyingFfmpeg();
  }

  private Failed failureOfTheRefusedCopy(FragmentedMp4Exception refused) {
    if (!endsEarly(refused)) {
      return refusal(refused);
    }

    return failureOfTheCopyingFfmpeg().orElseGet(() -> refusal(refused));
  }

  private Optional<Failed> failureOfTheEncodingFfmpeg(int exitCode) {
    if (exitCode == 0) {
      return Optional.empty();
    }

    return Optional.of(
        new Failed(
            ProducerFailure.PROCESS_EXITED_WITH_ERROR,
            encodeErrorOutput.exitDetail("FFmpeg", exitCode)));
  }

  private Optional<Failed> failureOfTheCopyAfterACleanEncode() {
    return transferFailure
        .get()
        .map(PipedFfmpegProcesses::unreadableCopy)
        .or(this::failureOfTheCopyingFfmpeg);
  }

  private static Failed unreadableCopy(IOException failure) {
    return new Failed(
        ProducerFailure.OUTPUT_UNREADABLE,
        "The copy of the source did not reach the FFmpeg that encodes it: " + failure);
  }

  private Optional<Failed> failureOfTheCopyingFfmpeg() {
    var exitCode = copy.onExit().join().exitValue();
    if (exitCode == 0) {
      return Optional.empty();
    }

    return Optional.of(
        new Failed(
            ProducerFailure.PROCESS_EXITED_WITH_ERROR,
            copyErrorOutput.exitDetail(COPYING_FFMPEG, exitCode)));
  }

  private static boolean endsEarly(FragmentedMp4Exception failure) {
    return ProducerFailure.of(failure.getReason()) == ProducerFailure.TRUNCATED_OUTPUT;
  }

  // The mover reads the copy within its own cap, so a copy over it is not output over the
  // segment cap.
  private static Failed refusal(FragmentedMp4Exception failure) {
    var reason = ProducerFailure.of(failure.getReason());
    if (reason == ProducerFailure.SEGMENT_CAP_EXCEEDED) {
      reason = ProducerFailure.COPY_UNIT_TOO_LARGE;
    }

    return new Failed(
        reason,
        "The copy of the source cannot be passed to the FFmpeg that encodes it: "
            + failure.getMessage());
  }

  @Override
  public void discardRemainingOutput() {
    try {
      encode.getInputStream().transferTo(OutputStream.nullOutputStream());
    } catch (IOException _) {
      // Nothing read after the attempt's outcome was decided matters.
    }
  }

  @Override
  public List<ObservedSourceStream> observedSourceStreams() {
    return encodeErrorOutput.observedSourceStreams();
  }

  @Override
  public void stopDrainingErrorOutput() {
    copyErrorOutput.close();
    encodeErrorOutput.close();
    videoTimings.close();
  }

  // A failed read comes from the copying FFmpeg's side of the transfer, and a failed write from the
  // encoding FFmpeg's, so the transfer tells them apart.
  private static final class CopyReading extends FilterInputStream {

    private volatile boolean failed;

    private CopyReading(InputStream copy) {
      super(copy);
    }

    boolean hasFailed() {
      return failed;
    }

    @Override
    public int read() throws IOException {
      try {
        return super.read();
      } catch (IOException e) {
        failed = true;
        throw e;
      }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      try {
        return super.read(buffer, offset, length);
      } catch (IOException e) {
        failed = true;
        throw e;
      }
    }
  }
}
