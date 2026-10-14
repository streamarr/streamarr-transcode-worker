package com.streamarr.transcode.worker.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Worker Application Process Integration Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class WorkerApplicationProcessIT {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should kill a child that ignores SIGTERM when an outcome wait was interrupted")
  void shouldKillAChildThatIgnoresSigtermWhenAnOutcomeWaitWasInterrupted() throws Exception {
    var pidFile = tempDir.resolve("child.pid");
    var script = tempDir.resolve("sigterm-ignoring-child");
    Files.writeString(
        script,
        """
        #!/bin/bash
        trap '' TERM
        printf '%s' "$$" > "$CHILD_PID_FILE"
        exec 3<>"/dev/tcp/127.0.0.1/$CHILD_SIGNAL_PORT"
        exec /bin/sleep 300
        """);
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));

    try (var signal = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))) {
      var ignoringSigterm =
          CompletableFuture.supplyAsync(() -> accept(signal), Thread.ofVirtual()::start);
      var command = new ProcessBuilder(script.toString());
      command.environment().put("CHILD_PID_FILE", pidFile.toString());
      command
          .environment()
          .put("CHILD_SIGNAL_PORT", String.valueOf(signal.socket().getLocalPort()));
      try (var child = WorkerApplicationProcess.start(command, tempDir.resolve("child.log"));
          var _ = child.awaitBeforeExit(ignoringSigterm, "it ignored SIGTERM")) {
        Thread.currentThread().interrupt();

        assertThatThrownBy(child::awaitExit).isInstanceOf(InterruptedException.class);
      }

      var childProcess = ProcessHandle.of(Long.parseLong(Files.readString(pidFile)));
      assertThat(childProcess.map(ProcessHandle::isAlive).orElse(false))
          .as("closing the process after an interrupted wait kills the child")
          .isFalse();
    } finally {
      killLeftoverChild(pidFile);
    }
  }

  private static SocketChannel accept(ServerSocketChannel signal) {
    try {
      return signal.accept();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static void killLeftoverChild(Path pidFile) throws Exception {
    if (!Files.exists(pidFile)) {
      return;
    }

    var leftover = ProcessHandle.of(Long.parseLong(Files.readString(pidFile)));
    if (leftover.isEmpty()) {
      return;
    }

    leftover.orElseThrow().destroyForcibly();
    leftover.orElseThrow().onExit().get();
  }
}
