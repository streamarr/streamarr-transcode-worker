package com.streamarr.transcode.engine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record TranscodeRequest(
    @NonNull UUID sessionId,
    UUID attemptId,
    @NonNull Path sourcePath,
    int targetSegmentDuration,
    @NonNull Optional<OutputFrameRate> outputFrameRate,
    @NonNull TranscodeDecision transcodeDecision,
    EncoderPreset encoderPreset,
    int width,
    int height,
    long bitrate,
    String variantLabel,
    int startSequenceNumber,
    int mediaSegmentCount,
    @NonNull Optional<Duration> seekPosition) {

  public TranscodeRequest {
    if (encoderPreset == null && encodesVideo(transcodeDecision)) {
      throw new IllegalArgumentException("A video encode requires an encoder preset");
    }

    if (variantLabel == null) {
      variantLabel = "default";
    }

    if (attemptId == null) {
      attemptId = UUID.randomUUID();
    }
  }

  public static TranscodeRequestBuilder builder() {
    return new TranscodeRequestBuilder()
        .seekPosition(Optional.empty())
        .outputFrameRate(Optional.empty());
  }

  private static boolean encodesVideo(TranscodeDecision decision) {
    var mode = decision.transcodeMode();
    return mode != null && mode.encodesVideo();
  }
}
