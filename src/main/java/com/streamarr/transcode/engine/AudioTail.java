package com.streamarr.transcode.engine;

import lombok.NonNull;

/**
 * Where an audio sample ends, and where one more sample of the same duration would end after it.
 */
record AudioTail(@NonNull MediaTime end, @NonNull MediaTime endPlusFrame) {

  static AudioTail of(@NonNull Sample sample, long timescale) {
    var timeBase = new TimeBase(1, timescale);
    var end = Math.addExact(sample.presentationTime(), sample.duration());
    return new AudioTail(
        new MediaTime(end, timeBase),
        new MediaTime(Math.addExact(end, sample.duration()), timeBase));
  }

  boolean coversToWithinOneFrame(MediaTime videoEnd, MediaTime beforeLastVideoFrame) {
    return end.compareTo(beforeLastVideoFrame) >= 0 || endPlusFrame.compareTo(videoEnd) >= 0;
  }
}
