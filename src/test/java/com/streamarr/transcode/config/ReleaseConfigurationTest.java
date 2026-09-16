package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("Release Configuration Tests")
class ReleaseConfigurationTest {

  @Test
  @DisplayName("Should configure Maven releases when worker versions advance")
  void shouldConfigureMavenReleasesWhenWorkerVersionsAdvance() throws Exception {
    Map<String, Object> config =
        new Yaml().load(Files.readString(Path.of("release-please-config.json")));
    var packages = (Map<?, ?>) config.get("packages");
    var worker = (Map<?, ?>) packages.get(".");
    Map<String, Object> manifest =
        new Yaml().load(Files.readString(Path.of(".release-please-manifest.json")));

    assertThat(config.get("bootstrap-sha")).isEqualTo("a1ef061ef706bb5b5c59cb95417145d66636b8a7");
    assertThat(worker.get("release-type")).isEqualTo("maven");
    assertThat(worker.get("initial-version")).isEqualTo("0.1.0");
    assertThat(manifest.keySet()).isSubsetOf(".");
    assertThat(manifest.values())
        .allSatisfy(version -> assertThat(version.toString()).matches("[0-9]+\\.[0-9]+\\.[0-9]+"));
  }

  @Test
  @DisplayName("Should include project changes without bylines when preparing release notes")
  void shouldIncludeProjectChangesWithoutBylinesWhenPreparingReleaseNotes() throws Exception {
    Map<String, Object> config =
        new Yaml().load(Files.readString(Path.of("release-please-config.json")));
    var worker = (Map<?, ?>) ((Map<?, ?>) config.get("packages")).get(".");

    assertThat(worker.get("include-commit-authors")).isEqualTo(false);
    assertThat(worker.get("include-component-in-tag")).isEqualTo(false);
    assertThat(worker.get("include-v-in-tag")).isEqualTo(true);
    assertThat((List<?>) worker.get("changelog-sections"))
        .anySatisfy(
            section ->
                assertThat(section).isEqualTo(Map.of("type", "behavioral", "section", "Changes")))
        .anySatisfy(
            section ->
                assertThat(section)
                    .isEqualTo(Map.of("type", "structural", "section", "Refactoring")));
    assertThat(config.get("group-pull-request-title-pattern"))
        .isEqualTo("chore${scope}: release${component} ${version}");
  }
}
