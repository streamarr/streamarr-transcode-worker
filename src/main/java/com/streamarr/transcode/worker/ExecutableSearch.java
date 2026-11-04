package com.streamarr.transcode.worker;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;

/** Finds the executable that a command name starts, the way a shell or execvp does. */
final class ExecutableSearch {

  private ExecutableSearch() {}

  /**
   * The executable that {@code command} names: the file itself when the name has a separator, and
   * otherwise the first executable file of that name in the directories of {@code searchPath}.
   */
  static Optional<Path> locate(String command, String searchPath) {
    if (command.contains(File.separator)) {
      return Optional.of(Path.of(command)).filter(Files::isExecutable);
    }

    return Arrays.stream(searchPath.split(File.pathSeparator, -1))
        .filter(directory -> !directory.isEmpty())
        .map(directory -> Path.of(directory).resolve(command))
        .filter(Files::isExecutable)
        .filter(candidate -> !Files.isDirectory(candidate))
        .findFirst();
  }
}
