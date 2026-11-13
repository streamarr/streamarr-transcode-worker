package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// The error lines are the ones that FFmpeg 8's nvenc.c and its CUDA loader print.
@Tag("UnitTest")
@DisplayName("Hardware Encoder Fault Tests")
class HardwareEncoderFaultTest {

  private static final EncoderBackend H264_NVENC =
      EncoderBackend.builder()
          .codecFamily(CodecFamily.H264)
          .encoder("h264_nvenc")
          .hardware(true)
          .build();

  private static final EncoderBackend HEVC_NVENC =
      EncoderBackend.builder()
          .codecFamily(CodecFamily.HEVC)
          .encoder("hevc_nvenc")
          .hardware(true)
          .build();
  private static final String DECODER_REFUSAL =
      """
      p412be p412le gbrap14be gbrap14le ayuv uyva vyu444 v30xle y216le xv48be xv48le
          dst: cuda
      [vf#0:0 @ 0x5613beacf940] Error reinitializing filters!
      [vf#0:0 @ 0x5613beacf940] Task finished with error code: -38 (Function not implemented)
      [vf#0:0 @ 0x5613beacf940] Terminating thread with return code -38 (Function not implemented)
      [vost#0:0/hevc_nvenc @ 0x5613beaf3780] [enc:hevc_nvenc @ 0x5613beaf3a40] Could not open \
      encoder before EOF
      [vost#0:0/hevc_nvenc @ 0x5613beaf3780] Task finished with error code: -22 (Invalid argument)
      [vost#0:0/hevc_nvenc @ 0x5613beaf3780] Terminating thread with return code -22 (Invalid \
      argument)
      [out#0/mp4 @ 0x5613beaddf40] Nothing was written into output file, because at least one of \
      its streams received no packets.
      Conversion failed!""";

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[h264_nvenc @ 0x55d0c8a4e1c0] OpenEncodeSessionEx failed: out of memory (10): (no details)",
        "[h264_nvenc @ 0x55d0c8a4e1c0] OpenEncodeSessionEx failed: incompatible client key (21):"
            + " (no details)"
      })
  @DisplayName("Should read a session refusal when the driver refuses to open an encode session")
  void shouldReadASessionRefusalWhenTheDriverRefusesToOpenAnEncodeSession(String errorOutput) {
    assertThat(HardwareEncoderFault.of(H264_NVENC, exitedWith(errorOutput)))
        .contains(HardwareEncoderFault.SESSION_REFUSED);
  }

  // The end of the worker's error output when an RTX 5090 (driver 615.78.08) already ran its 12
  // encode sessions and the worker's command asked for a 13th, under the pinned FFmpeg 8.1.3.
  @Test
  @DisplayName(
      "Should read a session refusal when the driver refuses a session past its limit and FFmpeg"
          + " then reports that it found no capable device")
  void
      shouldReadASessionRefusalWhenTheDriverRefusesASessionPastItsLimitAndFfmpegThenReportsThatItFoundNoCapableDevice() {
    var errorOutput =
        """
        [h264_nvenc @ 0x55e904799fc0] OpenEncodeSessionEx failed: incompatible client key (21): \
        (no details)
        [h264_nvenc @ 0x55e904799fc0] No capable devices found
        [vost#0:0/h264_nvenc @ 0x55e904799b40] [enc:h264_nvenc @ 0x55e904799f40] Error while \
        opening encoder - maybe incorrect parameters such as bit_rate, rate, width or height.
        [vf#0:0 @ 0x55e90479ab00] Error sending frames to consumers: Generic error in an external \
        library
        [vost#0:0/h264_nvenc @ 0x55e904799b40] [enc:h264_nvenc @ 0x55e904799f40] Could not open \
        encoder before EOF
        [out#0/mp4 @ 0x55e904799640] Nothing was written into output file, because at least one \
        of its streams received no packets.
        Conversion failed!""";

    assertThat(HardwareEncoderFault.of(H264_NVENC, exitedWith(errorOutput)))
        .contains(HardwareEncoderFault.SESSION_REFUSED);
  }

  // FFmpeg tries each GPU in turn: one can refuse the session while the next opens it and fails.
  @Test
  @DisplayName(
      "Should read a failed encoder when one GPU refuses the session and the next GPU's encoder"
          + " fails")
  void shouldReadAFailedEncoderWhenOneGpuRefusesTheSessionAndTheNextGpusEncoderFails() {
    var errorOutput =
        """
        [h264_nvenc @ 0x55e904799fc0] OpenEncodeSessionEx failed: incompatible client key (21): \
        (no details)
        [h264_nvenc @ 0x55e904799fc0] InitializeEncoder failed: device does not exist (5)
        [h264_nvenc @ 0x55e904799fc0] No capable devices found
        Conversion failed!""";

    assertThat(HardwareEncoderFault.of(H264_NVENC, exitedWith(errorOutput)))
        .contains(HardwareEncoderFault.FAILED);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "[h264_nvenc @ 0x55d0c8a4e1c0] Failed locking bitstream buffer: device lost (40)",
        "[h264_nvenc @ 0x55d0c8a4e1c0] EncodePicture failed!: generic error (20)",
        "[h264_nvenc @ 0x55d0c8a4e1c0] OpenEncodeSessionEx failed: unsupported device (2)",
        "[AVHWDeviceContext @ 0x5581] cu->cuCtxCreate(&hwctx->cuda_ctx, desired_flags, hwdev)"
            + " failed -> CUDA_ERROR_UNKNOWN: unknown error",
        "[h264_nvenc @ 0x55d0c8a4e1c0] No capable devices found"
      })
  @DisplayName("Should read a failed encoder when NVENC or CUDA reports any other error")
  void shouldReadAFailedEncoderWhenNvencOrCudaReportsAnyOtherError(String errorOutput) {
    assertThat(HardwareEncoderFault.of(H264_NVENC, exitedWith(errorOutput)))
        .contains(HardwareEncoderFault.FAILED);
  }

  // The end of the worker's error output when NVDEC refused a 64x64 HEVC picture ("Video width 64
  // not within range from 144 to 8192") under the pinned FFmpeg 8.1.3: FFmpeg decoded in software,
  // and scale_cuda, which takes only CUDA frames, refused the frames.
  @Test
  @DisplayName(
      "Should read a decoder refusal when the hardware decoder refuses the source and the GPU"
          + " filters get frames from system memory")
  void shouldReadADecoderRefusalWhenTheHardwareDecoderRefusesTheSource() {
    assertThat(HardwareEncoderFault.of(HEVC_NVENC, exitedWith(DECODER_REFUSAL)))
        .contains(HardwareEncoderFault.DECODER_REFUSED);
  }

  // FFmpeg's nvdec.c and its CUDA filters report a failed CUDA call with the call and its status.
  @ParameterizedTest
  @ValueSource(
      strings = {
        "[hevc @ 0x5613bfba1b40] decoder->cvdl->cuvidDecodePicture(decoder->decoder,"
            + " &ctx->pic_params) failed -> CUDA_ERROR_UNKNOWN: unknown error",
        "[Parsed_tonemap_cuda_1 @ 0x5613beacf940] cu->cuLaunchKernel(s->cu_func, 0, 0, 0, args)"
            + " failed -> CUDA_ERROR_ILLEGAL_ADDRESS: an illegal memory access was encountered"
      })
  @DisplayName(
      "Should read a failed encoder when NVDEC or a CUDA filter fails during the GPU reshape, so"
          + " that the stream continues on the software encoder")
  void shouldReadAFailedEncoderWhenNvdecOrACudaFilterFailsDuringTheGpuReshape(String errorOutput) {
    assertThat(HardwareEncoderFault.of(HEVC_NVENC, exitedWith(errorOutput)))
        .contains(HardwareEncoderFault.FAILED);
  }

  // FFmpeg's hwcontext_cuda.c and nvdec.c report an allocation that the GPU's memory cannot hold.
  @ParameterizedTest
  @ValueSource(
      strings = {
        "[AVHWFramesContext @ 0x5613bea01a80] cu->cuMemAlloc(&data, size) failed ->"
            + " CUDA_ERROR_OUT_OF_MEMORY: out of memory",
        "[hevc @ 0x5613bfba1b40] cuvidCreateDecoder(&decoder->decoder, params) failed ->"
            + " CUDA_ERROR_OUT_OF_MEMORY: out of memory\n[vist#0:0/hevc @ 0x5613be9f0a40] Error"
            + " submitting packet to decoder: Generic error in an external library"
      })
  @DisplayName(
      "Should read a device out of memory when the GPU's memory cannot hold the frames of the"
          + " job, so that the worker keeps offering the encoder to other jobs")
  void shouldReadADeviceOutOfMemoryWhenTheGpusMemoryCannotHoldTheFramesOfTheJob(
      String errorOutput) {
    assertThat(HardwareEncoderFault.of(HEVC_NVENC, exitedWith(errorOutput)))
        .contains(HardwareEncoderFault.DEVICE_OUT_OF_MEMORY);
  }

  @Test
  @DisplayName(
      "Should read a failed encoder when CUDA reports another error beside the GPU's memory"
          + " running out")
  void shouldReadAFailedEncoderWhenCudaReportsAnotherErrorBesideTheGpusMemoryRunningOut() {
    var errorOutput =
        """
        [AVHWFramesContext @ 0x5613bea01a80] cu->cuMemAlloc(&data, size) failed -> \
        CUDA_ERROR_OUT_OF_MEMORY: out of memory
        [Parsed_tonemap_cuda_1 @ 0x5613beacf940] cu->cuLaunchKernel(s->cu_func, 0, 0, 0, args) \
        failed -> CUDA_ERROR_ILLEGAL_ADDRESS: an illegal memory access was encountered
        """;

    assertThat(HardwareEncoderFault.of(HEVC_NVENC, exitedWith(errorOutput)))
        .contains(HardwareEncoderFault.FAILED);
  }

  @Test
  @DisplayName(
      "Should read a decoder refusal when the job holds its last frame and the filter graph of"
          + " both inputs gets frames from system memory")
  void shouldReadADecoderRefusalWhenTheFilterGraphOfBothInputsGetsFramesFromSystemMemory() {
    var errorOutput =
        """
        bgra64le yvyu422 ya16be ya16le gbrap gbrap16be gbrap16le 0rgb rgb0 0bgr bgr0
            dst: cuda
        [fc#1 @ 0x55e1cc2a6800] Error reinitializing filters!
        [fc#1 @ 0x55e1cc2a6800] Task finished with error code: -38 (Function not implemented)
        [vost#0:0/hevc_nvenc @ 0x55e1cc2b0d00] [enc:hevc_nvenc @ 0x55e1cc2b1140] Could not open \
        encoder before EOF
        Conversion failed!""";

    assertThat(HardwareEncoderFault.of(HEVC_NVENC, exitedWith(errorOutput)))
        .contains(HardwareEncoderFault.DECODER_REFUSED);
  }

  @Test
  @DisplayName(
      "Should read no hardware fault when filters that take frames from system memory cannot be"
          + " configured")
  void shouldReadNoHardwareFaultWhenFiltersThatTakeFramesFromSystemMemoryCannotBeConfigured() {
    var errorOutput =
        """
            src: cuda
            dst: yuv420p yuv422p yuv444p
        [vf#0:0 @ 0x5613beacf940] Error reinitializing filters!
        Conversion failed!""";

    assertThat(HardwareEncoderFault.of(HEVC_NVENC, exitedWith(errorOutput))).isEmpty();
  }

  @Test
  @DisplayName("Should read no hardware fault when FFmpeg fails for a reason outside NVENC")
  void shouldReadNoHardwareFaultWhenFfmpegFailsForAReasonOutsideNvenc() {
    var outcome = exitedWith("movie.mkv: Input/output error");

    assertThat(HardwareEncoderFault.of(H264_NVENC, outcome)).isEmpty();
  }

  @Test
  @DisplayName("Should read no hardware fault when a software encode prints an NVENC error")
  void shouldReadNoHardwareFaultWhenASoftwareEncodePrintsAnNvencError() {
    var libx264 =
        EncoderBackend.builder()
            .codecFamily(CodecFamily.H264)
            .encoder("libx264")
            .hardware(false)
            .build();

    assertThat(
            HardwareEncoderFault.of(
                libx264, exitedWith("[h264_nvenc @ 0x5581] No capable devices found")))
        .isEmpty();
  }

  @Test
  @DisplayName("Should read no hardware fault when the producer failed for a reason of its own")
  void shouldReadNoHardwareFaultWhenTheProducerFailedForAReasonOfItsOwn() {
    var noCapableDevice = "[h264_nvenc @ 0x5581] No capable devices found";
    var stalled =
        new AttemptOutcome.Failed(
            ProducerFailure.ENCODER_STALLED, noCapableDevice, noCapableDevice);

    assertThat(HardwareEncoderFault.of(H264_NVENC, stalled)).isEmpty();
  }

  private static AttemptOutcome exitedWith(String errorOutput) {
    return new AttemptOutcome.Failed(
        ProducerFailure.PROCESS_EXITED_WITH_ERROR,
        "FFmpeg exited with exit code 1: " + errorOutput,
        errorOutput);
  }
}
