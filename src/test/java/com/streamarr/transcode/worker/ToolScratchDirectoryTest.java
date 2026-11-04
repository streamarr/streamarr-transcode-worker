package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Tool Scratch Directory Tests")
class ToolScratchDirectoryTest {

  @TempDir Path parent;

  @Test
  @DisplayName("Should create a directory of the owner alone under the parent when created")
  void shouldCreateADirectoryOfTheOwnerAloneUnderTheParentWhenCreated() throws Exception {
    try (var scratch = ToolScratchDirectory.create(parent)) {
      assertThat(scratch.getPath()).isDirectory().hasParent(parent);
      assertThat(Files.getPosixFilePermissions(scratch.getPath()))
          .containsExactlyInAnyOrder(
              PosixFilePermission.OWNER_READ,
              PosixFilePermission.OWNER_WRITE,
              PosixFilePermission.OWNER_EXECUTE);
    }
  }

  @Test
  @DisplayName("Should remove the directory with everything the tools left when closed")
  void shouldRemoveTheDirectoryWithEverythingTheToolsLeftWhenClosed() throws Exception {
    var scratch = ToolScratchDirectory.create(parent);
    Files.writeString(scratch.getPath().resolve("cache"), "left behind");
    Files.createFile(Files.createDirectory(scratch.getPath().resolve("fontconfig")).resolve("x"));

    scratch.close();

    assertThat(scratch.getPath()).doesNotExist();
    assertThat(parent).isEmptyDirectory();
  }
}
