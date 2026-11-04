package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Executable Search Tests")
class ExecutableSearchTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should find the first executable of the name when searching the path in order")
  void shouldFindTheFirstExecutableOfTheNameWhenSearchingThePathInOrder() throws Exception {
    var first = Files.createDirectory(tempDir.resolve("first"));
    var second = Files.createDirectory(tempDir.resolve("second"));
    Files.writeString(first.resolve("ffmpeg"), "not executable");
    var found = executable(second.resolve("ffmpeg"));
    var searchPath = String.join(":", "", first.toString(), second.toString());

    assertThat(ExecutableSearch.locate("ffmpeg", searchPath)).contains(found);
  }

  @Test
  @DisplayName("Should skip a directory of the name when searching the path")
  void shouldSkipADirectoryOfTheNameWhenSearchingThePath() throws Exception {
    var first = Files.createDirectory(tempDir.resolve("first"));
    Files.createDirectory(first.resolve("ffmpeg"));

    assertThat(ExecutableSearch.locate("ffmpeg", first.toString())).isEmpty();
  }

  @Test
  @DisplayName("Should take the command as the file when it names an executable path")
  void shouldTakeTheCommandAsTheFileWhenItNamesAnExecutablePath() throws Exception {
    var elsewhere = Files.createDirectory(tempDir.resolve("elsewhere"));
    var tool = executable(tempDir.resolve("ffmpeg"));

    assertThat(ExecutableSearch.locate(tool.toString(), elsewhere.toString())).contains(tool);
  }

  @Test
  @DisplayName("Should find nothing when the command names a missing path")
  void shouldFindNothingWhenTheCommandNamesAMissingPath() throws Exception {
    var elsewhere = Files.createDirectory(tempDir.resolve("elsewhere"));
    executable(elsewhere.resolve("ffmpeg"));

    assertThat(ExecutableSearch.locate(tempDir.resolve("missing").toString(), elsewhere.toString()))
        .isEmpty();
  }

  @Test
  @DisplayName("Should find nothing when the search path is empty")
  void shouldFindNothingWhenTheSearchPathIsEmpty() {
    assertThat(ExecutableSearch.locate("ffmpeg", "")).isEmpty();
  }

  private static Path executable(Path file) throws Exception {
    Files.writeString(file, "#!/bin/sh\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
    return file;
  }
}
