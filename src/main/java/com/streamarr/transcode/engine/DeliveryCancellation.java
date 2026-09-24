package com.streamarr.transcode.engine;

import java.util.ArrayList;
import java.util.List;
import lombok.NonNull;

/**
 * Cancels one segment's delivery when the producer's attempt stops before the server accepted the
 * segment. The sink registers how it abandons the delivery, and the producer triggers it.
 */
public final class DeliveryCancellation {

  private final List<Runnable> actions = new ArrayList<>();
  private boolean cancelled;

  /** Runs the action when the producer cancels the delivery, or at once when it already has. */
  public void onCancel(@NonNull Runnable action) {
    synchronized (this) {
      if (!cancelled) {
        actions.add(action);
        return;
      }
    }

    action.run();
  }

  // The actions run outside the monitor, so an action may block briefly without holding it.
  void cancel() {
    List<Runnable> registered;
    synchronized (this) {
      if (cancelled) {
        return;
      }

      cancelled = true;
      registered = List.copyOf(actions);
      actions.clear();
    }

    registered.forEach(Runnable::run);
  }
}
