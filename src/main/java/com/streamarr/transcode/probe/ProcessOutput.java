package com.streamarr.transcode.probe;

import lombok.NonNull;

/** The standard output and exit code of one finished ffprobe process. */
final class ProcessOutput {

  private final byte[] stdout;
  private final int exitCode;

  ProcessOutput(byte @NonNull [] stdout, int exitCode) {
    this.stdout = stdout;
    this.exitCode = exitCode;
  }

  byte[] stdout() {
    return stdout;
  }

  int exitCode() {
    return exitCode;
  }
}
