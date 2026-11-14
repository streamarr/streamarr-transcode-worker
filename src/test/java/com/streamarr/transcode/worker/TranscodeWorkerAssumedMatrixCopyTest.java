package com.streamarr.transcode.worker;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.fixtures.RecordingFixtures.ENCODED_RECORDING;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.copyJobBuilderFor;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.sdrDynamicRange;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.startVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.BitstreamRewrite;
import build.buf.gen.streamarr.transcode.v1.CompatibleBase;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.MatrixCoefficients;
import build.buf.gen.streamarr.transcode.v1.VideoCodecFamily;
import build.buf.gen.streamarr.transcode.v1.VideoDecision;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.fixtures.RecordingEdits;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Transcode Worker Assumed Matrix Copy Tests")
class TranscodeWorkerAssumedMatrixCopyTest {

  @TempDir Path tempDir;

  @ParameterizedTest
  @CsvSource({
    "MATRIX_COEFFICIENTS_BT709, false",
    "MATRIX_COEFFICIENTS_SMPTE170M, false",
    "MATRIX_COEFFICIENTS_BT709, true",
    "MATRIX_COEFFICIENTS_SMPTE170M, true"
  })
  @DisplayName("Should deliver the assumed matrix when an H.264 copy lists its rewrite")
  void shouldDeliverTheAssumedMatrixWhenAnH264CopyListsItsRewrite(
      MatrixCoefficients matrix, boolean trailingBytes) throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var recording = recording(ENCODED_RECORDING);
    var runtime = new ScriptedWorkerRuntime();
    var output = bytesOf(recording.file());
    var delivered = trailingBytes ? withSpsTrailingBytes(output) : output;
    var launcher =
        new ScriptedProcessLauncher(_ -> ScriptedProcess.builder().output(delivered).build());
    var job = copyJobBuilderFor(recording);
    job.getDecisionBuilder()
        .getVideoBuilder()
        .setSourceDynamicRange(sdrDynamicRange())
        .setOutputDynamicRange(sdrDynamicRange())
        .setAssumedSourceMatrix(matrix)
        .addBitstreamRewrites(BitstreamRewrite.BITSTREAM_REWRITE_NAME_ASSUMED_SOURCE_MATRIX);

    try (var worker = workerBuilder(tempDir).runtime(runtime).engine(engine(launcher)).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());
      await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () ->
                  connection.events().stream()
                      .anyMatch(
                          event -> event.hasJobAttemptCompleted() || event.hasJobAttemptFailed()));

      assertThat(connection.events()).noneMatch(EstablishWorkerSessionRequest::hasJobAttemptFailed);
      assertThat(connection.registration().getCapabilities().getBitstreamRewritesList())
          .contains(BitstreamRewrite.BITSTREAM_REWRITE_NAME_ASSUMED_SOURCE_MATRIX);
      var uploaded = new ByteArrayOutputStream();
      connection.uploads().forEach(upload -> uploaded.writeBytes(upload.content()));
      var colour =
          matrix == MatrixCoefficients.MATRIX_COEFFICIENTS_BT709
              ? "6e636c7800010001000100"
              : "6e636c7800010001000600";
      assertThat(uploaded.toByteArray()).containsSubsequence(HexFormat.of().parseHex(colour));
    }
  }

  private static byte[] withSpsTrailingBytes(byte[] output) {
    var record = new String(output, StandardCharsets.ISO_8859_1).indexOf("avcC");
    assertThat(record).isPositive();
    var length = Short.toUnsignedInt(ByteBuffer.wrap(output).getShort(record + 10));
    var sps = Arrays.copyOfRange(output, record + 12, record + 12 + length + 1);
    sps[length] = (byte) 0xA5;
    var sample = ByteBuffer.allocate(4 + sps.length).putInt(sps.length).put(sps).array();
    return RecordingEdits.withBytesAfterTheFirstVideoSample(output, sample);
  }

  @Test
  @DisplayName(
      "Should fail as an output mismatch when the copied AVC record has no usable NAL length")
  void shouldFailAsAnOutputMismatchWhenTheCopiedAvcRecordHasNoUsableNalLength() throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var output = bytesOf(ENCODED_RECORDING);
    var avcC = new String(output, StandardCharsets.ISO_8859_1).indexOf("avcC");
    assertThat(avcC).isPositive();
    output[avcC + 8] = (byte) 0xFE;
    var launcher =
        new ScriptedProcessLauncher(_ -> ScriptedProcess.builder().output(output).build());
    var runtime = new ScriptedWorkerRuntime();
    var job = copyJobBuilderFor(recording(ENCODED_RECORDING));
    job.getDecisionBuilder()
        .getVideoBuilder()
        .setSourceDynamicRange(sdrDynamicRange())
        .setOutputDynamicRange(sdrDynamicRange())
        .setAssumedSourceMatrix(MatrixCoefficients.MATRIX_COEFFICIENTS_BT709)
        .addBitstreamRewrites(BitstreamRewrite.BITSTREAM_REWRITE_NAME_ASSUMED_SOURCE_MATRIX);

    try (var worker = workerBuilder(tempDir).runtime(runtime).engine(engine(launcher)).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());
      await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () ->
                  connection.events().stream()
                      .anyMatch(EstablishWorkerSessionRequest::hasJobAttemptFailed));

      assertThat(
              connection.events().stream()
                  .filter(EstablishWorkerSessionRequest::hasJobAttemptFailed)
                  .map(event -> event.getJobAttemptFailed().getFailure()))
          .containsExactly(JobAttemptFailure.JOB_ATTEMPT_FAILURE_OUTPUT_MISMATCH);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidMatrixJobs")
  @DisplayName(
      "Should refuse the job before starting FFmpeg when its copy matrix contradicts the contract")
  void shouldRefuseTheJobBeforeStartingFfmpegWhenItsCopyMatrixContradictsTheContract(
      String condition, UnaryOperator<VideoDecision.Builder> change) throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var runtime = new ScriptedWorkerRuntime();
    var launcher = ScriptedProcessLauncher.running();
    var job = copyJobBuilderFor(recording(ENCODED_RECORDING));
    change.apply(
        job.getDecisionBuilder()
            .getVideoBuilder()
            .setSourceDynamicRange(sdrDynamicRange())
            .setOutputDynamicRange(sdrDynamicRange())
            .setAssumedSourceMatrix(MatrixCoefficients.MATRIX_COEFFICIENTS_SMPTE170M)
            .addBitstreamRewrites(BitstreamRewrite.BITSTREAM_REWRITE_NAME_ASSUMED_SOURCE_MATRIX));

    try (var worker = workerBuilder(tempDir).runtime(runtime).engine(engine(launcher)).build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      startVariant(connection, job.build());
      await()
          .atMost(Duration.ofSeconds(10))
          .until(
              () ->
                  connection.events().stream()
                      .anyMatch(EstablishWorkerSessionRequest::hasJobAttemptFailed));

      assertThat(
              connection.events().stream()
                  .filter(EstablishWorkerSessionRequest::hasJobAttemptFailed)
                  .map(event -> event.getJobAttemptFailed().getFailure()))
          .containsExactly(JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
      assertThat(launcher.hasLaunchedAny()).isFalse();
    }
  }

  static Stream<Arguments> invalidMatrixJobs() {
    return Stream.of(
        invalid("no matrix", VideoDecision.Builder::clearAssumedSourceMatrix),
        invalid("no rewrite", VideoDecision.Builder::clearBitstreamRewrites),
        invalid(
            "unsupported matrix",
            video ->
                video.setAssumedSourceMatrix(MatrixCoefficients.MATRIX_COEFFICIENTS_BT2020_NCL)),
        invalid("unknown matrix", video -> video.setAssumedSourceMatrixValue(999)),
        invalid("HEVC", video -> video.setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC)),
        invalid("AV1", video -> video.setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1)),
        invalid(
            "PQ",
            video ->
                video.setSourceDynamicRange(
                    sdrDynamicRange().toBuilder()
                        .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ))),
        invalid(
            "HLG",
            video ->
                video.setSourceDynamicRange(
                    sdrDynamicRange().toBuilder()
                        .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_HLG))),
        invalid(
            "no compatible base",
            video ->
                video.setSourceDynamicRange(
                    sdrDynamicRange().toBuilder()
                        .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_NONE))),
        invalid(
            "PQ output",
            video ->
                video.setOutputDynamicRange(
                    sdrDynamicRange().toBuilder()
                        .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ))),
        invalid("no planned output", VideoDecision.Builder::clearOutputDynamicRange),
        invalid(
            "duplicate rewrite",
            video ->
                video.addBitstreamRewrites(
                    BitstreamRewrite.BITSTREAM_REWRITE_NAME_ASSUMED_SOURCE_MATRIX)));
  }

  private static Arguments invalid(String condition, UnaryOperator<VideoDecision.Builder> change) {
    return Arguments.of(condition, change);
  }
}
