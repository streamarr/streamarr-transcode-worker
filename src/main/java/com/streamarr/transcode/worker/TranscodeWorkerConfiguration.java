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
    @NonNull BackgroundProbeCapacity backgroundProbeCapacity,
    @NonNull Map<UUID, Path> sourceNamespaces,
    Duration uploadDeadline,
    Duration sessionAcceptanceTimeout,
    Duration audioStreamReadTimeout,
    Duration hardwareEncoderRecheckInterval) {

  static final Duration DEFAULT_UPLOAD_DEADLINE = Duration.ofSeconds(60);
  // Covers a server cold start, database migrations included, when both start together.
  static final Duration DEFAULT_SESSION_ACCEPTANCE_TIMEOUT = Duration.ofMinutes(5);
  // ffprobe reads a healthy source's first audio stream within a second.
  static final Duration DEFAULT_AUDIO_STREAM_READ_TIMEOUT = Duration.ofSeconds(30);
  // A withdrawn hardware encoder's test encode takes under a second.
  static final Duration DEFAULT_HARDWARE_ENCODER_RECHECK_INTERVAL = Duration.ofMinutes(1);

  public TranscodeWorkerConfiguration {
    if (availableSlots < 1) {
      throw new IllegalArgumentException("Available slots must be positive");
    }

    sourceNamespaces = Map.copyOf(sourceNamespaces);
    if (uploadDeadline == null) {
      uploadDeadline = DEFAULT_UPLOAD_DEADLINE;
    }

    if (sessionAcceptanceTimeout == null) {
      sessionAcceptanceTimeout = DEFAULT_SESSION_ACCEPTANCE_TIMEOUT;
    }

    if (audioStreamReadTimeout == null) {
      audioStreamReadTimeout = DEFAULT_AUDIO_STREAM_READ_TIMEOUT;
    }

    if (hardwareEncoderRecheckInterval == null) {
      hardwareEncoderRecheckInterval = DEFAULT_HARDWARE_ENCODER_RECHECK_INTERVAL;
    }
  }
}
