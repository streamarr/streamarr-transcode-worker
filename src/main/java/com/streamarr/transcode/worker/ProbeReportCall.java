package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReportMetadata;
import build.buf.gen.streamarr.transcode.v1.ReportProbeAttemptRequest;
import build.buf.gen.streamarr.transcode.v1.ReportProbeAttemptResponse;
import com.google.protobuf.ByteString;
import io.grpc.Context;
import io.grpc.StatusException;
import io.grpc.stub.BlockingClientCall;
import java.util.Optional;
import java.util.UUID;

/**
 * One probe attempt's report call (ADR 0062). The first message names the worker session, the
 * worker and the probe attempt, and states the length of the encoded probe report. Every later
 * message holds consecutive bytes of that encoding. The server then answers once with the length it
 * accepted. The upload deadline covers the whole call, from its opening to the server's answer.
 */
final class ProbeReportCall {

  /** The most bytes of the encoded report that one message carries. */
  private static final int MESSAGE_BYTES = 64 * 1024;

  private final UUID probeAttemptId;
  private final ByteString encoded;
  // Cancelling the context ends the open call from any thread without touching the call, which
  // only the report thread uses.
  private final Context.CancellableContext cancellation = Context.ROOT.withCancellation();

  ProbeReportCall(ProbeAttemptReport report) {
    probeAttemptId = fromProto(report.getResult().getProbeAttemptId());
    encoded = report.toByteString();
  }

  /** Opens a report call for the worker session that produced the report. */
  @FunctionalInterface
  interface Opener {

    /**
     * Names the worker session and the worker in the first message's metadata and opens the call in
     * the context, whose cancellation ends it, or returns nothing, and opens no call, when that
     * worker session has ended. A call that opens in a cancelled context never reaches the server.
     */
    Optional<BlockingClientCall<ReportProbeAttemptRequest, ReportProbeAttemptResponse>> open(
        ProbeAttemptReportMetadata.Builder metadata, Context context);
  }

  UUID probeAttemptId() {
    return probeAttemptId;
  }

  /**
   * Sends the report and waits for the server's answer.
   *
   * @return whether the call opened; the report of a worker session that has ended is never sent
   * @throws StatusException when the call ended without the server's answer
   * @throws WorkerJobException when the server accepted another length than the report's
   */
  boolean send(Opener opener) throws InterruptedException, StatusException {
    var metadata =
        ProbeAttemptReportMetadata.newBuilder()
            .setProbeAttemptId(toProto(probeAttemptId))
            .setReportLengthBytes(encoded.size());
    try (cancellation) {
      var call = opener.open(metadata, cancellation);
      if (call.isEmpty()) {
        return false;
      }

      sendOn(call.get(), metadata);
      return true;
    }
  }

  // A write after the server has closed the call sends nothing, and the answer then decides. The
  // answer stands once the call closes after it, as a unary answer does.
  private void sendOn(
      BlockingClientCall<ReportProbeAttemptRequest, ReportProbeAttemptResponse> call,
      ProbeAttemptReportMetadata.Builder metadata)
      throws InterruptedException, StatusException {
    call.write(ReportProbeAttemptRequest.newBuilder().setMetadata(metadata).build());
    for (var sent = 0; sent < encoded.size(); sent += MESSAGE_BYTES) {
      var end = Math.min(sent + MESSAGE_BYTES, encoded.size());
      call.write(
          ReportProbeAttemptRequest.newBuilder().setData(encoded.substring(sent, end)).build());
    }

    call.halfClose();
    var accepted =
        Optional.ofNullable(call.read()).map(ReportProbeAttemptResponse::getAcceptedLengthBytes);
    call.read();
    if (accepted.filter(length -> length == encoded.size()).isEmpty()) {
      throw new WorkerJobException(
          "Server accepted "
              + accepted.map(String::valueOf).orElse("no")
              + " bytes of a probe report of "
              + encoded.size());
    }
  }

  /** Ends the open call, or keeps the call from reaching the server when it has not opened yet. */
  void cancel(Throwable reason) {
    cancellation.cancel(reason);
  }
}
