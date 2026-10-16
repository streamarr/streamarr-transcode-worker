package com.streamarr.transcode.engine;

import static com.streamarr.transcode.fixtures.FfmpegMuxerHelpFixtures.FRAGMENTED_MP4_MUXER_HELP;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Transcode Capability Service Process Tests")
class TranscodeCapabilityServiceProcessTest {

  // Twice the default Linux pipe capacity of 64 KiB.
  private static final int PIPE_OVERFILL_BYTES = 128 * 1024;

  // A detection that never reads a full pipe blocks forever, so the bound turns a hang into a
  // failure.
  private static final Duration DETECTION_BOUND = Duration.ofSeconds(10);

  @TempDir Path temporaryDirectory;

  @Test
  @DisplayName(
      "Should report FFmpeg available when the version check writes more than a pipe holds")
  void shouldReportFfmpegAvailableWhenTheVersionCheckWritesMoreThanAPipeHolds() throws Exception {
    var service = detectCapabilities(FloodedCommand.VERSION);

    assertThat(service.isFfmpegAvailable()).isTrue();
  }

  @Test
  @DisplayName(
      "Should read the mp4 muxer options when the muxer help writes more than a pipe holds to"
          + " standard error")
  void shouldReadTheMp4MuxerOptionsWhenTheMuxerHelpWritesMoreThanAPipeHoldsToStandardError()
      throws Exception {
    var service = detectCapabilities(FloodedCommand.MP4_MUXER_HELP);

    assertThat(service.isFfmpegAvailable()).isTrue();
    assertThat(service.getUnavailableReason()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should read the encoder list when the encoder list writes more than a pipe holds to"
          + " standard error")
  void shouldReadTheEncoderListWhenTheEncoderListWritesMoreThanAPipeHoldsToStandardError()
      throws Exception {
    var service = detectCapabilities(FloodedCommand.ENCODERS);

    assertThat(service.getHardwareEncodingCapability().encoders()).containsExactly("h264_nvenc");
  }

  @Test
  @DisplayName(
      "Should read the accelerator list when the accelerator list writes more than a pipe holds to"
          + " standard error")
  void shouldReadTheAcceleratorListWhenTheAcceleratorListWritesMoreThanAPipeHoldsToStandardError()
      throws Exception {
    var service = detectCapabilities(FloodedCommand.HWACCELS);

    assertThat(service.getHardwareEncodingCapability().accelerator()).isEqualTo("cuda");
  }

  @Test
  @DisplayName("Should verify every encoder when each test encode writes more than a pipe holds")
  void shouldVerifyEveryEncoderWhenEachTestEncodeWritesMoreThanAPipeHolds() throws Exception {
    var service = detectCapabilities(FloodedCommand.TEST_ENCODE);

    assertThat(service.getHardwareEncodingCapability().encoders()).containsExactly("h264_nvenc");
    assertThat(service.workerVerifiedEncoders())
        .extracting(encoder -> encoder.backend().encoder())
        .containsExactlyInAnyOrder("libx264", "libx265", "libsvtav1");
  }

  private TranscodeCapabilityService detectCapabilities(FloodedCommand flooded) throws Exception {
    var service = new TranscodeCapabilityService(ffmpeg(flooded).toString(), ProcessBuilder::start);
    var detection =
        CompletableFuture.runAsync(service::detectCapabilities, Thread.ofVirtual()::start);

    assertThat(detection)
        .as("capability detection finishes while FFmpeg writes more than a pipe holds")
        .succeedsWithin(DETECTION_BOUND);
    return service;
  }

  // Every command exits 0. Only the flooded command writes more than a pipe holds.
  private Path ffmpeg(FloodedCommand flooded) throws Exception {
    var binary = temporaryDirectory.resolve("ffmpeg");
    Files.writeString(
        binary,
        """
        #!/bin/sh
        flood() { head -c %d /dev/zero; }
        case "$*" in
          -version) %s ;;
          *muxer=mp4*) %s
            cat <<'HELP'
        %s
        HELP
            ;;
          -encoders) %s
            echo ' V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)' ;;
          -hwaccels) %s
            printf 'Hardware acceleration methods:\\ncuda\\n' ;;
          *) %s ;;
        esac
        """
            .formatted(
                PIPE_OVERFILL_BYTES,
                flooded.writesFor(FloodedCommand.VERSION),
                flooded.writesFor(FloodedCommand.MP4_MUXER_HELP),
                FRAGMENTED_MP4_MUXER_HELP,
                flooded.writesFor(FloodedCommand.ENCODERS),
                flooded.writesFor(FloodedCommand.HWACCELS),
                flooded.writesFor(FloodedCommand.TEST_ENCODE)));
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
    return binary;
  }

  private enum FloodedCommand {
    // The check uses only the exit status of the version check and of each test encode.
    VERSION("flood; flood >&2"),
    MP4_MUXER_HELP("flood >&2"),
    ENCODERS("flood >&2"),
    HWACCELS("flood >&2"),
    TEST_ENCODE("flood; flood >&2");

    private final String flood;

    FloodedCommand(String flood) {
      this.flood = flood;
    }

    private String writesFor(FloodedCommand command) {
      return this == command ? flood : ":";
    }
  }
}
