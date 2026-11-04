package com.streamarr.transcode.worker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;

/**
 * The paths that FFmpeg and ffprobe may reach under Landlock, by the rights they get there. Every
 * other path is denied, and so is every TCP connection, so a soft link swapped into a media root
 * after the worker's check leads FFmpeg to a file it cannot open.
 *
 * @param mediaRoots the media roots, read-only, each pinned to the directory it was at startup
 * @param readOnly the system files the tools read: the loader cache, fonts and device information
 * @param readExecute the directories holding the tools, their libraries and the loader
 * @param temporaryDirectory the one directory the tools may write: their temporary files and the
 *     caches that fontconfig and the CUDA runtime keep
 * @param devices the device nodes and device directories that hardware encoding opens
 */
record LandlockPolicy(
    @NonNull List<PinnedDirectory> mediaRoots,
    @NonNull List<Path> readOnly,
    @NonNull List<Path> readExecute,
    @NonNull Path temporaryDirectory,
    @NonNull List<Path> devices) {

  // ld.so.cache, fontconfig's configuration and the OpenCL and Vulkan loaders' lists live in /etc;
  // the NVIDIA driver reads its parameters and the process's own maps in /proc, and PCI, NUMA and
  // module facts in /sys; x264 and x265 read the overcommit setting in /proc.
  private static final List<Path> SYSTEM_READ_ONLY =
      List.of(Path.of("/etc"), Path.of("/proc"), Path.of("/sys"), Path.of("/var/cache/fontconfig"));

  // The loader, glibc, the driver libraries a GPU runtime injects, nice, and the fonts.
  private static final List<Path> SYSTEM_READ_EXECUTE =
      List.of(Path.of("/usr"), Path.of("/lib"), Path.of("/lib64"));

  private static final String DRM_DEVICE_DIRECTORY = "dri";
  private static final String NVIDIA_DEVICE_PREFIX = "nvidia";

  LandlockPolicy {
    mediaRoots = List.copyOf(mediaRoots);
    readOnly = List.copyOf(readOnly);
    readExecute = List.copyOf(readExecute);
    devices = List.copyOf(devices);
  }

  /**
   * The policy for a worker host: its media roots read-only and pinned, the directories of its
   * FFmpeg tools executable, its temporary directory writable, and the DRM and NVIDIA device nodes
   * present in its device directory. A tool that the host cannot locate adds no directory; the
   * worker's tool checks report it.
   */
  static LandlockPolicy forHost(@NonNull Host host) {
    var mediaRoots =
        host.mediaRoots().stream().map(Path::toAbsolutePath).map(PinnedDirectory::of).toList();

    var readExecute = new ArrayList<>(SYSTEM_READ_EXECUTE);
    host.tools().stream()
        .map(tool -> ExecutableSearch.locate(tool, host.searchPath()))
        .flatMap(Optional::stream)
        .map(LandlockPolicy::realParent)
        .filter(directory -> !readExecute.contains(directory))
        .forEach(readExecute::add);

    return new LandlockPolicy(
        mediaRoots,
        SYSTEM_READ_ONLY,
        readExecute,
        host.temporaryDirectory().toAbsolutePath(),
        devicesIn(host));
  }

  private static Path realParent(Path executable) {
    try {
      return executable.toRealPath().getParent();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // A device directory without a GPU lists neither, and landlock-exec skips a path that is absent
  // when a tool starts.
  private static List<Path> devicesIn(Host host) {
    var directory = host.deviceDirectory();
    var devices = new ArrayList<Path>();
    devices.add(directory.resolve(DRM_DEVICE_DIRECTORY));
    if (!Files.isDirectory(directory)) {
      return devices;
    }

    try (Stream<Path> entries = Files.list(directory)) {
      entries
          .filter(entry -> entry.getFileName().toString().startsWith(NVIDIA_DEVICE_PREFIX))
          .sorted()
          .forEach(devices::add);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    return devices;
  }

  /**
   * A media root with the device and inode numbers it had when the worker started. landlock-exec
   * grants a root only while its path still names that directory, so a root renamed and replaced by
   * a soft link grants nothing and the tool does not run.
   */
  record PinnedDirectory(@NonNull Path path, long device, long inode) {

    static PinnedDirectory of(Path directory) {
      try {
        return new PinnedDirectory(
            directory,
            (Long) Files.getAttribute(directory, "unix:dev"),
            (Long) Files.getAttribute(directory, "unix:ino"));
      } catch (IOException e) {
        throw new UncheckedIOException("Cannot pin the media root " + directory, e);
      }
    }

    /** The identity as landlock-exec reads it: the device and inode numbers, unsigned. */
    String identity() {
      return Long.toUnsignedString(device) + ":" + Long.toUnsignedString(inode);
    }
  }

  /**
   * What a worker host offers the policy.
   *
   * @param mediaRoots the source roots of the worker's media namespaces, each an existing directory
   * @param tools the FFmpeg and ffprobe commands as configured: a path, or a name on the search
   *     path
   * @param searchPath the PATH variable that locates a tool configured by name
   * @param temporaryDirectory the directory the tools may write, of this worker alone
   * @param deviceDirectory the host's device directory, {@code /dev} on a Linux host
   */
  @Builder
  record Host(
      @NonNull Collection<Path> mediaRoots,
      @NonNull List<String> tools,
      @NonNull String searchPath,
      @NonNull Path temporaryDirectory,
      @NonNull Path deviceDirectory) {}
}
