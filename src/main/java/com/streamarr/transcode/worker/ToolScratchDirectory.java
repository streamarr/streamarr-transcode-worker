package com.streamarr.transcode.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.NonNull;

/**
 * The one directory the confined tools may write: a directory of this worker process alone, under
 * the JVM's temporary directory, so no other file there is reachable. It is removed with whatever
 * the tools left when the worker stops.
 */
final class ToolScratchDirectory implements AutoCloseable {

  private static final FileAttribute<Set<PosixFilePermission>> OWNER_ONLY =
      PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"));

  @Getter private final Path path;

  private ToolScratchDirectory(Path path) {
    this.path = path;
  }

  static ToolScratchDirectory create(@NonNull Path parent) throws IOException {
    return new ToolScratchDirectory(
        Files.createTempDirectory(parent, "streamarr-tools-", OWNER_ONLY));
  }

  @Override
  public void close() throws IOException {
    try (Stream<Path> tree = Files.walk(path)) {
      for (var entry : tree.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(entry);
      }
    }
  }
}
