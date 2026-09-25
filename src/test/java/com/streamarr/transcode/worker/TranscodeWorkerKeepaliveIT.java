package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.workerConfigurationBuilder;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.worker.support.WorkerApplicationControlPlane;
import io.grpc.StatusRuntimeException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Keepalive Integration Tests")
class TranscodeWorkerKeepaliveIT {

  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @TempDir Path tempDir;

  @Test
  @DisplayName(
      "Should detect a half-open control-plane connection via client keepalive when the peer is unresponsive")
  void shouldDetectHalfOpenControlPlaneConnectionViaClientKeepaliveWhenPeerIsUnresponsive()
      throws Exception {
    try (var server = WorkerApplicationControlPlane.builder().build()) {
      try (var relay = new FreezableRelay(server.port());
          var worker = worker(tempDir.resolve("media"))) {
        worker.start("localhost", relay.port());

        relay.freeze();
        try (var scope = Executors.newVirtualThreadPerTaskExecutor()) {
          var disconnection =
              scope.submit(
                  () -> {
                    worker.awaitDisconnection();
                    return null;
                  });
          try {
            assertThatThrownBy(() -> disconnection.get(30, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(WorkerJobException.class)
                .hasRootCauseInstanceOf(StatusRuntimeException.class);
          } finally {
            disconnection.cancel(true);
          }
        }
      }
    }
  }

  private TranscodeWorker worker(Path mediaRoot) {
    var configuration =
        workerConfigurationBuilder()
            .availableSlots(1)
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, mediaRoot))
            .keepAliveTime(Duration.ofSeconds(10))
            .keepAliveTimeout(Duration.ofSeconds(2))
            .build();
    return new TranscodeWorker(configuration, engine(ScriptedProcessLauncher.running()));
  }

  private static final class FreezableRelay implements AutoCloseable {

    private final ServerSocket listener;
    private final int targetPort;
    private final ExecutorService pumps = Executors.newVirtualThreadPerTaskExecutor();
    private final CopyOnWriteArrayList<Socket> sockets = new CopyOnWriteArrayList<>();
    private volatile boolean frozen;

    private FreezableRelay(int targetPort) throws IOException {
      this.targetPort = targetPort;
      listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
      pumps.submit(this::acceptConnections);
    }

    private int port() {
      return listener.getLocalPort();
    }

    private void freeze() {
      frozen = true;
    }

    private void acceptConnections() {
      try {
        while (true) {
          var inbound = listener.accept();
          var outbound = new Socket(InetAddress.getLoopbackAddress(), targetPort);
          sockets.add(inbound);
          sockets.add(outbound);
          pumps.submit(() -> pump(inbound, outbound));
          pumps.submit(() -> pump(outbound, inbound));
        }
      } catch (IOException _) {
        // The listener was closed; the relay is shutting down.
      }
    }

    /** While frozen, bytes are consumed but never forwarded — a half-open connection. */
    private void pump(Socket from, Socket to) {
      try {
        var input = from.getInputStream();
        var output = to.getOutputStream();
        var buffer = new byte[8192];
        var read = 0;
        while ((read = input.read(buffer)) != -1) {
          if (frozen) {
            continue;
          }
          output.write(buffer, 0, read);
          output.flush();
        }
      } catch (IOException _) {
        // Either side closed; the pump ends.
      }
    }

    @Override
    public void close() throws IOException {
      listener.close();
      for (var socket : sockets) {
        socket.close();
      }
      pumps.shutdownNow();
    }
  }
}
