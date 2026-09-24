package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.StartVariantCommand;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

@Tag("IntegrationTest")
@DisplayName("Worker Actuator Integration Tests")
class WorkerHealthServerIT {

  @TempDir Path directory;

  @Test
  @DisplayName("Should report live but not ready when initialized without an accepted session")
  void shouldReportLiveButNotReadyWhenInitializedWithoutAnAcceptedSession() throws Exception {
    try (var worker = workerBuilder(directory).runtime(new ScriptedWorkerRuntime()).build();
        var application = healthApplication(worker);
        var client = HttpClient.newHttpClient()) {
      var liveness = health(client, endpoint(application, "/actuator/health/liveness"));
      var readiness = health(client, endpoint(application, "/actuator/health/readiness"));

      assertThat(liveness.statusCode()).isEqualTo(200);
      assertThat(liveness.body()).contains("\"status\":\"UP\"");
      assertThat(readiness.statusCode()).isEqualTo(503);
      assertThat(readiness.body()).contains("\"status\":\"DOWN\"");
    }
  }

  @Test
  @DisplayName("Should answer health checks on a non-loopback address when serving Pod IP probes")
  void shouldAnswerHealthChecksOnANonLoopbackAddressWhenServingPodIpProbes() throws Exception {
    var address =
        NetworkInterface.networkInterfaces()
            .flatMap(NetworkInterface::inetAddresses)
            .filter(
                candidate -> candidate instanceof Inet4Address && !candidate.isLoopbackAddress())
            .findFirst()
            .orElseThrow();
    try (var worker = workerBuilder(directory).runtime(new ScriptedWorkerRuntime()).build();
        var application = healthApplication(worker);
        var client = HttpClient.newHttpClient()) {
      var response =
          health(
              client,
              URI.create(
                  "http://"
                      + address.getHostAddress()
                      + ":"
                      + port(application)
                      + "/actuator/health/liveness"));

      assertThat(response.statusCode()).isEqualTo(200);
      assertThat(response.body()).contains("\"status\":\"UP\"");
    }
  }

  @Test
  @DisplayName("Should become ready when the control plane accepts the worker session")
  void shouldBecomeReadyWhenTheControlPlaneAcceptsTheWorkerSession() throws Exception {
    var runtime = new ScriptedWorkerRuntime();
    try (var worker = workerBuilder(directory).runtime(runtime).build();
        var application = healthApplication(worker);
        var client = HttpClient.newHttpClient()) {
      worker.start("localhost", 1);

      var readiness = health(client, endpoint(application, "/actuator/health/readiness"));
      assertThat(readiness.statusCode()).isEqualTo(200);
      assertThat(readiness.body()).contains("\"status\":\"UP\"");
    }
  }

  @ParameterizedTest
  @EnumSource(SessionEnd.class)
  @DisplayName("Should remain live but become unready when the accepted session ends")
  void shouldRemainLiveButBecomeUnreadyWhenTheAcceptedSessionEnds(SessionEnd end) throws Exception {
    var runtime = new ScriptedWorkerRuntime();
    try (var worker = workerBuilder(directory).runtime(runtime).build();
        var application = healthApplication(worker);
        var client = HttpClient.newHttpClient()) {
      worker.start("localhost", 1);

      switch (end) {
        case COMPLETED -> runtime.connection().complete();
        case FAILED -> runtime.connection().fail("control plane disconnected");
        case CLOSED -> worker.close();
      }

      var readiness = health(client, endpoint(application, "/actuator/health/readiness"));
      var liveness = health(client, endpoint(application, "/actuator/health/liveness"));
      assertThat(readiness.statusCode()).isEqualTo(503);
      assertThat(readiness.body()).contains("\"status\":\"DOWN\"");
      assertThat(liveness.statusCode()).isEqualTo(200);
      assertThat(liveness.body()).contains("\"status\":\"UP\"");
      runtime
          .connection()
          .deliver(
              EstablishWorkerSessionResponse.newBuilder()
                  .setSessionAccepted(
                      WorkerSessionAccepted.newBuilder()
                          .setWorkerSessionId(toProto(UUID.randomUUID())))
                  .build());
      assertThat(health(client, endpoint(application, "/actuator/health/readiness")).statusCode())
          .as("late acceptance cannot revive a terminated session")
          .isEqualTo(503);
    }
  }

  @Test
  @DisplayName("Should retain replacement readiness when an old session delivers late callbacks")
  void shouldRetainReplacementReadinessWhenAnOldSessionDeliversLateCallbacks() throws Exception {
    var runtime = new ScriptedWorkerRuntime();
    try (var worker = workerBuilder(directory).runtime(runtime).build();
        var application = healthApplication(worker);
        var client = HttpClient.newHttpClient()) {
      worker.start("localhost", 1);
      var first = runtime.connection();
      worker.close();
      worker.start("localhost", 1);

      first.fail("old session failed after replacement");
      first.deliver(
          EstablishWorkerSessionResponse.newBuilder()
              .setSessionAccepted(
                  WorkerSessionAccepted.newBuilder().setWorkerSessionId(toProto(UUID.randomUUID())))
              .build());

      assertThat(health(client, endpoint(application, "/actuator/health/readiness")).statusCode())
          .isEqualTo(200);
      worker.close();
      runtime
          .connection()
          .deliver(
              EstablishWorkerSessionResponse.newBuilder()
                  .setSessionAccepted(
                      WorkerSessionAccepted.newBuilder()
                          .setWorkerSessionId(toProto(UUID.randomUUID())))
                  .build());
      assertThat(health(client, endpoint(application, "/actuator/health/readiness")).statusCode())
          .isEqualTo(503);
    }
  }

  @Test
  @DisplayName("Should become unready when Spring begins application shutdown")
  void shouldBecomeUnreadyWhenSpringBeginsApplicationShutdown() throws Exception {
    var runtime = new ScriptedWorkerRuntime();
    try (var worker = workerBuilder(directory).runtime(runtime).build();
        var application = healthApplication(worker);
        var client = HttpClient.newHttpClient()) {
      worker.start("localhost", 1);

      AvailabilityChangeEvent.publish(application, ReadinessState.REFUSING_TRAFFIC);

      assertThat(health(client, endpoint(application, "/actuator/health/readiness")).statusCode())
          .isEqualTo(503);
      assertThat(health(client, endpoint(application, "/actuator/health/liveness")).statusCode())
          .isEqualTo(200);
    }
  }

  @Test
  @DisplayName("Should remain ready when every advertised execution slot is occupied")
  void shouldRemainReadyWhenEveryAdvertisedExecutionSlotIsOccupied() throws Exception {
    Files.writeString(directory.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var launcher = ScriptedProcessLauncher.running();
    try (var worker = workerBuilder(directory).runtime(runtime).engine(engine(launcher)).build();
        var application = healthApplication(worker);
        var client = HttpClient.newHttpClient()) {
      worker.start("localhost", 1);
      var registration = runtime.connection().registration();
      var jobs = List.of(variantJobBuilder().build(), variantJobBuilder().build());
      assertThat(registration.getAvailableSlots()).isEqualTo(jobs.size());
      for (var job : jobs) {
        runtime
            .connection()
            .deliver(
                EstablishWorkerSessionResponse.newBuilder()
                    .setStartVariant(
                        StartVariantCommand.newBuilder()
                            .setTarget(registration.getWorker())
                            .setJob(job))
                    .build());
        assertThat(launcher.process(fromProto(job.getJobAttemptId())).isAlive()).isTrue();
      }

      assertThat(health(client, endpoint(application, "/actuator/health/readiness")).statusCode())
          .isEqualTo(200);
    }
  }

  @Test
  @DisplayName("Should expose only health over HTTP when the management server starts")
  void shouldExposeOnlyHealthOverHttpWhenTheManagementServerStarts() throws Exception {
    try (var worker = workerBuilder(directory).runtime(new ScriptedWorkerRuntime()).build();
        var application = healthApplication(worker);
        var client = HttpClient.newHttpClient()) {
      assertThat(health(client, endpoint(application, "/actuator/env")).statusCode())
          .isEqualTo(404);
      assertThat(health(client, endpoint(application, "/actuator/beans")).statusCode())
          .isEqualTo(404);
      assertThat(health(client, endpoint(application, "/")).statusCode()).isEqualTo(404);
    }
  }

  private ConfigurableApplicationContext healthApplication(TranscodeWorker worker) {
    return new SpringApplicationBuilder(HealthApplication.class)
        .initializers(
            context -> context.getBeanFactory().registerSingleton("transcodeWorker", worker))
        .run("--server.port=0", "--spring.main.banner-mode=off");
  }

  private static int port(ConfigurableApplicationContext application) {
    return ((WebServerApplicationContext) application).getWebServer().getPort();
  }

  private static URI endpoint(ConfigurableApplicationContext application, String path) {
    return URI.create("http://localhost:" + port(application) + path);
  }

  private static HttpResponse<String> health(HttpClient client, URI endpoint) throws Exception {
    return client.send(
        HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(5)).GET().build(),
        BodyHandlers.ofString());
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableAutoConfiguration
  @Import(WorkerActuatorConfiguration.class)
  static class HealthApplication {}

  private enum SessionEnd {
    COMPLETED,
    FAILED,
    CLOSED
  }
}
