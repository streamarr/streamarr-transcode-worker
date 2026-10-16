package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.fileContentsProducer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.worker.support.WorkerApplicationControlPlane;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Transport Tests")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class TranscodeWorkerTransportIT {

  private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

  @TempDir Path tempDir;

  @Test
  @DisplayName(
      "Should register through the configured network endpoint when no transport mode is configured")
  void shouldRegisterThroughTheConfiguredNetworkEndpointWhenNoTransportModeIsConfigured()
      throws Exception {
    var networkAddress =
        NetworkInterface.networkInterfaces()
            .flatMap(NetworkInterface::inetAddresses)
            .filter(address -> address instanceof Inet4Address && !address.isLoopbackAddress())
            .findFirst()
            .orElseThrow();
    var workerId = UUID.randomUUID();
    var bootId = UUID.randomUUID();
    var configuration = configuration().workerId(workerId).bootId(bootId).availableSlots(2).build();

    try (var controlPlane = WorkerApplicationControlPlane.builder().build();
        var worker = worker(configuration)) {
      worker.start(networkAddress.getHostAddress(), controlPlane.port());

      var registration = controlPlane.awaitRegistration();
      assertThat(registration.getWorker().getWorkerId()).isEqualTo(toProto(workerId));
      assertThat(registration.getWorker().getBootId()).isEqualTo(toProto(bootId));
      assertThat(controlPlane.awaitIdentityHeader()).isEqualTo(workerId.toString());
      assertThat(registration.getAvailableSlots()).isEqualTo(2);
      assertThat(registration.getCapabilities().getSourceNamespaceIdsList())
          .containsExactly(toProto(SOURCE_NAMESPACE_ID));
    }
  }

  @Test
  @DisplayName("Should register when the server starts listening after the worker starts")
  void shouldRegisterWhenTheServerStartsListeningAfterTheWorkerStarts() throws Exception {
    var workerId = UUID.randomUUID();

    try (var worker = worker(configuration().workerId(workerId).build());
        var starts = Executors.newVirtualThreadPerTaskExecutor()) {
      int port;
      Future<Void> start;
      try (var notYetServing = new ServerSocket(0, 1, LOOPBACK)) {
        port = notYetServing.getLocalPort();
        start =
            starts.submit(
                () -> {
                  worker.start(LOOPBACK.getHostAddress(), port);
                  return null;
                });
        resetOneConnection(notYetServing);
      }

      try (var controlPlane = WorkerApplicationControlPlane.builder().port(port).build()) {
        start.get();

        assertThat(controlPlane.awaitRegistration().getWorker().getWorkerId())
            .isEqualTo(toProto(workerId));
        assertThat(worker.hasAcceptedSession()).isTrue();
      }
    }
  }

  @Test
  @DisplayName(
      "Should fail startup naming the server when no server accepts the session within the bound")
  void shouldFailStartupNamingTheServerWhenNoServerAcceptsTheSessionWithinTheBound()
      throws Exception {
    // A bound socket that never listens holds its port, so no server can accept the session.
    try (var portHolder = new Socket();
        var worker =
            worker(configuration().sessionAcceptanceTimeout(Duration.ofMillis(200)).build())) {
      portHolder.bind(new InetSocketAddress(LOOPBACK, 0));
      var host = LOOPBACK.getHostAddress();
      var port = portHolder.getLocalPort();

      assertThatThrownBy(() -> worker.start(host, port))
          .isInstanceOf(SessionAcceptanceTimeoutException.class)
          .hasMessageContaining(host + ":" + port);
      assertThat(worker.hasAcceptedSession()).isFalse();
    }
  }

  @Test
  @DisplayName("Should stop waiting for the server when the worker closes during startup")
  void shouldStopWaitingForTheServerWhenTheWorkerClosesDuringStartup() throws Exception {
    try (var notYetServing = new ServerSocket(0, 1, LOOPBACK);
        var worker = worker(configuration().build());
        var starts = Executors.newVirtualThreadPerTaskExecutor()) {
      var start =
          starts.submit(
              () -> {
                worker.start(LOOPBACK.getHostAddress(), notYetServing.getLocalPort());
                return null;
              });
      resetOneConnection(notYetServing);

      worker.close();

      assertThatThrownBy(start::get).isInstanceOf(ExecutionException.class);
      assertThat(worker.hasAcceptedSession()).isFalse();
    }
  }

  // Returns once the worker has tried to connect, which fails as it would against a closed port.
  private static void resetOneConnection(ServerSocket listener) throws IOException {
    try (var connection = listener.accept()) {
      connection.setSoLinger(true, 0);
    }
  }

  private TranscodeWorker worker(TranscodeWorkerConfiguration configuration) {
    return TranscodeWorker.builder()
        .configuration(configuration)
        .engine(engine(ScriptedProcessLauncher.running()))
        .ffprobe(fileContentsProducer())
        .build();
  }

  private TranscodeWorkerConfiguration.TranscodeWorkerConfigurationBuilder configuration() {
    return TranscodeWorkerConfiguration.builder()
        .workerId(UUID.randomUUID())
        .bootId(UUID.randomUUID())
        .availableSlots(1)
        .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, tempDir));
  }
}
