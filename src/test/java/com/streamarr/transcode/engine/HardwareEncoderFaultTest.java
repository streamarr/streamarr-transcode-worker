package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
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
    var stalled =
        new AttemptOutcome.Failed(
            ProducerFailure.ENCODER_STALLED, "[h264_nvenc @ 0x5581] No capable devices found");

    assertThat(HardwareEncoderFault.of(H264_NVENC, stalled)).isEqualTo(Optional.empty());
  }

  private static AttemptOutcome exitedWith(String errorOutput) {
    return new AttemptOutcome.Failed(
        ProducerFailure.PROCESS_EXITED_WITH_ERROR,
        "FFmpeg exited with exit code 1: " + errorOutput);
  }
}
