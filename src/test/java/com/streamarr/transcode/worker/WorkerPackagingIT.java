package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("IntegrationTest")
@DisplayName("Worker Packaging Tests")
class WorkerPackagingIT {

  @Test
  @DisplayName("Should launch the worker through Spring Boot when the application is packaged")
  @SuppressWarnings("checkstyle:fullyQualifiedName") // The manifest names Boot's external launcher.
  void shouldLaunchTheWorkerThroughSpringBootWhenTheApplicationIsPackaged() throws Exception {
    try (var jar = applicationJar()) {
      var attributes = jar.getManifest().getMainAttributes();
      assertThat(attributes.getValue("Main-Class"))
          .isEqualTo("org.springframework.boot.loader.launch.JarLauncher");
      assertThat(attributes.getValue("Start-Class"))
          .isEqualTo(TranscodeWorkerApplication.class.getName());
      assertThat(jar.getEntry("BOOT-INF/classes/application.yml")).isNotNull();
    }
  }

  @Test
  @DisplayName("Should exclude server modules when the standalone worker is packaged")
  void shouldExcludeServerModulesWhenTheStandaloneWorkerIsPackaged() throws Exception {
    try (var jar = applicationJar()) {
      var entries = jar.stream().map(ZipEntry::getName).toList();
      assertThat(entries)
          .noneMatch(name -> name.startsWith("BOOT-INF/classes/com/streamarr/server/"));
      assertThat(entries)
          .filteredOn(name -> name.startsWith("BOOT-INF/lib/"))
          .isNotEmpty()
          .noneMatch(
              name ->
                  name.matches(
                      "BOOT-INF/lib/(server-|spring-security-|spring-data-|hibernate-|flyway-|jooq-|"
                          + "graphql-|dgs-|cedar-).*"));
    }
  }

  private JarFile applicationJar() throws Exception {
    try (var artifacts = Files.list(Path.of("target"))) {
      var jars = artifacts.filter(path -> path.getFileName().toString().endsWith(".jar")).toList();
      assertThat(jars).hasSize(1);
      return new JarFile(jars.getFirst().toFile());
    }
  }
}
