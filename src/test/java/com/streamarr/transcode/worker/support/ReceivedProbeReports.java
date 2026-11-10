package com.streamarr.transcode.worker.support;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReportMetadata;
import build.buf.gen.streamarr.transcode.v1.ReportProbeAttemptRequest;
import build.buf.gen.streamarr.transcode.v1.ReportProbeAttemptResponse;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import lombok.NonNull;

/**
 * The report calls of a scripted control plane that listens on a socket. It joins the bytes of each
 * call, decodes the probe report and answers with the length it received, as the server does.
 */
public final class ReceivedProbeReports {

  private final List<Received> received = new CopyOnWriteArrayList<>();
  private final Consumer<ProbeAttemptReport> listener;

  /** Report calls whose reports are only recorded. */
  public ReceivedProbeReports() {
    this(_ -> {});
  }

  /** Report calls that also hand each report received whole to the listener. */
  public ReceivedProbeReports(@NonNull Consumer<ProbeAttemptReport> listener) {
    this.listener = listener;
  }

  /**
   * One report call as it arrived: its first message, the length of each data message in order, and
   * the report that the joined bytes decode to.
   */
  public record Received(
      ProbeAttemptReportMetadata metadata,
      List<Integer> dataMessageLengths,
      ProbeAttemptReport report) {}

  /** Every report call that arrived whole, in the order in which the calls ended. */
  public List<Received> received() {
    return List.copyOf(received);
  }

  /** Serves one report call; a service returns this from its {@code reportProbeAttempt}. */
  public StreamObserver<ReportProbeAttemptRequest> serve(
      @NonNull StreamObserver<ReportProbeAttemptResponse> responses) {
    return new StreamObserver<>() {
      private final List<Integer> dataMessageLengths = new ArrayList<>();
      private ProbeAttemptReportMetadata metadata;
      private ByteString bytes = ByteString.EMPTY;

      @Override
      public void onNext(ReportProbeAttemptRequest request) {
        if (request.hasMetadata()) {
          metadata = request.getMetadata();
          return;
        }

        dataMessageLengths.add(request.getData().size());
        bytes = bytes.concat(request.getData());
      }

      @Override
      public void onError(Throwable failure) {
        // A call that the worker cancels is not recorded or answered.
      }

      @Override
      public void onCompleted() {
        ProbeAttemptReport report;
        try {
          report = ProbeAttemptReport.parseFrom(bytes);
        } catch (InvalidProtocolBufferException invalid) {
          responses.onError(Status.INVALID_ARGUMENT.withCause(invalid).asRuntimeException());
          return;
        }

        received.add(new Received(metadata, List.copyOf(dataMessageLengths), report));
        responses.onNext(
            ReportProbeAttemptResponse.newBuilder()
                .setProbeAttemptId(metadata.getProbeAttemptId())
                .setAcceptedLengthBytes(bytes.size())
                .build());
        responses.onCompleted();
        listener.accept(report);
      }
    };
  }
}
