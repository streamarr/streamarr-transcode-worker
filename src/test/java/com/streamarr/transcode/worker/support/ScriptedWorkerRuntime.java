package com.streamarr.transcode.worker.support;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.SegmentUploadMetadata;
import build.buf.gen.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentRequest;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentResponse;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.worker.TranscodeWorkerConfiguration;
import com.streamarr.transcode.worker.WorkerRuntime;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ForwardingChannelBuilder2;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

public final class ScriptedWorkerRuntime implements WorkerRuntime {

  private Connection connection;
  private QueuedProbeExecutor probes;

  @Override
  public ManagedChannelBuilder<?> channelBuilder(
      TranscodeWorkerConfiguration configuration, InetSocketAddress address) {
    connection = new Connection();
    return new ChannelBuilder(connection);
  }

  @Override
  public ExecutorService newProbeScope() {
    probes = new QueuedProbeExecutor();
    return probes;
  }

  public Connection connection() {
    assertThat(connection).as("the worker has opened its control connection").isNotNull();
    return connection;
  }

  public QueuedProbeExecutor probes() {
    assertThat(probes).as("the worker has opened its probe task scope").isNotNull();
    return probes;
  }

  public static final class Connection extends ManagedChannel {

    private final SessionCall call = new SessionCall();
    private final List<UploadCall> uploads = new CopyOnWriteArrayList<>();
    private final UploadReadiness readiness = new UploadReadiness();
    private boolean shutdown;

    public WorkerRegistration registration() throws Exception {
      return call.registration.get(5, TimeUnit.SECONDS);
    }

    /** Every message the worker has sent on its session stream, in order. */
    public List<EstablishWorkerSessionRequest> events() {
      return List.copyOf(call.events);
    }

    /** Every segment upload the worker has opened, in order. */
    public List<UploadCall> uploads() {
      return List.copyOf(uploads);
    }

    /**
     * From now on an upload call reports that it is ready for one more message only after each
     * {@link #grantUploadMessage()}.
     */
    public void withholdUploadReadiness() {
      readiness.withhold();
    }

    /** Lets the worker send one more upload message. */
    public void grantUploadMessage() {
      readiness.grant();
      uploads.forEach(UploadCall::signalReady);
    }

    /** The number of messages the worker has sent on all its upload calls. */
    public int uploadMessageCount() {
      return uploads.stream().mapToInt(upload -> upload.messages.size()).sum();
    }

    /** The number of upload messages the worker sent while its call reported it was not ready. */
    public int uploadMessagesSentWhileNotReady() {
      return readiness.violations();
    }

    public List<ProbeAttemptResult> results() {
      return call.events.stream()
          .filter(EstablishWorkerSessionRequest::hasProbeResult)
          .map(EstablishWorkerSessionRequest::getProbeResult)
          .toList();
    }

    public void deliver(EstablishWorkerSessionResponse response) {
      call.responses.onMessage(response);
    }

    public void complete() {
      call.responses.onClose(Status.OK, new Metadata());
    }

    public void fail(String description) {
      call.responses.onClose(Status.UNAVAILABLE.withDescription(description), new Metadata());
    }

    /** From now on the session stream refuses every message, as a cancelled call does. */
    public void refuseMessages() {
      call.refusing = true;
    }

    @Override
    public <Q, R> ClientCall<Q, R> newCall(MethodDescriptor<Q, R> method, CallOptions options) {
      if (method.equals(TranscodeWorkerServiceGrpc.getUploadSegmentMethod())) {
        var upload = new UploadCall(readiness);
        uploads.add(upload);
        return typed(upload);
      }

      assertThat(method).isEqualTo(TranscodeWorkerServiceGrpc.getEstablishWorkerSessionMethod());
      return typed(call);
    }

    @SuppressWarnings("unchecked")
    private static <Q, R> ClientCall<Q, R> typed(ClientCall<?, ?> scripted) {
      // newCall checks the descriptor, so these type parameters are the method's message types.
      return (ClientCall<Q, R>) scripted;
    }

    @Override
    public String authority() {
      return "scripted-control-plane";
    }

    @Override
    public ManagedChannel shutdown() {
      shutdown = true;
      return this;
    }

    @Override
    public ManagedChannel shutdownNow() {
      return shutdown();
    }

    @Override
    public boolean isShutdown() {
      return shutdown;
    }

    @Override
    public boolean isTerminated() {
      return isShutdown();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) {
      return shutdown;
    }
  }

  private static final class ChannelBuilder extends ForwardingChannelBuilder2<ChannelBuilder> {

    private final ManagedChannel channel;
    private final ManagedChannelBuilder<?> delegate =
        NettyChannelBuilder.forAddress("localhost", 1);

    private ChannelBuilder(ManagedChannel channel) {
      this.channel = channel;
    }

    @Override
    protected ManagedChannelBuilder<?> delegate() {
      return delegate;
    }

    @Override
    public ManagedChannel build() {
      return channel;
    }
  }

  private static final class SessionCall
      extends ClientCall<EstablishWorkerSessionRequest, EstablishWorkerSessionResponse> {

    private final CompletableFuture<WorkerRegistration> registration = new CompletableFuture<>();
    private final ConcurrentLinkedQueue<EstablishWorkerSessionRequest> events =
        new ConcurrentLinkedQueue<>();
    private Listener<EstablishWorkerSessionResponse> responses;
    private volatile boolean refusing;

    @Override
    public void start(Listener<EstablishWorkerSessionResponse> listener, Metadata headers) {
      responses = listener;
    }

    @Override
    public void sendMessage(EstablishWorkerSessionRequest message) {
      if (refusing) {
        throw new IllegalStateException("call was cancelled");
      }

      events.add(message);
      if (message.hasRegistration()) {
        registration.complete(message.getRegistration());
        responses.onMessage(
            EstablishWorkerSessionResponse.newBuilder()
                .setSessionAccepted(
                    WorkerSessionAccepted.newBuilder()
                        .setWorkerSessionId(toProto(UUID.randomUUID())))
                .build());
      }
    }

    @Override
    public void request(int messages) {
      // Delivery is driven explicitly by the scripted control plane.
    }

    @Override
    public void cancel(String message, Throwable cause) {
      // An already queued response can still be delivered after local shutdown.
    }

    @Override
    public void halfClose() {
      // Server completion is controlled independently from the client's half-close.
    }
  }

  // Whether an upload call is ready for another message: always, unless the test withholds
  // readiness and grants it one message at a time.
  private static final class UploadReadiness {

    private boolean withheld;
    private int grants;
    private int violations;

    private synchronized void withhold() {
      withheld = true;
    }

    private synchronized void grant() {
      grants++;
    }

    private synchronized boolean isReady() {
      return !withheld || grants > 0;
    }

    private synchronized void consume() {
      if (!isReady()) {
        violations++;
        return;
      }

      if (withheld) {
        grants--;
      }
    }

    private synchronized int violations() {
      return violations;
    }
  }

  /**
   * A segment upload that records what the worker sent and acknowledges every byte. Like a gRPC
   * call, a cancelled upload closes with {@code CANCELLED} and is never acknowledged.
   */
  public static final class UploadCall
      extends ClientCall<UploadSegmentRequest, UploadSegmentResponse> {

    private final UploadReadiness readiness;
    private final List<UploadSegmentRequest> messages = new CopyOnWriteArrayList<>();
    private volatile Listener<UploadSegmentResponse> responses;
    private boolean closed;
    private boolean cancelled;

    private UploadCall(UploadReadiness readiness) {
      this.readiness = readiness;
    }

    public SegmentUploadMetadata metadata() {
      return messages.getFirst().getMetadata();
    }

    /** The length of each data message's content, in order. */
    public List<Integer> dataMessageLengths() {
      return messages.stream()
          .filter(UploadSegmentRequest::hasData)
          .map(message -> message.getData().size())
          .toList();
    }

    public byte[] content() {
      var content = new ByteArrayOutputStream();
      messages.stream()
          .filter(UploadSegmentRequest::hasData)
          .forEach(message -> content.writeBytes(message.getData().toByteArray()));
      return content.toByteArray();
    }

    private void signalReady() {
      var listener = responses;
      if (listener != null && readiness.isReady()) {
        listener.onReady();
      }
    }

    @Override
    public void start(Listener<UploadSegmentResponse> listener, Metadata headers) {
      responses = listener;
    }

    @Override
    public void request(int messages) {
      // The single acknowledgement is delivered when the worker half-closes the upload.
    }

    @Override
    public boolean isReady() {
      return readiness.isReady();
    }

    @Override
    public void sendMessage(UploadSegmentRequest message) {
      readiness.consume();
      messages.add(message);
    }

    public synchronized boolean wasCancelled() {
      return cancelled;
    }

    @Override
    public void halfClose() {
      if (!tryClose()) {
        return;
      }

      responses.onMessage(
          UploadSegmentResponse.newBuilder().setAcceptedLengthBytes(content().length).build());
      responses.onClose(Status.OK, new Metadata());
    }

    @Override
    public void cancel(String message, Throwable cause) {
      if (!tryClose()) {
        return;
      }

      synchronized (this) {
        cancelled = true;
      }

      responses.onClose(Status.CANCELLED.withDescription(message).withCause(cause), new Metadata());
    }

    private synchronized boolean tryClose() {
      if (closed) {
        return false;
      }

      closed = true;
      return true;
    }
  }
}
