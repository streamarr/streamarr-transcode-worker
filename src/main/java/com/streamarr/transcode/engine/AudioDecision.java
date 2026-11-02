package com.streamarr.transcode.engine;

import java.util.Optional;
import lombok.Builder;

/**
 * The job's audio. An encode that names {@code channelLayout} writes that layout; without it, the
 * encode writes FFmpeg's default layout for {@code channels}.
 */
@Builder
public record AudioDecision(
    AudioMode mode,
    String codec,
    int channels,
    long bitrate,
    Optional<SpeakerLayout> channelLayout) {

  public AudioDecision {
    if (channelLayout == null) {
      channelLayout = Optional.empty();
    }
  }
}
