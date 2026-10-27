package com.streamarr.transcode.probe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/** The bytes of the first packets that {@link FirstPacketsProbe} adds to a stream's description. */
final class FirstPackets {

  private FirstPackets() {}

  /**
   * One array for each packet, in order; empty when the probe read no packet or cannot parse the
   * hex dump of one.
   */
  static Optional<List<byte[]>> of(JsonNode stream) {
    var dumps = stream.path(FirstPacketsProbe.FIRST_PACKETS).valueStream().toList();
    if (dumps.isEmpty()) {
      return Optional.empty();
    }

    var packets = new ArrayList<byte[]>();
    for (var dump : dumps) {
      var bytes = Extradata.dumped(dump.path("data").asString(""));
      if (bytes.isEmpty()) {
        return Optional.empty();
      }

      packets.add(bytes.get());
    }

    return Optional.of(packets);
  }
}
