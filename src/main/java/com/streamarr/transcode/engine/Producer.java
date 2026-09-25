package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.engine.AttemptOutcome.Stopped;
import com.streamarr.transcode.engine.ReadingEnding.Abandoned;
import com.streamarr.transcode.engine.ReadingEnding.Decided;
import com.streamarr.transcode.engine.ReadingEnding.EndOfOutput;
import com.streamarr.transcode.engine.ReadingEnding.TruncatedOutput;
import com.streamarr.transcode.engine.SegmentAssembler.Closing;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns one job attempt's FFmpeg process: reads its fragmented MP4 output on a virtual thread and
 * groups the fragments into media segments, delivers the initialization segment and then each media
 * segment to the sink on a second virtual thread while it assembles the next, and settles the
 * attempt's outcome once FFmpeg has exited.
 *
 * <p>The producer holds at most two segment caps of FFmpeg's output: the segment awaiting the
 * sink's acceptance and what the reader holds for the next. It admits each box's bytes before it
 * reads them, within its own budget and within the worker's, so when either budget is full, or when
 * a second segment closes while the first still awaits acceptance, the reader stops reading and the
 * pipe holds FFmpeg back. Once the attempt has an outcome, the producer drops and releases what the
 * reader holds at once, even while the reader waits on the pipe, and the delivery in flight once
 * that delivery returns.
 *
 * <p>A watchdog fails the attempt when FFmpeg writes nothing for the stall timeout while the reader
 * is reading; waiting for the sink pauses it, and it ends once the reader stops reading. Once the
 * output ends and the sink has accepted its last segment, FFmpeg must exit within the grace period,
 * or the producer fails the attempt too, as a truncated output when it was one. In both cases it
 * asks FFmpeg to terminate and destroys it after the grace period, because a hung FFmpeg can ignore
 * termination.
 */
@Slf4j
public final class Producer {

  // The server's segment cap; the reader admits no initialization segment or fragment above it,
  // and the grouper assembles no media segment above it.
  private static final long MAXIMUM_SEGMENT_BYTES = 16L * 1024 * 1024;

  // One segment awaiting acceptance and one assembling.
  static final long BUDGET_BYTES = 2 * MAXIMUM_SEGMENT_BYTES;

  private final FfmpegProcess ffmpeg;
  private final FragmentedMp4Reader reader;

  // Guarded by lock, because a decision discards the fragments it holds.
  private final SegmentAssembler assembler;

  private final SegmentSink sink;
  private final SegmentMemoryBudget memoryBudget;
  private final Duration stallTimeout;
  private final StallWatchdog watchdog;
  private final String threadName;
  private final CompletableFuture<AttemptOutcome> outcome = new CompletableFuture<>();
  private final Object lock = new Object();

  // Guarded by lock. The first outcome decided is the only one the producer settles.
  private Optional<AttemptOutcome> decision = Optional.empty();
  private Optional<Delivery> deliveryInFlight = Optional.empty();
  private long readerHeldBytes;
  private boolean mediaSegmentDelivered;

  private Producer(FfmpegProcess ffmpeg, Settings settings) {
    this.ffmpeg = ffmpeg;
    this.stallTimeout = settings.stallTimeout();
    this.watchdog = new StallWatchdog(stallTimeout);
    this.reader =
        new FragmentedMp4Reader(
            watchdog.watch(ffmpeg.output()), MAXIMUM_SEGMENT_BYTES, this::tryAdmit);
    this.assembler = settings.assembler();
    this.sink = settings.sink();
    this.memoryBudget = settings.memoryBudget();
    this.threadName = "producer-" + settings.jobAttemptId();
  }

  /**
   * Starts FFmpeg and reads its output until the attempt settles.
   *
   * @param gracePeriod how long FFmpeg may take to exit once its output ends and the sink has
   *     accepted its last segment, after a stop asks it to quit, and after the producer asks it to
   *     terminate
   * @param stallTimeout how long FFmpeg may write nothing while the reader reads its output
   * @param encodedFrameRate the frame rate an attempt that encodes video forces on its output, so
   *     that the producer fails an output holding a video sample shorter than half a frame; empty
   *     when the attempt copies the video, whose sample durations follow the source
   * @param memoryBudget the worker's segment memory, which every producer of the worker shares
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
      @NonNull Duration stallTimeout,
      @NonNull OptionalDouble encodedFrameRate,
      @NonNull SegmentMemoryBudget memoryBudget,
      @NonNull SegmentSink sink) {
    var settings =
        Settings.builder()
            .jobAttemptId(jobAttemptId)
            .assembler(
                new SegmentAssembler(
                    new SegmentGrouper(periodSeconds, startSequenceNumber, MAXIMUM_SEGMENT_BYTES),
                    EncodedFrameRate.of(encodedFrameRate)))
            .sink(sink)
            .memoryBudget(memoryBudget)
            .stallTimeout(stallTimeout)
            .build();
    Process process;
    try {
      process = launcher.launch(command, jobAttemptId);
    } catch (IOException e) {
      throw new TranscodeException(TranscodeException.GENERIC_MESSAGE, e);
    }

    var producer = new Producer(new FfmpegProcess(process, gracePeriod), settings);
    Thread.ofVirtual()
        .name(producer.threadName)
        .uncaughtExceptionHandler(producer::failReaderUnexpectedly)
        .start(producer::produce);
    Thread.ofVirtual()
        .name(producer.threadName + "-watchdog")
        .uncaughtExceptionHandler(producer::failWatchdogUnexpectedly)
        .start(producer::watchForStall);
    return producer;
  }

  long pid() {
    return ffmpeg.pid();
  }

  /** Completes once with the attempt's outcome, after FFmpeg has exited. */
  public CompletableFuture<AttemptOutcome> outcome() {
    return outcome.copy();
  }

  /**
   * Ends the attempt for good and returns at once. Unless the attempt already has an outcome, the
   * producer records the stop before it returns, so that it never reports a failure observed
   * afterwards, and starts no further delivery. It then asks FFmpeg to quit, cancels the delivery
   * in flight, discards the rest of FFmpeg's output, destroys FFmpeg when it has not exited within
   * the grace period, and settles the stop after FFmpeg has exited.
   */
  public void requestStop() {
    if (!tryDecide(new Stopped())) {
      return;
    }

    Thread.ofVirtual()
        .name(threadName + "-stop")
        .uncaughtExceptionHandler(this::failStopUnexpectedly)
        .start(this::settleStop);
  }

  /**
   * Requests the stop and returns once the attempt has an outcome. An interrupt of the waiting
   * thread destroys FFmpeg at once instead of waiting out the grace period.
   */
  public void stop() {
    requestStop();
    ffmpeg.awaitExitEndingItOnInterrupt();
    outcome.join();
  }

  private void settleStop() {
    ffmpeg.requestQuit();
    cancelDeliveryInFlight();
    ffmpeg.awaitExitWithinGracePeriod();
    settle(new Stopped());
  }

  // Records the attempt's outcome unless another is already recorded; the caller that records it
  // settles it once FFmpeg has exited, or starts the thread that does. The reader admits and
  // delivers nothing afterwards, so what it holds is dropped and released at once, even while it
  // waits on the pipe.
  private boolean tryDecide(AttemptOutcome decided) {
    long readerReleasedBytes;
    synchronized (lock) {
      if (decision.isPresent()) {
        return false;
      }

      decision = Optional.of(decided);
      assembler.discardOpenFragments();
      readerReleasedBytes = readerHeldBytes;
      readerHeldBytes = 0;
      lock.notifyAll();
    }

    reader.cancel();
    watchdog.end();
    memoryBudget.release(readerReleasedBytes);
    return true;
  }

  private boolean isDecided() {
    synchronized (lock) {
      return decision.isPresent();
    }
  }

  // Settles once no delivery is left in flight, so that nothing reaches the sink afterwards.
  private void settle(AttemptOutcome decided) {
    cancelDeliveryInFlight();
    awaitWhile(() -> deliveryInFlight.isPresent());
    outcome.complete(decided);
  }

  private void cancelDeliveryInFlight() {
    Optional<Delivery> delivery;
    synchronized (lock) {
      delivery = deliveryInFlight;
    }

    delivery.map(Delivery::cancellation).ifPresent(DeliveryCancellation::cancel);
  }

  // Waits on lock until the condition, evaluated under lock, is false. Nothing interrupts the
  // producer's own threads; an interrupt is restored once the wait ends.
  private void awaitWhile(BooleanSupplier condition) {
    var interrupted = false;
    synchronized (lock) {
      while (condition.getAsBoolean()) {
        interrupted |= !tryWaitOnLock();
      }
    }

    if (interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  // Holds lock. False when an interrupt ended the wait.
  private boolean tryWaitOnLock() {
    try {
      lock.wait();
      return true;
    } catch (InterruptedException _) {
      return false;
    }
  }

  private void watchForStall() {
    if (watchdog.awaitStall()) {
      failAndTerminateFfmpeg(
          new Failed(
              ProducerFailure.ENCODER_STALLED,
              "FFmpeg wrote no output for " + stallTimeout + " while the producer read it"));
    }
  }

  private void failAndTerminateFfmpeg(Failed failure) {
    if (!tryDecide(failure)) {
      return;
    }

    cancelDeliveryInFlight();
    ffmpeg.requestTermination();
    ffmpeg.awaitExitWithinGracePeriod();
    settle(failure);
  }

  // The reader waits for the producer rather than FFmpeg, so the stall watchdog pauses meanwhile
  // and counts afresh once the reader resumes.
  private void awaitReaderWhile(BooleanSupplier condition) {
    synchronized (lock) {
      if (!condition.getAsBoolean()) {
        return;
      }
    }

    watchdog.pause();
    try {
      awaitWhile(condition);
    } finally {
      watchdog.resume();
    }
  }

  // The watchdog measures FFmpeg only while the reader reads, so it ends with the reading, and the
  // reader delivers nothing more that it holds.
  private void produce() {
    try {
      var ending = readAndDeliver();
      watchdog.end();
      discardOpenFragments();
      holdOnlyTheAssemblingSegment();
      conclude(ending);
    } finally {
      ffmpeg.stopDrainingErrorOutput();
    }
  }

  private void conclude(ReadingEnding ending) {
    Runnable conclusion =
        switch (ending) {
          case EndOfOutput() ->
              () -> settleAtExitOnceAccepted(this::outcomeOfCompleteOutput, notExited());
          case TruncatedOutput(var failure) ->
              () -> settleAtExitOnceAccepted(() -> failure, failure);
          case Abandoned(var failure) -> () -> abandon(failure);
          case Decided() -> ffmpeg::discardRemainingOutput;
        };
    conclusion.run();
  }

  // FFmpeg's exit decides a complete output's outcome, and a non-zero exit takes precedence over
  // any outcome. A truncated output fails whether or not FFmpeg exits, so it keeps its own reason
  // as the failure when FFmpeg outlives the grace period.
  private void settleAtExitOnceAccepted(
      Supplier<AttemptOutcome> outcomeOnCleanExit, Failed failureWithoutExit) {
    if (!tryAwaitAcceptanceOfDeliveryInFlight()) {
      return;
    }

    if (!ffmpeg.tryAwaitExitWithinGracePeriod()) {
      failAndTerminateFfmpeg(failureWithoutExit);
      return;
    }

    var atExit = ffmpeg.outcomeAtExit(outcomeOnCleanExit.get());
    if (tryDecide(atExit)) {
      settle(atExit);
    }
  }

  private Failed notExited() {
    return new Failed(
        ProducerFailure.PROCESS_DID_NOT_EXIT,
        "FFmpeg did not exit within "
            + ffmpeg.gracePeriod()
            + " after its output ended and its last segment was accepted");
  }

  // The segment already in delivery is complete, so the reader lets it finish before it ends a
  // failed attempt; a stop or another failure decided meanwhile takes over instead.
  private void abandon(Failed failure) {
    if (!tryAwaitAcceptanceOfDeliveryInFlight() || !tryDecide(failure)) {
      ffmpeg.discardRemainingOutput();
      return;
    }

    ffmpeg.endForcibly();
    settle(failure);
  }

  // False when the attempt was decided while the delivery awaited acceptance.
  private boolean tryAwaitAcceptanceOfDeliveryInFlight() {
    awaitReaderWhile(() -> deliveryInFlight.isPresent() && decision.isEmpty());
    return !isDecided();
  }

  private void failReaderUnexpectedly(Thread readerThread, Throwable error) {
    logUnexpectedFailure(readerThread, error);
    discardOpenFragments();
    if (!tryFailWithProcessEnded(unexpected(error))) {
      ffmpeg.discardRemainingOutput();
    }
  }

  private void failWatchdogUnexpectedly(Thread watchdogThread, Throwable error) {
    logUnexpectedFailure(watchdogThread, error);
    tryFailWithProcessEnded(unexpected(error));
  }

  // The stop is already recorded, so it still settles, without waiting for FFmpeg to quit.
  private void failStopUnexpectedly(Thread stopThread, Throwable error) {
    logUnexpectedFailure(stopThread, error);
    ffmpeg.endForcibly();
    settle(new Stopped());
  }

  private void failDeliveryUnexpectedly(Thread deliveryThread, Throwable error) {
    logUnexpectedFailure(deliveryThread, error);
    failDelivery(unexpected(error));
  }

  private static void logUnexpectedFailure(Thread thread, Throwable error) {
    log.error("{} failed unexpectedly", thread.getName(), error);
  }

  private static Failed unexpected(Throwable error) {
    return new Failed(ProducerFailure.UNEXPECTED_ERROR, error.toString());
  }

  private boolean tryFailWithProcessEnded(Failed failure) {
    if (!tryDecide(failure)) {
      return false;
    }

    cancelDeliveryInFlight();
    ffmpeg.endForcibly();
    settle(failure);
    return true;
  }

  private ReadingEnding readAndDeliver() {
    try {
      return deliverEachSegment();
    } catch (FragmentedMp4Exception e) {
      return ReadingEnding.refused(e);
    } catch (IOException e) {
      return new Abandoned(new Failed(ProducerFailure.OUTPUT_UNREADABLE, e.toString()));
    } catch (FragmentedMp4Reader.ReadingCancelled _) {
      return new Decided();
    }
  }

  private ReadingEnding deliverEachSegment() throws IOException {
    var ending = deliverNextUnit();
    while (ending.isEmpty()) {
      holdOnlyTheAssemblingSegment();
      ending = deliverNextUnit();
    }

    return ending.orElseThrow();
  }

  // Reads the next unit and delivers the segment it closes; empty while reading goes on. The unit
  // is referenced only until this returns, so no read from the pipe keeps it reachable.
  private Optional<ReadingEnding> deliverNextUnit() throws IOException {
    var unit = reader.next();
    if (unit.isEmpty()) {
      return Optional.of(deliverLastSegment());
    }

    return deliverClosedSegment(unit.orElseThrow());
  }

  // Empty unless the unit ends reading, by closing a segment whose delivery ends it, by skipping a
  // segment number, or because the attempt was decided. The assembler takes a unit only while the
  // attempt is undecided, because the decision discards what the assembler holds.
  private Optional<ReadingEnding> deliverClosedSegment(Mp4Unit unit) {
    Closing closing;
    synchronized (lock) {
      if (decision.isPresent()) {
        return Optional.of(new Decided());
      }

      closing = assembler.accept(unit);
    }

    return deliverThenFail(closing);
  }

  private ReadingEnding deliverLastSegment() {
    Optional<ProducedSegment> lastSegment;
    synchronized (lock) {
      if (decision.isPresent()) {
        return new Decided();
      }

      lastSegment = assembler.finish();
    }

    return lastSegment.flatMap(this::handOff).orElseGet(EndOfOutput::new);
  }

  private void discardOpenFragments() {
    synchronized (lock) {
      assembler.discardOpenFragments();
    }
  }

  // The skipping fragment closes a complete segment, which is delivered before the skip fails the
  // attempt; a stop or a failed delivery decided first ends reading instead.
  private Optional<ReadingEnding> deliverThenFail(Closing closing) {
    var handOffEnding = closing.closedSegment().flatMap(this::handOff);
    if (handOffEnding.isPresent()) {
      return handOffEnding;
    }

    return closing.failure().map(ReadingEnding::refused);
  }

  // Admits a box's bytes while the reader's and the delivery's holdings fit in the producer's
  // budget and the box fits in the worker's; false once the attempt is decided.
  private boolean tryAdmit(long boxBytes) {
    awaitReaderWhile(() -> decision.isEmpty() && heldBytes() + boxBytes > BUDGET_BYTES);
    if (!tryReserveInWorkerBudget(boxBytes)) {
      return false;
    }

    synchronized (lock) {
      if (decision.isEmpty()) {
        readerHeldBytes += boxBytes;
        return true;
      }
    }

    memoryBudget.release(boxBytes);
    return false;
  }

  // The reader waits for other producers to release memory rather than for FFmpeg, so the stall
  // watchdog pauses meanwhile. False once the attempt is decided first.
  private boolean tryReserveInWorkerBudget(long boxBytes) {
    if (memoryBudget.tryReserveAtOnce(boxBytes)) {
      return true;
    }

    watchdog.pause();
    try {
      return memoryBudget.tryReserve(boxBytes, this::isDecided);
    } finally {
      watchdog.resume();
    }
  }

  // Guarded by lock.
  private long heldBytes() {
    return readerHeldBytes
        + deliveryInFlight.map(Delivery::segment).map(ProducedSegment::byteLength).orElse(0L);
  }

  // Whatever the grouper did not keep, such as preroll, is no longer held. Once the attempt has an
  // outcome, the reader holds nothing that it has not already released.
  private void holdOnlyTheAssemblingSegment() {
    long droppedBytes;
    synchronized (lock) {
      if (decision.isPresent()) {
        return;
      }

      droppedBytes = readerHeldBytes - assembler.heldBytes();
      readerHeldBytes = assembler.heldBytes();
    }

    if (droppedBytes > 0) {
      memoryBudget.release(droppedBytes);
    }
  }

  // Starts the segment's delivery once the previous one was accepted; empty once it starts, and
  // the attempt's end when the attempt is decided first.
  private Optional<ReadingEnding> handOff(ProducedSegment segment) {
    awaitReaderWhile(() -> deliveryInFlight.isPresent() && decision.isEmpty());
    Delivery delivery;
    synchronized (lock) {
      if (decision.isPresent()) {
        return Optional.of(new Decided());
      }

      delivery = new Delivery(segment, new DeliveryCancellation());
      deliveryInFlight = Optional.of(delivery);
      readerHeldBytes -= segment.byteLength();
    }

    Thread.ofVirtual()
        .name(threadName + "-delivery")
        .uncaughtExceptionHandler(this::failDeliveryUnexpectedly)
        .start(() -> deliver(delivery));
    return Optional.empty();
  }

  private void deliver(Delivery delivery) {
    var segment = delivery.segment();
    try {
      sink.deliver(segment, delivery.cancellation());
    } catch (RuntimeException e) {
      failDelivery(new Failed(ProducerFailure.SEGMENT_NOT_ACCEPTED, segment + ": " + e));
      return;
    }

    endDelivery(segment instanceof ProducedSegment.Media);
  }

  // The failure is decided before the delivery ends, so the reader, waiting to hand off its next
  // segment, cannot complete the attempt in between.
  private void failDelivery(Failed failure) {
    var decided = tryDecide(failure);
    endDelivery(false);
    if (decided) {
      ffmpeg.endForcibly();
      settle(failure);
    }
  }

  private void endDelivery(boolean mediaSegmentAccepted) {
    long deliveredBytes;
    synchronized (lock) {
      deliveredBytes =
          deliveryInFlight.map(Delivery::segment).map(ProducedSegment::byteLength).orElse(0L);
      deliveryInFlight = Optional.empty();
      mediaSegmentDelivered |= mediaSegmentAccepted;
      lock.notifyAll();
    }

    memoryBudget.release(deliveredBytes);
  }

  private AttemptOutcome outcomeOfCompleteOutput() {
    synchronized (lock) {
      if (!mediaSegmentDelivered) {
        return new Failed(
            ProducerFailure.NO_MEDIA_SEGMENT, "FFmpeg exited cleanly without a media segment");
      }
    }

    return new Completed();
  }

  private record Delivery(ProducedSegment segment, DeliveryCancellation cancellation) {}

  @Builder
  private record Settings(
      UUID jobAttemptId,
      SegmentAssembler assembler,
      SegmentSink sink,
      SegmentMemoryBudget memoryBudget,
      Duration stallTimeout) {}
}
