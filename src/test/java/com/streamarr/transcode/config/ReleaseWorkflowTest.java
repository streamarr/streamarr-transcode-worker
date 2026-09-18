package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("Release Workflow Tests")
class ReleaseWorkflowTest {

  @Test
  @DisplayName("Should delegate release maintenance with the App credentials when main changes")
  void shouldDelegateReleaseMaintenanceWithAppCredentialsWhenMainChanges() throws Exception {
    Map<?, ?> workflow =
        new Yaml().load(Files.readString(Path.of(".github/workflows/release-please.yml")));
    var triggers = (Map<?, ?>) workflow.get(true);
    var release = (Map<?, ?>) ((Map<?, ?>) workflow.get("jobs")).get("release");

    assertThat(triggers.keySet()).isEqualTo(Set.of("push", "workflow_dispatch"));
    assertThat(triggers.get("push")).asInstanceOf(MAP).containsEntry("branches", List.of("main"));
    assertThat(workflow.get("concurrency"))
        .asInstanceOf(MAP)
        .containsEntry("group", "release-please-main")
        .containsEntry("cancel-in-progress", false);
    assertThat(release.get("uses"))
        .asString()
        .matches("streamarr/streamarr-workflows/.github/workflows/release-please.yml@[a-f0-9]{40}");
    assertThat(release.get("secrets"))
        .asInstanceOf(MAP)
        .containsOnly(
            Map.entry("app-client-id", "${{ secrets.ORG_STREAMARR_RELEASE_CLIENT_ID }}"),
            Map.entry("app-private-key", "${{ secrets.ORG_STREAMARR_RELEASE_PRIVATE_KEY }}"));
  }
}
