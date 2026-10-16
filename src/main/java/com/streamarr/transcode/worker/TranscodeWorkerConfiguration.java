package com.streamarr.transcode.worker;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record TranscodeWorkerConfiguration(
    @NonNull UUID workerId,
    @NonNull UUID bootId,
    int availableSlots,
    @NonNull Map<UUID, Path> sourceNamespaces,
    Duration uploadReadinessTimeout,
    Duration uploadAcknowledgementTimeout,
    Duration sessionAcceptanceTimeout) {

  static final Duration DEFAULT_UPLOAD_READINESS_TIMEOUT = Duration.ofSeconds(30);
  static final Duration DEFAULT_UPLOAD_ACKNOWLEDGEMENT_TIMEOUT = Duration.ofSeconds(60);
  // Covers a server cold start, database migrations included, when both start together.
  static final Duration DEFAULT_SESSION_ACCEPTANCE_TIMEOUT = Duration.ofMinutes(5);

  public TranscodeWorkerConfiguration {
    if (availableSlots < 1) {
      throw new IllegalArgumentException("Available slots must be positive");
    }

    sourceNamespaces = Map.copyOf(sourceNamespaces);
    if (uploadReadinessTimeout == null) {
      uploadReadinessTimeout = DEFAULT_UPLOAD_READINESS_TIMEOUT;
    }
    if (uploadAcknowledgementTimeout == null) {
      uploadAcknowledgementTimeout = DEFAULT_UPLOAD_ACKNOWLEDGEMENT_TIMEOUT;
    }

    if (sessionAcceptanceTimeout == null) {
      sessionAcceptanceTimeout = DEFAULT_SESSION_ACCEPTANCE_TIMEOUT;
    }
  }
}
