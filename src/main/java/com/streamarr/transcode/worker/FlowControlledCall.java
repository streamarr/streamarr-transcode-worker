package com.streamarr.transcode.worker;

import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;
import java.time.Duration;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * One upload call, which sends a message only when the call is ready for it. It fails when the call
 * is not ready within the readiness timeout, or when the server has not acknowledged the upload
 * within the acknowledgement timeout of the first message sent. Every call method runs under this
 * object's monitor, so a cancellation from another thread never interleaves with a send.
 *
 * @param <Q> the messages that the worker sends on the call
 * @param <R> the server's single answer
 */
final class FlowControlledCall<Q, R> implements ClientResponseObserver<Q, R> {

  /** The most bytes of an upload that one message carries. */
  static final int MESSAGE_BYTES = 64 * 1024;

  private final CompletableFuture<R> response = new CompletableFuture<>();
  private final Duration readinessTimeout;
  private final Duration acknowledgementTimeout;
  private ClientCallStreamObserver<Q> call;

  // Guarded by this monitor.
  private OptionalLong acknowledgementDeadline = OptionalLong.empty();

  FlowControlledCall(Duration readinessTimeout, Duration acknowledgementTimeout) {
    this.readinessTimeout = readinessTimeout;
    this.acknowledgementTimeout = acknowledgementTimeout;
  }

  @Override
  public synchronized void beforeStart(ClientCallStreamObserver<Q> call) {
    this.call = call;
    call.setOnReadyHandler(this::signalReadiness);
  }

  // Sends nothing once the server has answered or the upload was cancelled; that decides it.
  synchronized void send(Q message) throws InterruptedException, TimeoutException {
    var readinessDeadline = System.nanoTime() + readinessTimeout.toNanos();
    while (!call.isReady() && !response.isDone()) {
      TimeUnit.NANOSECONDS.timedWait(this, nanosUntilNextDeadline(readinessDeadline));
    }

    if (response.isDone()) {
      return;
    }

    if (acknowledgementDeadline.isEmpty()) {
      acknowledgementDeadline =
          OptionalLong.of(System.nanoTime() + acknowledgementTimeout.toNanos());
    }

    call.onNext(message);
  }

  // Holds this monitor. The time left before the first of the readiness deadline and the
  // acknowledgement deadline passes.
  private long nanosUntilNextDeadline(long readinessDeadline) throws TimeoutException {
    var now = System.nanoTime();
    var untilReady = readinessDeadline - now;
    if (untilReady <= 0) {
      throw new TimeoutException(
          "The upload was not ready for its next message within " + readinessTimeout);
    }

    return Math.min(untilReady, nanosUntilAcknowledgementDeadline(now));
  }

  // Holds this monitor.
  private long nanosUntilAcknowledgementDeadline(long now) throws TimeoutException {
    if (acknowledgementDeadline.isEmpty()) {
      return Long.MAX_VALUE;
    }

    var untilAcknowledged = acknowledgementDeadline.getAsLong() - now;
    if (untilAcknowledged <= 0) {
      throw acknowledgementTimedOut();
    }

    return untilAcknowledged;
  }

  private TimeoutException acknowledgementTimedOut() {
    return new TimeoutException(
        "The server did not acknowledge the upload within "
            + acknowledgementTimeout
            + " of its first message");
  }

  private synchronized void signalReadiness() {
    notifyAll();
  }

  synchronized void finish() {
    if (!response.isDone()) {
      call.onCompleted();
    }
  }

  R awaitAcknowledgement() throws InterruptedException, ExecutionException, TimeoutException {
    try {
      return response.get(nanosUntilAcknowledgementDeadline(), TimeUnit.NANOSECONDS);
    } catch (TimeoutException _) {
      throw acknowledgementTimedOut();
    }
  }

  private synchronized long nanosUntilAcknowledgementDeadline() throws TimeoutException {
    return nanosUntilAcknowledgementDeadline(System.nanoTime());
  }

  // A stop before the call opens has no call to cancel; the producer then never sends on it.
  synchronized void cancel(Throwable failure) {
    response.completeExceptionally(failure);
    if (call != null) {
      call.cancel("Segment upload failed", failure);
    }

    notifyAll();
  }

  @Override
  public void onNext(R value) {
    response.complete(value);
    signalReadiness();
  }

  @Override
  public void onError(Throwable throwable) {
    response.completeExceptionally(throwable);
    signalReadiness();
  }

  @Override
  public void onCompleted() {
    // gRPC routes a client-streaming close without its single response through onError.
  }
}
