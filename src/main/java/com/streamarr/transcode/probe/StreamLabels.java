package com.streamarr.transcode.probe;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.google.protobuf.CodedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;

/**
 * The title and the handler name of an audio or subtitle stream, which name the stream in a menu.
 * The title is the first title or name tag that is not blank. Each value takes at most 512 bytes of
 * UTF-8; the worker leaves a longer value out and never shortens it.
 */
record StreamLabels(Optional<String> title, Optional<String> handlerName) {

  /** The titles and handler names of one result take at most this many bytes when encoded. */
  static final int MAXIMUM_ENCODED_BYTES = 16_384;

  private static final int MAXIMUM_VALUE_BYTES = 512;

  static StreamLabels of(JsonNode stream) {
    var title =
        Stream.of("title", "name")
            .map(key -> StreamTags.tag(stream, key))
            .flatMap(Optional::stream)
            .filter(value -> !value.isBlank())
            .findFirst()
            .filter(StreamLabels::fits);
    var handlerName = StreamTags.tag(stream, "handler_name").filter(StreamLabels::fits);
    return new StreamLabels(title, handlerName);
  }

  /** The bytes that the title and the handler name add to the stream's protobuf encoding. */
  int encodedBytes() {
    return title
            .map(
                value ->
                    CodedOutputStream.computeStringSize(ProbeStreamInfo.TITLE_FIELD_NUMBER, value))
            .orElse(0)
        + handlerName
            .map(
                value ->
                    CodedOutputStream.computeStringSize(
                        ProbeStreamInfo.HANDLER_NAME_FIELD_NUMBER, value))
            .orElse(0);
  }

  void addTo(ProbeStreamInfo.Builder stream) {
    title.ifPresent(stream::setTitle);
    handlerName.ifPresent(stream::setHandlerName);
  }

  private static boolean fits(String value) {
    return value.getBytes(StandardCharsets.UTF_8).length <= MAXIMUM_VALUE_BYTES;
  }
}
