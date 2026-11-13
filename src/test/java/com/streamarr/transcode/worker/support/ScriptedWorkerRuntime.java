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
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.ConnectivityState;
import io.grpc.Context;
import io.grpc.Contexts;
import io.grpc.Deadline;
import io.grpc.ForwardingClientCall;
import io.grpc.ForwardingClientCallListener;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.protobuf.StatusProto;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

public final class ScriptedWorkerRuntime implements WorkerRuntime {

  private static final Metadata.Key<String> CALL_ID_HEADER =
      Metadata.Key.of("scripted-call-id", Metadata.ASCII_STRING_MARSHALLER);
  private static final Context.Key<String> CALL_ID = Context.key("scripted-call-id");

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
    // The scripted server's upload and report calls, by the id that each of the worker's calls
    // carries in its headers.
    private final Map<String, ScriptedUpload<?, ?>> callsById = new ConcurrentHashMap<>();
    private final Server server;
    private final ManagedChannel uploadChannel;
    // The in-process channel schedules the deadline of each of the worker's calls here.
    private final ScheduledThreadPoolExecutor callDeadlines = callDeadlineTimers();
    private volatile boolean refusingAtTheWorkersLimit;
    private volatile boolean acknowledgementsWithheld;
    private volatile boolean reportAnswersWithheld;
    private volatile boolean refusingNextReport;
    private volatile boolean uploadsFailAfterTheirAnswers;
    private volatile boolean reportCallsFailAfterTheirAnswers;
    private boolean shutdown;

    // The worker's upload calls and report calls run on gRPC's in-process transport, against a
    // scripted server that runs every callback on the thread that causes it.
    private Connection(WorkerSessionAccepted limits) {
      call = new SessionCall(limits);
      var name = InProcessServerBuilder.generateName();
      server =
          started(
              InProcessServerBuilder.forName(name)
                  .directExecutor()
                  .addService(
                      ServerInterceptors.intercept(new ScriptedServer(), new CallIdReader()))
                  .build());
      // An idle channel would schedule its idle timer beside the deadlines.
      uploadChannel =
          InProcessChannelBuilder.forName(name)
              .directExecutor()
              .scheduledExecutorService(callDeadlines)
              .idleTimeout(30, TimeUnit.DAYS)
              .intercept(new WorkerCallInstrumentation())
              .build();
      awaitReady(uploadChannel);
    }

    // A cancelled deadline leaves the queue at once, and the timer thread ends once none is left.
    private static ScheduledThreadPoolExecutor callDeadlineTimers() {
      var timers = new ScheduledThreadPoolExecutor(1, Thread.ofPlatform().daemon().factory());
      timers.setRemoveOnCancelPolicy(true);
      timers.setKeepAliveTime(1, TimeUnit.SECONDS);
      timers.allowCoreThreadTimeOut(true);
      return timers;
    }

    private static Server started(Server server) {
      try {
        return server.start();
      } catch (IOException failure) {
        throw new UncheckedIOException(failure);
      }
    }

    // A call that the worker opens on a ready channel reaches the scripted server before its start
    // returns, so the server records the worker's calls in the order the worker opened them.
    private static void awaitReady(ManagedChannel channel) {
      var state = channel.getState(true);
      while (state != ConnectivityState.READY) {
        var changed = new CountDownLatch(1);
        channel.notifyWhenStateChanged(state, changed::countDown);
        try {
          changed.await();
        } catch (InterruptedException _) {
          Thread.currentThread().interrupt();
          throw new AssertionError("Interrupted while the in-process channel connected");
        }

        state = channel.getState(true);
      }
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
    }

    /** From now on an upload that the worker half-closes is never acknowledged. */
    public void withholdAcknowledgements() {
      acknowledgementsWithheld = true;
    }

    /** Acknowledges every upload that awaits its acknowledgement now, and withholds later ones. */
    public void acknowledgeAwaitingUploads() {
      uploads.forEach(UploadCall::acknowledgeWhenAwaited);
    }

    /** Fails each later upload as a broken transport does once the server has answered it. */
    public void failUploadsAfterTheirAnswers() {
      uploadsFailAfterTheirAnswers = true;
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

    /**
     * Waits until every report that the worker has begun to send has ended or awaits its answer,
     * and returns the report calls that await their answers, in the order the worker opened them.
     * While the test withholds the answers, these are the report calls that the worker keeps open.
     */
    public List<ReportCall> reportCallsAwaitingAnswers() {
      reportThreads.awaitAtMostUnfinished(() -> awaitingAnswers().size());
      return awaitingAnswers();
    }

    private List<ReportCall> awaitingAnswers() {
      return reportCalls().stream().filter(ReportCall::awaitsAcknowledgement).toList();
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

    /** Fails each later report call as a broken transport does once the server has answered it. */
    public void failReportCallsAfterTheirAnswers() {
      reportCallsFailAfterTheirAnswers = true;
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

    /**
     * The deadlines of the worker's upload and report calls that gRPC still holds: one for each
     * open call, and one for each call whose end the worker left unprocessed.
     */
    public int pendingCallDeadlines() {
      return callDeadlines.getQueue().size();
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
      if (method.equals(TranscodeWorkerServiceGrpc.getUploadSegmentMethod())
          || method.equals(TranscodeWorkerServiceGrpc.getReportProbeAttemptMethod())) {
        return uploadChannel.newCall(method, options);
      }

      assertThat(method).isEqualTo(TranscodeWorkerServiceGrpc.getEstablishWorkerSessionMethod());
      return typed(call);
    }

    private UploadCall newUploadCall() {
      var upload = new UploadCall(readiness, openUploads, () -> acknowledgementsWithheld);
      nextRefusal().ifPresent(upload::refuseOnTheFirstMessage);
      if (uploadsFailAfterTheirAnswers) {
        upload.failAfterItsAnswer();
      }

      return upload;
    }

    private ReportCall newReportCall() {
      var report = new ReportCall(reportReadiness, openReportCalls, () -> reportAnswersWithheld);
      report.whenSentWhole(reportThreads::signal);
      if (reportCallsFailAfterTheirAnswers) {
        report.failAfterItsAnswer();
      }

      if (refusingNextReport) {
        refusingNextReport = false;
        report.refuseOnTheFirstMessage(reportRefusal());
      }

      return report;
    }

    private void recordReportCall(ReportCall report) {
      synchronized (reportCalls) {
        reportCalls.add(report);
        reportCalls.notifyAll();
      }
    }

    private Optional<StatusRuntimeException> nextRefusal() {
      if (refusingAtTheWorkersLimit) {
        return Optional.of(workerLimitRefusal());
      }

      return Optional.ofNullable(refusals.poll());
    }

    // Takes the worker's call as the server's call with the id that the call carries, and counts it
    // as open before the server records it, so a test that sees the call sees it counted.
    private <U extends ScriptedUpload<Q, R>, Q, R> U accept(U call, StreamObserver<R> responses) {
      ScriptedUpload<Q, R> upload = call;
      callsById.put(CALL_ID.get(), upload);
      upload.start((ServerCallStreamObserver<R>) responses);
      return call;
    }

    private UploadReadiness readinessOf(MethodDescriptor<?, ?> method) {
      if (method.equals(TranscodeWorkerServiceGrpc.getUploadSegmentMethod())) {
        return readiness;
      }

      return reportReadiness;
    }

    private final class ScriptedServer
        extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase {

      @Override
      public StreamObserver<UploadSegmentRequest> uploadSegment(
          StreamObserver<UploadSegmentResponse> responses) {
        var upload = accept(newUploadCall(), responses);
        uploads.add(upload);
        return upload;
      }

      @Override
      public StreamObserver<ReportProbeAttemptRequest> reportProbeAttempt(
          StreamObserver<ReportProbeAttemptResponse> responses) {
        var report = accept(newReportCall(), responses);
        recordReportCall(report);
        return report;
      }
    }

    // Names each of the worker's calls in its headers, counts the messages that the worker sends
    // while its call is not ready for them, and runs a call's hooks on the worker's thread when the
    // worker learns that the call ended.
    private final class WorkerCallInstrumentation implements ClientInterceptor {

      @Override
      public <Q, R> ClientCall<Q, R> interceptCall(
          MethodDescriptor<Q, R> method, CallOptions options, Channel next) {
        var id = UUID.randomUUID().toString();
        var readinessOfCall = readinessOf(method);
        return new ForwardingClientCall.SimpleForwardingClientCall<>(
            next.newCall(method, options)) {

          @Override
          public void start(Listener<R> responseListener, Metadata headers) {
            headers.put(CALL_ID_HEADER, id);
            super.start(
                new ForwardingClientCallListener.SimpleForwardingClientCallListener<>(
                    responseListener) {

                  @Override
                  public void onClose(Status status, Metadata trailers) {
                    super.onClose(status, trailers);
                    Optional.ofNullable(callsById.get(id))
                        .ifPresent(ScriptedUpload::endKnownToTheWorker);
                  }
                },
                headers);
          }

          @Override
          public void sendMessage(Q message) {
            if (!isReady()) {
              readinessOfCall.violated();
            }

            super.sendMessage(message);
          }
        };
      }
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

    // The worker opens no call once it has shut its channel down, and a call that it opened earlier
    // ends as the test scripts it.
    @Override
    public ManagedChannel shutdown() {
      shutdown = true;
      uploadChannel.shutdown();
      server.shutdown();
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

  // Hands each server call the id that the worker's call carries in its headers.
  private static final class CallIdReader implements ServerInterceptor {

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(
        ServerCall<Q, R> call, Metadata headers, ServerCallHandler<Q, R> next) {
      return Contexts.interceptCall(
          Context.current().withValue(CALL_ID, headers.get(CALL_ID_HEADER)), call, headers, next);
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

    private void awaitIdle() {
      awaitAtMostUnfinished(() -> 0);
    }

    // Waits until at most this many report threads have yet to finish; the supplier is read again
    // after every signal. The test's own timeout interrupts a wait that never ends.
    private synchronized void awaitAtMostUnfinished(IntSupplier most) {
      try {
        while (unfinished > most.getAsInt()) {
          wait();
        }
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while a probe report was still being sent");
      }
    }

    private synchronized void signal() {
      notifyAll();
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

  // Whether the scripted server reads another message of an open call: at once, unless the test
  // withholds readiness and grants one message at a time. A grant goes to the earliest open call
  // that waits for one, or waits itself for the next call that asks.
  private static final class UploadReadiness {

    private final Deque<ScriptedUpload<?, ?>> waiting = new ArrayDeque<>();
    private boolean withheld;
    private int grants;
    private int violations;

    private synchronized void withhold() {
      withheld = true;
    }

    private void grant() {
      Optional<ScriptedUpload<?, ?>> next;
      synchronized (this) {
        next = Optional.ofNullable(waiting.pollFirst());
        if (next.isEmpty()) {
          grants++;
        }
      }

      next.ifPresent(ScriptedUpload::requestOne);
    }

    // The call reads one more message once readiness lets it; the request runs outside this
    // monitor, because gRPC can deliver the call's half-close inside it.
    private void readOneMoreOf(ScriptedUpload<?, ?> upload) {
      synchronized (this) {
        if (withheld && grants == 0) {
          waiting.addLast(upload);
          return;
        }

        if (withheld) {
          grants--;
        }
      }

      upload.requestOne();
    }

    // A grant that an ended call never used goes to the next call.
    private synchronized void ended(ScriptedUpload<?, ?> upload, boolean requestedUnread) {
      waiting.remove(upload);
      if (withheld && requestedUnread) {
        grants++;
      }
    }

    private synchronized void violated() {
      violations++;
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
   * The scripted server's side of a client-streaming upload, which records what the worker sent and
   * acknowledges every byte. A call that the worker cancels, or that outlives its deadline, ends
   * without an answer. A refused upload closes with the refusal's status and trailers once the
   * worker's first message, which names what the call uploads, arrives.
   *
   * @param <Q> the messages that the worker sends on the call
   * @param <R> the server's single answer
   */
  public abstract static class ScriptedUpload<Q, R> implements StreamObserver<Q> {

    private final UploadReadiness readiness;
    private final OpenUploads openUploads;
    private final BooleanSupplier acknowledgementsWithheld;
    private final List<Q> messages = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> sentWhole = new CompletableFuture<>();
    private final CompletableFuture<Void> endKnownToTheWorker = new CompletableFuture<>();
    private ServerCallStreamObserver<R> responses;
    private Optional<Deadline> deadline = Optional.empty();
    private Thread workersThread;
    private Optional<StatusRuntimeException> refusal = Optional.empty();
    private boolean requestedUnread;
    private boolean closed;
    private boolean cancelled;
    private boolean halfClosed;
    private boolean failsAfterItsAnswer;

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

    // Runs inside the server's method, which alone may turn gRPC's own reads off, in the call's
    // context, which holds the deadline that the worker stated, and on the worker's thread that
    // opens the call.
    private void start(ServerCallStreamObserver<R> calls) {
      calls.disableAutoRequest();
      calls.setOnCancelHandler(this::cancelled);
      synchronized (this) {
        responses = calls;
        deadline = Optional.ofNullable(Context.current().getDeadline());
        workersThread = Thread.currentThread();
      }

      openUploads.opened();
      readiness.readOneMoreOf(this);
    }

    private void requestOne() {
      ServerCallStreamObserver<R> calls;
      synchronized (this) {
        if (closed) {
          return;
        }

        requestedUnread = true;
        calls = responses;
      }

      calls.request(1);
    }

    synchronized void failAfterItsAnswer() {
      failsAfterItsAnswer = true;
    }

    private synchronized boolean failsAfterItsAnswer() {
      return failsAfterItsAnswer;
    }

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

      responses().onError(status.asRuntimeException());
    }

    /** The deadline that the worker stated for the call; empty when it stated none. */
    public synchronized Optional<Deadline> deadline() {
      return deadline;
    }

    /** Interrupts the worker's thread that opened the call, as a shutdown of its threads does. */
    public synchronized void interruptTheWorkersThread() {
      workersThread.interrupt();
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

    private synchronized Optional<StatusRuntimeException> refusal() {
      return refusal;
    }

    private synchronized ServerCallStreamObserver<R> responses() {
      return responses;
    }

    private void refuse(StatusRuntimeException refusal) {
      if (!tryClose()) {
        return;
      }

      responses().onError(refusal);
    }

    @Override
    public void onNext(Q message) {
      synchronized (this) {
        requestedUnread = false;
      }

      synchronized (messages) {
        messages.add(message);
        messages.notifyAll();
      }

      var refused = refusal();
      if (refused.isPresent()) {
        refuse(refused.get());
        return;
      }

      readiness.readOneMoreOf(this);
    }

    // gRPC reports the worker's cancel and an expired deadline here as well as to the cancel
    // handler.
    @Override
    public void onError(Throwable failure) {
      cancelled();
    }

    /**
     * Whether the worker cancelled the call, or its deadline passed, before the server closed it.
     */
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

    void whenSentWhole(Runnable action) {
      sentWhole.thenRun(action);
    }

    @Override
    public void onCompleted() {
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

      var calls = responses();
      calls.onNext(answer);
      if (failsAfterItsAnswer()) {
        calls.onError(
            Status.UNAVAILABLE
                .withDescription("The transport failed after the answer")
                .asRuntimeException());
        return;
      }

      calls.onCompleted();
    }

    /**
     * Runs this action on the worker's thread when the worker learns that the call ended, before
     * the worker acts on that end.
     */
    public void whenTheWorkerLearnsTheCallEnded(Runnable action) {
      endKnownToTheWorker.thenRun(action);
    }

    private void endKnownToTheWorker() {
      endKnownToTheWorker.complete(null);
    }

    private void cancelled() {
      if (!tryClose()) {
        return;
      }

      synchronized (this) {
        cancelled = true;
      }
    }

    private boolean tryClose() {
      boolean unread;
      synchronized (this) {
        if (closed) {
          return false;
        }

        closed = true;
        unread = requestedUnread;
      }

      openUploads.closed();
      readiness.ended(this, unread);
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
