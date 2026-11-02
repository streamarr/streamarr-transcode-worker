package com.streamarr.transcode.engine;

import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;

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
    Optional<SpeakerLayout> channelLayout,
    OptionalInt outputSampleRate,
    OptionalInt outputBitDepth) {

  public AudioDecision {
    if (channelLayout == null) {
      channelLayout = Optional.empty();
    }

    if (outputSampleRate == null) {
      outputSampleRate = OptionalInt.empty();
    }

    if (outputBitDepth == null) {
      outputBitDepth = OptionalInt.empty();
    }
  }
}
