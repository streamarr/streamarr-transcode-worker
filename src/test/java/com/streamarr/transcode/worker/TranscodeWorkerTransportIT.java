package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.worker.support.WorkerApplicationControlPlane;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Transport Tests")
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
    var configuration =
        TranscodeWorkerConfiguration.builder()
            .workerId(workerId)
            .bootId(bootId)
            .availableSlots(2)
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, tempDir))
            .build();

    try (var controlPlane = WorkerApplicationControlPlane.builder().build();
        var worker =
            new TranscodeWorker(configuration, engine(ScriptedProcessLauncher.running()))) {
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
}
