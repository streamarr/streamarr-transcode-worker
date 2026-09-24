package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Transcode Worker Settings Tests")
class TranscodeWorkerSettingsTest {

  private static final UUID WORKER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
  private static final UUID SOURCE_NAMESPACE_ID =
      UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

  @Test
  @DisplayName("Should use the local endpoint when only worker identity and media are configured")
  void shouldUseTheLocalEndpointWhenOnlyWorkerIdentityAndMediaAreConfigured() {
    var environment =
        Map.of(
            "TRANSCODE_WORKER_ID", WORKER_ID.toString(),
            "TRANSCODE_WORKER_SOURCE_NAMESPACE_ID", SOURCE_NAMESPACE_ID.toString(),
            "TRANSCODE_WORKER_SOURCE_ROOT", "/media");

    var settings = TranscodeWorkerSettings.fromEnvironment(environment);

    assertThat(settings.controlPlaneHost()).isEqualTo("127.0.0.1");
    assertThat(settings.controlPlanePort()).isEqualTo(9090);
  }

  @Test
  @DisplayName(
      "Should map required environment values with conservative defaults when loading settings")
  void shouldMapRequiredEnvironmentValuesWithConservativeDefaultsWhenLoadingSettings() {
    var settings = TranscodeWorkerSettings.fromEnvironment(requiredEnvironment());
    var worker = settings.workerConfiguration();

    assertThat(settings.controlPlaneHost()).isEqualTo("streamarr-server");
    assertThat(settings.controlPlanePort()).isEqualTo(9090);
    assertThat(settings.ffmpegPath()).isEqualTo("ffmpeg");
    assertThat(worker.workerId()).isEqualTo(WORKER_ID);
    assertThat(worker.availableSlots()).isEqualTo(1);
    assertThat(worker.sourceNamespaces()).containsEntry(SOURCE_NAMESPACE_ID, Path.of("/media"));
  }

  @Test
  @DisplayName("Should assign a fresh boot identity when settings are loaded again")
  void shouldAssignFreshBootIdentityWhenSettingsAreLoadedAgain() {
    var first = TranscodeWorkerSettings.fromEnvironment(requiredEnvironment());
    var second = TranscodeWorkerSettings.fromEnvironment(requiredEnvironment());

    assertThat(first.workerConfiguration().workerId())
        .isEqualTo(second.workerConfiguration().workerId());
    assertThat(first.workerConfiguration().bootId())
        .isNotEqualTo(second.workerConfiguration().bootId());
  }

  @Test
  @DisplayName("Should distinguish a missing UUID from an invalid UUID when loading settings")
  void shouldDistinguishMissingUuidFromInvalidUuidWhenLoadingSettings() {
    var environment = new HashMap<>(requiredEnvironment());
    environment.remove("TRANSCODE_WORKER_SOURCE_NAMESPACE_ID");

    assertThatThrownBy(() -> TranscodeWorkerSettings.fromEnvironment(environment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("TRANSCODE_WORKER_SOURCE_NAMESPACE_ID is required");
  }

  @Test
  @DisplayName("Should reject a non-positive worker slot count when loading settings")
  void shouldRejectNonPositiveWorkerSlotCountWhenLoadingSettings() {
    var environment = new HashMap<>(requiredEnvironment());
    environment.put("TRANSCODE_WORKER_SLOTS", "0");

    assertThatThrownBy(() -> TranscodeWorkerSettings.fromEnvironment(environment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("TRANSCODE_WORKER_SLOTS must be positive");
  }

  @Test
  @DisplayName("Should reject a worker configuration without capacity when loading settings")
  void shouldRejectWorkerConfigurationWithoutCapacityWhenLoadingSettings() {
    var configuration =
        TranscodeWorkerConfiguration.builder()
            .workerId(UUID.randomUUID())
            .bootId(UUID.randomUUID())
            .availableSlots(0)
            .sourceNamespaces(Map.of(SOURCE_NAMESPACE_ID, Path.of("/media")));

    assertThatThrownBy(configuration::build)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Available slots must be positive");
  }

  @Test
  @DisplayName("Should map explicit worker process settings when loading settings")
  void shouldMapExplicitWorkerProcessSettingsWhenLoadingSettings() {
    var environment = new HashMap<>(requiredEnvironment());
    environment.put("TRANSCODE_WORKER_CONTROL_PLANE_PORT", "65535");
    environment.put("TRANSCODE_WORKER_SLOTS", "2");
    environment.put("TRANSCODE_WORKER_FFMPEG_PATH", "/usr/local/bin/ffmpeg");

    var settings = TranscodeWorkerSettings.fromEnvironment(environment);

    assertThat(settings.controlPlanePort()).isEqualTo(65_535);
    assertThat(settings.ffmpegPath()).isEqualTo("/usr/local/bin/ffmpeg");
    assertThat(settings.workerConfiguration().availableSlots()).isEqualTo(2);
  }

  @Test
  @DisplayName("Should explain invalid worker process settings when loading settings")
  void shouldExplainInvalidWorkerProcessSettingsWhenLoadingSettings() {
    assertInvalidSetting("TRANSCODE_WORKER_ID", "not-a-uuid", "TRANSCODE_WORKER_ID must be a UUID");
    assertInvalidSetting(
        "TRANSCODE_WORKER_CONTROL_PLANE_PORT",
        "65536",
        "TRANSCODE_WORKER_CONTROL_PLANE_PORT must not exceed 65535");
    assertInvalidSetting(
        "TRANSCODE_WORKER_SLOTS", "two", "TRANSCODE_WORKER_SLOTS must be an integer");
  }

  @Test
  @DisplayName("Should default to ffprobe on PATH when its executable is not configured")
  void shouldDefaultToFfprobeOnPathWhenItsExecutableIsNotConfigured() {
    var settings = TranscodeWorkerSettings.fromEnvironment(requiredEnvironment());

    assertThat(settings.ffprobePath()).isEqualTo("ffprobe");
  }

  @Test
  @DisplayName("Should use the configured ffprobe executable when loading settings")
  void shouldUseTheConfiguredFfprobeExecutableWhenLoadingSettings() {
    var environment = new HashMap<>(requiredEnvironment());
    environment.put("TRANSCODE_WORKER_FFPROBE_PATH", "/usr/local/bin/ffprobe");

    var settings = TranscodeWorkerSettings.fromEnvironment(environment);

    assertThat(settings.ffprobePath()).isEqualTo("/usr/local/bin/ffprobe");
  }

  @Test
  @DisplayName("Should target one-second fragments when the fragmentation target is not configured")
  void shouldTargetOneSecondFragmentsWhenTheFragmentationTargetIsNotConfigured() {
    var settings = TranscodeWorkerSettings.fromEnvironment(requiredEnvironment());

    assertThat(settings.fragmentationTarget()).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  @DisplayName("Should use the configured fragmentation target when loading settings")
  void shouldUseTheConfiguredFragmentationTargetWhenLoadingSettings() {
    var environment = new HashMap<>(requiredEnvironment());
    environment.put("TRANSCODE_WORKER_FRAGMENTATION_TARGET", "250ms");

    var settings = TranscodeWorkerSettings.fromEnvironment(environment);

    assertThat(settings.fragmentationTarget()).isEqualTo(Duration.ofMillis(250));
  }

  @ParameterizedTest
  @ValueSource(strings = {"0s", "-1s", "500ns"})
  @DisplayName(
      "Should reject a fragmentation target shorter than a microsecond when loading settings")
  void shouldRejectAFragmentationTargetShorterThanAMicrosecondWhenLoadingSettings(String target) {
    assertInvalidSetting(
        "TRANSCODE_WORKER_FRAGMENTATION_TARGET",
        target,
        "TRANSCODE_WORKER_FRAGMENTATION_TARGET must be at least 1 microsecond");
  }

  @Test
  @DisplayName("Should explain a fragmentation target that is not a duration when loading settings")
  void shouldExplainAFragmentationTargetThatIsNotADurationWhenLoadingSettings() {
    assertInvalidSetting(
        "TRANSCODE_WORKER_FRAGMENTATION_TARGET",
        "soon",
        "TRANSCODE_WORKER_FRAGMENTATION_TARGET must be a duration such as 1s or 500ms");
  }

  private void assertInvalidSetting(String key, String value, String expectedMessage) {
    var environment = new HashMap<>(requiredEnvironment());
    environment.put(key, value);

    assertThatThrownBy(() -> TranscodeWorkerSettings.fromEnvironment(environment))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(expectedMessage);
  }

  private Map<String, String> requiredEnvironment() {
    return Map.of(
        "TRANSCODE_WORKER_CONTROL_PLANE_HOST",
        "streamarr-server",
        "TRANSCODE_WORKER_ID",
        WORKER_ID.toString(),
        "TRANSCODE_WORKER_SOURCE_NAMESPACE_ID",
        SOURCE_NAMESPACE_ID.toString(),
        "TRANSCODE_WORKER_SOURCE_ROOT",
        "/media");
  }
}
