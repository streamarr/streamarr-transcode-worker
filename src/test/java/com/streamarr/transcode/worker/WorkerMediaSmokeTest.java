package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import build.buf.gen.streamarr.transcode.v1.AudioMode;
import build.buf.gen.streamarr.transcode.v1.EncoderPreset;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.JobAttemptCompleted;
import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import build.buf.gen.streamarr.transcode.v1.KeyframePositionSource;
import build.buf.gen.streamarr.transcode.v1.KeyframePositions;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailable;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailableReason;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
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
import com.streamarr.transcode.worker.support.X264Options;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("SmokeTest")
@DisplayName("Worker Media Smoke Tests")
class WorkerMediaSmokeTest {

  // The 10 s fixture at the default 6 s period: the server advertises segments 0 and 1.
  private static final int FIXTURE_SEGMENT_COUNT = 2;

  // Media time of the long source; at a 1 s period it spans segments 0 to 1002.
  private static final int LONG_SOURCE_SECONDS = 1003;

  // The seekable sources span segments 0 to 2 at the default 6 s period. A replacement attempt
  // for segment 2 seeks to 12 s when it copies the video and to 6 s when it encodes it, so it
  // starts past the source's first frame either way.
  private static final int SEEKABLE_SOURCE_SECONDS = 18;
  private static final int SEEKABLE_SOURCE_SEGMENT_COUNT = 3;
  private static final int REPLACEMENT_START_SEQUENCE_NUMBER = 2;

  // The 10-bit source spans segments 0 and 1 at the default 6 s period.
  private static final int TEN_BIT_SOURCE_SECONDS = 8;
  private static final int TEN_BIT_SOURCE_SEGMENT_COUNT = 2;

  // The scene-cut source spans segments 0 to 2 at the default 6 s period.
  private static final int SCENE_CUT_SOURCE_FRAME_RATE = 30;
  private static final int SCENE_CUT_SOURCE_SEGMENT_COUNT = 3;

  // HEVC NAL unit types (ITU-T H.265, table 7-1): pictures are types 0 to 31, and the random
  // access pictures that a decoder can start at (BLA, IDR and CRA) are types 16 to 23.
  private static final int HEVC_LAST_VIDEO_CODING_LAYER_TYPE = 31;
  private static final int HEVC_FIRST_RANDOM_ACCESS_TYPE = 16;
  private static final int HEVC_LAST_RANDOM_ACCESS_TYPE = 23;
  private static final int HEVC_IDR_W_RADL = 19;
  private static final int HEVC_IDR_N_LP = 20;

  private static final Duration JOB_LIMIT = Duration.ofMinutes(5);
  private static final String EMPTY_EDIT_SOURCE = "mp4-empty-edit.mp4";
  private static final String TRIMMED_START_SOURCE = "mp4-trimmed-start.mp4";
  private static final String MOV_SOURCE = "mov-bframes-editlist.mov";
  private static final String REORDERED_AVI_SOURCE = "avi-bframes.avi";
  private static final String FRACTIONAL_START_SOURCE = "mp4-fractional-start.mp4";
  private static final String NEGATIVE_OFFSETS_SOURCE = "mp4-negative-offsets.mp4";
  private static final String NEGATIVE_OFFSETS_EMPTY_EDIT_SOURCE =
      "mp4-negative-offsets-empty-edit.mp4";
  private static final int PERMITTED_KEEPALIVE_SECONDS = 10;

  @TempDir static Path longSources;

  @TempDir static Path seekableSources;

  @TempDir static Path tenBitSources;

  // A source joins only once FFmpeg has written it successfully, so that a later case generates it
  // again instead of reading what a failed or timed-out run left behind.
  private static final Set<Path> generatedSeekableSources = new HashSet<>();

  private static Path longSource;

  @TempDir Path root;

  @Test
  @DisplayName("Should return video dimensions when a worker probes a real media file")
  void shouldReturnVideoDimensionsWhenAWorkerProbesARealMediaFile() throws Exception {
    copyMedia();
    var runtime = new ScriptedWorkerRuntime();
    var request = requestBuilder().build();
    try (var worker = workerBuilder(root).runtime(runtime).ffprobe(probeExecutor()).build()) {
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
    try (var worker = workerBuilder(root).runtime(runtime).ffprobe(probeExecutor()).build()) {
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

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "mp4-bframes-editlist.mp4",
        "mkv-libavformat.mkv",
        EMPTY_EDIT_SOURCE,
        TRIMMED_START_SOURCE,
        FRACTIONAL_START_SOURCE,
        NEGATIVE_OFFSETS_SOURCE,
        NEGATIVE_OFFSETS_EMPTY_EDIT_SOURCE
      })
  @DisplayName(
      "Should report the keyframes that ffprobe reads from the packets when the worker reads a"
          + " container index")
  void shouldReportKeyframesThatFfprobeReadsFromPacketsWhenWorkerReadsContainerIndex(String fixture)
      throws Exception {
    var source = keyframeSource(fixture);

    var report = probeExecutor().probe(source, keyframeRequest());

    var positions = report.result().getMedia().getKeyframes().getPositions();
    var packetKeyframes = packetKeyframes(source, positions);
    assertThat(positions.getKeyframeCount()).isEqualTo(packetKeyframes.size());
    assertThat(keyframeTimes(report.keyframePositionsParts())).isEqualTo(packetKeyframes);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "mpegts.ts, KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX",
    "avi.avi, KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX",
    "mkv-pipe.mkv, KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX",
    "mp4-fragmented.mp4, KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX",
    "mp4-truncated.mp4, KEYFRAME_PACKET_SCAN_REASON_UNREADABLE_LAST_INDEXED_KEYFRAME",
    "mkv-truncated-cues-front.mkv, KEYFRAME_PACKET_SCAN_REASON_UNREADABLE_LAST_INDEXED_KEYFRAME"
  })
  @DisplayName("Should require a packet scan when real ffprobe finds no usable container index")
  void shouldRequirePacketScanWhenRealFfprobeFindsNoUsableContainerIndex(
      String fixture, KeyframePacketScanReason reason) throws Exception {
    var result = keyframeProbe(keyframeSource(fixture));

    assertThat(result.getMedia().getKeyframes().getPacketScanRequired()).isEqualTo(reason);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "mp4-bframes-editlist.mp4",
        MOV_SOURCE,
        "mkv-libavformat.mkv",
        EMPTY_EDIT_SOURCE,
        TRIMMED_START_SOURCE,
        FRACTIONAL_START_SOURCE,
        NEGATIVE_OFFSETS_SOURCE,
        NEGATIVE_OFFSETS_EMPTY_EDIT_SOURCE
      })
  @DisplayName(
      "Should report the container index's keyframe positions when a packet scan reads a source"
          + " with an index")
  void shouldReportContainerIndexKeyframePositionsWhenPacketScanReadsSourceWithIndex(String fixture)
      throws Exception {
    var source = keyframeSource(fixture);
    var ffprobe = probeExecutor();

    var index = ffprobe.probe(source, keyframeRequest());
    var scan = ffprobe.probe(source, packetScanRequest());

    var indexPositions = index.result().getMedia().getKeyframes().getPositions();
    var scanPositions = scan.result().getMedia().getKeyframes().getPositions();
    assertThat(scanPositions.getSource())
        .isEqualTo(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN);
    assertThat(scanPositions.toBuilder().setSource(indexPositions.getSource()).build())
        .isEqualTo(indexPositions);
    assertThat(keyframeTimes(scan.keyframePositionsParts()))
        .isNotEmpty()
        .isEqualTo(keyframeTimes(index.keyframePositionsParts()));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "mpegts.ts",
        "avi.avi",
        "mkv-pipe.mkv",
        "mp4-fragmented.mp4",
        "mp4-truncated.mp4",
        "mkv-truncated-cues-front.mkv"
      })
  @DisplayName(
      "Should report the keyframes that FFmpeg passes to a muxer when a packet scan reads a source"
          + " without a usable index")
  void shouldReportKeyframesThatFfmpegPassesToMuxerWhenPacketScanReadsSourceWithoutUsableIndex(
      String fixture) throws Exception {
    var source = keyframeSource(fixture);

    var report = probeExecutor().probe(source, packetScanRequest());

    var positions = report.result().getMedia().getKeyframes().getPositions();
    var packetKeyframes = packetKeyframes(source, positions);
    assertThat(positions.getKeyframeCount()).isEqualTo(packetKeyframes.size()).isPositive();
    assertThat(keyframeTimes(report.keyframePositionsParts())).isEqualTo(packetKeyframes);
  }

  @Test
  @DisplayName(
      "Should report the keyframes unavailable when a packet scan reads an AVI whose video reorders"
          + " frames")
  void shouldReportKeyframesUnavailableWhenPacketScanReadsAviWhoseVideoReordersFrames()
      throws Exception {
    // AVI stores no presentation times. With B-frames, a keyframe's presentation time differs from
    // its decode time, and FFmpeg's copy into MP4 presents every frame at its decode time.
    var source = keyframeSource(REORDERED_AVI_SOURCE);

    var report = probeExecutor().probe(source, packetScanRequest());

    assertThat(report.result().getMedia().getKeyframes())
        .isEqualTo(
            ProbeKeyframes.newBuilder()
                .setUnavailable(
                    KeyframesUnavailable.newBuilder()
                        .setReason(
                            KeyframesUnavailableReason
                                .KEYFRAMES_UNAVAILABLE_REASON_KEYFRAME_WITHOUT_TIMESTAMP))
                .build());
    assertThat(report.keyframePositionsParts()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should report an execution failure when the source stops being readable after a packet scan"
          + " has read keyframes")
  void shouldReportExecutionFailureWhenSourceStopsBeingReadableAfterPacketScanHasReadKeyframes()
      throws Exception {
    var fixture = getClass().getResource("/keyframes/mpegts.ts");
    assertThat(fixture).isNotNull();
    Files.copy(Path.of(fixture.toURI()), root.resolve("readable.ts"));
    // The concat demuxer delivers every packet of the readable file and then fails to open the
    // missing one, as a read error from network storage would stop the scan partway.
    var source =
        Files.writeString(
            root.resolve("unreadable-after-keyframes.ffconcat"),
            """
            ffconcat version 1.0
            file 'readable.ts'
            file 'missing.ts'
            """);

    var report = probeExecutor().probe(source, packetScanRequest());

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
    assertThat(report.keyframePositionsParts()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should report CANCELLED and stop FFmpeg when a packet scan is cancelled while the source"
          + " blocks reads")
  void shouldReportCancelledAndStopFfmpegWhenPacketScanIsCancelledWhileSourceBlocksReads()
      throws Exception {
    var fixture = getClass().getResource("/keyframes/mpegts.ts");
    assertThat(fixture).isNotNull();
    var source = root.resolve("blocked.ts");
    var mkfifo = new ProcessBuilder("mkfifo", source.toString()).start();
    assertThat(mkfifo.waitFor()).isZero();
    // The writer serves the stream probe once. The packet scan then opens a FIFO that no process
    // writes, as a stalled read from network storage would block it.
    var writer =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    Files.write(source, Files.readAllBytes(Path.of(fixture.toURI())));
                  } catch (Exception _) {
                    // The test fails on the probe result instead.
                  }
                });
    var result = new CompletableFuture<ProbeAttemptResult>();
    var probe =
        Thread.ofVirtual()
            .start(
                () -> result.complete(probeExecutor().probe(source, packetScanRequest()).result()));

    try {
      var scan =
          await()
              .atMost(Duration.ofSeconds(30))
              .until(() -> packetScanProcess(source), Optional::isPresent)
              .orElseThrow();
      probe.interrupt();

      assertThat(result.get(10, TimeUnit.SECONDS).getFailure())
          .isEqualTo(ProbeFailure.PROBE_FAILURE_CANCELLED);
      assertThat(scan.isAlive()).isFalse();
    } finally {
      probe.interrupt();
      writer.interrupt();
      packetScanProcess(source).ifPresent(ProcessHandle::destroyForcibly);
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

  @Test
  @DisplayName("Should upload 8-bit High profile H.264 when transcoding a 10-bit SDR source")
  void shouldUploadEightBitHighProfileH264WhenTranscodingATenBitSdrSource() throws Exception {
    var source = tenBitSdrSource();

    var uploads =
        execute(
            engine(softwareCapabilities()),
            source.getParent(),
            tenBitSdrSourceJob(source, "h264").build());

    var configuration = AvcDecoderConfiguration.of(uploads.segments().get("init.mp4"));
    assertThat(configuration.profileIndication()).as("AVCProfileIndication (High)").isEqualTo(100);
    assertThat(configuration.chromaFormatIdc()).as("chroma_format_idc (4:2:0)").isEqualTo(1);
    assertThat(configuration.bitDepthLumaMinus8()).as("bit_depth_luma_minus8").isZero();
    assertThat(configuration.bitDepthChromaMinus8()).as("bit_depth_chroma_minus8").isZero();
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"h264", "hevc", "av1"})
  @DisplayName("Should upload 8-bit 4:2:0 video when encoding a 10-bit SDR source")
  void shouldUploadEightBitYuv420pVideoWhenEncodingATenBitSdrSource(String codecFamily)
      throws Exception {
    var source = tenBitSdrSource();

    var uploads =
        execute(
            engine(softwareCapabilities()),
            source.getParent(),
            tenBitSdrSourceJob(source, codecFamily).build());

    var media = playable(uploads.contentOf(uploads.names()));
    assertDecodes(media);
    assertThat(video(media).getCodec()).isEqualTo(codecFamily);
    assertThat(pixelFormatOf(media)).isEqualTo("yuv420p");
  }

  private static VariantJob.Builder tenBitSdrSourceJob(Path source, String codecFamily) {
    var job = variantJobBuilder();
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    job.getDecisionBuilder()
        .setMode(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
        .setVideoCodecFamily(codecFamily)
        .getAudioBuilder()
        .setMode(AudioMode.AUDIO_MODE_NONE);
    job.getVariantBuilder().setWidth(64).setHeight(36).setBitrateBitsPerSecond(100_000);
    job.getExecutionBuilder().setMediaSegmentCount(TEN_BIT_SOURCE_SEGMENT_COUNT);
    return job;
  }

  // 10-bit 4:2:0 HEVC without HDR signaling, the common real 10-bit source.
  private static synchronized Path tenBitSdrSource() throws Exception {
    var source = tenBitSources.resolve("ten-bit-sdr.mp4");
    if (Files.exists(source)) {
      return source;
    }

    var partial = tenBitSources.resolve("ten-bit-sdr.partial.mp4");
    runFfmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc=size=64x36:rate=24",
            "-t",
            String.valueOf(TEN_BIT_SOURCE_SECONDS),
            "-c:v",
            "libx265",
            "-pix_fmt",
            "yuv420p10le",
            "-x265-params",
            "log-level=error",
            "-tag:v",
            "hvc1"),
        partial);
    Files.move(partial, source);
    return source;
  }

  private static String pixelFormatOf(Path media) throws Exception {
    return runToCompletion(
            List.of(
                "ffprobe",
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_entries",
                "stream=pix_fmt",
                "-of",
                "csv=p=0",
                media.toString()))
        .strip();
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

  @Test
  @DisplayName("Should start every media segment with an IDR picture when encoder is libx265")
  void shouldStartEveryMediaSegmentWithIdrWhenEncoderIsLibx265() throws Exception {
    var source = sceneCutSource();
    var job = variantJobBuilder();
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    job.getDecisionBuilder()
        .setMode(TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE)
        .setVideoCodecFamily("hevc")
        .getAudioBuilder()
        .setMode(AudioMode.AUDIO_MODE_NONE);
    job.getVariantBuilder().setWidth(320).setHeight(180).setBitrateBitsPerSecond(200_000);
    job.getExecutionBuilder()
        .setFramerate(SCENE_CUT_SOURCE_FRAME_RATE)
        .setMediaSegmentCount(SCENE_CUT_SOURCE_SEGMENT_COUNT);

    var uploads = execute(engine(softwareCapabilities()), source.getParent(), job.build());

    var uploadNames = uploadNames(SCENE_CUT_SOURCE_SEGMENT_COUNT);
    assertThat(uploads.names()).containsExactlyElementsOf(uploadNames);
    assertThat(uploadNames.subList(1, uploadNames.size()))
        .allSatisfy(
            segment -> {
              var pictures = hevcPictureTypes(uploads.contentOf(List.of("init.mp4", segment)));
              assertThat(pictures)
                  .as("the HEVC picture types of %s in decode order", segment)
                  .first()
                  .isIn(HEVC_IDR_W_RADL, HEVC_IDR_N_LP);
              assertThat(pictures.subList(1, pictures.size()))
                  .as("the HEVC picture types inside %s", segment)
                  .noneMatch(WorkerMediaSmokeTest::isHevcRandomAccessPicture);
            });
  }

  @Test
  @DisplayName(
      "Should write the faster preset's settings into the x264 options when the job carries the"
          + " faster preset")
  void shouldWriteTheFasterPresetsSettingsIntoTheX264OptionsWhenTheJobCarriesTheFasterPreset()
      throws Exception {
    var source = seekableSource("mkv");
    var job = presetJob(source, "h264");

    var uploads = execute(engine(softwareCapabilities()), source.getParent(), job.build());

    // x264's own definition of faster; its default, medium, sets ref=3, subme=7, mixed_ref=1,
    // weightp=2 and rc_lookahead=40.
    assertThat(X264Options.of(uploads.contentOf(uploads.names())))
        .contains("ref=2", "subme=4", "mixed_ref=0", "weightp=1", "rc_lookahead=20");
  }

  @Test
  @DisplayName(
      "Should encode the bytes of SVT-AV1 preset 10 when the job carries the faster preset")
  void shouldEncodeTheBytesOfSvtAv1Preset10WhenTheJobCarriesTheFasterPreset() throws Exception {
    var source = seekableSource("mkv");
    var job = presetJob(source, "av1");
    var commands = new CopyOnWriteArrayList<List<String>>();
    ProcessLauncher launcher = new ProcessBuilderLauncher();
    launcher = withSvtAv1ParallelismOfOne(capturingCommands(launcher, commands));

    var uploads =
        execute(engine(softwareCapabilities(), launcher), source.getParent(), job.build());

    // SVT-AV1 writes no preset into its sequence header, so the initialization segment is the same
    // at every preset and only the media bytes show it. The references set the preset through
    // SVT-AV1's own parameter instead of FFmpeg's -preset option, and leave it at the default.
    var arguments = svtAv1ArgumentsWithoutPreset(commands.getFirst());
    var preset10 = svtAv1Reference(arguments, ":preset=10");
    var libraryDefault = svtAv1Reference(arguments, "");
    var uploaded = uploads.contentOf(uploads.names());
    assertThat(uploaded).isEqualTo(preset10).isNotEqualTo(libraryDefault);
  }

  private static VariantJob.Builder presetJob(Path source, String codecFamily) {
    var job = variantJobBuilder();
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    job.getDecisionBuilder()
        .setMode(TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE)
        .setVideoCodecFamily(codecFamily)
        .getAudioBuilder()
        .setMode(AudioMode.AUDIO_MODE_NONE);
    job.getVariantBuilder()
        .setWidth(64)
        .setHeight(36)
        .setBitrateBitsPerSecond(100_000)
        .setEncoderPreset(EncoderPreset.ENCODER_PRESET_FASTER);
    job.getExecutionBuilder().setMediaSegmentCount(SEEKABLE_SOURCE_SEGMENT_COUNT);
    return job;
  }

  private static ProcessLauncher capturingCommands(
      ProcessLauncher launcher, List<List<String>> commands) {
    return (command, jobAttemptId) -> {
      commands.add(List.copyOf(command));
      return launcher.launch(command, jobAttemptId);
    };
  }

  // The worker's arguments between "ffmpeg -y" and its pipe:1 output, without its -preset option.
  private static List<String> svtAv1ArgumentsWithoutPreset(List<String> command) {
    var arguments = new ArrayList<>(command.subList(2, command.size() - 1));
    var preset = arguments.indexOf("-preset");
    assertThat(preset).as("the position of the worker's -preset option").isNotNegative();
    assertThat(arguments.subList(preset, preset + 2)).containsExactly("-preset", "10");
    arguments.subList(preset, preset + 2).clear();
    return arguments;
  }

  private byte[] svtAv1Reference(List<String> arguments, String addedParameters) throws Exception {
    var adjusted = new ArrayList<>(arguments);
    var parameters = adjusted.indexOf("-svtav1-params") + 1;
    adjusted.set(parameters, adjusted.get(parameters) + addedParameters);
    var output = Files.createTempFile(root, "reference", ".mp4");
    runFfmpeg(adjusted, output);
    return Files.readAllBytes(output);
  }

  // 18 s at 30 fps: a test pattern for 2.5 s, then color bars. The cut at frame 75 lies inside
  // segment 0, where x265's scene-cut detection would otherwise place a keyframe.
  private Path sceneCutSource() throws Exception {
    var source = root.resolve("scene-cut.mkv");
    runFfmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc=size=320x180:rate=30:duration=2.5",
            "-f",
            "lavfi",
            "-i",
            "smptehdbars=size=320x180:rate=30:duration=15.5",
            "-filter_complex",
            "[0:v][1:v]concat=n=2:v=1:a=0",
            "-c:v",
            "libx264",
            "-preset",
            "ultrafast"),
        source);
    return source;
  }

  // Each picture's NAL unit type in decode order, read from the first slice of every picture
  // after FFmpeg rewrites the length-prefixed samples as an Annex B stream.
  private List<Integer> hevcPictureTypes(byte[] media) throws Exception {
    var annexB = Files.createTempFile(root, "pictures", ".hevc");
    runFfmpeg(
        List.of(
            "-i",
            playable(media).toString(),
            "-map",
            "0:v:0",
            "-c:v",
            "copy",
            "-bsf:v",
            "hevc_mp4toannexb",
            "-f",
            "hevc"),
        annexB);
    var stream = Files.readAllBytes(annexB);
    var pictures = new ArrayList<Integer>();
    for (var at = 0; at + 5 < stream.length; at++) {
      if (stream[at] != 0 || stream[at + 1] != 0 || stream[at + 2] != 1) {
        continue;
      }

      var type = (stream[at + 3] >> 1) & 0x3f;
      var firstSliceOfPicture = (stream[at + 5] & 0x80) != 0;
      if (type <= HEVC_LAST_VIDEO_CODING_LAYER_TYPE && firstSliceOfPicture) {
        pictures.add(type);
      }
    }

    return pictures;
  }

  private static boolean isHevcRandomAccessPicture(int type) {
    return type >= HEVC_FIRST_RANDOM_ACCESS_TYPE && type <= HEVC_LAST_RANDOM_ACCESS_TYPE;
  }

  @ParameterizedTest(name = "{0} source, {1}")
  @MethodSource("sourceContainersAndModes")
  @DisplayName(
      "Should upload the same initialization segment as an attempt from the start when a"
          + " replacement attempt seeks into a real source")
  void
      shouldUploadTheSameInitializationSegmentAsAnAttemptFromTheStartWhenAReplacementAttemptSeeksIntoARealSource(
          String container, TranscodeMode mode) throws Exception {
    var source = seekableSource(container);
    var fromStart = seekableSourceJob(source, mode);
    var replacement = seekableSourceJob(source, mode);
    replacement.getExecutionBuilder().setStartSequenceNumber(REPLACEMENT_START_SEQUENCE_NUMBER);

    var fromStartUploads =
        execute(engine(softwareCapabilities()), source.getParent(), fromStart.build());
    var replacementUploads =
        execute(engine(softwareCapabilities()), source.getParent(), replacement.build());

    assertThat(replacementUploads.names()).containsExactly("init.mp4", "segment2.m4s");
    assertThat(replacementUploads.segments())
        .as("the replacement attempt's initialization segment")
        .containsEntry("init.mp4", fromStartUploads.segments().get("init.mp4"));
  }

  static Stream<Arguments> sourceContainersAndModes() {
    return Stream.of("mp4", "mkv", "ts")
        .flatMap(
            container ->
                Stream.of(
                        TranscodeMode.TRANSCODE_MODE_REMUX,
                        TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE,
                        TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE,
                        TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
                    .map(mode -> Arguments.of(container, mode)));
  }

  private static VariantJob.Builder seekableSourceJob(Path source, TranscodeMode mode) {
    var job = variantJobBuilder();
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    job.getDecisionBuilder()
        .setMode(mode)
        .getAudioBuilder()
        .setMode(audioModeOf(mode))
        .setCodec("aac")
        .setChannels(2)
        .setBitrateBitsPerSecond(128_000);
    job.getVariantBuilder().setWidth(64).setHeight(36).setBitrateBitsPerSecond(100_000);
    job.getExecutionBuilder().setMediaSegmentCount(SEEKABLE_SOURCE_SEGMENT_COUNT);
    return job;
  }

  private static AudioMode audioModeOf(TranscodeMode mode) {
    return switch (mode) {
      case TRANSCODE_MODE_REMUX, TRANSCODE_MODE_VIDEO_TRANSCODE -> AudioMode.AUDIO_MODE_COPY;
      default -> AudioMode.AUDIO_MODE_TRANSCODE;
    };
  }

  // H.264 with a keyframe every 2 s, so that a stream copy's seek to a segment boundary lands on
  // one, and pink noise, whose AAC frames vary in size as a real soundtrack's do. The MP4 and
  // MPEG-TS sources carry the Matroska source's streams unchanged.
  private static synchronized Path seekableSource(String container) throws Exception {
    var matroska = seekableSources.resolve("seekable.mkv");
    if (!generatedSeekableSources.contains(matroska)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24",
              "-f",
              "lavfi",
              "-i",
              "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5",
              "-t",
              String.valueOf(SEEKABLE_SOURCE_SECONDS),
              "-c:v",
              "libx264",
              "-g",
              "48",
              "-c:a",
              "aac"),
          matroska);
      generatedSeekableSources.add(matroska);
    }

    var source = seekableSources.resolve("seekable." + container);
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(List.of("-i", matroska.toString(), "-c", "copy"), source);
      generatedSeekableSources.add(source);
    }

    return source;
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
        .encoderStallTimeout(FfmpegTranscodeEngine.DEFAULT_ENCODER_STALL_TIMEOUT)
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
    runToCompletion(
        Stream.of(
                List.of("ffmpeg", "-nostdin", "-v", "error", "-y"),
                arguments,
                List.of(output.toString()))
            .flatMap(List::stream)
            .toList());
  }

  // Returns the command's combined output after it exits successfully.
  private static String runToCompletion(List<String> command) throws Exception {
    var log = Files.createTempFile(longSources, command.getFirst(), ".log");
    var process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertThat(process.waitFor(2, TimeUnit.MINUTES))
          .as(command.getFirst() + " finished")
          .isTrue();
      var output = Files.readString(log);
      assertThat(process.exitValue()).as(output).isZero();
      return output;
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

  private static ProbeAttemptResult keyframeProbe(Path source) {
    return probeExecutor().probe(source, keyframeRequest()).result();
  }

  private static FfprobeExecutor probeExecutor() {
    return FfprobeExecutor.forBinaries(Path.of("ffprobe"), Path.of("ffmpeg"));
  }

  private static ProbeRequest packetScanRequest() {
    return keyframeRequest().toBuilder().setScanKeyframePackets(true).build();
  }

  private static Optional<ProcessHandle> packetScanProcess(Path source) {
    return ProcessHandle.current()
        .descendants()
        .filter(
            process ->
                process
                    .info()
                    .arguments()
                    .map(List::of)
                    .filter(arguments -> arguments.contains("framecrc"))
                    .filter(arguments -> arguments.contains(source.toString()))
                    .isPresent())
        .findFirst();
  }

  private static ProbeRequest keyframeRequest() {
    return requestBuilder()
        .setProbeVersion(FfprobeExecutor.KEYFRAME_POSITIONS_PROBE_VERSION)
        .build();
  }

  // MP4 sources that FFmpeg's stream copy writes: an empty edit that delays the video by 0.5 s,
  // an edit that starts the media between two keyframes, and a container start of 0.276009 s.
  // That start is 3,391.6 ticks of the video's 1/12288 s time base. Two more sources have negative
  // composition offsets (ctts version 1), one of them behind an empty edit.
  private Path keyframeSource(String fixture) throws Exception {
    var resource = getClass().getResource("/keyframes/" + fixture);
    var base = getClass().getResource("/keyframes/mp4-bframes-editlist.mp4");
    assertThat(base).isNotNull();
    var basePath = Path.of(base.toURI()).toString();
    return switch (fixture) {
      case EMPTY_EDIT_SOURCE -> {
        var output = root.resolve(fixture);
        runFfmpeg(
            List.of(
                "-itsoffset",
                "0.5",
                "-i",
                basePath,
                "-i",
                basePath,
                "-map",
                "0:v",
                "-map",
                "1:a",
                "-c",
                "copy"),
            output);
        yield output;
      }
      case FRACTIONAL_START_SOURCE -> {
        var output = root.resolve(fixture);
        runFfmpeg(List.of("-itsoffset", "0.3", "-i", basePath, "-c", "copy"), output);
        yield output;
      }
      case NEGATIVE_OFFSETS_SOURCE -> {
        var output = root.resolve(fixture);
        runFfmpeg(
            List.of("-i", basePath, "-c", "copy", "-movflags", "+negative_cts_offsets"), output);
        yield output;
      }
      case NEGATIVE_OFFSETS_EMPTY_EDIT_SOURCE -> {
        var output = root.resolve(fixture);
        runFfmpeg(
            List.of(
                "-itsoffset",
                "0.5",
                "-i",
                basePath,
                "-i",
                basePath,
                "-map",
                "0:v",
                "-map",
                "1:a",
                "-c",
                "copy",
                "-movflags",
                "+negative_cts_offsets"),
            output);
        yield output;
      }
      case REORDERED_AVI_SOURCE -> {
        var output = root.resolve(fixture);
        runFfmpeg(
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=64x36:rate=24:duration=6",
                "-c:v",
                "libx264",
                "-bf",
                "2",
                "-g",
                "48",
                "-pix_fmt",
                "yuv420p"),
            output);
        yield output;
      }
      case MOV_SOURCE -> {
        var output = root.resolve(fixture);
        runFfmpeg(List.of("-i", basePath, "-c", "copy"), output);
        yield output;
      }
      case TRIMMED_START_SOURCE -> {
        var output = root.resolve(fixture);
        runFfmpeg(List.of("-ss", "1", "-i", basePath, "-c", "copy"), output);
        yield output;
      }
      default -> {
        assertThat(resource).as("fixture %s", fixture).isNotNull();
        yield Path.of(resource.toURI());
      }
    };
  }

  private static List<KeyframeTime> keyframeTimes(List<ProbeKeyframePositionsPart> parts) {
    var times = new ArrayList<KeyframeTime>();
    long presentation = 0;
    long decode = 0;
    for (var part : parts) {
      for (var i = 0; i < part.getPresentationTimeDeltasCount(); i++) {
        presentation += part.getPresentationTimeDeltas(i);
        decode += part.getDecodeTimeDeltas(i);
        times.add(new KeyframeTime(presentation, decode));
      }
    }

    return times;
  }

  // The keyframe packets of the chosen stream as FFmpeg's -copyts -start_at_zero passes them to a
  // muxer, which is the contract's zero-based timeline. Only the chosen stream is mapped, as a job
  // attempt without audio maps it. -discard none keeps an MPEG-TS source's time zero at the
  // earliest start of every stream, as ADR 0037 requires; without it, FFmpeg starts the timeline at
  // the earliest mapped stream. framecrc adds the packet flags to every line whose packet is not
  // only a keyframe, and side data after them. A Matroska keyframe's decode time is its cue time,
  // which equals its presentation time.
  private static List<KeyframeTime> packetKeyframes(Path source, KeyframePositions positions)
      throws Exception {
    var process =
        new ProcessBuilder(
                "ffmpeg",
                "-hide_banner",
                "-nostdin",
                "-loglevel",
                "error",
                "-copyts",
                "-start_at_zero",
                "-discard",
                "none",
                "-i",
                source.toString(),
                "-map",
                "0:" + positions.getStreamIndex(),
                "-c",
                "copy",
                "-f",
                "framecrc",
                "-")
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    var lines =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
            .lines()
            .toList();
    assertThat(process.waitFor(1, TimeUnit.MINUTES)).isTrue();
    assertThat(process.exitValue()).isZero();
    assertThat(lines)
        .contains(
            "#tb 0: %d/%d"
                .formatted(positions.getTimeBaseNumerator(), positions.getTimeBaseDenominator()));
    var isMatroska = source.toString().endsWith(".mkv");
    var keyframes = new ArrayList<KeyframeTime>();
    for (var line : lines.stream().filter(line -> !line.startsWith("#")).toList()) {
      var fields = Arrays.stream(line.split(",")).map(String::trim).toList();
      var flags =
          fields.stream()
              .filter(field -> field.startsWith("F="))
              .findFirst()
              .map(field -> Integer.decode(field.substring("F=".length())))
              .orElse(1);
      if ((flags & 1) != 0) {
        var presentation = Long.parseLong(fields.get(2));
        var decode = isMatroska ? presentation : Long.parseLong(fields.get(1));
        keyframes.add(new KeyframeTime(presentation, decode));
      }
    }

    return keyframes;
  }

  private record KeyframeTime(long presentation, long decode) {}

  private static List<ProbeStreamInfo> streams(Path media) {
    return probeExecutor()
        .probe(media, requestBuilder().build())
        .result()
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

  // The fields of an ISO/IEC 14496-15 AVCDecoderConfigurationRecord that name the profile, chroma
  // format and bit depth. The chroma format and bit depths follow the parameter sets and are
  // present only for the High profiles.
  private record AvcDecoderConfiguration(
      int profileIndication,
      int chromaFormatIdc,
      int bitDepthLumaMinus8,
      int bitDepthChromaMinus8) {

    private static final byte[] AVCC = "avcC".getBytes(StandardCharsets.US_ASCII);

    private static AvcDecoderConfiguration of(byte[] initializationSegment) {
      var configuration = ByteBuffer.wrap(initializationSegment);
      configuration.position(indexOfAvcc(initializationSegment) + AVCC.length);
      configuration.get();
      var profileIndication = Byte.toUnsignedInt(configuration.get());
      configuration.position(configuration.position() + 3);
      skipParameterSets(configuration, configuration.get() & 0x1f);
      skipParameterSets(configuration, Byte.toUnsignedInt(configuration.get()));
      return new AvcDecoderConfiguration(
          profileIndication,
          configuration.get() & 0x03,
          configuration.get() & 0x07,
          configuration.get() & 0x07);
    }

    private static void skipParameterSets(ByteBuffer configuration, int count) {
      for (var set = 0; set < count; set++) {
        var length = Short.toUnsignedInt(configuration.getShort());
        configuration.position(configuration.position() + length);
      }
    }

    private static int indexOfAvcc(byte[] initializationSegment) {
      for (var index = 0; index + AVCC.length <= initializationSegment.length; index++) {
        if (Arrays.equals(
            initializationSegment, index, index + AVCC.length, AVCC, 0, AVCC.length)) {
          return index;
        }
      }

      throw new AssertionError("the initialization segment carries no avcC box");
    }
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
