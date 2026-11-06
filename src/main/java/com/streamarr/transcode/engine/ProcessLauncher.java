package com.streamarr.transcode.engine;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Starts FFmpeg for one job attempt, and pauses and resumes the FFmpeg it started. */
@FunctionalInterface
public interface ProcessLauncher {

  Process launch(List<String> command, UUID jobAttemptId) throws IOException;

  /**
   * Stops every thread of the process until {@link #resume}, with SIGSTOP. A process that has
   * exited is left alone.
   */
  default void pause(Process process) {
    ProcessSignals.send(process, ProcessSignals.Signal.STOP);
  }

  /** Continues a process that {@link #pause} stopped, with SIGCONT. */
  default void resume(Process process) {
    ProcessSignals.send(process, ProcessSignals.Signal.CONTINUE);
  }
}
