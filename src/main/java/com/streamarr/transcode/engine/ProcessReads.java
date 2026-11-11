package com.streamarr.transcode.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

/**
 * Reads how many bytes a child process has read so far, as Linux counts them in the {@code rchar}
 * line of {@code /proc/<pid>/io}: the bytes that the read calls of every thread of the process have
 * returned, whether they came from the page cache or from storage. The count grows only while the
 * process reads, so a process that waits in a read of a mount that hangs leaves it unchanged.
 */
final class ProcessReads {

  private static final String BYTES_READ = "rchar: ";

  private ProcessReads() {}

  /**
   * The bytes that the process has read; empty when the system keeps no such count, as outside
   * Linux, when the worker may not read it, or once the process has exited.
   */
  static OptionalLong bytesRead(Process process) {
    var bytesRead = bytesReadOf(Path.of("/proc", Long.toString(process.pid()), "io"));
    // A child whose handle is alive after the read still held its pid during it, so the count is
    // the child's. The handle compares the process's start time, which Process.isAlive does not.
    if (!process.toHandle().isAlive()) {
      return OptionalLong.empty();
    }

    return bytesRead;
  }

  private static OptionalLong bytesReadOf(Path io) {
    try {
      return Files.readAllLines(io).stream()
          .filter(line -> line.startsWith(BYTES_READ))
          .mapToLong(line -> Long.parseLong(line.substring(BYTES_READ.length())))
          .findFirst();
    } catch (IOException _) {
      return OptionalLong.empty();
    }
  }
}
