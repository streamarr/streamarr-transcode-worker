package com.streamarr.transcode.probe;

import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The side data of the first frames that the probe read from the chosen video stream, as {@link
 * FirstFramesProbe} adds them to the stream's description. At least one frame: a stream without
 * frames has no first frames. Cut short when the kept read holds fewer than 3 frames, so that
 * frames the probe did not read can carry what these frames do not.
 */
record FirstFrames(List<JsonNode> frames, boolean cutShort) {

  static Optional<FirstFrames> of(JsonNode stream) {
    var frames = stream.path(FirstFramesProbe.FIRST_FRAMES).valueStream().toList();
    var cutShort = stream.path(FirstFramesProbe.FIRST_FRAMES_CUT_SHORT).asBoolean();
    return frames.isEmpty() ? Optional.empty() : Optional.of(new FirstFrames(frames, cutShort));
  }

  /** Whether any frame carries side data of the type. */
  boolean carries(String type) {
    return frames.stream().anyMatch(frame -> sideData(frame, type).isPresent());
  }

  /** The first frame's side data of the type. */
  Optional<JsonNode> firstFrameSideData(String type) {
    return sideData(frames.getFirst(), type);
  }

  /** The side data of the type of the first frame that carries it. */
  Optional<JsonNode> earliestSideData(String type) {
    return frames.stream().flatMap(frame -> sideData(frame, type).stream()).findFirst();
  }

  private static Optional<JsonNode> sideData(JsonNode frame, String type) {
    return frame
        .path("side_data_list")
        .valueStream()
        .filter(entry -> type.equals(entry.path("side_data_type").asString("")))
        .findFirst();
  }
}
