package com.streamarr.transcode.engine;

import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.NonNull;

/**
 * How an attempt's NVENC encoder failed, read from FFmpeg's error output (ADR 0044). A refusal at
 * the driver's session limit, or of the source by the hardware decoder, sends only that job to the
 * software encoder, as does a GPU whose memory cannot hold the job's frames. Any other NVENC or
 * CUDA error means that the encoder failed, so the worker stops offering it until its test encode
 * passes again.
 */
public enum HardwareEncoderFault {
  /** The driver refused to open another encode session, such as at its session limit. */
  SESSION_REFUSED,
  /** The encoder or the device failed, such as after a driver reset. */
  FAILED,
  /**
   * The hardware decoder refused the source, such as a picture smaller than it decodes. FFmpeg then
   * decodes in software, and the filters that take only GPU frames fail.
   */
  DECODER_REFUSED,
  /** The GPU's memory could not hold the frames of the job. */
  DEVICE_OUT_OF_MEMORY;

  // FFmpeg's nvenc.c names the NVENC status after the call that failed. A consumer GPU at its
  // session limit answers OpenEncodeSessionEx with NV_ENC_ERR_OUT_OF_MEMORY (10) or
  // NV_ENC_ERR_INCOMPATIBLE_CLIENT_KEY (21).
  private static final Pattern SESSION_REFUSAL =
      Pattern.compile(
          "OpenEncodeSessionEx failed: (?:out of memory \\(10\\)|incompatible client key \\(21\\))");
  private static final Pattern NO_CAPABLE_DEVICE = Pattern.compile("No capable devices found");
  // A busy GPU, not a broken one: the frames of a job that decodes and filters on the GPU did not
  // fit beside the other streams' frames.
  private static final Pattern OUT_OF_DEVICE_MEMORY = Pattern.compile("CUDA_ERROR_OUT_OF_MEMORY");
  private static final Pattern NVENC_ERROR =
      Pattern.compile(
          "\\[\\w+_nvenc @ [^\\]]*\\][^\\n]*(?:failed|error)|CUDA_ERROR_\\w+|No capable devices found|cu\\w+ failed",
          Pattern.CASE_INSENSITIVE);

  // FFmpeg decodes in software once NVDEC refuses the source, and names the pixel formats that
  // the first GPU filter takes when it cannot hand that filter the frames: only cuda.
  private static final Pattern GPU_FILTERS_GOT_NO_GPU_FRAME =
      Pattern.compile("dst: cuda\\R[^\\n]*Error reinitializing filters");

  /**
   * How the attempt's hardware encoder failed; empty when the attempt ran no NVENC encoder or
   * failed for another reason.
   */
  public static Optional<HardwareEncoderFault> of(
      @NonNull EncoderBackend backend, @NonNull AttemptOutcome outcome) {
    if (!backend.hardware()
        || !backend.encoder().endsWith("_nvenc")
        || !(outcome instanceof AttemptOutcome.Failed(var reason, _, var errorOutput))
        || reason != ProducerFailure.PROCESS_EXITED_WITH_ERROR) {
      return Optional.empty();
    }

    var diagnostics = diagnosticLines(errorOutput);
    if (NVENC_ERROR.matcher(withoutBusyDeviceLines(diagnostics)).find()) {
      return Optional.of(FAILED);
    }

    if (OUT_OF_DEVICE_MEMORY.matcher(diagnostics).find()) {
      return Optional.of(DEVICE_OUT_OF_MEMORY);
    }

    if (SESSION_REFUSAL.matcher(diagnostics).find()) {
      return Optional.of(SESSION_REFUSED);
    }

    if (GPU_FILTERS_GOT_NO_GPU_FRAME.matcher(errorOutput).find()) {
      return Optional.of(DECODER_REFUSED);
    }

    return Optional.empty();
  }

  // FFmpeg starts each line that a component such as NVENC, NVDEC or a CUDA filter logs with that
  // component's name in brackets. The source's path and metadata, which FFmpeg also prints, start
  // otherwise, so text there that reads like an error is not read as one.
  private static String diagnosticLines(String errorOutput) {
    return errorOutput
        .lines()
        .filter(line -> line.startsWith("["))
        .collect(Collectors.joining("\n"));
  }

  // FFmpeg tries each GPU in turn and, once none opened a session, adds "No capable devices
  // found". Neither line means that an encoder failed, nor does a GPU out of memory, but any other
  // NVENC or CUDA error does, even after another GPU refused its session.
  private static String withoutBusyDeviceLines(String errorOutput) {
    var sessionRefused = SESSION_REFUSAL.matcher(errorOutput).find();
    return errorOutput
        .lines()
        .filter(line -> !SESSION_REFUSAL.matcher(line).find())
        .filter(line -> !OUT_OF_DEVICE_MEMORY.matcher(line).find())
        .filter(line -> !(sessionRefused && NO_CAPABLE_DEVICE.matcher(line).find()))
        .collect(Collectors.joining("\n"));
  }
}
