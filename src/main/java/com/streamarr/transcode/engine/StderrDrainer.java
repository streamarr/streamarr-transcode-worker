package com.streamarr.transcode.engine;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class StderrDrainer implements AutoCloseable {

  static final int CAPACITY = 200;
  private static final Duration EXIT_DETAIL_WAIT = Duration.ofSeconds(1);
  private static final int EXIT_DETAIL_LIMIT = 2000;

  private final CircularLineBuffer buffer = new CircularLineBuffer(CAPACITY);
  private final Thread drainThread;

  StderrDrainer(InputStream errorStream) {
    this.drainThread = Thread.ofVirtual().name("stderr-drainer").start(() -> drain(errorStream));
  }

  private void drain(InputStream errorStream) {
    try (var reader =
        new BufferedReader(new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        buffer.add(line);
      }
    } catch (Exception e) {
      log.debug("Stderr drain thread terminated: {}", e.getMessage());
    }
  }

  /**
   * Waits (bounded by {@code timeout}) for the drain thread to reach end of stream before reading,
   * so a dead process's stderr tail is fully captured instead of raced. On timeout the lines
   * captured so far are returned.
   */
  List<String> awaitRecentOutput(Duration timeout) {
    try {
      drainThread.join(timeout);
    } catch (InterruptedException _) {
      Thread.currentThread().interrupt();
    }

    return buffer.getLines();
  }

  /**
   * Names the exited process and its exit code, followed by the end of its error output, where
   * FFmpeg reports why it failed.
   */
  String exitDetail(String process, int exitCode) {
    var recentErrorOutput = String.join("\n", awaitRecentOutput(EXIT_DETAIL_WAIT));
    var tailStart = Math.max(0, recentErrorOutput.length() - EXIT_DETAIL_LIMIT);
    return process
        + " exited with exit code "
        + exitCode
        + ": "
        + recentErrorOutput.substring(tailStart);
  }

  @Override
  public void close() {
    drainThread.interrupt();
  }
}
