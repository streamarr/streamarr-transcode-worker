package com.streamarr.transcode.engine;

import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * The job's audio. The job copies or encodes the source stream at {@code streamIndex}, or the
 * source's first audio stream when it names none. An encode that names {@code channelLayout} writes
 * that layout; without it, the encode writes FFmpeg's default layout for {@code channels}. Such an
 * encode writes {@code outputSampleRate} and, for FLAC or ALAC, {@code outputBitDepth}; each absent
 * value follows the source.
 */
@Builder
public record AudioDecision(
    AudioMode mode,
    String codec,
    int channels,
    long bitrate,
    @NonNull OptionalInt streamIndex,
    @NonNull Optional<SpeakerLayout> channelLayout,
    @NonNull OptionalInt outputSampleRate,
    @NonNull OptionalInt outputBitDepth) {

  /**
   * FFmpeg's specifier of the source stream that the job copies or encodes: its index, or {@code
   * a:0}, the first audio stream, when the job names none.
   */
  public String sourceStreamSpecifier() {
    if (streamIndex.isPresent()) {
      return String.valueOf(streamIndex.getAsInt());
    }

    return "a:0";
  }

  // Lombok builder defaults — its generated setters assign them
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class AudioDecisionBuilder {
    private OptionalInt streamIndex = OptionalInt.empty();
    private Optional<SpeakerLayout> channelLayout = Optional.empty();
    private OptionalInt outputSampleRate = OptionalInt.empty();
    private OptionalInt outputBitDepth = OptionalInt.empty();
  }
}
