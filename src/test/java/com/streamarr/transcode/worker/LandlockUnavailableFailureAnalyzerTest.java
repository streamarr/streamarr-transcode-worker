package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.core.io.support.SpringFactoriesLoader.ArgumentResolver;
import org.springframework.core.io.support.SpringFactoriesLoader.FailureHandler;

@Tag("UnitTest")
@DisplayName("Landlock Unavailable Failure Analyzer Tests")
class LandlockUnavailableFailureAnalyzerTest {

  @Test
  @DisplayName("Should state the reason and the host fix when startup finds no Landlock")
  void shouldStateTheReasonAndTheHostFixWhenStartupFindsNoLandlock() {
    var unavailable =
        new LandlockUnavailableException(
            "the kernel offers no Landlock (ENOSYS)", "Run the worker on Linux 5.13 or later.");
    // Spring Boot wraps a failed bean before its registered analyzers read it.
    var startupFailure =
        new BeanCreationException("transcodeWorker", "Instantiation failed", unavailable);

    var analysis = analyzeAsSpringBootStartupDoes(startupFailure);

    assertThat(analysis.getDescription())
        .isEqualTo(
            "The worker cannot confine FFmpeg with Landlock because the kernel offers no Landlock"
                + " (ENOSYS).");
    assertThat(analysis.getAction()).isEqualTo("Run the worker on Linux 5.13 or later.");
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
