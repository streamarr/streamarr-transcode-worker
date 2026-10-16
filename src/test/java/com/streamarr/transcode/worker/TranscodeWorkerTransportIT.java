package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.fileContentsProducer;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.worker.support.WorkerApplicationControlPlane;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
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
