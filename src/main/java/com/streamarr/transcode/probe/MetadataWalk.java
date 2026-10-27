package com.streamarr.transcode.probe;

import java.nio.ByteBuffer;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * What the probe's walk of the chosen video stream's configuration record and first packets found
 * of its {@link HdrMessage}s. A nested message can apply only to a layer or a view that FFmpeg does
 * not decode, so it neither proves nor rules out a feature of the decoded picture.
 */
sealed interface MetadataWalk permits SeiMessages {

  /**
   * The walk of the stream; empty unless the probe read the stream's first frames, which only the
   * chosen video stream has, and the walk covers the stream's codec.
   */
  static Optional<MetadataWalk> of(JsonNode stream) {
    return SeiMessages.of(stream).map(MetadataWalk.class::cast);
  }

  /** Whether a message of the kind that is not nested shows. */
  boolean holds(HdrMessage message);

  /**
   * Whether the walk rules out a message of the kind: it read all that it covers, and no nested
   * message is of the kind.
   */
  boolean rulesOut(HdrMessage message);

  /** The payload of the first message of the kind that is not nested, read-only. */
  Optional<ByteBuffer> firstPayload(HdrMessage message);
}
