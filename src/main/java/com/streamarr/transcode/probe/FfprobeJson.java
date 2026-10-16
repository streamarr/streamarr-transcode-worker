package com.streamarr.transcode.probe;

import java.util.Optional;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;

/** Reads the fields of ffprobe's JSON output, which prints most numbers as strings. */
final class FfprobeJson {

  private FfprobeJson() {}

  /** The field as text, or empty when the field is missing or null. */
  static Optional<String> text(JsonNode parent, String name) {
    return Optional.ofNullable(parent.get(name))
        .filter(value -> !value.isNull())
        .map(JsonNode::asString);
  }

  /** The field parsed as a number, or empty when the field is missing, null or not a number. */
  static <T> Optional<T> number(JsonNode parent, String name, Function<String, T> parser) {
    try {
      return text(parent, name).map(parser);
    } catch (NumberFormatException _) {
      return Optional.empty();
    }
  }
}
