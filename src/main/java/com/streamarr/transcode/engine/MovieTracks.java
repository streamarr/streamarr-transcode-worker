package com.streamarr.transcode.engine;

import java.util.Optional;
import lombok.NonNull;

/** The video and sound tracks an initialization segment declares. */
record MovieTracks(@NonNull Optional<VideoTrack> video, @NonNull Optional<AudioTrack> audio) {}
