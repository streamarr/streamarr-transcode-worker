package com.streamarr.transcode.engine;

import java.nio.file.Path;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder
public record TranscodeRequest(
    @NonNull UUID sessionId,
    UUID attemptId,
    @NonNull Path sourcePath,
    int targetSegmentDuration,
    double framerate,
    @NonNull TranscodeDecision transcodeDecision,
    EncoderPreset encoderPreset,
    int width,
    int height,
    long bitrate,
    String variantLabel,
    int startSequenceNumber,
    int mediaSegmentCount) {

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

  private static boolean encodesVideo(TranscodeDecision decision) {
    var mode = decision.transcodeMode();
    return mode != null && mode.encodesVideo();
  }
}
