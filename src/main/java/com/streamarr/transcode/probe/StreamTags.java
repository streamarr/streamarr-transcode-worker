package com.streamarr.transcode.probe;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/** A stream's tags, whose keys FFmpeg's metadata dictionary compares without regard to case. */
final class StreamTags {

  private StreamTags() {}

  /** The value of the first tag whose key equals {@code key} without regard to case. */
  static Optional<String> tag(JsonNode stream, String key) {
    return tags(stream)
        .filter(tag -> tag.getKey().equalsIgnoreCase(key))
        .map(tag -> tag.getValue().asString())
        .findFirst();
  }

  /**
   * The value of a Matroska statistics tag, such as DURATION or NUMBER_OF_FRAMES. A muxer may add a
   * language suffix to the key, as in DURATION-eng.
   */
  static Optional<String> matroskaStatistic(JsonNode stream, String key) {
    var prefix = key.toUpperCase(Locale.ROOT) + "-";
    return tags(stream)
        .filter(
            tag -> {
              var name = tag.getKey().toUpperCase(Locale.ROOT);
              return name.equals(key) || name.startsWith(prefix);
            })
        .map(tag -> tag.getValue().asString())
        .findFirst();
  }

  private static Stream<Map.Entry<String, JsonNode>> tags(JsonNode stream) {
    return stream.path("tags").properties().stream().filter(tag -> tag.getValue().isString());
  }
}
