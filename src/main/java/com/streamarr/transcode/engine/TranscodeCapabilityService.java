package com.streamarr.transcode.engine;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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

  private static final long VALIDATION_DEADLINE_SECONDS = 10;

  private static final Pattern HW_ENCODER_PATTERN =
      Pattern.compile("^\\s*V\\S+\\s+(\\w+_(?:nvenc|qsv|amf|vaapi|videotoolbox))\\s+");

  private final String ffmpegPath;
  private final ProcessFactory processFactory;
  @Getter private boolean ffmpegAvailable;
  @Getter private String unavailableReason = "FFmpeg not found";

  @Getter
  private HardwareEncodingCapability hardwareEncodingCapability =
      HardwareEncodingCapability.builder().available(false).encoders(Set.of()).build();

  // Each codec family's worker-verified encoder, resolved after detection and again whenever a
  // hardware encoder is withdrawn or restored, so that every job of a codec family runs the backend
  // that the worker offers.
  private final AtomicReference<Map<CodecFamily, WorkerVerifiedEncoder>> encoders =
      new AtomicReference<>(Map.of());
  private Set<String> detectedHardwareEncoders = Set.of();
  private Set<CodecFamily> softwareEncoderFamilies = Set.of();
  // The hardware encoders that failed during a stream and have not passed their test encode since
  // (ADR 0044), each with its failures since then, so a recheck that a newer failure overtook does
  // not offer it again. The worker offers the software encoder of their families instead.
  private final Map<String, Long> withdrawnHardwareEncoders = new HashMap<>();

  // The outputs beyond 8-bit 4:2:0 SDR whose own test encode each codec family's backend passed.
  private Map<CodecFamily, Set<EncodeFeature>> encodeFeatures = Map.of();

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
    detectedHardwareEncoders = hwEncoders;
    softwareEncoderFamilies = detectSoftwareEncoders();
    encodeFeatures = detectEncodeFeatures(softwareEncoderFamilies);
    encoders.set(resolveEncoders(hwEncoders, softwareEncoderFamilies));

    log.info(
        "FFmpeg capabilities: GPU={}, encoders={}, accelerator={}, encoder backends={}, encode"
            + " features={}",
        hasGpu,
        hwEncoders,
        accelerator,
        encoders.get().values(),
        encodeFeatures);
  }

  private void markUnavailable(String reason) {
    ffmpegAvailable = false;
    unavailableReason = reason;
    log.warn("FFmpeg capability detection failed for {}: {}", ffmpegPath, reason);
  }

  /**
   * The encoder backend that jobs of each codec family run, for every codec family whose backend
   * passed its test encode, with its keyframe-verified trait and the encode features whose own test
   * encodes it passed.
   */
  public List<WorkerVerifiedEncoder> workerVerifiedEncoders() {
    return List.copyOf(encoders.get().values());
  }

  /**
   * The advertised encoder backend that jobs of the codec family run; empty when the worker
   * advertises no encoder for the codec family.
   */
  public Optional<EncoderBackend> resolveEncoder(String codecFamily) {
    return CodecFamily.named(codecFamily)
        .map(encoders.get()::get)
        .map(WorkerVerifiedEncoder::backend);
  }

  /**
   * The software encoder of each codec family whose software encoder passed its test encode, which
   * a job that asks for the software encoder runs (ADR 0044).
   */
  public List<WorkerVerifiedEncoder> softwareEncoders() {
    return softwareEncoderFamilies.stream()
        .sorted()
        .map(TranscodeCapabilityService::softwareBackend)
        .map(this::workerVerified)
        .toList();
  }

  /** The codec family's software encoder; empty when it did not pass its test encode. */
  public Optional<EncoderBackend> resolveSoftwareEncoder(String codecFamily) {
    return CodecFamily.named(codecFamily)
        .filter(softwareEncoderFamilies::contains)
        .map(TranscodeCapabilityService::softwareBackend);
  }

  /**
   * Stops offering a hardware encoder that failed during a stream, so its codec family resolves to
   * the next usable encoder (ADR 0044). Returns whether the offered encoders changed.
   */
  public synchronized boolean withdraw(EncoderBackend failed) {
    if (!failed.hardware()
        || withdrawnHardwareEncoders.merge(failed.encoder(), 1L, Long::sum) > 1) {
      return false;
    }

    resolveOfferedEncoders();
    return true;
  }

  /** Whether a withdrawn hardware encoder waits for its test encode to pass again. */
  public synchronized boolean hasWithdrawnEncoders() {
    return !withdrawnHardwareEncoders.isEmpty();
  }

  /**
   * Runs the test encode of every withdrawn hardware encoder and offers again each one that passes.
   * Returns whether the offered encoders changed.
   */
  public boolean recheckWithdrawnEncoders() {
    Map<String, Long> withdrawn;
    synchronized (this) {
      withdrawn = Map.copyOf(withdrawnHardwareEncoders);
    }

    var passed =
        withdrawn.entrySet().stream().filter(entry -> canEncodeCpuFrames(entry.getKey())).toList();
    synchronized (this) {
      var offeredAgain =
          passed.stream()
              .filter(entry -> withdrawnHardwareEncoders.remove(entry.getKey(), entry.getValue()))
              .count();
      if (offeredAgain == 0) {
        return false;
      }

      resolveOfferedEncoders();
      return true;
    }
  }

  private void resolveOfferedEncoders() {
    var offered = new HashSet<>(detectedHardwareEncoders);
    offered.removeAll(withdrawnHardwareEncoders.keySet());
    encoders.set(resolveEncoders(offered, softwareEncoderFamilies));
    log.info("Offered encoder backends: {}", encoders.get().values());
  }

  private Map<CodecFamily, WorkerVerifiedEncoder> resolveEncoders(
      Set<String> hardwareEncoders, Set<CodecFamily> softwareEncoderFamilies) {
    var resolved = new EnumMap<CodecFamily, WorkerVerifiedEncoder>(CodecFamily.class);
    for (var family : CodecFamily.values()) {
      usableEncoders(family, hardwareEncoders, softwareEncoderFamilies)
          .min(EncoderPreference.ORDER)
          .ifPresent(encoder -> resolved.put(family, encoder));
    }

    return Collections.unmodifiableMap(resolved);
  }

  private Stream<WorkerVerifiedEncoder> usableEncoders(
      CodecFamily family, Set<String> hardwareEncoders, Set<CodecFamily> softwareEncoderFamilies) {
    var hardware =
        hardwareEncoders.stream()
            .filter(family::isHardwareEncoderOfThisFamily)
            // The producer fails an attempt whose output differs from the job's codec profile,
            // tier and level, so a hardware encoder runs only once the command forces them on it.
            .filter(FfmpegCommandBuilder::forcesCodecProfile)
            .map(encoder -> hardwareBackend(family, encoder));
    var software =
        Stream.of(family)
            .filter(softwareEncoderFamilies::contains)
            .map(TranscodeCapabilityService::softwareBackend);
    return Stream.concat(hardware, software).map(this::workerVerified);
  }

  private static EncoderBackend hardwareBackend(CodecFamily family, String encoder) {
    return EncoderBackend.builder().codecFamily(family).encoder(encoder).hardware(true).build();
  }

  private static EncoderBackend softwareBackend(CodecFamily family) {
    return EncoderBackend.builder()
        .codecFamily(family)
        .encoder(family.softwareEncoder())
        .hardware(false)
        .build();
  }

  // Only a software backend lists encode features: the feature test commands model the software
  // filter chain.
  private WorkerVerifiedEncoder workerVerified(EncoderBackend backend) {
    var features =
        backend.hardware()
            ? Set.<EncodeFeature>of()
            : encodeFeatures.getOrDefault(backend.codecFamily(), Set.of());
    return WorkerVerifiedEncoder.builder()
        .backend(backend)
        .keyframeVerified(FfmpegCommandBuilder.isKeyframeVerified(backend.encoder()))
        .encodeFeatures(features)
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
      var hardwareEncoders = new HashSet<String>();

      try (var reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          var matcher = HW_ENCODER_PATTERN.matcher(line);
          if (matcher.find()) {
            hardwareEncoders.add(matcher.group(1));
          }
        }
      }

      process.waitFor();
      var usableEncoders =
          hardwareEncoders.stream()
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
      // Match the command builder's CPU scaling path and the B-frame and lookahead options every
      // encode passes: an encoder opens only if the GPU supports them, and compiled encoders may
      // need unavailable devices.
      var arguments =
          new ArrayList<>(
              List.of(
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
                  encoder));
      arguments.addAll(FfmpegCommandBuilder.frameTypeOptions(encoder));
      arguments.addAll(List.of("-f", "null", "-"));
      var process =
          processFactory.start(ffmpegReadingExitStatusOnly(arguments.toArray(String[]::new)));
      try {
        return process.waitFor(VALIDATION_DEADLINE_SECONDS, TimeUnit.SECONDS)
            && process.exitValue() == 0;
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

  // Only a software HEVC or AV1 encoder runs the feature test encodes: the test commands model the
  // software filter chain, and H.264 encodes only 8-bit SDR. Each runs them whether or not its
  // family offers a hardware encoder, which a failure during a stream can withdraw (ADR 0044).
  private Map<CodecFamily, Set<EncodeFeature>> detectEncodeFeatures(
      Set<CodecFamily> softwareFamilies) {
    var testedBackends =
        softwareFamilies.stream()
            .filter(family -> family != CodecFamily.H264)
            .sorted()
            .map(TranscodeCapabilityService::softwareBackend)
            .toList();
    if (testedBackends.isEmpty()) {
      return Map.of();
    }

    try (var dolbyVisionFrame = SampleDolbyVisionFrame.extract()) {
      var features = new EnumMap<CodecFamily, Set<EncodeFeature>>(CodecFamily.class);
      for (var backend : testedBackends) {
        features.put(backend.codecFamily(), detectEncodeFeatures(backend, dolbyVisionFrame.path()));
      }

      return Collections.unmodifiableMap(features);
    } catch (IOException e) {
      log.warn("Encode feature detection could not extract its Dolby Vision test frame", e);
      return Map.of();
    }
  }

  private Set<EncodeFeature> detectEncodeFeatures(EncoderBackend backend, Path dolbyVisionFrame) {
    return Arrays.stream(EncodeFeature.values())
        .filter(
            feature ->
                passesFeatureTest(
                    backend.encoder(),
                    EncodeFeatureCheck.of(feature, backend.codecFamily()),
                    dolbyVisionFrame))
        .collect(Collectors.toUnmodifiableSet());
  }

  private boolean passesFeatureTest(
      String encoder, EncodeFeatureCheck check, Path dolbyVisionFrame) {
    if (Thread.currentThread().isInterrupted()) {
      return false;
    }

    try {
      var process =
          processFactory.start(
              ffmpegReadingStandardOutput(
                  check.arguments(encoder, dolbyVisionFrame).toArray(String[]::new)));
      var output = new CompletableFuture<byte[]>();
      Thread.ofVirtual().name("encode-feature-test-output").start(() -> readFully(process, output));
      try {
        return process.waitFor(VALIDATION_DEADLINE_SECONDS, TimeUnit.SECONDS)
            && process.exitValue() == 0
            && check.passes(output.get(VALIDATION_DEADLINE_SECONDS, TimeUnit.SECONDS));
      } finally {
        terminateValidation(process);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      log.debug("Encode feature test interrupted for {}", encoder, e);
      return false;
    } catch (Exception e) {
      log.debug("Encode feature test failed for {}", encoder, e);
      return false;
    }
  }

  private static void readFully(Process process, CompletableFuture<byte[]> output) {
    try (var stream = process.getInputStream()) {
      output.complete(stream.readAllBytes());
    } catch (IOException e) {
      output.completeExceptionally(e);
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
