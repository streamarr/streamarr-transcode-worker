package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.engine.AttemptOutcome.Stopped;
import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.ReadingEnding.Abandoned;
import com.streamarr.transcode.engine.ReadingEnding.Decided;
import com.streamarr.transcode.engine.ReadingEnding.EndOfOutput;
import com.streamarr.transcode.engine.ReadingEnding.TruncatedOutput;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns one job attempt's FFmpeg process: reads its fragmented MP4 output on a virtual thread and
 * groups the fragments into media segments, delivers the initialization segment and then each media
 * segment, rebuilt as one {@code moof} and one {@code mdat}, to the sink on a second virtual thread
 * while it assembles the next, and settles the attempt's outcome once FFmpeg has exited.
 *
 * <p>The producer holds at most two segment caps: the segment awaiting the sink's acceptance and
 * what the reader holds for the next, which includes the segments awaiting their audio. It fails
 * the attempt when a box does not fit while no delivery is in flight to free room, since the reader
 * cannot release what it holds before it reads on. It admits each box's bytes before it reads them,
 * and the {@code moof} and {@code mdat} header of each rebuilt segment before it writes them,
 * within its own budget and within the worker's, so when either budget is full, or when a second
 * segment closes while the first still awaits acceptance, the reader stops reading and the pipe
 * holds FFmpeg back. A rebuilt segment views the bytes of the fragments that carried its samples,
 * so it holds them until its delivery returns, and a fragment whose samples a later segment also
 * carries, its audio or the video after a keyframe inside it, counts for both. Once the attempt has
 * an outcome, the producer drops and releases what the reader holds at once, even while the reader
 * waits on the pipe, and the delivery in flight once that delivery returns.
 *
 * <p>A watchdog fails the attempt when FFmpeg writes nothing for the stall timeout while the reader
 * is reading; waiting for the sink pauses it, and it ends once the reader stops reading. Once the
 * output ends and the sink has accepted its last segment, FFmpeg must exit within the grace period,
 * or the producer fails the attempt too, as a truncated output when it was one. In both cases it
 * asks FFmpeg to terminate and destroys it after the grace period, because a hung FFmpeg can ignore
 * termination.
 */
@Slf4j
// The launch builder's parameters only pass through to FFmpeg and the assembler (worker #109).
@SuppressWarnings("java:S6539")
public final class Producer {

  // The server's segment cap; the reader admits no initialization segment or fragment above it,
  // and the grouper assembles no media segment above it.
  private static final long MAXIMUM_SEGMENT_BYTES = 128L * 1024 * 1024;

  // One segment awaiting acceptance and one assembling.
  static final long BUDGET_BYTES = 2 * MAXIMUM_SEGMENT_BYTES;

  private static final long NANOSECONDS_PER_SECOND = Duration.ofSeconds(1).toNanos();
  private static final Duration BEFORE_ANY_SAMPLE = Duration.ofSeconds(Long.MIN_VALUE);

  private final FfmpegProcess ffmpeg;
  private final ProcessLauncher launcher;
  private final FragmentedMp4Reader reader;

  // Guarded by lock, because a decision discards the fragments it holds.
  private final SegmentAssembler assembler;

  private final SegmentSink sink;
  private final SegmentMemoryBudget memoryBudget;
  private final ProducerPauses pauses;
  private final UUID jobAttemptId;
  private final Duration stallTimeout;
  private final StallWatchdog watchdog;
  private final ProductionTimer productionTimer;
  private final MonotonicClock clock;
  private final String threadName;
  private final SourceEndRule sourceEndRule;
  private final int startSequenceNumber;
  private final CompletableFuture<AttemptOutcome> outcome = new CompletableFuture<>();
  private final Object lock = new Object();
  // The latest video or audio sample end that the reader has read, and the latest one taken.
  private final AtomicReference<Duration> outputEnd = new AtomicReference<>(BEFORE_ANY_SAMPLE);
  private final AtomicReference<Duration> takenOutputEnd = new AtomicReference<>(BEFORE_ANY_SAMPLE);

  // Guarded by lock. The first outcome decided is the only one the producer settles.
  private Optional<AttemptOutcome> decision = Optional.empty();
  private Optional<Delivery> deliveryInFlight = Optional.empty();
  private long readerHeldBytes;
  private OptionalInt lastDeliveredMediaSegment = OptionalInt.empty();
  // Guarded by lock. Once the attempt is decided or its output has ended, FFmpeg stays running.
  private boolean ffmpegPaused;
  private boolean heldBack;
  private boolean pausesEnded;
  // Guarded by lock. After a resume, FFmpeg runs for as long as it was paused and until the sink
  // accepts another media segment before it pauses again. Only running time serves that run: while
  // a hold stops FFmpeg, the run still owed waits in owedRunNanos.
  private long pausedAtNanos;
  private OptionalLong restEndsAtNanos = OptionalLong.empty();
  private long owedRunNanos;
  private boolean deliveredSinceResume = true;
  private long resumes;
  private List<PausedProcess> pausedProcesses = List.of();
  // Guarded by lock. Whether the tally counts this producer's FFmpeg as stopped: from a stop until
  // FFmpeg continues or every one of its processes has exited, as a killed stopped FFmpeg does
  // while the reader still waits on a delivery.
  private boolean countedStopped;

  private Producer(FfmpegProcess ffmpeg, Settings settings) {
    this.ffmpeg = ffmpeg;
    this.launcher = settings.launcher();
    this.stallTimeout = settings.stallTimeout();
    this.watchdog = new StallWatchdog(stallTimeout);
    this.productionTimer = new ProductionTimer(settings.clock());
    this.clock = settings.clock();
    this.reader =
        new FragmentedMp4Reader(
            watchdog.watch(ffmpeg.output()), MAXIMUM_SEGMENT_BYTES, this::tryAdmit);
    this.assembler = settings.assembler();
    this.sink = settings.sink();
    this.memoryBudget = settings.memoryBudget();
    this.pauses = settings.pauses();
    this.jobAttemptId = settings.jobAttemptId();
    this.threadName = "producer-" + settings.jobAttemptId();
    this.sourceEndRule = settings.sourceEndRule();
    this.startSequenceNumber = settings.startSequenceNumber();
  }

  /**
   * Starts FFmpeg and reads its output until the attempt settles.
   *
   * @param intervals the media segments' intervals, on which the producer groups the output
   * @param gracePeriod how long FFmpeg may take to exit once its output ends and the sink has
   *     accepted its last segment, after a stop asks it to quit, and after the producer asks it to
   *     terminate
   * @param stallTimeout how long FFmpeg may write nothing while the reader reads its output
   * @param encodedFrameRate the frame rate an attempt that encodes video forces on its output, so
   *     that the producer fails an output holding a video sample shorter than half a frame; empty
   *     when the attempt copies the video, whose sample durations follow the source
   * @param videoDecision the video that the initialization segment must declare; the producer fails
   *     the attempt as an output mismatch, before it delivers anything, when it declares other
   *     video
   * @param requiresSoundTrack whether the initialization segment must declare a sound track, as it
   *     must when the job names its audio stream; the producer fails the attempt as not an audio
   *     stream, before it delivers anything, when it declares none
   * @param paddedAudioEnd the advertised end of an attempt that pads its audio with silence, at or
   *     after which the producer delivers no audio sample; empty for any other attempt
   * @param mediaSegmentCount the variant's advertised media segments, against whose last one the
   *     producer reports whether the source ended early
   * @param copiesAudio whether the attempt copies the source's audio packets, which the producer
   *     places whole in the segment whose interval holds their start
   * @param memoryBudget the worker's segment memory, which every producer of the worker shares
   * @param pauses the tally of pauses and holds that every producer of the engine keeps
   * @param clock the clock that times each media segment's production
   * @param readsFromAKeyframeSeek whether the attempt reads the source from a positive seek
   *     position, so that an encode from a later segment fails when it opens that segment after the
   *     first frame at or after its start
   * @throws TranscodeException when FFmpeg cannot be started
   */
  @Builder(buildMethodName = "start")
  private static Producer launch(
      @NonNull ProcessLauncher launcher,
      @NonNull FfmpegPipeline pipeline,
      @NonNull UUID jobAttemptId,
      @NonNull SegmentIntervals intervals,
      int startSequenceNumber,
      int mediaSegmentCount,
      boolean copiesAudio,
      @NonNull Duration gracePeriod,
      @NonNull Duration stallTimeout,
      @NonNull Optional<OutputFrameRate> encodedFrameRate,
      @NonNull VideoDecision videoDecision,
      boolean requiresSoundTrack,
      @NonNull Optional<Duration> paddedAudioEnd,
      @NonNull SegmentMemoryBudget memoryBudget,
      @NonNull ProducerPauses pauses,
      @NonNull SegmentSink sink,
      @NonNull MonotonicClock clock,
      boolean readsFromAKeyframeSeek) {
    var settings =
        Settings.builder()
            .jobAttemptId(jobAttemptId)
            .launcher(launcher)
            .assembler(
                SegmentAssembler.builder()
                    .intervals(intervals)
                    .startSequenceNumber(startSequenceNumber)
                    .maximumSegmentBytes(MAXIMUM_SEGMENT_BYTES)
                    .encodedFrameRate(encodedFrameRate)
                    .videoDecision(videoDecision)
                    .requiresSoundTrack(requiresSoundTrack)
                    .paddedAudioEnd(paddedAudioEnd)
                    .readsFromAKeyframeSeek(readsFromAKeyframeSeek)
                    .build())
            .sink(sink)
            .memoryBudget(memoryBudget)
            .pauses(pauses)
            .stallTimeout(stallTimeout)
            .clock(clock)
            .sourceEndRule(
                SourceEndRule.builder()
                    .intervals(intervals)
                    .mediaSegmentCount(mediaSegmentCount)
                    .video(videoDecision)
                    .copiesAudio(copiesAudio)
                    .build())
            .startSequenceNumber(startSequenceNumber)
            .build();
    var ffmpeg =
        FfmpegProcess.builder()
            .launcher(launcher)
            .pipeline(pipeline)
            .jobAttemptId(jobAttemptId)
            .gracePeriod(gracePeriod)
            .start();
    var producer = new Producer(ffmpeg, settings);
    CompletableFuture.allOf(
            ffmpeg.processes().stream().map(Process::onExit).toArray(CompletableFuture<?>[]::new))
        .thenRun(producer::uncountStoppedFfmpeg);
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

  /**
   * The end of the latest video or audio sample that the producer has read from FFmpeg's output,
   * its presentation time plus its duration on the output's timeline, when it lies later than the
   * end that the previous call took; empty before any sample and while the output has not advanced.
   * The producer reads each fragment as FFmpeg writes it, so the end advances inside a media
   * segment that has not closed, and while FFmpeg writes audio alone after the video's end.
   */
  public Optional<Duration> takeAdvancedOutputEnd() {
    var end = outputEnd.get();
    var taken = takenOutputEnd.getAndAccumulate(end, Producer::later);
    if (end.compareTo(taken) <= 0) {
      return Optional.empty();
    }

    return Optional.of(end);
  }

  private static Duration later(Duration first, Duration second) {
    if (first.compareTo(second) >= 0) {
      return first;
    }

    return second;
  }

  // Audio counts as well as video: when the video ends first, FFmpeg writes the rest of the audio
  // in fragments without video, and the output still advances.
  private void advanceOutputEnd(Mp4Unit unit) {
    if (!(unit instanceof Fragment fragment)) {
      return;
    }

    var tracks = fragment.tracks();
    tracks.video().ifPresent(video -> advanceOutputEnd(fragment.videoSamples(), video.timescale()));
    tracks.audio().ifPresent(audio -> advanceOutputEnd(fragment.audioSamples(), audio.timescale()));
  }

  private void advanceOutputEnd(List<Sample> samples, long timescale) {
    samples.stream()
        .mapToLong(sample -> sample.presentationTime() + sample.duration())
        .max()
        .ifPresent(end -> outputEnd.accumulateAndGet(durationOf(end, timescale), Producer::later));
  }

  // A 32-bit timescale keeps the remainder's nanoseconds within a signed 64-bit value.
  private static Duration durationOf(long ticks, long timescale) {
    return Duration.ofSeconds(
        Math.floorDiv(ticks, timescale),
        Math.floorMod(ticks, timescale) * NANOSECONDS_PER_SECOND / timescale);
  }

  /**
   * Stops every FFmpeg process of the attempt until {@link #resumeFfmpeg}, so that another job
   * attempt gets the CPU, and answers whether it did. The time FFmpeg spends paused counts neither
   * as production time nor toward the stall timeout. Does nothing while FFmpeg is already paused,
   * once the attempt has an outcome, once FFmpeg's output has ended, and after a resume until
   * FFmpeg has run for as long as it was paused and the sink has accepted another media segment, so
   * that pauses take at most half of FFmpeg's time and at most one pause falls between two media
   * segments. Time that a hold stops FFmpeg serves none of that run. While a hold stops FFmpeg, the
   * pause keeps it stopped after the hold ends.
   */
  public boolean pauseFfmpeg() {
    synchronized (lock) {
      if (ffmpegPaused || pausesEnded || !deliveredSinceResume || isResting()) {
        return false;
      }

      // A hold already stopped FFmpeg; the seek's pause keeps it stopped once the hold ends.
      if (!heldBack && !stopFfmpegProcesses()) {
        return false;
      }

      ffmpegPaused = true;
      pausedAtNanos = clock.nanoTime();
      pauses.seekPaused();
      log.debug("Paused job attempt {} for a seek", jobAttemptId);
      return true;
    }
  }

  // Guarded by lock. One stopped process of a pipe holds the other back through the pipe between
  // them.
  private boolean stopFfmpegProcesses() {
    var stopped =
        ffmpeg.processes().stream().map(launcher::pause).flatMap(Optional::stream).toList();
    if (stopped.isEmpty()) {
      return false;
    }

    productionTimer.pause();
    watchdog.pause();
    pausedProcesses = stopped;
    countedStopped = true;
    pauses.stopped();
    return true;
  }

  /** Continues FFmpeg after {@link #pauseFfmpeg}; does nothing while FFmpeg is not paused. */
  public void resumeFfmpeg() {
    synchronized (lock) {
      resumePausedFfmpeg();
    }
  }

  // Guarded by lock.
  private void resumePausedFfmpeg() {
    if (!ffmpegPaused) {
      return;
    }

    ffmpegPaused = false;
    var resumedAt = clock.nanoTime();
    deliveredSinceResume = false;
    resumes++;
    pauses.seekResumed();
    log.debug("Resumed job attempt {} after a seek's pause", jobAttemptId);
    if (heldBack) {
      owedRunNanos = resumedAt - pausedAtNanos;
      return;
    }

    restEndsAtNanos = OptionalLong.of(resumedAt + (resumedAt - pausedAtNanos));
    continueFfmpegProcesses();
  }

  /**
   * Holds FFmpeg back until {@link #releaseHeldFfmpeg}, because the attempt runs far ahead of its
   * viewer, and answers whether FFmpeg is held. A hold has neither the rest nor the delivered
   * segment that {@link #pauseFfmpeg} waits for after a resume: the server releases it before the
   * viewer needs the attempt's next segment. FFmpeg runs again once neither a hold nor a seek's
   * pause stops it. Held time counts neither as production time nor toward the stall timeout. Does
   * nothing once the attempt has an outcome or FFmpeg's output has ended.
   */
  public boolean holdBackFfmpeg() {
    synchronized (lock) {
      if (pausesEnded) {
        return false;
      }

      if (heldBack) {
        return true;
      }

      if (!ffmpegPaused && !stopFfmpegProcesses()) {
        return false;
      }

      heldBack = true;
      owedRunNanos = remainingRunNanos();
      restEndsAtNanos = OptionalLong.empty();
      pauses.held();
      log.debug("Held back job attempt {}", jobAttemptId);
      return true;
    }
  }

  /** Ends the hold of {@link #holdBackFfmpeg}; FFmpeg stays paused while a seek's pause lasts. */
  public void releaseHeldFfmpeg() {
    synchronized (lock) {
      releaseHold();
    }
  }

  // Guarded by lock.
  private void releaseHold() {
    if (!heldBack) {
      return;
    }

    heldBack = false;
    pauses.holdReleased();
    log.debug("Released the hold of job attempt {}", jobAttemptId);
    if (ffmpegPaused) {
      return;
    }

    restEndsAtNanos = OptionalLong.of(clock.nanoTime() + owedRunNanos);
    owedRunNanos = 0;
    continueFfmpegProcesses();
  }

  // Guarded by lock. The run after the latest resume that FFmpeg has still to serve.
  private long remainingRunNanos() {
    if (restEndsAtNanos.isEmpty()) {
      return owedRunNanos;
    }

    return Math.max(0, restEndsAtNanos.getAsLong() - clock.nanoTime());
  }

  // Guarded by lock.
  private void continueFfmpegProcesses() {
    pausedProcesses.forEach(PausedProcess::resume);
    pausedProcesses = List.of();
    uncountStopped();
    watchdog.resume();
    productionTimer.resume();
  }

  private void uncountStoppedFfmpeg() {
    synchronized (lock) {
      uncountStopped();
    }
  }

  // Guarded by lock.
  private void uncountStopped() {
    if (countedStopped) {
      countedStopped = false;
      pauses.continued();
    }
  }

  // Guarded by lock.
  private boolean isResting() {
    return owedRunNanos > 0
        || restEndsAtNanos.isPresent() && clock.nanoTime() - restEndsAtNanos.getAsLong() < 0;
  }

  // FFmpeg must run to quit, to exit after its output ends and to be terminated.
  private void endPauses() {
    synchronized (lock) {
      pausesEnded = true;
      releaseHold();
      resumePausedFfmpeg();
    }
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
      pausesEnded = true;
      releaseHold();
      resumePausedFfmpeg();
      assembler.discardHeldFragments();
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

  // Holds lock. False when an interrupt ended the wait; awaitWhile restores the interrupt once its
  // loop ends, because restoring it here would make the loop's next wait throw at once.
  @SuppressWarnings({"java:S2273", "java:S2274", "java:S2142"}) // Called in awaitWhile's loop.
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

    pauseTimers();
    try {
      awaitWhile(condition);
    } finally {
      resumeTimers();
    }
  }

  // The watchdog measures FFmpeg only while the reader reads, so it ends with the reading, and the
  // reader delivers nothing more that it holds.
  private void produce() {
    try {
      var ending = readAndDeliver();
      endPauses();
      watchdog.end();
      discardHeldFragments();
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
    discardHeldFragments();
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

  // Reads the next unit and delivers the segments it closes; empty while reading goes on.
  private Optional<ReadingEnding> deliverNextUnit() throws IOException {
    var closedSegments = new ArrayDeque<ClosedSegment>();
    var endingOnceHandedOff = closeSegmentsOfNextUnit(closedSegments);
    return handOffInOrder(closedSegments).or(() -> endingOnceHandedOff);
  }

  // Moves the segments the next unit closes into the queue, the last segments at the end of the
  // output, and returns what ends reading once they are handed off. Neither the unit nor anything
  // else that references the segments outlives this, so a segment whose delivery has released its
  // memory is unreachable while the next waits for its handoff.
  private Optional<ReadingEnding> closeSegmentsOfNextUnit(Deque<ClosedSegment> closedSegments)
      throws IOException {
    var unit = reader.next();
    if (unit.isEmpty()) {
      return Optional.of(closeLastSegments(closedSegments));
    }

    advanceOutputEnd(unit.orElseThrow());
    return closeSegmentsOf(unit.orElseThrow(), closedSegments);
  }

  // Empty unless reading ends once the closed segments are handed off: because the unit skipped a
  // segment number, whose complete segments are delivered before the skip fails the attempt, or
  // because the attempt was decided. The assembler takes a unit only while the attempt is
  // undecided, because the decision discards what the assembler holds.
  private Optional<ReadingEnding> closeSegmentsOf(
      Mp4Unit unit, Deque<ClosedSegment> closedSegments) {
    synchronized (lock) {
      if (decision.isPresent()) {
        return Optional.of(new Decided());
      }

      var closing = assembler.accept(unit);
      closedSegments.addAll(closing.closedSegments());
      return closing.failure().map(ReadingEnding::refused);
    }
  }

  // The audio after the last segment is released before the last segments' deliveries reserve
  // anything.
  private ReadingEnding closeLastSegments(Deque<ClosedSegment> closedSegments) {
    synchronized (lock) {
      if (decision.isPresent()) {
        return new Decided();
      }

      assembler.dropAudioAfterTheLastSegment();
    }

    holdOnlyTheAssemblingSegment();
    synchronized (lock) {
      if (decision.isPresent()) {
        return new Decided();
      }

      closedSegments.addAll(assembler.finish());
    }

    return new EndOfOutput();
  }

  private void discardHeldFragments() {
    synchronized (lock) {
      assembler.discardHeldFragments();
    }
  }

  // Empty once every segment's delivery has started; the ending of the first that ends reading.
  // Each segment leaves the queue as its handoff begins: once its delivery ends and releases its
  // memory, nothing keeps its fragments reachable while the next segment waits for its turn.
  private Optional<ReadingEnding> handOffInOrder(Deque<ClosedSegment> closedSegments) {
    while (!closedSegments.isEmpty()) {
      var ending = handOff(closedSegments.removeFirst());
      if (ending.isPresent()) {
        return ending;
      }
    }

    return Optional.empty();
  }

  // Admits a box's bytes, or the heap of a fragment's samples, while the reader's and the
  // delivery's holdings fit in the producer's budget and the bytes fit in the worker's; false once
  // the attempt is decided. Only a delivery in flight frees the producer's budget, because the
  // reader cannot release what it holds before it reads on, so bytes that do not fit without one
  // never will.
  private boolean tryAdmit(long bytes) {
    awaitReaderWhile(
        () ->
            decision.isEmpty()
                && deliveryInFlight.isPresent()
                && heldBytes() + bytes > BUDGET_BYTES);
    requireRoomInTheProducersBudget(bytes);
    if (!tryReserveInWorkerBudget(bytes)) {
      return false;
    }

    synchronized (lock) {
      if (decision.isEmpty()) {
        readerHeldBytes += bytes;
        return true;
      }
    }

    memoryBudget.release(bytes);
    return false;
  }

  // The reader calls it once the box fits or no delivery is in flight, which only the reader
  // starts.
  private void requireRoomInTheProducersBudget(long bytes) {
    synchronized (lock) {
      if (decision.isPresent() || heldBytes() + bytes <= BUDGET_BYTES) {
        return;
      }

      throw new FragmentedMp4Exception(
          Reason.EXCEEDS_SEGMENT_CAP,
          bytes
              + " more bytes do not fit beside the "
              + heldBytes()
              + " the reader holds, such as segments awaiting their audio, which no delivery in"
              + " flight can free, within the producer's budget of "
              + BUDGET_BYTES);
    }
  }

  // The reader waits for other producers to release memory rather than for FFmpeg, so the stall
  // watchdog pauses meanwhile. False once the attempt is decided first.
  private boolean tryReserveInWorkerBudget(long bytes) {
    if (memoryBudget.tryReserveAtOnce(bytes)) {
      return true;
    }

    pauseTimers();
    try {
      return memoryBudget.tryReserve(bytes, this::isDecided);
    } finally {
      resumeTimers();
    }
  }

  // While the reader holds FFmpeg back, FFmpeg neither stalls nor spends time producing.
  private void pauseTimers() {
    watchdog.pause();
    productionTimer.pause();
  }

  private void resumeTimers() {
    productionTimer.resume();
    watchdog.resume();
  }

  // Guarded by lock.
  private long heldBytes() {
    return readerHeldBytes + deliveryInFlight.map(Delivery::heldBytes).orElse(0L);
  }

  // Whatever the grouper did not keep, such as preroll, is no longer held, and whatever a rewrite
  // added to a fragment, such as converted RPUs longer than the source's, is held beyond what the
  // reader admitted. Once the attempt has an outcome, the reader holds nothing that it has not
  // already released.
  private void holdOnlyTheAssemblingSegment() {
    long droppedBytes;
    synchronized (lock) {
      if (decision.isPresent()) {
        return;
      }

      droppedBytes = readerHeldBytes - assembler.heldBytes();
      readerHeldBytes = Math.min(readerHeldBytes, assembler.heldBytes());
    }

    if (droppedBytes > 0) {
      memoryBudget.release(droppedBytes);
    }

    if (droppedBytes < 0) {
      tryAdmit(-droppedBytes);
    }
  }

  // Writes the segment and starts its delivery once the previous one was accepted; empty once it
  // starts, and the attempt's end when the attempt is decided first or the segment does not fit.
  private Optional<ReadingEnding> handOff(ClosedSegment closedSegment) {
    awaitReaderWhile(() -> deliveryInFlight.isPresent() && decision.isEmpty());
    var reservation = tryReserveToWrite(closedSegment);
    if (reservation.isPresent()) {
      return reservation;
    }

    var segment = closedSegment.write(productionTimer);
    var deliveryHeldBytes = closedSegment.heldBytes() + closedSegment.additionalBytes();
    Delivery delivery;
    synchronized (lock) {
      if (decision.isPresent()) {
        return Optional.of(new Decided());
      }

      delivery = new Delivery(segment, deliveryHeldBytes, new DeliveryCancellation(), resumes);
      deliveryInFlight = Optional.of(delivery);
      readerHeldBytes -= deliveryHeldBytes;
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

    var acceptedMediaSegment = OptionalInt.empty();
    if (segment instanceof ProducedSegment.Media media) {
      acceptedMediaSegment = OptionalInt.of(media.sequenceNumber());
    }

    endDelivery(acceptedMediaSegment);
  }

  // The failure is decided before the delivery ends, so the reader, waiting to hand off its next
  // segment, cannot complete the attempt in between.
  private void failDelivery(Failed failure) {
    var decided = tryDecide(failure);
    endDelivery(OptionalInt.empty());
    if (decided) {
      ffmpeg.endForcibly();
      settle(failure);
    }
  }

  private void endDelivery(OptionalInt acceptedMediaSegment) {
    long deliveredBytes;
    synchronized (lock) {
      deliveredBytes = deliveryInFlight.map(Delivery::heldBytes).orElse(0L);
      // A delivery that began before the latest resume may carry a segment stored before the
      // pause, so only a later one counts as progress since the resume.
      var beganAfterTheLatestResume =
          deliveryInFlight.filter(inFlight -> inFlight.resumesAtStart() == resumes).isPresent();
      deliveryInFlight = Optional.empty();
      if (acceptedMediaSegment.isPresent()) {
        lastDeliveredMediaSegment = acceptedMediaSegment;
        deliveredSinceResume |= beganAfterTheLatestResume;
      }

      lock.notifyAll();
    }

    memoryBudget.release(deliveredBytes);
  }

  // An attempt that starts after every source sample has no media segment to deliver.
  private AttemptOutcome outcomeOfCompleteOutput() {
    var observedSourceStreams = ffmpeg.observedSourceStreams();
    synchronized (lock) {
      var sourceEnd = sourceEndRule.reportOn(observedSourceStreams, lastDeliveredMediaSegment);
      if (lastDeliveredMediaSegment.isEmpty() && !sourceEnd.endsBefore(startSequenceNumber)) {
        return new Failed(
            ProducerFailure.NO_MEDIA_SEGMENT, "FFmpeg exited cleanly without a media segment");
      }

      return new Completed(assembler.droppedLateAudioSampleCount(), sourceEnd);
    }
  }

  // Reserves what writing and delivering the segment holds besides the fragments the reader holds
  // for it; empty once reserved. Nothing is in flight here, so a segment that does not fit the
  // producer's budget now never will.
  private Optional<ReadingEnding> tryReserveToWrite(ClosedSegment closedSegment) {
    var bytes = closedSegment.additionalBytes();
    synchronized (lock) {
      if (decision.isPresent()) {
        return Optional.of(new Decided());
      }

      if (heldBytes() + bytes > BUDGET_BYTES) {
        return Optional.of(new Abandoned(doesNotFit(bytes)));
      }
    }

    if (!tryReserveInWorkerBudget(bytes)) {
      return Optional.of(new Decided());
    }

    synchronized (lock) {
      if (decision.isEmpty()) {
        readerHeldBytes += bytes;
        return Optional.empty();
      }
    }

    memoryBudget.release(bytes);
    return Optional.of(new Decided());
  }

  // Guarded by lock.
  private Failed doesNotFit(long bytes) {
    return new Failed(
        ProducerFailure.SEGMENT_CAP_EXCEEDED,
        "writing a media segment needs "
            + bytes
            + " bytes beyond the "
            + heldBytes()
            + " the producer holds, over its budget of "
            + BUDGET_BYTES);
  }

  /**
   * A segment in delivery and the bytes it holds: the fragments whose bytes it views, and the
   * {@code moof} and {@code mdat} header written for it.
   */
  private record Delivery(
      ProducedSegment segment,
      long heldBytes,
      DeliveryCancellation cancellation,
      long resumesAtStart) {}

  @Builder
  private record Settings(
      UUID jobAttemptId,
      ProcessLauncher launcher,
      SegmentAssembler assembler,
      SegmentSink sink,
      SegmentMemoryBudget memoryBudget,
      ProducerPauses pauses,
      Duration stallTimeout,
      MonotonicClock clock,
      SourceEndRule sourceEndRule,
      int startSequenceNumber) {}
}
