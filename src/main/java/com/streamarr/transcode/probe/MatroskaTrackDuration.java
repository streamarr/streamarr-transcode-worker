package com.streamarr.transcode.probe;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;

/**
 * A Matroska track's DURATION tag, which counts only when FFmpeg wrote it from the file's packets.
 * FFmpeg names itself in the segment's MuxingApp whatever WritingApp it is given. From FFmpeg 3.2,
 * whose libavformat is 57.56, it copies no tag named DURATION, and it writes a copied key with a
 * language suffix, such as DURATION-und, as a DURATION tag with a TagLanguage. Its own DURATION has
 * none: its trailer writes it from the packets, after the copied tags, into space that the header
 * reserved. A run stopped before the trailer wrote it keeps the copied tag last, and an FFmpeg
 * before 5.1 leaves its own without a TagString, which removes the tag. mkvpropedit drops each
 * DURATION, adds its own without a TagLanguage, and keeps the MuxingApp. mkvmerge's statistics do
 * not count: a remux with {@code --disable-track-statistics-tags} keeps the source's statistics
 * while {@code --sync} can move the packets.
 */
@Slf4j
final class MatroskaTrackDuration {

  private static final long MUXING_APP = 0x4D80L;
  private static final long TAG = 0x7373L;
  private static final long SIMPLE_TAG = 0x67C8L;
  private static final long TAG_NAME = 0x45A3L;
  private static final long TAG_LANGUAGE = 0x447AL;
  private static final Pattern LIBAVFORMAT = Pattern.compile("Lavf(\\d{1,4})\\.(\\d{1,4})\\..*");
  private static final int FIRST_MAJOR_VERSION = 57;
  private static final int FIRST_MINOR_VERSION = 56;

  private MatroskaTrackDuration() {}

  /** The track's DURATION in seconds, without a language suffix; empty when not positive. */
  static Optional<BigDecimal> of(JsonNode stream) {
    return StreamTags.unsuffixedMatroskaDuration(stream).filter(seconds -> seconds.signum() > 0);
  }

  /**
   * Whether the source's tracks' DURATION tags count: FFmpeg 3.2 or later muxed the source, and the
   * last DURATION tag of each Tag has no TagLanguage, so that the DURATION ffprobe reports for each
   * track, the last one it reads, is not a copied one. False when the source's Info or Tags cannot
   * be read.
   */
  static boolean areWrittenByFfmpeg(Path source) {
    try (var channel = FileChannel.open(source, StandardOpenOption.READ)) {
      var elements = MatroskaFile.TopLevel.infoAndTags(channel);
      return elements
              .info()
              .flatMap(info -> MatroskaFile.string(info, MUXING_APP))
              .filter(MatroskaTrackDuration::isFfmpegThatNeverCopiesDuration)
              .isPresent()
          && elements.tags().filter(MatroskaTrackDuration::isEachLastDurationUncopied).isPresent();
    } catch (IOException
        | ContainerIndexException
        | BufferUnderflowException
        | IllegalArgumentException failure) {
      log.warn("The chunk could not read the Matroska MuxingApp and Tags of {}", source, failure);
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

  /**
   * Fails the read at a value where FFmpeg's parse of the Tags would stop, which would leave it an
   * earlier DURATION tag than the last one in the file.
   */
  private static boolean isEachLastDurationUncopied(ByteBuffer tags) {
    MatroskaFile.requireReadable(tags, MatroskaSyntax.TAGS);
    var isUncopied = new AtomicBoolean(true);
    MatroskaFile.eachChild(
        tags,
        TAG,
        tag -> {
          if (!isLastDurationUncopied(tag)) {
            isUncopied.set(false);
          }
        });
    return isUncopied.get();
  }

  /**
   * Whether the Tag's last SimpleTag named DURATION, ignoring case as FFmpeg's demuxer matches tag
   * names, has no TagLanguage; true when the Tag has none. False when a SimpleTag nests another,
   * which FFmpeg never writes and {@link MatroskaSyntax#TAGS} leaves unchecked.
   */
  private static boolean isLastDurationUncopied(ByteBuffer tag) {
    var lastDuration = new AtomicReference<ByteBuffer>();
    var nests = new AtomicBoolean();
    MatroskaFile.eachChild(
        tag,
        SIMPLE_TAG,
        simpleTag -> {
          if (MatroskaFile.child(simpleTag, SIMPLE_TAG).isPresent()) {
            nests.set(true);
          }

          if (MatroskaFile.string(simpleTag, TAG_NAME)
              .filter("DURATION"::equalsIgnoreCase)
              .isPresent()) {
            lastDuration.set(simpleTag);
          }
        });
    return !nests.get()
        && Optional.ofNullable(lastDuration.get())
            .flatMap(duration -> MatroskaFile.child(duration, TAG_LANGUAGE))
            .isEmpty();
  }
}
