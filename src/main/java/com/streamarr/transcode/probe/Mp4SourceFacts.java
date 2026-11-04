package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The source facts of probe version 7 that only an MP4 or MOV file stores (ADR 0032): the sample
 * entry of each video stream and the major brand of the ftyp box. Other demuxers print a codec tag
 * that names no sample entry, such as an MPEG-TS stream type or an AVI FourCC, and Matroska keeps a
 * remuxed MP4 file's major_brand as an ordinary tag.
 */
@Slf4j
final class Mp4SourceFacts {

  /** The key under which the format's description holds the ftyp box's major brand. */
  static final String MAJOR_BRAND_KEY = "mp4_major_brand";

  // ffprobe prints each codec tag byte outside this set as its value in brackets, so a stream
  // without a sample entry, such as cover art, prints [0][0][0][0].
  private static final Pattern SAMPLE_ENTRY = Pattern.compile("[0-9A-Za-z. _-]{4}");

  private static final Pattern MAJOR_BRAND = Pattern.compile("[\\x20-\\x7E]{4}");

  private Mp4SourceFacts() {}

  /**
   * Adds the major brand that the file's ftyp box names. FFmpeg's major_brand tag can come from the
   * file's metadata instead, which can name another brand. A file whose top-level boxes are
   * malformed reports no major brand, and a failed read fails the probe attempt, so that a retry
   * reads the brand.
   */
  static void addMajorBrand(JsonNode ffprobeOutput, Path source) throws IOException {
    if (!(ffprobeOutput.path("format") instanceof ObjectNode format)
        || !ProbedSource.MP4_FORMAT.equals(format.path("format_name").asString(""))) {
      return;
    }

    try (var channel = FileChannel.open(source, StandardOpenOption.READ)) {
      Mp4MovieBox.majorBrand(channel)
          .filter(MAJOR_BRAND.asMatchPredicate())
          .ifPresent(brand -> format.put(MAJOR_BRAND_KEY, brand));
    } catch (ContainerIndexException | BufferUnderflowException failure) {
      log.warn("The probe found no usable MP4 major brand in {}", source, failure);
    }
  }

  static Optional<String> sampleEntry(JsonNode videoStream, String formatName) {
    if (!ProbedSource.MP4_FORMAT.equals(formatName)) {
      return Optional.empty();
    }

    return text(videoStream, "codec_tag_string").filter(SAMPLE_ENTRY.asMatchPredicate());
  }

  static Optional<String> majorBrand(JsonNode format) {
    return text(format, MAJOR_BRAND_KEY);
  }
}
