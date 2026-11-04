package com.streamarr.transcode.engine;

import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * The job's audio. An encode that names {@code channelLayout} writes that layout; without it, the
 * encode writes FFmpeg's default layout for {@code channels}. Such an encode writes {@code
 * outputSampleRate} and, for FLAC or ALAC, {@code outputBitDepth}; each absent value follows the
 * source.
 */
@Builder
public record AudioDecision(
    AudioMode mode,
    String codec,
    int channels,
    long bitrate,
    @NonNull Optional<SpeakerLayout> channelLayout,
    @NonNull OptionalInt outputSampleRate,
    @NonNull OptionalInt outputBitDepth) {

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class AudioDecisionBuilder {
    private Optional<SpeakerLayout> channelLayout = Optional.empty();
    private OptionalInt outputSampleRate = OptionalInt.empty();
    private OptionalInt outputBitDepth = OptionalInt.empty();
  }
}
