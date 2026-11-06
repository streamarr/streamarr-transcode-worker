package com.streamarr.transcode.engine;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Starts FFmpeg for one job attempt, and pauses the FFmpeg it started. */
@FunctionalInterface
public interface ProcessLauncher {

  Process launch(List<String> command, UUID jobAttemptId) throws IOException;

  /**
   * Stops every thread of the process with SIGSTOP until the returned pause is resumed with
   * SIGCONT. A process that has exited is left alone.
   */
  default PausedProcess pause(Process process) {
    return ProcessSignals.stop(process);
  }
}
