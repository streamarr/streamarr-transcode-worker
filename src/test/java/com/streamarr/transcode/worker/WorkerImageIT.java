package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.sourceBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.JobAttemptCompleted;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailed;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.JobAttemptStarted;
import build.buf.gen.streamarr.transcode.v1.JobAttemptStopped;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.streamarr.transcode.fixtures.image.ImageControlPlane;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.ContainerLaunchException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

@Tag("ImageTest")
@DisplayName("Worker Image Tests")
@Slf4j
@Timeout(value = 5, unit = TimeUnit.MINUTES)
class WorkerImageIT {

  // A decomposed (NFD) accent, literal percent sequences, and names outside the BMP must all reach
  // FFprobe and FFmpeg unchanged.
  private static final String UNICODE_KEY =
      "東京 Café’s 🎬 %2F ..%2F dir/Ame\u0301lie’s 100%23 #1 한국 𝄞 (2001).mkv";

  // The 10 s fixture at the default 6 s period: the server advertises segments 0 and 1.
  private static final int MEDIA_SEGMENT_COUNT = 2;

  @TempDir Path media;

  @Test
  @DisplayName("Should become ready after registration is accepted when using the default endpoint")
  void shouldBecomeReadyAfterRegistrationIsAcceptedWhenUsingTheDefaultEndpoint() throws Exception {
    try (var image = ImageFixture.builder().media(media).build()) {
      var healthBeforeAcceptance = image.acceptAfterHealthCheck();

      assertThat(healthBeforeAcceptance.liveness()).as("liveness before acceptance").isEqualTo(200);
      assertThat(healthBeforeAcceptance.readiness())
          .as("readiness before acceptance")
          .isEqualTo(503);
      var registration = image.registration();
      assertThat(registration.getWorker().getWorkerId()).isEqualTo(toProto(image.workerId));
      assertThat(registration.getCapabilities().getSourceNamespaceIdsList())
          .containsExactly(toProto(SOURCE_NAMESPACE_ID));
      image.awaitReadiness();
      var identity = image.worker.execInContainer("id", "-u");
      assertThat(identity.getExitCode()).as("id -u failed: %s", identity.getStderr()).isZero();
      var uid = identity.getStdout().trim();
      assertThat(uid).as("id -u output").matches("[0-9]+");
      assertThat(Long.parseLong(uid)).as("worker UID").isPositive();
    }
  }

  @Test
  @DisplayName("Should return media dimensions when the image probes a read-only fixture")
  void shouldReturnMediaDimensionsWhenTheImageProbesAReadOnlyFixture() throws Exception {
    copyMedia();
    var sentinel = media.resolve("write-check");
    Files.writeString(sentinel, "unchanged");
    Files.setPosixFilePermissions(sentinel, PosixFilePermissions.fromString("rw-rw-rw-"));
    try (var image = ImageFixture.builder().media(media).build()) {
      image.accept();
      var request = requestBuilder().build();

      var result = ProbeAttemptResult.parseFrom(image.command("probe", request.toByteArray()));

      assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
      assertThat(result.getProbeVersion()).isEqualTo(1);
      assertThat(result.getMedia().getStreamsList())
          .filteredOn(stream -> "video".equals(stream.getCodecType()))
          .singleElement()
          .satisfies(
              video -> {
                assertThat(video.getCodec()).isEqualTo("h264");
                assertThat(video.getWidth()).isEqualTo(320);
                assertThat(video.getHeight()).isEqualTo(180);
              });
      assertThat(
              image
                  .worker
                  .execInContainer("/bin/sh", "-c", "printf changed > /media/write-check")
                  .getExitCode())
          .isNotZero();
      assertThat(Files.readString(sentinel)).isEqualTo("unchanged");
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"TRANSCODE_MODE_REMUX", "TRANSCODE_MODE_FULL_TRANSCODE"})
  @DisplayName("Should upload decodable segments when the image executes a media job")
  void shouldUploadDecodableSegmentsWhenTheImageExecutesAMediaJob(TranscodeMode mode)
      throws Exception {
    copyMedia();
    try (var image = ImageFixture.builder().media(media).build()) {
      image.accept();
      var job = variantJobBuilder();
      job.getDecisionBuilder().setMode(mode);
      job.getVariantBuilder().setWidth(160).setHeight(90).setBitrateBitsPerSecond(500_000);
      job.getExecutionBuilder().setMediaSegmentCount(MEDIA_SEGMENT_COUNT);
      var request = job.build();

      var completed = JobAttemptCompleted.parseFrom(image.command("job", request.toByteArray()));

      assertThat(completed.getJobAttemptId()).isEqualTo(request.getJobAttemptId());
      var segment = image.command("segment", new byte[0]);
      assertThat(segment).isNotEmpty();
      var uploaded = media.resolve("uploaded.mp4");
      Files.write(uploaded, segment);
      var probe =
          image.worker.execInContainer(
              "/cnb/lifecycle/launcher",
              "ffprobe",
              "-v",
              "error",
              "-select_streams",
              "v:0",
              "-show_entries",
              "stream=codec_name,width,height",
              "-of",
              "json",
              "-o",
              "/tmp/uploaded.json",
              "/media/uploaded.mp4");
      assertThat(probe.getExitCode()).as(probe.getStderr()).isZero();
      var output = image.worker.execInContainer("cat", "/tmp/uploaded.json");
      assertThat(output.getExitCode()).as(output.getStderr()).isZero();
      var video = new ObjectMapper().readTree(output.getStdout()).path("streams").get(0);
      assertThat(video.path("codec_name").asString()).isEqualTo("h264");
      var height = mode == TranscodeMode.TRANSCODE_MODE_REMUX ? 180 : 90;
      assertThat(video.path("width").asInt()).isEqualTo(height * 16 / 9);
      assertThat(video.path("height").asInt()).isEqualTo(height);
      var decode =
          image.worker.execInContainer(
              "/cnb/lifecycle/launcher",
              "ffmpeg",
              "-v",
              "error",
              "-xerror",
              "-i",
              "/media/uploaded.mp4",
              "-f",
              "null",
              "-");
      assertThat(decode.getExitCode()).as(decode.getStderr()).isZero();
    }
  }

  @Test
  @DisplayName(
      "Should probe and stream a Unicode source key when the image runs with its default locale")
  void shouldProbeAndStreamUnicodeSourceKeyWhenImageRunsWithItsDefaultLocale() throws Exception {
    copyMedia(UNICODE_KEY);
    var ffmpeg = recordingExecutable("ffmpeg");
    var ffprobe = recordingExecutable("ffprobe");
    try (var image =
        ImageFixture.builder().media(media).ffmpegPath(ffmpeg).ffprobePath(ffprobe).build()) {
      image.accept();
      var probeRequest = requestBuilder().setSource(sourceBuilder().setRelativeKey(UNICODE_KEY));

      var probe =
          ProbeAttemptResult.parseFrom(image.command("probe", probeRequest.build().toByteArray()));

      assertThat(probe.getMedia().getStreamsList())
          .filteredOn(stream -> "video".equals(stream.getCodecType()))
          .singleElement()
          .satisfies(video -> assertThat(video.getCodec()).isEqualTo("h264"));
      assertThat(image.recordedArguments("ffprobe")).contains("/media/" + UNICODE_KEY);

      var jobBuilder = variantJobBuilder().setSource(sourceBuilder().setRelativeKey(UNICODE_KEY));
      jobBuilder.getExecutionBuilder().setMediaSegmentCount(MEDIA_SEGMENT_COUNT);
      var job = jobBuilder.build();

      var completed = JobAttemptCompleted.parseFrom(image.command("job", job.toByteArray()));

      assertThat(completed.getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      assertThat(image.command("segment", new byte[0])).isNotEmpty();
      assertThat(image.recordedArguments("ffmpeg")).contains("/media/" + UNICODE_KEY);
    }
  }

  @Test
  @DisplayName(
      "Should diagnose the locale and fail the probe for retry when the image runs under POSIX")
  void shouldDiagnoseLocaleAndFailProbeForRetryWhenImageRunsUnderPosix() throws Exception {
    copyMedia(UNICODE_KEY);
    try (var image = ImageFixture.builder().media(media).filenameLocale("POSIX").build()) {
      image.accept();
      var request = requestBuilder().setSource(sourceBuilder().setRelativeKey(UNICODE_KEY)).build();

      var result = ProbeAttemptResult.parseFrom(image.command("probe", request.toByteArray()));

      assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE);
      assertThat(image.worker.getLogs())
          .contains("rather than UTF-8")
          .contains("the effective locale is LC_ALL=POSIX");
    }
  }

  @Test
  @DisplayName(
      "Should diagnose the locale before startup fails when a POSIX worker has a non-ASCII root")
  void shouldDiagnoseLocaleBeforeStartupFailsWhenPosixWorkerHasNonAsciiRoot() {
    // A container that fails to launch no longer serves getLogs(), so collect output as it streams.
    var output = new ToStringConsumer();
    try (var worker =
        new GenericContainer<>(workerImage())
            .withImagePullPolicy(_ -> false)
            .withLogConsumer(
                output.andThen(new Slf4jLogConsumer(log).withPrefix("posix-root-worker")))
            .withEnv("LC_ALL", "POSIX")
            .withEnv("TRANSCODE_WORKER_ID", UUID.randomUUID().toString())
            .withEnv("TRANSCODE_WORKER_SOURCE_NAMESPACE_ID", SOURCE_NAMESPACE_ID.toString())
            .withEnv("TRANSCODE_WORKER_SOURCE_ROOT", "/media/Café")
            .withStartupCheckStrategy(
                new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(1)))) {
      assertThatThrownBy(worker::start).isInstanceOf(ContainerLaunchException.class);

      assertThat(output.toUtf8String())
          .containsSubsequence(
              "the effective locale is LC_ALL=POSIX", "InvalidPathException", "/media/Caf");
    }
  }

  private void copyMedia(String relativeKey) throws Exception {
    var source = getClass().getResource("/BigBuckBunny_320x180_10s.mp4");
    assertThat(source).isNotNull();
    var target = media.resolve(relativeKey);
    Files.createDirectories(target.getParent());
    Files.setPosixFilePermissions(target.getParent(), PosixFilePermissions.fromString("rwxr-xr-x"));
    Files.copy(Path.of(source.toURI()), target);
  }

  private static String workerImage() {
    var image = System.getProperty("worker.image");
    assertThat(image).as("Run image tests with -Dworker.image=<locally built image>").isNotBlank();
    return image;
  }

  private String recordingExecutable(String name) throws Exception {
    var script = media.resolve("recording-" + name);
    Files.writeString(
        script,
        """
        #!/bin/bash
        printf '%%s\\0' "$@" > /tmp/%s-arguments
        exec %s "$@"
        """
            .formatted(name, name));
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    return "/media/recording-" + name;
  }

  private void copyMedia() throws Exception {
    copyMedia("movie.mkv");
  }

  @Test
  @DisplayName("Should report the injected failure when the image uses a mounted executable")
  void shouldReportTheInjectedFailureWhenTheImageUsesAMountedExecutable() throws Exception {
    copyMedia();
    var script = scriptedFfmpeg("exit 73");
    try (var image = ImageFixture.builder().media(media).ffmpegPath(script).build()) {
      image.accept();
      var request = variantJobBuilder().build();

      var failed = JobAttemptFailed.parseFrom(image.command("failed-job", request.toByteArray()));

      assertThat(failed.getJobAttemptId()).isEqualTo(request.getJobAttemptId());
      assertThat(failed.getFailure())
          .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
      assertThat(image.health("readiness").statusCode()).isEqualTo(200);
    }
  }

  @Test
  @DisplayName("Should stop the media process gracefully when the container receives termination")
  void shouldStopTheMediaProcessGracefullyWhenTheContainerReceivesTermination() throws Exception {
    copyMedia();
    var script =
        scriptedFfmpeg(
            """
        read -r -n 1 command
        if [[ $command == q ]]; then
          printf q > /tmp/worker-image-graceful-stop
          exit 0
        fi
        exit 71
        """);
    try (var image = ImageFixture.builder().media(media).ffmpegPath(script).build()) {
      image.accept();
      var request = variantJobBuilder().build();
      var started = JobAttemptStarted.parseFrom(image.command("start-job", request.toByteArray()));
      assertThat(started.getJobAttemptId()).isEqualTo(request.getJobAttemptId());

      image
          .worker
          .getDockerClient()
          .stopContainerCmd(image.worker.getContainerId())
          .withTimeout(10)
          .exec();

      var marker = media.resolve("graceful-stop");
      image.worker.copyFileFromContainer("/tmp/worker-image-graceful-stop", marker.toString());
      assertThat(Files.readString(marker)).isEqualTo("q");
      assertThat(image.worker.getCurrentContainerInfo().getState().getExitCodeLong())
          .isIn(0L, 143L);
      assertThat(new String(image.command("disconnected", new byte[0]), StandardCharsets.UTF_8))
          .isEqualTo("true");
    }
  }

  @Test
  @DisplayName(
      "Should let FFmpeg exit and report the stop when the image stops a job whose upload awaits"
          + " acknowledgement")
  void shouldLetFfmpegExitAndReportTheStopWhenTheImageStopsAJobWhoseUploadAwaitsAcknowledgement()
      throws Exception {
    copyMedia();
    // FFmpeg reads the source at its native rate, so it is still remuxing when the stop arrives. It
    // runs as this wrapper's child, and the wrapper records its exit status, which a forced kill of
    // the wrapper would never write.
    var exitFile = "/tmp/worker-image-ffmpeg-exit";
    var script =
        scriptedFfmpeg(
            """
        ffmpeg -re "$@"
        status=$?
        printf '%%s' "$status" > %s
        exit "$status"
        """
                .formatted(exitFile));
    try (var image = ImageFixture.builder().media(media).ffmpegPath(script).build()) {
      image.accept();
      var request = variantJobBuilder().build();
      image.command("job-awaiting-acknowledgement", request.toByteArray());
      assertThat(image.worker.execInContainer("test", "-e", exitFile).getExitCode())
          .as("FFmpeg was still running when the stop was sent")
          .isNotZero();

      var stopped = JobAttemptStopped.parseFrom(image.command("stop-job", request.toByteArray()));

      assertThat(stopped.getJobAttemptId()).isEqualTo(request.getJobAttemptId());
      var exitStatus = image.worker.execInContainer("cat", exitFile);
      assertThat(exitStatus.getExitCode())
          .as("the wrapper recorded FFmpeg's exit, so no forced kill ended it")
          .isZero();
      assertThat(exitStatus.getStdout()).containsPattern("^[0-9]+$");
      assertThat(image.health("readiness").statusCode()).isEqualTo(200);
    }
  }

  private String scriptedFfmpeg(String jobBehavior) throws Exception {
    var script = media.resolve("scripted-ffmpeg");
    Files.writeString(
        script,
        """
        #!/bin/bash
        for argument in "$@"; do
          if [[ $argument == /media/movie.mkv ]]; then
        %s
          fi
        done
        exec ffmpeg "$@"
        """
            .formatted(jobBehavior.indent(4)));
    Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
    return "/media/scripted-ffmpeg";
  }

  private record HealthBeforeAcceptance(int liveness, int readiness) {}

  @DisplayName("Worker Image Fixture")
  private static final class ImageFixture implements AutoCloseable {

    private final UUID workerId = UUID.randomUUID();
    private final GenericContainer<?> controlPlane;
    private final GenericContainer<?> worker;
    private final CompletableFuture<Integer> workerExit = new CompletableFuture<>();
    private WaitContainerResultCallback workerExitWait;
    private final HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Builder
    private ImageFixture(Path media, String ffmpegPath, String ffprobePath, String filenameLocale)
        throws IOException {
      Files.setPosixFilePermissions(media, PosixFilePermissions.fromString("rwxr-xr-x"));
      var image = workerImage();
      controlPlane =
          new GenericContainer<>(image)
              .withImagePullPolicy(_ -> false)
              .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("control-plane-" + workerId))
              .withCopyFileToContainer(
                  MountableFile.forHostPath("target/test-classes"), "/test-classes")
              .withExposedPorts(8082, 9091)
              .withCreateContainerCmdModifier(
                  command -> command.withEntrypoint("/cnb/lifecycle/launcher"))
              .withCommand(
                  "java",
                  "--enable-native-access=ALL-UNNAMED",
                  "-cp",
                  "/test-classes:/workspace/BOOT-INF/classes:/workspace/BOOT-INF/lib/*",
                  ImageControlPlane.class.getName())
              .waitingFor(Wait.forHttp("/ready").forPort(8082));
      controlPlane.start();
      worker =
          new GenericContainer<>(image)
              .withImagePullPolicy(_ -> false)
              .withLogConsumer(new Slf4jLogConsumer(log).withPrefix("worker-" + workerId))
              .withNetworkMode("container:" + controlPlane.getContainerId())
              .withEnv("TRANSCODE_WORKER_ID", workerId.toString())
              .withEnv("TRANSCODE_WORKER_SOURCE_NAMESPACE_ID", SOURCE_NAMESPACE_ID.toString())
              .withEnv("TRANSCODE_WORKER_SOURCE_ROOT", "/media")
              .withFileSystemBind(media.toAbsolutePath().toString(), "/media", BindMode.READ_ONLY);
      if (ffmpegPath != null) {
        worker.withEnv("TRANSCODE_WORKER_FFMPEG_PATH", ffmpegPath);
      }

      if (ffprobePath != null) {
        worker.withEnv("TRANSCODE_WORKER_FFPROBE_PATH", ffprobePath);
      }

      if (filenameLocale != null) {
        worker.withEnv("LC_ALL", filenameLocale);
      }
      try {
        worker.start();
        watchWorkerExit();
      } catch (RuntimeException failure) {
        close();
        throw failure;
      }
    }

    private void watchWorkerExit() {
      workerExitWait = worker.getDockerClient().waitContainerCmd(worker.getContainerId()).start();
      Thread.ofVirtual()
          .start(
              () -> {
                try {
                  workerExit.complete(workerExitWait.awaitStatusCode());
                } catch (RuntimeException failure) {
                  workerExit.completeExceptionally(failure);
                }
              });
    }

    private WorkerRegistration registration() throws Exception {
      return WorkerRegistration.parseFrom(commandBeforeWorkerExit("registration"));
    }

    // The control plane waits for the registration before it accepts. A worker that exits first
    // fails this wait at once.
    private void accept() throws Exception {
      commandBeforeWorkerExit("accept");
    }

    private HealthBeforeAcceptance acceptAfterHealthCheck() throws Exception {
      var statuses =
          new String(commandBeforeWorkerExit("accept-after-health-check"), StandardCharsets.UTF_8)
              .split(" ");
      return new HealthBeforeAcceptance(
          Integer.parseInt(statuses[0]), Integer.parseInt(statuses[1]));
    }

    // Spring reports readiness only once the worker's start has returned, and nothing outside the
    // worker can observe that moment, so this polls. The poll fails at once when the worker exits.
    // It has no time limit of its own; it runs on the test thread, so the class timeout's interrupt
    // ends it.
    private void awaitReadiness() {
      await()
          .pollInSameThread()
          .forever()
          .failFast("The worker exited before it became ready", workerExit::isDone)
          .until(() -> health("readiness").statusCode() == 200);
    }

    private byte[] commandBeforeWorkerExit(String path) throws Exception {
      var response =
          http.sendAsync(
              commandRequest(path, new byte[0]), HttpResponse.BodyHandlers.ofByteArray());
      CompletableFuture.anyOf(response, workerExit).get();
      if (!response.isDone()) {
        throw new AssertionError(
            "The worker exited with status %d before the control plane answered %s%n%s"
                .formatted(workerExit.resultNow(), path, worker.getLogs()));
      }

      return successfulBody(path, response.get());
    }

    private byte[] command(String path, byte[] body) throws Exception {
      return successfulBody(
          path, http.send(commandRequest(path, body), HttpResponse.BodyHandlers.ofByteArray()));
    }

    private HttpRequest commandRequest(String path, byte[] body) {
      var endpoint =
          URI.create(
              "http://"
                  + controlPlane.getHost()
                  + ":"
                  + controlPlane.getMappedPort(8082)
                  + "/"
                  + path);
      return HttpRequest.newBuilder(endpoint)
          .timeout(Duration.ofSeconds(60))
          .POST(HttpRequest.BodyPublishers.ofByteArray(body))
          .build();
    }

    private static byte[] successfulBody(String path, HttpResponse<byte[]> response) {
      assertThat(response.statusCode())
          .as("Command %s failed: %s", path, new String(response.body(), StandardCharsets.UTF_8))
          .isEqualTo(200);
      return response.body();
    }

    // Read as base64 so no charset on the exec path can alter the recorded filename bytes.
    private List<String> recordedArguments(String executable) throws Exception {
      var recorded = worker.execInContainer("base64", "-w0", "/tmp/" + executable + "-arguments");
      assertThat(recorded.getExitCode()).as(recorded.getStderr()).isZero();
      var arguments =
          new String(
              Base64.getDecoder().decode(recorded.getStdout().trim()), StandardCharsets.UTF_8);
      return List.of(arguments.split("\\x00"));
    }

    private HttpResponse<String> health(String group) throws Exception {
      var endpoint =
          URI.create(
              "http://"
                  + controlPlane.getHost()
                  + ":"
                  + controlPlane.getMappedPort(9091)
                  + "/actuator/health/"
                  + group);
      return http.send(
          HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10)).GET().build(),
          HttpResponse.BodyHandlers.ofString());
    }

    @Override
    public void close() {
      worker.close();
      controlPlane.close();
      http.close();
      if (workerExitWait != null) {
        try {
          workerExitWait.close();
        } catch (IOException failure) {
          throw new UncheckedIOException(failure);
        }
      }
    }
  }
}
