package com.streamarr.transcode.fixtures.image;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.JobAttemptCompleted;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailed;
import build.buf.gen.streamarr.transcode.v1.JobAttemptStarted;
import build.buf.gen.streamarr.transcode.v1.JobAttemptStopped;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.StartProbeCommand;
import build.buf.gen.streamarr.transcode.v1.StartVariantCommand;
import build.buf.gen.streamarr.transcode.v1.StopVariantCommand;
import build.buf.gen.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentRequest;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentResponse;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class ImageControlPlane
    extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase {

  private final CompletableFuture<WorkerRegistration> registration = new CompletableFuture<>();
  private final CompletableFuture<ProbeAttemptResult> probe = new CompletableFuture<>();
  private final CompletableFuture<JobAttemptCompleted> completed = new CompletableFuture<>();
  private final CompletableFuture<JobAttemptFailed> failed = new CompletableFuture<>();
  private final CompletableFuture<JobAttemptStarted> started = new CompletableFuture<>();
  private final CompletableFuture<JobAttemptStopped> stopped = new CompletableFuture<>();
  private final CompletableFuture<Void> uploadAwaitsAcknowledgement = new CompletableFuture<>();
  private volatile boolean withholdAcknowledgements;
  private final CompletableFuture<Boolean> disconnected = new CompletableFuture<>();
  private final Map<String, byte[]> segments = new ConcurrentHashMap<>();
  private StreamObserver<EstablishWorkerSessionResponse> responses;

  // The image's worker reaches its control plane at its default endpoint, 127.0.0.1:9090.
  private static final ListenAddresses IMAGE_ADDRESSES =
      ListenAddresses.builder()
          .grpc(new InetSocketAddress(9090))
          .command(new InetSocketAddress(8082))
          .build();

  public static void main() throws Exception {
    try (var _ = serve(IMAGE_ADDRESSES)) {
      new CountDownLatch(1).await();
    }
  }

  static Serving serve(ListenAddresses addresses) throws IOException, InterruptedException {
    var controlPlane = new ImageControlPlane();
    var grpc =
        NettyServerBuilder.forAddress(addresses.grpc()).addService(controlPlane).build().start();
    try {
      var http = HttpServer.create(addresses.command(), 0);
      var threads = Executors.newVirtualThreadPerTaskExecutor();
      http.setExecutor(threads);
      http.createContext("/", controlPlane::exchange);
      http.start();
      return new Serving(grpc, http, threads);
    } catch (IOException | RuntimeException failure) {
      stop(grpc);
      throw failure;
    }
  }

  private static void stop(Server grpc) throws InterruptedException {
    grpc.shutdownNow();
    if (!grpc.awaitTermination(10, TimeUnit.SECONDS)) {
      throw new IllegalStateException("The control-plane gRPC server did not stop in 10 seconds");
    }
  }

  /** Addresses to listen on; port 0 binds an ephemeral port. */
  @Builder
  record ListenAddresses(@NonNull InetSocketAddress grpc, @NonNull InetSocketAddress command) {}

  @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
  static final class Serving implements AutoCloseable {
    private final Server grpc;
    private final HttpServer http;
    private final ExecutorService threads;

    InetSocketAddress grpcAddress() {
      // A Netty server started on one InetSocketAddress listens on exactly that socket.
      return (InetSocketAddress) grpc.getListenSockets().getFirst();
    }

    InetSocketAddress commandAddress() {
      return http.getAddress();
    }

    @Override
    public void close() throws InterruptedException {
      try (threads) {
        http.stop(0);
        stop(grpc);
      }
    }
  }

  private void exchange(HttpExchange exchange) throws IOException {
    byte[] body;
    var status = 200;
    try {
      body = respond(exchange);
    } catch (Exception failure) {
      log.warn(
          "Image control-plane command {} failed", exchange.getRequestURI().getPath(), failure);
      status = 500;
      body = failure.toString().getBytes(StandardCharsets.UTF_8);
    }

    try (exchange) {
      exchange.sendResponseHeaders(status, body.length);
      exchange.getResponseBody().write(body);
    }
  }

  private byte[] respond(HttpExchange exchange) throws Exception {
    return switch (exchange.getRequestURI().getPath()) {
      case "/ready" -> "ready".getBytes(StandardCharsets.UTF_8);
      case "/registration" -> registration.get(30, TimeUnit.SECONDS).toByteArray();
      case "/accept" -> accept();
      case "/accept-after-health-check" -> acceptAfterHealthCheck();
      case "/probe" -> probe(ProbeRequest.parseFrom(exchange.getRequestBody()));
      case "/job" -> job(VariantJob.parseFrom(exchange.getRequestBody()));
      case "/failed-job" -> failedJob(VariantJob.parseFrom(exchange.getRequestBody()));
      case "/start-job" -> startJob(VariantJob.parseFrom(exchange.getRequestBody()));
      case "/job-awaiting-acknowledgement" ->
          jobAwaitingAcknowledgement(VariantJob.parseFrom(exchange.getRequestBody()));
      case "/stop-job" -> stopJob(VariantJob.parseFrom(exchange.getRequestBody()));
      case "/disconnected" ->
          disconnected.get(10, TimeUnit.SECONDS).toString().getBytes(StandardCharsets.UTF_8);
      case "/segment" -> firstMediaSegment();
      default -> throw new IllegalArgumentException("Unknown image-test command");
    };
  }

  // The first media segment behind its initialization segment, so that it decodes alone.
  private byte[] firstMediaSegment() {
    var segment = new ByteArrayOutputStream();
    segment.writeBytes(segments.getOrDefault("init.mp4", new byte[0]));
    segment.writeBytes(segments.getOrDefault("segment0.m4s", new byte[0]));
    return segment.toByteArray();
  }

  private byte[] accept() throws Exception {
    registration.get(30, TimeUnit.SECONDS);
    responses.onNext(
        EstablishWorkerSessionResponse.newBuilder()
            .setSessionAccepted(
                WorkerSessionAccepted.newBuilder().setWorkerSessionId(toProto(UUID.randomUUID())))
            .build());
    return new byte[0];
  }

  // Reads the worker's health just before the acceptance and answers "<liveness> <readiness>".
  // The worker waits only a few seconds for its acceptance, and these loopback requests are the
  // only work that runs inside that wait.
  private byte[] acceptAfterHealthCheck() throws Exception {
    registration.get(30, TimeUnit.SECONDS);
    var health = workerHealth("liveness") + " " + workerHealth("readiness");
    accept();
    return health.getBytes(StandardCharsets.UTF_8);
  }

  // The worker shares this network namespace and serves its health on port 9091.
  private static int workerHealth(String group) throws IOException, InterruptedException {
    try (var http = HttpClient.newHttpClient()) {
      var endpoint = URI.create("http://127.0.0.1:9091/actuator/health/" + group);
      return http.send(
              HttpRequest.newBuilder(endpoint).GET().build(),
              HttpResponse.BodyHandlers.discarding())
          .statusCode();
    }
  }

  private byte[] probe(ProbeRequest request) throws Exception {
    responses.onNext(
        EstablishWorkerSessionResponse.newBuilder()
            .setStartProbe(
                StartProbeCommand.newBuilder()
                    .setTarget(registration.get(30, TimeUnit.SECONDS).getWorker())
                    .setRequest(request))
            .build());
    return probe.get(30, TimeUnit.SECONDS).toByteArray();
  }

  private byte[] job(VariantJob request) throws Exception {
    dispatch(request);
    return completed.get(45, TimeUnit.SECONDS).toByteArray();
  }

  private byte[] failedJob(VariantJob request) throws Exception {
    dispatch(request);
    return failed.get(30, TimeUnit.SECONDS).toByteArray();
  }

  private byte[] startJob(VariantJob request) throws Exception {
    dispatch(request);
    return started.get(30, TimeUnit.SECONDS).toByteArray();
  }

  // Withholds every upload's acknowledgement and answers once an upload of the job awaits one.
  private byte[] jobAwaitingAcknowledgement(VariantJob request) throws Exception {
    withholdAcknowledgements = true;
    dispatch(request);
    uploadAwaitsAcknowledgement.get(30, TimeUnit.SECONDS);
    return new byte[0];
  }

  // Stops the job and answers with the worker's report of the stop.
  private byte[] stopJob(VariantJob request) throws Exception {
    responses.onNext(
        EstablishWorkerSessionResponse.newBuilder()
            .setStopVariant(
                StopVariantCommand.newBuilder()
                    .setTarget(registration.get(30, TimeUnit.SECONDS).getWorker())
                    .setJobAttemptId(request.getJobAttemptId()))
            .build());
    return stopped.get(30, TimeUnit.SECONDS).toByteArray();
  }

  private void dispatch(VariantJob request) throws Exception {
    responses.onNext(
        EstablishWorkerSessionResponse.newBuilder()
            .setStartVariant(
                StartVariantCommand.newBuilder()
                    .setTarget(registration.get(30, TimeUnit.SECONDS).getWorker())
                    .setJob(request))
            .build());
  }

  @Override
  public StreamObserver<EstablishWorkerSessionRequest> establishWorkerSession(
      StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
    responses = responseObserver;
    return new StreamObserver<>() {
      @Override
      public void onNext(EstablishWorkerSessionRequest request) {
        if (request.hasRegistration()) {
          registration.complete(request.getRegistration());
        }
        if (request.hasProbeResult()) {
          probe.complete(request.getProbeResult());
        }
        if (request.hasJobAttemptCompleted()) {
          completed.complete(request.getJobAttemptCompleted());
          var cause =
              new IllegalStateException(
                  "Job completed before the expected result: " + request.getJobAttemptCompleted());
          failed.completeExceptionally(cause);
          started.completeExceptionally(cause);
          failStop(cause);
        }
        if (request.hasJobAttemptFailed()) {
          failed.complete(request.getJobAttemptFailed());
          var cause =
              new IllegalStateException(
                  "Job failed before the expected result: " + request.getJobAttemptFailed());
          completed.completeExceptionally(cause);
          started.completeExceptionally(cause);
          failStop(cause);
        }
        if (request.hasJobAttemptStopped()) {
          stopped.complete(request.getJobAttemptStopped());
        }
        if (request.hasJobAttemptStarted()) {
          started.complete(request.getJobAttemptStarted());
        }
      }

      @Override
      public void onError(Throwable failure) {
        terminateSession(failure);
      }

      @Override
      public void onCompleted() {
        terminateSession(
            new IllegalStateException("Worker session completed before the expected result"));
        responses.onCompleted();
      }
    };
  }

  private void failStop(Throwable cause) {
    uploadAwaitsAcknowledgement.completeExceptionally(cause);
    stopped.completeExceptionally(cause);
  }

  private void terminateSession(Throwable failure) {
    failStop(failure);
    registration.completeExceptionally(failure);
    probe.completeExceptionally(failure);
    completed.completeExceptionally(failure);
    failed.completeExceptionally(failure);
    started.completeExceptionally(failure);
    disconnected.complete(true);
  }

  @Override
  public StreamObserver<UploadSegmentRequest> uploadSegment(
      StreamObserver<UploadSegmentResponse> responseObserver) {
    return new StreamObserver<>() {
      private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      private String name;

      @Override
      public void onNext(UploadSegmentRequest request) {
        if (request.hasMetadata()) {
          name = request.getMetadata().getSegmentName();
          return;
        }
        bytes.writeBytes(request.getData().toByteArray());
      }

      @Override
      public void onError(Throwable failure) {
        completed.completeExceptionally(failure);
      }

      @Override
      public void onCompleted() {
        segments.put(name, bytes.toByteArray());
        if (withholdAcknowledgements) {
          uploadAwaitsAcknowledgement.complete(null);
          return;
        }

        responseObserver.onNext(
            UploadSegmentResponse.newBuilder().setAcceptedLengthBytes(bytes.size()).build());
        responseObserver.onCompleted();
      }
    };
  }
}
