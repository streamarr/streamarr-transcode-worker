package com.streamarr.transcode.worker;

import lombok.Getter;
import lombok.NonNull;

/**
 * The host does not let the worker confine FFmpeg with Landlock, so the worker refuses to start.
 */
public final class LandlockUnavailableException extends IllegalStateException {

  @Getter private final String action;

  LandlockUnavailableException(@NonNull String reason, @NonNull String action) {
    super("The worker cannot confine FFmpeg with Landlock because " + reason);
    this.action = action;
  }
}
