package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;
import org.springframework.core.io.support.SpringFactoriesLoader.FailureHandler;

@Tag("UnitTest")
@DisplayName("Session Acceptance Timeout Failure Analyzer Tests")
class SessionAcceptanceTimeoutFailureAnalyzerTest {

  @Test
  @DisplayName(
      "Should name the server and its settings when startup times out awaiting the session")
  void shouldNameTheServerAndItsSettingsWhenStartupTimesOutAwaitingTheSession() {
    var timeout =
        new SessionAcceptanceTimeoutException("streamarr-server", 9090, Duration.ofMinutes(5));
    // Spring Boot wraps a failed ApplicationRunner before its registered analyzers read it.
    var startupFailure = new IllegalStateException("Failed to execute ApplicationRunner", timeout);

    var analysis = analyzeAsSpringBootStartupDoes(startupFailure);

    assertThat(analysis.getDescription()).contains("streamarr-server:9090").contains("5m");
    assertThat(analysis.getAction())
        .contains("TRANSCODE_WORKER_CONTROL_PLANE_HOST")
        .contains("TRANSCODE_WORKER_CONTROL_PLANE_PORT")
        .contains("TRANSCODE_WORKER_SESSION_ACCEPTANCE_TIMEOUT");
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "PT5M, 5m",
    "PT1M30S, 90s",
    "PT1.5S, 1500ms",
    "PT0.0005S, 500us",
    "PT0.000000001S, 1ns"
  })
  @DisplayName("Should state the bound as a duration setting when startup times out")
  void shouldStateTheBoundAsADurationSettingWhenStartupTimesOut(
      Duration bound, String settingValue) {
    var timeout = new SessionAcceptanceTimeoutException("127.0.0.1", 9090, bound);

    var analysis = analyzeAsSpringBootStartupDoes(timeout);

    assertThat(analysis.getDescription()).endsWith(" within " + settingValue + ".");
  }

  // Analyzers that need the application context cannot be built here and are skipped; none of them
  // handles this failure.
  private static FailureAnalysis analyzeAsSpringBootStartupDoes(Throwable failure) {
    var analyzers =
        SpringFactoriesLoader.forDefaultResourceLocation()
            .load(
                FailureAnalyzer.class,
                ArgumentResolver.none(),
                FailureHandler.handleMessage((_, _) -> {}));
    var analysis =
        analyzers.stream()
            .map(analyzer -> analyzer.analyze(failure))
            .filter(Objects::nonNull)
            .findFirst();

    assertThat(analysis).as("a registered analyzer explains the failure").isPresent();
    return analysis.get();
  }
}
