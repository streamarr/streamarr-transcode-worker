package com.streamarr.transcode.worker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.NonNull;

/**
 * Whether the host lets the worker confine FFmpeg: the Landlock ABI version that {@code
 * landlock-exec --abi} reports from {@code landlock_create_ruleset}, or why it reports none.
 */
sealed interface LandlockAvailability {

  /** The kernel offers Landlock at this ABI version. */
  record Available(int abiVersion) implements LandlockAvailability {}

  /** The worker cannot confine FFmpeg; the action tells an operator how to fix the host. */
  record Unavailable(@NonNull String reason, @NonNull String action)
      implements LandlockAvailability {}

  static LandlockAvailability check(@NonNull String landlockExecPath) throws InterruptedException {
    Process process;
    try {
      process = new ProcessBuilder(landlockExecPath, "--abi").start();
    } catch (IOException e) {
      return new Unavailable(
          "landlock-exec is not available to the transcode worker: " + landlockExecPath,
          "Run the worker image, which carries landlock-exec, or compile it with"
              + " buildpacks/landlock/bin/compile and name it in"
              + " TRANSCODE_WORKER_LANDLOCK_EXEC_PATH or put it on PATH.");
    }

    try {
      var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      var diagnostics =
          new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).strip();
      return reported(process.waitFor(), output.strip(), diagnostics, landlockExecPath);
    } catch (IOException e) {
      return helperBroken("landlock-exec could not be read: " + e.getMessage());
    } catch (InterruptedException e) {
      process.destroyForcibly();
      throw e;
    }
  }

  // landlock-exec exits 0 with the ABI version on its standard output, and 2 with the errno name
  // that landlock_create_ruleset returned on its standard error.
  private static LandlockAvailability reported(
      int exitCode, String output, String diagnostics, String landlockExecPath) {
    if (exitCode == 2) {
      return withoutLandlock(diagnostics);
    }

    if (exitCode != 0) {
      return helperBroken("landlock-exec exited with code " + exitCode + ": " + diagnostics);
    }

    try {
      var abiVersion = Integer.parseInt(output);
      if (abiVersion >= 1) {
        return new Available(abiVersion);
      }
    } catch (NumberFormatException _) {
      // Reported below.
    }

    return helperBroken(
        "landlock-exec at " + landlockExecPath + " reported no Landlock ABI version: " + output);
  }

  private static Unavailable helperBroken(String reason) {
    return new Unavailable(reason, "Run the worker image, whose landlock-exec answers --abi.");
  }

  private static Unavailable withoutLandlock(String diagnostics) {
    if (diagnostics.contains("ENOSYS")) {
      return new Unavailable(
          "the kernel offers no Landlock (" + diagnostics + ")",
          "Run the worker on Linux 5.13 or later with Landlock built into the kernel"
              + " (CONFIG_SECURITY_LANDLOCK).");
    }

    if (diagnostics.contains("EOPNOTSUPP")) {
      return new Unavailable(
          "Landlock is built into the kernel but not enabled (" + diagnostics + ")",
          "Add landlock to the kernel's lsm= boot parameter; /sys/kernel/security/lsm lists the"
              + " enabled security modules.");
    }

    return new Unavailable(
        "the host refused landlock_create_ruleset (" + diagnostics + ")",
        "Run the worker on Linux 5.13 or later with Landlock in the kernel's list of security"
            + " modules (/sys/kernel/security/lsm), under a seccomp profile that allows the"
            + " landlock_create_ruleset, landlock_add_rule and landlock_restrict_self system calls,"
            + " as Docker's default profile does.");
  }
}
