package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.AudioMode;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.JobAttemptCompleted;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.StartVariantCommand;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentRequest;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentResponse;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.engine.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.ProcessBuilderLauncher;
import com.streamarr.transcode.engine.ProcessLauncher;
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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("SmokeTest")
@DisplayName("Worker Media Smoke Tests")
class WorkerMediaSmokeTest {

  // The 10 s fixture at the default 6 s period: the server advertises segments 0 and 1.
  private static final int FIXTURE_SEGMENT_COUNT = 2;

  // Media time of the long source; at a 1 s period it spans segments 0 to 1002.
  private static final int LONG_SOURCE_SECONDS = 1003;

  private static final Duration JOB_LIMIT = Duration.ofMinutes(5);
  private static final int PERMITTED_KEEPALIVE_SECONDS = 10;

  @TempDir static Path longSources;

  private static Path longSource;

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
  @DisplayName(
      "Should upload every advertised segment through the pipe when a worker executes a real"
          + " media job")
  void shouldUploadEveryAdvertisedSegmentThroughThePipeWhenAWorkerExecutesARealMediaJob(
      TranscodeMode mode) throws Exception {
    copyMedia();
    var job = variantJobBuilder();
    job.getDecisionBuilder().setMode(mode);
    job.getVariantBuilder().setWidth(160).setHeight(90).setBitrateBitsPerSecond(500_000);
    job.getExecutionBuilder().setMediaSegmentCount(FIXTURE_SEGMENT_COUNT);

    var uploads = execute(engine(realCapabilities()), root, job.build());

    assertThat(uploads.names()).containsExactlyElementsOf(uploadNames(FIXTURE_SEGMENT_COUNT));
    var media = playable(uploads.contentOf(uploads.names()));
    assertDecodes(media);
    assertThat(video(media))
        .satisfies(
            video -> {
              assertThat(video.getCodec()).isEqualTo("h264");
              var expectedHeight = mode == TranscodeMode.TRANSCODE_MODE_REMUX ? 180 : 90;
              assertThat(video.getHeight()).isEqualTo(expectedHeight);
              assertThat(video.getWidth()).isEqualTo(expectedHeight * 16 / 9);
            });
  }

  @Test
  @DisplayName(
      "Should upload every advertised segment when a worker copies an MPEG-TS source with AAC"
          + " audio")
  void shouldUploadEveryAdvertisedSegmentWhenAWorkerCopiesAnMpegTsSourceWithAacAudio()
      throws Exception {
    copyMedia();
    runFfmpeg(
        List.of("-i", root.resolve("movie.mkv").toString(), "-c", "copy", "-f", "mpegts"),
        root.resolve("movie.ts"));
    var job = variantJobBuilder();
    job.getSourceBuilder().setRelativeKey("movie.ts");
    job.getExecutionBuilder().setMediaSegmentCount(FIXTURE_SEGMENT_COUNT);
    job.getDecisionBuilder()
        .setMode(TranscodeMode.TRANSCODE_MODE_REMUX)
        .getAudioBuilder()
        .setMode(AudioMode.AUDIO_MODE_COPY)
        .setCodec("aac");

    var uploads = execute(engine(realCapabilities()), root, job.build());

    assertThat(uploads.names()).containsExactlyElementsOf(uploadNames(FIXTURE_SEGMENT_COUNT));
    var media = playable(uploads.contentOf(uploads.names()));
    assertDecodes(media);
    assertThat(streams(media))
        .filteredOn(stream -> "audio".equals(stream.getCodecType()))
        .singleElement()
        .satisfies(audio -> assertThat(audio.getCodec()).isEqualTo("aac"));
  }

  @Test
  @DisplayName(
      "Should upload decodable AV1 within the bitrate budget when transcoding an H264 file")
  void shouldUploadDecodableAv1WithinTheBitrateBudgetWhenTranscodingAnH264File() throws Exception {
    copyMedia();
    var job = variantJobBuilder();
    job.getDecisionBuilder()
        .setMode(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
        .setVideoCodecFamily("av1")
        .getAudioBuilder()
        .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
        .setCodec("aac")
        .setChannels(1)
        .setBitrateBitsPerSecond(64_000);
    job.getVariantBuilder().setWidth(320).setHeight(180).setBitrateBitsPerSecond(32_000);
    job.getExecutionBuilder().setMediaSegmentCount(FIXTURE_SEGMENT_COUNT);

    var uploads = execute(engine(realCapabilities()), root, job.build());

    assertThat(uploads.names()).containsExactlyElementsOf(uploadNames(FIXTURE_SEGMENT_COUNT));
    // Six- and four-second segments at 32 kbps video + 64 kbps audio, with 20% HLS headroom.
    assertThat(uploads.segments().get("segment0.m4s")).hasSizeLessThanOrEqualTo(86_400);
    assertThat(uploads.segments().get("segment1.m4s")).hasSizeLessThanOrEqualTo(57_600);
    var media = playable(uploads.contentOf(uploads.names()));
    assertDecodes(media);
    assertThat(video(media))
        .satisfies(
            video -> {
              assertThat(video.getCodec()).isEqualTo("av1");
              assertThat(video.getWidth()).isEqualTo(320);
              assertThat(video.getHeight()).isEqualTo(180);
            });
    assertThat(streams(media))
        .filteredOn(stream -> "audio".equals(stream.getCodecType()))
        .singleElement()
        .satisfies(
            audio -> {
              assertThat(audio.getCodec()).isEqualTo("aac");
              assertThat(audio.getChannels()).isEqualTo(1);
            });
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"h264", "hevc", "av1"})
  @DisplayName(
      "Should upload more than a thousand segments without skipping an interval when a software"
          + " encoder runs a long job")
  void
      shouldUploadMoreThanAThousandSegmentsWithoutSkippingAnIntervalWhenASoftwareEncoderRunsALongJob(
          String codecFamily) throws Exception {
    var source = longSource();
    var job = variantJobBuilder();
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    job.getDecisionBuilder()
        .setMode(TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE)
        .setVideoCodecFamily(codecFamily)
        .getAudioBuilder()
        .setMode(AudioMode.AUDIO_MODE_COPY)
        .setCodec("aac");
    job.getVariantBuilder().setWidth(64).setHeight(36).setBitrateBitsPerSecond(20_000);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(1)
        .setFramerate(24000.0 / 1001)
        .setMediaSegmentCount(LONG_SOURCE_SECONDS);
    ProcessLauncher launcher = new ProcessBuilderLauncher();
    if (codecFamily.equals("av1")) {
      launcher = withSvtAv1ParallelismOfOne(launcher);
    }

    var uploads =
        execute(engine(softwareCapabilities(), launcher), source.getParent(), job.build());

    assertThat(uploads.names()).containsExactlyElementsOf(uploadNames(LONG_SOURCE_SECONDS));
    var start = playable(uploads.contentOf(List.of("init.mp4", "segment0.m4s")));
    assertThat(video(start).getCodec()).isEqualTo(codecFamily);
  }

  private MediaUploads execute(FfmpegTranscodeEngine engine, Path sourceRoot, VariantJob job)
      throws Exception {
    try (var controlPlane = new MediaControlPlane();
        var worker = workerBuilder(sourceRoot).engine(engine).build()) {
      worker.start("127.0.0.1", controlPlane.port());
      var registeredWorker = controlPlane.registration.get(5, TimeUnit.SECONDS).getWorker();
      controlPlane.responses.onNext(
          EstablishWorkerSessionResponse.newBuilder()
              .setStartVariant(
                  StartVariantCommand.newBuilder().setTarget(registeredWorker).setJob(job))
              .build());

      assertThat(controlPlane.completed.get(JOB_LIMIT.toSeconds(), TimeUnit.SECONDS))
          .extracting(JobAttemptCompleted::getJobAttemptId)
          .isEqualTo(job.getJobAttemptId());
      return new MediaUploads(List.copyOf(controlPlane.names), Map.copyOf(controlPlane.segments));
    }
  }

  private static List<String> uploadNames(int mediaSegmentCount) {
    return Stream.concat(
            Stream.of("init.mp4"),
            IntStream.range(0, mediaSegmentCount).mapToObj(number -> "segment" + number + ".m4s"))
        .toList();
  }

  private static FfmpegTranscodeEngine engine(TranscodeCapabilityService capabilities) {
    return engine(capabilities, new ProcessBuilderLauncher());
  }

  private static FfmpegTranscodeEngine engine(
      TranscodeCapabilityService capabilities, ProcessLauncher launcher) {
    capabilities.detectCapabilities();
    assertThat(capabilities.isFfmpegAvailable()).as(capabilities.getUnavailableReason()).isTrue();
    return FfmpegTranscodeEngine.builder()
        .commandBuilder(new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1)))
        .capabilityService(capabilities)
        .launcher(launcher)
        .build();
  }

  // SVT-AV1 4.x can overrun its packetization reorder queue when its threads are starved of CPU
  // (upstream issue 2385, worker #42): FFmpeg then hangs, or squeezes the timestamps of
  // out-of-order packets, and this long job fails on a busy CI runner for a reason unrelated to
  // what it checks.
  // At a level of parallelism of one (lp=1) each stage has one thread and one picture in flight,
  // so the queue cannot overrun, and the forced and GOP keyframes land where they do at production
  // threading, so this check of where segments start still covers the recipe. Production keeps its
  // threading and relies on the stall watchdog and the short-sample rule instead; lp=1 encodes
  // 1080p slower than real time. The short AV1 smoke above keeps production threading.
  private static ProcessLauncher withSvtAv1ParallelismOfOne(ProcessLauncher launcher) {
    return (command, jobAttemptId) -> {
      var parameters = command.indexOf("-svtav1-params") + 1;
      assertThat(parameters).as("the SVT-AV1 command sets encoder parameters").isPositive();
      var adjusted = new ArrayList<>(command);
      adjusted.set(parameters, command.get(parameters) + ":lp=1");
      return launcher.launch(adjusted, jobAttemptId);
    };
  }

  private static TranscodeCapabilityService realCapabilities() {
    return new TranscodeCapabilityService("ffmpeg", command -> new ProcessBuilder(command).start());
  }

  // Lists no hardware encoder, so that each codec family runs its software encoder.
  private static TranscodeCapabilityService softwareCapabilities() {
    return new TranscodeCapabilityService(
        "ffmpeg",
        command -> {
          if (List.of(command).contains("-encoders")) {
            return new ProcessBuilder("true").start();
          }

          return new ProcessBuilder(command).start();
        });
  }

  // A tiny H.264 and AAC source for more than a thousand 1 s segments, recorded once.
  private static synchronized Path longSource() throws Exception {
    if (longSource == null) {
      var source = longSources.resolve("long.mp4");
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24000/1001",
              "-f",
              "lavfi",
              "-i",
              "anullsrc=channel_layout=mono:sample_rate=48000",
              "-t",
              String.valueOf(LONG_SOURCE_SECONDS),
              "-c:v",
              "libx264",
              "-preset",
              "ultrafast",
              "-c:a",
              "aac",
              "-b:a",
              "16k"),
          source);
      longSource = source;
    }

    return longSource;
  }

  private static void runFfmpeg(List<String> arguments, Path output) throws Exception {
    var command =
        Stream.of(
                List.of("ffmpeg", "-nostdin", "-v", "error", "-y"),
                arguments,
                List.of(output.toString()))
            .flatMap(List::stream)
            .toList();
    var log = Files.createTempFile(longSources, "ffmpeg", ".log");
    var process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertThat(process.waitFor(2, TimeUnit.MINUTES)).as("FFmpeg finished").isTrue();
      assertThat(process.exitValue()).as(Files.readString(log)).isZero();
    } finally {
      process.destroyForcibly();
    }
  }

  private Path playable(byte[] content) throws Exception {
    var media = Files.createTempFile(root, "uploaded", ".mp4");
    Files.write(media, content);
    return media;
  }

  private static void assertDecodes(Path media) throws Exception {
    runFfmpeg(List.of("-xerror", "-i", media.toString(), "-f", "null"), Path.of("-"));
  }

  private static List<ProbeStreamInfo> streams(Path media) {
    return FfprobeExecutor.forBinary(Path.of("ffprobe"))
        .probe(media, requestBuilder().build())
        .getMedia()
        .getStreamsList();
  }

  private static ProbeStreamInfo video(Path media) {
    var videos =
        streams(media).stream().filter(stream -> "video".equals(stream.getCodecType())).toList();
    assertThat(videos).hasSize(1);
    return videos.getFirst();
  }

  private void copyMedia() throws Exception {
    var source = getClass().getResource("/BigBuckBunny_320x180_10s.mp4");
    assertThat(source).isNotNull();
    Files.copy(Path.of(source.toURI()), root.resolve("movie.mkv"));
  }

  // What the control plane accepted, in upload order.
  private record MediaUploads(List<String> names, Map<String, byte[]> segments) {

    private byte[] contentOf(List<String> uploadNames) {
      var content = new ByteArrayOutputStream();
      uploadNames.forEach(name -> content.writeBytes(segments.get(name)));
      return content.toByteArray();
    }
  }

  private static final class MediaControlPlane
      extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase implements AutoCloseable {

    private final CompletableFuture<WorkerRegistration> registration = new CompletableFuture<>();
    private final CompletableFuture<JobAttemptCompleted> completed = new CompletableFuture<>();
    private final Map<String, byte[]> segments = new ConcurrentHashMap<>();
    private final List<String> names = new CopyOnWriteArrayList<>();
    private final Server server;
    private StreamObserver<EstablishWorkerSessionResponse> responses;

    private MediaControlPlane() throws Exception {
      // As Streamarr's session listener does, so that a job longer than the default ping
      // allowance keeps its session.
      server =
          NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
              .permitKeepAliveTime(PERMITTED_KEEPALIVE_SECONDS, TimeUnit.SECONDS)
              .permitKeepAliveWithoutCalls(true)
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
          names.add(name);
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
