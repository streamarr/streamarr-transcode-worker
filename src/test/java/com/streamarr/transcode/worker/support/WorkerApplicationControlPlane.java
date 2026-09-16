package com.streamarr.transcode.worker.support;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.StartProbeCommand;
import build.buf.gen.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import lombok.Builder;

public final class WorkerApplicationControlPlane
    extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase implements AutoCloseable {

  private static final Metadata.Key<String> WORKER_ID_HEADER =
      Metadata.Key.of("x-streamarr-worker-id", Metadata.ASCII_STRING_MARSHALLER);

  private final Server server;
  private final CompletableFuture<String> identityHeader = new CompletableFuture<>();
  private final BooleanSupplier producerAdmitted;
  private final CompletableFuture<WorkerRegistration> registration = new CompletableFuture<>();
  private final CompletableFuture<Boolean> admittedAtRegistration = new CompletableFuture<>();
  private final ConcurrentLinkedQueue<WorkerRegistration> registrations =
      new ConcurrentLinkedQueue<>();
  private final CompletableFuture<ProbeAttemptResult> result = new CompletableFuture<>();
  private StreamObserver<EstablishWorkerSessionResponse> responses;

  @Builder
  private WorkerApplicationControlPlane(BooleanSupplier producerAdmitted) throws Exception {
    this.producerAdmitted = producerAdmitted;
    var builder =
        NettyServerBuilder.forPort(0)
            .addService(this)
            .intercept(
                new ServerInterceptor() {
                  @Override
                  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                      ServerCall<ReqT, RespT> call,
                      Metadata headers,
                      ServerCallHandler<ReqT, RespT> next) {
                    identityHeader.complete(headers.get(WORKER_ID_HEADER));
                    return next.startCall(call, headers);
                  }
                });
    server = builder.build().start();
  }

  public String awaitIdentityHeader() throws Exception {
    return identityHeader.get(5, TimeUnit.SECONDS);
  }

  public int port() {
    return server.getPort();
  }

  public WorkerRegistration awaitRegistration() throws Exception {
    return registration.get(10, TimeUnit.SECONDS);
  }

  public boolean wasAdmittedAtRegistration() throws Exception {
    return admittedAtRegistration.get(10, TimeUnit.SECONDS);
  }

  public List<WorkerRegistration> registrationsAfterExit() throws Exception {
    server.shutdown();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS))
        .as("the exited worker's transport and queued callbacks have drained")
        .isTrue();
    return List.copyOf(registrations);
  }

  public ProbeAttemptResult awaitResult() throws Exception {
    return result.get(5, TimeUnit.SECONDS);
  }

  public void startProbe(ProbeRequest request) throws Exception {
    responses.onNext(
        EstablishWorkerSessionResponse.newBuilder()
            .setStartProbe(
                StartProbeCommand.newBuilder()
                    .setTarget(awaitRegistration().getWorker())
                    .setRequest(request))
            .build());
  }

  @Override
  public StreamObserver<EstablishWorkerSessionRequest> establishWorkerSession(
      StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
    responses = responseObserver;
    return new StreamObserver<>() {
      @Override
      public void onNext(EstablishWorkerSessionRequest value) {
        if (value.hasRegistration()) {
          registrations.add(value.getRegistration());
          admittedAtRegistration.complete(producerAdmitted.getAsBoolean());
          responses.onNext(
              EstablishWorkerSessionResponse.newBuilder()
                  .setSessionAccepted(
                      WorkerSessionAccepted.newBuilder()
                          .setWorkerSessionId(toProto(UUID.randomUUID())))
                  .build());
          registration.complete(value.getRegistration());
          return;
        }

        if (value.hasProbeResult()) {
          result.complete(value.getProbeResult());
        }
      }

      @Override
      public void onError(Throwable failure) {
        // Child process termination ends this session during cleanup.
      }

      @Override
      public void onCompleted() {
        responses.onCompleted();
      }
    };
  }

  @Override
  public void close() throws InterruptedException {
    server.shutdownNow();
    assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }

  public static class WorkerApplicationControlPlaneBuilder {
    private BooleanSupplier producerAdmitted = () -> true;
  }
}
