package com.streamarr.transcode.engine;

import java.util.Optional;
import lombok.NonNull;

/**
 * Cancels one segment's delivery when the producer's attempt stops before the server accepted the
 * segment. The sink registers how it abandons the delivery, and the producer triggers it.
 */
public final class DeliveryCancellation {

  private Optional<Runnable> action = Optional.empty();
  private boolean cancelled;

  /**
   * Registers the delivery's one cancellation action, which runs when the producer cancels the
   * delivery, or at once when it already has.
   *
   * @throws IllegalStateException when the delivery already has a cancellation action
   */
  public void onCancel(@NonNull Runnable cancellationAction) {
    synchronized (this) {
      if (action.isPresent()) {
        throw new IllegalStateException("The delivery already has a cancellation action");
      }

      if (!cancelled) {
        action = Optional.of(cancellationAction);
        return;
      }
    }

    cancellationAction.run();
  }

  // The action runs outside the monitor, so it may block briefly without holding it.
  void cancel() {
    Optional<Runnable> registered;
    synchronized (this) {
      if (cancelled) {
        return;
      }

      cancelled = true;
      registered = action;
    }

    registered.ifPresent(Runnable::run);
  }
}
