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

  @Override
  public void close() {
    drainThread.interrupt();
  }
}
