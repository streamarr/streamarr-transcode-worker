package com.streamarr.transcode.engine;

import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.NonNull;

/**
 * How an attempt's NVENC encoder failed, read from FFmpeg's error output (ADR 0044). A refusal at
 * the driver's session limit sends only that job to the software encoder. Any other NVENC or CUDA
 * error means that the encoder failed, so the worker stops offering it until its test encode passes
 * again.
 */
public enum HardwareEncoderFault {
  /** The driver refused to open another encode session, such as at its session limit. */
  SESSION_REFUSED,
  /** The encoder or the device failed, such as after a driver reset. */
  FAILED;

  // FFmpeg's nvenc.c names the NVENC status after the call that failed. A consumer GPU at its
  // session limit answers OpenEncodeSessionEx with NV_ENC_ERR_OUT_OF_MEMORY (10) or
  // NV_ENC_ERR_INCOMPATIBLE_CLIENT_KEY (21).
  private static final Pattern SESSION_REFUSAL =
      Pattern.compile(
          "OpenEncodeSessionEx failed: (?:out of memory \\(10\\)|incompatible client key \\(21\\))");
  private static final Pattern NO_CAPABLE_DEVICE = Pattern.compile("No capable devices found");
  private static final Pattern NVENC_ERROR =
      Pattern.compile(
          "\\[\\w+_nvenc @ [^\\]]*\\][^\\n]*(?:failed|error)|CUDA_ERROR_\\w+|No capable devices found|cu\\w+ failed",
          Pattern.CASE_INSENSITIVE);

  /**
   * How the attempt's hardware encoder failed; empty when the attempt ran no NVENC encoder or
   * failed for another reason.
   */
  public static Optional<HardwareEncoderFault> of(
      @NonNull EncoderBackend backend, @NonNull AttemptOutcome outcome) {
    if (!backend.hardware()
        || !backend.encoder().endsWith("_nvenc")
        || !(outcome instanceof AttemptOutcome.Failed(var reason, var detail))
        || reason != ProducerFailure.PROCESS_EXITED_WITH_ERROR) {
      return Optional.empty();
    }

    if (NVENC_ERROR.matcher(withoutSessionRefusals(detail)).find()) {
      return Optional.of(FAILED);
    }

    if (SESSION_REFUSAL.matcher(detail).find()) {
      return Optional.of(SESSION_REFUSED);
    }

    return Optional.empty();
  }

  // FFmpeg tries each GPU in turn and, once none opened a session, adds "No capable devices
  // found". Neither line means that an encoder failed, but any other NVENC or CUDA error does, even
  // after another GPU refused its session.
  private static String withoutSessionRefusals(String detail) {
    if (!SESSION_REFUSAL.matcher(detail).find()) {
      return detail;
    }

    return detail
        .lines()
        .filter(line -> !SESSION_REFUSAL.matcher(line).find())
        .filter(line -> !NO_CAPABLE_DEVICE.matcher(line).find())
        .collect(Collectors.joining("\n"));
  }
}
