package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@Tag("UnitTest")
@DisplayName("Transcode Capability Service Tests")
class TranscodeCapabilityServiceTest {

  @Test
  @DisplayName("Should report unavailable when FFmpeg not installed")
  void shouldReportUnavailableWhenFfmpegNotInstalled() {
    var service =
        new TranscodeCapabilityService(
            "ffmpeg",
            _ -> {
              throw new IOException("No such file or directory");
            });

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isFalse();
    assertThat(service.getUnavailableReason()).isEqualTo("FFmpeg not found");
  }

  @Test
  @DisplayName("Should report version probe failure when version probe exits unsuccessfully")
  void shouldReportVersionProbeFailureWhenVersionProbeExitsUnsuccessfully() {
    var service = new TranscodeCapabilityService("ffmpeg", _ -> createProcess("", 1));

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isFalse();
    assertThat(service.getUnavailableReason()).isEqualTo("FFmpeg version probe failed");
  }

  @Test
  @DisplayName("Should report version probe failure when version probe throws")
  void shouldReportVersionProbeFailureWhenVersionProbeThrows() {
    var service =
        new TranscodeCapabilityService(
            "ffmpeg",
            _ -> {
              throw new IllegalStateException("probe failed");
            });

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isFalse();
    assertThat(service.getUnavailableReason()).isEqualTo("FFmpeg version probe failed");
  }

  @Test
  @DisplayName("Should restore interrupt when version probe is interrupted")
  void shouldRestoreInterruptWhenVersionProbeIsInterrupted() {
    var interruptedProcess =
        new FakeProcess("", 0) {
          @Override
          public int waitFor() throws InterruptedException {
            throw new InterruptedException("probe interrupted");
          }
        };
    var service = new TranscodeCapabilityService("ffmpeg", _ -> interruptedProcess);

    try {
      service.detectCapabilities();

      assertThat(service.isFfmpegAvailable()).isFalse();
      assertThat(service.getUnavailableReason()).isEqualTo("FFmpeg version probe interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should report unavailable when FFmpeg lacks required HLS segment options")
  void shouldReportUnavailableWhenFfmpegLacksRequiredHlsSegmentOptions() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 4.4.2", 0),
            "hls", createProcess("Muxer hls [Apple HTTP Live Streaming]:", 0));
    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isFalse();
    assertThat(service.getUnavailableReason()).isEqualTo("Missing hls_segment_options");
  }

  @Test
  @DisplayName("Should report HLS probe failure when capability probe exits unsuccessfully")
  void shouldReportHlsProbeFailureWhenCapabilityProbeExitsUnsuccessfully() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 8.1.2", 0),
            "hls", createProcess("-hls_segment_options <dictionary>", 1));
    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isFalse();
    assertThat(service.getUnavailableReason()).isEqualTo("FFmpeg HLS capability probe failed");
  }

  @Test
  @DisplayName("Should report HLS probe failure when capability probe throws")
  void shouldReportHlsProbeFailureWhenCapabilityProbeThrows() {
    var service =
        new TranscodeCapabilityService(
            "ffmpeg",
            command -> {
              if (String.join(" ", command).contains("muxer=hls")) {
                throw new IllegalStateException("probe failed");
              }

              return createProcess("ffmpeg version 8.1.2", 0);
            });

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isFalse();
    assertThat(service.getUnavailableReason()).isEqualTo("FFmpeg HLS capability probe failed");
  }

  @Test
  @DisplayName("Should restore interrupt when required HLS capability probe is interrupted")
  void shouldRestoreInterruptWhenRequiredHlsCapabilityProbeIsInterrupted() {
    var interruptedProcess =
        new FakeProcess("-hls_segment_options <dictionary>", 0) {
          @Override
          public int waitFor() throws InterruptedException {
            throw new InterruptedException("probe interrupted");
          }
        };
    var outputs =
        Map.of(
            "ffmpeg",
            createProcess("ffmpeg version 8.1.2", 0),
            "hls",
            (Process) interruptedProcess);
    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    try {
      service.detectCapabilities();

      assertThat(service.isFfmpegAvailable()).isFalse();
      assertThat(service.getUnavailableReason())
          .isEqualTo("FFmpeg HLS capability probe interrupted");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should report CPU only when no GPU detected")
  void shouldReportCpuOnlyWhenNoGpuDetected() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\n", 0),
            "encoders", createProcess("", 0));

    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isTrue();
    assertThat(service.getHardwareEncodingCapability().available()).isFalse();
  }

  @Test
  @DisplayName("Should use software when a compiled hardware encoder cannot encode frames")
  void shouldUseSoftwareWhenACompiledHardwareEncoderCannotEncodeFrames() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 8.1.2", 0),
            "encoders", createProcess("V....D h264_vaapi VAAPI H.264 encoder", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\nvaapi\n", 0),
            "h264_vaapi", createProcess("No usable device", 218));
    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isTrue();
    assertThat(service.getHardwareEncodingCapability().available()).isFalse();
    assertThat(service.getHardwareEncodingCapability().encoders()).isEmpty();
    assertThat(service.resolveEncoder("h264")).isEqualTo("libx264");
  }

  @Test
  @DisplayName("Should retain usable hardware when another compiled encoder fails")
  void shouldRetainUsableHardwareWhenAnotherCompiledEncoderFails() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 8.1.2", 0),
            "encoders",
                createProcess(
                    """
                V....D h264_vaapi VAAPI H.264 encoder
                V....D h264_nvenc NVENC H.264 encoder
                """,
                    0),
            "hwaccels", createProcess("Hardware acceleration methods:\ncuda\n", 0),
            "h264_vaapi", createProcess("No usable device", 218),
            "h264_nvenc", createProcess("", 0));
    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    assertThat(service.getHardwareEncodingCapability().available()).isTrue();
    assertThat(service.getHardwareEncodingCapability().encoders()).containsExactly("h264_nvenc");
    assertThat(service.resolveEncoder("h264")).isEqualTo("h264_nvenc");
  }

  @Test
  @DisplayName("Should terminate encoder validation when it exceeds its deadline")
  void shouldTerminateEncoderValidationWhenItExceedsItsDeadline() {
    var encoderProcess = TimedEncoderProcess.builder().interrupted(false).build();
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 8.1.2", 0),
            "encoders", createProcess("V....D h264_nvenc NVENC H.264 encoder", 0),
            "h264_nvenc", encoderProcess);
    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    assertThat(encoderProcess.isAlive()).isFalse();
    assertThat(service.resolveEncoder("h264")).isEqualTo("libx264");
  }

  @Test
  @ExtendWith(OutputCaptureExtension.class)
  @DisplayName("Should report incomplete cleanup when encoder validation does not terminate")
  void shouldReportIncompleteCleanupWhenEncoderValidationDoesNotTerminate(CapturedOutput output) {
    var encoderProcess = TimedEncoderProcess.builder().ignoresTermination(true).build();
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 8.1.2", 0),
            "encoders", createProcess("V....D h264_nvenc NVENC H.264 encoder", 0),
            "h264_nvenc", encoderProcess);
    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    assertThat(service.resolveEncoder("h264")).isEqualTo("libx264");
    assertThat(output).contains("Hardware encoder validation did not exit after termination");
  }

  @Test
  @DisplayName("Should terminate encoder validation when detection is interrupted")
  void shouldTerminateEncoderValidationWhenDetectionIsInterrupted() {
    var encoderProcess = TimedEncoderProcess.builder().interrupted(true).build();
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 8.1.2", 0),
            "encoders", createProcess("V....D h264_nvenc NVENC H.264 encoder", 0),
            "h264_nvenc", encoderProcess);
    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    try {
      service.detectCapabilities();

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(encoderProcess.isAlive()).isFalse();
      assertThat(service.resolveEncoder("h264")).isEqualTo("libx264");
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should leave remaining encoders unstarted when validation is interrupted")
  void shouldLeaveRemainingEncodersUnstartedWhenValidationIsInterrupted() {
    var validationProcesses =
        new ArrayDeque<Process>(
            List.of(TimedEncoderProcess.builder().interrupted(true).build(), createProcess("", 0)));
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 8.1.2", 0),
            "encoders",
                createProcess(
                    """
                V....D h264_nvenc NVENC H.264 encoder
                V....D h264_qsv QSV H.264 encoder
                """,
                    0),
            "hwaccels", createProcess("Hardware acceleration methods:\ncuda\nqsv\n", 0));
    var service =
        new TranscodeCapabilityService(
            "ffmpeg",
            command -> {
              if (List.of(command).contains("-c:v")) {
                return validationProcesses.removeFirst();
              }

              return resolveProcess(command, outputs);
            });

    try {
      service.detectCapabilities();

      assertThat(validationProcesses).hasSize(1);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      assertThat(service.getHardwareEncodingCapability().encoders()).isEmpty();
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  @DisplayName("Should use software when encoder validation cannot start")
  void shouldUseSoftwareWhenEncoderValidationCannotStart() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 8.1.2", 0),
            "encoders", createProcess("V....D h264_nvenc NVENC H.264 encoder", 0));
    var service =
        new TranscodeCapabilityService(
            "ffmpeg",
            command -> {
              if (List.of(command).contains("-c:v")) {
                throw new IOException("Cannot launch validation process");
              }

              return resolveProcess(command, outputs);
            });

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isTrue();
    assertThat(service.resolveEncoder("h264")).isEqualTo("libx264");
  }

  @Test
  @DisplayName("Should detect NVENC capability when NVENC encoders are available")
  void shouldDetectNvencCapabilityWhenNvencEncodersAreAvailable() {
    var encoderOutput =
        """
        V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)
        V....D hevc_nvenc           NVIDIA NVENC hevc encoder (codec hevc)
        V....D av1_nvenc            NVIDIA NVENC av1 encoder (codec av1)
        """;

    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\ncuda\n", 0),
            "encoders", createProcess(encoderOutput, 0));

    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isTrue();
    assertThat(service.getHardwareEncodingCapability().available()).isTrue();
    assertThat(service.getHardwareEncodingCapability().encoders())
        .contains("h264_nvenc", "hevc_nvenc", "av1_nvenc");
  }

  @Test
  @DisplayName("Should resolve H264 encoder to hardware when available")
  void shouldResolveH264EncoderToHardwareWhenAvailable() {
    var encoderOutput =
        """
        V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)
        """;

    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\ncuda\n", 0),
            "encoders", createProcess(encoderOutput, 0));

    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.resolveEncoder("h264")).isEqualTo("h264_nvenc");
  }

  @Test
  @DisplayName("Should resolve AV1 encoder to software when no hardware")
  void shouldResolveAv1EncoderToSoftwareWhenNoHardware() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\n", 0),
            "encoders", createProcess("", 0));

    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.resolveEncoder("av1")).isEqualTo("libsvtav1");
  }

  @Test
  @DisplayName("Should resolve H264 encoder to software when no hardware")
  void shouldResolveH264EncoderToSoftwareWhenNoHardware() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\n", 0),
            "encoders", createProcess("", 0));

    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.resolveEncoder("h264")).isEqualTo("libx264");
  }

  @Test
  @DisplayName("Should detect QSV capability when QSV encoder is available")
  void shouldDetectQsvCapabilityWhenQsvEncoderIsAvailable() {
    var encoderOutput =
        """
        V....D h264_qsv             H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (Intel Quick Sync Video acceleration) (codec h264)
        """;

    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\nqsv\n", 0),
            "encoders", createProcess(encoderOutput, 0));

    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.getHardwareEncodingCapability().available()).isTrue();
    assertThat(service.resolveEncoder("h264")).isEqualTo("h264_qsv");
  }

  private Process resolveProcess(String[] command, Map<String, Process> outputs) {
    var cmdStr = String.join(" ", command);
    if (cmdStr.contains("-version")) {
      return outputs.get("ffmpeg");
    }

    if (cmdStr.contains("muxer=hls")) {
      return outputs.getOrDefault("hls", createProcess("-hls_segment_options <dictionary>", 0));
    }

    if (cmdStr.contains("-hwaccels")) {
      return outputs.get("hwaccels");
    }

    if (cmdStr.contains("-encoders")) {
      return outputs.get("encoders");
    }

    var encoderIndex = List.of(command).indexOf("-c:v");
    if (encoderIndex >= 0) {
      return outputs.getOrDefault(command[encoderIndex + 1], createProcess("", 0));
    }

    return createProcess("", 1);
  }

  @Test
  @DisplayName("Should resolve HEVC encoder to hardware when NVENC available")
  void shouldResolveHevcEncoderToHardwareWhenNvencAvailable() {
    var encoderOutput =
        """
        V....D hevc_nvenc           NVIDIA NVENC hevc encoder (codec hevc)
        """;

    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\ncuda\n", 0),
            "encoders", createProcess(encoderOutput, 0));

    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.resolveEncoder("hevc")).isEqualTo("hevc_nvenc");
  }

  @Test
  @DisplayName("Should resolve to software default when codec family is unknown")
  void shouldResolveToSoftwareDefaultWhenCodecFamilyIsUnknown() {
    var encoderOutput =
        """
        V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)
        """;

    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\ncuda\n", 0),
            "encoders", createProcess(encoderOutput, 0));

    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.resolveEncoder("vp9")).isEqualTo("libx264");
  }

  @Test
  @DisplayName("Should fallback to software when hardware encoder does not match codec")
  void shouldFallbackToSoftwareWhenHardwareEncoderDoesNotMatchCodec() {
    var encoderOutput =
        """
        V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)
        """;

    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\ncuda\n", 0),
            "encoders", createProcess(encoderOutput, 0));

    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.resolveEncoder("av1")).isEqualTo("libsvtav1");
  }

  @Test
  @DisplayName("Should detect accelerator string when GPU present")
  void shouldDetectAcceleratorStringWhenGpuPresent() {
    var encoderOutput =
        """
        V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)
        """;

    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\ncuda\n", 0),
            "encoders", createProcess(encoderOutput, 0));

    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.getHardwareEncodingCapability().accelerator()).isEqualTo("cuda");
  }

  @Test
  @DisplayName("Should concatenate multiple accelerators with comma")
  void shouldConcatenateMultipleAcceleratorsWithComma() {
    var encoderOutput =
        """
        V....D h264_nvenc           NVIDIA NVENC H.264 encoder (codec h264)
        """;

    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\ncuda\ncuvid\n", 0),
            "encoders", createProcess(encoderOutput, 0));

    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.getHardwareEncodingCapability().accelerator()).isEqualTo("cuda,cuvid");
  }

  @Test
  @DisplayName("Should detect VideoToolbox encoder on macOS")
  void shouldDetectVideoToolboxEncoderOnMacOs() {
    var encoderOutput =
        """
        V....D h264_videotoolbox    VideoToolbox H.264 Encoder (codec h264)
        """;

    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 7.0", 0),
            "hwaccels", createProcess("Hardware acceleration methods:\nvideotoolbox\n", 0),
            "encoders", createProcess(encoderOutput, 0));

    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));
    service.detectCapabilities();

    assertThat(service.getHardwareEncodingCapability().encoders()).contains("h264_videotoolbox");
    assertThat(service.resolveEncoder("h264")).isEqualTo("h264_videotoolbox");
  }

  private Process createProcess(String stdout, int exitCode) {
    return new FakeProcess(stdout, exitCode);
  }

  private static final class TimedEncoderProcess extends FakeProcess {

    private final boolean interrupted;
    private final boolean ignoresTermination;
    private boolean alive = true;
    private boolean terminationRequested;

    @Builder
    private TimedEncoderProcess(boolean interrupted, boolean ignoresTermination) {
      super("", 0);
      this.interrupted = interrupted;
      this.ignoresTermination = ignoresTermination;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      if (terminationRequested && !ignoresTermination) {
        alive = false;
        return true;
      }

      if (interrupted) {
        throw new InterruptedException("Encoder validation interrupted");
      }

      return false;
    }

    @Override
    public boolean isAlive() {
      return alive;
    }

    @Override
    public void destroy() {
      terminationRequested = true;
    }
  }
}
