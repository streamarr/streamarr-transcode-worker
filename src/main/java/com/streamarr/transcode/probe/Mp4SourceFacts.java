package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import java.util.Optional;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * The source facts of probe version 7 that only an MP4 or MOV file stores (ADR 0032): the sample
 * entry of each video stream and the major brand of the ftyp box. Other demuxers print a codec tag
 * that names no sample entry, such as an MPEG-TS stream type or an AVI FourCC, and Matroska keeps a
 * remuxed MP4 file's major_brand as an ordinary tag.
 */
final class Mp4SourceFacts {

  // ffprobe prints each codec tag byte outside this set as its value in brackets, so a stream
  // without a sample entry, such as cover art, prints [0][0][0][0].
  private static final Pattern SAMPLE_ENTRY = Pattern.compile("[0-9A-Za-z. _-]{4}");

  // FFmpeg stores the brand as a C string, so a zero byte cuts it short.
  private static final Pattern MAJOR_BRAND = Pattern.compile("[\\x20-\\x7E]{4}");

  private Mp4SourceFacts() {}

  static Optional<String> sampleEntry(JsonNode videoStream, String formatName) {
    if (!ProbedSource.MP4_FORMAT.equals(formatName)) {
      return Optional.empty();
    }

    return text(videoStream, "codec_tag_string").filter(SAMPLE_ENTRY.asMatchPredicate());
  }

  static Optional<String> majorBrand(JsonNode format) {
    if (!ProbedSource.MP4_FORMAT.equals(text(format, "format_name").orElse(""))) {
      return Optional.empty();
    }

    return text(format.path("tags"), "major_brand").filter(MAJOR_BRAND.asMatchPredicate());
  }
}
