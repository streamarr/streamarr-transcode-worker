package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

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

    assertThat(worker).asInstanceOf(MAP).containsEntry("release-type", "maven");
  }

  @Test
  @DisplayName("Should include project changes without bylines when preparing release notes")
  void shouldIncludeProjectChangesWithoutBylinesWhenPreparingReleaseNotes() throws Exception {
    Map<String, Object> config =
        new Yaml().load(Files.readString(Path.of("release-please-config.json")));
    var worker = (Map<?, ?>) ((Map<?, ?>) config.get("packages")).get(".");

    assertThat(worker)
        .asInstanceOf(MAP)
        .containsEntry("include-commit-authors", false)
        .containsEntry("include-component-in-tag", false)
        .containsEntry("include-v-in-tag", true);
    assertThat((List<?>) worker.get("changelog-sections"))
        .anySatisfy(
            section ->
                assertThat(section).isEqualTo(Map.of("type", "behavioral", "section", "Changes")))
        .anySatisfy(
            section ->
                assertThat(section)
                    .isEqualTo(Map.of("type", "structural", "section", "Refactoring")));
    assertThat(config)
        .containsEntry(
            "group-pull-request-title-pattern", "chore${scope}: release${component} ${version}");
  }
}
