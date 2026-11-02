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
    @NonNull Optional<Duration> seekPosition,
    @NonNull Optional<SourceAudio> sourceAudio,
    @NonNull Optional<Duration> advertisedEnd) {

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

    var audio = transcodeDecision.audioDecision();
    if (audio != null && audio.channelLayout().isPresent() && sourceAudio.isEmpty()) {
      throw new IllegalArgumentException(
          "An audio encode in a named layout requires the chosen audio stream's facts");
    }

    if (advertisedEnd.isEmpty() && holdsOrPads(transcodeDecision)) {
      throw new IllegalArgumentException(
          "Holding the last frame or padding with silence requires the advertised end");
    }
  }

  public static TranscodeRequestBuilder builder() {
    return new TranscodeRequestBuilder()
        .seekPosition(Optional.empty())
        .outputFrameRate(Optional.empty())
        .sourceAudio(Optional.empty())
        .advertisedEnd(Optional.empty());
  }

  private static boolean holdsOrPads(TranscodeDecision decision) {
    var holds =
        decision.videoDecision() instanceof VideoDecision.Encode encode
            && encode.holdsLastFrameToAdvertisedEnd();
    var audio = decision.audioDecision();
    return holds || (audio != null && audio.padsWithSilenceToAdvertisedEnd());
  }

  private static boolean encodesVideo(TranscodeDecision decision) {
    var mode = decision.transcodeMode();
    return mode != null && mode.encodesVideo();
  }
}
