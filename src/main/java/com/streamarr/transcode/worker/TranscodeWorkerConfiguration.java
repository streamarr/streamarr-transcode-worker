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
    Duration keepAliveTime,
    Duration keepAliveTimeout,
    Duration uploadReadinessTimeout,
    Duration uploadAcknowledgementTimeout) {

  private static final Duration DEFAULT_KEEPALIVE_TIME = Duration.ofSeconds(30);
  private static final Duration DEFAULT_KEEPALIVE_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration DEFAULT_UPLOAD_READINESS_TIMEOUT = Duration.ofSeconds(30);
  private static final Duration DEFAULT_UPLOAD_ACKNOWLEDGEMENT_TIMEOUT = Duration.ofSeconds(60);

  public TranscodeWorkerConfiguration {
    if (availableSlots < 1) {
      throw new IllegalArgumentException("Available slots must be positive");
    }

    sourceNamespaces = Map.copyOf(sourceNamespaces);
    if (keepAliveTime == null) {
      keepAliveTime = DEFAULT_KEEPALIVE_TIME;
    }
    if (keepAliveTimeout == null) {
      keepAliveTimeout = DEFAULT_KEEPALIVE_TIMEOUT;
    }
    if (uploadReadinessTimeout == null) {
      uploadReadinessTimeout = DEFAULT_UPLOAD_READINESS_TIMEOUT;
    }
    if (uploadAcknowledgementTimeout == null) {
      uploadAcknowledgementTimeout = DEFAULT_UPLOAD_ACKNOWLEDGEMENT_TIMEOUT;
    }
  }
}
