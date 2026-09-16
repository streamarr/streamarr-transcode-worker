package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.JobAttemptCompleted;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailed;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.JobAttemptStarted;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import com.streamarr.transcode.fixtures.image.ImageControlPlane;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.UUID;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.ObjectMapper;

@Tag("ImageTest")
@DisplayName("Worker Image Tests")
class WorkerImageIT {

  @TempDir Path media;

  @Test
  @DisplayName("Should become ready after registration is accepted when using the default endpoint")
  void shouldBecomeReadyAfterRegistrationIsAcceptedWhenUsingTheDefaultEndpoint() throws Exception {
    try (var image = ImageFixture.builder().media(media).build()) {
      var registration = image.registration();
      assertThat(registration.getWorker().getWorkerId()).isEqualTo(toProto(image.workerId));
      assertThat(registration.getCapabilities().getSourceNamespaceIdsList())
          .containsExactly(toProto(SOURCE_NAMESPACE_ID));
      assertThat(image.health("liveness").statusCode()).isEqualTo(200);
      assertThat(image.health("readiness").statusCode()).isEqualTo(503);

      image.accept();

      await()
          .atMost(Duration.ofSeconds(10))
          .untilAsserted(() -> assertThat(image.health("readiness").statusCode()).isEqualTo(200));
      assertThat(image.worker.execInContainer("id", "-u").getStdout().trim()).isNotEqualTo("0");
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
      var request = job.build();

      var completed = JobAttemptCompleted.parseFrom(image.command("job", request.toByteArray()));

      assertThat(completed.getJobAttemptId()).isEqualTo(request.getJobAttemptId());
      var segment = image.command("segment", new byte[0]);
      assertThat(segment).isNotEmpty();
      var uploaded = media.resolve("uploaded.ts");
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
              "/media/uploaded.ts");
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
              "/media/uploaded.ts",
              "-f",
              "null",
              "-");
      assertThat(decode.getExitCode()).as(decode.getStderr()).isZero();
    }
  }

  private void copyMedia() throws Exception {
    var source = getClass().getResource("/BigBuckBunny_320x180_10s.mp4");
    assertThat(source).isNotNull();
    Files.copy(Path.of(source.toURI()), media.resolve("movie.mkv"));
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

  private static final class ImageFixture implements AutoCloseable {

    private final UUID workerId = UUID.randomUUID();
    private final GenericContainer<?> controlPlane;
    private final GenericContainer<?> worker;
    private final HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Builder
    private ImageFixture(Path media, String ffmpegPath) throws IOException {
      Files.setPosixFilePermissions(media, PosixFilePermissions.fromString("rwxr-xr-x"));
      var image = System.getProperty("worker.image");
      assertThat(image)
          .as("Run image tests with -Dworker.image=<locally built image>")
          .isNotBlank();
      controlPlane =
          new GenericContainer<>(image)
              .withImagePullPolicy(_ -> false)
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
              .withNetworkMode("container:" + controlPlane.getContainerId())
              .withEnv("TRANSCODE_WORKER_ID", workerId.toString())
              .withEnv("TRANSCODE_WORKER_SOURCE_NAMESPACE_ID", SOURCE_NAMESPACE_ID.toString())
              .withEnv("TRANSCODE_WORKER_SOURCE_ROOT", "/media")
              .withEnv("TRANSCODE_WORKER_SEGMENT_BASE_PATH", "/tmp/segments")
              .withFileSystemBind(media.toAbsolutePath().toString(), "/media", BindMode.READ_ONLY)
              .waitingFor(Wait.forLogMessage(".*Started TranscodeWorkerApplication.*", 1));
      if (ffmpegPath != null) {
        worker.withEnv("TRANSCODE_WORKER_FFMPEG_PATH", ffmpegPath);
      }
      try {
        worker.start();
      } catch (RuntimeException failure) {
        close();
        throw failure;
      }
    }

    private WorkerRegistration registration() throws Exception {
      return WorkerRegistration.parseFrom(command("registration", new byte[0]));
    }

    private void accept() throws Exception {
      command("accept", new byte[0]);
    }

    private byte[] command(String path, byte[] body) throws Exception {
      var endpoint =
          URI.create(
              "http://"
                  + controlPlane.getHost()
                  + ":"
                  + controlPlane.getMappedPort(8082)
                  + "/"
                  + path);
      var response =
          http.send(
              HttpRequest.newBuilder(endpoint)
                  .timeout(Duration.ofSeconds(60))
                  .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                  .build(),
              HttpResponse.BodyHandlers.ofByteArray());
      assertThat(response.statusCode()).isEqualTo(200);
      return response.body();
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
    }
  }
}
