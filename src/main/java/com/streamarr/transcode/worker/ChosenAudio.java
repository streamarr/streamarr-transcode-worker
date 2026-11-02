package com.streamarr.transcode.worker;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.engine.SourceAudio;
import java.util.Optional;
import java.util.OptionalInt;

/** What the worker read of the audio stream that a job attempt encodes, before the attempt. */
sealed interface ChosenAudio {

  /** The job copies its audio, carries none, or encodes it without naming a layout. */
  record NotRead() implements ChosenAudio {}

  record Read(SourceAudio facts) implements ChosenAudio {}

  /** The read failed, so the attempt cannot choose how to encode the audio. */
  record Unreadable(Exception failure) implements ChosenAudio {}

  static SourceAudio factsOf(ProbeStreamInfo stream) {
    return SourceAudio.builder()
        .codec(stream.getCodec())
        .profile(
            stream.hasAudioProfile() ? Optional.of(stream.getAudioProfile()) : Optional.empty())
        .channels(stream.getChannels())
        .channelLayout(
            stream.hasChannelLayout() ? Optional.of(stream.getChannelLayout()) : Optional.empty())
        .sampleFormat(
            stream.hasSampleFormat() ? Optional.of(stream.getSampleFormat()) : Optional.empty())
        .bitDepth(stream.hasBitDepth() ? OptionalInt.of(stream.getBitDepth()) : OptionalInt.empty())
        .sampleRate(
            stream.hasSampleRateHz()
                ? OptionalInt.of(stream.getSampleRateHz())
                : OptionalInt.empty())
        .build();
  }
}
