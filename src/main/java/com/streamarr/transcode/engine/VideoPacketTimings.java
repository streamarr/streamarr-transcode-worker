package com.streamarr.transcode.engine;

import java.io.IOException;

/** The timing of each video packet of the copy of the source, in the order the copy holds them. */
@FunctionalInterface
interface VideoPacketTimings {

  /**
   * The next packet's timing, once the FFmpeg that copies the source has written it.
   *
   * @throws FragmentedMp4Exception when that FFmpeg's timing ended before the packet's, or cannot
   *     be read, for the named reason
   */
  VideoPacketTiming next() throws IOException;
}
