package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.FfprobeJson.text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adds the extradata of each video and audio stream to ffprobe's stream descriptions. One ffprobe
 * run for each stream type prints it, because -show_data for every stream also prints each Matroska
 * attachment's whole file: a large font attachment makes the output too long to parse.
 */
@RequiredArgsConstructor
final class StreamExtradataProbe {

  // FFmpeg's stream specifiers: V selects the video streams that are not attached pictures.
  private static final Map<String, String> SELECTORS = Map.of("video", "V", "audio", "a");

  @NonNull private final ObjectMapper objectMapper;
  @NonNull private final ContainerIndexProbe.FfprobeRunner ffprobe;

  void addTo(JsonNode ffprobeOutput, Path source) throws ExecutionException, InterruptedException {
    var streams = new HashMap<Integer, ObjectNode>();
    for (var stream : ffprobeOutput.path("streams")) {
      if (stream instanceof ObjectNode description && stream.path("index").isInt()) {
        streams.put(stream.path("index").asInt(), description);
      }
    }

    for (var type : List.of("video", "audio")) {
      var present =
          streams.values().stream()
              .anyMatch(stream -> text(stream, "codec_type").filter(type::equals).isPresent());
      if (present) {
        addExtradata(streams, SELECTORS.get(type), source);
      }
    }
  }

  private void addExtradata(Map<Integer, ObjectNode> streams, String selector, Path source)
      throws ExecutionException, InterruptedException {
    var arguments = new ArrayList<>(List.of("-v", "quiet", "-print_format", "json"));
    arguments.addAll(FfprobeExecutor.READ_LIMITS);
    arguments.addAll(
        List.of(
            "-select_streams",
            selector,
            "-show_entries",
            "stream=index,extradata",
            "-show_data",
            source.toString()));
    var output = ffprobe.run(arguments);
    if (output.exitCode() != 0) {
      throw new StreamExtradataException(
          "ffprobe's extradata read exited with code " + output.exitCode());
    }

    for (var stream : objectMapper.readTree(output.stdout()).path("streams")) {
      var description = Optional.ofNullable(streams.get(stream.path("index").asInt(-1)));
      text(stream, "extradata")
          .ifPresent(extradata -> description.ifPresent(node -> node.put("extradata", extradata)));
    }
  }

  /** An extradata read whose ffprobe run failed. */
  static final class StreamExtradataException extends RuntimeException {

    StreamExtradataException(String message) {
      super(message);
    }
  }
}
