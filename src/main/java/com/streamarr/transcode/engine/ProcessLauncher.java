package com.streamarr.transcode.engine;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Starts FFmpeg for one job attempt, and pauses the FFmpeg it started. */
@FunctionalInterface
public interface ProcessLauncher {

  Process launch(List<String> command, UUID jobAttemptId) throws IOException;

  /**
   * Stops every thread of the process with SIGSTOP until the returned pause is resumed with
   * SIGCONT. Empty when the process keeps running: it has exited, or no Linux pidfd names it.
   */
  default Optional<PausedProcess> pause(Process process) {
    return ProcessSignals.stop(process);
  }
}
