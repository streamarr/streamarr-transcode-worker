package com.streamarr.transcode.engine;

import static com.streamarr.transcode.fixtures.FfmpegMuxerHelpFixtures.FRAGMENTED_MP4_MUXER_HELP;
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
  @DisplayName("Should name the missing options when the mp4 muxer cannot fragment its output")
  void shouldNameTheMissingOptionsWhenTheMp4MuxerCannotFragmentItsOutput() {
    var outputs =
        Map.of(
            "ffmpeg",
            createProcess("ffmpeg version 4.1.11", 0),
            "mp4",
            createProcess(
                """
                mov/mp4/tgp/psp/tg2/ipod/ismv/f4v muxer AVOptions:
                  -movflags          <flags>      E.......... MOV muxer flags (default 0)
                     delay_moov                   E.......... Delay writing the initial moov
                     frag_discont                 E.......... Signal a discontinuous fragment
                     frag_keyframe                E.......... Fragment at video keyframes
                     skip_trailer                 E.......... Skip writing the trailer
                  -min_frag_duration <int>        E.......... Minimum fragment duration
                """,
                0));
    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isFalse();
    assertThat(service.getUnavailableReason())
        .isEqualTo("Missing mp4 muxer options: -frag_duration, cmaf");
  }

  @Test
  @DisplayName("Should report mp4 muxer probe failure when capability probe exits unsuccessfully")
  void shouldReportMp4MuxerProbeFailureWhenCapabilityProbeExitsUnsuccessfully() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 8.1.2", 0),
            "mp4", createProcess(FRAGMENTED_MP4_MUXER_HELP, 1));
    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isFalse();
    assertThat(service.getUnavailableReason())
        .isEqualTo("FFmpeg mp4 muxer capability probe failed");
  }

  @Test
  @DisplayName("Should report mp4 muxer probe failure when capability probe throws")
  void shouldReportMp4MuxerProbeFailureWhenCapabilityProbeThrows() {
    var service =
        new TranscodeCapabilityService(
            "ffmpeg",
            command -> {
              if (String.join(" ", command).contains("muxer=mp4")) {
                throw new IllegalStateException("probe failed");
              }

              return createProcess("ffmpeg version 8.1.2", 0);
            });

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isFalse();
    assertThat(service.getUnavailableReason())
        .isEqualTo("FFmpeg mp4 muxer capability probe failed");
  }

  @Test
  @DisplayName("Should restore interrupt when the mp4 muxer capability probe is interrupted")
  void shouldRestoreInterruptWhenTheMp4MuxerCapabilityProbeIsInterrupted() {
    var interruptedProcess =
        new FakeProcess(FRAGMENTED_MP4_MUXER_HELP, 0) {
          @Override
          public int waitFor() throws InterruptedException {
            throw new InterruptedException("probe interrupted");
          }
        };
    var outputs =
        Map.of(
            "ffmpeg",
            createProcess("ffmpeg version 8.1.2", 0),
            "mp4",
            (Process) interruptedProcess);
    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    try {
      service.detectCapabilities();

      assertThat(service.isFfmpegAvailable()).isFalse();
      assertThat(service.getUnavailableReason())
          .isEqualTo("FFmpeg mp4 muxer capability probe interrupted");
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
    assertThat(service.resolveEncoder("h264")).map(EncoderBackend::encoder).contains("libx264");
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
    assertThat(service.resolveEncoder("h264")).map(EncoderBackend::encoder).contains("h264_nvenc");
  }

  @Test
  @DisplayName(
      "Should run the hardware encoder that sorts first by name when two of one codec family pass")
  void shouldRunTheHardwareEncoderThatSortsFirstByNameWhenTwoOfOneCodecFamilyPass() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 8.1.2", 0),
            "encoders",
                createProcess(
                    """
                V....D h264_vaapi VAAPI H.264 encoder
                V....D h264_qsv QSV H.264 encoder
                V....D h264_nvenc NVENC H.264 encoder
                """,
                    0),
            "hwaccels", createProcess("Hardware acceleration methods:\ncuda\nqsv\nvaapi\n", 0));
    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    assertThat(service.getHardwareEncodingCapability().encoders())
        .containsExactlyInAnyOrder("h264_nvenc", "h264_qsv", "h264_vaapi");
    assertThat(service.resolveEncoder("h264")).map(EncoderBackend::encoder).contains("h264_nvenc");
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
    assertThat(service.resolveEncoder("h264")).map(EncoderBackend::encoder).contains("libx264");
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

    assertThat(service.resolveEncoder("h264")).map(EncoderBackend::encoder).contains("libx264");
    assertThat(output).contains("Encoder validation did not exit after termination");
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
      assertThat(service.workerVerifiedEncoders()).isEmpty();
      assertThat(service.resolveEncoder("h264")).isEmpty();
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
              if (List.of(command).contains("h264_nvenc")) {
                throw new IOException("Cannot launch validation process");
              }

              return resolveProcess(command, outputs);
            });

    service.detectCapabilities();

    assertThat(service.isFfmpegAvailable()).isTrue();
    assertThat(service.resolveEncoder("h264")).map(EncoderBackend::encoder).contains("libx264");
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

    assertThat(service.resolveEncoder("h264")).map(EncoderBackend::encoder).contains("h264_nvenc");
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

    assertThat(service.resolveEncoder("av1")).map(EncoderBackend::encoder).contains("libsvtav1");
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

    assertThat(service.resolveEncoder("h264")).map(EncoderBackend::encoder).contains("libx264");
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
    assertThat(service.resolveEncoder("h264")).map(EncoderBackend::encoder).contains("h264_qsv");
  }

  @Test
  @DisplayName("Should advertise for every codec family the encoder backend that jobs run")
  void shouldAdvertiseForEveryCodecFamilyTheEncoderBackendThatJobsRun() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 8.1.2", 0),
            "encoders",
                createProcess(
                    """
                V....D h264_nvenc NVENC H.264 encoder
                V....D hevc_vaapi VAAPI H.265 encoder
                """,
                    0),
            "hwaccels", createProcess("Hardware acceleration methods:\ncuda\n", 0),
            "hevc_vaapi", createProcess("No usable device", 218));
    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    var advertised = service.workerVerifiedEncoders();
    var codecFamilies =
        Map.of("h264", CodecFamily.H264, "hevc", CodecFamily.HEVC, "av1", CodecFamily.AV1);
    assertThat(advertised)
        .extracting(encoder -> encoder.backend().codecFamily())
        .containsExactlyInAnyOrderElementsOf(codecFamilies.values());
    codecFamilies.forEach(
        (familyName, family) ->
            assertThat(advertised)
                .filteredOn(encoder -> encoder.backend().codecFamily() == family)
                .singleElement()
                .extracting(WorkerVerifiedEncoder::backend)
                .isEqualTo(service.resolveEncoder(familyName).orElseThrow()));
    assertThat(advertised)
        .containsExactlyInAnyOrder(
            WorkerVerifiedEncoder.builder()
                .backend(hardwareBackend(CodecFamily.H264, "h264_nvenc"))
                .keyframeVerified(false)
                .build(),
            WorkerVerifiedEncoder.builder()
                .backend(softwareBackend(CodecFamily.HEVC, "libx265"))
                .keyframeVerified(true)
                .build(),
            WorkerVerifiedEncoder.builder()
                .backend(softwareBackend(CodecFamily.AV1, "libsvtav1"))
                .keyframeVerified(true)
                .build());
  }

  @Test
  @DisplayName(
      "Should advertise and run no encoder for a codec family when its software encoder fails its"
          + " test encode")
  void shouldAdvertiseAndRunNoEncoderForACodecFamilyWhenItsSoftwareEncoderFailsItsTestEncode() {
    var outputs =
        Map.of(
            "ffmpeg", createProcess("ffmpeg version 8.1.2", 0),
            "encoders", createProcess("", 0),
            "libsvtav1", createProcess("Unknown encoder 'libsvtav1'", 8));
    var service =
        new TranscodeCapabilityService("ffmpeg", command -> resolveProcess(command, outputs));

    service.detectCapabilities();

    assertThat(service.workerVerifiedEncoders())
        .extracting(encoder -> encoder.backend().encoder())
        .containsExactlyInAnyOrder("libx264", "libx265");
    assertThat(service.resolveEncoder("av1")).isEmpty();
  }

  @Test
  @DisplayName("Should advertise no encoder when FFmpeg is unavailable")
  void shouldAdvertiseNoEncoderWhenFfmpegIsUnavailable() {
    var service = new TranscodeCapabilityService("ffmpeg", _ -> createProcess("", 1));

    service.detectCapabilities();

    assertThat(service.workerVerifiedEncoders()).isEmpty();
  }

  private static EncoderBackend hardwareBackend(CodecFamily codecFamily, String encoder) {
    return EncoderBackend.builder()
        .codecFamily(codecFamily)
        .encoder(encoder)
        .hardware(true)
        .build();
  }

  private static EncoderBackend softwareBackend(CodecFamily codecFamily, String encoder) {
    return EncoderBackend.builder()
        .codecFamily(codecFamily)
        .encoder(encoder)
        .hardware(false)
        .build();
  }

  private Process resolveProcess(String[] command, Map<String, Process> outputs) {
    var cmdStr = String.join(" ", command);
    if (cmdStr.contains("-version")) {
      return outputs.get("ffmpeg");
    }

    if (cmdStr.contains("muxer=mp4")) {
      return outputs.getOrDefault("mp4", createProcess(FRAGMENTED_MP4_MUXER_HELP, 0));
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

    assertThat(service.resolveEncoder("hevc")).map(EncoderBackend::encoder).contains("hevc_nvenc");
  }

  @Test
  @DisplayName("Should resolve no encoder when the codec family is unknown")
  void shouldResolveNoEncoderWhenTheCodecFamilyIsUnknown() {
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

    assertThat(service.resolveEncoder("vp9")).isEmpty();
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

    assertThat(service.resolveEncoder("av1")).map(EncoderBackend::encoder).contains("libsvtav1");
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
    assertThat(service.resolveEncoder("h264"))
        .map(EncoderBackend::encoder)
        .contains("h264_videotoolbox");
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
