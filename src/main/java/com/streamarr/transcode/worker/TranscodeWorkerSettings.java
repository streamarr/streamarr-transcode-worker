package com.streamarr.transcode.worker;

import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.Builder;

@Builder
record TranscodeWorkerSettings(
    String controlPlaneHost,
    int controlPlanePort,
    String ffmpegPath,
    String ffprobePath,
    Duration fragmentationTarget,
    Duration encoderStallTimeout,
    TranscodeWorkerConfiguration workerConfiguration) {

  private static final String PREFIX = "TRANSCODE_WORKER_";
  private static final String DEFAULT_FRAGMENTATION_TARGET = "1s";
  private static final Pattern DURATION = Pattern.compile("(-?\\d+)(ns|us|ms|s|m|h)");
  private static final Map<String, ChronoUnit> DURATION_UNITS =
      Map.of(
          "ns", ChronoUnit.NANOS,
          "us", ChronoUnit.MICROS,
          "ms", ChronoUnit.MILLIS,
          "s", ChronoUnit.SECONDS,
          "m", ChronoUnit.MINUTES,
          "h", ChronoUnit.HOURS);

  static TranscodeWorkerSettings fromEnvironment(Map<String, String> environment) {
    var sourceNamespaceId = uuid(environment, PREFIX + "SOURCE_NAMESPACE_ID");
    var workerConfiguration =
        TranscodeWorkerConfiguration.builder()
            .workerId(uuid(environment, PREFIX + "ID"))
            .bootId(UUID.randomUUID())
            .availableSlots(positiveInteger(environment, PREFIX + "SLOTS", 1))
            .sourceNamespaces(Map.of(sourceNamespaceId, path(environment, PREFIX + "SOURCE_ROOT")))
            .uploadReadinessTimeout(
                positiveDuration(
                    environment,
                    PREFIX + "UPLOAD_READINESS_TIMEOUT",
                    TranscodeWorkerConfiguration.DEFAULT_UPLOAD_READINESS_TIMEOUT))
            .uploadAcknowledgementTimeout(
                positiveDuration(
                    environment,
                    PREFIX + "UPLOAD_ACKNOWLEDGEMENT_TIMEOUT",
                    TranscodeWorkerConfiguration.DEFAULT_UPLOAD_ACKNOWLEDGEMENT_TIMEOUT))
            .build();
    return TranscodeWorkerSettings.builder()
        .controlPlaneHost(optional(environment, PREFIX + "CONTROL_PLANE_HOST", "127.0.0.1"))
        .controlPlanePort(port(environment, PREFIX + "CONTROL_PLANE_PORT", 9090))
        .ffmpegPath(optional(environment, PREFIX + "FFMPEG_PATH", "ffmpeg"))
        .ffprobePath(optional(environment, PREFIX + "FFPROBE_PATH", "ffprobe"))
        .fragmentationTarget(fragmentationTarget(environment, PREFIX + "FRAGMENTATION_TARGET"))
        .encoderStallTimeout(
            positiveDuration(
                environment,
                PREFIX + "ENCODER_STALL_TIMEOUT",
                FfmpegTranscodeEngine.DEFAULT_ENCODER_STALL_TIMEOUT))
        .workerConfiguration(workerConfiguration)
        .build();
  }

  private static String required(Map<String, String> environment, String key) {
    var value = environment.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(key + " is required");
    }
    return value;
  }

  private static String optional(Map<String, String> environment, String key, String defaultValue) {
    var value = environment.get(key);
    return value == null || value.isBlank() ? defaultValue : value;
  }

  private static Path path(Map<String, String> environment, String key) {
    return Path.of(required(environment, key));
  }

  private static UUID uuid(Map<String, String> environment, String key) {
    var value = required(environment, key);
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(key + " must be a UUID", e);
    }
  }

  // FFmpeg reads the target in whole microseconds, and treats zero as no target.
  private static Duration fragmentationTarget(Map<String, String> environment, String key) {
    var target =
        duration(environment, key, DEFAULT_FRAGMENTATION_TARGET).truncatedTo(ChronoUnit.MICROS);
    if (!target.isPositive()) {
      throw new IllegalArgumentException(key + " must be at least 1 microsecond");
    }

    return target;
  }

  private static Duration positiveDuration(
      Map<String, String> environment, String key, Duration defaultValue) {
    var configured = environment.get(key);
    if (configured == null || configured.isBlank()) {
      return defaultValue;
    }

    var value = parseDuration(key, configured);
    if (!value.isPositive()) {
      throw new IllegalArgumentException(key + " must be positive");
    }

    return value;
  }

  private static Duration duration(
      Map<String, String> environment, String key, String defaultValue) {
    return parseDuration(key, optional(environment, key, defaultValue));
  }

  // A whole number of one unit, from nanoseconds to hours, such as 1s or 500ms.
  private static Duration parseDuration(String key, String text) {
    var matcher = DURATION.matcher(text);
    if (!matcher.matches()) {
      throw invalidDuration(key);
    }

    try {
      return Duration.of(Long.parseLong(matcher.group(1)), DURATION_UNITS.get(matcher.group(2)));
    } catch (NumberFormatException | ArithmeticException _) {
      throw invalidDuration(key);
    }
  }

  private static IllegalArgumentException invalidDuration(String key) {
    return new IllegalArgumentException(key + " must be a duration such as 1s or 500ms");
  }

  private static int positiveInteger(
      Map<String, String> environment, String key, int defaultValue) {
    var value = integer(environment, key, defaultValue);
    if (value < 1) {
      throw new IllegalArgumentException(key + " must be positive");
    }
    return value;
  }

  private static int port(Map<String, String> environment, String key, int defaultValue) {
    var value = positiveInteger(environment, key, defaultValue);
    if (value > 65_535) {
      throw new IllegalArgumentException(key + " must not exceed 65535");
    }
    return value;
  }

  private static int integer(Map<String, String> environment, String key, int defaultValue) {
    try {
      return Integer.parseInt(optional(environment, key, String.valueOf(defaultValue)));
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(key + " must be an integer", e);
    }
  }
}
