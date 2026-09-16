package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.AudioMode;
import build.buf.gen.streamarr.transcode.v1.ContainerFormat;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.JobAttemptCompleted;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.StartVariantCommand;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentRequest;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentResponse;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.engine.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.LocalFfmpegProcessManager;
import com.streamarr.transcode.engine.TranscodeCapabilityService;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("SmokeTest")
@DisplayName("Worker Media Smoke Tests")
class WorkerMediaSmokeTest {

  @TempDir Path root;

  @Test
  @DisplayName("Should return video dimensions when a worker probes a real media file")
  void shouldReturnVideoDimensionsWhenAWorkerProbesARealMediaFile() throws Exception {
    copyMedia();
    var runtime = new ScriptedWorkerRuntime();
    var request = requestBuilder().build();
    try (var worker =
        workerBuilder(root)
            .runtime(runtime)
            .ffprobe(FfprobeExecutor.forBinary(Path.of("ffprobe")))
            .build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().results())
          .singleElement()
          .satisfies(
              result -> {
                assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
                assertThat(result.getMedia().getStreamsList())
                    .filteredOn(stream -> "video".equals(stream.getCodecType()))
                    .singleElement()
                    .satisfies(
                        video -> {
                          assertThat(video.getCodec()).isEqualTo("h264");
                          assertThat(video.getWidth()).isEqualTo(320);
                          assertThat(video.getHeight()).isEqualTo(180);
                        });
              });
    }
  }

  @Test
  @DisplayName("Should report invalid media when the real producer cannot decode a file")
  void shouldReportInvalidMediaWhenTheRealProducerCannotDecodeAFile() throws Exception {
    Files.writeString(root.resolve("movie.mkv"), "This file contains no media stream.");
    var runtime = new ScriptedWorkerRuntime();
    var request = requestBuilder().build();
    try (var worker =
        workerBuilder(root)
            .runtime(runtime)
            .ffprobe(FfprobeExecutor.forBinary(Path.of("ffprobe")))
            .build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().results())
          .singleElement()
          .satisfies(
              result -> {
                assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
                assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
              });
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"TRANSCODE_MODE_REMUX", "TRANSCODE_MODE_FULL_TRANSCODE"})
  @DisplayName("Should upload decodable segments when a worker executes a real media job")
  void shouldUploadDecodableSegmentsWhenAWorkerExecutesARealMediaJob(TranscodeMode mode)
      throws Exception {
    copyMedia();
    var capabilities =
        new TranscodeCapabilityService("ffmpeg", command -> new ProcessBuilder(command).start());
    capabilities.detectCapabilities();
    assertThat(capabilities.isFfmpegAvailable()).isTrue();
    var engine =
        new FfmpegTranscodeEngine(
            new FfmpegCommandBuilder("ffmpeg"), new LocalFfmpegProcessManager(), capabilities);
    try (var controlPlane = new MediaControlPlane();
        var worker = workerBuilder(root).engine(engine).build()) {
      worker.start("127.0.0.1", controlPlane.port());
      var job = variantJobBuilder();
      job.getDecisionBuilder().setMode(mode);
      job.getVariantBuilder().setWidth(160).setHeight(90).setBitrateBitsPerSecond(500_000);
      var request = job.build();
      var registeredWorker = controlPlane.registration.get(5, TimeUnit.SECONDS).getWorker();
      controlPlane.responses.onNext(
          EstablishWorkerSessionResponse.newBuilder()
              .setStartVariant(
                  StartVariantCommand.newBuilder().setTarget(registeredWorker).setJob(request))
              .build());

      assertThat(controlPlane.completed.get(30, TimeUnit.SECONDS).getJobAttemptId())
          .isEqualTo(request.getJobAttemptId());
      assertThat(controlPlane.segments).containsKey("segment0.ts");
      var segment = root.resolve("uploaded.ts");
      Files.write(segment, controlPlane.segments.get("segment0.ts"));
      var media =
          FfprobeExecutor.forBinary(Path.of("ffprobe")).probe(segment, requestBuilder().build());
      assertThat(media.getMedia().getStreamsList())
          .filteredOn(stream -> "video".equals(stream.getCodecType()))
          .singleElement()
          .satisfies(
              video -> {
                assertThat(video.getCodec()).isEqualTo("h264");
                var expectedHeight = mode == TranscodeMode.TRANSCODE_MODE_REMUX ? 180 : 90;
                assertThat(video.getHeight()).isEqualTo(expectedHeight);
                assertThat(video.getWidth()).isEqualTo(expectedHeight * 16 / 9);
              });
    }
  }

  @Test
  @DisplayName(
      "Should upload decodable AV1 within the bitrate budget when transcoding an H264 file")
  void shouldUploadDecodableAv1WithinTheBitrateBudgetWhenTranscodingAnH264File() throws Exception {
    copyMedia();
    var capabilities =
        new TranscodeCapabilityService("ffmpeg", command -> new ProcessBuilder(command).start());
    capabilities.detectCapabilities();
    var engine =
        new FfmpegTranscodeEngine(
            new FfmpegCommandBuilder("ffmpeg"), new LocalFfmpegProcessManager(), capabilities);
    try (var controlPlane = new MediaControlPlane();
        var worker = workerBuilder(root).engine(engine).build()) {
      worker.start("127.0.0.1", controlPlane.port());
      var job = variantJobBuilder();
      job.getDecisionBuilder()
          .setMode(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
          .setVideoCodecFamily("av1")
          .setContainer(ContainerFormat.CONTAINER_FORMAT_FMP4)
          .getAudioBuilder()
          .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
          .setCodec("aac")
          .setChannels(1)
          .setBitrateBitsPerSecond(64_000);
      job.getVariantBuilder().setWidth(320).setHeight(180).setBitrateBitsPerSecond(32_000);
      var request = job.build();
      var registeredWorker = controlPlane.registration.get(5, TimeUnit.SECONDS).getWorker();
      controlPlane.responses.onNext(
          EstablishWorkerSessionResponse.newBuilder()
              .setStartVariant(
                  StartVariantCommand.newBuilder().setTarget(registeredWorker).setJob(request))
              .build());

      assertThat(controlPlane.completed.get(30, TimeUnit.SECONDS).getJobAttemptId())
          .isEqualTo(request.getJobAttemptId());
      assertThat(controlPlane.segments).containsKeys("init.mp4", "segment0.m4s", "segment1.m4s");
      // Six- and four-second segments at 32 kbps video + 64 kbps audio, with 20% HLS headroom.
      assertThat(controlPlane.segments.get("segment0.m4s")).hasSizeLessThanOrEqualTo(86_400);
      assertThat(controlPlane.segments.get("segment1.m4s")).hasSizeLessThanOrEqualTo(57_600);
      var uploaded = new ByteArrayOutputStream();
      uploaded.writeBytes(controlPlane.segments.get("init.mp4"));
      uploaded.writeBytes(controlPlane.segments.get("segment0.m4s"));
      uploaded.writeBytes(controlPlane.segments.get("segment1.m4s"));
      var segment = root.resolve("uploaded.mp4");
      Files.write(segment, uploaded.toByteArray());
      var result =
          FfprobeExecutor.forBinary(Path.of("ffprobe")).probe(segment, requestBuilder().build());
      assertThat(result.getMedia().getStreamsList())
          .filteredOn(stream -> "video".equals(stream.getCodecType()))
          .singleElement()
          .satisfies(
              video -> {
                assertThat(video.getCodec()).isEqualTo("av1");
                assertThat(video.getWidth()).isEqualTo(320);
                assertThat(video.getHeight()).isEqualTo(180);
              });
      assertThat(result.getMedia().getStreamsList())
          .filteredOn(stream -> "audio".equals(stream.getCodecType()))
          .singleElement()
          .satisfies(
              audio -> {
                assertThat(audio.getCodec()).isEqualTo("aac");
                assertThat(audio.getChannels()).isEqualTo(1);
              });
      var output = root.resolve("decoded.log");
      var decode =
          new ProcessBuilder(
                  "ffmpeg", "-v", "error", "-xerror", "-i", segment.toString(), "-f", "null", "-")
              .redirectErrorStream(true)
              .redirectOutput(output.toFile())
              .start();
      try {
        assertThat(decode.waitFor(30, TimeUnit.SECONDS)).as("AV1 decode completed").isTrue();
        assertThat(decode.exitValue()).as(Files.readString(output)).isZero();
      } finally {
        decode.destroyForcibly();
      }
    }
  }

  private void copyMedia() throws Exception {
    var source = getClass().getResource("/BigBuckBunny_320x180_10s.mp4");
    assertThat(source).isNotNull();
    Files.copy(Path.of(source.toURI()), root.resolve("movie.mkv"));
  }

  private static final class MediaControlPlane
      extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase implements AutoCloseable {

    private final CompletableFuture<WorkerRegistration> registration = new CompletableFuture<>();
    private final CompletableFuture<JobAttemptCompleted> completed = new CompletableFuture<>();
    private final Map<String, byte[]> segments = new ConcurrentHashMap<>();
    private final Server server;
    private StreamObserver<EstablishWorkerSessionResponse> responses;

    private MediaControlPlane() throws Exception {
      server =
          NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
              .addService(this)
              .build()
              .start();
    }

    private int port() {
      return server.getPort();
    }

    @Override
    public StreamObserver<EstablishWorkerSessionRequest> establishWorkerSession(
        StreamObserver<EstablishWorkerSessionResponse> responseObserver) {
      responses = responseObserver;
      return new StreamObserver<>() {
        @Override
        public void onNext(EstablishWorkerSessionRequest message) {
          if (message.hasRegistration()) {
            responseObserver.onNext(
                EstablishWorkerSessionResponse.newBuilder()
                    .setSessionAccepted(
                        WorkerSessionAccepted.newBuilder()
                            .setWorkerSessionId(toProto(UUID.randomUUID())))
                    .build());
            registration.complete(message.getRegistration());
          }

          if (message.hasJobAttemptCompleted()) {
            completed.complete(message.getJobAttemptCompleted());
          }

          if (message.hasJobAttemptFailed()) {
            completed.completeExceptionally(new AssertionError(message.getJobAttemptFailed()));
          }
        }

        @Override
        public void onError(Throwable failure) {
          completed.completeExceptionally(failure);
        }

        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };
    }

    @Override
    public StreamObserver<UploadSegmentRequest> uploadSegment(
        StreamObserver<UploadSegmentResponse> responseObserver) {
      return new StreamObserver<>() {
        private final ByteArrayOutputStream content = new ByteArrayOutputStream();
        private String name;

        @Override
        public void onNext(UploadSegmentRequest request) {
          if (request.hasMetadata()) {
            name = request.getMetadata().getSegmentName();
            return;
          }

          content.writeBytes(request.getData().toByteArray());
        }

        @Override
        public void onError(Throwable failure) {
          completed.completeExceptionally(failure);
        }

        @Override
        public void onCompleted() {
          segments.put(name, content.toByteArray());
          responseObserver.onNext(
              UploadSegmentResponse.newBuilder().setAcceptedLengthBytes(content.size()).build());
          responseObserver.onCompleted();
        }
      };
    }

    @Override
    public void close() throws InterruptedException {
      server.shutdownNow();
      assertThat(server.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }
}
