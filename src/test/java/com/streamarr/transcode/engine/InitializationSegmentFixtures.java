package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Reads the initialization segments that the pinned worker image wrote;
 * src/test/resources/initialization-segments/README.md describes each one and how to record them
 * again.
 */
final class InitializationSegmentFixtures {

  private InitializationSegmentFixtures() {}

  static byte[] bytesOfInitializationSegment(String file) {
    try (var stream =
        InitializationSegmentFixtures.class.getResourceAsStream(
            "/initialization-segments/" + file)) {
      assertThat(stream).as("initialization segment %s", file).isNotNull();
      return stream.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static InitializationSegment initializationSegment(String file) {
    return new InitializationSegment(bytesOfInitializationSegment(file));
  }
}
