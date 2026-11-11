package com.streamarr.transcode.worker.support;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReportMetadata;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ReportProbeAttemptRequest;
import build.buf.gen.streamarr.transcode.v1.ReportProbeAttemptResponse;
import build.buf.gen.streamarr.transcode.v1.SegmentUploadLimitReached;
import build.buf.gen.streamarr.transcode.v1.SegmentUploadMetadata;
import build.buf.gen.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentRequest;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentResponse;
import build.buf.gen.streamarr.transcode.v1.Uuid;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class ScriptedWorkerRuntime implements WorkerRuntime {

  private final WorkerSessionAccepted limits;
  private Connection connection;
  private QueuedProbeExecutor probes;

  /** A runtime whose server accepts the worker session and states no limit. */
  public ScriptedWorkerRuntime() {
    this(WorkerSessionAccepted.getDefaultInstance());
  }

  private ScriptedWorkerRuntime(WorkerSessionAccepted limits) {
    this.limits = limits;
  }

  /**
   * A runtime whose server accepts the worker session with this many upload calls at once; 0 states
   * no limit.
   */
  public static ScriptedWorkerRuntime withSegmentUploadLimit(int segmentUploadLimit) {
    return accepting(WorkerSessionAccepted.newBuilder().setSegmentUploadLimit(segmentUploadLimit));
  }

  /**
   * A runtime whose server states these limits when it accepts the worker session; a limit left
   * unset states no limit.
   */
  public static ScriptedWorkerRuntime accepting(WorkerSessionAccepted.Builder limits) {
    return new ScriptedWorkerRuntime(limits.build());
  }

  @Override
  public ManagedChannel openChannel(
      TranscodeWorkerConfiguration configuration, InetSocketAddress address) {
    connection = new Connection(limits);
    return connection;
  }

  @Override
  public ExecutorService newProbeReportScope() {
    return connection().reportThreads;
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

  private static StatusRuntimeException reportRefusal() {
    return Status.FAILED_PRECONDITION
        .withDescription("The probe attempt takes no report")
        .asRuntimeException();
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
    private final List<ReportCall> reportCalls = new ArrayList<>();
    private final UploadReadiness reportReadiness = new UploadReadiness();
    private final OpenUploads openReportCalls = new OpenUploads();
    private final ReportThreads reportThreads = new ReportThreads();
    private volatile boolean refusingAtTheWorkersLimit;
    private volatile boolean acknowledgementsWithheld;
    private volatile boolean reportAnswersWithheld;
    private volatile boolean refusingNextReport;
    private boolean shutdown;

    private Connection(WorkerSessionAccepted limits) {
      call = new SessionCall(limits);
    }

    /** The id of the worker session that the scripted server accepted. */
    public Uuid workerSessionId() {
      assertThat(call.workerSessionId).as("the server accepted the worker session").isCompleted();
      return call.workerSessionId.resultNow();
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

    /**
     * The probe attempt that each {@code ProbeAttemptEndedWithoutReport} event of the worker names,
     * in the order in which the worker sent the events.
     */
    public List<Uuid> attemptsEndedWithoutReport() {
      return call.events.stream()
          .filter(EstablishWorkerSessionRequest::hasProbeAttemptEndedWithoutReport)
          .map(event -> event.getProbeAttemptEndedWithoutReport().getProbeAttemptId())
          .toList();
    }

    /**
     * Delivers this command when the worker sends its next event that matches, on the thread that
     * sends the event and before the send returns, as a server that answers the event at once.
     */
    public void deliverWhenTheWorkerSends(
        Predicate<EstablishWorkerSessionRequest> event, EstablishWorkerSessionResponse command) {
      call.answers.add(new EventAnswer(event, command));
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
      return uploads.stream().mapToInt(upload -> upload.messages().size()).sum();
    }

    /** The number of upload messages the worker sent while its call reported it was not ready. */
    public int uploadMessagesSentWhileNotReady() {
      return readiness.violations();
    }

    /**
     * The result of every probe report that the worker has sent whole, in the order in which the
     * worker opened the report calls, once the worker's report threads have nothing left to send.
     */
    public List<ProbeAttemptResult> results() {
      return reports().stream().map(ProbeAttemptReport::getResult).toList();
    }

    /**
     * Every probe report that the worker has sent whole, in the order in which the worker opened
     * the report calls, once the worker's report threads have nothing left to send. A report whose
     * call still waits, for readiness or for a withheld answer, keeps this method waiting.
     */
    public List<ProbeAttemptReport> reports() {
      reportThreads.awaitIdle();
      return reportCalls().stream()
          .filter(ReportCall::wasSentWhole)
          .map(ReportCall::report)
          .toList();
    }

    /** Every report call that the worker has opened so far, in the order it opened them. */
    public List<ReportCall> reportCalls() {
      synchronized (reportCalls) {
        return List.copyOf(reportCalls);
      }
    }

    /** Waits until the worker has opened this many report calls, and returns them in order. */
    public List<ReportCall> awaitReportCalls(int count) throws InterruptedException {
      synchronized (reportCalls) {
        while (reportCalls.size() < count) {
          reportCalls.wait();
        }

        return List.copyOf(reportCalls);
      }
    }

    /** Waits until every report that the worker has begun to send has ended. */
    public void awaitReportThreads() {
      reportThreads.awaitIdle();
    }

    /** Whether the worker has shut down the threads that send this session's reports. */
    public boolean hasJoinedReportThreads() {
      return reportThreads.isTerminated();
    }

    /**
     * From now on a report thread stops before its first statement, as a thread that has started
     * and has yet to run, until {@link #releaseReportThreads()}.
     */
    public void holdReportThreads() {
      reportThreads.hold();
    }

    public void releaseReportThreads() {
      reportThreads.release();
    }

    /** Waits until the worker's close waits for this session's report threads to end. */
    public void awaitJoiningReportThreads(CompletableFuture<?> workerClose)
        throws InterruptedException, ExecutionException {
      CompletableFuture.anyOf(reportThreads.joining, workerClose).get();
      assertThat(reportThreads.joining)
          .as("the worker waited for its report threads before its close ended")
          .isDone();
    }

    /**
     * From now on a report call reports that it is ready for one more message only after each
     * {@link #grantReportMessage()}.
     */
    public void withholdReportReadiness() {
      reportReadiness.withhold();
    }

    /** Lets the worker send one more message on a report call. */
    public void grantReportMessage() {
      reportReadiness.grant();
      reportCalls().forEach(ReportCall::signalReady);
    }

    /** From now on a report call that the worker half-closes is not answered. */
    public void withholdReportAnswers() {
      reportAnswersWithheld = true;
    }

    /** Stops withholding answers and answers every report call that awaits one. */
    public void releaseReportAnswers() {
      reportAnswersWithheld = false;
      reportCalls().forEach(ReportCall::acknowledgeWhenAwaited);
    }

    /** The next report call that the worker opens is refused once its first message arrives. */
    public void refuseNextReport() {
      refusingNextReport = true;
    }

    /** The most report calls open at once: started, and neither answered, refused nor canceled. */
    public int mostReportCallsOpenAtOnce() {
      return openReportCalls.most();
    }

    /** The number of report messages the worker sent while its call reported it was not ready. */
    public int reportMessagesSentWhileNotReady() {
      return reportReadiness.violations();
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

      if (method.equals(TranscodeWorkerServiceGrpc.getReportProbeAttemptMethod())) {
        return typed(newReportCall());
      }

      assertThat(method).isEqualTo(TranscodeWorkerServiceGrpc.getEstablishWorkerSessionMethod());
      return typed(call);
    }

    private ReportCall newReportCall() {
      var report = new ReportCall(reportReadiness, openReportCalls, () -> reportAnswersWithheld);
      if (refusingNextReport) {
        refusingNextReport = false;
        report.refuseOnTheFirstMessage(reportRefusal());
      }

      synchronized (reportCalls) {
        reportCalls.add(report);
        reportCalls.notifyAll();
      }

      return report;
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
    private final CompletableFuture<Uuid> workerSessionId = new CompletableFuture<>();
    private final List<EventAnswer> answers = new CopyOnWriteArrayList<>();
    private final WorkerSessionAccepted limits;
    private Listener<EstablishWorkerSessionResponse> responses;
    private volatile boolean refusing;

    private SessionCall(WorkerSessionAccepted limits) {
      this.limits = limits;
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
        var accepted = limits.toBuilder().setWorkerSessionId(toProto(UUID.randomUUID())).build();
        workerSessionId.complete(accepted.getWorkerSessionId());
        responses.onMessage(
            EstablishWorkerSessionResponse.newBuilder().setSessionAccepted(accepted).build());
      }

      answers.stream()
          .filter(answer -> answer.event().test(message))
          .findFirst()
          .filter(answers::remove)
          .ifPresent(answer -> responses.onMessage(answer.command()));
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

  // A command that the scripted server delivers at once when the worker sends a matching event.
  private record EventAnswer(
      Predicate<EstablishWorkerSessionRequest> event, EstablishWorkerSessionResponse command) {}

  // The threads that send the worker's probe reports: one virtual thread for each task, counted so
  // that a test can wait until the worker has nothing left to send.
  private static final class ReportThreads extends AbstractExecutorService {

    private final ExecutorService running = Executors.newVirtualThreadPerTaskExecutor();
    private final CompletableFuture<Void> joining = new CompletableFuture<>();
    private int unfinished;
    private boolean held;

    @Override
    public void execute(Runnable command) {
      started();
      try {
        running.execute(
            () -> {
              try {
                awaitReleased();
                command.run();
              } finally {
                finished();
              }
            });
      } catch (RejectedExecutionException rejection) {
        finished();
        throw rejection;
      }
    }

    private synchronized void hold() {
      held = true;
    }

    private synchronized void release() {
      held = false;
      notifyAll();
    }

    // An interrupt during the hold stays pending, as it does for a thread that has yet to run.
    private synchronized void awaitReleased() {
      var interrupted = false;
      while (held) {
        try {
          wait();
        } catch (InterruptedException _) {
          interrupted = true;
        }
      }

      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    private synchronized void started() {
      unfinished++;
    }

    private synchronized void finished() {
      unfinished--;
      notifyAll();
    }

    // The test's own timeout interrupts a wait for a report that never ends.
    private synchronized void awaitIdle() {
      try {
        while (unfinished > 0) {
          wait();
        }
      } catch (InterruptedException interruption) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while a probe report was still being sent");
      }
    }

    @Override
    public void shutdown() {
      running.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
      return running.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
      return running.isShutdown();
    }

    @Override
    public boolean isTerminated() {
      return running.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
      joining.complete(null);
      return running.awaitTermination(timeout, unit);
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
   * A client-streaming upload that records what the worker sent and acknowledges every byte. Like a
   * gRPC call, a cancelled upload closes with {@code CANCELLED} and is never acknowledged. A
   * refused upload closes with the refusal's status and trailers once the worker's first message,
   * which names what the call uploads, arrives.
   *
   * @param <Q> the messages that the worker sends on the call
   * @param <R> the server's single answer
   */
  public abstract static class ScriptedUpload<Q, R> extends ClientCall<Q, R> {

    private final UploadReadiness readiness;
    private final OpenUploads openUploads;
    private final BooleanSupplier acknowledgementsWithheld;
    private final List<Q> messages = new CopyOnWriteArrayList<>();
    private volatile Listener<R> responses;
    private Optional<StatusRuntimeException> refusal = Optional.empty();
    private final CompletableFuture<Void> sentWhole = new CompletableFuture<>();
    private boolean closed;
    private boolean cancelled;
    private boolean halfClosed;

    private ScriptedUpload(
        UploadReadiness readiness,
        OpenUploads openUploads,
        BooleanSupplier acknowledgementsWithheld) {
      this.readiness = readiness;
      this.openUploads = openUploads;
      this.acknowledgementsWithheld = acknowledgementsWithheld;
    }

    /** The bytes that a data message carries; empty for the message that names the upload. */
    abstract Optional<ByteString> dataOf(Q message);

    /** The server's answer to an upload of this many bytes. */
    abstract R acknowledgementOf(long acceptedLengthBytes);

    synchronized void refuseOnTheFirstMessage(StatusRuntimeException refusal) {
      this.refusal = Optional.of(refusal);
    }

    void refuseNow(StatusRuntimeException refusal) {
      synchronized (this) {
        this.refusal = Optional.of(refusal);
      }

      refuse(refusal);
    }

    // Closes the open call with this status and without an answer, as a failed transport does.
    void failNow(Status status) {
      if (!tryClose()) {
        return;
      }

      responses.onClose(status, new Metadata());
    }

    /** Whether the server refused the upload. */
    public synchronized boolean wasRefused() {
      return refusal.isPresent();
    }

    /** Every message that the worker sent on the call, in order. */
    public List<Q> messages() {
      return List.copyOf(messages);
    }

    /** Waits until the worker has sent this many messages, and returns every message so far. */
    public List<Q> awaitMessages(int count) throws InterruptedException {
      synchronized (messages) {
        while (messages.size() < count) {
          messages.wait();
        }

        return List.copyOf(messages);
      }
    }

    /** The length of each data message's content, in order. */
    public List<Integer> dataMessageLengths() {
      return messages.stream()
          .map(this::dataOf)
          .flatMap(Optional::stream)
          .map(ByteString::size)
          .toList();
    }

    public byte[] content() {
      var content = new ByteArrayOutputStream();
      messages.stream()
          .map(this::dataOf)
          .flatMap(Optional::stream)
          .forEach(data -> content.writeBytes(data.toByteArray()));
      return content.toByteArray();
    }

    void signalReady() {
      var listener = responses;
      if (listener != null && readiness.isReady()) {
        listener.onReady();
      }
    }

    @Override
    public void start(Listener<R> listener, Metadata headers) {
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
    public void sendMessage(Q message) {
      readiness.consume();
      synchronized (messages) {
        messages.add(message);
        messages.notifyAll();
      }

      refusal().ifPresent(this::refuse);
    }

    public synchronized boolean wasCancelled() {
      return cancelled;
    }

    /** Whether the worker sent the whole upload and awaits the acknowledgement. */
    public synchronized boolean awaitsAcknowledgement() {
      return halfClosed && !closed;
    }

    /** Whether the worker sent the whole upload: every message, and then its half-close. */
    public synchronized boolean wasSentWhole() {
      return halfClosed;
    }

    /** Waits until the worker has sent the whole upload. */
    public void awaitSentWhole() throws InterruptedException, ExecutionException {
      sentWhole.get();
    }

    @Override
    public void halfClose() {
      synchronized (this) {
        halfClosed = true;
      }

      sentWhole.complete(null);
      if (acknowledgementsWithheld.getAsBoolean()) {
        return;
      }

      acknowledge();
    }

    void acknowledgeWhenAwaited() {
      if (awaitsAcknowledgement()) {
        acknowledge();
      }
    }

    private void acknowledge() {
      answer(acknowledgementOf(content().length));
    }

    void answer(R answer) {
      if (!tryClose()) {
        return;
      }

      responses.onMessage(answer);
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

  /** A segment upload, whose first message names the segment. */
  public static final class UploadCall
      extends ScriptedUpload<UploadSegmentRequest, UploadSegmentResponse> {

    private UploadCall(
        UploadReadiness readiness,
        OpenUploads openUploads,
        BooleanSupplier acknowledgementsWithheld) {
      super(readiness, openUploads, acknowledgementsWithheld);
    }

    /**
     * Refuses the open upload because the worker holds its segment upload limit of calls, as when
     * the server's refusal reaches the worker after it has sent the segment.
     */
    public void refuseAtTheWorkersLimit() {
      refuseNow(workerLimitRefusal());
    }

    public SegmentUploadMetadata metadata() {
      return messages().getFirst().getMetadata();
    }

    @Override
    Optional<ByteString> dataOf(UploadSegmentRequest message) {
      return message.hasData() ? Optional.of(message.getData()) : Optional.empty();
    }

    @Override
    UploadSegmentResponse acknowledgementOf(long acceptedLengthBytes) {
      return UploadSegmentResponse.newBuilder().setAcceptedLengthBytes(acceptedLengthBytes).build();
    }
  }

  /**
   * A report call, whose first message names the probe attempt and states the length of its encoded
   * probe report.
   */
  public static final class ReportCall
      extends ScriptedUpload<ReportProbeAttemptRequest, ReportProbeAttemptResponse> {

    private ReportCall(
        UploadReadiness readiness, OpenUploads openCalls, BooleanSupplier answersWithheld) {
      super(readiness, openCalls, answersWithheld);
    }

    public ProbeAttemptReportMetadata metadata() {
      return messages().getFirst().getMetadata();
    }

    /** The probe report that the call's data messages carry. */
    public ProbeAttemptReport report() {
      try {
        return ProbeAttemptReport.parseFrom(content());
      } catch (InvalidProtocolBufferException invalid) {
        throw new AssertionError("The report call's bytes are no probe report", invalid);
      }
    }

    /** Answers the call, which awaits its answer, with this accepted length. */
    public void answerWithAcceptedLength(long acceptedLengthBytes) {
      answer(acknowledgementOf(acceptedLengthBytes));
    }

    /** Refuses the open call, as when the server no longer expects the attempt's report. */
    public void refuse() {
      refuseNow(reportRefusal());
    }

    /** Ends the open call as a transport that failed before the server answered does. */
    public void failTransport() {
      failNow(Status.UNAVAILABLE.withDescription("The transport of the report call failed"));
    }

    @Override
    Optional<ByteString> dataOf(ReportProbeAttemptRequest message) {
      return message.hasData() ? Optional.of(message.getData()) : Optional.empty();
    }

    @Override
    ReportProbeAttemptResponse acknowledgementOf(long acceptedLengthBytes) {
      return ReportProbeAttemptResponse.newBuilder()
          .setProbeAttemptId(metadata().getProbeAttemptId())
          .setAcceptedLengthBytes(acceptedLengthBytes)
          .build();
    }
  }
}
