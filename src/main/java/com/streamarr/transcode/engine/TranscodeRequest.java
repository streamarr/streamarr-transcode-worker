package com.streamarr.transcode.engine;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.Builder;
import lombok.NonNull;

@Builder(toBuilder = true)
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
    @NonNull List<Long> segmentStartSlots,
    @NonNull Optional<Duration> seekPosition,
    @NonNull Optional<SourceAudio> sourceAudio,
    @NonNull Optional<Duration> advertisedEnd) {

  public TranscodeRequest {
    segmentStartSlots = List.copyOf(segmentStartSlots);
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
        .segmentStartSlots(List.of())
        .seekPosition(Optional.empty())
        .outputFrameRate(Optional.empty())
        .sourceAudio(Optional.empty())
        .advertisedEnd(Optional.empty());
  }

  /**
   * The media segments' intervals: the job's segment list, whose last segment ends at the
   * advertised end when the job carries one, or the plain grid when it has no list.
   */
  SegmentIntervals segmentIntervals() {
    var intervals = SegmentIntervals.onSegmentList(targetSegmentDuration, segmentStartSlots);
    return advertisedEnd.map(intervals::withLastListedSegmentEndingAt).orElse(intervals);
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
