package com.streamarr.transcode.probe;

import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The chosen video stream: the first video stream that is not an attached picture. Its video
 * ordinal counts the video streams before it, and its track ID is the MP4 track_ID ffprobe reports
 * as the stream id.
 */
record ChosenVideoStream(int streamIndex, int videoOrdinal, Optional<Long> trackId) {

  static Optional<ChosenVideoStream> find(JsonNode streams) {
    var videoOrdinal = 0;
    var position = 0;
    for (var stream : streams) {
      var isVideo = "video".equals(stream.path("codec_type").asString(""));
      if (isVideo && stream.path("disposition").path("attached_pic").asInt() != 1) {
        return Optional.of(
            new ChosenVideoStream(
                stream.path("index").asInt(position), videoOrdinal, trackId(stream)));
      }

      if (isVideo) {
        videoOrdinal++;
      }

      position++;
    }

    return Optional.empty();
  }

  private static Optional<Long> trackId(JsonNode stream) {
    var id = stream.path("id").asString("");
    if (!id.startsWith("0x")) {
      return Optional.empty();
    }

    try {
      return Optional.of(Long.parseLong(id.substring(2), 16));
    } catch (NumberFormatException _) {
      return Optional.empty();
    }
  }
}
