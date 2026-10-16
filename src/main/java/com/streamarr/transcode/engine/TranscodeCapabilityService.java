package com.streamarr.transcode.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

@Slf4j
public class TranscodeCapabilityService {

  private static final List<String> REQUIRED_MP4_MUXER_OPTIONS =
      Stream.concat(Stream.of("-frag_duration"), FfmpegCommandBuilder.MP4_MOVFLAGS.stream())
          .toList();

  private static final Pattern HW_ENCODER_PATTERN =
      Pattern.compile("^\\s*V\\S+\\s+(\\w+_(?:nvenc|qsv|amf|vaapi|videotoolbox))\\s+");

  private final String ffmpegPath;
  private final ProcessFactory processFactory;
  @Getter private boolean ffmpegAvailable;
  @Getter private String unavailableReason = "FFmpeg not found";

  @Getter
  private HardwareEncodingCapability hardwareEncodingCapability =
      HardwareEncodingCapability.builder().available(false).encoders(Set.of()).build();

  // Each codec family's worker-verified encoder backend, resolved once after detection, so that
  // every job of a codec family runs the backend that registration advertises.
  private Map<CodecFamily, EncoderBackend> encoderBackends = Map.of();

  public TranscodeCapabilityService(String ffmpegPath, ProcessFactory processFactory) {
    this.ffmpegPath = ffmpegPath;
    this.processFactory = processFactory;
  }

  public void detectCapabilities() {
    var versionProbe = checkFfmpegVersion();
    if (!versionProbe.successful()) {
      markUnavailable(versionProbe.unavailableReason());
      return;
    }

    var mp4MuxerProbe = probeRequiredMp4MuxerOptions();
    if (!mp4MuxerProbe.successful()) {
      markUnavailable(mp4MuxerProbe.unavailableReason());
      return;
    }

    ffmpegAvailable = true;
    unavailableReason = "";
    var hwEncoders = detectHardwareEncoders();
    var hasGpu = !hwEncoders.isEmpty();
    var accelerator = hasGpu ? detectAccelerator() : "";

    hardwareEncodingCapability =
        HardwareEncodingCapability.builder()
            .available(hasGpu)
            .encoders(hwEncoders)
            .accelerator(accelerator)
            .build();
    encoderBackends = resolveEncoderBackends(hwEncoders, detectSoftwareEncoders());

    log.info(
        "FFmpeg capabilities: GPU={}, encoders={}, accelerator={}, encoder backends={}",
        hasGpu,
        hwEncoders,
        accelerator,
        encoderBackends.values());
  }

  private void markUnavailable(String reason) {
    ffmpegAvailable = false;
    unavailableReason = reason;
    log.warn("FFmpeg capability detection failed for {}: {}", ffmpegPath, reason);
  }

  /**
   * The encoder backend that jobs of each codec family run, for every codec family whose backend
   * passed its test encode, with its keyframe-verified trait.
   */
  public List<WorkerVerifiedEncoder> workerVerifiedEncoders() {
    return encoderBackends.values().stream()
        .map(
            backend ->
                WorkerVerifiedEncoder.builder()
                    .backend(backend)
                    .keyframeVerified(FfmpegCommandBuilder.isKeyframeVerified(backend.encoder()))
                    .build())
        .toList();
  }

  /**
   * The advertised encoder backend that jobs of the codec family run; empty when the worker
   * advertises no encoder for the codec family.
   */
  public Optional<EncoderBackend> resolveEncoder(String codecFamily) {
    return CodecFamily.named(codecFamily).map(encoderBackends::get);
  }

  private static Map<CodecFamily, EncoderBackend> resolveEncoderBackends(
      Set<String> hardwareEncoders, Set<CodecFamily> softwareEncoderFamilies) {
    var backends = new EnumMap<CodecFamily, EncoderBackend>(CodecFamily.class);
    for (var family : CodecFamily.values()) {
      hardwareBackend(family, hardwareEncoders)
          .or(() -> softwareBackendIfVerified(family, softwareEncoderFamilies))
          .ifPresent(backend -> backends.put(family, backend));
    }

    return Collections.unmodifiableMap(backends);
  }

  private static Optional<EncoderBackend> hardwareBackend(
      CodecFamily family, Set<String> hardwareEncoders) {
    // The iteration order of an immutable Set changes between JVM runs. Sorting by name keeps the
    // advertised backend the same across worker restarts until a hardware preference order exists.
    return hardwareEncoders.stream()
        .filter(family::isHardwareEncoderOfThisFamily)
        // The producer fails an attempt whose output differs from the job's codec profile, tier
        // and level, so a hardware encoder runs only once the command forces them on it.
        .filter(FfmpegCommandBuilder::forcesCodecProfile)
        .sorted()
        .findFirst()
        .map(
            encoder ->
                EncoderBackend.builder()
                    .codecFamily(family)
                    .encoder(encoder)
                    .hardware(true)
                    .build());
  }

  private static Optional<EncoderBackend> softwareBackendIfVerified(
      CodecFamily family, Set<CodecFamily> softwareEncoderFamilies) {
    if (!softwareEncoderFamilies.contains(family)) {
      return Optional.empty();
    }

    return Optional.of(softwareBackend(family));
  }

  private static EncoderBackend softwareBackend(CodecFamily family) {
    return EncoderBackend.builder()
        .codecFamily(family)
        .encoder(family.softwareEncoder())
        .hardware(false)
        .build();
  }

  private CapabilityProbeResult checkFfmpegVersion() {
    try {
      var process = processFactory.start(ffmpegReadingExitStatusOnly("-version"));
      var exitCode = process.waitFor();
      return exitCode == 0
          ? CapabilityProbeResult.success()
          : CapabilityProbeResult.failure("FFmpeg version probe failed");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("FFmpeg version check interrupted", e);
      return CapabilityProbeResult.failure("FFmpeg version probe interrupted");
    } catch (IOException e) {
      log.debug("FFmpeg executable not found", e);
      return CapabilityProbeResult.failure("FFmpeg not found");
    } catch (Exception e) {
      log.debug("FFmpeg version check failed", e);
      return CapabilityProbeResult.failure("FFmpeg version probe failed");
    }
  }

  private CapabilityProbeResult probeRequiredMp4MuxerOptions() {
    try {
      var process =
          processFactory.start(ffmpegReadingStandardOutput("-hide_banner", "-h", "muxer=mp4"));
      var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      if (process.waitFor() != 0) {
        return CapabilityProbeResult.failure("FFmpeg mp4 muxer capability probe failed");
      }

      var missingOptions =
          REQUIRED_MP4_MUXER_OPTIONS.stream().filter(option -> !output.contains(option)).toList();
      if (!missingOptions.isEmpty()) {
        return CapabilityProbeResult.failure(
            "Missing mp4 muxer options: " + String.join(", ", missingOptions));
      }

      return CapabilityProbeResult.success();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("FFmpeg mp4 muxer capability check interrupted", e);
      return CapabilityProbeResult.failure("FFmpeg mp4 muxer capability probe interrupted");
    } catch (Exception e) {
      log.debug("FFmpeg mp4 muxer capability check failed", e);
      return CapabilityProbeResult.failure("FFmpeg mp4 muxer capability probe failed");
    }
  }

  private record CapabilityProbeResult(boolean successful, String unavailableReason) {

    private static CapabilityProbeResult success() {
      return new CapabilityProbeResult(true, "");
    }

    private static CapabilityProbeResult failure(String reason) {
      return new CapabilityProbeResult(false, reason);
    }
  }

  private Set<String> detectHardwareEncoders() {
    try {
      var process = processFactory.start(ffmpegReadingStandardOutput("-encoders"));
      var encoders = new HashSet<String>();

      try (var reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          var matcher = HW_ENCODER_PATTERN.matcher(line);
          if (matcher.find()) {
            encoders.add(matcher.group(1));
          }
        }
      }

      process.waitFor();
      var usableEncoders =
          encoders.stream()
              .filter(this::canEncodeCpuFrames)
              .collect(Collectors.toUnmodifiableSet());
      if (Thread.currentThread().isInterrupted()) {
        log.debug("Hardware encoder detection interrupted");
        return Set.of();
      }

      return usableEncoders;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("Hardware encoder detection interrupted", e);
      return Set.of();
    } catch (Exception e) {
      log.debug("Hardware encoder detection failed", e);
      return Set.of();
    }
  }

  // The codec families whose software encoder passes the same test encode as a hardware encoder.
  private Set<CodecFamily> detectSoftwareEncoders() {
    var families =
        Arrays.stream(CodecFamily.values())
            .filter(family -> canEncodeCpuFrames(family.softwareEncoder()))
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(CodecFamily.class)));
    if (Thread.currentThread().isInterrupted()) {
      log.debug("Software encoder detection interrupted");
      return Set.of();
    }

    return families;
  }

  // Once the thread is interrupted, a test encode fails without starting FFmpeg, so an interrupted
  // detection starts no further test encode. The caller reads the restored interrupt flag.
  private boolean canEncodeCpuFrames(String encoder) {
    if (Thread.currentThread().isInterrupted()) {
      return false;
    }

    try {
      // Match the command builder's CPU scaling path. Compiled encoders may need unavailable
      // devices.
      var process =
          processFactory.start(
              ffmpegReadingExitStatusOnly(
                  "-nostdin",
                  "-v",
                  "quiet",
                  "-f",
                  "lavfi",
                  "-i",
                  "color=size=320x240:rate=30",
                  "-vf",
                  "scale=-2:240",
                  "-frames:v",
                  "1",
                  "-an",
                  "-c:v",
                  encoder,
                  "-f",
                  "null",
                  "-"));
      try {
        return process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0;
      } finally {
        terminateValidation(process);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("Encoder validation interrupted for {}", encoder, e);
      return false;
    } catch (Exception e) {
      log.debug("Encoder validation failed for {}", encoder, e);
      return false;
    }
  }

  private void terminateValidation(Process process) throws InterruptedException {
    if (!process.isAlive()) {
      return;
    }

    process.destroyForcibly();
    if (!process.waitFor(1, TimeUnit.SECONDS)) {
      log.warn("Encoder validation did not exit after termination");
    }
  }

  private String detectAccelerator() {
    try {
      var process = processFactory.start(ffmpegReadingStandardOutput("-hwaccels"));
      var result = getStringBuilder(process);

      process.waitFor();
      return result.toString();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("Accelerator detection interrupted", e);
      return "";
    } catch (Exception e) {
      log.debug("Accelerator detection failed", e);
      return "";
    }
  }

  private static @NonNull StringBuilder getStringBuilder(Process process) throws IOException {
    var result = new StringBuilder();

    try (var reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        var trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("Hardware")) {
          continue;
        }

        if (!result.isEmpty()) {
          result.append(",");
        }

        result.append(trimmed);
      }
    }

    return result;
  }

  // FFmpeg blocks on a write to a full pipe, so every stream the check does not read is discarded.
  private ProcessBuilder ffmpegReadingStandardOutput(String... arguments) {
    return ffmpeg(arguments).redirectError(Redirect.DISCARD);
  }

  private ProcessBuilder ffmpegReadingExitStatusOnly(String... arguments) {
    return ffmpeg(arguments).redirectOutput(Redirect.DISCARD).redirectError(Redirect.DISCARD);
  }

  private ProcessBuilder ffmpeg(String... arguments) {
    var command = new ArrayList<String>();
    command.add(ffmpegPath);
    command.addAll(Arrays.asList(arguments));
    return new ProcessBuilder(command);
  }

  @FunctionalInterface
  public interface ProcessFactory {
    Process start(ProcessBuilder command) throws IOException;
  }
}
