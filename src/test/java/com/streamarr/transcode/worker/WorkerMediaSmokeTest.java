package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.copiedVideo;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import build.buf.gen.streamarr.transcode.v1.AudioMode;
import build.buf.gen.streamarr.transcode.v1.EncoderPreset;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.JobAttemptCompleted;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailed;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
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
import build.buf.gen.streamarr.transcode.v1.TranscodeExecution;
import build.buf.gen.streamarr.transcode.v1.TranscodeMode;
import build.buf.gen.streamarr.transcode.v1.TranscodeWorkerServiceGrpc;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentRequest;
import build.buf.gen.streamarr.transcode.v1.UploadSegmentResponse;
import build.buf.gen.streamarr.transcode.v1.VariantJob;
import build.buf.gen.streamarr.transcode.v1.VideoCodecFamily;
import build.buf.gen.streamarr.transcode.v1.VideoCodecProfile;
import build.buf.gen.streamarr.transcode.v1.VideoCodecTier;
import build.buf.gen.streamarr.transcode.v1.VideoDecision;
import build.buf.gen.streamarr.transcode.v1.VideoMode;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.engine.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.ProcessBuilderLauncher;
import com.streamarr.transcode.engine.ProcessLauncher;
import com.streamarr.transcode.engine.TranscodeCapabilityService;
import com.streamarr.transcode.fixtures.AudioInterval;
import com.streamarr.transcode.fixtures.Mp4Samples;
import com.streamarr.transcode.fixtures.TopLevelBoxes;
import com.streamarr.transcode.fixtures.TopLevelBoxes.Box;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.worker.support.FfprobePackets;
import com.streamarr.transcode.worker.support.FfprobePackets.Packet;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import com.streamarr.transcode.worker.support.TeeingProcessLauncher;
import com.streamarr.transcode.worker.support.X264Options;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
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
  // The seekable sources' 6 s period in their 48 kHz audio timescale.
  private static final long SEEKABLE_SOURCE_AUDIO_PERIOD_TICKS = 6L * 48_000;

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
  private static final int HEVC_VPS = 32;
  private static final int HEVC_SPS = 33;
  private static final int HEVC_PPS = 34;
  private static final int HEVC_PREFIX_SEI = 39;
  private static final int HEVC_SUFFIX_SEI = 40;

  // SEI payload types (ITU-T H.265, 7.3.5).
  private static final int HEVC_MASTERING_DISPLAY_SEI = 137;
  private static final int HEVC_CONTENT_LIGHT_LEVEL_SEI = 144;

  // The hvcC record's fields before its count of NAL unit arrays (ISO/IEC 14496-15, 8.3.3.1.2).
  private static final int HVCC_FIELDS_BEFORE_ARRAYS = 22;

  // A common HDR10 grade: BT.2020 primaries, a D65 white point, and a 1,000 cd/m2 display.
  private static final MasteringDisplayColourVolume HDR10_MASTERING_DISPLAY =
      new MasteringDisplayColourVolume(
          List.of(8500, 39850, 6550, 2300, 35400, 14600), 15635, 16450, 10_000_000, 50);
  private static final ContentLightLevel HDR10_CONTENT_LIGHT_LEVEL =
      new ContentLightLevel(1000, 400);
  // libx265's 64x36 Main 10 at level 1.0 in the Main tier.
  private static final String HDR10_CODEC_STRING = "hvc1.2.4.L30.90";

  // The fixture's Constrained Baseline H.264 at level 1.3. Its 160x90 variant at 24 fps and 500
  // kb/s
  // needs H.264 level 1.3.
  private static final H264Source FIXTURE = new H264Source("avc1.42C00D", 13);

  // The seekable sources' libx264 encode of testsrc's RGB: High 4:4:4 Predictive at level 1.0. Its
  // 64x36 variant needs H.264 level 1.1.
  private static final H264Source SEEKABLE_SOURCE = new H264Source("avc1.F4000A", 11);

  // The levels that a 64x36 variant at 24 fps and up to 100 kb/s needs: H.264 1.1, because High
  // allows 80 kb/s at level 1.0, H.265 1.0 and AV1 2.0.
  private static final Map<String, Integer> TINY_VARIANT_LEVELS =
      Map.of("h264", 11, "hevc", 10, "av1", 20);

  // The sample-entry sources span segments 0 and 1 at a 1 s period, with a keyframe every second.
  private static final int SAMPLE_ENTRY_SOURCE_SECONDS = 2;
  private static final String DOLBY_VISION_FIXTURE = "dolby-vision-8.2-dvh1.mp4";
  // The fixture's 10-bit base layer: Main 10 at level 2.0 in the Main tier.
  private static final String DOLBY_VISION_BASE_LAYER_CODEC_STRING = "hvc1.2.4.L60.90";

  // 18 s of video at the default 6 s period.
  private static final int ONE_FRAME_PER_SECOND_SEGMENT_COUNT = 3;

  // 90 s of video at the default 6 s period.
  private static final int AUDIO_ENDS_LONG_BEFORE_VIDEO_SEGMENT_COUNT = 15;

  // The fragmentation target that a worker passes FFmpeg unless its settings name another.
  private static final Duration DEFAULT_FRAGMENTATION_TARGET = Duration.ofMillis(250);

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
  @TempDir static Path irregularKeyframeSources;

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
    var job = variantJobBuilder(mode);
    withVideo(job, h264VideoFor(mode, FIXTURE));
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
    withVideo(job, copiedVideo(FIXTURE.codecString()));
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
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    // 320x180 at 24 fps needs AV1 level 2.0.
    withVideo(job, forcedSoftwareEncode("av1", 20));
    job.getDecisionBuilder()
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

  @ParameterizedTest(name = "{0}")
  @CsvSource({"h264, avc1, High, 40", "hevc, hvc1, Main, 120", "av1, av01, Main, 8"})
  @DisplayName(
      "Should write the forced codec profile, level and tier in 8-bit 4:2:0 when a software encoder"
          + " encodes")
  void shouldWriteTheForcedCodecProfileLevelAndTierInEightBitFourTwoZeroWhenASoftwareEncoderEncodes(
      String codecFamily, String sampleEntry, String profile, int probedLevel) throws Exception {
    var source = tenBitSdrSource();
    // Level 4.0, far above the level each encoder chooses for 64x36 on its own.
    var job = tenBitSdrSourceJob(source, codecFamily);
    withVideo(job, forcedSoftwareEncode(codecFamily, 40));

    // ffprobe reports no tier. The tier is checked because the job completes: the producer refuses
    // an initialization segment whose codec string, with its L, M or H tier letter, differs.
    var uploads = execute(engine(softwareCapabilities()), source.getParent(), job.build());

    var media = playable(uploads.contentOf(uploads.names()));
    assertDecodes(media);
    assertThat(videoStreamFields(media, "codec_tag_string,profile,level,pix_fmt"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "codec_tag_string",
                sampleEntry,
                "profile",
                profile,
                "level",
                String.valueOf(probedLevel),
                "pix_fmt",
                "yuv420p"));
  }

  @Test
  @DisplayName(
      "Should report an output mismatch and upload nothing when a real encode declares another"
          + " level than the job's codec string")
  void
      shouldReportAnOutputMismatchAndUploadNothingWhenARealEncodeDeclaresAnotherLevelThanTheJobsCodecString()
          throws Exception {
    var source = tenBitSdrSource();
    var job = tenBitSdrSourceJob(source, "h264");
    withVideo(job, forcedSoftwareEncode("h264", 40).setCodecString("avc1.64000B"));

    var failure =
        executeExpectingFailure(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(failure.failure().getFailure())
        .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_OUTPUT_MISMATCH);
    assertThat(failure.uploads().names()).isEmpty();
  }

  @ParameterizedTest(name = "{0} source")
  @ValueSource(strings = {"mp4", "mkv", "ts"})
  @DisplayName("Should write hvc1 when a worker copies libx265 HEVC from a real source")
  void shouldWriteHvc1WhenAWorkerCopiesLibx265HevcFromARealSource(String container)
      throws Exception {
    // libx265 at 64x36 writes Main at level 1.0. FFmpeg's mp4 muxer writes its own default, hev1,
    // for this MP4 source and for every copy of HEVC from Matroska or MPEG-TS.
    var source = root.resolve("hevc." + container);
    runFfmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x36:rate=24:duration=" + SAMPLE_ENTRY_SOURCE_SECONDS,
            "-c:v",
            "libx265",
            "-preset",
            "ultrafast",
            "-pix_fmt",
            "yuv420p",
            "-x265-params",
            "keyint=24:min-keyint=24:scenecut=0:log-level=error"),
        source);

    var media = copiedMedia(source, "hvc1.1.6.L30.90");

    assertThat(videoStreamFields(media, "codec_tag_string"))
        .containsEntry("codec_tag_string", "hvc1");
  }

  // Sources whose parameter sets travel only in the samples, under the sample entry that the
  // source names: hev1 and avc3 as GPAC wrote them, and dvhe and dvav renamed from them.
  @ParameterizedTest(name = "{1} source")
  @CsvSource({
    "hev1-parameter-sets-in-samples.mp4, hev1, hvc1.1.6.L30.90, hvc1",
    "hev1-parameter-sets-in-samples.mp4, dvhe, hvc1.1.6.L30.90, hvc1",
    "avc3-parameter-sets-in-samples.mp4, avc3, avc1.42C00A, avc1",
    "avc3-parameter-sets-in-samples.mp4, dvav, avc1.42C00A, avc1"
  })
  @DisplayName(
      "Should move the source's parameter sets into the record of every attempt when the source"
          + " carries them only in its samples")
  void
      shouldMoveTheSourcesParameterSetsIntoTheRecordOfEveryAttemptWhenTheSourceCarriesThemOnlyInItsSamples(
          String fixture, String sourceSampleEntry, String codecString, String sampleEntry)
          throws Exception {
    var source = sampleEntryFixture(fixture, sourceSampleEntry);
    var fromStart = sampleEntryCopyJob(source, codecString);
    var replacement = sampleEntryCopyJob(source, codecString);
    replacement.getExecutionBuilder().setStartSequenceNumber(1);

    // The producer refuses a record that lacks the parameter sets its sample entry promises, so
    // each attempt completes only when FFmpeg moved them into the record.
    var fromStartUploads = execute(engine(realCapabilities()), root, fromStart.build());
    var replacementUploads = execute(engine(realCapabilities()), root, replacement.build());

    var media = playable(fromStartUploads.contentOf(fromStartUploads.names()));
    assertDecodes(media);
    assertThat(videoStreamFields(media, "codec_tag_string"))
        .containsEntry("codec_tag_string", sampleEntry);
    assertThat(replacementUploads.segments())
        .as("the replacement attempt's initialization segment")
        .containsEntry("init.mp4", fromStartUploads.segments().get("init.mp4"));
  }

  @Test
  @DisplayName(
      "Should upload the same initialization segment from every attempt when the SEI messages of"
          + " a source's keyframes differ")
  void
      shouldUploadTheSameInitializationSegmentFromEveryAttemptWhenTheSeiMessagesOfASourcesKeyframesDiffer()
          throws Exception {
    // Each keyframe of the fixture repeats libx265's version message, a user-data SEI message,
    // before its parameter sets. The second keyframe's copy now names another build.
    var source = sampleEntryFixture("hev1-parameter-sets-in-samples.mp4", "hev1");
    var bytes = Files.readAllBytes(source);
    var version = "x265 (build 216)".getBytes(StandardCharsets.US_ASCII);
    var first = indexOf(bytes, version);
    var second = indexOf(Arrays.copyOfRange(bytes, first + 1, bytes.length), version) + first + 1;
    assertThat(second).as("the second keyframe's version message").isGreaterThan(first);
    bytes[second + version.length - 2] = '7';
    Files.write(source, bytes);
    var fromStart = sampleEntryCopyJob(source, "hvc1.1.6.L30.90");
    var replacement = sampleEntryCopyJob(source, "hvc1.1.6.L30.90");
    replacement.getExecutionBuilder().setStartSequenceNumber(1);

    var fromStartUploads = execute(engine(realCapabilities()), root, fromStart.build());
    var replacementUploads = execute(engine(realCapabilities()), root, replacement.build());

    assertDecodes(playable(replacementUploads.contentOf(replacementUploads.names())));
    assertThat(replacementUploads.segments())
        .as("the replacement attempt's initialization segment")
        .containsEntry("init.mp4", fromStartUploads.segments().get("init.mp4"));
    var changedVersion = "x265 (build 217)".getBytes(StandardCharsets.US_ASCII);
    assertThat(indexOf(replacementUploads.segments().get("segment1.m4s"), changedVersion))
        .as("the version message in the first keyframe of the replacement attempt")
        .isNotNegative();
  }

  @Test
  @DisplayName(
      "Should deliver HDR10 static metadata in front of every keyframe and none in the record when"
          + " a worker copies HEVC whose record alone carries it")
  void
      shouldDeliverHdr10StaticMetadataInFrontOfEveryKeyframeAndNoneInTheRecordWhenAWorkerCopiesHevcWhoseRecordAloneCarriesIt()
          throws Exception {
    var source = hdr10SourceWithStaticMetadataOnlyInItsRecord();
    var sourceBytes = Files.readAllBytes(source);
    assertThat(seiMessages(hevcRecordNalUnits(sourceBytes)))
        .as("the SEI messages of the source's record")
        .containsKeys(HEVC_MASTERING_DISPLAY_SEI, HEVC_CONTENT_LIGHT_LEVEL_SEI);
    assertThat(nalUnitsOfFirstPicture(sourceBytes, firstChunkOffset(sourceBytes)))
        .as("the NAL unit types of the source's first keyframe")
        .extracting(WorkerMediaSmokeTest::hevcNalUnitType)
        .isNotEmpty()
        .doesNotContain(HEVC_PREFIX_SEI, HEVC_SUFFIX_SEI);

    var uploads =
        execute(
            engine(realCapabilities()),
            root,
            sampleEntryCopyJob(source, HDR10_CODEC_STRING).build());

    assertThat(uploads.names()).containsExactlyElementsOf(uploadNames(SAMPLE_ENTRY_SOURCE_SECONDS));
    assertDecodes(playable(uploads.contentOf(uploads.names())));
    assertThat(hevcRecordNalUnits(uploads.segments().get("init.mp4")))
        .as("the NAL unit types of the delivered record")
        .extracting(WorkerMediaSmokeTest::hevcNalUnitType)
        .doesNotContain(HEVC_PREFIX_SEI, HEVC_SUFFIX_SEI)
        .contains(HEVC_VPS, HEVC_SPS, HEVC_PPS);
    // Neither the locked FFmpeg nor Homebrew FFmpeg writes an mdcv or clli box for this copy: the
    // source has neither box, and only SEI messages carry its HDR10 static metadata.
    for (var segment : uploads.names().subList(1, uploads.names().size())) {
      var bytes = uploads.segments().get(segment);
      var keyframe = nalUnitsOfFirstPicture(bytes, firstSampleOffset(bytes));
      assertThat(hevcNalUnitType(keyframe.getLast()))
          .as("the picture type at the start of %s", segment)
          .matches(WorkerMediaSmokeTest::isHevcRandomAccessPicture);
      var messages = seiMessages(keyframe);
      assertThat(messages)
          .as("the SEI messages in front of the keyframe of %s", segment)
          .containsKeys(HEVC_MASTERING_DISPLAY_SEI, HEVC_CONTENT_LIGHT_LEVEL_SEI);
      assertThat(MasteringDisplayColourVolume.of(messages.get(HEVC_MASTERING_DISPLAY_SEI)))
          .isEqualTo(HDR10_MASTERING_DISPLAY);
      assertThat(ContentLightLevel.of(messages.get(HEVC_CONTENT_LIGHT_LEVEL_SEI)))
          .isEqualTo(HDR10_CONTENT_LIGHT_LEVEL);
    }
  }

  // libx265 writes the HDR10 SEI messages into the record and in front of every keyframe. The
  // filter removes the parameter sets (types 32 to 34) and prefix SEI messages (type 39) from the
  // samples, and keeps the record.
  private Path hdr10SourceWithStaticMetadataOnlyInItsRecord() throws Exception {
    var encoded = root.resolve("hdr10-encoded.mp4");
    runFfmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x36:rate=24:duration=" + SAMPLE_ENTRY_SOURCE_SECONDS,
            "-c:v",
            "libx265",
            "-preset",
            "ultrafast",
            "-pix_fmt",
            "yuv420p10le",
            "-x265-params",
            "keyint=24:min-keyint=24:scenecut=0:log-level=error:colorprim=bt2020"
                + ":transfer=smpte2084:colormatrix=bt2020nc:master-display="
                + HDR10_MASTERING_DISPLAY.x265Parameter()
                + ":max-cll="
                + HDR10_CONTENT_LIGHT_LEVEL.x265Parameter()),
        encoded);
    var source = root.resolve("hdr10.mp4");
    runFfmpeg(
        List.of(
            "-i",
            encoded.toString(),
            "-c",
            "copy",
            "-bsf:v",
            "filter_units=remove_types=32|33|34|39",
            "-tag:v",
            "hvc1"),
        source);
    return source;
  }

  // The NAL units of every array of the first hvcC record (ISO/IEC 14496-15, 8.3.3.1.2).
  private static List<byte[]> hevcRecordNalUnits(byte[] mp4) {
    var hvcC = indexOf(mp4, "hvcC".getBytes(StandardCharsets.US_ASCII));
    assertThat(hvcC).as("the hvcC box").isNotNegative();
    var configuration = ByteBuffer.wrap(mp4);
    configuration.position(hvcC + 4 + HVCC_FIELDS_BEFORE_ARRAYS);
    var arrays = Byte.toUnsignedInt(configuration.get());
    var nalUnits = new ArrayList<byte[]>();
    for (var array = 0; array < arrays; array++) {
      configuration.get();
      var count = Short.toUnsignedInt(configuration.getShort());
      for (var nalUnit = 0; nalUnit < count; nalUnit++) {
        var bytes = new byte[Short.toUnsignedInt(configuration.getShort())];
        configuration.get(bytes);
        nalUnits.add(bytes);
      }
    }

    return nalUnits;
  }

  // The offset of a progressive MP4's first chunk: the first entry of its stco box.
  private static int firstChunkOffset(byte[] mp4) {
    var stco = indexOf(mp4, "stco".getBytes(StandardCharsets.US_ASCII));
    assertThat(stco).as("the stco box").isNotNegative();
    return ByteBuffer.wrap(mp4).getInt(stco + 12);
  }

  // The offset of a media segment's first sample: the data offset of its first trun box, which
  // counts from the start of the moof box because CMAF sets default-base-is-moof.
  private static int firstSampleOffset(byte[] segment) {
    var moof = indexOf(segment, "moof".getBytes(StandardCharsets.US_ASCII)) - 4;
    var trun = indexOf(segment, "trun".getBytes(StandardCharsets.US_ASCII));
    assertThat(moof).as("the moof box").isNotNegative();
    assertThat(trun).as("the trun box").isGreaterThan(moof);
    var fields = ByteBuffer.wrap(segment);
    assertThat(fields.getInt(trun + 4) & 0x01).as("the data-offset-present flag of trun").isOne();
    return moof + fields.getInt(trun + 12);
  }

  // The four-byte length-prefixed NAL units from the offset through the first picture's slice.
  private static List<byte[]> nalUnitsOfFirstPicture(byte[] media, int offset) {
    var samples = ByteBuffer.wrap(media);
    samples.position(offset);
    var nalUnits = new ArrayList<byte[]>();
    while (nalUnits.isEmpty()
        || hevcNalUnitType(nalUnits.getLast()) > HEVC_LAST_VIDEO_CODING_LAYER_TYPE) {
      var nalUnit = new byte[samples.getInt()];
      samples.get(nalUnit);
      nalUnits.add(nalUnit);
    }

    return nalUnits;
  }

  private static int hevcNalUnitType(byte[] nalUnit) {
    return (nalUnit[0] >> 1) & 0x3f;
  }

  // The payload of every message in the prefix SEI NAL units by payload type (ITU-T H.265,
  // 7.3.5), without the NAL unit header and emulation prevention bytes.
  private static Map<Integer, byte[]> seiMessages(List<byte[]> nalUnits) {
    var messages = new HashMap<Integer, byte[]>();
    nalUnits.stream()
        .filter(nalUnit -> hevcNalUnitType(nalUnit) == HEVC_PREFIX_SEI)
        .map(nalUnit -> ByteBuffer.wrap(rawPayloadOf(nalUnit)))
        .forEach(
            payloads -> {
              // The last byte holds the stop bit that ends the trailing bits.
              while (payloads.remaining() > 1) {
                var type = seiVariableLengthValue(payloads);
                var payload = new byte[seiVariableLengthValue(payloads)];
                payloads.get(payload);
                messages.put(type, payload);
              }
            });
    return messages;
  }

  // A payload type or size: a run of 0xFF bytes, each adding 255, then the last byte.
  private static int seiVariableLengthValue(ByteBuffer payloads) {
    var value = 0;
    var next = Byte.toUnsignedInt(payloads.get());
    while (next == 0xff) {
      value += next;
      next = Byte.toUnsignedInt(payloads.get());
    }

    return value + next;
  }

  // The bytes after the two-byte NAL unit header, without the 0x03 that follows each 0x0000.
  private static byte[] rawPayloadOf(byte[] nalUnit) {
    var payload = new ByteArrayOutputStream();
    var zeros = 0;
    for (var at = 2; at < nalUnit.length; at++) {
      var value = nalUnit[at];
      if (zeros >= 2 && value == 3) {
        zeros = 0;
        continue;
      }

      zeros = value == 0 ? zeros + 1 : 0;
      payload.write(value);
    }

    return payload.toByteArray();
  }

  // An SEI mastering display colour volume (ITU-T H.265, D.2.28): the primaries in green, blue,
  // red order, as libx265 writes them, and the white point in units of 0.00002, and the luminance
  // in units of 0.0001 cd/m2.
  private record MasteringDisplayColourVolume(
      List<Integer> primaries,
      int whitePointX,
      int whitePointY,
      long maxLuminance,
      long minLuminance) {

    private static MasteringDisplayColourVolume of(byte[] payload) {
      var fields = ByteBuffer.wrap(payload);
      var primaries = new ArrayList<Integer>();
      for (var coordinate = 0; coordinate < 6; coordinate++) {
        primaries.add(Short.toUnsignedInt(fields.getShort()));
      }

      return new MasteringDisplayColourVolume(
          List.copyOf(primaries),
          Short.toUnsignedInt(fields.getShort()),
          Short.toUnsignedInt(fields.getShort()),
          Integer.toUnsignedLong(fields.getInt()),
          Integer.toUnsignedLong(fields.getInt()));
    }

    private String x265Parameter() {
      return "G(%d,%d)B(%d,%d)R(%d,%d)WP(%d,%d)L(%d,%d)"
          .formatted(
              primaries.get(0),
              primaries.get(1),
              primaries.get(2),
              primaries.get(3),
              primaries.get(4),
              primaries.get(5),
              whitePointX,
              whitePointY,
              maxLuminance,
              minLuminance);
    }
  }

  // An SEI content light level information (ITU-T H.265, D.2.35), in cd/m2.
  private record ContentLightLevel(int maxContentLightLevel, int maxPictureAverageLightLevel) {

    private static ContentLightLevel of(byte[] payload) {
      var fields = ByteBuffer.wrap(payload);
      return new ContentLightLevel(
          Short.toUnsignedInt(fields.getShort()), Short.toUnsignedInt(fields.getShort()));
    }

    private String x265Parameter() {
      return maxContentLightLevel + "," + maxPictureAverageLightLevel;
    }
  }

  @Test
  @DisplayName("Should write avc1 when a worker copies H.264 from a dva1 source")
  void shouldWriteAvc1WhenAWorkerCopiesH264FromADva1Source() throws Exception {
    var avc1 = root.resolve("avc1.mp4");
    runFfmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x36:rate=24:duration=" + SAMPLE_ENTRY_SOURCE_SECONDS,
            "-c:v",
            "libx264",
            "-preset",
            "ultrafast",
            "-pix_fmt",
            "yuv420p",
            "-g",
            "24"),
        avc1);
    var source = withSampleEntry(Files.readAllBytes(avc1), "avc1", "dva1", "dva1.mp4");

    var media = copiedMedia(source, "avc1.42C00A");

    assertThat(videoStreamFields(media, "codec_tag_string"))
        .containsEntry("codec_tag_string", "avc1");
  }

  // The committed Dolby Vision profile 8.2 fixture: 10-bit HEVC under dvh1, with its configuration
  // record and an RPU after every frame.
  @Test
  @DisplayName(
      "Should copy every RPU under hvc1 with the base layer's codec string when a worker copies"
          + " Dolby Vision profile 8.2")
  void shouldCopyEveryRpuUnderHvc1WithTheBaseLayersCodecStringWhenAWorkerCopiesDolbyVision82()
      throws Exception {
    var source = sampleEntryFixture(DOLBY_VISION_FIXTURE, "dvh1");

    // The job completes only when the copy declares the base layer's codec string.
    var media = copiedMedia(source, DOLBY_VISION_BASE_LAYER_CODEC_STRING);

    assertThat(videoStreamFields(media, "codec_tag_string,profile,pix_fmt"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("codec_tag_string", "hvc1", "profile", "Main 10", "pix_fmt", "yuv420p10le"));
    assertThat(frameSideDataPerFrame(media))
        .hasSize(SAMPLE_ENTRY_SOURCE_SECONDS * 24)
        .allSatisfy(sideData -> assertThat(sideData).contains("Dolby Vision RPU Data"));
  }

  // ADR 0013 accepts this loss: the locked FFmpeg's mp4 muxer drops the configuration record of a
  // 10-bit profile 8.2 copy even under -strict unofficial. Other builds may keep it, so only the
  // locked Jellyfin build is held to the loss.
  @Test
  @DisplayName(
      "Should drop the Dolby Vision configuration record when the locked FFmpeg copies Dolby"
          + " Vision profile 8.2")
  void shouldDropTheDolbyVisionConfigurationRecordWhenTheLockedFfmpegCopiesDolbyVision82()
      throws Exception {
    assumeTrue(
        runToCompletion(List.of("ffmpeg", "-version"))
            .lines()
            .findFirst()
            .orElseThrow()
            .contains("-Jellyfin"),
        "the FFmpeg on PATH is not the locked Jellyfin build");
    var source = sampleEntryFixture(DOLBY_VISION_FIXTURE, "dvh1");
    assertThat(streamSideData(source)).contains("DOVI configuration record");

    var media = copiedMedia(source, DOLBY_VISION_BASE_LAYER_CODEC_STRING);

    assertThat(streamSideData(media)).doesNotContain("DOVI configuration record");
  }

  // Copies the source on 1 s segments as one attempt from the start, and returns the delivered
  // initialization segment and media segments as one file.
  private Path copiedMedia(Path source, String codecString) throws Exception {
    var uploads =
        execute(engine(realCapabilities()), root, sampleEntryCopyJob(source, codecString).build());

    assertThat(uploads.names()).containsExactlyElementsOf(uploadNames(SAMPLE_ENTRY_SOURCE_SECONDS));
    var media = playable(uploads.contentOf(uploads.names()));
    assertDecodes(media);
    return media;
  }

  private static VariantJob.Builder sampleEntryCopyJob(Path source, String codecString) {
    var family = VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC;
    if (codecString.startsWith("avc1")) {
      family = VideoCodecFamily.VIDEO_CODEC_FAMILY_H264;
    }

    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_REMUX);
    withVideo(job, copiedVideo(codecString).setCodecFamily(family));
    job.getDecisionBuilder()
        .setVideoCodecFamily("")
        .getAudioBuilder()
        .setMode(AudioMode.AUDIO_MODE_NONE);
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(1)
        .setMediaSegmentCount(SAMPLE_ENTRY_SOURCE_SECONDS);
    return job;
  }

  // A committed fixture from src/test/resources/sample-entries, written into the source root under
  // the requested sample entry.
  private Path sampleEntryFixture(String fixture, String sampleEntry) throws Exception {
    var resource = getClass().getResource("/sample-entries/" + fixture);
    assertThat(resource).as("fixture %s", fixture).isNotNull();
    var bytes = Files.readAllBytes(Path.of(resource.toURI()));
    var written = sampleEntryTypeOf(bytes);
    return withSampleEntry(bytes, written, sampleEntry, sampleEntry + "-" + fixture);
  }

  // Writes the MP4 into the source root with the type of its one sample entry renamed.
  private Path withSampleEntry(byte[] mp4, String from, String to, String fileName)
      throws Exception {
    assertThat(sampleEntryTypeOf(mp4)).isEqualTo(from);
    var renamed = mp4.clone();
    var type = to.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(type, 0, renamed, sampleEntryTypeIndex(mp4), type.length);
    var source = root.resolve(fileName);
    Files.write(source, renamed);
    return source;
  }

  private static String sampleEntryTypeOf(byte[] mp4) {
    var at = sampleEntryTypeIndex(mp4);
    return new String(mp4, at, 4, StandardCharsets.US_ASCII);
  }

  // The stsd box's type, version and flags, and entry count come before its first sample entry's
  // size and type.
  private static int sampleEntryTypeIndex(byte[] mp4) {
    var stsd = indexOf(mp4, "stsd".getBytes(StandardCharsets.US_ASCII));
    assertThat(stsd).as("the stsd box").isNotNegative();
    return stsd + 16;
  }

  private static int indexOf(byte[] bytes, byte[] target) {
    for (var at = 0; at + target.length <= bytes.length; at++) {
      if (Arrays.equals(bytes, at, at + target.length, target, 0, target.length)) {
        return at;
      }
    }

    return -1;
  }

  private static List<String> streamSideData(Path media) throws Exception {
    return runToCompletion(
            List.of(
                "ffprobe",
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_entries",
                "stream_side_data=side_data_type",
                "-of",
                "csv=p=0",
                media.toString()))
        .lines()
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .toList();
  }

  // Each decoded frame's side data types, as one comma-separated line per frame.
  private static List<String> frameSideDataPerFrame(Path media) throws Exception {
    return runToCompletion(
            List.of(
                "ffprobe",
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_entries",
                "frame=pict_type:frame_side_data=side_data_type",
                "-of",
                "csv=p=0",
                media.toString()))
        .lines()
        .filter(line -> !line.isBlank())
        .toList();
  }

  // The fields of the first video stream that ffprobe reports, by name.
  private static Map<String, String> videoStreamFields(Path media, String fields) throws Exception {
    return Stream.of(
            runToCompletion(
                    List.of(
                        "ffprobe",
                        "-v",
                        "error",
                        "-select_streams",
                        "v:0",
                        "-show_entries",
                        "stream=" + fields,
                        "-of",
                        "default=noprint_wrappers=1",
                        media.toString()))
                .strip()
                .split("\\R"))
        .map(line -> line.split("=", 2))
        .collect(Collectors.toMap(field -> field[0], field -> field[1]));
  }

  private static VariantJob.Builder tenBitSdrSourceJob(Path source, String codecFamily) {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    withVideo(job, forcedSoftwareEncode(codecFamily, TINY_VARIANT_LEVELS.get(codecFamily)));
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    job.getDecisionBuilder()
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
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE);
    withVideo(job, forcedSoftwareEncode(codecFamily, TINY_VARIANT_LEVELS.get(codecFamily)));
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    job.getDecisionBuilder()
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
    // A 320x180 variant needs H.265 level 2.0.
    withVideo(job, forcedSoftwareEncode("hevc", 20));
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
    // The worker rebuilds each media segment from FFmpeg's samples, so the uploads carry the
    // references' packets, not their fragments.
    var preset10 = svtAv1Reference(arguments, ":preset=10");
    var libraryDefault = svtAv1Reference(arguments, "");
    var uploaded = FfprobePackets.of(uploads.contentOf(uploads.names()), root);
    assertThat(uploaded)
        .isEqualTo(FfprobePackets.of(preset10, root))
        .isNotEqualTo(FfprobePackets.of(libraryDefault, root));
  }

  private static VariantJob.Builder presetJob(Path source, String codecFamily) {
    var job = variantJobBuilder();
    withVideo(job, forcedSoftwareEncode(codecFamily, TINY_VARIANT_LEVELS.get(codecFamily)));
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

  @ParameterizedTest(name = "{0} source")
  @ValueSource(strings = {"mkv", "ts"})
  @DisplayName(
      "Should start the first segment at its first keyframe when a stream copy seeks to the"
          + " server's keyframe position in an irregular-keyframe source")
  void
      shouldStartTheFirstSegmentAtItsFirstKeyframeWhenAStreamCopySeeksToTheServersKeyframePositionInAnIrregularKeyframeSource(
          String container) throws Exception {
    var source = irregularKeyframeSource(container);
    var sourcePackets = videoPacketsOnZeroBasedTimeline(source);
    var keyframeIndex =
        firstKeyframeIndexOfSegment(sourcePackets, REPLACEMENT_START_SEQUENCE_NUMBER);
    var fromStart = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_REMUX);
    var replacement = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_REMUX);
    replacement
        .getExecutionBuilder()
        .setStartSequenceNumber(REPLACEMENT_START_SEQUENCE_NUMBER)
        .setSeekPositionSeconds(REPLACEMENT_START_SEQUENCE_NUMBER * 6);
    setSeekPosition(
        replacement.getExecutionBuilder(),
        probedDecodeTime(source, sourcePackets.get(keyframeIndex)));

    var fromStartUploads =
        execute(engine(realCapabilities()), source.getParent(), fromStart.build());
    var replacementUploads =
        execute(engine(realCapabilities()), source.getParent(), replacement.build());

    assertThat(replacementUploads.names()).containsExactly("init.mp4", "segment2.m4s");
    var replacementMedia = playable(replacementUploads.contentOf(replacementUploads.names()));
    assertDecodes(replacementMedia);
    // The attempt from the start copies every source packet in decode order, so its packet at the
    // keyframe's index is that keyframe on the output timeline.
    var fromStartPackets =
        videoPackets(playable(fromStartUploads.contentOf(fromStartUploads.names())));
    assertThat(fromStartPackets).hasSameSizeAs(sourcePackets);
    var copiedKeyframe = fromStartPackets.get(keyframeIndex);
    assertThat(copiedKeyframe.keyframe()).as("the copy of the server's keyframe").isTrue();
    assertThat(videoPackets(replacementMedia).getFirst().presentationTime())
        .as("the first video presentation time of the replacement attempt's segment 2")
        .isEqualByComparingTo(copiedKeyframe.presentationTime());
  }

  @ParameterizedTest(name = "{0} source, {1}, from segment {2}")
  @MethodSource("sourceContainersModesAndStarts")
  @DisplayName(
      "Should upload each media segment as one moof and one mdat of FFmpeg's own packets when a"
          + " worker runs a real job")
  void shouldUploadEachMediaSegmentAsOneMoofAndOneMdatOfFfmpegsOwnPacketsWhenAWorkerRunsARealJob(
      String container, TranscodeMode mode, int startSequenceNumber) throws Exception {
    var run = runSeekableSourceJob(container, mode, startSequenceNumber);

    var ffmpegBoxes = TopLevelBoxes.of(run.ffmpegOutput());
    assertThat(ffmpegBoxes).extracting(Box::type).startsWith("ftyp", "moov");
    assertThat(run.uploads().segments().get("init.mp4"))
        .as("the initialization segment")
        .isEqualTo(Arrays.copyOf(run.ffmpegOutput(), ffmpegBoxes.get(1).end()));
    assertThat(run.mediaSegmentNames())
        .isNotEmpty()
        .allSatisfy(
            name ->
                assertThat(TopLevelBoxes.of(run.uploads().segments().get(name)))
                    .extracting(Box::type)
                    .containsExactly("moof", "mdat"));
    var ffmpegPackets = FfprobePackets.of(run.ffmpegOutput(), root);
    var ffmpegPacketsByStreamAndDts =
        ffmpegPackets.stream()
            .collect(Collectors.toMap(WorkerMediaSmokeTest::streamAndDts, Function.identity()));
    var uploadedPackets = FfprobePackets.of(run.uploadedOutput(), root);
    assertThat(uploadedPackets)
        .isNotEmpty()
        .allSatisfy(
            packet ->
                assertThat(packet)
                    .isEqualTo(ffmpegPacketsByStreamAndDts.get(streamAndDts(packet))));
    var uploadedVideo = uploadedPackets.stream().filter(Packet::isVideo).toList();
    var ffmpegVideo = ffmpegPackets.stream().filter(Packet::isVideo).toList();
    assertThat(uploadedVideo.getFirst().isKeyframe()).isTrue();
    assertThat(uploadedVideo)
        .containsExactlyElementsOf(
            ffmpegVideo.subList(ffmpegVideo.indexOf(uploadedVideo.getFirst()), ffmpegVideo.size()));
  }

  @ParameterizedTest(name = "{0} source, {1}, from segment {2}")
  @MethodSource("sourceContainersModesAndStarts")
  @DisplayName(
      "Should upload each media segment with exactly FFmpeg's audio packets that start inside its"
          + " interval when a worker runs a real job")
  void
      shouldUploadEachMediaSegmentWithExactlyFfmpegsAudioPacketsThatStartInsideItsIntervalWhenAWorkerRunsARealJob(
          String container, TranscodeMode mode, int startSequenceNumber) throws Exception {
    var run = runSeekableSourceJob(container, mode, startSequenceNumber);

    var ffmpegAudio =
        FfprobePackets.of(run.ffmpegOutput(), root).stream().filter(Packet::isAudio).toList();
    var names = run.mediaSegmentNames();
    assertThat(names).isNotEmpty();
    for (var name : names) {
      var interval =
          AudioInterval.ofDeliveredSegment(
              numberOf(name), SEEKABLE_SOURCE_AUDIO_PERIOD_TICKS, name.equals(names.getLast()));
      var uploadedAudio =
          FfprobePackets.of(run.uploads().contentOf(List.of("init.mp4", name)), root).stream()
              .filter(Packet::isAudio)
              .toList();
      assertThat(uploadedAudio)
          .as("the audio packets of %s", name)
          .isNotEmpty()
          .containsExactlyElementsOf(
              ffmpegAudio.stream().filter(packet -> interval.holds(packet.pts())).toList());
    }
  }

  // At one frame per second, libx264's B-frames delay each video packet's decode time by two
  // seconds against the audio that FFmpeg writes beside it.
  @Test
  @DisplayName(
      "Should upload every audio packet FFmpeg writes when a stream copy of a one-frame-per-second"
          + " source reorders its video")
  void
      shouldUploadEveryAudioPacketFfmpegWritesWhenAStreamCopyOfAOneFramePerSecondSourceReordersItsVideo()
          throws Exception {
    var source = oneFramePerSecondSource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_REMUX);
    job.getExecutionBuilder().setMediaSegmentCount(ONE_FRAME_PER_SECOND_SEGMENT_COUNT);
    var launcher = new TeeingProcessLauncher(new ProcessBuilderLauncher());

    var uploads =
        execute(engine(softwareCapabilities(), launcher), source.getParent(), job.build());

    var ffmpegAudio =
        FfprobePackets.of(launcher.outputOf(fromProto(job.getJobAttemptId())), root).stream()
            .filter(Packet::isAudio)
            .toList();
    assertThat(FfprobePackets.of(uploads.contentOf(uploads.names()), root))
        .filteredOn(Packet::isAudio)
        .isNotEmpty()
        .containsExactlyElementsOf(ffmpegAudio);
  }

  @Test
  @DisplayName(
      "Should upload the start-0 attempt's video packets for every media segment when a"
          + " stream-copy replacement attempt that encodes its audio holds its first segment's"
          + " keyframe inside FFmpeg's first fragment")
  void
      shouldUploadTheStartZeroAttemptsVideoPacketsWhenAReplacementAttemptHoldsItsFirstSegmentsKeyframeInsideFfmpegsFirstFragment()
          throws Exception {
    var source = keyframeBeforeBoundarySource();
    var fromStart = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    var replacement = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    replacement.getExecutionBuilder().setStartSequenceNumber(REPLACEMENT_START_SEQUENCE_NUMBER);
    var launcher = new TeeingProcessLauncher(new ProcessBuilderLauncher());

    var fromStartUploads =
        execute(engine(softwareCapabilities(), launcher), source.getParent(), fromStart.build());
    var replacementUploads =
        execute(engine(softwareCapabilities(), launcher), source.getParent(), replacement.build());

    // The seek lands on the keyframe at 10.010 s while the encoded audio starts at 12 s, and FFmpeg
    // writes nothing until every track has a sample, so its first fragment holds the keyframe at
    // 12.012 s too.
    var output = Mp4Samples.read(launcher.outputOf(fromProto(replacement.getJobAttemptId())));
    var videoTrackId = output.videoTrack().trackId();
    assertThat(output.fragments().getFirst().samples())
        .filteredOn(sample -> sample.trackId() == videoTrackId && sample.isSync())
        .hasSize(2);
    assertThat(replacementUploads.names()).containsExactly("init.mp4", "segment2.m4s");
    assertThat(videoPacketsOf(replacementUploads, "segment2.m4s"))
        .isNotEmpty()
        .isEqualTo(videoPacketsOf(fromStartUploads, "segment2.m4s"));
  }

  @Test
  @DisplayName(
      "Should upload a media segment after the audio's end while FFmpeg's output continues when the"
          + " audio ends long before the video")
  void shouldUploadAMediaSegmentAfterTheAudiosEndWhileFfmpegsOutputContinues() throws Exception {
    var source = audioEndsLongBeforeVideoSource();
    var launcher = new TeeingProcessLauncher(new ProcessBuilderLauncher());
    var unwithheld = audioEndsLongBeforeVideoJob(source);
    var unwithheldUploads =
        execute(engine(softwareCapabilities(), launcher), source.getParent(), unwithheld.build());
    var output = launcher.outputOf(fromProto(unwithheld.getJobAttemptId()));
    // A stream copy writes the same output every run. Segment 5 ends at 36 s, and its audio at
    // 10 s, so the worker must upload it before it reads the video past 50 s. The output is
    // withheld for less than the stall timeout, so that a late upload fails this test, not the
    // attempt.
    var segmentFiveUploaded = new CompletableFuture<Void>();
    var releasedByTheUpload =
        launcher.withholdOutputAfter(
            endOfTheFragmentWhoseVideoReaches(output, 50),
            segmentFiveUploaded,
            FfmpegTranscodeEngine.DEFAULT_ENCODER_STALL_TIMEOUT.dividedBy(2));

    var uploads =
        execute(
            engine(softwareCapabilities(), launcher),
            source.getParent(),
            audioEndsLongBeforeVideoJob(source).build(),
            name -> {
              if (name.equals("segment5.m4s")) {
                segmentFiveUploaded.complete(null);
              }
            });

    assertThat(releasedByTheUpload).isCompletedWithValue(true);
    assertThat(uploads.names())
        .containsExactlyElementsOf(uploadNames(AUDIO_ENDS_LONG_BEFORE_VIDEO_SEGMENT_COUNT))
        .isEqualTo(unwithheldUploads.names());
    assertThat(uploads.contentOf(uploads.names()))
        .isEqualTo(unwithheldUploads.contentOf(unwithheldUploads.names()));
  }

  @Test
  @DisplayName(
      "Should upload every media segment without the audio encoder's last frames when an attempt"
          + " that encodes its audio reads audio that ends long before the video")
  void shouldUploadEveryMediaSegmentWithoutTheAudioEncodersLastFramesWhenTheAudioEndsLongBeforeIt()
      throws Exception {
    var source = audioEndsLongBeforeVideoSource();
    var launcher = new TeeingProcessLauncher(new ProcessBuilderLauncher());
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    job.getExecutionBuilder().setMediaSegmentCount(AUDIO_ENDS_LONG_BEFORE_VIDEO_SEGMENT_COUNT);

    var uploads =
        execute(engine(softwareCapabilities(), launcher), source.getParent(), job.build());

    assertThat(uploads.names())
        .containsExactlyElementsOf(uploadNames(AUDIO_ENDS_LONG_BEFORE_VIDEO_SEGMENT_COUNT));
    var ffmpegAudio =
        FfprobePackets.of(launcher.outputOf(fromProto(job.getJobAttemptId())), root).stream()
            .filter(Packet::isAudio)
            .toList();
    var uploadedAudio =
        FfprobePackets.of(uploads.contentOf(uploads.names()), root).stream()
            .filter(Packet::isAudio)
            .toList();
    // The copied video runs ahead of the audio still inside FFmpeg's decoder, filter and encoder,
    // and the muxer stops holding the video for it 10 s after the audio's last packet. The frames
    // that come out later, as many as thread scheduling leaves behind, arrive after their segment
    // was delivered, and the worker drops them: only the end of FFmpeg's audio can be missing.
    assertThat(uploadedAudio)
        .isNotEmpty()
        .containsExactlyElementsOf(ffmpegAudio.subList(0, uploadedAudio.size()));
  }

  static Stream<Arguments> sourceContainersModesAndStarts() {
    return sourceContainersAndModes()
        .flatMap(
            arguments ->
                Stream.of(0, REPLACEMENT_START_SEQUENCE_NUMBER)
                    .map(start -> Arguments.of(arguments.get()[0], arguments.get()[1], start)));
  }

  private SeekableSourceRun runSeekableSourceJob(
      String container, TranscodeMode mode, int startSequenceNumber) throws Exception {
    var source = seekableSource(container);
    var job = seekableSourceJob(source, mode);
    job.getExecutionBuilder().setStartSequenceNumber(startSequenceNumber);
    var launcher = new TeeingProcessLauncher(new ProcessBuilderLauncher());

    var uploads =
        execute(engine(softwareCapabilities(), launcher), source.getParent(), job.build());

    return new SeekableSourceRun(uploads, launcher.outputOf(fromProto(job.getJobAttemptId())));
  }

  private static List<Long> streamAndDts(Packet packet) {
    return List.of((long) packet.stream(), packet.dts());
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
    var job = variantJobBuilder(mode);
    withVideo(job, h264VideoFor(mode, SEEKABLE_SOURCE));
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    job.getDecisionBuilder()
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

  // H.264 at 4 fps with B-frames, whose keyframes fall at irregular times. Segment 2 holds two
  // keyframes, so that a seek past its first one shows, and segment 1 holds the keyframe before
  // them. libx264 reorders two frames, so a keyframe's decode time lies 0.5 s before its
  // presentation time. FFmpeg moves the -ss target of such a stream 3/23 s earlier, and an MPEG-TS
  // seek reads from the last packet whose decode time is at or before that target. A seek to
  // segment 2's boundary at 12 s therefore reads past the decode time of segment 2's first
  // keyframe, and the copy starts at the keyframe after it.
  private static synchronized Path irregularKeyframeSource(String container) throws Exception {
    var matroska = irregularKeyframeSources.resolve("irregular.mkv");
    if (!generatedSeekableSources.contains(matroska)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=4",
              "-f",
              "lavfi",
              "-i",
              "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5",
              "-t",
              String.valueOf(SEEKABLE_SOURCE_SECONDS),
              "-c:v",
              "libx264",
              "-g",
              "1000",
              "-sc_threshold",
              "0",
              "-force_key_frames",
              "0,2.5,5.25,7.25,9.5,11.5,12,13.25,15.5",
              "-c:a",
              "aac"),
          matroska);
      generatedSeekableSources.add(matroska);
    }

    var source = irregularKeyframeSources.resolve("irregular." + container);
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(List.of("-i", matroska.toString(), "-c", "copy"), source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  private static VariantJob.Builder audioEndsLongBeforeVideoJob(Path source) {
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_REMUX);
    job.getExecutionBuilder().setMediaSegmentCount(AUDIO_ENDS_LONG_BEFORE_VIDEO_SEGMENT_COUNT);
    return job;
  }

  // Where the first fragment whose video decode end reaches this media time ends in the output.
  private static int endOfTheFragmentWhoseVideoReaches(byte[] output, long seconds) {
    var movie = Mp4Samples.read(output);
    var video = movie.videoTrack();
    var mediaDataBoxes =
        TopLevelBoxes.of(output).stream().filter(box -> box.type().equals("mdat")).toList();
    for (var index = 0; index < movie.fragments().size(); index++) {
      var reaches =
          movie.fragments().get(index).samples().stream()
              .filter(sample -> sample.trackId() == video.trackId())
              .anyMatch(sample -> sample.end() >= seconds * video.timescale());
      if (reaches) {
        return mediaDataBoxes.get(index).end();
      }
    }

    throw new AssertionError("the video never reaches " + seconds + " s");
  }

  // H.264 at 23.976 fps with a keyframe every 2 s for 90 s, and a sine tone that ends at 10 s: 80 s
  // before the video, many times FFmpeg's muxer interleave bound.
  private static synchronized Path audioEndsLongBeforeVideoSource() throws Exception {
    var source = seekableSources.resolve("audio-ends-long-before-video.mp4");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24000/1001:duration=90",
              "-f",
              "lavfi",
              "-i",
              "sine=frequency=440:sample_rate=48000:duration=10",
              "-c:v",
              "libx264",
              "-g",
              "48",
              "-keyint_min",
              "48",
              "-c:a",
              "aac"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  private List<Packet> videoPacketsOf(MediaUploads uploads, String name) throws Exception {
    return FfprobePackets.of(uploads.contentOf(List.of("init.mp4", name)), root).stream()
        .filter(Packet::isVideo)
        .toList();
  }

  // H.264 at one frame per second with libx264's default B-frames and a keyframe every 6 s, and
  // pink noise for the whole 18 s.
  private static synchronized Path oneFramePerSecondSource() throws Exception {
    var source = seekableSources.resolve("one-frame-per-second.mp4");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=1:duration=18",
              "-f",
              "lavfi",
              "-i",
              "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5:duration=18",
              "-c:v",
              "libx264",
              "-g",
              "6",
              "-c:a",
              "aac"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // H.264 at 23.976 fps with a keyframe every 48 frames (2.002 s), so that a stream copy's seek
  // to a segment boundary lands on a keyframe before it, and pink noise.
  private static synchronized Path keyframeBeforeBoundarySource() throws Exception {
    var source = seekableSources.resolve("keyframe-before-boundary.mp4");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24000/1001",
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
              "-keyint_min",
              "48",
              "-c:a",
              "aac"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // The first keyframe whose presentation time lies in the segment's interval on the 6 s grid: the
  // keyframe whose decode time the server sends as a stream copy's seek position.
  private static int firstKeyframeIndexOfSegment(List<VideoPacket> packets, int sequenceNumber) {
    var segmentStart = BigDecimal.valueOf((long) sequenceNumber * 6);
    return IntStream.range(0, packets.size())
        .filter(index -> packets.get(index).keyframe())
        .filter(index -> packets.get(index).presentationTime().compareTo(segmentStart) >= 0)
        .findFirst()
        .orElseThrow();
  }

  // The decode time that the worker's keyframe probe reports. The probe reads a Matroska
  // keyframe's decode time from its cue time, which is the keyframe's presentation time.
  private static BigDecimal probedDecodeTime(Path source, VideoPacket keyframe) {
    if (source.toString().endsWith(".mkv")) {
      return keyframe.presentationTime();
    }

    return keyframe.decodeTime();
  }

  // The source's video packets in decode order, on the zero-based timeline the producer groups on.
  private static List<VideoPacket> videoPacketsOnZeroBasedTimeline(Path source) throws Exception {
    var startTime = new BigDecimal(ffprobe(List.of("-show_entries", "format=start_time"), source));
    return videoPackets(source).stream()
        .map(packet -> packet.onTimelineStartingAt(startTime))
        .toList();
  }

  private static List<VideoPacket> videoPackets(Path media) throws Exception {
    var lines =
        ffprobe(
            List.of("-select_streams", "v:0", "-show_entries", "packet=pts_time,dts_time,flags"),
            media);
    return lines.lines().map(VideoPacket::parse).toList();
  }

  private static String ffprobe(List<String> arguments, Path media) throws Exception {
    var command =
        Stream.of(
                List.of("ffprobe", "-v", "error"),
                arguments,
                List.of("-of", "csv=p=0", media.toString()))
            .flatMap(List::stream)
            .toList();
    var process =
        new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try {
      var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertThat(process.waitFor(1, TimeUnit.MINUTES)).as("ffprobe finished").isTrue();
      assertThat(process.exitValue()).as("ffprobe exit code").isZero();
      return output.strip();
    } finally {
      process.destroyForcibly();
    }
  }

  private static void setSeekPosition(TranscodeExecution.Builder execution, BigDecimal seconds) {
    var whole = seconds.setScale(0, RoundingMode.FLOOR);
    execution
        .getSeekPositionBuilder()
        .setSeconds(whole.longValueExact())
        .setNanos(seconds.subtract(whole).movePointRight(9).intValueExact());
  }

  // A video packet's times in seconds, as ffprobe prints them.
  private record VideoPacket(BigDecimal presentationTime, BigDecimal decodeTime, boolean keyframe) {

    private static VideoPacket parse(String line) {
      var fields = line.split(",", -1);
      var presentationTime = new BigDecimal(fields[0]);
      var decodeTime = "N/A".equals(fields[1]) ? presentationTime : new BigDecimal(fields[1]);
      return new VideoPacket(presentationTime, decodeTime, fields[2].startsWith("K"));
    }

    private VideoPacket onTimelineStartingAt(BigDecimal startTime) {
      return new VideoPacket(
          presentationTime.subtract(startTime), decodeTime.subtract(startTime), keyframe);
    }
  }

  private static void withVideo(VariantJob.Builder job, VideoDecision.Builder video) {
    job.getDecisionBuilder().setVideo(video);
  }

  // A copy of the source's H.264, or an H.264 encode at the level its variant needs, as the mode
  // says.
  private static VideoDecision.Builder h264VideoFor(TranscodeMode mode, H264Source source) {
    return switch (mode) {
      case TRANSCODE_MODE_VIDEO_TRANSCODE, TRANSCODE_MODE_FULL_TRANSCODE ->
          forcedSoftwareEncode("h264", source.variantLevel());
      default -> copiedVideo(source.codecString());
    };
  }

  private record H264Source(String codecString, int variantLevel) {}

  // An 8-bit encode of the codec family that forces the level, with the codec string that the
  // worker's encoder writes for it: High for H.264, and Main at the Main tier for H.265 and AV1.
  private static VideoDecision.Builder forcedSoftwareEncode(String codecFamily, int level) {
    var video = VideoDecision.newBuilder().setMode(VideoMode.VIDEO_MODE_ENCODE).setLevel(level);
    return switch (codecFamily) {
      case "h264" ->
          video
              .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_H264)
              .setCodecProfile(VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_HIGH)
              .setCodecString(String.format(Locale.ROOT, "avc1.6400%02X", level));
      case "hevc" ->
          video
              .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC)
              .setCodecProfile(VideoCodecProfile.VIDEO_CODEC_PROFILE_HEVC_MAIN)
              .setTier(VideoCodecTier.VIDEO_CODEC_TIER_MAIN)
              .setCodecString(String.format(Locale.ROOT, "hvc1.1.6.L%d.90", level * 3));
      case "av1" ->
          video
              .setCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_AV1)
              .setCodecProfile(VideoCodecProfile.VIDEO_CODEC_PROFILE_AV1_MAIN)
              .setTier(VideoCodecTier.VIDEO_CODEC_TIER_MAIN)
              .setCodecString(
                  String.format(Locale.ROOT, "av01.0.%02dM.08", av1SequenceLevelIndex(level)));
      default -> throw new AssertionError("No software encoder for " + codecFamily);
    };
  }

  // AV1 numbers levels 2.0 to 7.3 from 0, four minor levels to each major level.
  private static int av1SequenceLevelIndex(int level) {
    return (level / 10 - 2) * 4 + level % 10;
  }

  private MediaUploads execute(FfmpegTranscodeEngine engine, Path sourceRoot, VariantJob job)
      throws Exception {
    return execute(engine, sourceRoot, job, _ -> {});
  }

  // Calls uploadStarted with each upload's name once the control plane reads its metadata.
  private MediaUploads execute(
      FfmpegTranscodeEngine engine, Path sourceRoot, VariantJob job, Consumer<String> uploadStarted)
      throws Exception {
    try (var controlPlane = new MediaControlPlane(uploadStarted);
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

  private FailedExecution executeExpectingFailure(
      FfmpegTranscodeEngine engine, Path sourceRoot, VariantJob job) throws Exception {
    try (var controlPlane = new MediaControlPlane(_ -> {});
        var worker = workerBuilder(sourceRoot).engine(engine).build()) {
      worker.start("127.0.0.1", controlPlane.port());
      var registeredWorker = controlPlane.registration.get(5, TimeUnit.SECONDS).getWorker();
      controlPlane.responses.onNext(
          EstablishWorkerSessionResponse.newBuilder()
              .setStartVariant(
                  StartVariantCommand.newBuilder().setTarget(registeredWorker).setJob(job))
              .build());

      var failure = controlPlane.failed.get(JOB_LIMIT.toSeconds(), TimeUnit.SECONDS);
      return new FailedExecution(
          failure,
          new MediaUploads(List.copyOf(controlPlane.names), Map.copyOf(controlPlane.segments)));
    }
  }

  private record FailedExecution(JobAttemptFailed failure, MediaUploads uploads) {}

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
        .commandBuilder(new FfmpegCommandBuilder("ffmpeg", DEFAULT_FRAGMENTATION_TARGET))
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

  private static long numberOf(String mediaSegmentName) {
    return Long.parseLong(
        mediaSegmentName.substring("segment".length(), mediaSegmentName.indexOf(".")));
  }

  // A job's uploads and everything its FFmpeg wrote to standard output.
  private record SeekableSourceRun(MediaUploads uploads, byte[] ffmpegOutput) {

    private List<String> mediaSegmentNames() {
      return uploads.names().stream().filter(name -> name.startsWith("segment")).toList();
    }

    private byte[] uploadedOutput() {
      return uploads.contentOf(uploads.names());
    }
  }

  private static final class MediaControlPlane
      extends TranscodeWorkerServiceGrpc.TranscodeWorkerServiceImplBase implements AutoCloseable {

    private final CompletableFuture<WorkerRegistration> registration = new CompletableFuture<>();
    private final CompletableFuture<JobAttemptCompleted> completed = new CompletableFuture<>();
    private final CompletableFuture<JobAttemptFailed> failed = new CompletableFuture<>();
    private final Map<String, byte[]> segments = new ConcurrentHashMap<>();
    private final List<String> names = new CopyOnWriteArrayList<>();
    private final Server server;
    private final Consumer<String> uploadStarted;
    private StreamObserver<EstablishWorkerSessionResponse> responses;

    private MediaControlPlane(Consumer<String> uploadStarted) throws Exception {
      this.uploadStarted = uploadStarted;
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
            failed.complete(message.getJobAttemptFailed());
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
            uploadStarted.accept(name);
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
