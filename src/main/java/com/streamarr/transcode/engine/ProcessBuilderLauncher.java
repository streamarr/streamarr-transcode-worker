package com.streamarr.transcode.engine;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import lombok.NonNull;

/**
 * Starts FFmpeg as a child process with piped standard streams, naming its job attempt in the
 * environment so an operator can map a process to its attempt.
 */
public final class ProcessBuilderLauncher implements ProcessLauncher {

  private static final String JOB_ATTEMPT_ID_VARIABLE = "STREAMARR_JOB_ATTEMPT_ID";

  @Override
  public Process launch(@NonNull List<String> command, @NonNull UUID jobAttemptId)
      throws IOException {
    var processBuilder = new ProcessBuilder(command);
    processBuilder.environment().put(JOB_ATTEMPT_ID_VARIABLE, jobAttemptId.toString());
    return processBuilder.start();
  }
}
