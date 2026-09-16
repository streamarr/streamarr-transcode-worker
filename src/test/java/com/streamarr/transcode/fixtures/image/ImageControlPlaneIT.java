package com.streamarr.transcode.fixtures.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.JobAttemptCompleted;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailed;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("IntegrationTest")
@Isolated
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Image Control Plane Tests")
class ImageControlPlaneIT {
  private Thread server;
  private HttpClient http;

  @BeforeEach
  void startServer() {
    http = HttpClient.newHttpClient();
    server =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    ImageControlPlane.main();
                  } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                  } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                  }
                });
    await()
        .atMost(Duration.ofSeconds(10))
        .ignoreExceptions()
        .untilAsserted(() -> assertThat(post("ready", new byte[0]).statusCode()).isEqualTo(200));
  }

  @AfterEach
  void stopServer() throws InterruptedException {
    http.close();
    server.interrupt();
    server.join(Duration.ofSeconds(10));
    assertThat(server.isAlive()).as("control-plane server stopped").isFalse();
  }

  @Test
  @DisplayName("Should return the parsing cause when a probe command is malformed")
  void shouldReturnTheParsingCauseWhenAProbeCommandIsMalformed() throws Exception {
    var response = post("probe", new byte[] {(byte) 0x80});

    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(response.body()).contains("InvalidProtocolBufferException");
  }

  @ParameterizedTest
  @MethodSource("jobOutcomes")
  @DisplayName("Should report the terminal outcome when the expected job result cannot arrive")
  void shouldReportTheTerminalOutcomeWhenTheExpectedJobResultCannotArrive(JobOutcome outcome)
      throws Exception {
    try (var peer = WorkerPeer.builder().build()) {
      post("accept", new byte[0]);
      var response =
          http.sendAsync(
              request(outcome.command(), VariantJob.getDefaultInstance().toByteArray()),
              HttpResponse.BodyHandlers.ofString());
      peer.command.get(5, TimeUnit.SECONDS);

      peer.requests.onNext(outcome.event());

      var reply = response.get(5, TimeUnit.SECONDS);
      assertThat(reply.statusCode()).as(reply.body()).isEqualTo(500);
      assertThat(reply.body()).contains(outcome.cause());
    }
  }

  private static Stream<JobOutcome> jobOutcomes() {
    var completed =
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptCompleted(JobAttemptCompleted.getDefaultInstance())
            .build();
    var failed =
        EstablishWorkerSessionRequest.newBuilder()
            .setJobAttemptFailed(
                JobAttemptFailed.newBuilder()
                    .setFailure(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED))
            .build();
    return Stream.of(
        JobOutcome.builder().command("failed-job").event(completed).cause("completed").build(),
        JobOutcome.builder().command("start-job").event(completed).cause("completed").build(),
        JobOutcome.builder()
            .command("start-job")
            .event(failed)
            .cause("JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED")
            .build(),
        JobOutcome.builder()
            .command("job")
            .event(failed)
            .cause("JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED")
            .build());
  }

  @Builder
  @DisplayName("Terminal Job Outcome")
  private record JobOutcome(String command, EstablishWorkerSessionRequest event, String cause) {}

  @ParameterizedTest
  @MethodSource("sessionOutcomes")
  @DisplayName("Should report the session termination when an expected result is pending")
  void shouldReportTheSessionTerminationWhenAnExpectedResultIsPending(SessionOutcome outcome)
      throws Exception {
    var waitingForRegistration = outcome.command().equals("registration");
    try (var peer = WorkerPeer.builder().withoutRegistration(waitingForRegistration).build()) {
      if (!waitingForRegistration) {
        post("accept", new byte[0]);
      }
      var response =
          http.sendAsync(
              request(outcome.command(), new byte[0]), HttpResponse.BodyHandlers.ofString());
      if (!waitingForRegistration) {
        peer.command.get(5, TimeUnit.SECONDS);
      }

      if (outcome.cleanCompletion()) {
        peer.requests.onCompleted();
      } else {
        peer.requests.onError(
            Status.UNAVAILABLE.withDescription("injected peer failure").asRuntimeException());
      }
      assertThat(post("disconnected", new byte[0]).body()).isEqualTo("true");

      var reply = response.get(5, TimeUnit.SECONDS);
      assertThat(reply.statusCode()).as(reply.body()).isEqualTo(500);
      assertThat(reply.body()).contains(outcome.cleanCompletion() ? "completed" : "CANCELLED");
    }
  }

  private static Stream<SessionOutcome> sessionOutcomes() {
    var afterRegistration =
        Stream.of("probe", "job", "failed-job", "start-job")
            .flatMap(
                command ->
                    Stream.of(false, true)
                        .map(
                            clean ->
                                SessionOutcome.builder()
                                    .command(command)
                                    .cleanCompletion(clean)
                                    .build()));
    return Stream.concat(
        afterRegistration,
        Stream.of(SessionOutcome.builder().command("registration").cleanCompletion(true).build()));
  }

  @Builder
  @DisplayName("Terminal Session Outcome")
  private record SessionOutcome(String command, boolean cleanCompletion) {}

  @DisplayName("Worker Peer")
  private static final class WorkerPeer implements AutoCloseable {
    private final ManagedChannel channel =
        NettyChannelBuilder.forAddress("127.0.0.1", 9090).usePlaintext().build();
    private final CompletableFuture<Void> command = new CompletableFuture<>();
    private final StreamObserver<EstablishWorkerSessionRequest> requests;

    @Builder
    private WorkerPeer(boolean withoutRegistration) {
      requests =
          TranscodeWorkerServiceGrpc.newStub(channel)
              .establishWorkerSession(
                  new StreamObserver<>() {
                    @Override
                    public void onNext(EstablishWorkerSessionResponse value) {
                      if (value.hasStartVariant() || value.hasStartProbe()) {
                        command.complete(null);
                      }
                    }

                    @Override
                    public void onError(Throwable failure) {
                      command.completeExceptionally(failure);
                    }

                    @Override
                    public void onCompleted() {
                      command.completeExceptionally(new IllegalStateException("Session closed"));
                    }
                  });
      if (withoutRegistration) {
        requests.onNext(EstablishWorkerSessionRequest.getDefaultInstance());
        return;
      }

      requests.onNext(
          EstablishWorkerSessionRequest.newBuilder()
              .setRegistration(WorkerRegistration.getDefaultInstance())
              .build());
    }

    @Override
    public void close() throws InterruptedException {
      channel.shutdownNow();
      assertThat(channel.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private HttpRequest request(String command, byte[] body) {
    return HttpRequest.newBuilder(URI.create("http://127.0.0.1:8082/" + command))
        .timeout(Duration.ofSeconds(3))
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
  }

  private HttpResponse<String> post(String command, byte[] body) throws Exception {
    return http.send(request(command, body), HttpResponse.BodyHandlers.ofString());
  }
}
