package com.streamarr.transcode.engine;

import java.util.Optional;
import java.util.OptionalInt;
import lombok.Builder;
import lombok.NonNull;

/**
 * What the worker read of the chosen audio stream before an attempt that encodes it in a named
 * layout: ffprobe's codec name and profile, channel count, layout text, sample format, bit depth
 * and sample rate, and, for a stereo or mono downmix of AC-3 or E-AC-3, the Lo/Ro levels that the
 * stream's first frame signals.
 */
@Builder
public record SourceAudio(
    @NonNull String codec,
    @NonNull Optional<String> profile,
    int channels,
    @NonNull Optional<String> channelLayout,
    @NonNull Optional<String> sampleFormat,
    @NonNull OptionalInt bitDepth,
    @NonNull OptionalInt sampleRate,
    @NonNull Optional<LoRoLevels> loRoLevels) {

  public static SourceAudioBuilder builder() {
    return new SourceAudioBuilder()
        .profile(Optional.empty())
        .channelLayout(Optional.empty())
        .sampleFormat(Optional.empty())
        .bitDepth(OptionalInt.empty())
        .sampleRate(OptionalInt.empty())
        .loRoLevels(Optional.empty());
  }

  /**
   * The layout that FFmpeg decodes the stream in: the probed layout, or FFmpeg's default for the
   * channel count when the stream states none. Empty when FFmpeg names a channel of the probed
   * layout that the worker does not know.
   */
  public Optional<SpeakerLayout> speakerLayout() {
    if (channelLayout.isPresent()) {
      return SpeakerLayout.parse(channelLayout.orElseThrow());
    }

    return SpeakerLayout.defaultFor(channels);
  }

  /**
   * The centre and surround levels of an AC-3 or E-AC-3 stream's Lo/Ro downmix, as FFmpeg's decoder
   * exports them on each frame.
   */
  public record LoRoLevels(double center, double surround) {}
}
