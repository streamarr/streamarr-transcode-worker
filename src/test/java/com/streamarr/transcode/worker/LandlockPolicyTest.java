package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
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
      "Should pin each media root to the directory's device and inode numbers when built for a host")
  void shouldPinEachMediaRootToTheDirectorysDeviceAndInodeNumbersWhenBuiltForAHost()
      throws Exception {
    var movies = Files.createDirectory(tempDir.resolve("movies"));
    var shows = Files.createDirectory(tempDir.resolve("shows"));

    var policy = LandlockPolicy.forHost(hostBuilder().mediaRoots(List.of(movies, shows)).build());

    assertThat(policy.mediaRoots())
        .containsExactly(
            new LandlockPolicy.PinnedDirectory(movies, deviceOf(movies), inodeOf(movies)),
            new LandlockPolicy.PinnedDirectory(shows, deviceOf(shows), inodeOf(shows)));
  }

  @Test
  @DisplayName("Should list only the system files read-only when built for a host")
  void shouldListOnlyTheSystemFilesReadOnlyWhenBuiltForAHost() throws Exception {
    var policy = LandlockPolicy.forHost(hostBuilder().build());

    assertThat(policy.readOnly())
        .containsExactly(
            Path.of("/etc"), Path.of("/proc"), Path.of("/sys"), Path.of("/var/cache/fontconfig"));
  }

  @Test
  @DisplayName("Should make a media root absolute when the host configures a relative one")
  void shouldMakeAMediaRootAbsoluteWhenTheHostConfiguresARelativeOne() throws Exception {
    var media = Files.createDirectory(tempDir.resolve("media"));
    var relative = Path.of("").toAbsolutePath().relativize(media.toRealPath());

    var policy = LandlockPolicy.forHost(hostBuilder().mediaRoots(List.of(relative)).build());

    assertThat(policy.mediaRoots())
        .singleElement()
        .extracting(LandlockPolicy.PinnedDirectory::path)
        .isEqualTo(relative.toAbsolutePath());
  }

  @Test
  @DisplayName("Should refuse to build the policy when a media root does not exist")
  void shouldRefuseToBuildThePolicyWhenAMediaRootDoesNotExist() throws Exception {
    var host = hostBuilder().mediaRoots(List.of(tempDir.resolve("missing"))).build();

    assertThatThrownBy(() -> LandlockPolicy.forHost(host))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("missing");
  }

  @Test
  @DisplayName("Should write the identity as unsigned device and inode numbers when pinned")
  void shouldWriteTheIdentityAsUnsignedDeviceAndInodeNumbersWhenPinned() {
    var pinned = new LandlockPolicy.PinnedDirectory(Path.of("/media"), -1L, 7L);

    assertThat(pinned.identity()).isEqualTo("18446744073709551615:7");
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
  void shouldAddNoDirectoryForAToolWhenTheHostCannotLocateIt() throws Exception {
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
  @DisplayName("Should list a shared tool directory once when both tools resolve to it")
  void shouldListASharedToolDirectoryOnceWhenBothToolsResolveToIt() throws Exception {
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
  void shouldMakeTheTemporaryDirectoryTheOneWritablePathWhenBuiltForAHost() throws Exception {
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
  void shouldStillListTheDrmDirectoryWhenTheDeviceDirectoryIsMissing() throws Exception {
    var dev = tempDir.resolve("no-dev");

    var policy = LandlockPolicy.forHost(hostBuilder().deviceDirectory(dev).build());

    assertThat(policy.devices()).containsExactly(dev.resolve("dri"));
  }

  private static long deviceOf(Path directory) throws Exception {
    return (Long) Files.getAttribute(directory, "unix:dev");
  }

  private static long inodeOf(Path directory) throws Exception {
    return (Long) Files.getAttribute(directory, "unix:ino");
  }

  // Pinning a media root reads its identity, so the default root exists.
  private LandlockPolicy.Host.HostBuilder hostBuilder() throws Exception {
    return LandlockPolicy.Host.builder()
        .mediaRoots(List.of(Files.createDirectories(tempDir.resolve("library"))))
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
