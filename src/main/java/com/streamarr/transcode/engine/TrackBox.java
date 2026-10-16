package com.streamarr.transcode.engine;

import lombok.NonNull;

/** One {@code trak} of a {@code moov}, whose fields are read only when asked for. */
record TrackBox(@NonNull BoxView trak) {

  /** The handler type of the track's media, such as {@code vide} or {@code soun}. */
  String handler() {
    return trak.requiredChild("mdia").requiredChild("hdlr").fields().skip(8).fourcc();
  }

  long trackId() {
    var fields = trak.requiredChild("tkhd").fields();
    var version = fields.u8();
    return fields.skip(3).skip(creationAndModificationTimeBytes(version)).u32();
  }

  /**
   * @throws FragmentedMp4Exception when the media header declares a timescale of zero
   */
  long timescale() {
    var fields = trak.requiredChild("mdia").requiredChild("mdhd").fields();
    var version = fields.u8();
    var timescale = fields.skip(3).skip(creationAndModificationTimeBytes(version)).u32();
    if (timescale == 0) {
      throw FragmentedMp4Exception.malformed("mdhd declares a timescale of zero");
    }

    return timescale;
  }

  private static int creationAndModificationTimeBytes(int version) {
    if (version == 1) {
      return 16;
    }

    return 8;
  }
}
