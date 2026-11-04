package com.streamarr.transcode.engine;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.function.UnaryOperator;
import lombok.NonNull;

/**
 * Starts FFmpeg as a child process with piped standard streams, naming its job attempt in the
 * environment so an operator can map a process to its attempt.
 */
public final class ProcessBuilderLauncher implements ProcessLauncher {

  private static final String JOB_ATTEMPT_ID_VARIABLE = "STREAMARR_JOB_ATTEMPT_ID";

  private final UnaryOperator<ProcessBuilder> confinement;

  /**
   * @param confinement prepares every FFmpeg's process builder before it starts, such as by running
   *     FFmpeg through a sandbox
   */
  public ProcessBuilderLauncher(@NonNull UnaryOperator<ProcessBuilder> confinement) {
    this.confinement = confinement;
  }

  @Override
  public Process launch(@NonNull List<String> command, @NonNull UUID jobAttemptId)
      throws IOException {
    var processBuilder = confinement.apply(new ProcessBuilder(command));
    processBuilder.environment().put(JOB_ATTEMPT_ID_VARIABLE, jobAttemptId.toString());
    return processBuilder.start();
  }
}
