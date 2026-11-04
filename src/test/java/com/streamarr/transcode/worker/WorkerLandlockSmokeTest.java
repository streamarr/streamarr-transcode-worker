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
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import com.streamarr.transcode.engine.ProcessBuilderLauncher;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The worker checks a source's path for soft links before it starts a tool, and a link swapped in
 * after that check would lead the tool anywhere. These tests swap one in at exactly that moment and
 * prove that Landlock keeps the tool inside the media root, while the same policy lets an ordinary
 * probe and job run.
 */
@Tag("SmokeTest")
@EnabledOnOs(OS.LINUX)
@DisplayName("Worker Landlock Smoke Tests")
@Timeout(120)
class WorkerLandlockSmokeTest {

  private static final int PUBLIC_WIDTH = 320;
  private static final int SECRET_WIDTH = 128;

  @TempDir Path base;
  private Path media;
  private Path outside;
  private Path temporaryDirectory;

  @BeforeEach
  void arrangeSources() throws Exception {
    media = Files.createDirectory(base.resolve("media"));
    outside = Files.createDirectory(base.resolve("outside"));
    temporaryDirectory = Files.createDirectory(base.resolve("tmp"));
    source(media.resolve("movie.mp4"), PUBLIC_WIDTH);
    source(Files.createDirectory(media.resolve("show")).resolve("episode.mp4"), PUBLIC_WIDTH);
    source(outside.resolve("secret.mp4"), SECRET_WIDTH);
    source(outside.resolve("episode.mp4"), SECRET_WIDTH);
  }

  @Test
  @DisplayName("Should probe and transcode a source inside the media root when FFmpeg is confined")
  void shouldProbeAndTranscodeASourceInsideTheMediaRootWhenFfmpegIsConfined() throws Exception {
    var runtime = new ScriptedWorkerRuntime();
    try (var worker = worker(runtime, confinement())) {
      worker.start("localhost", 1);
      var connection = runtime.connection();

      probe(runtime, "movie.mp4");
      assertThat(connection.results()).singleElement().satisfies(WorkerLandlockSmokeTest::isPublic);

      startVariant(connection, encode("movie.mp4"));
      awaitAttemptEnd(connection);
      assertThat(connection.events())
          .noneMatch(EstablishWorkerSessionRequest::hasJobAttemptFailed)
          .anyMatch(EstablishWorkerSessionRequest::hasJobAttemptCompleted);
      assertThat(connection.uploads())
          .extracting(upload -> upload.metadata().getSegmentName())
          .containsExactly("init.mp4", "segment0.m4s", "segment1.m4s");
    }
  }

  @Test
  @DisplayName(
      "Should fail the probe without reading the target when a soft link to a file outside the"
          + " media root replaces the source after the worker's check")
  void
      shouldFailTheProbeWithoutReadingTheTargetWhenASoftLinkToAFileOutsideTheMediaRootReplacesTheSourceAfterTheWorkersCheck()
          throws Exception {
    var runtime = new ScriptedWorkerRuntime();
    try (var worker = worker(runtime, swappingFile(confinement()))) {
      worker.start("localhost", 1);

      probe(runtime, "movie.mp4");

      assertThat(Files.isSymbolicLink(media.resolve("movie.mp4"))).as("the swap happened").isTrue();
      assertThat(runtime.connection().results())
          .singleElement()
          .satisfies(
              result -> {
                assertThat(result.hasMedia()).isFalse();
                assertThat(result.getFailure())
                    .isEqualTo(ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE);
              });
    }
  }

  @Test
  @DisplayName(
      "Should fail the probe without reading the target when a soft link to a directory outside"
          + " the media root replaces the source's directory after the worker's check")
  void
      shouldFailTheProbeWithoutReadingTheTargetWhenASoftLinkToADirectoryOutsideTheMediaRootReplacesTheSourcesDirectoryAfterTheWorkersCheck()
          throws Exception {
    var runtime = new ScriptedWorkerRuntime();
    try (var worker = worker(runtime, swappingDirectory(confinement()))) {
      worker.start("localhost", 1);

      probe(runtime, "show/episode.mp4");

      assertThat(Files.isSymbolicLink(media.resolve("show"))).as("the swap happened").isTrue();
      assertThat(runtime.connection().results())
          .singleElement()
          .satisfies(
              result -> {
                assertThat(result.hasMedia()).isFalse();
                assertThat(result.getFailure())
                    .isEqualTo(ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE);
              });
    }
  }

  @Test
  @DisplayName(
      "Should fail the job attempt without uploading when a soft link to a file outside the media"
          + " root replaces the source after the worker's check")
  void
      shouldFailTheJobAttemptWithoutUploadingWhenASoftLinkToAFileOutsideTheMediaRootReplacesTheSourceAfterTheWorkersCheck()
          throws Exception {
    var runtime = new ScriptedWorkerRuntime();
    try (var worker = worker(runtime, swappingFile(confinement()))) {
      worker.start("localhost", 1);
      var connection = runtime.connection();

      startVariant(connection, encode("movie.mp4"));
      awaitAttemptEnd(connection);

      assertThat(Files.isSymbolicLink(media.resolve("movie.mp4"))).as("the swap happened").isTrue();
      assertThat(connection.events())
          .noneMatch(EstablishWorkerSessionRequest::hasJobAttemptCompleted)
          .filteredOn(EstablishWorkerSessionRequest::hasJobAttemptFailed)
          .singleElement()
          .extracting(event -> event.getJobAttemptFailed().getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(connection.uploads()).isEmpty();
    }
  }

  // The control: the same swap leads an unconfined ffprobe to the file outside the media root, so
  // the worker's check alone does not close the race, and the tests above pass because of Landlock.
  @Test
  @DisplayName(
      "Should report the file outside the media root when a soft link replaces the source after"
          + " the worker's check and FFmpeg runs unconfined")
  void
      shouldReportTheFileOutsideTheMediaRootWhenASoftLinkReplacesTheSourceAfterTheWorkersCheckAndFfmpegRunsUnconfined()
          throws Exception {
    var runtime = new ScriptedWorkerRuntime();
    try (var worker = worker(runtime, swappingFile(UnaryOperator.identity()))) {
      worker.start("localhost", 1);

      probe(runtime, "movie.mp4");

      assertThat(runtime.connection().results())
          .singleElement()
          .satisfies(
              result -> {
                assertThat(result.hasMedia()).isTrue();
                assertThat(result.getMedia().getStreams(0).getWidth()).isEqualTo(SECRET_WIDTH);
              });
    }
  }

  private TranscodeWorker worker(
      ScriptedWorkerRuntime runtime, UnaryOperator<ProcessBuilder> confinement) {
    return workerBuilder(media)
        .runtime(runtime)
        .ffprobe(FfprobeExecutor.forBinaries(Path.of("ffprobe"), Path.of("ffmpeg"), confinement))
        .engine(engine(new ProcessBuilderLauncher(confinement)))
        .build();
  }

  // The worker's policy for this host, with a temporary directory of its own: the JVM's would hold
  // the whole fixture, the files outside the media root included.
  private UnaryOperator<ProcessBuilder> confinement() {
    return HostConfinement.landlock(HostConfinement.policy(List.of(media), temporaryDirectory));
  }

  // The tool's process builder is prepared after the worker's soft-link check and before the tool
  // starts, which is when a swap defeats the check.
  private UnaryOperator<ProcessBuilder> swappingFile(UnaryOperator<ProcessBuilder> confinement) {
    return swapping(
        confinement,
        () -> {
          Files.delete(media.resolve("movie.mp4"));
          Files.createSymbolicLink(media.resolve("movie.mp4"), outside.resolve("secret.mp4"));
        });
  }

  private UnaryOperator<ProcessBuilder> swappingDirectory(
      UnaryOperator<ProcessBuilder> confinement) {
    return swapping(
        confinement,
        () -> {
          Files.move(media.resolve("show"), media.resolve("show-moved"));
          Files.createSymbolicLink(media.resolve("show"), outside);
        });
  }

  private static UnaryOperator<ProcessBuilder> swapping(
      UnaryOperator<ProcessBuilder> confinement, Swap swap) {
    var swapped = new AtomicBoolean();
    return builder -> {
      if (swapped.compareAndSet(false, true)) {
        try {
          swap.perform();
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }

      return confinement.apply(builder);
    };
  }

  private static void probe(ScriptedWorkerRuntime runtime, String relativeKey) throws Exception {
    var probe = requestBuilder();
    probe.getSourceBuilder().setRelativeKey(relativeKey);
    start(runtime.connection(), probe.build());
    runtime.probes().drain();
  }

  private static void isPublic(ProbeAttemptResult result) {
    assertThat(result.hasMedia()).isTrue();
    assertThat(result.getMedia().getStreams(0).getWidth()).isEqualTo(PUBLIC_WIDTH);
  }

  private static void awaitAttemptEnd(ScriptedWorkerRuntime.Connection connection) {
    await()
        .atMost(Duration.ofSeconds(60))
        .until(
            () ->
                connection.events().stream()
                    .anyMatch(
                        event -> event.hasJobAttemptCompleted() || event.hasJobAttemptFailed()));
  }

  private static VariantJob encode(String relativeKey) {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE);
    job.getSourceBuilder().setRelativeKey(relativeKey);
    job.getDecisionBuilder().getAudioBuilder().setMode(AudioMode.AUDIO_MODE_NONE);
    job.getDecisionBuilder().getVideoBuilder().setLevel(20).setCodecString("avc1.640014");
    job.getVariantBuilder().setWidth(PUBLIC_WIDTH).setHeight(180).setBitrateBitsPerSecond(200_000);
    job.getExecutionBuilder().setTargetSegmentDurationSeconds(1).setMediaSegmentCount(2);
    return job.build();
  }

  // Two seconds of a test pattern at the given width; the tests tell the sources apart by width.
  private void source(Path file, int width) throws Exception {
    var height = width * 9 / 16;
    var output = Files.createTempFile(base, "ffmpeg", ".txt");
    var process =
        new ProcessBuilder(
                "ffmpeg",
                "-nostdin",
                "-v",
                "error",
                "-y",
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=" + width + "x" + height + ":rate=24",
                "-t",
                "2",
                "-c:v",
                "libx264",
                file.toString())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("ffmpeg writes %s", file).isTrue();
      assertThat(process.exitValue()).as(Files.readString(output)).isZero();
    } finally {
      process.destroyForcibly();
    }
  }

  @FunctionalInterface
  private interface Swap {
    void perform() throws IOException;
  }
}
