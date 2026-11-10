package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.ReportProbeAttemptRequest;
import build.buf.gen.streamarr.transcode.v1.ReportProbeAttemptResponse;
import build.buf.gen.streamarr.transcode.v1.StartProbeCommand;
import build.buf.gen.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.worker.support.ControlPlaneServer;
import com.streamarr.transcode.worker.support.ReceivedProbeReports;
import io.grpc.stub.StreamObserver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Probe Report Integration Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerProbeReportIT {

  // The server accepts at most 128 KiB in one message of every call.
  private static final int INBOUND_MESSAGE_LIMIT_BYTES = 131_072;

  // The most bytes of a probe report that one message of its report call carries.
  private static final int MESSAGE_BYTES = 65_536;

  @TempDir Path tempDir;

  @Test
  @DisplayName(
      "Should deliver a probe report larger than 128 KiB whole, in several messages, when the"
          + " control plane accepts at most 131,072 bytes in one message")
  void shouldDeliverAProbeReportLargerThan128KibWholeInSeveralMessages() throws Exception {
    var mediaRoot = Files.createDirectory(tempDir.resolve("media"));
    var language = "a".repeat(300_000);
    Files.writeString(
        mediaRoot.resolve("movie.mkv"),
        """
        {"streams":[{"index":0,"codec_type":"video","tags":{"language":"%s"}}]}
        """
            .formatted(language));
    var request = requestBuilder().build();
    var service = new ProbingService(request);

    try (var server =
            ControlPlaneServer.withInboundMessageLimit(service, INBOUND_MESSAGE_LIMIT_BYTES);
        var worker = workerBuilder(mediaRoot).build()) {
      server.start();
      worker.start("localhost", server.port());

      var received = service.awaitReport();

      var reportBytes = received.report().getSerializedSize();
      assertThat(reportBytes).isGreaterThan(INBOUND_MESSAGE_LIMIT_BYTES);
      assertThat(received.report().getResult().getMedia().getStreams(0).getLanguage())
          .isEqualTo(language);
      assertThat(received.metadata().getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
      assertThat(received.metadata().getReportLengthBytes()).isEqualTo(reportBytes);
      assertThat(received.dataMessageLengths())
          .hasSizeGreaterThan(1)
          .allSatisfy(length -> assertThat(length).isBetween(1, MESSAGE_BYTES));
      assertThat(received.dataMessageLengths().stream().mapToInt(Integer::intValue).sum())
          .isEqualTo(reportBytes);
    }
  }

  // Accepts the worker session, starts the probe at once and receives its report call.
  private static final class ProbingService
      extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase {

    private final ProbeRequest request;
    private final CompletableFuture<Void> reported = new CompletableFuture<>();
    private final ReceivedProbeReports probeReports =
        new ReceivedProbeReports(_ -> reported.complete(null));

    private ProbingService(ProbeRequest request) {
      this.request = request;
    }

    private ReceivedProbeReports.Received awaitReport()
        throws InterruptedException, ExecutionException {
      reported.get();
      return probeReports.received().getFirst();
    }

    @Override
    public StreamObserver<EstablishWorkerSessionRequest> establishWorkerSession(
        StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
      return new StreamObserver<>() {
        @Override
        public void onNext(EstablishWorkerSessionRequest event) {
          if (!event.hasRegistration()) {
            return;
          }

          responseObserver.onNext(
              EstablishWorkerSessionResponse.newBuilder()
                  .setSessionAccepted(
                      WorkerSessionAccepted.newBuilder()
                          .setWorkerSessionId(toProto(UUID.randomUUID())))
                  .build());
          responseObserver.onNext(
              EstablishWorkerSessionResponse.newBuilder()
                  .setStartProbe(
                      StartProbeCommand.newBuilder()
                          .setTarget(event.getRegistration().getWorker())
                          .setRequest(request))
                  .build());
        }

        @Override
        public void onError(Throwable failure) {
          reported.completeExceptionally(failure);
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }

    @Override
    public StreamObserver<ReportProbeAttemptRequest> reportProbeAttempt(
        StreamObserver<ReportProbeAttemptResponse> responseObserver) {
      return probeReports.serve(responseObserver);
    }
  }
}
