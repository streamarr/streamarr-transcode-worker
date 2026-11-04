package com.streamarr.transcode.worker;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/** Reports a host that cannot confine FFmpeg with Landlock in place of a stack trace. */
final class LandlockUnavailableFailureAnalyzer
    extends AbstractFailureAnalyzer<LandlockUnavailableException> {

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, LandlockUnavailableException cause) {
    return new FailureAnalysis(cause.getMessage() + ".", cause.getAction(), cause);
  }
}
