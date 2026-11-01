package com.streamarr.transcode.probe;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adds the WritingApp of a Matroska segment's Info, the program that last wrote the file, to the
 * format's ffprobe description under {@link #KEY}, which ffprobe does not report. The probe reads
 * it only when a stream carries Matroska statistics tags, which {@link MatroskaStatistics} trusts
 * by it, and adds nothing when it cannot read it.
 */
@Slf4j
final class MatroskaWritingApp {

  /** The key under which the format's description holds the segment's WritingApp. */
  static final String KEY = "matroska_writing_app";

  private static final long WRITING_APP = 0x5741L;

  private MatroskaWritingApp() {}

  static void addTo(JsonNode ffprobeOutput, Path source) {
    if (!(ffprobeOutput.path("format") instanceof ObjectNode format)
        || !ProbedSource.MATROSKA_FORMAT.equals(format.path("format_name").asString(""))
        || !hasStatistics(ffprobeOutput.path("streams"))) {
      return;
    }

    read(source).ifPresent(writingApp -> format.put(KEY, writingApp));
  }

  private static boolean hasStatistics(JsonNode streams) {
    for (var stream : streams) {
      if (StreamTags.matroskaStatistic(stream, MatroskaStatistics.WRITING_APP).isPresent()) {
        return true;
      }
    }

    return false;
  }

  private static Optional<String> read(Path source) {
    try (var channel = FileChannel.open(source, StandardOpenOption.READ)) {
      return MatroskaFile.TopLevel.info(channel)
          .flatMap(info -> MatroskaFile.string(info, WRITING_APP));
    } catch (IOException
        | ContainerIndexException
        | BufferUnderflowException
        | IllegalArgumentException failure) {
      log.warn("The probe could not read the Matroska WritingApp of {}", source, failure);
      return Optional.empty();
    }
  }
}
