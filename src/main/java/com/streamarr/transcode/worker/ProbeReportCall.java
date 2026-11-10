package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReportMetadata;
import build.buf.gen.streamarr.transcode.v1.ReportProbeAttemptRequest;
import build.buf.gen.streamarr.transcode.v1.ReportProbeAttemptResponse;
import com.google.protobuf.ByteString;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * One probe attempt's report call (ADR 0062). The first message names the worker session, the
 * worker and the probe attempt, and states the length of the encoded probe report. Every later
 * message holds consecutive bytes of that encoding. The server then answers once with the length it
 * accepted. The worker waits the readiness timeout for each message and the acknowledgement timeout
 * for the answer, and no bound covers the whole call.
 */
final class ProbeReportCall {

  private final UUID probeAttemptId;
  private final ByteString encoded;
  private final FlowControlledCall<ReportProbeAttemptRequest, ReportProbeAttemptResponse> call;

  ProbeReportCall(
      ProbeAttemptReport report, Duration readinessTimeout, Duration acknowledgementTimeout) {
    probeAttemptId = fromProto(report.getResult().getProbeAttemptId());
    encoded = report.toByteString();
    call =
        FlowControlledCall.acknowledgedAfterItsLastMessage(
            readinessTimeout, acknowledgementTimeout);
  }

  /** Opens a report call for the worker session that produced the report. */
  @FunctionalInterface
  interface Opener {

    /**
     * Opens the call and returns its first message with the worker session and the worker named, or
     * returns nothing, and opens no call, when that worker session has ended.
     */
    Optional<ProbeAttemptReportMetadata.Builder> open(
        FlowControlledCall<ReportProbeAttemptRequest, ReportProbeAttemptResponse> call);
  }

  UUID probeAttemptId() {
    return probeAttemptId;
  }

  /**
   * Sends the report and waits for the server's answer.
   *
   * @return whether the call opened; the report of a worker session that has ended is never sent
   * @throws WorkerJobException when the server accepted another length than the report's
   */
  boolean send(Opener opener) throws InterruptedException, ExecutionException, TimeoutException {
    var metadata = opener.open(call);
    if (metadata.isEmpty()) {
      return false;
    }

    ReportProbeAttemptResponse answer;
    try {
      call.send(
          ReportProbeAttemptRequest.newBuilder()
              .setMetadata(
                  metadata
                      .get()
                      .setProbeAttemptId(toProto(probeAttemptId))
                      .setReportLengthBytes(encoded.size()))
              .build());
      sendBytes();
      call.finish();
      answer = call.awaitAcknowledgement();
    } catch (InterruptedException | ExecutionException | TimeoutException | RuntimeException e) {
      call.cancel(e);
      throw e;
    }

    if (answer.getAcceptedLengthBytes() != encoded.size()) {
      throw new WorkerJobException(
          "Server accepted "
              + answer.getAcceptedLengthBytes()
              + " bytes of a probe report of "
              + encoded.size());
    }

    return true;
  }

  private void sendBytes() throws InterruptedException, TimeoutException {
    for (var sent = 0; sent < encoded.size(); sent += FlowControlledCall.MESSAGE_BYTES) {
      var end = Math.min(sent + FlowControlledCall.MESSAGE_BYTES, encoded.size());
      call.send(
          ReportProbeAttemptRequest.newBuilder().setData(encoded.substring(sent, end)).build());
    }
  }

  /** Cancels the call, or keeps it from sending when it has not opened yet. */
  void cancel(Throwable reason) {
    call.cancel(reason);
  }
}
