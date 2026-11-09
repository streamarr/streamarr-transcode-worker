package com.streamarr.transcode.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * The input of the worker's test encode of the Dolby Vision reshape, copied out of the worker's
 * resources into a temporary file, which FFmpeg reads because an MP4 whose moov follows its media
 * cannot be read from a pipe. Closing it deletes the file.
 */
@Slf4j
final class SampleDolbyVisionFrame implements AutoCloseable {

  // One 64x64 frame of Dolby Vision profile 5 under dvh1, with its RPU;
  // src/test/sample-entry-fixtures/record-dolby-vision-5-fixture.sh records it.
  private static final String RESOURCE = "dolby-vision-5-test-frame.mp4";
  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"));

  private final Path path;

  private SampleDolbyVisionFrame(Path path) {
    this.path = path;
  }

  static SampleDolbyVisionFrame extract() throws IOException {
    var path = Files.createTempFile("streamarr-dolby-vision-test-frame", ".mp4", OWNER_ONLY);
    try (var resource = SampleDolbyVisionFrame.class.getResourceAsStream(RESOURCE)) {
      if (resource == null) {
        throw new IOException("The worker's resources hold no " + RESOURCE);
      }

      // Writing into the created file keeps its owner-only permissions, which replacing it would
      // drop.
      try (var copy = Files.newOutputStream(path)) {
        resource.transferTo(copy);
      }

      return new SampleDolbyVisionFrame(path);
    } catch (IOException e) {
      Files.deleteIfExists(path);
      throw e;
    }
  }

  Path path() {
    return path;
  }

  @Override
  public void close() {
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      log.warn("Could not delete the Dolby Vision test frame at {}", path, e);
    }
  }
}
