package com.streamarr.transcode.engine;

import java.util.Optional;
import lombok.NonNull;

/** The codec configuration that an initialization segment declares for one track. */
sealed interface TrackCodec {

  /** The RFC 6381 codec string that the track's sample entry and its records declare. */
  String codecString();

  /**
   * A video track.
   *
   * @param codecString the sample entry alone when the worker does not read its record
   * @param pictureFormat empty when the worker does not read the sample entry's record, or when the
   *     record does not state the format
   */
  record Video(@NonNull String codecString, @NonNull Optional<PictureFormat> pictureFormat)
      implements TrackCodec {}

  /**
   * An audio track.
   *
   * @param codecString the sample entry alone unless it is {@code mp4a}
   */
  record Audio(@NonNull String codecString) implements TrackCodec {}
}
