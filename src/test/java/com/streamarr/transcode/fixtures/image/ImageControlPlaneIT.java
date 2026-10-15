package com.streamarr.transcode.fixtures.image;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("IntegrationTest")
@DisplayName("Image Control Plane Tests")
class ImageControlPlaneIT {
  private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
  private static final ImageControlPlane.ListenAddresses EPHEMERAL_LOOPBACK_ADDRESSES =
      ImageControlPlane.ListenAddresses.builder()
          .grpc(new InetSocketAddress(LOOPBACK, 0))
          .command(new InetSocketAddress(LOOPBACK, 0))
          .build();

  private ImageControlPlane.Serving controlPlane;
  private HttpClient http;

  @BeforeEach
  void startServer() throws IOException {
    http = HttpClient.newHttpClient();
    controlPlane = ImageControlPlane.serve(EPHEMERAL_LOOPBACK_ADDRESSES);
  }

  @AfterEach
  void stopServer() throws InterruptedException {
    http.close();
    controlPlane.close();
  }

  @Test
  @DisplayName("Should listen only on loopback when the test serves the control plane")
  void shouldListenOnlyOnLoopbackWhenTheTestServesTheControlPlane() {
    assertThat(Stream.of(controlPlane.grpcAddress(), controlPlane.commandAddress()))
        .allSatisfy(address -> assertThat(address.getAddress().isLoopbackAddress()).isTrue());
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
    try (var peer = WorkerPeer.builder().address(controlPlane.grpcAddress()).build()) {
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
            .build(),
        JobOutcome.builder()
            .command("job-awaiting-acknowledgement")
            .event(completed)
            .cause("completed")
            .build(),
        JobOutcome.builder()
            .command("job-awaiting-acknowledgement")
            .event(failed)
            .cause("JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED")
            .build(),
        JobOutcome.builder().command("stop-job").event(completed).cause("completed").build(),
        JobOutcome.builder()
            .command("stop-job")
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
    try (var peer =
        WorkerPeer.builder()
            .address(controlPlane.grpcAddress())
            .withoutRegistration(waitingForRegistration)
            .build()) {
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
        Stream.of(
                "probe",
                "job",
                "failed-job",
                "start-job",
                "job-awaiting-acknowledgement",
                "stop-job")
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
    private final ManagedChannel channel;
    private final CompletableFuture<Void> command = new CompletableFuture<>();
    private final StreamObserver<EstablishWorkerSessionRequest> requests;

    @Builder
    private WorkerPeer(@NonNull InetSocketAddress address, boolean withoutRegistration) {
      channel = NettyChannelBuilder.forAddress(address).usePlaintext().build();
      requests =
          TranscodeWorkerServiceGrpc.newStub(channel)
              .establishWorkerSession(
                  new StreamObserver<>() {
                    @Override
                    public void onNext(EstablishWorkerSessionResponse value) {
                      if (value.hasStartVariant()
                          || value.hasStartProbe()
                          || value.hasStopVariant()) {
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

  private HttpRequest request(String command, byte[] body) throws URISyntaxException {
    var listener = controlPlane.commandAddress();
    var host = listener.getAddress().getHostAddress();
    return HttpRequest.newBuilder(
            new URI("http", null, host, listener.getPort(), "/" + command, null, null))
        .timeout(Duration.ofSeconds(3))
        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        .build();
  }

  private HttpResponse<String> post(String command, byte[] body) throws Exception {
    return http.send(request(command, body), HttpResponse.BodyHandlers.ofString());
  }
}
