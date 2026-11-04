package com.streamarr.transcode.worker;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import lombok.NonNull;

/**
 * Runs every FFmpeg tool through {@code landlock-exec} with the policy's rules, so the tool and
 * whatever it starts can reach only the policy's paths, and points the caches that its libraries
 * write at the policy's temporary directory.
 */
final class LandlockConfinement implements UnaryOperator<ProcessBuilder> {

  private static final String COMMAND_SEPARATOR = "--";

  private final List<String> prefix;
  private final Map<String, String> environment;

  LandlockConfinement(@NonNull String landlockExecPath, @NonNull LandlockPolicy policy) {
    var arguments = new ArrayList<String>();
    arguments.add(landlockExecPath);
    policy
        .mediaRoots()
        .forEach(
            root -> {
              rule(arguments, "--read-pinned", root.path());
              arguments.add(root.identity());
            });
    policy.readOnly().forEach(path -> rule(arguments, "--read", path));
    policy.readExecute().forEach(path -> rule(arguments, "--read-execute", path));
    rule(arguments, "--read-write", policy.temporaryDirectory());
    policy.devices().forEach(path -> rule(arguments, "--device", path));
    arguments.add(COMMAND_SEPARATOR);
    this.prefix = List.copyOf(arguments);
    var temporaryDirectory = policy.temporaryDirectory().toString();
    this.environment =
        Map.of(
            "TMPDIR", temporaryDirectory,
            "XDG_CACHE_HOME", temporaryDirectory,
            "CUDA_CACHE_PATH", temporaryDirectory);
  }

  private static void rule(List<String> arguments, String option, Path path) {
    arguments.add(option);
    arguments.add(path.toString());
  }

  @Override
  public ProcessBuilder apply(ProcessBuilder builder) {
    var command = new ArrayList<>(prefix);
    command.addAll(builder.command());
    builder.command(command);
    builder.environment().putAll(environment);
    return builder;
  }
}
