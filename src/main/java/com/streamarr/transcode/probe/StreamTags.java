package com.streamarr.transcode.probe;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/** A stream's tags, whose keys FFmpeg's metadata dictionary compares without regard to case. */
final class StreamTags {

  private static final Pattern MATROSKA_DURATION =
      Pattern.compile("(\\d+):([0-5]\\d):([0-5]\\d(?:\\.\\d+)?)");

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

  /** The DURATION tag, with or without a language suffix such as DURATION-eng, in seconds. */
  static Optional<BigDecimal> matroskaDuration(JsonNode stream) {
    return matroskaStatistic(stream, "DURATION")
        .map(MATROSKA_DURATION::matcher)
        .filter(Matcher::matches)
        .map(
            match ->
                new BigDecimal(match.group(3))
                    .add(BigDecimal.valueOf(Long.parseLong(match.group(2)) * 60))
                    .add(BigDecimal.valueOf(Long.parseLong(match.group(1)) * 3600)));
  }

  private static Stream<Map.Entry<String, JsonNode>> tags(JsonNode stream) {
    return stream.path("tags").properties().stream().filter(tag -> tag.getValue().isString());
  }
}
