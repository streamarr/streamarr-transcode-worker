package com.streamarr.transcode.probe;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.BufferUnderflowException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * A Matroska track's DURATION tag, which counts only when FFmpeg wrote it from the file's packets.
 * FFmpeg names itself in the segment's MuxingApp whatever WritingApp it is given. When its output
 * is seekable, which the Cues it then writes show, it writes each track's DURATION after the tags
 * it copies from its input, so ffprobe reports its own value over a copied one. From FFmpeg 3.2,
 * whose libavformat is 57.56, it also copies no tag named DURATION. mkvpropedit rewrites the tag
 * from the file's packets and keeps the MuxingApp. mkvmerge's statistics do not count: a remux with
 * {@code --disable-track-statistics-tags} keeps the source's statistics while {@code --sync} can
 * move the packets.
 */
@Slf4j
final class MatroskaTrackDuration {

  private static final long MUXING_APP = 0x4D80L;
  private static final Pattern LIBAVFORMAT = Pattern.compile("Lavf(\\d{1,4})\\.(\\d{1,4})\\..*");
  private static final int FIRST_MAJOR_VERSION = 57;
  private static final int FIRST_MINOR_VERSION = 56;

  private MatroskaTrackDuration() {}

  /** The track's DURATION in seconds, without a language suffix; empty when not positive. */
  static Optional<BigDecimal> of(JsonNode stream) {
    return StreamTags.unsuffixedMatroskaDuration(stream).filter(seconds -> seconds.signum() > 0);
  }

  /**
   * Whether FFmpeg 3.2 or later wrote the Matroska source to a seekable output, so that its tracks'
   * DURATION tags count; false when the source's Info or Cues cannot be read.
   */
  static boolean isWrittenByFfmpeg(Path source) {
    try (var channel = FileChannel.open(source, StandardOpenOption.READ)) {
      var topLevel = MatroskaFile.TopLevel.read(channel);
      return topLevel.cues().isPresent()
          && topLevel
              .info()
              .flatMap(info -> MatroskaFile.string(info, MUXING_APP))
              .filter(MatroskaTrackDuration::isFfmpegThatNeverCopiesDuration)
              .isPresent();
    } catch (IOException
        | ContainerIndexException
        | BufferUnderflowException
        | IllegalArgumentException failure) {
      log.warn("The chunk could not read the Matroska MuxingApp and Cues of {}", source, failure);
      return false;
    }
  }

  private static boolean isFfmpegThatNeverCopiesDuration(String muxingApp) {
    var version = LIBAVFORMAT.matcher(muxingApp);
    if (!version.matches()) {
      return false;
    }

    var major = Integer.parseInt(version.group(1));
    var minor = Integer.parseInt(version.group(2));
    return major > FIRST_MAJOR_VERSION
        || (major == FIRST_MAJOR_VERSION && minor >= FIRST_MINOR_VERSION);
  }
}
