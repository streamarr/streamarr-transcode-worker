package com.streamarr.transcode.worker;

import io.grpc.StatusException;
import io.grpc.stub.BlockingClientCall;
import java.util.Optional;

/** The server's single answer to a segment upload or a report call. */
final class CallAnswers {

  private CallAnswers() {}

  /**
   * Half-closes the call and waits for the server's answer. The answer decides, whatever status the
   * call closes with after it, so a transport that fails after the answer does not undo it. The
   * worker reads on until the call closes, because gRPC releases the call only once the thread that
   * reads it has seen its close.
   *
   * @return the answer; empty when the server closed the call without one
   * @throws StatusException when the call ended before the server answered
   */
  static <R> Optional<R> answerOf(BlockingClientCall<?, R> call)
      throws InterruptedException, StatusException {
    call.halfClose();
    var answer = Optional.ofNullable(call.read());
    try {
      call.read();
    } catch (StatusException _) {
      // The server has answered, and the call's end after the answer changes nothing.
    }

    return answer;
  }
}
