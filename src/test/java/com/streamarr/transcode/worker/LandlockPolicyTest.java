package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Landlock Policy Tests")
class LandlockPolicyTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName(
      "Should list the media roots read-only beside the system files when built for a host")
  void shouldListTheMediaRootsReadOnlyBesideTheSystemFilesWhenBuiltForAHost() throws Exception {
    var movies = Files.createDirectory(tempDir.resolve("movies"));
    var shows = Files.createDirectory(tempDir.resolve("shows"));

    var policy = LandlockPolicy.forHost(hostBuilder().mediaRoots(List.of(movies, shows)).build());

    assertThat(policy.readOnly())
        .containsExactly(
            movies,
            shows,
            Path.of("/etc"),
            Path.of("/proc"),
            Path.of("/sys"),
            Path.of("/var/cache/fontconfig"));
  }

  @Test
  @DisplayName("Should make a media root absolute when the host configures a relative one")
  void shouldMakeAMediaRootAbsoluteWhenTheHostConfiguresARelativeOne() {
    var policy =
        LandlockPolicy.forHost(hostBuilder().mediaRoots(List.of(Path.of("media"))).build());

    assertThat(policy.readOnly()).first().isEqualTo(Path.of("media").toAbsolutePath());
  }

  @Test
  @DisplayName(
      "Should grant execution in the real directory of each tool found on the search path when"
          + " built for a host")
  void shouldGrantExecutionInTheRealDirectoryOfEachToolFoundOnTheSearchPathWhenBuiltForAHost()
      throws Exception {
    var runtime = Files.createDirectories(tempDir.resolve("runtime/bin"));
    executable(runtime.resolve("ffmpeg"));
    executable(runtime.resolve("ffprobe"));
    var links = Files.createDirectory(tempDir.resolve("links"));
    Files.createSymbolicLink(links.resolve("ffmpeg"), runtime.resolve("ffmpeg"));
    Files.createSymbolicLink(links.resolve("ffprobe"), runtime.resolve("ffprobe"));

    var policy =
        LandlockPolicy.forHost(
            hostBuilder()
                .tools(List.of("ffmpeg", "ffprobe"))
                .searchPath(tempDir.resolve("empty") + ":" + links)
                .build());

    assertThat(policy.readExecute())
        .containsExactly(Path.of("/usr"), Path.of("/lib"), Path.of("/lib64"), runtime.toRealPath());
  }

  @Test
  @DisplayName("Should grant execution in a tool's directory when the tool is configured as a path")
  void shouldGrantExecutionInAToolsDirectoryWhenTheToolIsConfiguredAsAPath() throws Exception {
    var ffmpeg = Files.createDirectory(tempDir.resolve("ffmpeg-build"));
    executable(ffmpeg.resolve("ffmpeg"));
    var ffprobe = Files.createDirectory(tempDir.resolve("ffprobe-build"));
    executable(ffprobe.resolve("ffprobe"));

    var policy =
        LandlockPolicy.forHost(
            hostBuilder()
                .tools(
                    List.of(
                        ffmpeg.resolve("ffmpeg").toString(), ffprobe.resolve("ffprobe").toString()))
                .searchPath("")
                .build());

    assertThat(policy.readExecute()).endsWith(ffmpeg.toRealPath(), ffprobe.toRealPath()).hasSize(5);
  }

  @Test
  @DisplayName("Should add no directory for a tool when the host cannot locate it")
  void shouldAddNoDirectoryForAToolWhenTheHostCannotLocateIt() {
    var policy =
        LandlockPolicy.forHost(
            hostBuilder()
                .tools(List.of("ffmpeg", tempDir.resolve("missing/ffprobe").toString()))
                .searchPath(tempDir.toString())
                .build());

    assertThat(policy.readExecute())
        .containsExactly(Path.of("/usr"), Path.of("/lib"), Path.of("/lib64"));
  }

  @Test
  @DisplayName("Should list a tool directory once when both tools share it under the system tree")
  void shouldListAToolDirectoryOnceWhenBothToolsShareItUnderTheSystemTree() throws Exception {
    var usr = Files.createDirectory(tempDir.resolve("usr"));
    var bin = Files.createDirectory(usr.resolve("bin"));
    executable(bin.resolve("ffmpeg"));
    executable(bin.resolve("ffprobe"));

    var policy =
        LandlockPolicy.forHost(
            hostBuilder().tools(List.of("ffmpeg", "ffprobe")).searchPath(bin.toString()).build());

    assertThat(policy.readExecute())
        .containsExactly(Path.of("/usr"), Path.of("/lib"), Path.of("/lib64"), bin.toRealPath());
  }

  @Test
  @DisplayName("Should make the temporary directory the one writable path when built for a host")
  void shouldMakeTheTemporaryDirectoryTheOneWritablePathWhenBuiltForAHost() {
    var policy =
        LandlockPolicy.forHost(hostBuilder().temporaryDirectory(Path.of("var/tmp")).build());

    assertThat(policy.temporaryDirectory()).isEqualTo(Path.of("var/tmp").toAbsolutePath());
  }

  @Test
  @DisplayName(
      "Should list the DRM directory and every NVIDIA node as devices when the host has a GPU")
  void shouldListTheDrmDirectoryAndEveryNvidiaNodeAsDevicesWhenTheHostHasAGpu() throws Exception {
    var dev = Files.createDirectory(tempDir.resolve("dev"));
    for (var node : List.of("nvidiactl", "nvidia0", "nvidia1", "nvidia-uvm", "nvidia-modeset")) {
      Files.createFile(dev.resolve(node));
    }
    Files.createDirectory(dev.resolve("nvidia-caps"));
    Files.createDirectory(dev.resolve("dri"));
    for (var other : List.of("null", "sda", "shm", "tty", "urandom")) {
      Files.createFile(dev.resolve(other));
    }

    var policy = LandlockPolicy.forHost(hostBuilder().deviceDirectory(dev).build());

    assertThat(policy.devices())
        .containsExactly(
            dev.resolve("dri"),
            dev.resolve("nvidia-caps"),
            dev.resolve("nvidia-modeset"),
            dev.resolve("nvidia-uvm"),
            dev.resolve("nvidia0"),
            dev.resolve("nvidia1"),
            dev.resolve("nvidiactl"));
  }

  @Test
  @DisplayName("Should list only the DRM directory as a device when the host has no NVIDIA node")
  void shouldListOnlyTheDrmDirectoryAsADeviceWhenTheHostHasNoNvidiaNode() throws Exception {
    var dev = Files.createDirectory(tempDir.resolve("dev"));
    Files.createFile(dev.resolve("null"));

    var policy = LandlockPolicy.forHost(hostBuilder().deviceDirectory(dev).build());

    assertThat(policy.devices()).containsExactly(dev.resolve("dri"));
  }

  @Test
  @DisplayName("Should still list the DRM directory when the device directory is missing")
  void shouldStillListTheDrmDirectoryWhenTheDeviceDirectoryIsMissing() {
    var dev = tempDir.resolve("no-dev");

    var policy = LandlockPolicy.forHost(hostBuilder().deviceDirectory(dev).build());

    assertThat(policy.devices()).containsExactly(dev.resolve("dri"));
  }

  private LandlockPolicy.Host.HostBuilder hostBuilder() {
    return LandlockPolicy.Host.builder()
        .mediaRoots(List.of(tempDir.resolve("media")))
        .tools(List.of())
        .searchPath("")
        .temporaryDirectory(tempDir.resolve("tmp"))
        .deviceDirectory(tempDir.resolve("dev"));
  }

  private static void executable(Path file) throws Exception {
    Files.writeString(file, "#!/bin/sh\n");
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
  }
}
