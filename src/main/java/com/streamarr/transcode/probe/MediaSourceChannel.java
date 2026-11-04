package com.streamarr.transcode.probe;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import lombok.NonNull;

/**
 * Opens a media source for the worker's own reads. The source's final component is opened without
 * following a soft link, so a link swapped into the media root after the worker's path check fails
 * to open instead of leading the worker to a file outside the root. The JDK opens a path by name,
 * so a link in a directory above the source is still followed; FFmpeg's and ffprobe's reads run
 * under Landlock and reach no such file either way.
 */
public final class MediaSourceChannel {

  private MediaSourceChannel() {}

  public static FileChannel openForReading(@NonNull Path source) throws IOException {
    return FileChannel.open(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
  }
}
