package com.streamarr.transcode.worker.support;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.SegmentUploadLimitReached;
import build.buf.gen.streamarr.transcode.v1.SegmentUploadMetadata;
import build.buf.gen.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentRequest;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentResponse;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.google.protobuf.Any;
import com.google.rpc.Code;
import com.streamarr.transcode.worker.TranscodeWorkerConfiguration;
import com.streamarr.transcode.worker.WorkerRuntime;
import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public final class ScriptedWorkerRuntime implements WorkerRuntime {

  private final int segmentUploadLimit;
  private Connection connection;
  private QueuedProbeExecutor probes;

  /** A runtime whose server accepts the worker session without a segment upload limit. */
  public ScriptedWorkerRuntime() {
    this(0);
  }

  private ScriptedWorkerRuntime(int segmentUploadLimit) {
    this.segmentUploadLimit = segmentUploadLimit;
  }

  /**
   * A runtime whose server accepts the worker session with this many upload calls at once; 0 states
   * no limit.
   */
  public static ScriptedWorkerRuntime withSegmentUploadLimit(int segmentUploadLimit) {
    return new ScriptedWorkerRuntime(segmentUploadLimit);
  }

  @Override
  public ManagedChannel openChannel(
      TranscodeWorkerConfiguration configuration, InetSocketAddress address) {
    connection = new Connection(segmentUploadLimit);
    return connection;
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

  private static StatusRuntimeException workerLimitRefusal() {
    return StatusProto.toStatusRuntimeException(workerLimitRefusalStatus());
  }

  // The detailed google.rpc.Status shares its simple name with gRPC's Status.
  @SuppressWarnings("checkstyle:fullyQualifiedName")
  private static com.google.rpc.Status workerLimitRefusalStatus() {
    return com.google.rpc.Status.newBuilder()
        .setCode(Code.RESOURCE_EXHAUSTED_VALUE)
        .setMessage("Worker segment upload limit reached")
        .addDetails(Any.pack(SegmentUploadLimitReached.getDefaultInstance()))
        .build();
  }

  public static final class Connection extends ManagedChannel {

    private final SessionCall call;
    private final List<UploadCall> uploads = new CopyOnWriteArrayList<>();
    private final UploadReadiness readiness = new UploadReadiness();
    private final OpenUploads openUploads = new OpenUploads();
    private final ConcurrentLinkedQueue<StatusRuntimeException> refusals =
        new ConcurrentLinkedQueue<>();
    private volatile boolean refusingAtTheWorkersLimit;
    private volatile boolean acknowledgementsWithheld;
    private boolean shutdown;

    private Connection(int segmentUploadLimit) {
      call = new SessionCall(segmentUploadLimit);
    }

    /**
     * The worker's registration. The scripted session accepts the registration as the worker sends
     * it, so the worker has registered once its start() returns.
     */
    public WorkerRegistration registration() {
      assertThat(call.registration).as("the worker has registered").isCompleted();
      return call.registration.resultNow();
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

    /** From now on an upload that the worker half-closes is never acknowledged. */
    public void withholdAcknowledgements() {
      acknowledgementsWithheld = true;
    }

    /** Acknowledges every upload that awaits its acknowledgement now, and withholds later ones. */
    public void acknowledgeAwaitingUploads() {
      uploads.forEach(UploadCall::acknowledgeWhenAwaited);
    }

    /** Stops withholding acknowledgements and acknowledges every upload that awaits one. */
    public void releaseAcknowledgements() {
      acknowledgementsWithheld = false;
      acknowledgeAwaitingUploads();
    }

    /**
     * The next upload call that the worker opens is refused because the worker holds its segment
     * upload limit of calls, with the marker the server attaches to that refusal.
     */
    public void refuseNextUploadAtTheWorkersLimit() {
      refusals.add(workerLimitRefusal());
    }

    /**
     * From now on every upload call that the worker opens is refused because the worker holds its
     * segment upload limit of calls, until {@link #acceptUploads()}.
     */
    public void refuseUploadsAtTheWorkersLimit() {
      refusingAtTheWorkersLimit = true;
    }

    /** Stops refusing the upload calls that the worker opens from now on. */
    public void acceptUploads() {
      refusingAtTheWorkersLimit = false;
    }

    /**
     * The next upload call that the worker opens is refused at one of the server's fleet-wide
     * limits, which carries no marker.
     */
    public void refuseNextUploadAtAFleetWideLimit() {
      refusals.add(
          Status.RESOURCE_EXHAUSTED
              .withDescription("Concurrent segment upload limit reached")
              .asRuntimeException());
    }

    /** The number of uploads that the worker sent whole and that await their acknowledgement. */
    public long uploadsAwaitingAcknowledgement() {
      return uploads.stream().filter(UploadCall::awaitsAcknowledgement).count();
    }

    /** The most upload calls open at once: started, and neither answered nor cancelled. */
    public int mostUploadsOpenAtOnce() {
      return openUploads.most();
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
        var upload = new UploadCall(readiness, openUploads, () -> acknowledgementsWithheld);
        nextRefusal().ifPresent(upload::refuseOnTheFirstMessage);
        uploads.add(upload);
        return typed(upload);
      }

      assertThat(method).isEqualTo(TranscodeWorkerServiceGrpc.getEstablishWorkerSessionMethod());
      return typed(call);
    }

    private Optional<StatusRuntimeException> nextRefusal() {
      if (refusingAtTheWorkersLimit) {
        return Optional.of(workerLimitRefusal());
      }

      return Optional.ofNullable(refusals.poll());
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

  private static final class SessionCall
      extends ClientCall<EstablishWorkerSessionRequest, EstablishWorkerSessionResponse> {

    private final CompletableFuture<WorkerRegistration> registration = new CompletableFuture<>();
    private final ConcurrentLinkedQueue<EstablishWorkerSessionRequest> events =
        new ConcurrentLinkedQueue<>();
    private final int segmentUploadLimit;
    private Listener<EstablishWorkerSessionResponse> responses;
    private volatile boolean refusing;

    private SessionCall(int segmentUploadLimit) {
      this.segmentUploadLimit = segmentUploadLimit;
    }

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
                        .setWorkerSessionId(toProto(UUID.randomUUID()))
                        .setSegmentUploadLimit(segmentUploadLimit))
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

  // The upload calls open now, and the most that were open at once.
  private static final class OpenUploads {

    private int open;
    private int most;

    private synchronized void opened() {
      open++;
      most = Math.max(most, open);
    }

    private synchronized void closed() {
      open--;
    }

    private synchronized int most() {
      return most;
    }
  }

  /**
   * A segment upload that records what the worker sent and acknowledges every byte. Like a gRPC
   * call, a cancelled upload closes with {@code CANCELLED} and is never acknowledged. A refused
   * upload closes with the refusal's status and trailers once the worker's first message, which
   * names the segment, arrives.
   */
  public static final class UploadCall
      extends ClientCall<UploadSegmentRequest, UploadSegmentResponse> {

    private final UploadReadiness readiness;
    private final OpenUploads openUploads;
    private final BooleanSupplier acknowledgementsWithheld;
    private final List<UploadSegmentRequest> messages = new CopyOnWriteArrayList<>();
    private volatile Listener<UploadSegmentResponse> responses;
    private Optional<StatusRuntimeException> refusal = Optional.empty();
    private boolean closed;
    private boolean cancelled;
    private boolean halfClosed;

    private UploadCall(
        UploadReadiness readiness,
        OpenUploads openUploads,
        BooleanSupplier acknowledgementsWithheld) {
      this.readiness = readiness;
      this.openUploads = openUploads;
      this.acknowledgementsWithheld = acknowledgementsWithheld;
    }

    private synchronized void refuseOnTheFirstMessage(StatusRuntimeException refusal) {
      this.refusal = Optional.of(refusal);
    }

    /**
     * Refuses the open upload because the worker holds its segment upload limit of calls, as when
     * the server's refusal reaches the worker after it has sent the segment.
     */
    public void refuseAtTheWorkersLimit() {
      var limitRefusal = workerLimitRefusal();
      synchronized (this) {
        refusal = Optional.of(limitRefusal);
      }

      refuse(limitRefusal);
    }

    /** Whether the server refused the upload. */
    public synchronized boolean wasRefused() {
      return refusal.isPresent();
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
      openUploads.opened();
    }

    private synchronized Optional<StatusRuntimeException> refusal() {
      return refusal;
    }

    private void refuse(StatusRuntimeException refusal) {
      if (!tryClose()) {
        return;
      }

      var trailers = Optional.ofNullable(refusal.getTrailers()).orElseGet(Metadata::new);
      responses.onClose(refusal.getStatus(), trailers);
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
      refusal().ifPresent(this::refuse);
    }

    public synchronized boolean wasCancelled() {
      return cancelled;
    }

    /** Whether the worker sent the whole segment and awaits the acknowledgement. */
    public synchronized boolean awaitsAcknowledgement() {
      return halfClosed && !closed;
    }

    @Override
    public void halfClose() {
      synchronized (this) {
        halfClosed = true;
      }

      if (acknowledgementsWithheld.getAsBoolean()) {
        return;
      }

      acknowledge();
    }

    private void acknowledgeWhenAwaited() {
      if (awaitsAcknowledgement()) {
        acknowledge();
      }
    }

    private void acknowledge() {
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
      openUploads.closed();
      return true;
    }
  }
}
