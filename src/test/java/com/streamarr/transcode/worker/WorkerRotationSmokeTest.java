package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.engine;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.startVariant;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.AudioMode;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.engine.ProcessBuilderLauncher;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("SmokeTest")
@DisplayName("Worker Rotation Smoke Tests")
@Timeout(90)
class WorkerRotationSmokeTest {

  @TempDir Path sourceRoot;

  @ParameterizedTest(name = "{0} degrees")
  @CsvSource({
    "89.5, 90, 180x320",
    "-89.5, -90, 180x320",
    "90.9, 91, 320x180",
    "-90.9, -91, 320x180"
  })
  @DisplayName("Should upload the planned dimensions when a source has a fractional rotation")
  void shouldUploadThePlannedDimensionsWhenASourceHasAFractionalRotation(
      String sourceDegrees, int appliedDegrees, String expectedSize) throws Exception {
    var source = rotatedSource(sourceDegrees);
    var runtime = new ScriptedWorkerRuntime();
    try (var worker =
        workerBuilder(sourceRoot)
            .runtime(runtime)
            .ffprobe(FfprobeExecutor.forBinaries(Path.of("ffprobe"), Path.of("ffmpeg")))
            .engine(engine(new ProcessBuilderLauncher()))
            .build()) {
      worker.start("localhost", 1);
      var connection = runtime.connection();
      var probe = requestBuilder();
      probe.getSourceBuilder().setRelativeKey(source.getFileName().toString());
      start(connection, probe.build());
      runtime.probes().drain();

      assertThat(connection.results())
          .singleElement()
          .satisfies(result -> assertThat(result.hasMedia()).isTrue());
      var video = connection.results().getFirst().getMedia().getStreams(0);
      assertThat(video.getRotationDegrees()).isEqualTo(appliedDegrees);
      assertThat(video.getWidth()).isEqualTo(320);
      assertThat(video.getHeight()).isEqualTo(180);

      startVariant(connection, encode(source, video));
      await()
          .atMost(Duration.ofSeconds(30))
          .until(
              () ->
                  connection.events().stream()
                      .anyMatch(
                          event -> event.hasJobAttemptCompleted() || event.hasJobAttemptFailed()));
      assertThat(connection.events())
          .noneMatch(EstablishWorkerSessionRequest::hasJobAttemptFailed)
          .anyMatch(EstablishWorkerSessionRequest::hasJobAttemptCompleted);
      assertThat(connection.uploads())
          .extracting(upload -> upload.metadata().getSegmentName())
          .containsExactly("init.mp4", "segment0.m4s", "segment1.m4s");

      var uploaded = sourceRoot.resolve("uploaded.mp4");
      var content = new ByteArrayOutputStream();
      connection.uploads().forEach(upload -> content.writeBytes(upload.content()));
      Files.write(uploaded, content.toByteArray());
      assertThat(
              run(List.of(
                      "ffprobe",
                      "-v",
                      "error",
                      "-select_streams",
                      "v:0",
                      "-show_entries",
                      "stream=width,height",
                      "-of",
                      "csv=p=0:s=x",
                      uploaded.toString()))
                  .strip())
          .isEqualTo(expectedSize);
      run(List.of("ffmpeg", "-v", "error", "-i", uploaded.toString(), "-f", "null", "-"));
    }
  }

  private VariantJob encode(Path source, ProbeStreamInfo video) {
    var transposed = Math.abs(video.getRotationDegrees()) == 90;
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE);
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    job.getDecisionBuilder().getAudioBuilder().setMode(AudioMode.AUDIO_MODE_NONE);
    job.getDecisionBuilder().getVideoBuilder().setLevel(20).setCodecString("avc1.640014");
    job.getVariantBuilder()
        .setWidth(transposed ? video.getHeight() : video.getWidth())
        .setHeight(transposed ? video.getWidth() : video.getHeight())
        .setBitrateBitsPerSecond(200_000);
    job.getExecutionBuilder().setTargetSegmentDurationSeconds(1).setMediaSegmentCount(2);
    return job.build();
  }

  private Path rotatedSource(String degrees) throws Exception {
    var base = sourceRoot.resolve("base.mp4");
    run(
        List.of(
            "ffmpeg",
            "-nostdin",
            "-v",
            "error",
            "-y",
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=320x180:rate=24",
            "-t",
            "2",
            "-c:v",
            "libx264",
            base.toString()));
    var source = sourceRoot.resolve("rotated.mp4");
    run(
        List.of(
            "ffmpeg",
            "-nostdin",
            "-v",
            "error",
            "-y",
            "-display_rotation",
            degrees,
            "-i",
            base.toString(),
            "-c:v",
            "copy",
            source.toString()));
    return source;
  }

  private String run(List<String> command) throws Exception {
    var output = Files.createTempFile(sourceRoot, "process", ".txt");
    var process =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertThat(process.waitFor(30, TimeUnit.SECONDS)).as(command.getFirst()).isTrue();
      var result = Files.readString(output);
      assertThat(process.exitValue()).as(result).isZero();
      return result;
    } finally {
      process.destroyForcibly();
    }
  }
}
