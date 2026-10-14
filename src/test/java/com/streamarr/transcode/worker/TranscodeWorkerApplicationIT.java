package com.streamarr.transcode.worker;

import static com.streamarr.transcode.fixtures.FfmpegMuxerHelpFixtures.FRAGMENTED_MP4_MUXER_HELP;
import static com.streamarr.transcode.fixtures.RemoteWorkerFixtures.SOURCE_NAMESPACE_ID;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import build.buf.gen.streamarr.transcode.v1.MediaSourceRef;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.worker.support.WorkerApplicationControlPlane;
import com.streamarr.transcode.worker.support.WorkerApplicationProcess;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerPortFileWriter;

@Tag("IntegrationTest")
@DisplayName("Transcode Worker Application Integration Tests")
@Timeout(value = 2, unit = TimeUnit.MINUTES)
class TranscodeWorkerApplicationIT {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should serve Actuator liveness when the worker application starts")
  void shouldServeActuatorLivenessWhenTheWorkerApplicationStarts() throws Exception {
    var portFile = tempDir.resolve("http.port");
    try (var controlPlane = WorkerApplicationControlPlane.builder().build();
        var client = HttpClient.newHttpClient()) {
      var fixture =
          ApplicationFixture.builder()
              .port(controlPlane.port())
              .ffprobe(versionOnlyFfprobe())
              .mainClass(PortReportingStartup.class)
              .build();
      var processBuilder = applicationProcess(fixture);
      processBuilder.environment().put("WORKER_HTTP_PORT_FILE", portFile.toString());
      try (var worker = start(processBuilder)) {
        worker.awaitRegistration(controlPlane);
        var httpPort = Integer.parseInt(Files.readString(portFile).strip());
        var response =
            client.send(
                HttpRequest.newBuilder(
                        URI.create("http://localhost:" + httpPort + "/actuator/health/liveness"))
                    .GET()
                    .build(),
                BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
      }
    }
  }

  @Test
  @DisplayName("Should return a source failure when a POSIX worker receives a Unicode source")
  void shouldReturnASourceFailureWhenAPosixWorkerReceivesAUnicodeSource() throws Exception {
    var request =
        ProbeRequest.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(1)
            .setSource(
                MediaSourceRef.newBuilder()
                    .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                    .setRelativeKey("caf\u00e9.mkv"))
            .build();

    try (var controlPlane = WorkerApplicationControlPlane.builder().build()) {
      var fixture =
          ApplicationFixture.builder()
              .port(controlPlane.port())
              .ffprobe(versionOnlyFfprobe())
              .build();
      var processBuilder = applicationProcess(fixture);
      processBuilder.environment().put("LC_ALL", "C");
      processBuilder.environment().put("LANG", "C");
      try (var worker = start(processBuilder)) {
        worker.awaitRegistration(controlPlane);
        controlPlane.startProbe(request);

        var result = worker.awaitProbeResult(controlPlane);
        assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
        assertThat(result.getProbeVersion()).isEqualTo(request.getProbeVersion());
        assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE);
      }
    }
  }

  @ParameterizedTest(name = "{displayName} [{0}]")
  @EnumSource(UnavailableBinary.class)
  @DisplayName("Should decline probe admission when the configured binary cannot execute")
  void shouldDeclineProbeAdmissionWhenTheConfiguredBinaryCannotExecute(
      UnavailableBinary unavailable) throws Exception {
    var binary = unavailableBinary(unavailable);
    assertThat(Files.isExecutable(binary)).isFalse();

    try (var controlPlane = WorkerApplicationControlPlane.builder().build()) {
      var fixture = ApplicationFixture.builder().port(controlPlane.port()).ffprobe(binary).build();
      try (var worker = start(applicationProcess(fixture))) {
        assertThat(worker.awaitExitBeforeRegistration(controlPlane))
            .as("startup refuses an unavailable producer")
            .isNotZero();
        assertThat(controlPlane.registrationsAfterExit()).isEmpty();
        assertThat(controlPlane.wasContacted())
            .as("the worker checks ffprobe before it connects to the server")
            .isFalse();
      }
    }
  }

  @Test
  @DisplayName("Should refuse startup when ffprobe reports a failing version check")
  void shouldRefuseStartupWhenFfprobeReportsAFailingVersionCheck() throws Exception {
    var binary = tempDir.resolve("failing-ffprobe");
    Files.writeString(
        binary,
        """
        #!/bin/sh
        [ "$#" -eq 1 ] && [ "$1" = "-version" ] || exit 99
        exit 7
        """);
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));

    try (var controlPlane = WorkerApplicationControlPlane.builder().build()) {
      var fixture = ApplicationFixture.builder().port(controlPlane.port()).ffprobe(binary).build();
      try (var worker = start(applicationProcess(fixture))) {
        assertThat(worker.awaitExitBeforeRegistration(controlPlane))
            .as("the worker refuses startup after the configured ffprobe fails its version check")
            .isNotZero();
        assertThat(controlPlane.registrationsAfterExit()).isEmpty();
        assertThat(controlPlane.wasContacted())
            .as("the worker checks ffprobe before it connects to the server")
            .isFalse();
      }
    }
  }

  @ParameterizedTest(name = "{displayName} [{0}]")
  @EnumSource(BinaryLocation.class)
  @DisplayName("Should produce media with the configured binary when the application starts")
  void shouldProduceMediaWithTheConfiguredBinaryWhenTheApplicationStarts(BinaryLocation location)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    var binary = tempDir.resolve("working-ffprobe");
    Files.writeString(
        binary,
        """
        #!/bin/sh
        if [ "$#" -eq 1 ]; then
          [ "$1" = "-version" ] || exit 99
          exit 0
        fi
        echo '{"streams":[{"index":0,"codec_type":"video","codec_name":"configured-codec"}]}'
        """);
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
    var request =
        ProbeRequest.newBuilder()
            .setProbeAttemptId(toProto(UUID.randomUUID()))
            .setProbeVersion(1)
            .setSource(
                MediaSourceRef.newBuilder()
                    .setSourceNamespaceId(toProto(SOURCE_NAMESPACE_ID))
                    .setRelativeKey("movie.mkv"))
            .build();

    try (var controlPlane = WorkerApplicationControlPlane.builder().build()) {
      var configuredBinary = location == BinaryLocation.PATH ? binary.getFileName() : binary;
      var fixture =
          ApplicationFixture.builder().port(controlPlane.port()).ffprobe(configuredBinary).build();
      try (var worker = start(applicationProcess(fixture))) {
        var registration = worker.awaitRegistration(controlPlane);
        assertThat(registration.getCapabilities().getProbeVersionsList()).contains(1);
        controlPlane.startProbe(request);

        var result = worker.awaitProbeResult(controlPlane);
        assertThat(result.hasMedia()).isTrue();
        assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
        assertThat(result.getMedia().getStreamsList())
            .singleElement()
            .satisfies(stream -> assertThat(stream.getCodec()).isEqualTo("configured-codec"));
      }
    }
  }

  @Test
  @DisplayName(
      "Should connect an environment-configured worker process when starting with the local default")
  void shouldConnectEnvironmentConfiguredWorkerProcessWhenStartingWithTheLocalDefault()
      throws Exception {
    try (var controlPlane = WorkerApplicationControlPlane.builder().build()) {
      var fixture =
          ApplicationFixture.builder()
              .port(controlPlane.port())
              .ffprobe(versionOnlyFfprobe())
              .build();
      var processBuilder = applicationProcess(fixture);
      var workerId = UUID.fromString(processBuilder.environment().get("TRANSCODE_WORKER_ID"));
      try (var worker = start(processBuilder)) {
        var registration = worker.awaitRegistration(controlPlane);

        assertThat(registration.getWorker().getWorkerId()).isEqualTo(toProto(workerId));
        assertThat(registration.getCapabilities().getSourceNamespaceIdsList())
            .containsExactly(toProto(SOURCE_NAMESPACE_ID));
      }
    }
  }

  @Test
  @DisplayName("Should exit the worker process when the control plane disconnects")
  void shouldExitWorkerProcessWhenControlPlaneDisconnects() throws Exception {
    try (var controlPlane = WorkerApplicationControlPlane.builder().build()) {
      var fixture =
          ApplicationFixture.builder()
              .port(controlPlane.port())
              .ffprobe(versionOnlyFfprobe())
              .build();
      try (var worker = start(applicationProcess(fixture))) {
        worker.awaitRegistration(controlPlane);
        controlPlane.close();

        assertThatCode(worker::awaitExit)
            .as("the application exits after its control plane disconnects")
            .doesNotThrowAnyException();
      }
    }
  }

  @Test
  @DisplayName("Should refuse startup when FFmpeg is unavailable")
  void shouldRefuseStartupWhenFfmpegIsUnavailable() throws Exception {
    try (var controlPlane = WorkerApplicationControlPlane.builder().build()) {
      var fixture =
          ApplicationFixture.builder()
              .port(controlPlane.port())
              .ffprobe(versionOnlyFfprobe())
              .build();
      var processBuilder = applicationProcess(fixture);
      processBuilder
          .environment()
          .put("TRANSCODE_WORKER_FFMPEG_PATH", tempDir.resolve("missing-ffmpeg").toString());
      try (var worker = start(processBuilder)) {
        assertThat(worker.awaitExitBeforeRegistration(controlPlane))
            .as("startup refuses missing FFmpeg")
            .isNotZero();
        assertThat(controlPlane.registrationsAfterExit()).isEmpty();
        assertThat(controlPlane.wasContacted())
            .as("the worker checks FFmpeg before it connects to the server")
            .isFalse();
      }
    }
  }

  private Path versionOnlyFfprobe() throws Exception {
    var binary = tempDir.resolve("ffprobe");
    Files.writeString(
        binary,
        """
        #!/bin/sh
        [ "$#" -eq 1 ] && [ "$1" = "-version" ]
        """);
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
    return binary;
  }

  private Path unavailableBinary(UnavailableBinary unavailable) throws Exception {
    var binary = tempDir.resolve("unavailable-ffprobe");
    if (unavailable == UnavailableBinary.NON_EXECUTABLE) {
      Files.writeString(binary, "#!/bin/sh\nexit 0\n");
      Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rw-------"));
    }

    return binary;
  }

  @Test
  @DisplayName("Should wait for successful probe validation when registering the application")
  void shouldWaitForSuccessfulProbeValidationWhenRegisteringTheApplication() throws Exception {
    var pidFile = tempDir.resolve("probe.pid");
    var binary = tempDir.resolve("held-version-ffprobe");
    Files.writeString(
        binary,
        """
        #!/bin/bash
        [ "$#" -eq 1 ] && [ "$1" = "-version" ] || exit 99
        printf '%s' "$$" > "$PROBE_PID_FILE"
        exec 3<>"/dev/tcp/127.0.0.1/$PROBE_SIGNAL_PORT"
        read -r release <&3
        exit 0
        """);
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));

    try (var controlPlane =
            WorkerApplicationControlPlane.builder()
                .producerAdmitted(() -> validationHasExited(pidFile))
                .build();
        var signal = ServerSocketChannel.open().bind(new InetSocketAddress("127.0.0.1", 0))) {
      var heldVersionCheck =
          CompletableFuture.supplyAsync(() -> accept(signal), Thread.ofVirtual()::start);
      var fixture = ApplicationFixture.builder().port(controlPlane.port()).ffprobe(binary).build();
      var processBuilder = applicationProcess(fixture);
      processBuilder.environment().put("PROBE_PID_FILE", pidFile.toString());
      processBuilder
          .environment()
          .put("PROBE_SIGNAL_PORT", String.valueOf(signal.socket().getLocalPort()));
      try (var worker = start(processBuilder);
          var versionCheck =
              worker.awaitBeforeExit(heldVersionCheck, "the version check reached its held wait")) {
        assertThat(validationHasExited(pidFile)).isFalse();
        versionCheck.write(ByteBuffer.wrap("release\n".getBytes(US_ASCII)));

        assertThat(worker.awaitRegistration(controlPlane).getCapabilities().getProbeVersionsList())
            .containsExactly(1);
        assertThat(controlPlane.wasAdmittedAtRegistration())
            .as("registration occurs after the held version process has exited successfully")
            .isTrue();
      } finally {
        stopNativeProcess(pidFile);
      }
    }
  }

  private static SocketChannel accept(ServerSocketChannel signal) {
    try {
      return signal.accept();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static boolean validationHasExited(Path pidFile) {
    if (!Files.exists(pidFile)) {
      return false;
    }

    try {
      return ProcessHandle.of(Long.parseLong(Files.readString(pidFile)))
          .map(process -> !process.isAlive())
          .orElse(true);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  @Test
  @DisplayName("Should preserve interruption and stop ffprobe when startup is interrupted")
  void shouldPreserveInterruptionAndStopFfprobeWhenStartupIsInterrupted() throws Exception {
    var started = tempDir.resolve("started.fifo");
    assertThat(new ProcessBuilder("mkfifo", started.toString()).start().waitFor())
        .as("mkfifo creates the startup signal")
        .isZero();
    var pidFile = tempDir.resolve("probe.pid");
    var binary = tempDir.resolve("waiting-ffprobe");
    Files.writeString(
        binary,
        """
        #!/bin/sh
        printf '%s' "$$" > "$PROBE_PID_FILE"
        : > "$PROBE_STARTED_FIFO"
        exec /bin/sleep 300
        """);
    Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
    var fixture =
        ApplicationFixture.builder()
            .port(1)
            .ffprobe(binary)
            .mainClass(InterruptedStartup.class)
            .build();
    var processBuilder = applicationProcess(fixture);
    processBuilder.environment().put("PROBE_PID_FILE", pidFile.toString());
    processBuilder.environment().put("PROBE_STARTED_FIFO", started.toString());
    try (var worker = start(processBuilder)) {
      assertThat(worker.awaitExit())
          .as(
              "startup rethrows its interruption with the interrupt flag set"
                  + " (exit status %d: flag cleared, %d: startup returned)",
              InterruptedStartup.INTERRUPT_FLAG_CLEARED, InterruptedStartup.STARTUP_RETURNED)
          .isZero();
      var nativeProcess = ProcessHandle.of(Long.parseLong(Files.readString(pidFile)));
      assertThat(nativeProcess.map(ProcessHandle::isAlive).orElse(false)).isFalse();
    } finally {
      stopNativeProcess(pidFile);
    }
  }

  private ProcessBuilder applicationProcess(ApplicationFixture fixture) throws Exception {
    var ffmpeg = tempDir.resolve("ffmpeg");
    Files.writeString(
        ffmpeg,
        """
        #!/bin/sh
        case "$*" in
          *"muxer=mp4"*) cat <<'HELP'
        %s
        HELP
          ;;
        esac
        exit 0
        """
            .formatted(FRAGMENTED_MP4_MUXER_HELP));
    Files.setPosixFilePermissions(ffmpeg, PosixFilePermissions.fromString("rwx------"));
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
        .filter(argument -> argument.startsWith("-javaagent:") && argument.contains("jacoco"))
        .findFirst()
        .ifPresent(command::add);
    command.add("-cp");
    command.add(System.getProperty("java.class.path"));
    command.add(fixture.mainClass().getName());
    var process = new ProcessBuilder(command);
    process.environment().keySet().removeIf(key -> key.startsWith("TRANSCODE_WORKER_"));
    process
        .environment()
        .put(
            "PATH",
            tempDir + System.getProperty("path.separator") + process.environment().get("PATH"));
    process
        .environment()
        .putAll(
            Map.ofEntries(
                Map.entry("TRANSCODE_WORKER_CONTROL_PLANE_PORT", String.valueOf(fixture.port())),
                Map.entry("TRANSCODE_WORKER_ID", UUID.randomUUID().toString()),
                Map.entry("TRANSCODE_WORKER_SOURCE_NAMESPACE_ID", SOURCE_NAMESPACE_ID.toString()),
                Map.entry("TRANSCODE_WORKER_SOURCE_ROOT", tempDir.toString()),
                Map.entry("SERVER_PORT", "0"),
                Map.entry("TRANSCODE_WORKER_FFMPEG_PATH", ffmpeg.toString()),
                Map.entry("TRANSCODE_WORKER_FFPROBE_PATH", fixture.ffprobe().toString())));
    return process;
  }

  private WorkerApplicationProcess start(ProcessBuilder processBuilder) throws IOException {
    return WorkerApplicationProcess.start(processBuilder, tempDir.resolve("worker.log"));
  }

  private static void stopNativeProcess(Path pidFile) throws Exception {
    if (!Files.exists(pidFile)) {
      return;
    }

    var nativeProcess = ProcessHandle.of(Long.parseLong(Files.readString(pidFile)));
    if (nativeProcess.isEmpty()) {
      return;
    }

    var process = nativeProcess.orElseThrow();
    process.destroyForcibly();
    process.onExit().get();
  }

  private enum UnavailableBinary {
    MISSING,
    NON_EXECUTABLE
  }

  private enum BinaryLocation {
    ABSOLUTE_PATH,
    PATH
  }

  @Builder
  private record ApplicationFixture(int port, Path ffprobe, Class<?> mainClass) {

    static class ApplicationFixtureBuilder {
      private Class<?> mainClass = TranscodeWorkerApplication.class;
    }
  }

  public static final class PortReportingStartup {

    private PortReportingStartup() {}

    public static void main(String[] args) {
      var portFile = new File(System.getenv("WORKER_HTTP_PORT_FILE"));
      SpringApplication.withHook(
          application -> {
            application.addListeners(new WebServerPortFileWriter(portFile));
            return null;
          },
          () -> {
            TranscodeWorkerApplication.main(args);
            return null;
          });
    }
  }

  public static final class InterruptedStartup {

    static final int INTERRUPT_PRESERVED = 0;
    static final int INTERRUPT_FLAG_CLEARED = 3;
    static final int STARTUP_RETURNED = 4;

    private InterruptedStartup() {}

    public static void main(String[] args) {
      var started = Path.of(System.getenv("PROBE_STARTED_FIFO"));
      var startup = Thread.currentThread();
      // Opening a FIFO blocks in native code that ignores interrupts, so only a daemon waits there.
      Thread.ofPlatform()
          .daemon()
          .start(
              () -> {
                readToEnd(started);
                startup.interrupt();
              });
      System.exit(startupExitStatus(args));
    }

    private static int startupExitStatus(String[] args) {
      try {
        TranscodeWorkerApplication.main(args);
        return STARTUP_RETURNED;
      } catch (InterruptedException _) {
        if (Thread.currentThread().isInterrupted()) {
          return INTERRUPT_PRESERVED;
        }

        return INTERRUPT_FLAG_CLEARED;
      }
    }

    private static void readToEnd(Path fifo) {
      try {
        Files.readAllBytes(fifo);
      } catch (IOException exception) {
        throw new UncheckedIOException(exception);
      }
    }
  }
}
