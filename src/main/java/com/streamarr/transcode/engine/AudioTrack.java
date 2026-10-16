package com.streamarr.transcode.engine;

import java.util.Optional;

/** The sound track an initialization segment declares: its track id and timescale. */
record AudioTrack(long trackId, long timescale) {

  private static final String AUDIO_HANDLER = "soun";

  /** Finds the first sound track of a {@code moov}; empty when it declares none. */
  static Optional<AudioTrack> of(BoxView moov) {
    return moov.children("trak").stream()
        .map(TrackBox::new)
        .filter(trak -> trak.handler().equals(AUDIO_HANDLER))
        .findFirst()
        .map(trak -> new AudioTrack(trak.trackId(), trak.timescale()));
  }
}
