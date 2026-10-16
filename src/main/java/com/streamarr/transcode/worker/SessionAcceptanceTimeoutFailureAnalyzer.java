package com.streamarr.transcode.worker;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Reports a server that never accepted the worker session in place of a stack trace. */
final class SessionAcceptanceTimeoutFailureAnalyzer
    extends AbstractFailureAnalyzer<SessionAcceptanceTimeoutException> {

  @Override
  protected FailureAnalysis analyze(
      Throwable rootFailure, SessionAcceptanceTimeoutException cause) {
    return new FailureAnalysis(
        cause.getMessage() + ".",
        "Start the Streamarr server and check that TRANSCODE_WORKER_CONTROL_PLANE_HOST and"
            + " TRANSCODE_WORKER_CONTROL_PLANE_PORT name its worker session listener. If the"
            + " server needs longer to start, raise TRANSCODE_WORKER_SESSION_ACCEPTANCE_TIMEOUT.",
        cause);
  }
}
