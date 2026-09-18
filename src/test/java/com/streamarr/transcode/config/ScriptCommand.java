package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ScriptCommand {

  private final List<String> command = new ArrayList<>();
  private final Map<String, String> environment = new HashMap<>();
  private Path prependedPath;

  private ScriptCommand(Path script) {
    command.add(script.toString());
  }

  static ScriptCommand of(Path script) {
    return new ScriptCommand(script);
  }

  static void writeFake(Path directory, String name, String body) throws IOException {
    var fake = directory.resolve(name);
    Files.writeString(fake, "#!/bin/bash\nset -euo pipefail\n" + body);
    assertThat(fake.toFile().setExecutable(true)).isTrue();
  }

  ScriptCommand argument(String argument) {
    command.add(argument);
    return this;
  }

  ScriptCommand environment(String name, String value) {
    environment.put(name, value);
    return this;
  }

  ScriptCommand prependPath(Path path) {
    prependedPath = path;
    return this;
  }

  Result execute() throws IOException, InterruptedException {
    var processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
    processBuilder.environment().putAll(environment);
    if (prependedPath != null) {
      var systemPath = processBuilder.environment().get("PATH");
      processBuilder.environment().put("PATH", prependedPath + ":" + systemPath);
    }

    var process = processBuilder.start();
    var output = new String(process.getInputStream().readAllBytes());
    return new Result(process.waitFor(), output);
  }

  record Result(int exitCode, String output) {}
}
