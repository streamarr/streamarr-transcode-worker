package com.streamarr.transcode.fixtures.image;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.JobAttemptCompleted;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailed;
import build.buf.gen.streamarr.transcode.v1.JobAttemptStarted;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.StartProbeCommand;
import build.buf.gen.streamarr.transcode.v1.StartVariantCommand;
import build.buf.gen.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentRequest;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentResponse;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ImageControlPlane
    extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase {

  private final CompletableFuture<WorkerRegistration> registration = new CompletableFuture<>();
  private final CompletableFuture<ProbeAttemptResult> probe = new CompletableFuture<>();
  private final CompletableFuture<JobAttemptCompleted> completed = new CompletableFuture<>();
  private final CompletableFuture<JobAttemptFailed> failed = new CompletableFuture<>();
  private final CompletableFuture<JobAttemptStarted> started = new CompletableFuture<>();
  private final CompletableFuture<Boolean> disconnected = new CompletableFuture<>();
  private final Map<String, byte[]> segments = new ConcurrentHashMap<>();
  private StreamObserver<EstablishWorkerSessionResponse> responses;

  public static void main() throws Exception {
    var controlPlane = new ImageControlPlane();
    var grpc = NettyServerBuilder.forPort(9090).addService(controlPlane).build().start();
    var http = HttpServer.create(new InetSocketAddress(8082), 0);
    try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
      http.setExecutor(threads);
      http.createContext("/", controlPlane::exchange);
      http.start();
      try {
        new CountDownLatch(1).await();
      } finally {
        http.stop(0);
        grpc.shutdownNow();
        grpc.awaitTermination(10, TimeUnit.SECONDS);
      }
    }
  }

  private void exchange(HttpExchange exchange) {
    try (exchange) {
      var body = respond(exchange);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
    } catch (Exception failure) {
      throw new IllegalStateException("Image control-plane request failed", failure);
    }
  }

  private byte[] respond(HttpExchange exchange) throws Exception {
    return switch (exchange.getRequestURI().getPath()) {
      case "/ready" -> "ready".getBytes(StandardCharsets.UTF_8);
      case "/registration" -> registration.get(30, TimeUnit.SECONDS).toByteArray();
      case "/accept" -> accept();
      case "/probe" -> probe(ProbeRequest.parseFrom(exchange.getRequestBody()));
      case "/job" -> job(VariantJob.parseFrom(exchange.getRequestBody()));
      case "/failed-job" -> failedJob(VariantJob.parseFrom(exchange.getRequestBody()));
      case "/start-job" -> startJob(VariantJob.parseFrom(exchange.getRequestBody()));
      case "/disconnected" ->
          disconnected.get(10, TimeUnit.SECONDS).toString().getBytes(StandardCharsets.UTF_8);
      case "/segment" -> segments.getOrDefault("segment0.ts", new byte[0]);
      default -> throw new IllegalArgumentException("Unknown image-test command");
    };
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
        }
        if (request.hasJobAttemptFailed()) {
          failed.complete(request.getJobAttemptFailed());
          completed.completeExceptionally(new AssertionError(request.getJobAttemptFailed()));
        }
        if (request.hasJobAttemptStarted()) {
          started.complete(request.getJobAttemptStarted());
        }
      }

      @Override
      public void onError(Throwable failure) {
        disconnected.complete(true);
        probe.completeExceptionally(failure);
        completed.completeExceptionally(failure);
      }

      @Override
      public void onCompleted() {
        disconnected.complete(true);
        responses.onCompleted();
      }
    };
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
        responseObserver.onNext(
            UploadSegmentResponse.newBuilder().setAcceptedLengthBytes(bytes.size()).build());
        responseObserver.onCompleted();
      }
    };
  }
}
