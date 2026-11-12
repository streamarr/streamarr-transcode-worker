package com.streamarr.transcode.worker;

import static com.streamarr.transcode.protocol.ProtoUuid.fromProto;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.copiedVideo;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.sdrDynamicRange;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.variantJobBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import build.buf.gen.streamarr.transcode.v1.AudioChannelLayout;
import build.buf.gen.streamarr.transcode.v1.AudioMode;
import build.buf.gen.streamarr.transcode.v1.CompatibleBase;
import build.buf.gen.streamarr.transcode.v1.Deinterlace;
import build.buf.gen.streamarr.transcode.v1.DeinterlaceOutput;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionAbsent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionConfiguration;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionPresent;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionRpuPlacement;
import build.buf.gen.streamarr.transcode.v1.EncoderPreset;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionRequest;
import build.buf.gen.streamarr.transcode.v1.EstablishWorkerSessionResponse;
import build.buf.gen.streamarr.transcode.v1.FieldOrder;
import build.buf.gen.streamarr.transcode.v1.Fraction;
import build.buf.gen.streamarr.transcode.v1.JobAttemptCompleted;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailed;
import build.buf.gen.streamarr.transcode.v1.JobAttemptFailure;
import build.buf.gen.streamarr.transcode.v1.JobAttemptProgress;
import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import build.buf.gen.streamarr.transcode.v1.KeyframePositionSource;
import build.buf.gen.streamarr.transcode.v1.KeyframePositions;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailable;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailableReason;
import build.buf.gen.streamarr.transcode.v1.MasteringDisplayColorVolume;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.SourceEndedEarly;
import build.buf.gen.streamarr.transcode.v1.SourceStreamEnd;
import build.buf.gen.streamarr.transcode.v1.StartVariantCommand;
import build.buf.gen.streamarr.transcode.v1.SubtitleMode;
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
import build.buf.gen.streamarr.transcode.v1.VideoDynamicRange;
import build.buf.gen.streamarr.transcode.v1.VideoMode;
import build.buf.gen.streamarr.transcode.v1.WorkerRegistration;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.engine.AnnexTwoMeter;
import com.streamarr.transcode.engine.FfmpegCommandBuilder;
import com.streamarr.transcode.engine.FfmpegTranscodeEngine;
import com.streamarr.transcode.engine.ProcessBuilderLauncher;
import com.streamarr.transcode.engine.ProcessLauncher;
import com.streamarr.transcode.engine.Speaker;
import com.streamarr.transcode.engine.SpeakerLayout;
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
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.ThrowingSupplier;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
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
  // The long-GOP MPEG-TS source's audio runs to 40 s, inside segment 6 of 6 s segments.
  private static final int LONG_FINAL_GOP_SEGMENT_COUNT = 7;
  private static final int SEEKABLE_SOURCE_SEGMENT_COUNT = 3;
  private static final int SLOT_SECONDS = 3;
  // The 3 s slots where the segments of the source with keyframes 10 s apart start.
  private static final List<Integer> TEN_SECOND_KEYFRAME_SEGMENT_STARTS = List.of(0, 3, 6, 10);
  private static final int REPLACEMENT_START_SEQUENCE_NUMBER = 2;
  // The DVD PCM source's 12 s of video and counted audio, in four 3 s segments.
  private static final int DVD_PCM_SOURCE_SEGMENT_COUNT = 4;
  private static final int DVD_PCM_SAMPLE_RATE = 48_000;
  private static final int DVD_PCM_SOURCE_SAMPLES =
      DVD_PCM_SOURCE_SEGMENT_COUNT * SLOT_SECONDS * DVD_PCM_SAMPLE_RATE;
  // A FLAC encode codes the sample rate divided by 20 a frame (ADR 0043).
  private static final int FLAC_FRAME_SAMPLES_AT_48_KHZ = 2_400;
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

  // ffprobe prints each time rounded to the microsecond, so the difference of two printed times
  // can be one microsecond off. A picture that took another's time moves a whole frame.
  private static final Comparator<BigDecimal> WITHIN_PRINTED_ROUNDING =
      (left, right) -> {
        if (left.subtract(right).abs().compareTo(new BigDecimal("0.000001")) <= 0) {
          return 0;
        }

        return left.compareTo(right);
      };

  private static final String DOLBY_VISION_FIXTURE = "dolby-vision-8.2-dvh1.mp4";
  // The fixture's 10-bit base layer: Main 10 at level 2.0 in the Main tier.
  private static final String DOLBY_VISION_BASE_LAYER_CODEC_STRING = "hvc1.2.4.L60.90";

  // The profile 5 sources span three 2 s segments, with a keyframe every second.
  private static final int PROFILE_5_PERIOD_SECONDS = 2;
  private static final int PROFILE_5_SEGMENT_COUNT = 3;
  private static final int LARGE_PICTURE_SOURCE_SECONDS = 24;
  private static final int LARGE_PICTURE_REPLACEMENT_START_SEQUENCE_NUMBER = 7;
  private static final int DISPLAY_SOURCE_HEIGHT = 180;
  private static final int ACCESS_UNIT_DELIMITER = 35;
  // An HEVC NAL unit of type 62, as a Dolby Vision RPU, whose payload FFmpeg cannot parse as one.
  private static final byte[] STAND_IN_RPU =
      HexFormat.of().parseHex("000000017C01" + "19".repeat(24) + "80");

  // 18 s of video at the default 6 s period.
  private static final int ONE_FRAME_PER_SECOND_SEGMENT_COUNT = 3;

  // 90 s of video at the default 6 s period.
  private static final int AUDIO_ENDS_LONG_BEFORE_VIDEO_SEGMENT_COUNT = 15;

  // Segment 5 starts at 30 s, where an attempt that copies the video seeks: 20 s after the audio's
  // end.
  private static final int AFTER_THE_AUDIOS_END_SEQUENCE_NUMBER = 5;

  // 84 s of audio in 6 s segments; the 64 s of video end inside segment 10.
  private static final int VIDEO_ENDS_BEFORE_AUDIO_SEGMENT_COUNT = 14;
  private static final int VIDEO_ENDS_BEFORE_AUDIO_LAST_VIDEO_SEGMENT = 10;
  // The video stream, 0, ends at 64 s and the audio stream, 1, at 84 s.
  private static final int VIDEO_ENDS_BEFORE_AUDIO_AUDIO_INDEX = 1;
  private static final List<SourceStreamEnd> VIDEO_ENDS_BEFORE_AUDIO_STREAM_ENDS =
      List.of(streamEnd(0, Duration.ofSeconds(64)), streamEnd(1, Duration.ofSeconds(84)));
  // The 90 s that the truncated source's index promises, in 6 s segments.
  private static final int TRUNCATED_SOURCE_INDEXED_SEGMENT_COUNT = 15;
  private static final Duration ONE_FRAME_AT_24_FPS = Duration.ofNanos(1_000_000_000L / 24 + 1);
  // 1,764 s of audio in 6 s segments after the same 64 s of video.
  private static final int LONG_AUDIO_AFTER_VIDEO_SEGMENT_COUNT = 294;
  private static final Duration HALF_OF_THE_LONG_AUDIO = Duration.ofSeconds(882);
  // The seekable sources' 6 s period at their 24 fps.
  private static final int FRAMES_IN_A_PERIOD = 144;
  // The longest frame among the audio codecs these tests encode: AC-3 and E-AC-3 code 1,536
  // samples a frame, AAC 1,024.
  private static final long LONGEST_AUDIO_FRAME_TICKS = 1536;

  // 10 s of 30 fps video and audio, advertised as three 6 s segments that end at 15.01 s.
  private static final int THIRTY_FPS_SOURCE_SEGMENT_COUNT = 3;
  private static final Duration INSIDE_THE_LAST_SEGMENT = Duration.ofMillis(15_010);

  // 18 s of video and 10 s of FLAC audio in three 6 s segments.
  private static final int FLAC_SOURCE_SEGMENT_COUNT = 3;
  private static final int FLAC_SOURCE_SAMPLE_RATE = 48_000;
  // FFmpeg's FLAC encoder codes 4,608 samples a frame at 48 kHz, less than 0.1 s.
  private static final int FLAC_SAMPLES_PAST_THE_END_AT_MOST = 4_800;

  // The fragmentation target that a worker passes FFmpeg unless its settings name another.
  private static final Duration DEFAULT_FRAGMENTATION_TARGET = Duration.ofMillis(250);

  private static final Duration JOB_LIMIT = Duration.ofMinutes(5);
  // How long the server waits for an attempt's progress before it replaces the attempt's producer.
  private static final Duration SERVER_STALL_THRESHOLD = Duration.ofSeconds(10);
  // About three times the 256 kb/s of the audio that makes up nearly all of a source's bytes.
  private static final int THRICE_THE_SOURCES_BYTE_RATE = 96 * 1024;

  // The interlaced sources hold three 3 s segments of 29.97 fps frames, each weaving two fields.
  // An encoded replacement attempt for segment 2 seeks to segment 1, past the source's start.
  private static final int INTERLACED_SOURCE_SEGMENT_COUNT = 3;
  private static final int INTERLACED_SOURCE_FRAMES = 270;
  private static final int INTERLACED_REPLACEMENT_START = 2;
  // Each woven frame of an interlaced source shown twice, as an encode that does not deinterlace
  // writes it at the field rate. With the pinned FFmpeg, deinterlaced frames scored about 3 dB
  // higher against their own pictures than against these frames; frames left woven, or
  // deinterlaced with the other field first, scored about 1 dB lower.
  private static final String WOVEN_FRAMES_AT_THE_FIELD_RATE = "fps=60000/1001,";
  private static final Pattern IDET_MULTI_FRAME =
      Pattern.compile("Multi frame detection: TFF: *(\\d+) BFF: *(\\d+) Progressive: *(\\d+)");
  private static final Pattern PSNR_AVERAGE = Pattern.compile("average:([0-9.]+)");
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
  @TempDir static Path rpuLayoutSources;
  @TempDir static Path largePictureSources;
  @TempDir static Path interlacedSources;

  // The seven audio dispositions of probe version 7, by the names that ffprobe prints.
  private static final Map<String, Predicate<ProbeStreamInfo>> AUDIO_DISPOSITIONS =
      Map.of(
          "comment", ProbeStreamInfo::getIsComment,
          "visual_impaired", ProbeStreamInfo::getIsVisualImpaired,
          "descriptions", ProbeStreamInfo::getIsDescriptions,
          "original", ProbeStreamInfo::getIsOriginal,
          "dub", ProbeStreamInfo::getIsDub,
          "clean_effects", ProbeStreamInfo::getIsCleanEffects,
          "dependent", ProbeStreamInfo::getIsDependent);

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

  @Test
  @DisplayName(
      "Should report the video's codec string when a Matroska file holds an attachment too large"
          + " to print")
  void shouldReportTheVideosCodecStringWhenAMatroskaFileHoldsAnAttachmentTooLargeToPrint()
      throws Exception {
    // ffprobe's -show_data prints 16 bytes as a 68-character line, so the 32 MiB attachment
    // would take about 142.6 million characters: more than the 100 million that Jackson reads
    // as one string.
    var font = root.resolve("font.ttf");
    Files.write(font, new byte[32 * 1024 * 1024]);
    var source = root.resolve("attachment.mkv");
    runFfmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x36:rate=24:duration=1",
            "-c:v",
            "libx264",
            "-profile:v",
            "baseline",
            "-level",
            "1.0",
            "-pix_fmt",
            "yuv420p",
            "-attach",
            font.toString(),
            "-metadata:s:t",
            "mimetype=font/ttf"),
        source);

    var result = probeExecutor().probe(source, keyframeRequest()).result();

    // Constrained Baseline at level 1.0.
    assertThat(result.getMedia().getStreams(0).getCodecString()).isEqualTo("avc1.42C00A");
  }

  @Test
  @DisplayName(
      "Should report each audio stream's layout, depth, dispositions and FLAC block size when a"
          + " worker probes a real Matroska file")
  void
      shouldReportEachAudioStreamsLayoutDepthDispositionsAndFlacBlockSizeWhenAWorkerProbesARealFile()
          throws Exception {
    var subtitle = root.resolve("commentary.srt");
    Files.writeString(subtitle, "1\n00:00:00,000 --> 00:00:01,000\nHello\n");
    var source = root.resolve("audio-facts.mkv");
    // 24-bit quad FLAC in 144-sample blocks, marked original; quad PCM, whose layout Matroska does
    // not store; mono AC-3 whose bitstream mode is commentary, which replaces its default
    // disposition; and a subtitle stream marked as commentary.
    runFfmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x36:rate=24:duration=1",
            "-f",
            "lavfi",
            "-i",
            "sine=frequency=440:duration=1",
            "-i",
            subtitle.toString(),
            "-filter_complex",
            "[1]asplit=3[a][b][mono];[a]pan=quad|FL=c0|FR=c0|BL=c0|BR=c0[flac];"
                + "[b]pan=quad|FL=c0|FR=c0|BL=c0|BR=c0[pcm]",
            "-map",
            "0:v",
            "-map",
            "[flac]",
            "-map",
            "[pcm]",
            "-map",
            "[mono]",
            "-map",
            "2:s",
            "-c:v",
            "libx264",
            "-preset",
            "ultrafast",
            "-c:a:0",
            "flac",
            "-sample_fmt:a:0",
            "s32",
            "-frame_size:a:0",
            "144",
            "-c:a:1",
            "pcm_s16le",
            "-c:a:2",
            "ac3",
            "-audio_service_type:a:2",
            "co",
            "-disposition:a:0",
            "original",
            "-disposition:a:2",
            "default",
            "-disposition:s:0",
            "comment",
            "-c:s",
            "srt"),
        source);

    var streams = probeExecutor().probe(source, keyframeRequest()).result().getMedia();

    var flac = streams.getStreams(1);
    assertThat(flac.getChannelLayout()).isEqualTo("quad");
    assertThat(flac.getSampleFormat()).isEqualTo("s32");
    assertThat(flac.getBitDepth()).isEqualTo(24);
    assertThat(flac.getFlacMinimumBlockSizeSamples()).isEqualTo(144);
    assertThat(flac.getIsOriginal()).isTrue();
    var pcm = streams.getStreams(2);
    assertThat(pcm.hasChannelLayout()).isFalse();
    assertThat(pcm.getChannels()).isEqualTo(4);
    assertThat(pcm.getBitDepth()).isEqualTo(16);
    assertThat(pcm.hasFlacMinimumBlockSizeSamples()).isFalse();
    var commentary = streams.getStreams(3);
    assertThat(commentary.getChannelLayout()).isEqualTo("mono");
    assertThat(commentary.hasBitDepth()).isFalse();
    assertThat(commentary.getIsComment()).isTrue();
    assertThat(commentary.getIsDefault()).isFalse();
    assertThat(streams.getStreams(4).getIsComment()).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("audioDepthCases")
  @DisplayName(
      "Should report the sample format and the bit depth that ffprobe prints when a worker probes"
          + " a real audio stream")
  void shouldReportSampleFormatAndBitDepthThatFfprobePrintsWhenAWorkerProbesARealAudioStream(
      AudioDepthCase audioCase) throws Exception {
    var arguments = new ArrayList<>(List.of("-map", "1:a"));
    arguments.addAll(audioCase.audioArguments());

    var audio = probeAudioSource("depth." + audioCase.container(), arguments).getStreams(1);

    assertThat(audio.getSampleFormat()).isEqualTo(audioCase.sampleFormat());
    assertThat(
            Optional.of(audio)
                .filter(ProbeStreamInfo::hasBitDepth)
                .map(ProbeStreamInfo::getBitDepth))
        .isEqualTo(Optional.ofNullable(audioCase.bitDepth()));
  }

  // FLAC, ALAC, TrueHD and PCM report a depth. ffprobe prints no depth for AC-3.
  static Stream<AudioDepthCase> audioDepthCases() {
    return Stream.of(
        AudioDepthCase.builder()
            .audioArguments(List.of("-c:a", "flac", "-sample_fmt", "s16"))
            .container("mkv")
            .sampleFormat("s16")
            .bitDepth(16)
            .build(),
        AudioDepthCase.builder()
            .audioArguments(List.of("-c:a", "pcm_s24le"))
            .container("mkv")
            .sampleFormat("s32")
            .bitDepth(24)
            .build(),
        AudioDepthCase.builder()
            .audioArguments(List.of("-c:a", "alac", "-sample_fmt", "s16p"))
            .container("mp4")
            .sampleFormat("s16p")
            .bitDepth(16)
            .build(),
        AudioDepthCase.builder()
            .audioArguments(List.of("-c:a", "alac", "-sample_fmt", "s32p"))
            .container("mp4")
            .sampleFormat("s32p")
            .bitDepth(24)
            .build(),
        // FFmpeg's TrueHD encoder runs only when experimental encoders are allowed.
        AudioDepthCase.builder()
            .audioArguments(List.of("-c:a", "truehd", "-strict", "experimental"))
            .container("mkv")
            .sampleFormat("s32")
            .bitDepth(24)
            .build(),
        AudioDepthCase.builder()
            .audioArguments(List.of("-c:a", "ac3"))
            .container("mkv")
            .sampleFormat("fltp")
            .build());
  }

  // An absent bit depth means that the probe reports none.
  @Builder
  private record AudioDepthCase(
      List<String> audioArguments, String container, String sampleFormat, Integer bitDepth) {

    @Override
    public String toString() {
      return String.join(" ", audioArguments) + " in " + container;
    }
  }

  @ParameterizedTest(name = "{1}-sample blocks in {0}")
  @CsvSource({"mkv, 144", "mkv, 4608", "mp4, 144", "mp4, 4608"})
  @DisplayName(
      "Should report the STREAMINFO minimum block size of a FLAC stream and none for an ALAC"
          + " stream when a worker probes a real file")
  void shouldReportFlacMinimumBlockSizeAndNoneForAlacWhenAWorkerProbesARealFile(
      String container, int frameSize) throws Exception {
    var media =
        probeAudioSource(
            "block-size." + container,
            List.of(
                "-map",
                "1:a",
                "-map",
                "1:a",
                "-c:a:0",
                "flac",
                "-frame_size:a:0",
                Integer.toString(frameSize),
                "-c:a:1",
                "alac"));

    assertThat(media.getStreams(1).getFlacMinimumBlockSizeSamples()).isEqualTo(frameSize);
    var alac = media.getStreams(2);
    assertThat(alac.getCodec()).isEqualTo("alac");
    assertThat(alac.hasFlacMinimumBlockSizeSamples()).isFalse();
  }

  // Each row sets one disposition on an AAC stream and expects the flags that ffprobe reads back.
  // Matroska stores the comment, visual impaired and original flags, and a dub as original 0 (RFC
  // 9559). FFmpeg writes MP4 dispositions as DASH roles, and one role stands for both
  // visual_impaired and descriptions. MPEG-TS stores one ISO 639 audio type: clean effects,
  // hearing impaired or visual impaired commentary.
  @ParameterizedTest(name = "{1} in {0}")
  @CsvSource(
      textBlock =
          """
          mkv, comment,         comment
          mkv, visual_impaired, visual_impaired
          mkv, descriptions,
          mkv, original,        original
          mkv, dub,             dub
          mkv, clean_effects,
          mkv, dependent,
          mp4, comment,         comment
          mp4, visual_impaired, descriptions visual_impaired
          mp4, descriptions,    descriptions visual_impaired
          mp4, original,
          mp4, dub,             dub
          mp4, clean_effects,
          mp4, dependent,
          ts,  comment,
          ts,  visual_impaired, descriptions visual_impaired
          ts,  descriptions,
          ts,  original,
          ts,  dub,
          ts,  clean_effects,   clean_effects
          ts,  dependent,
          """)
  @DisplayName(
      "Should report the audio dispositions that survive the container when a worker probes a real"
          + " file")
  void shouldReportAudioDispositionsThatSurviveTheContainerWhenAWorkerProbesARealFile(
      String container, String disposition, String surviving) throws Exception {
    var media =
        probeAudioSource(
            "disposition." + container,
            List.of("-map", "1:a", "-c:a", "aac", "-disposition:a", disposition));

    assertThat(audioDispositionsOf(media.getStreams(1))).isEqualTo(flagsNamed(surviving));
  }

  // Both AC-3 streams carry the comment and visual impaired container flags. The first stream's
  // bitstream mode is complete main, so ffprobe keeps the flags that the container stores. The
  // second stream's bitstream mode is music and effects. ffprobe replaces every container flag of
  // that stream with clean effects, a flag that Matroska and MP4 cannot store.
  @ParameterizedTest(name = "{0}")
  @CsvSource(
      textBlock =
          """
          mkv, comment visual_impaired
          mp4, comment descriptions visual_impaired
          ts,  descriptions visual_impaired
          """)
  @DisplayName(
      "Should report the bitstream mode's flag instead of the container flags when a worker probes"
          + " a real AC-3 stream whose mode is not complete main")
  void shouldReportBitstreamModeFlagInsteadOfContainerFlagsWhenAc3ModeIsNotCompleteMain(
      String container, String containerFlags) throws Exception {
    var media =
        probeAudioSource(
            "bitstream-mode." + container,
            List.of(
                "-map",
                "1:a",
                "-map",
                "1:a",
                "-c:a",
                "ac3",
                "-audio_service_type:a:0",
                "ma",
                "-audio_service_type:a:1",
                "ef",
                "-disposition:a",
                "comment+visual_impaired"));

    assertThat(audioDispositionsOf(media.getStreams(1))).isEqualTo(flagsNamed(containerFlags));
    assertThat(audioDispositionsOf(media.getStreams(2))).containsExactly("clean_effects");
  }

  // Probes a one-second file whose stream 0 is a 64x36 H.264 picture track, because the probe
  // fails a file without video. Input 1 is a 48 kHz sine tone for the audio arguments to map.
  private ProbeMediaInfo probeAudioSource(String fileName, List<String> audioArguments)
      throws Exception {
    var source = root.resolve(fileName);
    var arguments =
        new ArrayList<>(
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=64x36:rate=24:duration=1",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:sample_rate=48000:duration=1",
                "-map",
                "0:v",
                "-c:v",
                "libx264",
                "-preset",
                "ultrafast"));
    arguments.addAll(audioArguments);
    runFfmpeg(arguments, source);
    return probeExecutor().probe(source, keyframeRequest()).result().getMedia();
  }

  private static Set<String> audioDispositionsOf(ProbeStreamInfo stream) {
    return AUDIO_DISPOSITIONS.entrySet().stream()
        .filter(disposition -> disposition.getValue().test(stream))
        .map(Map.Entry::getKey)
        .collect(Collectors.toSet());
  }

  // A blank CSV cell names no flag.
  private static Set<String> flagsNamed(String names) {
    return Optional.ofNullable(names).map(list -> Set.of(list.split(" "))).orElse(Set.of());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("mp4SourceFactCases")
  @DisplayName(
      "Should report the sample entry and the major brand that an MP4 or MOV file stores when a"
          + " worker probes a real file")
  void shouldReportTheSampleEntryAndMajorBrandThatAnMp4OrMovFileStoresWhenAWorkerProbesARealFile(
      Mp4SourceFactCase sourceCase) throws Exception {
    var source = root.resolve(sourceCase.fileName());
    runFfmpeg(videoSourceArguments(sourceCase.codec(), sourceCase.muxerArguments()), source);

    var media = probeExecutor().probe(source, keyframeRequest()).result().getMedia();

    assertThat(media.getStreams(0).getSampleEntry()).isEqualTo(sourceCase.sampleEntry());
    assertThat(media.getContainer().getMajorBrand()).isEqualTo(sourceCase.majorBrand());
  }

  // FFmpeg's mp4 muxer writes hev1 for HEVC unless the tag option names hvc1. Its mov muxer writes
  // the QuickTime brand, whose last two characters are spaces.
  static Stream<Mp4SourceFactCase> mp4SourceFactCases() {
    return Stream.of(
        Mp4SourceFactCase.builder()
            .fileName("source-facts-h264.mp4")
            .codec("libx264")
            .muxerArguments(List.of())
            .sampleEntry("avc1")
            .majorBrand("isom")
            .build(),
        Mp4SourceFactCase.builder()
            .fileName("source-facts-hevc.mp4")
            .codec("libx265")
            .muxerArguments(List.of())
            .sampleEntry("hev1")
            .majorBrand("isom")
            .build(),
        Mp4SourceFactCase.builder()
            .fileName("source-facts-hvc1.mp4")
            .codec("libx265")
            .muxerArguments(List.of("-tag:v", "hvc1"))
            .sampleEntry("hvc1")
            .majorBrand("isom")
            .build(),
        Mp4SourceFactCase.builder()
            .fileName("source-facts-hvc1.mov")
            .codec("libx265")
            .muxerArguments(List.of("-tag:v", "hvc1"))
            .sampleEntry("hvc1")
            .majorBrand("qt  ")
            .build(),
        Mp4SourceFactCase.builder()
            .fileName("source-facts-mp42.mp4")
            .codec("libx264")
            .muxerArguments(List.of("-brand", "mp42"))
            .sampleEntry("avc1")
            .majorBrand("mp42")
            .build());
  }

  @Builder
  private record Mp4SourceFactCase(
      String fileName,
      String codec,
      List<String> muxerArguments,
      String sampleEntry,
      String majorBrand) {

    @Override
    public String toString() {
      return String.join(" ", muxerArguments) + " " + codec + " in " + fileName;
    }
  }

  @Test
  @DisplayName(
      "Should report the ftyp box's major brand when a real MP4 file's metadata names the source's")
  void shouldReportTheFtypBoxsMajorBrandWhenARealMp4FilesMetadataNamesTheSources()
      throws Exception {
    var quickTime = root.resolve("metadata-brand-source.mov");
    runFfmpeg(videoSourceArguments("libx264", List.of()), quickTime);
    // The mp4 muxer writes the isom brand, and use_metadata_tags keeps the QuickTime source's
    // major_brand as metadata, which ffprobe then prints as the file's major_brand.
    var source = root.resolve("metadata-brand.mp4");
    runFfmpeg(
        List.of("-i", quickTime.toString(), "-c", "copy", "-movflags", "use_metadata_tags"),
        source);

    var media = probeExecutor().probe(source, keyframeRequest()).result().getMedia();

    assertThat(media.getContainer().getMajorBrand()).isEqualTo("isom");
  }

  @Test
  @DisplayName(
      "Should report the brand that ffprobe prints when a real MP4 file holds a second ftyp box"
          + " before its movie box")
  void shouldReportTheBrandThatFfprobePrintsWhenARealMp4FileHoldsASecondFtypBoxBeforeItsMovieBox()
      throws Exception {
    var written = root.resolve("two-brands-written.mp4");
    runFfmpeg(videoSourceArguments("libx264", List.of()), written);
    // FFmpeg's mp4 muxer writes ftyp(isom), free, mdat, moov. A second ftyp box between the mdat
    // and the moov moves no sample, and FFmpeg reads it before the movie box adds streams.
    var source = root.resolve("two-brands.mp4");
    Files.write(source, withBoxBeforeMovieBox(Files.readAllBytes(written), quickTimeFileType()));

    var media = probeExecutor().probe(source, keyframeRequest()).result().getMedia();

    assertThat(media.getContainer().getMajorBrand()).isEqualTo("qt  ");
    assertThat(formatTag(source, "major_brand")).isEqualTo("qt  ");
  }

  private static byte[] quickTimeFileType() {
    var brand = "qt  ".getBytes(StandardCharsets.US_ASCII);
    return ByteBuffer.allocate(20)
        .putInt(20)
        .put("ftyp".getBytes(StandardCharsets.US_ASCII))
        .put(brand)
        .putInt(0)
        .put(brand)
        .array();
  }

  // Inserts the box in front of the file's top-level moov box.
  private static byte[] withBoxBeforeMovieBox(byte[] mp4, byte[] box) {
    var buffer = ByteBuffer.wrap(mp4);
    var position = 0;
    while (true) {
      var size = buffer.getInt(position);
      var type = new String(mp4, position + 4, 4, StandardCharsets.US_ASCII);
      if ("moov".equals(type)) {
        break;
      }

      position += size;
    }

    var result = new byte[mp4.length + box.length];
    System.arraycopy(mp4, 0, result, 0, position);
    System.arraycopy(box, 0, result, position, box.length);
    System.arraycopy(mp4, position, result, position + box.length, mp4.length - position);
    return result;
  }

  private static String formatTag(Path media, String tag) throws Exception {
    return runToCompletion(
            List.of(
                "ffprobe",
                "-v",
                "error",
                "-show_entries",
                "format_tags=" + tag,
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                media.toString()),
            media)
        .replaceFirst("\\R\\z", "");
  }

  // FFmpeg cannot write the Dolby Vision sample entries without a Dolby Vision stream, so each
  // case renames the sample entry that FFmpeg wrote.
  @ParameterizedTest(name = "{1} renamed {2}")
  @CsvSource({"libx265, hev1, dvh1, hvc1.", "libx264, avc1, dvav, avc1."})
  @DisplayName(
      "Should report the Dolby Vision sample entry apart from the copy's codec string when an MP4"
          + " file stores one")
  void shouldReportTheDolbyVisionSampleEntryApartFromTheCopysCodecStringWhenAnMp4FileStoresOne(
      String codec, String written, String renamed, String codecStringPrefix) throws Exception {
    var writtenSource = root.resolve("written-" + written + ".mp4");
    runFfmpeg(videoSourceArguments(codec, List.of()), writtenSource);
    var source =
        withSampleEntry(
            Files.readAllBytes(writtenSource), written, renamed, renamed + "-source.mp4");

    var video = probeExecutor().probe(source, keyframeRequest()).result().getMedia().getStreams(0);

    assertThat(video.getSampleEntry()).isEqualTo(renamed);
    assertThat(video.getCodecString()).startsWith(codecStringPrefix);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"mkv", "ts"})
  @DisplayName(
      "Should report no sample entry and no major brand when a real file of another container"
          + " holds video copied from an MP4 file")
  void shouldReportNoSampleEntryAndNoMajorBrandWhenAnotherContainerHoldsVideoCopiedFromAnMp4File(
      String container) throws Exception {
    var mp4 = root.resolve("remuxed-from.mp4");
    runFfmpeg(videoSourceArguments("libx265", List.of("-tag:v", "hvc1")), mp4);
    // A copy keeps the MP4 file's major_brand as an ordinary tag of the new container.
    var source = root.resolve("remuxed." + container);
    runFfmpeg(List.of("-i", mp4.toString(), "-c", "copy"), source);

    var media = probeExecutor().probe(source, keyframeRequest()).result().getMedia();

    assertThat(media.getStreams(0).hasSampleEntry()).isFalse();
    assertThat(media.getContainer().hasMajorBrand()).isFalse();
  }

  // A one-second 64x36 picture track, encoded with the given codec and written with the given
  // muxer arguments.
  private static List<String> videoSourceArguments(String codec, List<String> muxerArguments) {
    var arguments =
        new ArrayList<>(
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=64x36:rate=24:duration=1",
                "-c:v",
                codec,
                "-preset",
                "ultrafast",
                "-pix_fmt",
                "yuv420p"));
    if ("libx265".equals(codec)) {
      arguments.addAll(List.of("-x265-params", "log-level=error"));
    }

    arguments.addAll(muxerArguments);
    return arguments;
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
    // The writer serves each ffprobe read. When the packet scan opens the FIFO, the writer holds it
    // open and writes nothing, as a stalled read from network storage would block the scan.
    var media = Files.readAllBytes(Path.of(fixture.toURI()));
    var scanReady = new CompletableFuture<ProcessHandle>();
    var writer =
        Thread.ofVirtual().start(() -> serveUntilThePacketScanOpens(source, media, scanReady));
    var result = new CompletableFuture<ProbeAttemptResult>();
    var probe =
        Thread.ofVirtual()
            .start(
                () -> result.complete(probeExecutor().probe(source, packetScanRequest()).result()));

    try {
      var scan = scanReady.get(30, TimeUnit.SECONDS);
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

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"TRANSCODE_MODE_REMUX", "TRANSCODE_MODE_FULL_TRANSCODE"})
  @DisplayName("Should upload the chosen video stream when cover art and audio precede it")
  void shouldUploadTheChosenVideoStreamWhenCoverArtAndAudioPrecedeIt(TranscodeMode mode)
      throws Exception {
    var source = sourceWithCoverArtBeforeVideo();
    var probe = probeExecutor().probe(source, requestBuilder().build()).result().getMedia();
    assertThat(probe.getChosenVideoStreamIndex()).isEqualTo(2);
    assertThat(probe.getStreamsList())
        .extracting(ProbeStreamInfo::getCodec)
        .containsExactly("png", "aac", "h264");
    var job = variantJobBuilder(mode);
    withVideo(job, h264VideoFor(mode, FIXTURE).setStreamIndex(2));
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    job.getVariantBuilder().setWidth(320).setHeight(180).setBitrateBitsPerSecond(500_000);
    job.getExecutionBuilder().setMediaSegmentCount(FIXTURE_SEGMENT_COUNT);

    var uploads = execute(engine(softwareCapabilities()), root, job.build());

    assertThat(uploads.names()).containsExactlyElementsOf(uploadNames(FIXTURE_SEGMENT_COUNT));
    var media = playable(uploads.contentOf(uploads.names()));
    assertDecodes(media);
    assertThat(video(media))
        .satisfies(
            video -> {
              assertThat(video.getCodec()).isEqualTo("h264");
              assertThat(video.getWidth()).isEqualTo(320);
              assertThat(video.getHeight()).isEqualTo(180);
            });
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TranscodeMode.class,
      names = {"TRANSCODE_MODE_UNSPECIFIED", "UNRECOGNIZED"},
      mode = EnumSource.Mode.EXCLUDE)
  @DisplayName("Should upload the second audio track when the job names it")
  void shouldUploadTheSecondAudioTrackWhenTheJobNamesIt(TranscodeMode mode) throws Exception {
    var source = sourceWithTwoAudioTracks();
    var probe = probeExecutor().probe(source, requestBuilder().build()).result().getMedia();
    assertThat(probe.getStreamsList())
        .extracting(ProbeStreamInfo::getCodec, ProbeStreamInfo::getSampleRateHz)
        .containsExactly(tuple("h264", 0), tuple("aac", 48_000), tuple("ac3", 44_100));
    var audioCodec = audioModeOf(mode) == AudioMode.AUDIO_MODE_COPY ? "ac3" : "aac";
    var job = variantJobBuilder(mode);
    withVideo(job, h264VideoFor(mode, FIXTURE));
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setMode(audioModeOf(mode))
        .setCodec(audioCodec)
        .setChannels(2)
        .setBitrateBitsPerSecond(128_000)
        .setStreamIndex(2);
    job.getVariantBuilder().setWidth(320).setHeight(180).setBitrateBitsPerSecond(500_000);
    job.getExecutionBuilder().setMediaSegmentCount(FIXTURE_SEGMENT_COUNT);

    var uploads = execute(engine(softwareCapabilities()), root, job.build());

    assertThat(uploads.names()).containsExactlyElementsOf(uploadNames(FIXTURE_SEGMENT_COUNT));
    var media = playable(uploads.contentOf(uploads.names()));
    assertDecodes(media);
    // Only the second track holds 44.1 kHz audio, and the AAC encoder keeps the source's rate.
    assertThat(streams(media))
        .filteredOn(stream -> "audio".equals(stream.getCodecType()))
        .singleElement()
        .satisfies(
            audio -> {
              assertThat(audio.getCodec()).isEqualTo(audioCodec);
              assertThat(audio.getSampleRateHz()).isEqualTo(44_100);
            });
  }

  @ParameterizedTest(name = "{0} naming stream {1} with subtitles {2}")
  @CsvSource({
    "TRANSCODE_MODE_REMUX, 0, SUBTITLE_MODE_EXCLUDE",
    "TRANSCODE_MODE_REMUX, 3, SUBTITLE_MODE_HLS",
    "TRANSCODE_MODE_REMUX, 4, SUBTITLE_MODE_EXCLUDE",
    "TRANSCODE_MODE_REMUX, 5, SUBTITLE_MODE_EXCLUDE",
    "TRANSCODE_MODE_REMUX, 9, SUBTITLE_MODE_EXCLUDE",
    "TRANSCODE_MODE_FULL_TRANSCODE, 0, SUBTITLE_MODE_EXCLUDE",
    "TRANSCODE_MODE_FULL_TRANSCODE, 3, SUBTITLE_MODE_HLS",
    "TRANSCODE_MODE_FULL_TRANSCODE, 4, SUBTITLE_MODE_EXCLUDE",
    "TRANSCODE_MODE_FULL_TRANSCODE, 5, SUBTITLE_MODE_EXCLUDE",
    "TRANSCODE_MODE_FULL_TRANSCODE, 9, SUBTITLE_MODE_EXCLUDE"
  })
  @DisplayName(
      "Should report an invalid specification and upload nothing when the stream that the job names"
          + " as its audio is a video, subtitle, attachment or cover art stream, or no stream, of a"
          + " real source")
  void
      shouldReportAnInvalidSpecificationAndUploadNothingWhenTheStreamThatTheJobNamesAsItsAudioIsNotAnAudioStreamOfARealSource(
          TranscodeMode mode, int streamIndex, SubtitleMode subtitleMode) throws Exception {
    var source = sourceWithSubtitleAndAttachment();
    var job = variantJobBuilder(mode);
    withVideo(job, h264VideoFor(mode, FIXTURE));
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setMode(audioModeOf(mode))
        .setCodec("aac")
        .setChannels(2)
        .setBitrateBitsPerSecond(128_000)
        .setStreamIndex(streamIndex);
    job.getDecisionBuilder().getSubtitleBuilder().setMode(subtitleMode);
    job.getVariantBuilder().setWidth(320).setHeight(180).setBitrateBitsPerSecond(500_000);
    job.getExecutionBuilder().setMediaSegmentCount(FIXTURE_SEGMENT_COUNT);

    var failure = executeExpectingFailure(engine(softwareCapabilities()), root, job.build());

    assertThat(failure.failure().getFailure())
        .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_INVALID_SPECIFICATION);
    assertThat(failure.uploads().names()).isEmpty();
  }

  // The fixture's video and AAC track, then a SubRip subtitle, a font attachment and cover art,
  // which FFmpeg reads from Matroska as a video stream with the attached picture disposition.
  private Path sourceWithSubtitleAndAttachment() throws Exception {
    copyMedia();
    var subtitles = root.resolve("subtitles.srt");
    Files.writeString(subtitles, "1\n00:00:00,000 --> 00:00:02,000\nHello\n");
    var font = root.resolve("font.ttf");
    Files.write(font, new byte[] {0, 1, 0, 0});
    var cover = root.resolve("cover.png");
    runFfmpeg(List.of("-f", "lavfi", "-i", "color=red:s=64x64", "-frames:v", "1"), cover);
    var source = root.resolve("subtitle-and-attachment.mkv");
    runFfmpeg(
        List.of(
            "-i",
            root.resolve("movie.mkv").toString(),
            "-i",
            subtitles.toString(),
            "-map",
            "0:v:0",
            "-map",
            "0:a:0",
            "-map",
            "0:a:0",
            "-map",
            "1:s:0",
            "-c",
            "copy",
            "-c:s",
            "srt",
            "-attach",
            font.toString(),
            "-metadata:s:t:0",
            "mimetype=application/x-truetype-font",
            "-attach",
            cover.toString(),
            "-metadata:s:t:1",
            "mimetype=image/png"),
        source);
    var streams = probeExecutor().probe(source, requestBuilder().build()).result().getMedia();
    assertThat(streams.getStreamsList())
        .extracting(ProbeStreamInfo::getCodecType)
        .containsExactly("video", "audio", "audio", "subtitle", "attachment", "video");
    return source;
  }

  // The fixture's video and 48 kHz AAC track, then its audio again as a 44.1 kHz AC-3 track.
  private Path sourceWithTwoAudioTracks() throws Exception {
    copyMedia();
    var source = root.resolve("two-audio-tracks.mkv");
    runFfmpeg(
        List.of(
            "-i",
            root.resolve("movie.mkv").toString(),
            "-map",
            "0:v:0",
            "-map",
            "0:a:0",
            "-map",
            "0:a:0",
            "-c:v",
            "copy",
            "-c:a:0",
            "copy",
            "-c:a:1",
            "ac3",
            "-ar:a:1",
            "44100",
            "-metadata:s:a:0",
            "language=eng",
            "-metadata:s:a:1",
            "language=jpn"),
        source);
    return source;
  }

  private Path sourceWithCoverArtBeforeVideo() throws Exception {
    copyMedia();
    var cover = root.resolve("cover.png");
    runFfmpeg(List.of("-f", "lavfi", "-i", "color=red:s=64x64", "-frames:v", "1"), cover);
    var source = root.resolve("cover-first.mp4");
    runFfmpeg(
        List.of(
            "-i",
            root.resolve("movie.mkv").toString(),
            "-i",
            cover.toString(),
            "-map",
            "0:a:0",
            "-map",
            "0:v:0",
            "-map",
            "1:v:0",
            "-c",
            "copy",
            "-disposition:v:1",
            "attached_pic"),
        source);
    var bytes = Files.readAllBytes(source);
    var moov =
        TopLevelBoxes.of(bytes).stream()
            .filter(box -> box.type().equals("moov"))
            .findFirst()
            .orElseThrow();
    var body = Arrays.copyOfRange(bytes, moov.start() + 8, moov.end());
    var children = TopLevelBoxes.of(body);
    var reordered = new ByteArrayOutputStream();
    // FFmpeg assigns cover art a stream index when it encounters udta; box sizes stay unchanged.
    Stream.concat(
            children.stream().filter(box -> box.type().equals("udta")),
            children.stream().filter(box -> !box.type().equals("udta")))
        .forEach(box -> reordered.write(body, box.start(), box.size()));
    System.arraycopy(reordered.toByteArray(), 0, bytes, moov.start() + 8, body.length);
    Files.write(source, bytes);
    return source;
  }

  @ParameterizedTest(name = "{0}/{1}")
  @CsvSource({"24000, 1001", "30000, 1001"})
  @DisplayName("Should upload the exact output frame rate when an encoded attempt starts or seeks")
  void shouldUploadTheExactOutputFrameRateWhenAnEncodedAttemptStartsOrSeeks(
      int numerator, int denominator) throws Exception {
    copyMedia();
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    withVideo(job, h264VideoFor(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE, FIXTURE));
    job.getVariantBuilder().setWidth(320).setHeight(180).setBitrateBitsPerSecond(500_000);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(3)
        .setMediaSegmentCount(4)
        .setFramerate(30)
        .setOutputFrameRate(
            Fraction.newBuilder().setNumerator(numerator).setDenominator(denominator));

    var initial = execute(engine(softwareCapabilities()), root, job.build());
    job.getExecutionBuilder().setStartSequenceNumber(2);
    job.setJobAttemptId(toProto(UUID.randomUUID()));
    var replacement = execute(engine(softwareCapabilities()), root, job.build());

    assertThat(replacement.names()).containsExactly("init.mp4", "segment2.m4s", "segment3.m4s");
    assertThat(replacement.segments())
        .containsEntry("init.mp4", initial.segments().get("init.mp4"));
    for (var uploads : List.of(initial, replacement)) {
      var media = playable(uploads.contentOf(uploads.names()));
      assertDecodes(media);
      var frameRate =
          runToCompletion(
              List.of(
                  "ffprobe",
                  "-v",
                  "error",
                  "-select_streams",
                  "v:0",
                  "-show_entries",
                  "stream=r_frame_rate",
                  "-of",
                  "default=noprint_wrappers=1:nokey=1",
                  media.toString()));
      assertThat(frameRate.strip()).isEqualTo(numerator + "/" + denominator);
    }
  }

  @ParameterizedTest(name = "{1}")
  @CsvSource({"FIELD_ORDER_TT, tff", "FIELD_ORDER_BB, bff"})
  @DisplayName(
      "Should upload progressive video with one frame per field in the source's field order when"
          + " an encode deinterlaces an interlaced source")
  void
      shouldUploadProgressiveVideoWithOneFramePerFieldInTheSourcesFieldOrderWhenAnEncodeDeinterlacesAnInterlacedSource(
          FieldOrder fieldOrder, String parity) throws Exception {
    var source = interlacedSource(parity);
    assertThat(idetMultiFrameCounts(source))
        .as("idet's reading of the generated source")
        .containsEntry(parity.toUpperCase(Locale.ROOT), INTERLACED_SOURCE_FRAMES);

    var uploads =
        execute(
            engine(softwareCapabilities()),
            source.getParent(),
            interlacedSourceJob(source, fieldOrder).build());

    var media = playable(uploads.contentOf(uploads.names()));
    assertDecodes(media);
    assertThat(videoStreamEntry(media, "r_frame_rate")).isEqualTo("60000/1001");
    assertThat(videoStreamEntry(media, "field_order")).isEqualTo("progressive");
    assertThat(countedFrames(media)).isEqualTo(2 * INTERLACED_SOURCE_FRAMES);
    var againstThePictures = psnrAgainst(media, interlacedSourcePictures(), "");
    assertThat(againstThePictures)
        .as("PSNR against the pictures, over PSNR against the woven frames")
        .isGreaterThan(psnrAgainst(media, source, WOVEN_FRAMES_AT_THE_FIELD_RATE))
        // Deinterlacing with the other field first swaps the two frames of each field pair, so
        // each uploaded frame then resembles its pair partner more than the picture it should
        // show.
        .as("PSNR against the pictures, over PSNR against each picture's pair partner")
        .isGreaterThan(psnrAgainst(media, interlacedSourcePictures(), "shuffleframes=1 0,"));
  }

  @Test
  @DisplayName(
      "Should upload the same initialization segment and output frame rate when a replacement"
          + " attempt deinterlaces from a seek")
  void shouldUploadTheSameInitializationSegmentAndOutputFrameRateWhenAReplacementAttemptSeeks()
      throws Exception {
    var source = interlacedSource("tff");
    var job = interlacedSourceJob(source, FieldOrder.FIELD_ORDER_TT);

    var initial = execute(engine(softwareCapabilities()), source.getParent(), job.build());
    job.getExecutionBuilder().setStartSequenceNumber(INTERLACED_REPLACEMENT_START);
    job.setJobAttemptId(toProto(UUID.randomUUID()));
    var replacement = execute(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(replacement.names()).containsExactly("init.mp4", "segment2.m4s");
    assertThat(replacement.segments())
        .containsEntry("init.mp4", initial.segments().get("init.mp4"));
    var media = playable(replacement.contentOf(replacement.names()));
    assertDecodes(media);
    assertThat(videoStreamEntry(media, "r_frame_rate")).isEqualTo("60000/1001");
    assertThat(videoStreamEntry(media, "field_order")).isEqualTo("progressive");
    // One segment of one frame per field.
    assertThat(countedFrames(media))
        .isEqualTo(2 * INTERLACED_SOURCE_FRAMES / INTERLACED_SOURCE_SEGMENT_COUNT);
    var framesBeforeTheReplacement =
        countedFrames(playable(initial.contentOf(initial.names()))) - countedFrames(media);
    var replacedFrames = "trim=start_frame=" + framesBeforeTheReplacement + ",";
    assertThat(psnrAgainst(media, interlacedSourcePictures(), replacedFrames))
        .as("PSNR against the pictures, over PSNR against the woven frames")
        .isGreaterThan(psnrAgainst(media, source, WOVEN_FRAMES_AT_THE_FIELD_RATE + replacedFrames));
  }

  // Interlaced video at 320x180, written one frame per field at 59.94 fps.
  private static VariantJob.Builder interlacedSourceJob(Path source, FieldOrder fieldOrder) {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    // 320x180 at 59.94 fps needs H.264 level 2.1.
    withVideo(
        job,
        forcedSoftwareEncode("h264", 21)
            .setDeinterlace(
                Deinterlace.newBuilder()
                    .setFieldOrder(fieldOrder)
                    .setOutput(DeinterlaceOutput.DEINTERLACE_OUTPUT_ONE_FRAME_PER_FIELD)));
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    job.getDecisionBuilder().getAudioBuilder().setMode(AudioMode.AUDIO_MODE_NONE);
    job.getVariantBuilder().setWidth(320).setHeight(180).setBitrateBitsPerSecond(1_000_000);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(3)
        .setMediaSegmentCount(INTERLACED_SOURCE_SEGMENT_COUNT)
        .setFramerate(30000.0 / 1001)
        .setOutputFrameRate(Fraction.newBuilder().setNumerator(60000).setDenominator(1001));
    return job;
  }

  // The progressive 59.94 fps pictures whose fields the interlaced sources weave together.
  private static synchronized Path interlacedSourcePictures() throws Exception {
    return buildFixtureOnce(
        interlacedSources.resolve("pictures.mkv"),
        output ->
            runFfmpeg(
                List.of(
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc2=size=320x180:rate=60000/1001",
                    "-frames:v",
                    String.valueOf(2 * INTERLACED_SOURCE_FRAMES),
                    "-c:v",
                    "ffv1"),
                output));
  }

  // libx264 codes the woven frames as field pairs, with the given field first.
  private static synchronized Path interlacedSource(String parity) throws Exception {
    var pictures = interlacedSourcePictures();
    var interleave = parity.equals("tff") ? "interleave_top" : "interleave_bottom";
    return buildFixtureOnce(
        interlacedSources.resolve(parity + ".mp4"),
        output ->
            runFfmpeg(
                List.of(
                    "-i",
                    pictures.toString(),
                    "-vf",
                    "tinterlace=mode=" + interleave + ",setfield=" + parity,
                    "-c:v",
                    "libx264",
                    "-flags",
                    "+ildct+ilme",
                    "-x264opts",
                    parity + "=1",
                    "-qp",
                    "10",
                    "-g",
                    "30",
                    "-pix_fmt",
                    "yuv420p"),
                output));
  }

  private static Map<String, Integer> idetMultiFrameCounts(Path media) throws Exception {
    var output =
        runToCompletion(
            List.of(
                "ffmpeg",
                "-nostdin",
                "-hide_banner",
                "-i",
                media.toString(),
                "-vf",
                "idet",
                "-f",
                "null",
                "-"));
    // FFmpeg configures the filter graph twice and idet reports on each; the last report counts
    // the frames.
    var reports = IDET_MULTI_FRAME.matcher(output).results().toList();
    assertThat(reports).as(output).isNotEmpty();
    var counts = reports.getLast();
    return Map.of(
        "TFF", Integer.parseInt(counts.group(1)),
        "BFF", Integer.parseInt(counts.group(2)),
        "PROGRESSIVE", Integer.parseInt(counts.group(3)));
  }

  private static String videoStreamEntry(Path media, String entry) throws Exception {
    return runToCompletion(
            List.of(
                "ffprobe",
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_entries",
                "stream=" + entry,
                "-of",
                "default=noprint_wrappers=1:nokey=1",
                media.toString()))
        .strip();
  }

  private static int countedFrames(Path media) throws Exception {
    return Integer.parseInt(
        runToCompletion(
                List.of(
                    "ffprobe",
                    "-v",
                    "error",
                    "-count_frames",
                    "-select_streams",
                    "v:0",
                    "-show_entries",
                    "stream=nb_read_frames",
                    "-of",
                    "default=noprint_wrappers=1:nokey=1",
                    media.toString()))
            .strip());
  }

  // The average luma and chroma PSNR of the media against the reference after the reference
  // filter, comparing frames by their order.
  private static double psnrAgainst(Path media, Path reference, String referenceFilter)
      throws Exception {
    var output =
        runToCompletion(
            List.of(
                "ffmpeg",
                "-nostdin",
                "-hide_banner",
                "-i",
                media.toString(),
                "-i",
                reference.toString(),
                "-lavfi",
                "[0:v]setpts=N/TB/100[media];[1:v]"
                    + referenceFilter
                    + "setpts=N/TB/100[reference];[media][reference]psnr",
                "-f",
                "null",
                "-"));
    var matcher = PSNR_AVERAGE.matcher(output);
    assertThat(matcher.find()).as(output).isTrue();
    return Double.parseDouble(matcher.group(1));
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

  @Test
  @DisplayName("Should upload BT.709 limited 8-bit video when a PQ source is encoded to SDR")
  void shouldUploadBt709LimitedEightBitVideoWhenAPqSourceIsEncodedToSdr() throws Exception {
    var source = hdr10SourceWithStaticMetadataOnlyInItsRecord();
    var job = tenBitSdrSourceJob(source, "h264");
    job.getExecutionBuilder().setTargetSegmentDurationSeconds(1).setMediaSegmentCount(2);
    job.getDecisionBuilder()
        .getVideoBuilder()
        .setSourceDynamicRange(
            VideoDynamicRange.newBuilder().setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ))
        .setOutputDynamicRange(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
                .setHdr10Plus(Presence.PRESENCE_ABSENT)
                .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance()));

    var uploads = execute(engine(softwareCapabilities()), root, job.build());

    var media = playable(uploads.contentOf(uploads.names()));
    assertDecodes(media);
    assertSdrVideo(media);
  }

  @ParameterizedTest(name = "{0} source, {1}")
  @CsvSource({
    "COMPATIBLE_BASE_SDR, h264",
    "COMPATIBLE_BASE_SDR, hevc",
    "COMPATIBLE_BASE_SDR, av1",
    "COMPATIBLE_BASE_UNSPECIFIED, h264",
    "COMPATIBLE_BASE_UNSPECIFIED, hevc",
    "COMPATIBLE_BASE_UNSPECIFIED, av1"
  })
  @DisplayName("Should upload BT.709 limited video when an SDR or unspecified source is encoded")
  void shouldUploadBt709LimitedVideoWhenAnSdrOrUnspecifiedSourceIsEncoded(
      CompatibleBase sourceBase, String codecFamily) throws Exception {
    var source = sdrColorSource(sourceBase);
    var job = tenBitSdrSourceJob(source, codecFamily);
    job.getExecutionBuilder().setTargetSegmentDurationSeconds(1).setMediaSegmentCount(2);
    job.getDecisionBuilder()
        .getVideoBuilder()
        .setSourceDynamicRange(VideoDynamicRange.newBuilder().setCompatibleBase(sourceBase));

    var uploads = execute(engine(softwareCapabilities()), root, job.build());

    var media = playable(uploads.contentOf(uploads.names()));
    assertDecodes(media);
    assertSdrVideo(media);
  }

  private Path sdrColorSource(CompatibleBase sourceBase) throws Exception {
    var source = root.resolve("sdr-color.mp4");
    var arguments =
        new ArrayList<>(
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=64x36:rate=24:duration=2",
                "-c:v",
                "libx265",
                "-preset",
                "ultrafast",
                "-pix_fmt",
                "yuv420p10le",
                "-x265-params",
                "keyint=24:min-keyint=24:scenecut=0:log-level=error"));
    if (sourceBase == CompatibleBase.COMPATIBLE_BASE_SDR) {
      arguments.addAll(
          List.of(
              "-vf",
              "scale=in_range=tv:out_range=pc",
              "-color_range",
              "pc",
              "-color_primaries",
              "bt709",
              "-color_trc",
              "bt709",
              "-colorspace",
              "bt709"));
    }

    runFfmpeg(arguments, source);
    return source;
  }

  private static void assertSdrVideo(Path media) throws Exception {
    assertThat(
            videoStreamFields(
                media, "pix_fmt,color_range,color_space,color_transfer,color_primaries"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "pix_fmt", "yuv420p",
                "color_range", "tv",
                "color_space", "bt709",
                "color_transfer", "bt709",
                "color_primaries", "bt709"));
  }

  @ParameterizedTest(name = "{0} source, {1}")
  @CsvSource({
    "COMPATIBLE_BASE_PQ, h264",
    "COMPATIBLE_BASE_PQ, hevc",
    "COMPATIBLE_BASE_PQ, av1",
    "COMPATIBLE_BASE_HLG, h264",
    "COMPATIBLE_BASE_HLG, hevc",
    "COMPATIBLE_BASE_HLG, av1"
  })
  @DisplayName(
      "Should upload matching SDR initialization without HDR metadata when an HDR encode seeks")
  void shouldUploadMatchingSdrInitializationWithoutHdrMetadataWhenAnHdrEncodeSeeks(
      CompatibleBase sourceBase, String codecFamily) throws Exception {
    var source = hdrColorSource(sourceBase);
    addAmbientViewingEnvironment(source);
    assertThat(video(source).getDynamicRange().getCompatibleBase()).isEqualTo(sourceBase);
    assertThat(videoStreamFields(source, "color_primaries,color_space"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("color_primaries", "bt2020", "color_space", "bt2020nc"));
    assertThat(streamSideData(source)).contains("Ambient viewing environment");
    assertThat(frameSideDataPerFrame(source))
        .isNotEmpty()
        .allSatisfy(sideData -> assertThat(sideData).contains("Ambient viewing environment"));
    if (sourceBase == CompatibleBase.COMPATIBLE_BASE_PQ) {
      assertThat(frameSideDataPerFrame(source))
          .anySatisfy(
              sideData ->
                  assertThat(sideData)
                      .contains("Mastering display metadata", "Content light level metadata"));
    }

    var fromStart = tenBitSdrSourceJob(source, codecFamily);
    fromStart.getExecutionBuilder().setTargetSegmentDurationSeconds(1).setMediaSegmentCount(3);
    fromStart
        .getDecisionBuilder()
        .getVideoBuilder()
        .setSourceDynamicRange(VideoDynamicRange.newBuilder().setCompatibleBase(sourceBase));
    var replacement = fromStart.clone();
    replacement.setJobAttemptId(toProto(UUID.randomUUID()));
    replacement.getExecutionBuilder().setStartSequenceNumber(2);

    var initialUploads = execute(engine(softwareCapabilities()), root, fromStart.build());
    var replacementUploads = execute(engine(softwareCapabilities()), root, replacement.build());

    assertThat(replacementUploads.names()).containsExactly("init.mp4", "segment2.m4s");
    assertThat(replacementUploads.segments())
        .containsEntry("init.mp4", initialUploads.segments().get("init.mp4"));
    assertSdrUploads(initialUploads);
    assertSdrUploads(replacementUploads);
  }

  private Path assertSdrUploads(MediaUploads uploads) throws Exception {
    var media = playable(uploads.contentOf(uploads.names()));
    assertDecodes(media);
    assertSdrVideo(media);
    assertThat(streamSideData(media))
        .doesNotContain(
            "Ambient viewing environment",
            "Mastering display metadata",
            "Content light level metadata");
    assertThat(frameSideDataPerFrame(media))
        .allSatisfy(
            sideData ->
                assertThat(sideData)
                    .doesNotContain(
                        "Ambient viewing environment",
                        "Mastering display metadata",
                        "Content light level metadata"));
    for (var box : List.of("amve", "mdcv", "clli", "dvcC", "dvvC")) {
      assertThat(
              indexOf(uploads.segments().get("init.mp4"), box.getBytes(StandardCharsets.US_ASCII)))
          .as("the SDR initialization segment's %s box", box)
          .isNegative();
    }

    return media;
  }

  private Path hdrColorSource(CompatibleBase sourceBase) throws Exception {
    var source = root.resolve("hdr-color.mp4");
    var transfer =
        switch (sourceBase) {
          case COMPATIBLE_BASE_PQ -> "smpte2084";
          case COMPATIBLE_BASE_HLG -> "arib-std-b67";
          default -> throw new IllegalArgumentException("An HDR fixture requires PQ or HLG");
        };
    var metadata =
        sourceBase == CompatibleBase.COMPATIBLE_BASE_PQ
            ? ":master-display="
                + HDR10_MASTERING_DISPLAY.x265Parameter()
                + ":max-cll="
                + HDR10_CONTENT_LIGHT_LEVEL.x265Parameter()
            : "";
    runFfmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x36:rate=24:duration=3",
            "-vf",
            "setparams=color_primaries=bt2020:color_trc=" + transfer + ":colorspace=bt2020nc",
            "-c:v",
            "libx265",
            "-preset",
            "ultrafast",
            "-pix_fmt",
            "yuv420p10le",
            "-color_primaries",
            "bt2020",
            "-color_trc",
            transfer,
            "-colorspace",
            "bt2020nc",
            "-x265-params",
            "keyint=24:min-keyint=24:scenecut=0:log-level=error" + metadata),
        source);
    return source;
  }

  // FFmpeg reads amve but has no option to write it. These generated files have one video track
  // and moov after mdat, so extending the sample entry does not move any media chunk.
  private static void addAmbientViewingEnvironment(Path source) throws Exception {
    var original = Files.readAllBytes(source);
    assertThat(TopLevelBoxes.of(original))
        .extracting(Box::type)
        .containsSubsequence("mdat", "moov");
    var stsd = indexOf(original, "stsd".getBytes(StandardCharsets.US_ASCII));
    assertThat(ByteBuffer.wrap(original).getInt(stsd + 8)).as("sample entry count").isEqualTo(1);
    var entry = sampleEntryTypeIndex(original) - 4;
    var insertAt = entry + ByteBuffer.wrap(original).getInt(entry);
    var ambient =
        ByteBuffer.allocate(16)
            .putInt(16)
            .put("amve".getBytes(StandardCharsets.US_ASCII))
            .putInt(1_000_000)
            .putShort((short) 15635)
            .putShort((short) 16450)
            .array();
    var amended = ByteBuffer.allocate(original.length + ambient.length);
    amended
        .put(original, 0, insertAt)
        .put(ambient)
        .put(original, insertAt, original.length - insertAt);
    for (var type : List.of("moov", "trak", "mdia", "minf", "stbl", "stsd")) {
      var start = indexOf(original, type.getBytes(StandardCharsets.US_ASCII)) - 4;
      assertThat(start).as("the generated source's %s box", type).isNotNegative();
      amended.putInt(start, amended.getInt(start) + ambient.length);
    }

    amended.putInt(entry, amended.getInt(entry) + ambient.length);
    Files.write(source, amended.array());
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"h264", "hevc", "av1"})
  @DisplayName(
      "Should encode the SDR compatible base when a Dolby Vision profile 8.2 source is encoded")
  void shouldEncodeTheSdrCompatibleBaseWhenADolbyVisionProfile82SourceIsEncoded(String codecFamily)
      throws Exception {
    var source = sampleEntryFixture(DOLBY_VISION_FIXTURE, "dvh1");
    assertThat(
            runToCompletion(
                List.of(
                    "ffprobe",
                    "-v",
                    "error",
                    "-select_streams",
                    "v:0",
                    "-show_entries",
                    "stream_side_data=dv_profile,dv_bl_signal_compatibility_id",
                    "-of",
                    "default=noprint_wrappers=1",
                    source.toString())))
        .contains("dv_profile=8", "dv_bl_signal_compatibility_id=2");
    assertThat(frameSideDataPerFrame(source))
        .isNotEmpty()
        .allSatisfy(sideData -> assertThat(sideData).contains("Dolby Vision RPU Data"));
    var job = tenBitSdrSourceJob(source, codecFamily);
    job.getExecutionBuilder().setTargetSegmentDurationSeconds(1).setMediaSegmentCount(2);
    job.getVariantBuilder().setWidth(64).setHeight(64);
    job.getDecisionBuilder()
        .getVideoBuilder()
        .setSourceDynamicRange(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_SDR)
                .setHdr10Plus(Presence.PRESENCE_ABSENT)
                .setDolbyVisionPresent(
                    DolbyVisionPresent.newBuilder()
                        .setDeclared(
                            DolbyVisionConfiguration.newBuilder()
                                .setProfile(8)
                                .setBaseLayerCompatibilityId(2)
                                .setBaseLayer(true)
                                .setRpu(true))
                        .setObservedRpu(Presence.PRESENCE_PRESENT)));

    var uploads = execute(engine(softwareCapabilities()), root, job.build());

    var media = assertSdrUploads(uploads);
    assertThat(streamSideData(media)).doesNotContain("DOVI configuration record");
    assertThat(frameSideDataPerFrame(media))
        .allSatisfy(sideData -> assertThat(sideData).doesNotContain("Dolby Vision RPU Data"));
  }

  @ParameterizedTest(name = "{0}, {1} audio, start {2}")
  @MethodSource("rpuLayoutsAudioCodecsAndStarts")
  @DisplayName(
      "Should upload what one FFmpeg encodes from the source whose RPUs follow their pictures when"
          + " a Dolby Vision profile 5 source's samples start with RPUs")
  void
      shouldUploadWhatOneFfmpegEncodesFromTheSourceWhoseRpusFollowTheirPicturesWhenADolbyVisionProfile5SourcesSamplesStartWithRpus(
          RpuLayout layout, String sourceAudioCodec, int startSequenceNumber) throws Exception {
    var rpusAhead = rpuLayoutSource(layout, sourceAudioCodec);
    var rpusBehind = rpuLayoutSource(RpuLayout.BEHIND_THEIR_PICTURE, sourceAudioCodec);
    var aheadCommands = new CopyOnWriteArrayList<List<String>>();
    var behindCommands = new CopyOnWriteArrayList<List<String>>();

    var fromRpusAhead =
        execute(
            engine(
                softwareCapabilities(),
                capturingCommands(
                    withX264ThreadsOfOne(new ProcessBuilderLauncher()), aheadCommands)),
            rpusAhead.getParent(),
            profile5Job(rpusAhead, layout.placement(), startSequenceNumber).build());
    var fromRpusBehind =
        execute(
            engine(
                softwareCapabilities(),
                capturingCommands(
                    withX264ThreadsOfOne(new ProcessBuilderLauncher()), behindCommands)),
            rpusBehind.getParent(),
            profile5Job(
                    rpusBehind,
                    DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE,
                    startSequenceNumber)
                .build());

    assertThat(aheadCommands).as("FFmpegs for the source whose RPUs start samples").hasSize(2);
    assertThat(behindCommands).as("FFmpegs for the source whose RPUs follow pictures").hasSize(1);
    assertThat(fromRpusAhead.names())
        .containsExactlyElementsOf(fromRpusBehind.names())
        .hasSize(PROFILE_5_SEGMENT_COUNT - startSequenceNumber + 1);
    for (var name : fromRpusBehind.names()) {
      assertThat(packetsOf(fromRpusAhead, name))
          .as(name)
          .containsExactlyElementsOf(packetsOf(fromRpusBehind, name));
    }
  }

  // The copy of a profile 5 source carries only the source's video, so its MP4 muxer writes each
  // packet as it comes. A copy that also carried audio wrote nothing until the audio's first
  // sample, and its first fragment then held every picture that the demuxer read before that
  // sample. Without the server's keyframe position, the replacement reads from 18 s, one period
  // before its first segment, and the MP4 demuxer lands on the keyframe at 10 s: 8 s of pictures,
  // 63.8 MB on the pinned FFmpeg, over the 32 MiB that the mover reads. The decode time of the
  // keyframe at 20 s, which B-frames follow, lies before its presentation time, so a seek there
  // also lands on the keyframe at 10 s: 79.0 MB.
  @ParameterizedTest(name = "server's keyframe presented by {0}")
  @NullSource
  @ValueSource(strings = "20.5")
  @DisplayName(
      "Should upload what one FFmpeg encodes when the copy of a profile 5 MP4 source with B-frames"
          + " reads more pictures before its seek than the mover reads in one fragment")
  void
      shouldUploadWhatOneFfmpegEncodesWhenTheCopyOfAProfile5Mp4SourceWithBFramesReadsMorePicturesBeforeItsSeekThanTheMoverReadsInOneFragment(
          BigDecimal keyframePresentedBy) throws Exception {
    var source = largePictureHevcSource();
    var job = largePictureJob(source);
    if (keyframePresentedBy != null) {
      setSeekPosition(
          job.getExecutionBuilder(),
          decodeTimeOfTheLastKeyframePresentedBy(source, keyframePresentedBy));
    }

    assertEncodeOfTheCopyUploadsWhatOneFfmpegEncodes(source, job);
  }

  // A full encode of the large-picture source from segment 7, at 21 s on a 3 s period.
  private static VariantJob.Builder largePictureJob(Path source) {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    withVideo(job, forcedSoftwareEncode("h264", TINY_VARIANT_LEVELS.get("h264")));
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
        .setCodec("aac")
        .setChannels(1)
        .setBitrateBitsPerSecond(64_000);
    job.getVariantBuilder().setWidth(64).setHeight(36).setBitrateBitsPerSecond(100_000);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(SLOT_SECONDS)
        .setMediaSegmentCount(LARGE_PICTURE_SOURCE_SECONDS / SLOT_SECONDS)
        .setStartSequenceNumber(LARGE_PICTURE_REPLACEMENT_START_SEQUENCE_NUMBER);
    return job;
  }

  // 24 s of 512x288 10-bit HEVC at 24 fps whose lossless noise makes each picture about 330 KB,
  // with 3 B-frames and keyframes only at 0, 10 and 20 s, and a 440 Hz tone, in MP4.
  private static synchronized Path largePictureHevcSource() throws Exception {
    return buildFixtureOnce(
        largePictureSources.resolve("large-pictures.mp4"),
        output ->
            runFfmpeg(
                List.of(
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc2=size=512x288:rate=24,noise=alls=100:allf=t+u",
                    "-f",
                    "lavfi",
                    "-i",
                    "sine=frequency=440:sample_rate=48000",
                    "-t",
                    String.valueOf(LARGE_PICTURE_SOURCE_SECONDS),
                    "-pix_fmt",
                    "yuv420p10le",
                    "-c:v",
                    "libx265",
                    "-x265-params",
                    "lossless=1:keyint=100000:min-keyint=1:scenecut=0:bframes=3:log-level=error",
                    "-forced-idr",
                    "1",
                    "-force_key_frames",
                    "0,10,20",
                    "-tag:v",
                    "hvc1",
                    "-c:a",
                    "aac",
                    "-b:a",
                    "96k"),
                output));
  }

  static Stream<Arguments> rpuLayoutsAudioCodecsAndStarts() {
    return Stream.of(RpuLayout.AHEAD_OF_THE_NEXT_PICTURE, RpuLayout.AHEAD_OF_EVERY_PICTURE)
        .flatMap(
            layout ->
                Stream.of("aac", "ac3", "eac3", "flac", "alac", "libopus")
                    .flatMap(
                        codec -> Stream.of(0, 2).map(start -> Arguments.of(layout, codec, start))));
  }

  // The packets of the upload and its initialization segment.
  private List<Packet> packetsOf(MediaUploads uploads, String name) throws Exception {
    return FfprobePackets.of(uploads.contentOf(List.of("init.mp4", name)), root);
  }

  private static VariantJob.Builder profile5Job(
      Path source, DolbyVisionRpuPlacement rpuPlacement, int startSequenceNumber) {
    var job = variantJobBuilder(TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getSourceBuilder().setRelativeKey(source.getFileName().toString());
    withVideo(job, forcedSoftwareEncode("h264", TINY_VARIANT_LEVELS.get("h264")));
    withProfile5Source(job, rpuPlacement);
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
        .setCodec("aac")
        .setChannels(1)
        .setBitrateBitsPerSecond(64_000);
    job.getVariantBuilder().setWidth(64).setHeight(36).setBitrateBitsPerSecond(100_000);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(PROFILE_5_PERIOD_SECONDS)
        .setMediaSegmentCount(PROFILE_5_SEGMENT_COUNT)
        .setStartSequenceNumber(startSequenceNumber);
    return job;
  }

  // Dolby Vision profile 5 HEVC, whose samples place each picture's RPU as rpuPlacement says, in a
  // container whose packets carry presentation times.
  private static void withProfile5Source(
      VariantJob.Builder job, DolbyVisionRpuPlacement rpuPlacement) {
    job.getDecisionBuilder()
        .getVideoBuilder()
        .setSourceCodecFamily(VideoCodecFamily.VIDEO_CODEC_FAMILY_HEVC)
        .setSourcePacketsCarryPresentationTimes(true)
        .setSourceDynamicRange(
            VideoDynamicRange.newBuilder()
                .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_NONE)
                .setHdr10Plus(Presence.PRESENCE_ABSENT)
                .setDolbyVisionPresent(
                    DolbyVisionPresent.newBuilder()
                        .setObservedRpu(Presence.PRESENCE_PRESENT)
                        .setRpuPlacement(rpuPlacement)));
  }

  @ParameterizedTest(name = "{0}, {1}, start {2}")
  @MethodSource("sourceContainersEncodingModesAndStarts")
  @DisplayName(
      "Should upload the packets one FFmpeg encodes from the source when the encode reads the"
          + " source's fragmented MP4 copy")
  void
      shouldUploadThePacketsOneFfmpegEncodesFromTheSourceWhenTheEncodeReadsTheSourcesFragmentedMp4Copy(
          String container, TranscodeMode mode, int startSequenceNumber) throws Exception {
    var source = seekableHevcSource(container);
    var job = seekableSourceJob(source, mode);
    job.getExecutionBuilder().setStartSequenceNumber(startSequenceNumber);

    assertEncodeOfTheCopyUploadsWhatOneFfmpegEncodes(source, job);
  }

  @ParameterizedTest(name = "{0}, start {1}")
  @MethodSource("encodingModesAndStarts")
  @DisplayName(
      "Should upload the packets one FFmpeg encodes from the source when the encode reads the"
          + " fragmented MP4 copy of a variable-frame-rate Matroska source whose video reorders"
          + " pictures")
  void
      shouldUploadThePacketsOneFfmpegEncodesFromTheSourceWhenTheEncodeReadsTheFragmentedMp4CopyOfAVariableFrameRateMatroskaSourceWhoseVideoReordersPictures(
          TranscodeMode mode, int startSequenceNumber) throws Exception {
    var source = variableFrameRateHevcSource();
    var job = seekableSourceJob(source, mode);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(PROFILE_5_PERIOD_SECONDS)
        .setMediaSegmentCount(PROFILE_5_SEGMENT_COUNT)
        .setStartSequenceNumber(startSequenceNumber);

    assertEncodeOfTheCopyUploadsWhatOneFfmpegEncodes(source, job);
  }

  @ParameterizedTest(name = "{0}, {1}")
  @MethodSource("displayChangesAndContainers")
  @DisplayName(
      "Should upload what one FFmpeg encodes, turned and cropped as the source's container says,"
          + " when the encode reads the fragmented MP4 copy of a rotated or cropped source")
  void
      shouldUploadWhatOneFfmpegEncodesTurnedAndCroppedAsTheSourcesContainerSaysWhenTheEncodeReadsTheFragmentedMp4CopyOfARotatedOrCroppedSource(
          DisplayChange change, String container) throws Exception {
    var source = displayChangedSource(change, container);
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getVariantBuilder().setHeight(DISPLAY_SOURCE_HEIGHT / 2);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(PROFILE_5_PERIOD_SECONDS)
        .setMediaSegmentCount(PROFILE_5_SEGMENT_COUNT);

    var fromTheCopy = assertEncodeOfTheCopyUploadsWhatOneFfmpegEncodes(source, job);

    assertThat(dimensionsOf(fromTheCopy)).isEqualTo(change.encodedDimensions());
  }

  // libx264's frame threads under a bitrate cap make its rate control depend on when each frame
  // arrives, which differs between one FFmpeg and the encode of the copy once frames take more than
  // an instant to encode; with one thread, the same frames encode to the same packets.
  private static ProcessLauncher withX264ThreadsOfOne(ProcessLauncher launcher) {
    return (command, jobAttemptId) -> {
      var encoder = command.indexOf("libx264");
      if (encoder < 0) {
        return launcher.launch(command, jobAttemptId);
      }

      var adjusted = new ArrayList<>(command);
      adjusted.addAll(encoder + 1, List.of("-threads:v", "1"));
      return launcher.launch(adjusted, jobAttemptId);
    };
  }

  static Stream<Arguments> displayChangesAndContainers() {
    return Arrays.stream(DisplayChange.values())
        .flatMap(
            change -> Stream.of("mp4", "mkv").map(container -> Arguments.of(change, container)));
  }

  // What the source's container says of its 320x180 pictures, and the size of their encode at half
  // that height, whose width FFmpeg rounds to an even number from the turned and cropped aspect.
  private enum DisplayChange {
    ROTATED(true, false, "50x90"),
    CROPPED(false, true, "144x90"),
    ROTATED_AND_CROPPED(true, true, "56x90");

    private final boolean rotated;
    private final boolean cropped;
    private final String encodedDimensions;

    DisplayChange(boolean rotated, boolean cropped, String encodedDimensions) {
      this.rotated = rotated;
      this.cropped = cropped;
      this.encodedDimensions = encodedDimensions;
    }

    String encodedDimensions() {
      return encodedDimensions;
    }
  }

  private String dimensionsOf(MediaUploads uploads) throws Exception {
    var media = playable(uploads.contentOf(uploads.names()));
    return runToCompletion(
            List.of(
                "ffprobe",
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_entries",
                "stream=width,height",
                "-of",
                "csv=p=0:s=x",
                media.toString()))
        .strip();
  }

  // HEVC with noise, in MP4 whose display matrix turns the pictures 90 degrees, or whose clap box
  // crops 32, 96, 20 and 40 pixels from their left, right, top and bottom, to 192x120, or both.
  // The Matroska source carries the MP4 source's streams, with its rotation and cropping.
  private static synchronized Path displayChangedSource(DisplayChange change, String container)
      throws Exception {
    var name = change.name().toLowerCase(Locale.ROOT);
    var source = seekableSources.resolve(name + "." + container);
    if (generatedSeekableSources.contains(source)) {
      return source;
    }

    var mp4 = seekableSources.resolve(name + ".mp4");
    if (!generatedSeekableSources.contains(mp4)) {
      writeDisplayChangedMp4(change, mp4);
      generatedSeekableSources.add(mp4);
    }

    if (!container.equals("mp4")) {
      runFfmpeg(List.of("-i", mp4.toString(), "-map", "0", "-c", "copy"), source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  private static void writeDisplayChangedMp4(DisplayChange change, Path mp4) throws Exception {
    var input = new ArrayList<String>();
    if (change.rotated) {
      input.addAll(List.of("-display_rotation", "90"));
    }

    input.addAll(List.of("-i", displaySource().toString(), "-c", "copy"));
    runFfmpeg(input, mp4);
    if (change.cropped) {
      cropInPlace(mp4);
    }
  }

  private static synchronized Path displaySource() throws Exception {
    var source = seekableSources.resolve("display.mp4");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc2=size=320x" + DISPLAY_SOURCE_HEIGHT + ":rate=24",
              "-f",
              "lavfi",
              "-i",
              "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5",
              "-t",
              String.valueOf(PROFILE_5_PERIOD_SECONDS * PROFILE_5_SEGMENT_COUNT),
              "-c:v",
              "libx265",
              "-x265-params",
              "keyint=24:min-keyint=24:log-level=error",
              "-c:a",
              "aac"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // Inserts the clap box into the video sample entry of an MP4 whose moov follows its mdat, so no
  // chunk offset moves, and grows every box that holds the entry by its size.
  private static void cropInPlace(Path mp4) throws IOException {
    var bytes = Files.readAllBytes(mp4);
    var enclosing = new ArrayList<Integer>();
    var position = 0;
    var end = bytes.length;
    for (var type : List.of("moov", "trak", "mdia", "minf", "stbl", "stsd")) {
      position = childOf(bytes, position, end, type);
      enclosing.add(position);
      end = position + ByteBuffer.wrap(bytes, position, 4).getInt();
      position += type.equals("stsd") ? 16 : 8;
    }

    var entry = position;
    enclosing.add(entry);
    var entryEnd = entry + ByteBuffer.wrap(bytes, entry, 4).getInt();
    var width = ByteBuffer.wrap(bytes, entry + 32, 2).getShort();
    var height = ByteBuffer.wrap(bytes, entry + 34, 2).getShort();
    var clap =
        ByteBuffer.allocate(40)
            .putInt(40)
            .put("clap".getBytes(StandardCharsets.US_ASCII))
            .putInt(width - 32 - 96)
            .putInt(1)
            .putInt(height - 20 - 40)
            .putInt(1)
            .putInt(32 - 96)
            .putInt(2)
            .putInt(20 - 40)
            .putInt(2)
            .array();
    var cropped = new ByteArrayOutputStream();
    cropped.write(bytes, 0, entryEnd);
    cropped.writeBytes(clap);
    cropped.write(bytes, entryEnd, bytes.length - entryEnd);
    var result = ByteBuffer.wrap(cropped.toByteArray());
    for (var box : enclosing) {
      result.putInt(box, result.getInt(box) + clap.length);
    }

    Files.write(mp4, result.array());
  }

  // The position of the first child of the type between the positions; the movie holds one video
  // track, its first trak.
  private static int childOf(byte[] bytes, int start, int end, String type) {
    for (var position = start; position < end; ) {
      var size = ByteBuffer.wrap(bytes, position, 4).getInt();
      var found = new String(bytes, position + 4, 4, StandardCharsets.US_ASCII);
      if (found.equals(type)) {
        return position;
      }

      position += size;
    }

    throw new AssertionError("no " + type + " between " + start + " and " + end);
  }

  static Stream<Arguments> encodingModesAndStarts() {
    return Stream.of(
            TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE,
            TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
        .flatMap(mode -> Stream.of(0, 2).map(start -> Arguments.of(mode, start)));
  }

  // Runs the profile 5 job once as one FFmpeg, whose source's RPUs follow their pictures, and once
  // as the encode of the source's copy, whose RPUs may start samples, each with libx264 on one
  // thread, and compares their packets.
  private MediaUploads assertEncodeOfTheCopyUploadsWhatOneFfmpegEncodes(
      Path source, VariantJob.Builder job) throws Exception {
    withProfile5Source(job, DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE);
    var oneFfmpeg =
        execute(
            engine(softwareCapabilities(), withX264ThreadsOfOne(new ProcessBuilderLauncher())),
            source.getParent(),
            job.build());
    withProfile5Source(job, DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);
    job.setJobAttemptId(toProto(UUID.randomUUID()));
    var commands = new CopyOnWriteArrayList<List<String>>();

    var fromTheCopy =
        execute(
            engine(
                softwareCapabilities(),
                capturingCommands(withX264ThreadsOfOne(new ProcessBuilderLauncher()), commands)),
            source.getParent(),
            job.build());

    assertThat(commands).as("the FFmpegs of the attempt that reads the copy").hasSize(2);
    assertThat(fromTheCopy.names()).containsExactlyElementsOf(oneFfmpeg.names());
    for (var name : oneFfmpeg.names()) {
      assertThat(packetsOf(fromTheCopy, name))
          .as(name)
          .containsExactlyElementsOf(packetsOf(oneFfmpeg, name));
    }

    return fromTheCopy;
  }

  static Stream<Arguments> sourceContainersEncodingModesAndStarts() {
    return sourceContainersModesAndStarts()
        .filter(
            arguments ->
                ((TranscodeMode) arguments.get()[1]) == TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE
                    || ((TranscodeMode) arguments.get()[1])
                        == TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
  }

  private enum RpuLayout {
    BEHIND_THEIR_PICTURE(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_AFTER_OWN_PICTURE),
    AHEAD_OF_THE_NEXT_PICTURE(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE),
    AHEAD_OF_EVERY_PICTURE(DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);

    private final DolbyVisionRpuPlacement placement;

    RpuLayout(DolbyVisionRpuPlacement placement) {
      this.placement = placement;
    }

    DolbyVisionRpuPlacement placement() {
      return placement;
    }
  }

  // A 10-bit HEVC source with audio of the codec that holds a stand-in Dolby Vision RPU for each
  // picture: after its picture, or behind the access unit delimiter of the next picture, as
  // mkvmerge wrote it before version 57, or behind the delimiter of every picture, as that layout
  // reads once its start is cut away and as a layout whose RPUs start their own pictures would
  // read. Its stand-in RPUs carry no reshaping, so the decoder applies none whichever layout it
  // reads, and both sources decode to the same pictures.
  private static synchronized Path rpuLayoutSource(RpuLayout layout, String audioCodec)
      throws Exception {
    var layoutName = layout.name().toLowerCase(Locale.ROOT);
    return buildFixtureOnce(
        rpuLayoutSources.resolve(layoutName + "-" + audioCodec + ".mp4"),
        output -> {
          var withRpus = rpuLayoutSources.resolve(layoutName + ".hevc");
          Files.write(withRpus, withStandInRpus(Files.readAllBytes(rpuLayoutPictures()), layout));
          runFfmpeg(
              List.of(
                  "-i",
                  withRpus.toString(),
                  "-f",
                  "lavfi",
                  "-i",
                  "sine=frequency=440:sample_rate=48000",
                  "-map",
                  "0:v",
                  "-map",
                  "1:a",
                  "-c:v",
                  "copy",
                  "-bsf:v",
                  "setts=pts=N*1/24/TB:dts=N*1/24/TB",
                  "-c:a",
                  audioCodec,
                  "-shortest"),
              output);
        });
  }

  private static synchronized Path rpuLayoutPictures() throws Exception {
    return buildFixtureOnce(
        rpuLayoutSources.resolve("pictures.hevc"),
        output ->
            runFfmpeg(
                List.of(
                    "-f",
                    "lavfi",
                    "-i",
                    "testsrc2=size=64x36:rate=24",
                    "-t",
                    String.valueOf(PROFILE_5_PERIOD_SECONDS * PROFILE_5_SEGMENT_COUNT),
                    "-pix_fmt",
                    "yuv420p10le",
                    "-c:v",
                    "libx265",
                    "-x265-params",
                    "keyint=24:min-keyint=24:bframes=0:aud=1:log-level=error",
                    "-f",
                    "hevc"),
                output));
  }

  // Each access unit starts at its delimiter. The last picture's RPU has no next picture to
  // precede.
  private static byte[] withStandInRpus(byte[] pictures, RpuLayout layout) {
    var accessUnits = annexBAccessUnits(pictures);
    var withRpus = new ByteArrayOutputStream();
    for (var index = 0; index < accessUnits.size(); index++) {
      var accessUnit = accessUnits.get(index);
      if (layout == RpuLayout.BEHIND_THEIR_PICTURE) {
        withRpus.writeBytes(accessUnit);
        withRpus.writeBytes(STAND_IN_RPU);
        continue;
      }

      var delimiterEnd = accessUnitDelimiterEnd(accessUnit);
      withRpus.write(accessUnit, 0, delimiterEnd);
      if (layout == RpuLayout.AHEAD_OF_EVERY_PICTURE || index > 0) {
        withRpus.writeBytes(STAND_IN_RPU);
      }

      withRpus.write(accessUnit, delimiterEnd, accessUnit.length - delimiterEnd);
    }

    return withRpus.toByteArray();
  }

  private static List<byte[]> annexBAccessUnits(byte[] stream) {
    var starts = new ArrayList<Integer>();
    for (var offset = 0; offset + 4 < stream.length; offset++) {
      var startCode = stream[offset] == 0 && stream[offset + 1] == 0 && stream[offset + 2] == 1;
      if (startCode
          && hevcNalUnitType(Arrays.copyOfRange(stream, offset + 3, offset + 4))
              == ACCESS_UNIT_DELIMITER) {
        starts.add(offset > 0 && stream[offset - 1] == 0 ? offset - 1 : offset);
      }
    }

    assertThat(starts).as("access unit delimiters").isNotEmpty().startsWith(0);
    starts.add(stream.length);
    return IntStream.range(0, starts.size() - 1)
        .mapToObj(index -> Arrays.copyOfRange(stream, starts.get(index), starts.get(index + 1)))
        .toList();
  }

  // The delimiter is a start code, a two-byte NAL unit header and one byte of payload.
  private static int accessUnitDelimiterEnd(byte[] accessUnit) {
    var startCodeLength = accessUnit[2] == 1 ? 3 : 4;
    return startCodeLength + 3;
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

  // When FFmpeg's DASH muxer flushes each fragment, the mp4 muxer gives a fragment's first sample
  // the previous fragment's end time. After an open-GOP keyframe, that is the time of a leading
  // picture, decoded after the keyframe but shown before it, so two pictures share one
  // presentation time and none shows at the keyframe's. Chromium can then drop the fragment.
  @ParameterizedTest(name = "{0} source")
  @ValueSource(strings = {"mp4", "mkv", "ts"})
  @DisplayName(
      "Should keep every presentation time of the source when a worker copies open-GOP HEVC whose"
          + " keyframes have leading pictures")
  void shouldKeepEveryPresentationTimeOfTheSourceWhenAWorkerCopiesOpenGopHevc(String container)
      throws Exception {
    var source = openGopHevcSource(container);
    var sourcePackets = fromTheFirstKeyframe(videoPackets(source));
    assertThat(keyframesFollowedByLeadingPictures(sourcePackets))
        .as("keyframes after the first whose next picture in decode order shows before them")
        .isPositive();

    var copiedPackets = fromTheFirstKeyframe(videoPackets(copiedMedia(source, "hvc1.1.6.L30.90")));

    assertThat(copiedPackets)
        .extracting(VideoPacket::presentationTime)
        .doesNotHaveDuplicates()
        .usingElementComparator(WITHIN_PRINTED_ROUNDING)
        .containsExactlyElementsOf(presentationTimes(sourcePackets));
    assertThat(presentationTimes(keyframesOf(copiedPackets)))
        .usingElementComparator(WITHIN_PRINTED_ROUNDING)
        .containsExactlyElementsOf(presentationTimes(keyframesOf(sourcePackets)));
  }

  // libx265 at 29.97 fps, whose open GOPs make each keyframe after the first a CRA picture with
  // B-frames as leading pictures.
  private Path openGopHevcSource(String container) throws Exception {
    var source = root.resolve("open-gop-hevc." + container);
    runFfmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x36:rate=30000/1001:duration=" + SAMPLE_ENTRY_SOURCE_SECONDS,
            "-c:v",
            "libx265",
            "-preset",
            "ultrafast",
            "-pix_fmt",
            "yuv420p",
            "-x265-params",
            "keyint=10:min-keyint=10:scenecut=0:bframes=4:open-gop=1:log-level=error"),
        source);
    return source;
  }

  // The packets in decode order, timed from the first keyframe: the copy moves its whole timeline
  // by the source's frame reordering, which shifts every picture alike.
  private static List<VideoPacket> fromTheFirstKeyframe(List<VideoPacket> packets) {
    var firstKeyframe = packets.getFirst();
    assertThat(firstKeyframe.keyframe())
        .as("the first packet in decode order is a keyframe")
        .isTrue();
    return packets.stream()
        .map(packet -> packet.onTimelineStartingAt(firstKeyframe.presentationTime()))
        .toList();
  }

  private static long keyframesFollowedByLeadingPictures(List<VideoPacket> packets) {
    return IntStream.range(1, packets.size() - 1)
        .filter(index -> packets.get(index).keyframe())
        .filter(index -> packets.get(index + 1).showsBefore(packets.get(index)))
        .count();
  }

  private static List<VideoPacket> keyframesOf(List<VideoPacket> packets) {
    return packets.stream().filter(VideoPacket::keyframe).toList();
  }

  private static List<BigDecimal> presentationTimes(List<VideoPacket> packets) {
    return packets.stream().map(VideoPacket::presentationTime).toList();
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

  // The job carries the probed static values, as the server sends them for a copy with a PQ output
  // (ADR 0036). The locked FFmpeg writes neither message into the copy's record, so every message
  // there comes from the job.
  @Test
  @DisplayName(
      "Should deliver the job's static HDR metadata as SEI messages in the hvc1 record, keep it in"
          + " front of every keyframe and declare PQ when a worker copies HDR10 with a planned PQ"
          + " output")
  void
      shouldDeliverTheJobsStaticHdrMetadataInTheHvc1RecordAndKeepItInTheSamplesWhenAWorkerCopiesHdr10WithAPlannedPqOutput()
          throws Exception {
    var source = hdr10Source();
    var job = sampleEntryCopyJob(source, HDR10_CODEC_STRING);
    var video = job.getDecisionBuilder().getVideoBuilder();
    video.setOutputDynamicRange(
        VideoDynamicRange.newBuilder()
            .setCompatibleBase(CompatibleBase.COMPATIBLE_BASE_PQ)
            .setHdr10Plus(Presence.PRESENCE_ABSENT)
            .setDolbyVisionAbsent(DolbyVisionAbsent.getDefaultInstance()));
    HDR10_MASTERING_DISPLAY.writeTo(video.getMasteringDisplayBuilder());
    video
        .getContentLightLevelBuilder()
        .setMaxContentLightLevel(HDR10_CONTENT_LIGHT_LEVEL.maxContentLightLevel())
        .setMaxFrameAverageLightLevel(HDR10_CONTENT_LIGHT_LEVEL.maxPictureAverageLightLevel());

    var uploads = execute(engine(realCapabilities()), root, job.build());

    assertThat(uploads.names()).containsExactlyElementsOf(uploadNames(SAMPLE_ENTRY_SOURCE_SECONDS));
    var media = playable(uploads.contentOf(uploads.names()));
    assertDecodes(media);
    assertThat(videoStreamFields(media, "codec_tag_string,color_transfer"))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of("codec_tag_string", "hvc1", "color_transfer", "smpte2084"));
    var recordNalUnits = hevcRecordNalUnits(uploads.segments().get("init.mp4"));
    assertThat(recordNalUnits)
        .as("the NAL unit types of the delivered record")
        .extracting(WorkerMediaSmokeTest::hevcNalUnitType)
        .containsExactly(HEVC_VPS, HEVC_SPS, HEVC_PPS, HEVC_PREFIX_SEI, HEVC_PREFIX_SEI);
    var masteringDisplay = seiMessages(List.of(recordNalUnits.get(3)));
    var contentLightLevel = seiMessages(List.of(recordNalUnits.get(4)));
    assertThat(masteringDisplay).containsOnlyKeys(HEVC_MASTERING_DISPLAY_SEI);
    assertThat(MasteringDisplayColourVolume.of(masteringDisplay.get(HEVC_MASTERING_DISPLAY_SEI)))
        .isEqualTo(HDR10_MASTERING_DISPLAY);
    assertThat(contentLightLevel).containsOnlyKeys(HEVC_CONTENT_LIGHT_LEVEL_SEI);
    assertThat(ContentLightLevel.of(contentLightLevel.get(HEVC_CONTENT_LIGHT_LEVEL_SEI)))
        .isEqualTo(HDR10_CONTENT_LIGHT_LEVEL);
    for (var segment : uploads.names().subList(1, uploads.names().size())) {
      var bytes = uploads.segments().get(segment);
      assertThat(seiMessages(nalUnitsOfFirstPicture(bytes, firstSampleOffset(bytes))))
          .as("the SEI messages in front of the keyframe of %s", segment)
          .containsKeys(HEVC_MASTERING_DISPLAY_SEI, HEVC_CONTENT_LIGHT_LEVEL_SEI);
    }
  }

  // The filter removes the parameter sets (types 32 to 34) and prefix SEI messages (type 39) from
  // the samples of libx265's HDR10 stream, and keeps the record.
  private Path hdr10SourceWithStaticMetadataOnlyInItsRecord() throws Exception {
    var encoded = hdr10Source();
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

  // libx265's PQ stream with HDR10 static metadata, which it writes into the record and in front of
  // every keyframe.
  private Path hdr10Source() throws Exception {
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
    return encoded;
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

    // The contract names each primary; the SEI message lists them as green, blue and red.
    private void writeTo(MasteringDisplayColorVolume.Builder contract) {
      contract.getGreenBuilder().setX(primaries.get(0)).setY(primaries.get(1));
      contract.getBlueBuilder().setX(primaries.get(2)).setY(primaries.get(3));
      contract.getRedBuilder().setX(primaries.get(4)).setY(primaries.get(5));
      contract.getWhitePointBuilder().setX(whitePointX).setY(whitePointY);
      contract.setMaxLuminance((int) maxLuminance).setMinLuminance((int) minLuminance);
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
    return buildFixtureOnce(
        tenBitSources.resolve("ten-bit-sdr.mp4"),
        output ->
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
                output));
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
        .setOutputFrameRate(Fraction.newBuilder().setNumerator(24_000).setDenominator(1001))
        .setMediaSegmentCount(LONG_SOURCE_SECONDS);

    var uploads = execute(engine(softwareCapabilities()), source.getParent(), job.build());

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
        .setOutputFrameRate(
            Fraction.newBuilder().setNumerator(SCENE_CUT_SOURCE_FRAME_RATE).setDenominator(1))
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

  // The worker's arguments between "ffmpeg -y" and its pipe:1 output, without its -preset option
  // and without the framecrc of the source's streams that follows that output.
  private static List<String> svtAv1ArgumentsWithoutPreset(List<String> command) {
    var arguments = new ArrayList<>(command.subList(2, command.indexOf("pipe:1")));
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
    assertThat(run.uploads().segments())
        .as("the initialization segment")
        .containsEntry("init.mp4", Arrays.copyOf(run.ffmpegOutput(), ffmpegBoxes.get(1).end()));
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
          AudioInterval.ofDeliveredSegment(numberOf(name), SEEKABLE_SOURCE_AUDIO_PERIOD_TICKS);
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
          + " stream-copy replacement attempt that encodes its audio writes two keyframes into"
          + " FFmpeg's first fragment")
  void
      shouldUploadTheStartZeroAttemptsVideoPacketsWhenACopyReplacementWritesTwoKeyframesIntoFfmpegsFirstFragment()
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

    // The attempt reads from one period early. Its seek lands on the keyframe at 4.004 s while the
    // encoded audio starts just after 6 s, and FFmpeg writes nothing until every track has a
    // sample, so its first fragment holds the keyframe at 6.006 s too.
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

  // DVD-Video carries 24-bit PCM in MPEG-PS, and FFmpeg's DVD PCM decoder reads it in blocks of
  // four samples. The replacement attempt for segment 2 seeks 3 s into the source, one segment
  // before its first, so its decoder starts at the first audio packet after the seek, and the
  // attempt keeps the packets' timestamps. FLAC keeps every sample's value, so each delivered
  // sample must be the source sample of its own time.
  @Test
  @Disabled(
      "Red on the pinned FFmpeg, v8.1.3-2-streamarr.1. Its MPEG-PS muxer starts most audio packets"
          + " of this source inside a block of the DVD PCM decoder, so the attempt decodes noise"
          + " after its seek, and its demuxer stamps each packet up to 3.5 ms after its first"
          + " sample. Enable this test with the fork release that carries"
          + " streamarr/streamarr-ffmpeg#6 and streamarr/streamarr-ffmpeg#7.")
  @DisplayName(
      "Should deliver every source sample at its own time, from the first one, when a replacement"
          + " attempt for segment 2 encodes the 24-bit DVD PCM of an MPEG-PS source")
  void shouldDeliverEverySourceSampleAtItsOwnTimeWhenAReplacementEncodesDvdPcmOfAnMpegPsSource()
      throws Exception {
    var source = dvdPcmSource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    job.getDecisionBuilder().getVideoBuilder().setStreamIndex(firstStreamIndexOf(source, "v"));
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setCodec("flac")
        .setChannels(1)
        .setStreamIndex(firstStreamIndexOf(source, "a"))
        .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_MONO);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(SLOT_SECONDS)
        .setMediaSegmentCount(DVD_PCM_SOURCE_SEGMENT_COUNT)
        .setStartSequenceNumber(REPLACEMENT_START_SEQUENCE_NUMBER);

    var uploads = execute(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(uploads.names()).containsExactly("init.mp4", "segment2.m4s", "segment3.m4s");
    var delivered = TimedSamples.of(playable(uploads.contentOf(uploads.names())));
    var segmentStart = REPLACEMENT_START_SEQUENCE_NUMBER * SLOT_SECONDS * DVD_PCM_SAMPLE_RATE;
    var first = delivered.firstTime();
    assertThat(first)
        .as("the first delivered sample's time, less than one FLAC frame after segment 2's start")
        .isBetween(segmentStart, segmentStart + FLAC_FRAME_SAMPLES_AT_48_KHZ - 1);
    assertThat(delivered.notTheSourceSampleOfTheirTime())
        .as("delivered samples that are not the source sample of their own time")
        .isZero();
    assertThat(delivered.times())
        .as("the delivered samples' times: each time from the first to the source's end, once")
        .isEqualTo(IntStream.range(first, DVD_PCM_SOURCE_SAMPLES).toArray());
  }

  // 12 s of H.264 at 64x36 and 24 fps with a keyframe every 2 s and no B-frames, beside 12 s of
  // 24-bit mono DVD PCM at 48 kHz whose sample n holds n + 1, in the VOB that FFmpeg's MPEG-PS
  // muxer writes. Every decoded sample names the source sample it came from.
  private static synchronized Path dvdPcmSource() throws Exception {
    var source = seekableSources.resolve("counted-dvd-pcm.vob");
    if (generatedSeekableSources.contains(source)) {
      return source;
    }

    var samples = seekableSources.resolve("counted-dvd-pcm.s32le");
    var counted =
        ByteBuffer.allocate(DVD_PCM_SOURCE_SAMPLES * Integer.BYTES).order(ByteOrder.LITTLE_ENDIAN);
    IntStream.rangeClosed(1, DVD_PCM_SOURCE_SAMPLES).forEach(count -> counted.putInt(count << 8));
    Files.write(samples, counted.array());
    runFfmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc=size=64x36:rate=24:duration=" + DVD_PCM_SOURCE_SEGMENT_COUNT * SLOT_SECONDS,
            "-f",
            "s32le",
            "-ar",
            String.valueOf(DVD_PCM_SAMPLE_RATE),
            "-ac",
            "1",
            "-i",
            samples.toString(),
            "-c:v",
            "libx264",
            "-g",
            "48",
            "-bf",
            "0",
            "-c:a",
            "pcm_dvd",
            "-f",
            "vob"),
        source);
    generatedSeekableSources.add(source);
    return source;
  }

  // The index of the source's first stream of the type that an ffprobe stream specifier names. An
  // MPEG-PS demuxer numbers the streams in the order of their first packets.
  private static int firstStreamIndexOf(Path source, String streamType) throws Exception {
    var streams =
        ffprobe(
            List.of("-select_streams", streamType + ":0", "-show_entries", "stream=index"), source);
    return Integer.parseInt(streams.lines().findFirst().orElseThrow());
  }

  // The delivered audio's decoded samples and the time each one plays at, in samples: a decoded
  // frame's samples follow its presentation time.
  private static final class TimedSamples {

    private final int[] times;
    private final int[] values;

    private TimedSamples(int[] times, int[] values) {
      this.times = times;
      this.values = values;
    }

    static TimedSamples of(Path media) throws Exception {
      var decoded = media.resolveSibling(media.getFileName() + ".s32le");
      runFfmpeg(List.of("-i", media.toString(), "-map", "0:a:0", "-f", "s32le"), decoded);
      var buffer =
          ByteBuffer.wrap(Files.readAllBytes(decoded)).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
      var values = new int[buffer.remaining()];
      buffer.get(values);
      var times =
          ffprobe(List.of("-select_streams", "a:0", "-show_entries", "frame=pts,nb_samples"), media)
              .lines()
              .flatMapToInt(TimedSamples::timesOfFrame)
              .toArray();
      assertThat(times).as("the sample times of the decoded frames").hasSameSizeAs(values);
      return new TimedSamples(times, values);
    }

    // The times of the samples of the frame that ffprobe prints as "pts,nb_samples".
    private static IntStream timesOfFrame(String ptsAndSampleCount) {
      var fields = ptsAndSampleCount.split(",");
      var start = Integer.parseInt(fields[0]);
      return IntStream.range(start, start + Integer.parseInt(fields[1]));
    }

    int firstTime() {
      assertThat(times).as("the delivered samples").isNotEmpty();
      return times[0];
    }

    int[] times() {
      return times.clone();
    }

    // The counted source's sample at time n holds n + 1 in its upper 24 bits.
    long notTheSourceSampleOfTheirTime() {
      return IntStream.range(0, times.length)
          .filter(index -> values[index] != (times[index] + 1) << 8)
          .count();
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("losslessLayoutChanges")
  @DisplayName(
      "Should keep every source sample in its channel when a lossless encode renames the surround"
          + " pair or pads the layout")
  void shouldKeepEverySourceSampleInItsChannelWhenALosslessEncodeRenamesOrPadsTheLayout(
      LosslessLayoutChange change) throws Exception {
    var source = losslessLayoutSource(change.sourceLayout());
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    var output = SpeakerLayout.named(change.outputLayout());
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setCodec(change.codec())
        .setChannels(output.channelCount())
        .setChannelLayout(change.contractLayout());
    job.getExecutionBuilder().setMediaSegmentCount(1);

    var uploads = execute(engine(realCapabilities()), seekableSources, job.build());

    var delivered = root.resolve("delivered.mp4");
    Files.write(delivered, uploads.contentOf(uploadNames(1)));
    var sourceLayout = SpeakerLayout.named(change.sourceLayout());
    var sourceChannels = decodedChannels(source, sourceLayout.channelCount());
    var deliveredChannels = decodedChannels(delivered, output.channelCount());
    assertThat(deliveredChannels[0]).hasSameSizeAs(sourceChannels[0]);
    for (var index = 0; index < output.channelCount(); index++) {
      var speaker = output.speakers().get(index);
      var held = change.sources().get(speaker.name());
      if (held == null) {
        assertThat(deliveredChannels[index]).as("added channel %s", speaker).containsOnly(0);
        continue;
      }

      assertThat(deliveredChannels[index])
          .as("%s, which holds the source's %s", speaker, held)
          .isEqualTo(sourceChannels[sourceLayout.speakers().indexOf(Speaker.valueOf(held))]);
    }
  }

  @ParameterizedTest(name = "{0} at {1} bits")
  @CsvSource({"flac, 24", "alac, 16"})
  @DisplayName(
      "Should write the named rate and depth when a lossless encode steps 96 kHz down to 48 kHz")
  void shouldWriteTheNamedRateAndDepthWhenALosslessEncodeSteps96KhzDownTo48Khz(
      String codec, int bitDepth) throws Exception {
    var source = losslessSource("stereo", 96_000);
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setCodec(codec)
        .setChannels(2)
        .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO)
        .setOutputSampleRateHz(48_000)
        .setOutputBitDepth(bitDepth);
    job.getExecutionBuilder().setMediaSegmentCount(1);

    var uploads = execute(engine(realCapabilities()), seekableSources, job.build());

    var delivered = root.resolve("delivered.mp4");
    Files.write(delivered, uploads.contentOf(uploadNames(1)));
    assertThat(
            ffprobe(
                List.of("-select_streams", "a:0", "-show_entries", "stream=sample_rate"),
                delivered))
        .isEqualTo("48000");
    var samples = Arrays.stream(decodedChannels(delivered, 2)).flatMapToInt(Arrays::stream);
    var belowDepth = (1 << (Integer.SIZE - bitDepth)) - 1;
    assertThat(samples.filter(sample -> (sample & belowDepth) != 0).count())
        .as("samples with bits below %d", bitDepth)
        .isZero();
    var frameDurations =
        ffprobe(List.of("-select_streams", "a:0", "-show_entries", "packet=duration"), delivered)
            .lines()
            .toList();
    if ("flac".equals(codec)) {
      // ADR 0043: FLAC frames of the sample rate divided by 20; only the last may be shorter.
      assertThat(frameDurations.subList(0, frameDurations.size() - 1)).containsOnly("2400");
    }
  }

  @ParameterizedTest(name = "{0}-bit")
  @ValueSource(ints = {24, 16})
  @DisplayName(
      "Should keep every unsummed channel's samples and hold the summed pair under its ceiling"
          + " when FLAC writes 7.1 as 5.1")
  void
      shouldKeepEveryUnsummedChannelsSamplesAndHoldTheSummedPairUnderItsCeilingWhenFlacWrites71As51(
          int bitDepth) throws Exception {
    var noise = noiseSource("7.1", "white", "1.0");
    var source =
        bitDepth == 16
            ? reencodedAudio(noise, "s16", List.of("-c:a", "flac", "-sample_fmt", "s16"))
            : noise;
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setCodec("flac")
        .setChannels(6)
        .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE);
    job.getExecutionBuilder().setMediaSegmentCount(1);

    var uploads = execute(engine(realCapabilities()), seekableSources, job.build());

    var delivered = root.resolve("downmix-7.1-to-5.1-" + bitDepth + ".mp4");
    Files.write(delivered, uploads.contentOf(uploadNames(1)));
    var sourceChannels = decodedChannels(source, 8);
    var deliveredChannels = decodedChannels(delivered, 6);
    assertThat(deliveredChannels[0]).hasSameSizeAs(sourceChannels[0]);
    for (var channel = 0; channel < 4; channel++) {
      assertThat(deliveredChannels[channel])
          .as("unsummed channel %s", SpeakerLayout.named("5.1").speakers().get(channel))
          .isEqualTo(sourceChannels[channel]);
    }

    // 7.1 is FL FR FC LFE BL BR SL SR, so each 5.1 surround sums one back and one side channel.
    assertThat(peakOf(matrixSum(sourceChannels[4], sourceChannels[6]))).isGreaterThan(1.0);
    assertThat(peakOf(deliveredChannels[4])).isLessThan(MINUS_0_8_DBFS);
    assertThat(peakOf(deliveredChannels[5])).isLessThan(MINUS_0_8_DBFS);
  }

  @Test
  @DisplayName(
      "Should keep the channels that a pad adds silent when a lossless encode resamples and pads the"
          + " layout")
  void shouldKeepTheChannelsThatAPadAddsSilentWhenALosslessEncodeResamplesAndPadsTheLayout()
      throws Exception {
    var source = losslessSource("4.0", 96_000);
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    var output = SpeakerLayout.named("6.1");
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setCodec("flac")
        .setChannels(output.channelCount())
        .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE)
        .setOutputSampleRateHz(48_000);
    job.getExecutionBuilder().setMediaSegmentCount(1);

    var uploads = execute(engine(realCapabilities()), seekableSources, job.build());

    var delivered = root.resolve("delivered.mp4");
    Files.write(delivered, uploads.contentOf(uploadNames(1)));
    var deliveredChannels = decodedChannels(delivered, output.channelCount());
    for (var added : List.of(Speaker.LFE, Speaker.SL, Speaker.SR)) {
      assertThat(deliveredChannels[output.speakers().indexOf(added)])
          .as("added channel %s", added)
          .containsOnly(0);
    }

    assertThat(deliveredChannels[output.speakers().indexOf(Speaker.FL)])
        .isNotEqualTo(new int[deliveredChannels[0].length]);
  }

  @Test
  @DisplayName(
      "Should mix at the Lo/Ro levels that the first AC-3 frame signals when FLAC writes stereo")
  void shouldMixAtTheLoRoLevelsThatTheFirstAc3FrameSignalsWhenFlacWritesStereo() throws Exception {
    var source = ac3WithLoRoLevelsSource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setCodec("flac")
        .setChannels(2)
        .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO);
    job.getExecutionBuilder().setMediaSegmentCount(1);

    var uploads = execute(engine(realCapabilities()), seekableSources, job.build());

    var delivered = root.resolve("downmix-ac3-to-stereo.mp4");
    Files.write(delivered, uploads.contentOf(uploadNames(1)));
    // 5.1(side) is FL FR FC LFE SL SR; the track signals 0.5 for its centre and its surrounds.
    var sourceChannels = decodedChannels(source, 6, "-drc_scale", "0");
    var deliveredChannels = decodedChannels(delivered, 2);
    var left = loRoSum(sourceChannels[0], sourceChannels[2], sourceChannels[4]);
    var right = loRoSum(sourceChannels[1], sourceChannels[2], sourceChannels[5]);
    assertThat(relativeErrorOf(deliveredChannels[0], left)).isLessThan(0.01);
    assertThat(relativeErrorOf(deliveredChannels[1], right)).isLessThan(0.01);
  }

  private static final double MINUS_0_8_DBFS = 0.912;

  private static double[] matrixSum(int[] back, int[] side) {
    var sum = new double[back.length];
    for (var frame = 0; frame < back.length; frame++) {
      sum[frame] = Math.sqrt(0.5) * ((double) back[frame] + side[frame]) / FULL_SCALE;
    }

    return sum;
  }

  private static double[] loRoSum(int[] front, int[] centre, int[] surround) {
    var sum = new double[front.length];
    for (var frame = 0; frame < front.length; frame++) {
      sum[frame] = (front[frame] + 0.5 * centre[frame] + 0.5 * surround[frame]) / FULL_SCALE;
    }

    return sum;
  }

  private static final double FULL_SCALE = 2_147_483_648.0;

  private static double peakOf(double[] samples) {
    return Arrays.stream(samples).map(Math::abs).max().orElseThrow();
  }

  private static double peakOf(int[] samples) {
    return Arrays.stream(samples)
        .mapToDouble(sample -> Math.abs(sample / FULL_SCALE))
        .max()
        .orElseThrow();
  }

  // The RMS of the difference over the RMS of the expected samples.
  // AC-3 holds whole frames of 1,536 samples, so the delivered audio may end a frame earlier.
  private static double relativeErrorOf(int[] delivered, double[] expected) {
    assertThat(delivered.length).isCloseTo(expected.length, within(1_536));
    var error = 0.0;
    var signal = 0.0;
    for (var frame = 0; frame < Math.min(delivered.length, expected.length); frame++) {
      var difference = delivered[frame] / FULL_SCALE - expected[frame];
      error += difference * difference;
      signal += expected[frame] * expected[frame];
    }

    return Math.sqrt(error / signal);
  }

  // The seekable source's video, and AC-3 5.1(side) of quiet pink noise whose frames signal 0.5
  // for the Lo/Ro centre and surround levels.
  private static Path ac3WithLoRoLevelsSource() throws Exception {
    return reencodedAudio(
        noiseSource("5.1(side)", "pink", "0.2"),
        "ac3-loro",
        List.of(
            "-c:a",
            "ac3",
            "-b:a",
            "640k",
            "-dmix_mode",
            "loro",
            "-loro_cmixlev",
            "0.5",
            "-loro_surmixlev",
            "0.5"));
  }

  // The source's video, and its audio encoded with the options.
  private static synchronized Path reencodedAudio(
      Path original, String suffix, List<String> audioOptions) throws Exception {
    var name = original.getFileName().toString().replace(".mkv", "-" + suffix + ".mkv");
    var source = original.resolveSibling(name);
    if (generatedSeekableSources.contains(source)) {
      return source;
    }

    var arguments =
        new ArrayList<>(List.of("-i", original.toString(), "-map", "0", "-c:v", "copy"));
    arguments.addAll(audioOptions);
    runFfmpeg(arguments, source);
    generatedSeekableSources.add(source);
    return source;
  }

  // ADR 0050's downmix matrix: each source writes expressions into named channels of the seekable
  // source's audio, and every other channel is silent.
  private static final int MATRIX_SAMPLE_RATE = 48_000;
  private static final int MATRIX_SOURCE_SECONDS = 6;
  private static final int MATRIX_SEGMENT_SECONDS = 6;
  // Every tone below completes whole cycles in this window, so each reading excludes the others.
  private static final int MATRIX_READ_FROM = MATRIX_SAMPLE_RATE;
  private static final int MATRIX_READ_LENGTH = 4 * MATRIX_SAMPLE_RATE;
  private static final String DIALOGUE_TONE = "0.1*sin(2*PI*1000*t)";
  private static final double DIALOGUE_SIDE_DB = 20 * Math.log10(0.1 * Math.sqrt(0.5));
  private static final String LOUD_TONE = "0.501187*sin(2*PI*1000*t)";
  // A quarter of the sample rate at 45°, so that every sample lies 3 dB below the tone's peak and
  // a limiter that reads only samples lets the sum pass its ceiling between them.
  private static final String INTERSAMPLE_LOUD_TONE = "0.501187*sin(2*PI*12000*t+PI/4)";
  private static final String LFE_TONE = "0.1*sin(2*PI*50*t)";
  private static final double LOSSY_TONE_TOLERANCE_DB = 0.5;
  private static final double LOSSLESS_TONE_TOLERANCE_DB = 0.05;
  // A measured result for this tone, not a bound: each lossy codec at 192 kb/s decoded it near −3
  // dBTP, and a limiter that reads only samples let AAC through above 0 dBTP.
  private static final double LOSSY_192K_CEILING_DBTP = -2.0;
  // ADR 0050: the Annex 2 filter reads a FLAC or ALAC downmix no more than 0.01 dB above −1 dBTP.
  private static final double LOSSLESS_CEILING_DBTP = -1.0 + 0.01;
  private static final int MATRIX_SOURCE_SAMPLES = MATRIX_SOURCE_SECONDS * MATRIX_SAMPLE_RATE;
  private static final int CLICK_POSITION = 120_017;
  private static final int LAST_CLICK_POSITION = MATRIX_SOURCE_SAMPLES - 1;
  private static final double CLICK_AMPLITUDE = 0.5;
  private static final String CLICKS =
      CLICK_AMPLITUDE + "*(eq(n," + CLICK_POSITION + ")+eq(n," + LAST_CLICK_POSITION + "))";
  private static final String ONE_SIXTEEN_BIT_STEP = "1/32768";
  private static final double MIX_LEVEL_TOLERANCE_DB = 0.1;
  private static final Map<String, Integer> LEVEL_TONES =
      Map.of("FL", 250, "FR", 350, "FC", 450, "SL", 550, "SR", 650);

  /** A stereo or mono downmix in one codec, at the written depth when the job names one. */
  record Downmix(String codec, String layout, OptionalInt bitDepth) {

    static Downmix to(String codec, String layout) {
      return new Downmix(codec, layout, OptionalInt.empty());
    }

    Downmix atBits(int depth) {
      return new Downmix(codec, layout, OptionalInt.of(depth));
    }

    boolean isLossless() {
      return "flac".equals(codec) || "alac".equals(codec);
    }

    AudioChannelLayout contractLayout() {
      return "mono".equals(layout)
          ? AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_MONO
          : AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO;
    }

    @Override
    public String toString() {
      var depth = bitDepth.isPresent() ? " " + bitDepth.getAsInt() + "-bit" : "";
      return codec + depth + " " + layout;
    }
  }

  /**
   * FLAC audio in the layout at the depth, whose named channels hold FFmpeg expressions of time
   * {@code t} and sample number {@code n}.
   */
  @Builder
  record MatrixSource(
      String name, String layout, Map<String, String> channels, int bitDepth, int seconds) {}

  private static MatrixSource.MatrixSourceBuilder matrixSource(String name, String layout) {
    return MatrixSource.builder()
        .name(name)
        .layout(layout)
        .bitDepth(24)
        .seconds(MATRIX_SOURCE_SECONDS);
  }

  static Stream<Downmix> stereoDownmixes() {
    return Stream.of(
        Downmix.to("aac", "stereo"),
        Downmix.to("ac3", "stereo"),
        Downmix.to("eac3", "stereo"),
        Downmix.to("flac", "stereo").atBits(16),
        Downmix.to("alac", "stereo").atBits(16));
  }

  static Stream<Arguments> surroundSourcesAndStereoDownmixes() {
    return Stream.of("5.1", "7.1")
        .flatMap(layout -> stereoDownmixes().map(downmix -> Arguments.of(layout, downmix)));
  }

  @ParameterizedTest(name = "{0} to {1}")
  @MethodSource("surroundSourcesAndStereoDownmixes")
  @DisplayName(
      "Should write the dialogue at -3 dB on each side when a downmix to stereo carries a centre"
          + " tone")
  void shouldWriteTheDialogueAtMinus3DbOnEachSideWhenADownmixToStereoCarriesACentreTone(
      String layout, Downmix downmix) throws Exception {
    var source =
        matrixSourceFile(
            matrixSource("dialogue-" + layout, layout)
                .channels(Map.of("FC", DIALOGUE_TONE))
                .build());

    var delivered = decodedDownmix(source, downmix);

    for (var side = 0; side < 2; side++) {
      assertThat(decibels(toneAmplitude(delivered[side], 1000)))
          .as("side %d of the dialogue, dBFS", side)
          .isCloseTo(DIALOGUE_SIDE_DB, within(LOSSY_TONE_TOLERANCE_DB));
    }
  }

  @ParameterizedTest(name = "{0} to {1}")
  @MethodSource("surroundSourcesAndStereoDownmixes")
  @DisplayName(
      "Should decode no sample above full scale and hold the codec's ceiling when a downmix to"
          + " stereo sums a -6 dBFS tone in every channel")
  void shouldDecodeNoSampleAboveFullScaleAndHoldTheCodecsCeilingWhenADownmixSumsALoudTone(
      String layout, Downmix downmix) throws Exception {
    var source =
        matrixSourceFile(
            everyChannel("intersample-loud-" + layout, layout, INTERSAMPLE_LOUD_TONE).build());

    var delivered = decodedDownmix(source, downmix);

    var ceiling = downmix.isLossless() ? LOSSLESS_CEILING_DBTP : LOSSY_192K_CEILING_DBTP;
    for (var side = 0; side < 2; side++) {
      assertThat(samplePeak(delivered[side])).as("side %d's sample peak", side).isLessThan(1.0);
      assertThat(AnnexTwoMeter.truePeakDb(delivered[side]))
          .as("side %d's true peak, dBTP", side)
          .isLessThan(ceiling);
    }
  }

  @ParameterizedTest(name = "2.1 to {0}")
  @MethodSource("stereoDownmixes")
  @DisplayName(
      "Should write the LFE tone at -3 dB on both sides when a downmix steps 2.1 to stereo")
  void shouldWriteTheLfeToneAtMinus3DbOnBothSidesWhenADownmixSteps21ToStereo(Downmix downmix)
      throws Exception {
    var source =
        matrixSourceFile(matrixSource("lfe-2.1", "2.1").channels(Map.of("LFE", LFE_TONE)).build());

    var delivered = decodedDownmix(source, downmix);

    var tolerance = downmix.isLossless() ? LOSSLESS_TONE_TOLERANCE_DB : LOSSY_TONE_TOLERANCE_DB;
    for (var side = 0; side < 2; side++) {
      assertThat(decibels(toneAmplitude(delivered[side], 50)))
          .as("side %d of the LFE tone, dBFS", side)
          .isCloseTo(DIALOGUE_SIDE_DB, within(tolerance));
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"flac", "alac"})
  @DisplayName(
      "Should keep the limited tone a sine when a 16-bit source's channels sum above full scale"
          + " in the matrix")
  void shouldKeepTheLimitedToneASineWhenA16BitSourcesChannelsSumAboveFullScaleInTheMatrix(
      String codec) throws Exception {
    var source =
        matrixSourceFile(everyChannel("loud-16-bit", "5.1", LOUD_TONE).bitDepth(16).build());

    var delivered = decodedDownmix(source, Downmix.to(codec, "stereo").atBits(16));

    for (var side = 0; side < 2; side++) {
      assertThat(decibels(residualBesideTone(delivered[side], 1000)))
          .as("side %d's residual beside the limited tone, dB", side)
          .isLessThan(-40.0);
    }
  }

  @ParameterizedTest(name = "{0}-bit source")
  @ValueSource(ints = {16, 24})
  @DisplayName(
      "Should dither the summed side when a 16-bit FLAC downmix writes a level between two steps")
  void shouldDitherTheSummedSideWhenA16BitFlacDownmixWritesALevelBetweenTwoSteps(int bitDepth)
      throws Exception {
    var source =
        matrixSourceFile(
            matrixSource("one-step-surround-" + bitDepth, "5.1")
                .channels(Map.of("BL", ONE_SIXTEEN_BIT_STEP))
                .bitDepth(bitDepth)
                .build());
    var job = downmixJob(source, Downmix.to("flac", "stereo").atBits(16));

    var delivered = deliveredMedia(job, "one-step-surround-" + bitDepth);

    // 5.1 is FL FR FC LFE BL BR; the left side holds 0.7071 of one 16-bit step, which rounding
    // writes as 1 in every sample and TPDF dither spreads around it.
    var settled = 4_800;
    var left = decodedChannels(delivered, 2)[0];
    var steps = Arrays.stream(left, settled, left.length - settled).map(s -> s >> 16).toArray();
    assertThat(Arrays.stream(steps).distinct().count()).as("distinct steps").isGreaterThan(2);
    assertThat(Arrays.stream(steps).average().orElseThrow())
        .as("mean step")
        .isCloseTo(Math.sqrt(0.5), within(0.05));
  }

  static Stream<Downmix> clickDownmixes() {
    return Stream.of(
        Downmix.to("aac", "stereo"),
        Downmix.to("ac3", "stereo"),
        Downmix.to("eac3", "stereo"),
        Downmix.to("flac", "stereo"),
        Downmix.to("alac", "stereo"));
  }

  @ParameterizedTest(name = "5.1 to {0}")
  @MethodSource("clickDownmixes")
  @DisplayName(
      "Should keep each centre click at its sample position, the source's last sample included,"
          + " when a downmix steps 5.1 to stereo")
  void shouldKeepEachCentreClickAtItsSamplePositionWhenADownmixSteps51ToStereo(Downmix downmix)
      throws Exception {
    var source =
        matrixSourceFile(matrixSource("clicks-5.1", "5.1").channels(Map.of("FC", CLICKS)).build());

    var delivered =
        decodedFromMediaTimeZero(deliveredMedia(downmixJob(source, downmix), "clicks"), 2);

    var halfway = MATRIX_SOURCE_SAMPLES / 2;
    for (var side = 0; side < 2; side++) {
      assertThat(peakPosition(delivered[side], 0, halfway))
          .as("side %d's first click", side)
          .isEqualTo(CLICK_POSITION);
      assertThat(peakPosition(delivered[side], halfway, delivered[side].length))
          .as("side %d's click on the source's last sample", side)
          .isEqualTo(LAST_CLICK_POSITION);
    }

    // A lossy codec writes whole frames, so its last frame may run past the source's last sample.
    if (!downmix.isLossless()) {
      assertThat(delivered[0]).hasSizeGreaterThanOrEqualTo(MATRIX_SOURCE_SAMPLES);
      return;
    }

    assertThat(delivered[0]).hasSize(MATRIX_SOURCE_SAMPLES);
    for (var side = 0; side < 2; side++) {
      assertThat(
              new double[] {delivered[side][CLICK_POSITION], delivered[side][LAST_CLICK_POSITION]})
          .as("side %d's clicks", side)
          .containsExactly(
              new double[] {CLICK_AMPLITUDE * Math.sqrt(0.5), CLICK_AMPLITUDE * Math.sqrt(0.5)},
              within(1e-6));
    }
  }

  static Stream<Arguments> signalledLevelsOutputsAndStarts() {
    return Stream.of(
            new SignalledLevels("ac3", 0.594604, 0.5),
            new SignalledLevels("eac3", 0.594604, 0.5),
            new SignalledLevels("eac3", 0.5, MINUS_3_DB_LEVEL))
        .flatMap(
            levels ->
                Stream.of("stereo", "mono")
                    .flatMap(
                        layout ->
                            Stream.of(0, REPLACEMENT_START_SEQUENCE_NUMBER)
                                .map(start -> Arguments.of(levels, layout, start))));
  }

  private static final double MINUS_3_DB_LEVEL = 0.707107;

  /**
   * The Lo/Ro levels that an AC-3 or E-AC-3 track signals. FFmpeg's E-AC-3 encoder writes no centre
   * or surround mix level from center_mixlev or surround_mixlev, so an E-AC-3 track signals them
   * through its Lo/Ro levels.
   */
  record SignalledLevels(String codec, double centre, double surround) {

    List<String> encoderOptions() {
      var centreLevel = String.valueOf(centre);
      var surroundLevel = String.valueOf(surround);
      if ("ac3".equals(codec)) {
        return List.of("-center_mixlev", centreLevel, "-surround_mixlev", surroundLevel);
      }

      return List.of(
          "-dmix_mode", "loro", "-loro_cmixlev", centreLevel, "-loro_surmixlev", surroundLevel);
    }

    // Levels that differ from these in both the centre and the surrounds.
    SignalledLevels others() {
      return new SignalledLevels(codec, otherThan(centre), otherThan(surround));
    }

    private static double otherThan(double level) {
      return Double.compare(level, MINUS_3_DB_LEVEL) == 0 ? 0.5 : MINUS_3_DB_LEVEL;
    }

    @Override
    public String toString() {
      return String.format(Locale.ROOT, "%s centre %.3f surround %.3f", codec, centre, surround);
    }
  }

  @ParameterizedTest(name = "{0}, {1}, from segment {2}")
  @MethodSource("signalledLevelsOutputsAndStarts")
  @DisplayName(
      "Should mix the centre and surrounds at the levels that the first frame signals when an"
          + " attempt downmixes AC-3 or E-AC-3 from the start or from a seek")
  void shouldMixTheCentreAndSurroundsAtTheSignalledLevelsWhenAnAttemptDownmixesFromAStartOrSeek(
      SignalledLevels levels, String layout, int startSequenceNumber) throws Exception {
    var source = signalledLevelsSource(levels);
    var job = downmixJob(source, Downmix.to("flac", layout));
    job.getExecutionBuilder()
        .setMediaSegmentCount(SEEKABLE_SOURCE_SECONDS / MATRIX_SEGMENT_SECONDS)
        .setStartSequenceNumber(startSequenceNumber);

    var deliveredFile = deliveredMedia(job, "levels-" + layout + "-" + startSequenceNumber);

    var decoder = List.of("-drc_scale", "0", "-target_level", "-31");
    var sourceChannels = decodedFloatChannels(source, 6, decoder);
    var outputChannels = "mono".equals(layout) ? 1 : 2;
    var delivered = decodedFloatChannels(deliveredFile, outputChannels, List.of());
    var expected = expectedLevelGains(levels, layout);
    for (var output = 0; output < outputChannels; output++) {
      var gains = toneGains(delivered[output], sourceChannels);
      for (var speaker : LEVEL_TONES.keySet()) {
        assertToneGain(
            speaker + " in output channel " + output,
            gains.get(speaker),
            expected.get(output).getOrDefault(speaker, 0.0));
      }
    }
  }

  // Each source channel's tone in the output channel, over the same tone in the source channel.
  private static Map<String, Double> toneGains(double[] output, double[][] sourceChannels) {
    var sourceSpeakers = SpeakerLayout.named("5.1(side)").speakers();
    var gains = new HashMap<String, Double>();
    LEVEL_TONES.forEach(
        (speaker, frequency) ->
            gains.put(
                speaker,
                toneAmplitude(output, frequency)
                    / toneAmplitude(
                        sourceChannels[sourceSpeakers.indexOf(Speaker.valueOf(speaker))],
                        frequency)));
    return gains;
  }

  // A tone that the matrix leaves out of the channel must read at least 40 dB below its source.
  private static void assertToneGain(String tone, double gain, double expectedGain) {
    if (expectedGain == 0.0) {
      assertThat(gain).as(tone).isLessThan(0.01);
      return;
    }

    assertThat(decibels(gain))
        .as("%s, dB", tone)
        .isCloseTo(decibels(expectedGain), within(MIX_LEVEL_TOLERANCE_DB));
  }

  // ADR 0050's Lo/Ro matrix at the signalled levels; mono is 0.7071 × (left + right).
  private static List<Map<String, Double>> expectedLevelGains(
      SignalledLevels levels, String layout) {
    var left = Map.of("FL", 1.0, "FC", levels.centre(), "SL", levels.surround());
    var right = Map.of("FR", 1.0, "FC", levels.centre(), "SR", levels.surround());
    if ("stereo".equals(layout)) {
      return List.of(left, right);
    }

    var mono = new HashMap<String, Double>();
    left.forEach((speaker, gain) -> mono.merge(speaker, Math.sqrt(0.5) * gain, Double::sum));
    right.forEach((speaker, gain) -> mono.merge(speaker, Math.sqrt(0.5) * gain, Double::sum));
    return List.of(mono);
  }

  // The seekable source's video, and an AC-3 or E-AC-3 5.1(side) track of 18 s that holds a tone
  // of its own in each channel except the LFE. Its frames signal the levels up to the replacement
  // attempt's segment and other levels from there on, so an attempt that read the levels where it
  // seeks would mix differently from an attempt from the start.
  private static synchronized Path signalledLevelsSource(SignalledLevels levels) throws Exception {
    var name =
        String.format(
            Locale.ROOT, "%s-c%.3f-s%.3f", levels.codec(), levels.centre(), levels.surround());
    var source = seekableSources.resolve("matrix-levels-" + name + ".mkv");
    if (generatedSeekableSources.contains(source)) {
      return source;
    }

    var tones = new HashMap<String, String>();
    LEVEL_TONES.forEach(
        (speaker, frequency) -> tones.put(speaker, "0.1*sin(2*PI*" + frequency + "*t)"));
    var tonal =
        matrixSourceFile(
            matrixSource("level-tones", "5.1(side)")
                .channels(tones)
                .seconds(SEEKABLE_SOURCE_SECONDS)
                .build());
    var seek = String.valueOf(REPLACEMENT_START_SEQUENCE_NUMBER * MATRIX_SEGMENT_SECONDS);
    var head = seekableSources.resolve(name + "-head." + levels.codec());
    runFfmpeg(levelsEncode(List.of("-i", tonal.toString(), "-t", seek), levels), head);
    var tail = seekableSources.resolve(name + "-tail." + levels.codec());
    runFfmpeg(levelsEncode(List.of("-ss", seek, "-i", tonal.toString()), levels.others()), tail);
    runFfmpeg(
        List.of(
            "-i",
            tonal.toString(),
            "-i",
            "concat:" + head + "|" + tail,
            "-map",
            "0:v",
            "-map",
            "1:a",
            "-c",
            "copy"),
        source);
    generatedSeekableSources.add(source);
    return source;
  }

  // The input's audio as a raw AC-3 or E-AC-3 stream whose frames signal the levels.
  private static List<String> levelsEncode(List<String> input, SignalledLevels levels) {
    var arguments = new ArrayList<>(input);
    arguments.addAll(List.of("-map", "0:a", "-c:a", levels.codec(), "-b:a", "640k"));
    arguments.addAll(levels.encoderOptions());
    arguments.addAll(List.of("-f", levels.codec()));
    return arguments;
  }

  private static MatrixSource.MatrixSourceBuilder everyChannel(
      String name, String layout, String tone) {
    var channels = new HashMap<String, String>();
    SpeakerLayout.named(layout).speakers().forEach(s -> channels.put(s.name(), tone));
    return matrixSource(name, layout).channels(channels);
  }

  private static synchronized Path matrixSourceFile(MatrixSource spec) throws Exception {
    var source = seekableSources.resolve("matrix-" + spec.name() + ".mkv");
    if (generatedSeekableSources.contains(source)) {
      return source;
    }

    var speakers = SpeakerLayout.named(spec.layout()).speakers();
    var arguments = new ArrayList<>(List.of("-f", "lavfi", "-i", "testsrc=size=64x36:rate=24"));
    var joined = new StringBuilder();
    for (var channel = 0; channel < speakers.size(); channel++) {
      var expression = spec.channels().getOrDefault(speakers.get(channel).name(), "0");
      arguments.addAll(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "aevalsrc=exprs='" + expression + "':sample_rate=" + MATRIX_SAMPLE_RATE));
      joined.append("[").append(channel + 1).append(":a]");
    }

    // Without a map, join places each mono input, whose one channel is FC, by that name first.
    var map =
        IntStream.range(0, speakers.size())
            .mapToObj(channel -> channel + ".0-" + speakers.get(channel))
            .collect(Collectors.joining("|"));
    joined
        .append("join=inputs=")
        .append(speakers.size())
        .append(":channel_layout=")
        .append(spec.layout())
        .append(":map=")
        .append(map);
    arguments.addAll(
        List.of(
            "-filter_complex",
            joined + "[audio]",
            "-map",
            "0:v",
            "-map",
            "[audio]",
            "-t",
            String.valueOf(spec.seconds()),
            "-c:v",
            "libx264",
            "-g",
            "48",
            "-c:a",
            "flac",
            "-sample_fmt",
            spec.bitDepth() == 16 ? "s16" : "s32"));
    runFfmpeg(arguments, source);
    generatedSeekableSources.add(source);
    return source;
  }

  private static VariantJob.Builder downmixJob(Path source, Downmix downmix) {
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    var audio =
        job.getDecisionBuilder()
            .getAudioBuilder()
            .setCodec(downmix.codec())
            .setChannels(SpeakerLayout.named(downmix.layout()).channelCount())
            .setChannelLayout(downmix.contractLayout())
            .setBitrateBitsPerSecond(192_000);
    downmix.bitDepth().ifPresent(audio::setOutputBitDepth);
    job.getExecutionBuilder().setMediaSegmentCount(1);
    return job;
  }

  // Every upload of the job, as one file.
  private Path deliveredMedia(VariantJob.Builder job, String name) throws Exception {
    var uploads = execute(engine(realCapabilities()), seekableSources, job.build());
    var execution = job.getExecution();
    assertThat(uploads.names())
        .containsExactlyElementsOf(
            Stream.concat(
                    Stream.of("init.mp4"),
                    IntStream.range(
                            execution.getStartSequenceNumber(), execution.getMediaSegmentCount())
                        .mapToObj(number -> "segment" + number + ".m4s"))
                .toList());
    var delivered = root.resolve(name + "-" + job.getDecision().getAudio().getCodec() + ".mp4");
    Files.write(delivered, uploads.contentOf(uploads.names()));
    return delivered;
  }

  private double[][] decodedDownmix(Path source, Downmix downmix) throws Exception {
    var delivered = deliveredMedia(downmixJob(source, downmix), "downmix");
    return decodedFloatChannels(
        delivered, SpeakerLayout.named(downmix.layout()).channelCount(), List.of());
  }

  // Each channel of the file's first audio stream in 64-bit floating point, which keeps a decoded
  // sample above full scale.
  private double[][] decodedFloatChannels(Path file, int channels, List<String> decoderOptions)
      throws Exception {
    var arguments = new ArrayList<>(decoderOptions);
    arguments.addAll(List.of("-i", file.toString(), "-map", "0:a:0"));
    return decodedFloat(arguments, channels, file.getFileName() + ".f64");
  }

  // The decoded samples from media time zero on: the worker writes a lossy encoder's priming
  // before time zero, which -copyts keeps there for atrim to remove.
  private double[][] decodedFromMediaTimeZero(Path file, int channels) throws Exception {
    return decodedFloat(
        List.of("-copyts", "-i", file.toString(), "-map", "0:a:0", "-af", "atrim=start=0"),
        channels,
        file.getFileName() + ".from-zero.f64");
  }

  private double[][] decodedFloat(List<String> arguments, int channels, String name)
      throws Exception {
    var pcm = root.resolve(name);
    var command = new ArrayList<>(arguments);
    command.addAll(List.of("-c:a", "pcm_f64le", "-f", "f64le"));
    runFfmpeg(command, pcm);
    var samples =
        ByteBuffer.wrap(Files.readAllBytes(pcm)).order(ByteOrder.LITTLE_ENDIAN).asDoubleBuffer();
    var frames = samples.remaining() / channels;
    var decoded = new double[channels][frames];
    for (var frame = 0; frame < frames; frame++) {
      for (var channel = 0; channel < channels; channel++) {
        decoded[channel][frame] = samples.get();
      }
    }

    return decoded;
  }

  // The tone's peak amplitude over the read window, from its in-phase and quadrature sums.
  private static double toneAmplitude(double[] samples, int frequency) {
    var fit = ToneFit.of(samples, frequency);
    return Math.hypot(fit.cosine(), fit.sine());
  }

  // The RMS of the read window once the fitted tone is removed, over the tone's RMS.
  private static double residualBesideTone(double[] samples, int frequency) {
    var fit = ToneFit.of(samples, frequency);
    var residual = 0.0;
    for (var n = MATRIX_READ_FROM; n < MATRIX_READ_FROM + MATRIX_READ_LENGTH; n++) {
      var phase = 2 * Math.PI * frequency * n / MATRIX_SAMPLE_RATE;
      var difference = samples[n] - fit.cosine() * Math.cos(phase) - fit.sine() * Math.sin(phase);
      residual += difference * difference;
    }

    var toneRms = Math.hypot(fit.cosine(), fit.sine()) * Math.sqrt(0.5);
    return Math.sqrt(residual / MATRIX_READ_LENGTH) / toneRms;
  }

  private record ToneFit(double cosine, double sine) {

    static ToneFit of(double[] samples, int frequency) {
      assertThat(samples).hasSizeGreaterThanOrEqualTo(MATRIX_READ_FROM + MATRIX_READ_LENGTH);
      var cosine = 0.0;
      var sine = 0.0;
      for (var n = MATRIX_READ_FROM; n < MATRIX_READ_FROM + MATRIX_READ_LENGTH; n++) {
        var phase = 2 * Math.PI * frequency * n / MATRIX_SAMPLE_RATE;
        cosine += samples[n] * Math.cos(phase);
        sine += samples[n] * Math.sin(phase);
      }

      return new ToneFit(2 * cosine / MATRIX_READ_LENGTH, 2 * sine / MATRIX_READ_LENGTH);
    }
  }

  private static double samplePeak(double[] samples) {
    return Arrays.stream(samples).map(Math::abs).max().orElseThrow();
  }

  private static int peakPosition(double[] samples, int from, int to) {
    return IntStream.range(from, to)
        .reduce((a, b) -> Math.abs(samples[b]) > Math.abs(samples[a]) ? b : a)
        .orElseThrow();
  }

  private static double decibels(double linear) {
    return 20 * Math.log10(linear);
  }

  @Test
  @DisplayName(
      "Should dither the summed surrounds and keep every other channel's samples when FLAC writes"
          + " 24-bit 7.1 as 5.1")
  void shouldDitherTheSummedSurroundsAndKeepEveryOtherChannelsSamplesWhenFlacWrites24Bit71As51()
      throws Exception {
    var source = surroundsAtOneStepSource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setCodec("flac")
        .setChannels(6)
        .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE);
    job.getExecutionBuilder().setMediaSegmentCount(1);

    var uploads = execute(engine(realCapabilities()), seekableSources, job.build());

    var delivered = root.resolve("downmix-24-bit-7.1-to-5.1.mp4");
    Files.write(delivered, uploads.contentOf(uploadNames(1)));
    var sourceChannels = decodedChannels(source, 8);
    var deliveredChannels = decodedChannels(delivered, 6);
    for (var channel = 0; channel < 4; channel++) {
      assertThat(deliveredChannels[channel])
          .as("unsummed channel %s", SpeakerLayout.named("5.1").speakers().get(channel))
          .isEqualTo(sourceChannels[channel]);
    }

    // Each surround sums a back and a side channel that hold one 24-bit step: 0.7071 × 2 = 1.414
    // steps, which truncation writes as 1 in every sample and TPDF dither spreads around it.
    var settled = 4_800;
    for (var channel = 4; channel < 6; channel++) {
      var steps =
          Arrays.stream(
                  deliveredChannels[channel], settled, deliveredChannels[channel].length - settled)
              .map(sample -> sample >> 8)
              .toArray();
      assertThat(Arrays.stream(steps).distinct().count()).as("distinct steps").isGreaterThan(2);
      assertThat(Arrays.stream(steps).average().orElseThrow())
          .as("mean step")
          .isCloseTo(Math.sqrt(2) - 0.5, within(0.05));
    }
  }

  // The seekable source's video, and 24-bit FLAC 7.1 whose front channels hold pink noise and
  // whose back and side channels hold a constant of one 24-bit step.
  private static synchronized Path surroundsAtOneStepSource() throws Exception {
    var source = seekableSources.resolve("lossless-7.1-surrounds-at-one-step.mkv");
    if (generatedSeekableSources.contains(source)) {
      return source;
    }

    var arguments = new ArrayList<>(List.of("-f", "lavfi", "-i", "testsrc=size=64x36:rate=24"));
    var joined = new StringBuilder();
    for (var channel = 0; channel < 8; channel++) {
      var input =
          channel < 4
              ? "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5:seed=" + (channel + 11)
              : "aevalsrc=exprs=1/8388608:sample_rate=48000";
      arguments.addAll(List.of("-f", "lavfi", "-i", input));
      joined.append("[").append(channel + 1).append(":a]");
    }

    joined.append("join=inputs=8:channel_layout=7.1");
    arguments.addAll(
        List.of(
            "-filter_complex",
            joined + "[audio]",
            "-map",
            "0:v",
            "-map",
            "[audio]",
            "-t",
            "6",
            "-c:v",
            "libx264",
            "-g",
            "48",
            "-c:a",
            "flac",
            "-sample_fmt",
            "s32"));
    runFfmpeg(arguments, source);
    generatedSeekableSources.add(source);
    return source;
  }

  static Stream<LosslessLayoutChange> losslessLayoutChanges() {
    return Stream.of(
        LosslessLayoutChange.builder()
            .sourceLayout("6.1")
            .codec("alac")
            .outputLayout("6.1(back)")
            .contractLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE_BACK)
            .sources(
                Map.of(
                    "FL", "FL", "FR", "FR", "FC", "FC", "LFE", "LFE", "BL", "SL", "BR", "SR", "BC",
                    "BC"))
            .build(),
        LosslessLayoutChange.builder()
            .sourceLayout("5.1")
            .codec("flac")
            .outputLayout("5.1(side)")
            .contractLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE_SIDE)
            .sources(
                Map.of("FL", "FL", "FR", "FR", "FC", "FC", "LFE", "LFE", "SL", "BL", "SR", "BR"))
            .build(),
        LosslessLayoutChange.builder()
            .sourceLayout("4.0")
            .codec("flac")
            .outputLayout("6.1")
            .contractLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_SIX_POINT_ONE)
            .sources(Map.of("FL", "FL", "FR", "FR", "FC", "FC", "BC", "BC"))
            .build(),
        LosslessLayoutChange.builder()
            .sourceLayout("quad")
            .codec("alac")
            .outputLayout("5.0")
            .contractLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO)
            .sources(Map.of("FL", "FL", "FR", "FR", "BL", "BL", "BR", "BR"))
            .build());
  }

  /**
   * A lossless encode from a source layout to an output layout, and the source channel that each
   * output channel holds; an output channel that the map leaves out is padding.
   */
  @Builder
  record LosslessLayoutChange(
      String sourceLayout,
      String codec,
      String outputLayout,
      AudioChannelLayout contractLayout,
      Map<String, String> sources) {
    @Override
    public String toString() {
      return sourceLayout + " to " + codec + " " + outputLayout;
    }
  }

  // The seekable source's video, and 24-bit FLAC in the layout whose channels each hold noise of
  // their own seed, so that a moved channel cannot match its place.
  private static Path losslessLayoutSource(String layout) throws Exception {
    return noiseSource(layout, "pink", "0.5");
  }

  private static Path losslessSource(String layout, int sampleRate) throws Exception {
    return noiseSource(layout, "pink", "0.5", sampleRate);
  }

  private static Path noiseSource(String layout, String color, String amplitude) throws Exception {
    return noiseSource(layout, color, amplitude, 48_000);
  }

  private static synchronized Path noiseSource(
      String layout, String color, String amplitude, int sampleRate) throws Exception {
    var source =
        seekableSources.resolve(
            "lossless-"
                + layout.replaceAll("[()]", "_")
                + "-"
                + color
                + amplitude
                + "-"
                + sampleRate
                + ".mkv");
    if (generatedSeekableSources.contains(source)) {
      return source;
    }

    var channels = SpeakerLayout.named(layout).channelCount();
    var arguments = new ArrayList<>(List.of("-f", "lavfi", "-i", "testsrc=size=64x36:rate=24"));
    var joined = new StringBuilder();
    for (var channel = 0; channel < channels; channel++) {
      arguments.addAll(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "anoisesrc=color="
                  + color
                  + ":sample_rate="
                  + sampleRate
                  + ":amplitude="
                  + amplitude
                  + ":seed="
                  + (channel + 11)));
      joined.append("[").append(channel + 1).append(":a]");
    }

    joined.append("join=inputs=").append(channels).append(":channel_layout=").append(layout);
    arguments.addAll(
        List.of(
            "-filter_complex",
            joined + "[audio]",
            "-map",
            "0:v",
            "-map",
            "[audio]",
            "-t",
            "6",
            "-c:v",
            "libx264",
            "-g",
            "48",
            "-c:a",
            "flac",
            "-sample_fmt",
            "s32"));
    runFfmpeg(arguments, source);
    generatedSeekableSources.add(source);
    return source;
  }

  // Each channel of the file's first audio stream, decoded to 32-bit samples in FFmpeg's order.
  private int[][] decodedChannels(Path file, int channels, String... decoderOptions)
      throws Exception {
    var pcm = root.resolve(file.getFileName() + ".s32");
    var arguments = new ArrayList<>(List.of(decoderOptions));
    arguments.addAll(List.of("-i", file.toString(), "-map", "0:a:0", "-f", "s32le"));
    runFfmpeg(arguments, pcm);
    var samples = ByteBuffer.wrap(Files.readAllBytes(pcm)).order(ByteOrder.LITTLE_ENDIAN);
    var frames = samples.remaining() / Integer.BYTES / channels;
    var decoded = new int[channels][frames];
    for (var frame = 0; frame < frames; frame++) {
      for (var channel = 0; channel < channels; channel++) {
        decoded[channel][frame] = samples.getInt();
      }
    }

    return decoded;
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("layoutToneEncodes")
  @DisplayName(
      "Should place each source tone in each output channel at ADR 0050's gain and in the source's"
          + " phase when an encode keeps, steps or folds the source's layout as the job names")
  void shouldPlaceEachSourceToneAtAdr0050sGainWhenAnEncodeKeepsStepsOrFoldsTheLayout(
      ToneEncode encode) throws Exception {
    var delivered = deliveredToneEncode(encode);

    assertTonesLandAtTheirGains(encode, delivered);
  }

  // Each 3- and 4-channel layout kept or stepped to stereo, 7.0 folded to 5.0, and 6.1 to 5.1.
  static Stream<ToneEncode> layoutToneEncodes() {
    return Stream.of(
            keptThreeAndFourChannelLayouts(),
            threeAndFourChannelStereoSteps(),
            sevenPointZeroFolds(),
            sixPointOneEncodes())
        .flatMap(encodes -> encodes);
  }

  static Stream<ToneEncode> keptThreeAndFourChannelLayouts() {
    var aac = Stream.of("3.0", "4.0").map(layout -> keptLayout(layout, "aac").build());
    var dolbyDigital =
        Stream.of("ac3", "eac3")
            .flatMap(
                codec ->
                    Stream.concat(
                        Stream.of("2.1", "3.0", "4.0")
                            .map(layout -> keptLayout(layout, codec).build()),
                        Arrays.stream(ToneSet.values())
                            .flatMap(
                                tones ->
                                    Stream.of(
                                        quadAsQuadSide(codec).tones(tones).build(),
                                        keptLayout("quad(side)", codec).tones(tones).build()))));
    return Stream.concat(aac, dolbyDigital);
  }

  // Of these layouts, AAC carries only 3.0 and 4.0, and AC-3 and E-AC-3 carry all but 3.0(back)
  // and 3.1 (ADR 0050), so the job names stereo for the rest.
  static Stream<ToneEncode> threeAndFourChannelStereoSteps() {
    var aac =
        Stream.of("2.1", "3.0(back)", "3.1").map(layout -> steppedToStereo(layout, "aac").build());
    var aacQuads =
        Arrays.stream(ToneSet.values())
            .flatMap(
                tones ->
                    Stream.of("quad", "quad(side)")
                        .map(layout -> steppedToStereo(layout, "aac").tones(tones).build()));
    var dolbyDigital =
        Stream.of("ac3", "eac3")
            .flatMap(
                codec ->
                    Stream.of("3.0(back)", "3.1")
                        .map(layout -> steppedToStereo(layout, codec).build()));
    return Stream.of(aac, aacQuads, dolbyDigital).flatMap(encodes -> encodes);
  }

  static Stream<ToneEncode> sevenPointZeroFolds() {
    return Stream.of(
        sevenPointZeroFold("aac", "5.0"),
        sevenPointZeroFold("ac3", "5.0(side)"),
        sevenPointZeroFold("eac3", "5.0(side)"),
        sevenPointZeroFold("opus", "5.0"));
  }

  @Test
  @DisplayName(
      "Should keep each 7.0 tone in its own channel and decode the added low-frequency channel as"
          + " silence when Opus pads 7.0 to 7.1")
  void shouldKeepEach70ToneInItsOwnChannelAndDecodeSilentLfeWhenOpusPads70To71() throws Exception {
    var encode =
        ToneEncode.builder()
            .sourceLayout("7.0")
            .tones(ToneSet.OWN_TONE_IN_EACH_CHANNEL)
            .codec("opus")
            .outputLayout("7.1")
            .decodedLayout("7.1")
            .routes(keptRoutes("7.0"))
            .build();

    var delivered = deliveredToneEncode(encode);

    assertTonesLandAtTheirGains(encode, delivered);
    assertThat(decodedChannelFromZero(delivered, "LFE"))
        .as("the added low-frequency channel")
        .containsOnly(0);
  }

  static Stream<ToneEncode> sixPointOneEncodes() {
    return Stream.of(
        sixPointOneTo51("ac3", "5.1(side)"),
        sixPointOneTo51("eac3", "5.1(side)"),
        sixPointOneTo51("aac", "5.1"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("aacToneEncodes")
  @DisplayName(
      "Should write the AAC channel configuration of the channel count, never 0, when AAC encodes"
          + " a named layout")
  void shouldWriteTheAacChannelConfigurationOfTheChannelCountWhenAacEncodesANamedLayout(
      ToneEncode encode) throws Exception {
    var delivered = deliveredToneEncode(encode);

    assertThat(aacChannelConfiguration(delivered))
        .isEqualTo(LAYOUT_CHANNELS.get(encode.outputLayout()).size());
  }

  static Stream<ToneEncode> aacToneEncodes() {
    return layoutToneEncodes().filter(encode -> "aac".equals(encode.codec()));
  }

  // ADR 0050's −3 dB, at which a downmix mixes one channel into another.
  private static final double MINUS_3_DB = Math.sqrt(0.5);
  // Every tone peaks at −20 dBFS, so that no sum of the matrix reaches the lossy limiter's −3 dBFS.
  private static final double TONE_AMPLITUDE = 0.1;
  // A tone that a channel does not hold reads at least 40 dB below the source's tones.
  private static final double ABSENT_TONE_AMPLITUDE = TONE_AMPLITUDE / 100;
  private static final double WITHIN_1_DB = Math.pow(10, 1.0 / 20);
  // A tone keeps the source's phase, so a tone that arrives in quadrature, or from the other side
  // of an opposite-phase pair, fails.
  private static final double MOST_DEGREES_FROM_THE_SOURCES_PHASE = 10;
  private static final int TONE_SAMPLE_RATE = 48_000;
  // Each tone is read over whole seconds after each codec's start-up, so that the tones, all at
  // whole hertz, do not read into each other.
  private static final int TONE_READ_FROM_SECOND = 1;
  private static final int TONE_READ_SECONDS = 4;

  private static final Map<String, AudioChannelLayout> CONTRACT_LAYOUTS =
      Map.ofEntries(
          Map.entry("stereo", AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO),
          Map.entry("2.1", AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_TWO_POINT_ONE),
          Map.entry("3.0", AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_THREE_POINT_ZERO),
          Map.entry("4.0", AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FOUR_POINT_ZERO),
          Map.entry("quad", AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_QUAD),
          Map.entry("quad(side)", AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_QUAD_SIDE),
          Map.entry("5.0", AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO),
          Map.entry("5.0(side)", AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ZERO_SIDE),
          Map.entry("5.1", AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE),
          Map.entry("5.1(side)", AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_FIVE_POINT_ONE_SIDE),
          Map.entry("7.1", AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_SEVEN_POINT_ONE));

  // The channels of each layout that these tests name, by the names that `ffmpeg -layouts` lists.
  // The tests address every channel by its name, through join and pan, so this order does not
  // matter and the worker's own channel order cannot hide a moved channel.
  private static final Map<String, List<String>> LAYOUT_CHANNELS =
      Map.ofEntries(
          Map.entry("stereo", List.of("FL", "FR")),
          Map.entry("2.1", List.of("FL", "FR", "LFE")),
          Map.entry("3.0", List.of("FL", "FR", "FC")),
          Map.entry("3.0(back)", List.of("FL", "FR", "BC")),
          Map.entry("3.1", List.of("FL", "FR", "FC", "LFE")),
          Map.entry("4.0", List.of("FL", "FR", "FC", "BC")),
          Map.entry("quad", List.of("FL", "FR", "BL", "BR")),
          Map.entry("quad(side)", List.of("FL", "FR", "SL", "SR")),
          Map.entry("5.0", List.of("FL", "FR", "FC", "BL", "BR")),
          Map.entry("5.0(side)", List.of("FL", "FR", "FC", "SL", "SR")),
          Map.entry("5.1", List.of("FL", "FR", "FC", "LFE", "BL", "BR")),
          Map.entry("5.1(side)", List.of("FL", "FR", "FC", "LFE", "SL", "SR")),
          Map.entry("6.1", List.of("FL", "FR", "FC", "LFE", "BC", "SL", "SR")),
          Map.entry("7.0", List.of("FL", "FR", "FC", "BL", "BR", "SL", "SR")),
          Map.entry("7.1", List.of("FL", "FR", "FC", "LFE", "BL", "BR", "SL", "SR")));

  // ADR 0050's stereo matrix of each 3- and 4-channel layout that some codec does not carry: the
  // centre, a surround and, without surrounds, the low-frequency channel each mix into their side
  // or both sides at −3 dB. A back centre mixes into each side's surround at −3 dB, so it reaches
  // each side at −6 dB.
  private static final Map<String, List<Route>> STEREO_ROUTES =
      Map.of(
          "2.1",
          withFronts(route("LFE", "FL", MINUS_3_DB), route("LFE", "FR", MINUS_3_DB)),
          "3.0(back)",
          withFronts(
              route("BC", "FL", MINUS_3_DB * MINUS_3_DB),
              route("BC", "FR", MINUS_3_DB * MINUS_3_DB)),
          "3.1",
          withFronts(
              route("FC", "FL", MINUS_3_DB),
              route("FC", "FR", MINUS_3_DB),
              route("LFE", "FL", MINUS_3_DB),
              route("LFE", "FR", MINUS_3_DB)),
          "quad",
          withFronts(route("BL", "FL", MINUS_3_DB), route("BR", "FR", MINUS_3_DB)),
          "quad(side)",
          withFronts(route("SL", "FL", MINUS_3_DB), route("SR", "FR", MINUS_3_DB)));

  /** A source channel's sine tone at whole hertz; a negative amplitude is the opposite phase. */
  private record Tone(int frequency, double amplitude) {

    private static Tone of(int frequency) {
      return new Tone(frequency, TONE_AMPLITUDE);
    }

    private static Tone inOppositePhase(int frequency) {
      return new Tone(frequency, -TONE_AMPLITUDE);
    }
  }

  /**
   * The tone of each source channel, by the name that FFmpeg gives the channel. Every tone lies
   * below 120 Hz, inside the low-frequency channel's band, so that a tone mixed into that channel
   * reads there too.
   */
  private enum ToneSet {
    // The rear pair shares one tone in opposite phase, so a fold of the pair into one channel
    // cancels it, and a swap of its sides reads negative. A matrix that mixes the pair's
    // difference into each side reads the same as one that keeps each rear channel on its side,
    // so the cases that move a rear pair also run with the next set.
    REAR_PAIR_IN_OPPOSITE_PHASE(
        Map.of(
            "FL", Tone.of(60),
            "FR", Tone.of(70),
            "FC", Tone.of(80),
            "LFE", Tone.of(50),
            "BC", Tone.of(90),
            "BL", Tone.of(100),
            "BR", Tone.inOppositePhase(100),
            "SL", Tone.of(100),
            "SR", Tone.inOppositePhase(100))),
    OWN_TONE_IN_EACH_CHANNEL(
        Map.of(
            "FL", Tone.of(60),
            "FR", Tone.of(70),
            "FC", Tone.of(80),
            "LFE", Tone.of(50),
            "BC", Tone.of(90),
            "BL", Tone.of(100),
            "BR", Tone.of(105),
            "SL", Tone.of(110),
            "SR", Tone.of(115)));

    private final Map<String, Tone> tones;

    ToneSet(Map<String, Tone> tones) {
      this.tones = tones;
    }
  }

  /** A source channel that reaches an output channel at a gain. */
  private record Route(String from, String to, double gain) {}

  /**
   * An encode of a toned source in the layout that the job names, the layout that FFmpeg decodes
   * the output as, and every gain at which a source channel reaches an output channel.
   */
  @Builder
  record ToneEncode(
      String sourceLayout,
      ToneSet tones,
      String codec,
      String outputLayout,
      String decodedLayout,
      List<Route> routes) {
    @Override
    public String toString() {
      return sourceLayout + " to " + codec + " " + outputLayout + ", " + tones;
    }
  }

  private static Route route(String from, String to, double gain) {
    return new Route(from, to, gain);
  }

  private static List<Route> withFronts(Route... routes) {
    return Stream.concat(
            Stream.of(route("FL", "FL", 1.0), route("FR", "FR", 1.0)), Stream.of(routes))
        .toList();
  }

  private static List<Route> keptRoutes(String layout) {
    return LAYOUT_CHANNELS.get(layout).stream()
        .map(channel -> route(channel, channel, 1.0))
        .toList();
  }

  private static ToneEncode.ToneEncodeBuilder keptLayout(String layout, String codec) {
    return ToneEncode.builder()
        .sourceLayout(layout)
        .tones(ToneSet.REAR_PAIR_IN_OPPOSITE_PHASE)
        .codec(codec)
        .outputLayout(layout)
        .decodedLayout(layout)
        .routes(keptRoutes(layout));
  }

  // A/52 has one 2/2 mode, which FFmpeg's decoder reports as quad(side).
  private static ToneEncode.ToneEncodeBuilder quadAsQuadSide(String codec) {
    return keptLayout("quad", codec)
        .decodedLayout("quad(side)")
        .routes(
            List.of(
                route("FL", "FL", 1.0),
                route("FR", "FR", 1.0),
                route("BL", "SL", 1.0),
                route("BR", "SR", 1.0)));
  }

  private static ToneEncode.ToneEncodeBuilder steppedToStereo(String layout, String codec) {
    return ToneEncode.builder()
        .sourceLayout(layout)
        .tones(ToneSet.REAR_PAIR_IN_OPPOSITE_PHASE)
        .codec(codec)
        .outputLayout("stereo")
        .decodedLayout("stereo")
        .routes(STEREO_ROUTES.get(layout));
  }

  // 7.0 is FL FR FC BL BR SL SR: the back and side channel of each side mix into its surround.
  private static ToneEncode sevenPointZeroFold(String codec, String layout) {
    var leftSurround = surroundOf(layout, "L");
    var rightSurround = surroundOf(layout, "R");
    return ToneEncode.builder()
        .sourceLayout("7.0")
        .tones(ToneSet.OWN_TONE_IN_EACH_CHANNEL)
        .codec(codec)
        .outputLayout(layout)
        .decodedLayout(layout)
        .routes(
            List.of(
                route("FL", "FL", 1.0),
                route("FR", "FR", 1.0),
                route("FC", "FC", 1.0),
                route("BL", leftSurround, MINUS_3_DB),
                route("SL", leftSurround, MINUS_3_DB),
                route("BR", rightSurround, MINUS_3_DB),
                route("SR", rightSurround, MINUS_3_DB)))
        .build();
  }

  // 6.1 is FL FR FC LFE BC SL SR: each side channel keeps its level in the surround of its side,
  // and the back centre mixes into both surrounds.
  private static ToneEncode sixPointOneTo51(String codec, String layout) {
    var leftSurround = surroundOf(layout, "L");
    var rightSurround = surroundOf(layout, "R");
    return ToneEncode.builder()
        .sourceLayout("6.1")
        .tones(ToneSet.OWN_TONE_IN_EACH_CHANNEL)
        .codec(codec)
        .outputLayout(layout)
        .decodedLayout(layout)
        .routes(
            List.of(
                route("FL", "FL", 1.0),
                route("FR", "FR", 1.0),
                route("FC", "FC", 1.0),
                route("LFE", "LFE", 1.0),
                route("SL", leftSurround, 1.0),
                route("SR", rightSurround, 1.0),
                route("BC", leftSurround, MINUS_3_DB),
                route("BC", rightSurround, MINUS_3_DB)))
        .build();
  }

  // The surround of the side, L or R, that the 5-channel or 6-channel layout names: its side
  // channel in AC-3's and E-AC-3's layouts, and its back channel in AAC's and Opus's.
  private static String surroundOf(String layout, String side) {
    var sideSurround = "S" + side;
    if (LAYOUT_CHANNELS.get(layout).contains(sideSurround)) {
      return sideSurround;
    }

    return "B" + side;
  }

  // The job encodes the toned source's audio in the named layout and copies its video; the
  // delivered file is the job's initialization segment and its one media segment.
  private Path deliveredToneEncode(ToneEncode encode) throws Exception {
    var source = tonedSource(encode.sourceLayout(), encode.tones());
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setCodec(encode.codec())
        .setChannels(LAYOUT_CHANNELS.get(encode.outputLayout()).size())
        .setBitrateBitsPerSecond(384_000)
        .setChannelLayout(CONTRACT_LAYOUTS.get(encode.outputLayout()));
    job.getExecutionBuilder().setMediaSegmentCount(1);

    var uploads = execute(engine(realCapabilities()), seekableSources, job.build());

    var delivered = root.resolve(("tones-" + encode).replaceAll("[ ()]", "_") + ".mp4");
    Files.write(delivered, uploads.contentOf(uploadNames(1)));
    return delivered;
  }

  // The file decodes in the expected layout, and each source tone reads in each output channel at
  // the sum of its routes' gains within 1 dB, in the source's phase, or not at all.
  private void assertTonesLandAtTheirGains(ToneEncode encode, Path delivered) throws Exception {
    // ffprobe's CSV ends an AC-3 or E-AC-3 stream's fields with an empty one.
    assertThat(
            ffprobe(
                    List.of("-select_streams", "a:0", "-show_entries", "stream=channel_layout"),
                    delivered)
                .split(",")[0])
        .as("decoded layout")
        .isEqualTo(encode.decodedLayout());
    var tones = encode.tones().tones;
    var frequencies =
        LAYOUT_CHANNELS.get(encode.sourceLayout()).stream()
            .map(channel -> tones.get(channel).frequency())
            .distinct()
            .toList();
    for (var speaker : LAYOUT_CHANNELS.get(encode.decodedLayout())) {
      var samples = decodedChannelFromZero(delivered, speaker);
      for (var frequency : frequencies) {
        var routes =
            encode.routes().stream()
                .filter(route -> route.to().equals(speaker))
                .filter(route -> tones.get(route.from()).frequency() == frequency)
                .toList();
        var read = ToneReading.of(samples, frequency);
        var description = String.format(Locale.ROOT, "%d Hz in %s", frequency, speaker);
        if (routes.isEmpty()) {
          assertThat(read.amplitude()).as(description).isLessThan(ABSENT_TONE_AMPLITUDE);
          continue;
        }

        var expected =
            routes.stream()
                .mapToDouble(route -> route.gain() * tones.get(route.from()).amplitude())
                .sum();
        assertThat(read.amplitude() / Math.abs(expected))
            .as("%s, read %.5f against %.5f", description, read.amplitude(), Math.abs(expected))
            .isBetween(1 / WITHIN_1_DB, WITHIN_1_DB);
        assertThat(read.phaseDegreesAgainst(Math.signum(expected)))
            .as("%s, degrees from the source's phase", description)
            .isLessThan(MOST_DEGREES_FROM_THE_SOURCES_PHASE);
      }
    }
  }

  // The file's channel of FFmpeg's name, decoded to 32-bit samples from media time zero. The
  // encoder's priming samples, which the worker writes before zero, are left out, so that the
  // decoded samples line up with the source's. Without -copyts, FFmpeg would move the file's first
  // sample to zero.
  private int[] decodedChannelFromZero(Path file, String channel) throws Exception {
    var pcm = root.resolve(file.getFileName() + "." + channel + ".s32");
    runFfmpeg(
        List.of(
            "-copyts",
            "-i",
            file.toString(),
            "-map",
            "0:a:0",
            "-af",
            "atrim=start=0,pan=mono|c0=" + channel,
            "-f",
            "s32le"),
        pcm);
    var samples =
        ByteBuffer.wrap(Files.readAllBytes(pcm)).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
    var decoded = new int[samples.remaining()];
    samples.get(decoded);
    return decoded;
  }

  /**
   * A tone's sine and cosine components in a decoded channel, against the source's tones, which are
   * sines from media time zero.
   */
  private record ToneReading(double sine, double cosine) {

    // Both components over whole seconds, so that the other tones, all at whole hertz, read zero.
    private static ToneReading of(int[] channel, int frequency) {
      var from = TONE_READ_FROM_SECOND * TONE_SAMPLE_RATE;
      var frames = TONE_READ_SECONDS * TONE_SAMPLE_RATE;
      var sine = 0.0;
      var cosine = 0.0;
      for (var frame = from; frame < from + frames; frame++) {
        var angle = 2 * Math.PI * frequency * frame / TONE_SAMPLE_RATE;
        var sample = channel[frame] / FULL_SCALE;
        sine += sample * Math.sin(angle);
        cosine += sample * Math.cos(angle);
      }

      return new ToneReading(2 * sine / frames, 2 * cosine / frames);
    }

    private double amplitude() {
      return Math.hypot(sine, cosine);
    }

    // How far the tone's phase lies from the source's tone, or from its opposite when the polarity
    // is negative: 0 to 180 degrees.
    private double phaseDegreesAgainst(double polarity) {
      return Math.abs(Math.toDegrees(Math.atan2(polarity * cosine, polarity * sine)));
    }
  }

  // The channel configuration of the file's AAC, from the ADTS header that FFmpeg writes for it
  // (ISO/IEC 14496-3, 1.A.2.2.1): 0 means that a program config element names the channels.
  private int aacChannelConfiguration(Path delivered) throws Exception {
    var adts = root.resolve(delivered.getFileName() + ".aac");
    runFfmpeg(
        List.of("-i", delivered.toString(), "-map", "0:a:0", "-c:a", "copy", "-f", "adts"), adts);
    var header = Files.readAllBytes(adts);
    return ((header[2] & 0x01) << 2) | ((header[3] & 0xC0) >> 6);
  }

  // The seekable source's video, and 6 s of 24-bit FLAC in the layout, each channel holding its
  // tone of the set.
  private static synchronized Path tonedSource(String layout, ToneSet tones) throws Exception {
    var name = "tones-" + layout + "-" + tones.name().toLowerCase(Locale.ROOT);
    var source = seekableSources.resolve(name.replaceAll("[()]", "_") + ".mkv");
    if (generatedSeekableSources.contains(source)) {
      return source;
    }

    // Each channel's tone joins the layout at the channel's name, not at a position.
    var channels = LAYOUT_CHANNELS.get(layout);
    var arguments = new ArrayList<>(List.of("-f", "lavfi", "-i", "testsrc=size=64x36:rate=24"));
    var map = new ArrayList<String>();
    for (var input = 0; input < channels.size(); input++) {
      var tone = tones.tones.get(channels.get(input));
      arguments.addAll(
          List.of(
              "-f",
              "lavfi",
              "-i",
              String.format(
                  Locale.ROOT,
                  "aevalsrc=exprs=%s*sin(2*PI*%d*t):sample_rate=%d",
                  tone.amplitude(),
                  tone.frequency(),
                  TONE_SAMPLE_RATE)));
      map.add(input + ".0-" + channels.get(input));
    }

    var join =
        "join=inputs=%d:channel_layout=%s:map=%s"
            .formatted(channels.size(), layout, String.join("|", map));
    arguments.addAll(
        List.of(
            "-filter_complex",
            Stream.iterate(1, input -> input <= channels.size(), input -> input + 1)
                    .map(input -> "[" + input + ":a]")
                    .collect(Collectors.joining())
                + join
                + "[audio]",
            "-map",
            "0:v",
            "-map",
            "[audio]",
            "-t",
            "6",
            "-c:v",
            "libx264",
            "-g",
            "48",
            "-c:a",
            "flac",
            "-sample_fmt",
            "s32"));
    runFfmpeg(arguments, source);
    generatedSeekableSources.add(source);
    return source;
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

  @Test
  @DisplayName(
      "Should upload only the audio of its own interval in the segment that holds the video's end"
          + " when a stream copy starts there and the audio outlasts the video")
  void shouldUploadOnlyTheAudioOfItsOwnIntervalWhenAStreamCopyStartsWhereTheVideoEnds()
      throws Exception {
    var source = videoEndsBeforeAudioSource();
    var launcher = new TeeingProcessLauncher(new ProcessBuilderLauncher());
    var job = videoEndsBeforeAudioJob(source, TranscodeMode.TRANSCODE_MODE_REMUX);
    job.getExecutionBuilder().setStartSequenceNumber(VIDEO_ENDS_BEFORE_AUDIO_LAST_VIDEO_SEGMENT);

    var uploads =
        execute(engine(softwareCapabilities(), launcher), source.getParent(), job.build());

    var lastVideoSegment = "segment" + VIDEO_ENDS_BEFORE_AUDIO_LAST_VIDEO_SEGMENT + ".m4s";
    assertThat(uploads.names()).containsExactly("init.mp4", lastVideoSegment);
    var ffmpegPackets =
        FfprobePackets.of(launcher.outputOf(fromProto(job.getJobAttemptId())), root);
    var interval =
        AudioInterval.ofDeliveredSegment(
            VIDEO_ENDS_BEFORE_AUDIO_LAST_VIDEO_SEGMENT, SEEKABLE_SOURCE_AUDIO_PERIOD_TICKS);
    var uploaded = packetsOf(uploads, lastVideoSegment);
    assertThat(uploaded.stream().filter(Packet::isVideo).toList())
        .isNotEmpty()
        .containsExactlyElementsOf(ffmpegPackets.stream().filter(Packet::isVideo).toList());
    assertThat(uploaded.stream().filter(Packet::isAudio).toList())
        .as("the copied audio of 60 s to 66 s, packet for packet")
        .isNotEmpty()
        .containsExactlyElementsOf(
            ffmpegPackets.stream()
                .filter(Packet::isAudio)
                .filter(packet -> interval.holds(packet.pts()))
                .toList());
  }

  @Test
  @DisplayName(
      "Should upload the segment that holds the video's end with only its own interval's audio"
          + " when an encode from segment 0 reads audio that outlasts the video")
  void shouldUploadTheSegmentThatHoldsTheVideosEndWithOnlyItsOwnAudioWhenAnEncodeReadsTheTail()
      throws Exception {
    var source = videoEndsBeforeAudioSource();
    var launcher = new TeeingProcessLauncher(new ProcessBuilderLauncher());
    var job = videoEndsBeforeAudioJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);

    var uploads =
        execute(engine(softwareCapabilities(), launcher), source.getParent(), job.build());

    assertThat(uploads.names())
        .containsExactlyElementsOf(uploadNames(VIDEO_ENDS_BEFORE_AUDIO_LAST_VIDEO_SEGMENT + 1));
    var ffmpegAudio =
        FfprobePackets.of(launcher.outputOf(fromProto(job.getJobAttemptId())), root).stream()
            .filter(Packet::isAudio)
            .toList();
    var interval =
        AudioInterval.ofDeliveredSegment(
            VIDEO_ENDS_BEFORE_AUDIO_LAST_VIDEO_SEGMENT, SEEKABLE_SOURCE_AUDIO_PERIOD_TICKS);
    assertThat(
            packetsOf(uploads, "segment" + VIDEO_ENDS_BEFORE_AUDIO_LAST_VIDEO_SEGMENT + ".m4s")
                .stream()
                .filter(Packet::isAudio)
                .toList())
        .as("the encoded audio of 60 s to 66 s")
        .isNotEmpty()
        .containsExactlyElementsOf(
            ffmpegAudio.stream().filter(packet -> interval.holds(packet.pts())).toList());
  }

  @ParameterizedTest(name = "{0} from segment {1}")
  @CsvSource({
    "TRANSCODE_MODE_REMUX, 0",
    "TRANSCODE_MODE_REMUX, 11",
    "TRANSCODE_MODE_FULL_TRANSCODE, 0",
    "TRANSCODE_MODE_FULL_TRANSCODE, 11"
  })
  @DisplayName(
      "Should complete reporting that the source ended in the segment that holds the video's end,"
          + " with audio after it, when an attempt that holds no frame reads video that ends"
          + " before the audio")
  void shouldCompleteReportingTheSourceEndedEarlyWhenAnAttemptReadsVideoThatEndsBeforeTheAudio(
      TranscodeMode mode, int startSequenceNumber) throws Exception {
    var source = videoEndsBeforeAudioSource();
    var job = videoEndsBeforeAudioJob(source, mode);
    job.getExecutionBuilder().setStartSequenceNumber(startSequenceNumber);

    var completion =
        executeToCompletion(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(completion.report().getSourceEndedEarly())
        .isEqualTo(
            SourceEndedEarly.newBuilder()
                .setLastSegmentWithSourceMedia(VIDEO_ENDS_BEFORE_AUDIO_LAST_VIDEO_SEGMENT)
                .addStreamIndexesWithMediaAfterLastSegment(VIDEO_ENDS_BEFORE_AUDIO_AUDIO_INDEX)
                .build());
    assertThat(completion.report().getStreamEndsList())
        .containsExactlyInAnyOrderElementsOf(VIDEO_ENDS_BEFORE_AUDIO_STREAM_ENDS);
    assertThat(completion.uploads().names())
        .doesNotContainAnyElementsOf(
            IntStream.range(
                    VIDEO_ENDS_BEFORE_AUDIO_LAST_VIDEO_SEGMENT + 1,
                    VIDEO_ENDS_BEFORE_AUDIO_SEGMENT_COUNT)
                .mapToObj(WorkerMediaSmokeTest::segmentName)
                .toList());
  }

  // The source's video edit ends at 5.9 s, inside segment 0, while its video samples and its audio
  // run to 10 s. The demuxer flags the video packets after the edit for discarding, so no picture
  // reaches segment 1, and the attempt from segment 1 delivers nothing.
  @Test
  @DisplayName(
      "Should complete reporting that the source ended in the segment where the video's edit ends,"
          + " with audio after it, when an attempt after that edit reads packets that it cuts away")
  void shouldCompleteReportingTheSourceEndedWhereTheVideosEditEndsWhenAnAttemptReadsPacketsAfterIt()
      throws Exception {
    var source = videoEditEndingBeforeItsSamplesSource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getExecutionBuilder().setMediaSegmentCount(2).setStartSequenceNumber(1);

    var completion =
        executeToCompletion(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(completion.report().getSourceEndedEarly())
        .isEqualTo(
            SourceEndedEarly.newBuilder()
                .setLastSegmentWithSourceMedia(0)
                .addStreamIndexesWithMediaAfterLastSegment(1)
                .build());
  }

  // 10 s of H.264 at 64x36 and 24 fps with a keyframe every 2 s, and a 10 s tone, in MP4 whose
  // video edit ends at 5.9 s.
  private static synchronized Path videoEditEndingBeforeItsSamplesSource() throws Exception {
    var source = seekableSources.resolve("video-edit-ending-before-its-samples.mp4");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24:duration=10",
              "-f",
              "lavfi",
              "-i",
              "sine=frequency=440:sample_rate=48000:duration=10",
              "-c:v",
              "libx264",
              "-g",
              "48",
              "-c:a",
              "aac"),
          source);
      endTheFirstTracksEditAt5900Milliseconds(source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // Shortens the first edit of the first track, a version 0 elst entry, to 5,900 ticks of FFmpeg's
  // 1 ms movie timescale.
  private static void endTheFirstTracksEditAt5900Milliseconds(Path mp4) throws IOException {
    var bytes = Files.readAllBytes(mp4);
    var position = 0;
    var end = bytes.length;
    for (var type : List.of("moov", "trak", "edts", "elst")) {
      position = childOf(bytes, position, end, type);
      end = position + ByteBuffer.wrap(bytes, position, 4).getInt();
      position += 8;
    }

    assertThat(bytes[position]).as("the elst version").isZero();
    ByteBuffer.wrap(bytes).putInt(position + 8, 5_900);
    Files.write(mp4, bytes);
  }

  // The source's one picture starts at 0 s and lasts 20 s. The encode repeats it at 24 fps up to
  // 20 s, so segment 3, from 18 s to 24 s, holds the video's end, whether the attempt starts at 0
  // and delivers it or starts after the end and delivers nothing.
  @ParameterizedTest(name = "from segment {0}")
  @ValueSource(ints = {0, 5})
  @DisplayName(
      "Should complete reporting the segment where the video's last frame ends when an encode reads"
          + " a last frame that spans several segments")
  void shouldCompleteReportingTheSegmentWhereTheVideosLastFrameEndsWhenItSpansSeveralSegments(
      int startSequenceNumber) throws Exception {
    var source = oneTwentySecondPictureSource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getExecutionBuilder().setMediaSegmentCount(6).setStartSequenceNumber(startSequenceNumber);

    var completion =
        executeToCompletion(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(completion.report().getSourceEndedEarly().getLastSegmentWithSourceMedia())
        .isEqualTo(3);
  }

  // One H.264 picture at 64x36 that lasts 20 s, and 20 s of a 440 Hz tone.
  private static synchronized Path oneTwentySecondPictureSource() throws Exception {
    var source = seekableSources.resolve("one-twenty-second-picture.mp4");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "color=c=red:s=64x36:r=1/20:d=20",
              "-f",
              "lavfi",
              "-i",
              "sine=frequency=440:sample_rate=48000:duration=20",
              "-c:v",
              "libx264",
              "-c:a",
              "aac"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // The source's one FLAC packet runs from 0 s to 7.5 s, past the end of segment 0 at 6 s, and its
  // video ends at 5 s. The encode converts the packet over its whole duration, and its AAC after
  // 6 s has no segment to go in, as no video opens segment 1.
  @Test
  @DisplayName(
      "Should complete listing the audio after the last segment when an encode reads an audio packet"
          + " that crosses the end of the segment that holds the video's end")
  void shouldCompleteListingTheAudioAfterTheLastSegmentWhenAnEncodedAudioPacketCrossesItsEnd()
      throws Exception {
    var source = audioPacketAcrossTheVideosLastSegmentEndSource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getExecutionBuilder().setMediaSegmentCount(2);

    var completion =
        executeToCompletion(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(completion.report().getSourceEndedEarly())
        .isEqualTo(
            SourceEndedEarly.newBuilder()
                .setLastSegmentWithSourceMedia(0)
                .addStreamIndexesWithMediaAfterLastSegment(1)
                .build());
  }

  // 5 s of H.264 at 64x36 and 24 fps, and a 440 Hz tone at 8 kHz for 7.5 s in one FLAC frame.
  private static synchronized Path audioPacketAcrossTheVideosLastSegmentEndSource()
      throws Exception {
    var source = seekableSources.resolve("audio-packet-across-the-videos-last-segment-end.mkv");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24:duration=5",
              "-f",
              "lavfi",
              "-i",
              "sine=frequency=440:sample_rate=8000:duration=7.5",
              "-c:v",
              "libx264",
              "-c:a",
              "flac",
              "-frame_size",
              "60000"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // The copy's video ends at 12 s, the end of segment 1, so segment 2 has no video. The tone ends
  // less than one audio frame before 12 s, and the encoder fills its last frame past 12 s with
  // padding: AAC's 1,024-sample frame, after 1,024 samples of priming, and Opus's 960-sample frame,
  // after 312. AC-3's and E-AC-3's encoder drops the tail of its 1,536-sample frames instead. The
  // MP4 muxer trims the last packet with an edit list. Matroska trims the last Opus packet only
  // with DiscardPadding, which FFmpeg passes as side data that its decoder applies. AAC in Matroska
  // keeps no record of its padding, so no reader can tell the padding from the tone.
  @ParameterizedTest(name = "{0} in {1}")
  @CsvSource({
    "aac, mp4",
    "ac3, mp4",
    "ac3, mkv",
    "eac3, mp4",
    "eac3, mkv",
    "libopus, mp4",
    "libopus, mkv"
  })
  @DisplayName(
      "Should list no stream with media after the last segment when only the encoded audio's"
          + " trailing codec padding crosses that segment's end")
  void shouldListNoStreamAfterTheLastSegmentWhenOnlyTheAudiosTrailingCodecPaddingCrossesItsEnd(
      String codec, String container) throws Exception {
    var source = toneEndingInsideItsLastFrameBeforeTwelveSecondsSource(codec, container);
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    job.getExecutionBuilder().setMediaSegmentCount(3);

    var completion =
        executeToCompletion(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(completion.report().getSourceEndedEarly())
        .isEqualTo(SourceEndedEarly.newBuilder().setLastSegmentWithSourceMedia(1).build());
    assertThat(completion.report().getStreamEndsList())
        .filteredOn(end -> end.getStreamIndex() == 1)
        .extracting(
            end -> Duration.ofSeconds(end.getEndTime().getSeconds(), end.getEndTime().getNanos()))
        .as("the audio's end, within AC-3's 32 ms frame before 12 s")
        .singleElement(InstanceOfAssertFactories.DURATION)
        .isLessThan(Duration.ofSeconds(12))
        .isGreaterThan(Duration.ofMillis(11_968));
  }

  // Less than one frame of the codec before 12 s: 575,700 samples at 48 kHz for Opus, whose last
  // packet starts at sample 575,688; 575,760 for the others.
  private static Duration toneEndBeforeTwelveSeconds(String codec) {
    return switch (codec) {
      case "libopus" -> Duration.ofNanos(11_993_750_000L);
      default -> Duration.ofMillis(11_995);
    };
  }

  // 12 s of H.264 at 64x36 and 24 fps with a keyframe every 2 s, and a stereo 440 Hz tone at
  // 48 kHz in the codec that ends less than one of its frames before 12 s.
  private static synchronized Path toneEndingInsideItsLastFrameBeforeTwelveSecondsSource(
      String codec, String container) throws Exception {
    var source =
        seekableSources.resolve("tone-ending-inside-its-last-frame-" + codec + "." + container);
    if (!generatedSeekableSources.contains(source)) {
      var toneEnd = toneEndBeforeTwelveSeconds(codec);
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24:duration=12",
              "-f",
              "lavfi",
              "-i",
              "sine=frequency=440:sample_rate=48000:duration="
                  + BigDecimal.valueOf(toneEnd.toNanos(), 9).toPlainString(),
              "-c:v",
              "libx264",
              "-g",
              "48",
              "-keyint_min",
              "48",
              "-c:a",
              codec,
              "-ac",
              "2"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // FFmpeg reads the source through a named pipe at about three times real time, as from a slow
  // mount, so copying the 60 s of audio after the video's end takes about 20 s, twice the server's
  // stall threshold. Segment 1 runs from 6 s to the end and stays open throughout, so no upload
  // shows that the copy advances. FFmpeg's muxer holds up to 10 s of audio while it waits for more
  // video, which pauses the output for about 3 s once the video ends.
  @Test
  @DisplayName(
      "Should report progress throughout the audio after the video's end when a copy's video ends"
          + " long before its audio")
  void shouldReportProgressThroughoutTheAudioAfterTheVideosEndWhenACopysVideoEndsLongBefore()
      throws Exception {
    var source = videoEndsLongBeforeAudioSource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_REMUX);
    job.getExecutionBuilder().setMediaSegmentCount(2).addAllSegmentStartSlots(List.of(0, 1));

    try (var pipe =
        new PacedSourcePipe(root.resolve("source-pipe"), THRICE_THE_SOURCES_BYTE_RATE)) {
      var engine = engine(softwareCapabilities(), pipe.feeding(new ProcessBuilderLauncher()));

      var completion = executeToCompletion(engine, source.getParent(), job.build());

      assertThat(completion.uploads().names()).containsExactlyElementsOf(uploadNames(2));
      assertThat(completion.progress()).isNotEmpty();
      assertThat(completion.longestSilence())
          .as("the longest time without a progress report before the attempt completed")
          .isLessThan(SERVER_STALL_THRESHOLD);
      assertThat(completion.progress().getLast().outputEnd())
          .as("the latest output end reported; the video ends at 10 s and the audio at 70 s")
          .isGreaterThanOrEqualTo(Duration.ofSeconds(50));
    }
  }

  // 10 s of a still grey picture in H.264 at 64x36 and 24 fps with a keyframe every 2 s, which
  // takes almost no bytes, and 70 s of stereo pink noise in AAC at 256 kb/s. The muxer interleaves
  // strictly by time: by default it writes the encoder's last video packets up to 10 s of audio
  // later, so the file would hold video until about its 20 s of audio.
  private static synchronized Path videoEndsLongBeforeAudioSource() throws Exception {
    var source = seekableSources.resolve("video-ends-long-before-audio.mkv");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "color=c=gray:size=64x36:rate=24:duration=10",
              "-f",
              "lavfi",
              "-i",
              "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5:duration=70",
              "-c:v",
              "libx264",
              "-pix_fmt",
              "yuv444p",
              "-g",
              "48",
              "-keyint_min",
              "48",
              "-c:a",
              "aac",
              "-ac",
              "2",
              "-b:a",
              "256k",
              "-max_interleave_delta",
              "0"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  /**
   * Feeds FFmpeg its source through a named pipe, in place of the path that its command reads, at a
   * steady number of bytes a second, as a slow mount would serve it.
   */
  private static final class PacedSourcePipe implements AutoCloseable {

    private static final int WRITES_PER_SECOND = 20;

    private final Path pipe;
    private final int bytesPerWrite;
    private final ScheduledExecutorService writer =
        Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
    private volatile FileChannel output;

    private PacedSourcePipe(Path pipe, int bytesPerSecond) throws Exception {
      var mkfifo = new ProcessBuilder("mkfifo", pipe.toString()).start();
      assertThat(mkfifo.waitFor()).isZero();
      this.pipe = pipe;
      this.bytesPerWrite = bytesPerSecond / WRITES_PER_SECOND;
    }

    private ProcessLauncher feeding(ProcessLauncher launcher) {
      return (command, jobAttemptId) -> {
        var input = command.indexOf("-i") + 1;
        assertThat(input).as("the command reads an input").isPositive();
        startWriting(readSource(Path.of(command.get(input))));
        var adjusted = new ArrayList<>(command);
        adjusted.set(input, pipe.toString());
        return launcher.launch(adjusted, jobAttemptId);
      };
    }

    private static byte[] readSource(Path source) {
      try {
        return Files.readAllBytes(source);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    // Opened for reading as well as writing, the pipe opens at once on Linux, even when FFmpeg
    // never opens its end, and a write that waits on a full pipe ends when close() interrupts it.
    private void startWriting(byte[] source) {
      try {
        output = FileChannel.open(pipe, StandardOpenOption.READ, StandardOpenOption.WRITE);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }

      var written = new AtomicInteger();
      writer.scheduleAtFixedRate(
          () -> writeNext(source, written), 0, 1_000 / WRITES_PER_SECOND, TimeUnit.MILLISECONDS);
    }

    private void writeNext(byte[] source, AtomicInteger written) {
      try {
        var length = Math.min(bytesPerWrite, source.length - written.get());
        var block = ByteBuffer.wrap(source, written.getAndAdd(length), length);
        while (block.hasRemaining()) {
          output.write(block);
        }

        if (written.get() == source.length) {
          output.close();
          writer.shutdown();
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }

    @Override
    public void close() throws Exception {
      writer.shutdownNow();
      if (output != null) {
        output.close();
      }

      assertThat(writer.awaitTermination(5, TimeUnit.SECONDS)).as("the writer stopped").isTrue();
    }
  }

  @ParameterizedTest(name = "from segment {0}")
  @ValueSource(ints = {0, 11})
  @DisplayName(
      "Should complete reporting each stream's end and no early end when an encode holds the last"
          + " frame of video that ends before the audio it pads")
  void shouldCompleteReportingEachStreamsEndAndNoEarlyEndWhenAnEncodeHoldsAndPadsToTheEnd(
      int startSequenceNumber) throws Exception {
    var source = videoEndsBeforeAudioSource();
    var job = videoEndsBeforeAudioJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    paddedTo(job, VIDEO_ENDS_BEFORE_AUDIO_SEGMENT_COUNT);
    job.getDecisionBuilder().getVideoBuilder().setHoldLastFrameToAdvertisedEnd(true);
    job.getExecutionBuilder().setStartSequenceNumber(startSequenceNumber);

    var completion =
        executeToCompletion(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(completion.report().hasSourceEndedEarly()).isFalse();
    assertThat(completion.report().getStreamEndsList())
        .containsExactlyInAnyOrderElementsOf(VIDEO_ENDS_BEFORE_AUDIO_STREAM_ENDS);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = TranscodeMode.class,
      names = {"TRANSCODE_MODE_REMUX", "TRANSCODE_MODE_FULL_TRANSCODE"})
  @DisplayName(
      "Should report the source's own stream ends and early end when the source's metadata carries"
          + " text shaped like the framecrc of its streams")
  void shouldReportTheSourcesOwnStreamEndsWhenItsMetadataCarriesFramecrcShapedText(
      TranscodeMode mode) throws Exception {
    var source = videoEndsBeforeAudioSourceWithFramecrcShapedMetadata();
    var job = videoEndsBeforeAudioJob(source, mode);
    job.getExecutionBuilder().setStartSequenceNumber(11);

    var completion =
        executeToCompletion(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(completion.report().getStreamEndsList())
        .containsExactlyInAnyOrderElementsOf(VIDEO_ENDS_BEFORE_AUDIO_STREAM_ENDS);
    assertThat(completion.report().getSourceEndedEarly())
        .isEqualTo(
            SourceEndedEarly.newBuilder()
                .setLastSegmentWithSourceMedia(VIDEO_ENDS_BEFORE_AUDIO_LAST_VIDEO_SEGMENT)
                .addStreamIndexesWithMediaAfterLastSegment(VIDEO_ENDS_BEFORE_AUDIO_AUDIO_INDEX)
                .build());
  }

  // A held encode reads its audio from a second input, whose copy of the video to FFmpeg's null
  // muxer is one more output whose metadata FFmpeg would write among the framecrc.
  @Test
  @DisplayName(
      "Should report each stream's end and no early end when an encode holds the last frame and"
          + " the source's metadata carries text shaped like the framecrc of its streams")
  void shouldReportEachStreamsEndWhenAHeldEncodesSourceMetadataCarriesFramecrcShapedText()
      throws Exception {
    var source = videoEndsBeforeAudioSourceWithFramecrcShapedMetadata();
    var job = videoEndsBeforeAudioJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    paddedTo(job, VIDEO_ENDS_BEFORE_AUDIO_SEGMENT_COUNT);
    job.getDecisionBuilder().getVideoBuilder().setHoldLastFrameToAdvertisedEnd(true);
    job.getExecutionBuilder().setStartSequenceNumber(11);

    var completion =
        executeToCompletion(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(completion.report().hasSourceEndedEarly()).isFalse();
    assertThat(completion.report().getStreamEndsList())
        .containsExactlyInAnyOrderElementsOf(VIDEO_ENDS_BEFORE_AUDIO_STREAM_ENDS);
  }

  // Segment 12, from 72 s, lies wholly after the source's audio turns from mono to stereo at 70 s,
  // where its left channel plays 440 Hz and its right 880 Hz.
  @Test
  @DisplayName(
      "Should keep the stereo channels apart after the source's audio turns from mono to stereo"
          + " when an encode from the start holds the last frame of video that ends before the"
          + " audio")
  void shouldKeepTheStereoChannelsApartAfterTheSourcesAudioTurnsFromMonoToStereoWhenAnEncodeHolds()
      throws Exception {
    var source = videoEndsBeforeAudioThatTurnsFromMonoToStereoSource();
    var job = videoEndsBeforeAudioJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getExecutionBuilder()
        .getAdvertisedEndBuilder()
        .setSeconds(VIDEO_ENDS_BEFORE_AUDIO_SEGMENT_COUNT * 6L);
    job.getDecisionBuilder().getVideoBuilder().setHoldLastFrameToAdvertisedEnd(true);

    var uploads = execute(engine(softwareCapabilities()), source.getParent(), job.build());

    var samples =
        ByteBuffer.wrap(decodedAudio(uploads.contentOf(List.of("init.mp4", segmentName(12)))))
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer();
    assertThat(
            IntStream.range(0, samples.limit() / 2)
                .map(frame -> Math.abs(samples.get(2 * frame) - samples.get(2 * frame + 1)))
                .max()
                .orElseThrow())
        .as("the widest difference between segment 12's left and right samples")
        .isGreaterThan(1_000);
  }

  // An encode that holds the last frame reads its audio from a second input and filters it in a
  // graph of its own; its audio must not change for that. The encoder's input must reach that
  // graph: FFmpeg downmixes integer samples with a gain that keeps them from clipping, rounds a
  // resampler's output to integers, and gives a source whose layout changes the layout it started
  // with.
  @ParameterizedTest(name = "{0}")
  @MethodSource("heldAudioCases")
  @DisplayName(
      "Should encode the same audio as an encode that holds no frame when an encode holds the last"
          + " frame")
  void shouldEncodeTheSameAudioAsAnEncodeThatHoldsNoFrameWhenAnEncodeHoldsTheLastFrame(
      HeldAudioCase heldAudio) throws Throwable {
    var source = heldAudio.source().get();
    var held = heldAudio.job(source);
    held.getDecisionBuilder().getVideoBuilder().setHoldLastFrameToAdvertisedEnd(true);

    var heldUploads = execute(engine(softwareCapabilities()), source.getParent(), held.build());
    var uploads =
        execute(engine(softwareCapabilities()), source.getParent(), heldAudio.job(source).build());

    assertThat(decodedAudio(heldUploads.contentOf(heldUploads.names())))
        .as("the held encode's decoded audio")
        .isNotEmpty()
        .isEqualTo(decodedAudio(uploads.contentOf(uploads.names())));
  }

  static Stream<HeldAudioCase> heldAudioCases() {
    return Stream.of(
        HeldAudioCase.builder()
            .name("16-bit 5.1 FLAC at 96 kHz downmixed to stereo AC-3")
            .source(WorkerMediaSmokeTest::integerSurroundFlacSource)
            .segmentCount(2)
            .codec("ac3")
            .channels(2)
            .build(),
        HeldAudioCase.builder()
            .name("16-bit stereo FLAC at 96 kHz near full scale, in named stereo AC-3")
            .source(WorkerMediaSmokeTest::loudIntegerStereoFlacSource)
            .segmentCount(2)
            .codec("ac3")
            .channels(2)
            .layout(Optional.of(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO))
            .build(),
        HeldAudioCase.builder()
            .name("AC-3 that turns from mono to stereo, as stereo ALAC")
            .source(WorkerMediaSmokeTest::monoThenStereoSource)
            .segmentCount(2)
            .codec("alac")
            .channels(2)
            .build(),
        HeldAudioCase.builder()
            .name("AC-3 that turns from mono to stereo, as four channels of AC-3")
            .source(WorkerMediaSmokeTest::monoThenStereoSource)
            .segmentCount(2)
            .codec("ac3")
            .channels(4)
            .build(),
        HeldAudioCase.builder()
            .name("loud 5.1 AC-3 downmixed to stereo ALAC")
            .source(WorkerMediaSmokeTest::loudSurroundAc3Source)
            .segmentCount(2)
            .codec("alac")
            .channels(2)
            .build(),
        HeldAudioCase.builder()
            .name("16-bit 7.1(wide) FLAC as eight channels of ALAC")
            .source(WorkerMediaSmokeTest::wideSevenPointOneFlacSource)
            .segmentCount(2)
            .codec("alac")
            .channels(8)
            .build());
  }

  // A source, and the audio that a job encodes from it over this many 6 s segments.
  @Builder
  private record HeldAudioCase(
      String name,
      ThrowingSupplier<Path> source,
      int segmentCount,
      String codec,
      int channels,
      Optional<AudioChannelLayout> layout) {

    HeldAudioCase {
      layout = layout == null ? Optional.empty() : layout;
    }

    VariantJob.Builder job(Path source) {
      var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
      job.getExecutionBuilder().setMediaSegmentCount(segmentCount);
      job.getExecutionBuilder().getAdvertisedEndBuilder().setSeconds(segmentCount * 6L);
      var audio =
          job.getDecisionBuilder()
              .getAudioBuilder()
              .setCodec(codec)
              .setChannels(channels)
              .setBitrateBitsPerSecond(192_000);
      layout.ifPresent(audio::setChannelLayout);
      return job;
    }

    @Override
    public String toString() {
      return name;
    }
  }

  // FFmpeg encodes -ac 4 in AC-3 as quad(side), the first four-channel layout its encoder lists,
  // where the source's side channels play 700 Hz on the left and 900 Hz on the right.
  @Test
  @DisplayName(
      "Should keep the side channels apart when an encode holds the last frame and encodes four"
          + " channels of quad(side) audio in AC-3")
  void shouldKeepTheSideChannelsApartWhenAnEncodeHoldsAndEncodesFourChannelsOfQuadSideInAc3()
      throws Exception {
    var source = videoEndsBeforeQuadSideAudioSource();
    var job = videoEndsBeforeAudioJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getExecutionBuilder()
        .getAdvertisedEndBuilder()
        .setSeconds(VIDEO_ENDS_BEFORE_AUDIO_SEGMENT_COUNT * 6L);
    job.getDecisionBuilder().getVideoBuilder().setHoldLastFrameToAdvertisedEnd(true);
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setCodec("ac3")
        .setChannels(4)
        .setBitrateBitsPerSecond(384_000);

    var uploads = execute(engine(softwareCapabilities()), source.getParent(), job.build());

    var samples =
        ByteBuffer.wrap(decodedAudio(uploads.contentOf(List.of("init.mp4", segmentName(12)))))
            .order(ByteOrder.LITTLE_ENDIAN)
            .asShortBuffer();
    assertThat(
            IntStream.range(0, samples.limit() / 4)
                .map(frame -> Math.abs(samples.get(4 * frame + 2) - samples.get(4 * frame + 3)))
                .max()
                .orElseThrow())
        .as("the widest difference between segment 12's side left and side right samples")
        .isGreaterThan(1_000);
  }

  // The source's last frame starts at 5.983 s, inside segment 0, but FFmpeg's conversion to 24 fps
  // can place the output's last frame at 6 s, inside segment 1.
  @Test
  @DisplayName(
      "Should report the last uploaded segment as the last with source media when an encode's"
          + " frame-rate conversion moves the source's last frame into the next segment")
  void shouldReportTheLastUploadedSegmentWhenFrameRateConversionMovesTheLastFrameIntoTheNext()
      throws Exception {
    var source = sixtyFramesPerSecondEndingBeforeABoundarySource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getExecutionBuilder().setMediaSegmentCount(3);

    var completion =
        executeToCompletion(engine(softwareCapabilities()), source.getParent(), job.build());

    var lastUploaded =
        completion.uploads().names().stream()
            .filter(name -> name.startsWith("segment"))
            .map(name -> Integer.parseInt(name.substring("segment".length(), name.indexOf('.'))))
            .max(Comparator.naturalOrder())
            .orElseThrow();
    assertThat(completion.report().getSourceEndedEarly().getLastSegmentWithSourceMedia())
        .isEqualTo(lastUploaded);
    assertThat(
            completion
                .report()
                .getSourceEndedEarly()
                .getStreamIndexesWithMediaAfterLastSegmentList())
        .as("the audio runs on to 15 s, past every uploaded segment")
        .containsExactly(1);
  }

  // The truncated source's index promises 90 s, its data holds less, and its probe would say 90 s.
  @Test
  @DisplayName(
      "Should upload every advertised segment and report the segment where the source's data ends"
          + " when an encode holds and pads a source truncated before its index's end")
  void shouldUploadEverySegmentAndReportWhereTheDataEndsWhenAnEncodeHoldsAndPadsATruncatedSource()
      throws Exception {
    var source = truncatedSource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    paddedTo(job, TRUNCATED_SOURCE_INDEXED_SEGMENT_COUNT);
    job.getDecisionBuilder().getVideoBuilder().setHoldLastFrameToAdvertisedEnd(true);

    var completion =
        executeToCompletion(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(completion.uploads().names())
        .containsExactlyElementsOf(uploadNames(TRUNCATED_SOURCE_INDEXED_SEGMENT_COUNT));
    var report = completion.report();
    var lastSegment = report.getSourceEndedEarly().getLastSegmentWithSourceMedia();
    assertThat(report.getSourceEndedEarly().getStreamIndexesWithMediaAfterLastSegmentList())
        .as("a held encode carries every source sample")
        .isEmpty();
    assertThat(lastSegment).isBetween(1, TRUNCATED_SOURCE_INDEXED_SEGMENT_COUNT - 2);
    assertThat(report.getStreamEndsList())
        .extracting(SourceStreamEnd::getStreamIndex)
        .containsExactlyInAnyOrder(0, 1);
    var latestEnd =
        report.getStreamEndsList().stream()
            .map(
                end ->
                    Duration.ofSeconds(end.getEndTime().getSeconds(), end.getEndTime().getNanos()))
            .max(Comparator.naturalOrder())
            .orElseThrow();
    assertThat(latestEnd)
        .as("the end of the last source sample, which starts inside segment %d", lastSegment)
        .isGreaterThan(Duration.ofSeconds(lastSegment * 6L))
        .isLessThanOrEqualTo(Duration.ofSeconds((lastSegment + 1) * 6L).plus(ONE_FRAME_AT_24_FPS));
  }

  // Before an index-less source's keyframe scan finishes, the server reads an attempt past the
  // video's end back from one period before that end: 14 s here, inside the source's last GOP,
  // which runs from 10 s to the video's end at 20 s.
  @Test
  @DisplayName(
      "Should hold the video's last picture in every segment of the attempt when a held encode's"
          + " read-back lies after the last keyframe of a source without an index")
  void shouldHoldTheLastPictureInEverySegmentWhenAHeldEncodesReadBackLiesAfterTheLastKeyframe()
      throws Exception {
    var source = longFinalGopTransportStreamSource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    paddedTo(job, LONG_FINAL_GOP_SEGMENT_COUNT);
    job.getDecisionBuilder().getVideoBuilder().setHoldLastFrameToAdvertisedEnd(true);
    job.getExecutionBuilder().setStartSequenceNumber(4);
    setSeekPosition(job.getExecutionBuilder(), BigDecimal.valueOf(14));

    var completion =
        executeToCompletion(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(completion.uploads().names())
        .containsExactly("init.mp4", "segment4.m4s", "segment5.m4s", "segment6.m4s");
    for (var name : List.of("segment4.m4s", "segment5.m4s", "segment6.m4s")) {
      assertThat(packetsOf(completion.uploads(), name).stream().filter(Packet::isVideo))
          .as("the held pictures of %s, 6 s at 24 fps", name)
          .hasSize(144);
    }

    assertThat(completion.report().getStreamEndsList())
        .filteredOn(end -> end.getStreamIndex() == 0)
        .extracting(
            end -> Duration.ofSeconds(end.getEndTime().getSeconds(), end.getEndTime().getNanos()))
        .as("the video's 20 s, which start 21 ms in, after the AAC priming")
        .singleElement(InstanceOfAssertFactories.DURATION)
        .isBetween(Duration.ofSeconds(20), Duration.ofSeconds(20).plus(ONE_FRAME_AT_24_FPS));
  }

  // 20 s of H.264 at 64x36 and 24 fps with IDR keyframes at 0, 2, 4, 6, 8 and 10 s only, and a
  // 440 Hz tone for 40 s, in MPEG-TS, which carries no index.
  private static synchronized Path longFinalGopTransportStreamSource() throws Exception {
    var source = seekableSources.resolve("long-final-gop.ts");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24:duration=20",
              "-f",
              "lavfi",
              "-i",
              "sine=frequency=440:sample_rate=48000:duration=40",
              "-c:v",
              "libx264",
              "-g",
              "1000",
              "-keyint_min",
              "1000",
              "-sc_threshold",
              "0",
              "-forced-idr",
              "1",
              "-force_key_frames",
              "0,2,4,6,8,10",
              "-c:a",
              "aac"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // With no audio frame to read, the mp4 muxer cannot write the AC-3 or E-AC-3 sample entry: FFmpeg
  // logs that it cannot write the moov before the first packet, writes the moov's header with no
  // size, and still exits 0.
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"ac3", "eac3"})
  @DisplayName(
      "Should report a failed transcode and upload no initialization segment when an attempt that"
          + " encodes AC-3 or E-AC-3 audio starts after the audio's end")
  void shouldReportAFailedTranscodeAndUploadNothingWhenAnAc3AttemptStartsAfterTheAudiosEnd(
      String codec) throws Exception {
    var source = audioEndsLongBeforeVideoSource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    job.getExecutionBuilder()
        .setMediaSegmentCount(AUDIO_ENDS_LONG_BEFORE_VIDEO_SEGMENT_COUNT)
        .setStartSequenceNumber(AFTER_THE_AUDIOS_END_SEQUENCE_NUMBER);
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setCodec(codec)
        .setChannels(2)
        .setBitrateBitsPerSecond(192_000);

    var failure =
        executeExpectingFailure(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(failure.failure().getFailure())
        .isEqualTo(JobAttemptFailure.JOB_ATTEMPT_FAILURE_TRANSCODE_FAILED);
    assertThat(failure.uploads().names()).isEmpty();
  }

  @ParameterizedTest(name = "{0} from segment {2}")
  @MethodSource("videoEndsBeforeAudioSourcesAndStarts")
  @DisplayName(
      "Should upload every advertised segment, each opening at a keyframe with one period of"
          + " video and only its own interval's audio, when an encode holds the last frame of video"
          + " that ends before the audio")
  void shouldUploadEveryAdvertisedSegmentWhenAnEncodeHoldsTheLastFrameOfVideoThatEndsBeforeAudio(
      ThrowingSupplier<Path> sourceOf, int segmentCount, int startSequenceNumber) throws Throwable {
    var source = sourceOf.get();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    paddedTo(job, segmentCount);
    job.getDecisionBuilder().getVideoBuilder().setHoldLastFrameToAdvertisedEnd(true);
    job.getExecutionBuilder().setStartSequenceNumber(startSequenceNumber);

    var uploads = execute(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(uploads.names())
        .containsExactlyElementsOf(
            uploadNames(segmentCount).stream()
                .filter(
                    name -> !name.startsWith("segment") || numberOf(name) >= startSequenceNumber)
                .toList());
    for (var number = startSequenceNumber; number < segmentCount; number++) {
      var movie = Mp4Samples.read(uploads.contentOf(List.of("init.mp4", segmentName(number))));
      var video = movie.samplesOf(movie.videoTrack());
      assertThat(video).as("segment %d's video", number).hasSize(FRAMES_IN_A_PERIOD);
      assertThat(video.getFirst().isSync()).as("segment %d opens at a keyframe", number).isTrue();
      var interval = AudioInterval.ofDeliveredSegment(number, SEEKABLE_SOURCE_AUDIO_PERIOD_TICKS);
      var audio = movie.samplesOf(movie.audioTrack());
      assertThat(audio)
          .as("segment %d's audio starts inside its interval", number)
          .isNotEmpty()
          .allMatch(sample -> interval.holds(sample.presentationTime()));
      if (number > VIDEO_ENDS_BEFORE_AUDIO_LAST_VIDEO_SEGMENT) {
        assertThat(audio.stream().mapToLong(Mp4Samples.Sample::duration).sum())
            .as("segment %d holds one period of audio", number)
            .isGreaterThan(SEEKABLE_SOURCE_AUDIO_PERIOD_TICKS - LONGEST_AUDIO_FRAME_TICKS)
            .isLessThanOrEqualTo(SEEKABLE_SOURCE_AUDIO_PERIOD_TICKS + LONGEST_AUDIO_FRAME_TICKS);
      }
    }
  }

  static Stream<Arguments> videoEndsBeforeAudioSourcesAndStarts() {
    return Stream.of(
            Arguments.of(
                Named.<ThrowingSupplier<Path>>of(
                    "84 s of stereo AAC", WorkerMediaSmokeTest::videoEndsBeforeAudioSource),
                VIDEO_ENDS_BEFORE_AUDIO_SEGMENT_COUNT),
            Arguments.of(
                Named.<ThrowingSupplier<Path>>of(
                    "84 s of AC-3 that turns from stereo to 5.1 after the video's end",
                    WorkerMediaSmokeTest::videoEndsBeforeAudioThatChangesLayoutSource),
                VIDEO_ENDS_BEFORE_AUDIO_SEGMENT_COUNT),
            Arguments.of(
                Named.<ThrowingSupplier<Path>>of(
                    "1,764 s of 5.1 AC-3", WorkerMediaSmokeTest::longAudioAfterVideoEndSource),
                LONG_AUDIO_AFTER_VIDEO_SEGMENT_COUNT))
        .flatMap(
            source ->
                Stream.of(
                        0,
                        VIDEO_ENDS_BEFORE_AUDIO_LAST_VIDEO_SEGMENT,
                        VIDEO_ENDS_BEFORE_AUDIO_LAST_VIDEO_SEGMENT + 1)
                    .map(start -> Arguments.of(source.get()[0], source.get()[1], start)));
  }

  // The first segment after the video's end needs one period of held pictures and of audio, so it
  // must not wait for the downmix of the 1,700 s of audio after it. What FFmpeg had written when
  // that segment's upload started shows how far past it FFmpeg had encoded the audio.
  @Test
  @DisplayName(
      "Should upload the first segment after the video's end before encoding the audio after it"
          + " when an encode holds the last frame and downmixes half an hour of 5.1 audio")
  void shouldUploadTheFirstSegmentAfterTheVideosEndBeforeEncodingTheAudioAfterItWhenAHoldRuns()
      throws Exception {
    var source = longAudioAfterVideoEndSource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    paddedTo(job, LONG_AUDIO_AFTER_VIDEO_SEGMENT_COUNT);
    job.getDecisionBuilder().getVideoBuilder().setHoldLastFrameToAdvertisedEnd(true);
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO);
    var firstSegmentAfterTheVideo = VIDEO_ENDS_BEFORE_AUDIO_LAST_VIDEO_SEGMENT + 1;
    job.getExecutionBuilder().setStartSequenceNumber(firstSegmentAfterTheVideo);
    var segment = segmentName(firstSegmentAfterTheVideo);
    var launcher = new TeeingProcessLauncher(new ProcessBuilderLauncher());
    var attempt = fromProto(job.getJobAttemptId());
    var writtenWhenTheUploadStarted = new AtomicReference<byte[]>();

    var completion =
        executeToCompletion(
            engine(softwareCapabilities(), launcher),
            source.getParent(),
            job.build(),
            name -> {
              if (name.equals(segment)) {
                writtenWhenTheUploadStarted.compareAndSet(null, launcher.outputOf(attempt));
              }
            });

    assertThat(completion.uploads().names()).contains(segment);
    var written = Mp4Samples.read(wholeFragmentsOf(writtenWhenTheUploadStarted.get()));
    assertThat(written.samplesOf(written.audioTrack()))
        .as("the audio FFmpeg had written when %s's upload started", segment)
        .isNotEmpty();
    assertThat(written.latestSampleEnd())
        .as(
            "how far FFmpeg had written when %s's upload started; the audio ends at 1,764 s",
            segment)
        .isGreaterThan(Duration.ofSeconds(6L * firstSegmentAfterTheVideo))
        .isLessThan(HALF_OF_THE_LONG_AUDIO);
  }

  // The output's boxes up to the end of its last whole mdat, so that a snapshot taken while FFmpeg
  // writes reads as whole fragments.
  private static byte[] wholeFragmentsOf(byte[] output) {
    var buffer = ByteBuffer.wrap(output);
    var end = 0;
    var start = 0;
    while (start + 8 <= output.length && start + buffer.getInt(start) <= output.length) {
      if (new String(output, start + 4, 4, StandardCharsets.US_ASCII).equals("mdat")) {
        end = start + buffer.getInt(start);
      }

      start += buffer.getInt(start);
    }

    return Arrays.copyOf(output, end);
  }

  @Test
  @DisplayName(
      "Should write no held picture and no padded audio frame that starts at or after the"
          + " advertised end when it lies inside the last segment and the source's frame rate"
          + " differs from the output's")
  void shouldWriteNoPaddedMediaAtOrAfterTheAdvertisedEndWhenItLiesInsideTheLastSegment()
      throws Exception {
    var source = thirtyFramesPerSecondSource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    paddedTo(job, THIRTY_FPS_SOURCE_SEGMENT_COUNT);
    job.getDecisionBuilder().getVideoBuilder().setHoldLastFrameToAdvertisedEnd(true);
    job.getExecutionBuilder()
        .getAdvertisedEndBuilder()
        .setSeconds(INSIDE_THE_LAST_SEGMENT.getSeconds())
        .setNanos(INSIDE_THE_LAST_SEGMENT.getNano());

    var uploads = execute(engine(softwareCapabilities()), source.getParent(), job.build());

    var movie = Mp4Samples.read(uploads.contentOf(uploads.names()));
    var advertisedEndTicks = INSIDE_THE_LAST_SEGMENT.toMillis() * 48;
    assertThat(movie.samplesOf(movie.videoTrack()))
        .as("one picture for each slot of the 24 fps grid that starts before 15.01 s")
        .hasSize(361);
    assertThat(movie.samplesOf(movie.audioTrack()))
        .as("the padded audio frames up to the advertised end, the last one whole")
        .allMatch(sample -> sample.presentationTime() < advertisedEndTicks)
        .anyMatch(sample -> sample.presentationTime() + sample.duration() >= advertisedEndTicks);
  }

  @ParameterizedTest(name = "{0} in {1}")
  @MethodSource("paddedCodecsAndModes")
  @DisplayName(
      "Should write an initialization segment byte-identical to the normal attempt's and every"
          + " advertised segment when an attempt that pads its audio starts after the audio's end")
  void shouldWriteTheNormalAttemptsInitializationSegmentWhenAPaddedAttemptStartsAfterTheAudiosEnd(
      String codec, TranscodeMode mode) throws Exception {
    var source = audioEndsLongBeforeVideoSource();
    var normal = audioEndsLongBeforeVideoPaddedJob(source, mode, codec);
    var silenceOnly = audioEndsLongBeforeVideoPaddedJob(source, mode, codec);
    silenceOnly.getExecutionBuilder().setStartSequenceNumber(AFTER_THE_AUDIOS_END_SEQUENCE_NUMBER);

    var normalUploads = execute(engine(softwareCapabilities()), source.getParent(), normal.build());
    var silenceOnlyUploads =
        execute(engine(softwareCapabilities()), source.getParent(), silenceOnly.build());

    assertThat(silenceOnlyUploads.segments())
        .containsEntry("init.mp4", normalUploads.segments().get("init.mp4"));
    assertThat(silenceOnlyUploads.names())
        .containsExactlyElementsOf(
            uploadNames(AUDIO_ENDS_LONG_BEFORE_VIDEO_SEGMENT_COUNT).stream()
                .filter(
                    name ->
                        !name.startsWith("segment")
                            || numberOf(name) >= AFTER_THE_AUDIOS_END_SEQUENCE_NUMBER)
                .toList());
  }

  // The audio ends at 10 s, and each attempt seeks to 12 s: one that copies the video seeks to
  // its first segment's start, and one that encodes it one 6 s period earlier and holds the last
  // frame past the video's end at 18 s. Neither reads a source sample.
  @ParameterizedTest(name = "{0} from segment {2} of {1}")
  @CsvSource({"TRANSCODE_MODE_AUDIO_TRANSCODE, 3, 2", "TRANSCODE_MODE_FULL_TRANSCODE, 4, 3"})
  @DisplayName(
      "Should write the normal attempt's initialization segment and padded audio up to the"
          + " advertised end when a padded downmix to a named layout starts after the audio's end")
  void shouldWriteTheNormalAttemptsInitializationSegmentWhenAPaddedNamedDownmixStartsAfterTheEnd(
      TranscodeMode mode, int segmentCount, int startSequenceNumber) throws Exception {
    var source = surroundAudioEndsBeforeVideoSource();
    var normal = surroundAudioEndsBeforeVideoNamedStereoJob(source, mode);
    paddedTo(normal, segmentCount);
    var silenceOnly = surroundAudioEndsBeforeVideoNamedStereoJob(source, mode);
    paddedTo(silenceOnly, segmentCount);
    silenceOnly.getExecutionBuilder().setStartSequenceNumber(startSequenceNumber);

    var normalUploads = execute(engine(softwareCapabilities()), source.getParent(), normal.build());
    var silenceOnlyUploads =
        execute(engine(softwareCapabilities()), source.getParent(), silenceOnly.build());

    assertThat(silenceOnlyUploads.segments())
        .containsEntry("init.mp4", normalUploads.segments().get("init.mp4"));
    assertThat(silenceOnlyUploads.names())
        .containsExactlyElementsOf(
            uploadNames(segmentCount).stream()
                .filter(
                    name -> !name.startsWith("segment") || numberOf(name) >= startSequenceNumber)
                .toList());
    var movie = Mp4Samples.read(silenceOnlyUploads.contentOf(silenceOnlyUploads.names()));
    var audio = movie.samplesOf(movie.audioTrack());
    var firstSegmentStartTicks = startSequenceNumber * 6L * 48_000;
    var advertisedEndTicks = segmentCount * 6L * 48_000;
    assertThat(audio.getFirst().presentationTime())
        .as("the first padded AAC frame at 48 kHz, within a frame of the first segment's start")
        .isBetween(firstSegmentStartTicks, firstSegmentStartTicks + 1023);
    assertThat(audio)
        .as("the padded AAC frames up to the advertised end, the last one whole")
        .allMatch(sample -> sample.presentationTime() < advertisedEndTicks)
        .anyMatch(sample -> sample.presentationTime() + sample.duration() >= advertisedEndTicks);
  }

  static Stream<Arguments> paddedCodecsAndModes() {
    return Stream.of("aac", "ac3", "eac3")
        .flatMap(
            codec ->
                Stream.of(
                        TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE,
                        TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE)
                    .map(mode -> Arguments.of(codec, mode)));
  }

  @Test
  @DisplayName(
      "Should decode to every source sample unchanged and in place, then only digital silence up to"
          + " the advertised end, when a lossless encode pads audio that ends early")
  void shouldDecodeToEverySourceSampleThenOnlySilenceWhenALosslessEncodePadsAudioThatEndsEarly()
      throws Exception {
    var source = flacAudioEndsBeforeVideoSource();
    var padded = flacAudioEndsBeforeVideoJob(source);
    paddedTo(padded, FLAC_SOURCE_SEGMENT_COUNT);
    var unpadded = flacAudioEndsBeforeVideoJob(source);

    var paddedUploads = execute(engine(softwareCapabilities()), source.getParent(), padded.build());
    var unpaddedUploads =
        execute(engine(softwareCapabilities()), source.getParent(), unpadded.build());

    var sourceSamples = decodedAudio(Files.readAllBytes(source));
    var unpaddedSamples = decodedAudio(unpaddedUploads.contentOf(unpaddedUploads.names()));
    assertThat(unpaddedSamples).as("the unpadded encode").isEqualTo(sourceSamples);
    assertSourceSamplesThenSilenceToTheAdvertisedEnd(
        decodedAudio(paddedUploads.contentOf(paddedUploads.names())), sourceSamples);
  }

  // The job names the second of two FLAC tracks, which ends at 10 s. The first runs to the video's
  // end at another pitch, so a padded encode of the first would hold no silence.
  @Test
  @DisplayName(
      "Should decode to every sample of the audio stream that the job names, then only digital"
          + " silence up to the advertised end, when a lossless encode pads the second audio stream")
  void shouldDecodeToEverySampleOfTheNamedStreamThenOnlySilenceWhenALosslessEncodePadsTheSecond()
      throws Exception {
    var source = flacSecondAudioEndsBeforeVideoSource();
    var job = flacAudioEndsBeforeVideoJob(source);
    job.getDecisionBuilder().getAudioBuilder().setStreamIndex(2);
    paddedTo(job, FLAC_SOURCE_SEGMENT_COUNT);

    var uploads = execute(engine(softwareCapabilities()), source.getParent(), job.build());

    assertSourceSamplesThenSilenceToTheAdvertisedEnd(
        decodedAudio(uploads.contentOf(uploads.names())), decodedAudio(source, "0:2"));
  }

  private static void assertSourceSamplesThenSilenceToTheAdvertisedEnd(
      byte[] paddedSamples, byte[] sourceSamples) {
    var advertisedEndBytes = FLAC_SOURCE_SEGMENT_COUNT * 6 * FLAC_SOURCE_SAMPLE_RATE * 2;
    assertThat(Arrays.copyOf(paddedSamples, sourceSamples.length))
        .as("the padded encode up to the source's end")
        .isEqualTo(sourceSamples);
    assertThat(Arrays.copyOfRange(paddedSamples, sourceSamples.length, paddedSamples.length))
        .as("the padded encode after the source's end")
        .isNotEmpty()
        .containsOnly((byte) 0);
    assertThat(paddedSamples.length)
        .isGreaterThanOrEqualTo(advertisedEndBytes)
        .isLessThanOrEqualTo(advertisedEndBytes + FLAC_SAMPLES_PAST_THE_END_AT_MOST * 2);
  }

  // The encoder takes 48 kHz stereo floats, so FFmpeg resamples and downmixes the 96 kHz 16-bit 5.1
  // source. AC-3 codes the same frames from the same input, so the padded encode's frames before
  // the source's end show that its encoder read the same samples there.
  @Test
  @DisplayName(
      "Should write the unpadded encode's audio frames up to the source's end when a padded encode"
          + " resamples and downmixes the audio")
  void shouldWriteTheUnpaddedEncodesAudioFramesWhenAPaddedEncodeResamplesAndDownmixesTheAudio()
      throws Exception {
    var source = surroundAudioEndsBeforeVideoSource();
    var padded = surroundAudioEndsBeforeVideoJob(source);
    paddedTo(padded, FLAC_SOURCE_SEGMENT_COUNT);
    var unpadded = surroundAudioEndsBeforeVideoJob(source);

    var paddedUploads = execute(engine(softwareCapabilities()), source.getParent(), padded.build());
    var unpaddedUploads =
        execute(engine(softwareCapabilities()), source.getParent(), unpadded.build());

    var paddedAudio = audioPacketsOf(paddedUploads);
    var unpaddedAudio = audioPacketsOf(unpaddedUploads);
    assertThat(paddedAudio).hasSizeGreaterThan(unpaddedAudio.size());
    assertThat(paddedAudio.subList(0, unpaddedAudio.size()))
        .isNotEmpty()
        .containsExactlyElementsOf(unpaddedAudio);
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

  @ParameterizedTest(name = "from segment {0}")
  @ValueSource(ints = {0, 2})
  @DisplayName(
      "Should upload every listed segment, each opening at the source keyframe in its first slot"
          + " with only its listed interval's audio, when a copy's job merges 3 s slots between"
          + " keyframes 10 s apart")
  void shouldUploadEveryListedSegmentWhenACopysJobMergesSlotsBetweenKeyframesTenSecondsApart(
      int startSequenceNumber) throws Exception {
    var source = tenSecondKeyframeSource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_REMUX);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(SLOT_SECONDS)
        .setMediaSegmentCount(TEN_SECOND_KEYFRAME_SEGMENT_STARTS.size())
        .addAllSegmentStartSlots(TEN_SECOND_KEYFRAME_SEGMENT_STARTS)
        .setStartSequenceNumber(startSequenceNumber)
        .setSeekPositionSeconds(
            TEN_SECOND_KEYFRAME_SEGMENT_STARTS.get(startSequenceNumber) * SLOT_SECONDS);

    var uploads = execute(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(uploads.names())
        .containsExactlyElementsOf(
            uploadNames(TEN_SECOND_KEYFRAME_SEGMENT_STARTS.size()).stream()
                .filter(
                    name -> !name.startsWith("segment") || numberOf(name) >= startSequenceNumber)
                .toList());
    for (var number = startSequenceNumber;
        number < TEN_SECOND_KEYFRAME_SEGMENT_STARTS.size();
        number++) {
      var movie = Mp4Samples.read(uploads.contentOf(List.of("init.mp4", segmentName(number))));
      var firstSlot = listedInterval(number, movie.videoTrack().timescale());
      var video = movie.samplesOf(movie.videoTrack()).getFirst();
      assertThat(video.isSync()).as("segment %d opens at a keyframe", number).isTrue();
      assertThat(video.presentationTime())
          .as("segment %d opens in its first slot", number)
          .isBetween(firstSlot.start(), firstSlot.start() + SLOT_SECONDS * firstSlot.timescale());
      var audioInterval = listedInterval(number, movie.audioTrack().timescale());
      assertThat(movie.samplesOf(movie.audioTrack()))
          .as("segment %d's audio starts inside its listed interval", number)
          .isNotEmpty()
          .allMatch(sample -> audioInterval.interval().holds(sample.presentationTime()));
    }
  }

  @ParameterizedTest(name = "from segment {0}")
  @ValueSource(ints = {0, 2})
  @DisplayName(
      "Should upload every listed segment, each opening at a keyframe on its listed start and"
          + " keeping the encoder's other keyframes inside it, when an encode's job merges 3 s"
          + " slots between source keyframes 10 s apart")
  void shouldUploadEveryListedSegmentOpeningAtItsListedStartWhenAnEncodesJobMergesSlots(
      int startSequenceNumber) throws Exception {
    var source = tenSecondKeyframeSource();
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(SLOT_SECONDS)
        .setMediaSegmentCount(TEN_SECOND_KEYFRAME_SEGMENT_STARTS.size())
        .addAllSegmentStartSlots(TEN_SECOND_KEYFRAME_SEGMENT_STARTS)
        .setStartSequenceNumber(startSequenceNumber)
        .setSeekPositionSeconds(
            TEN_SECOND_KEYFRAME_SEGMENT_STARTS.get(startSequenceNumber) * SLOT_SECONDS);

    var uploads = execute(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(uploads.names())
        .containsExactlyElementsOf(
            uploadNames(TEN_SECOND_KEYFRAME_SEGMENT_STARTS.size()).stream()
                .filter(
                    name -> !name.startsWith("segment") || numberOf(name) >= startSequenceNumber)
                .toList());
    // Unless the muxer writes negative composition offsets, it can shift presentation times by up
    // to libx264's reordering delay of two frames; FFmpeg releases differ in what they write.
    for (var number = startSequenceNumber;
        number < TEN_SECOND_KEYFRAME_SEGMENT_STARTS.size();
        number++) {
      var movie = Mp4Samples.read(uploads.contentOf(List.of("init.mp4", segmentName(number))));
      var timescale = movie.videoTrack().timescale();
      var reorderingDelay = 2 * timescale / 24;
      var listed = listedInterval(number, timescale);
      var video = movie.samplesOf(movie.videoTrack());
      assertThat(video.getFirst().isSync()).as("segment %d opens at a keyframe", number).isTrue();
      assertThat(video.getFirst().presentationTime())
          .as("segment %d opens on its listed start", number)
          .isBetween(listed.start(), listed.start() + reorderingDelay);
      assertThat(video)
          .as("segment %d's frames all start inside its listed interval", number)
          .allMatch(
              sample ->
                  sample.presentationTime() >= listed.start()
                      && sample.presentationTime() - reorderingDelay < listed.interval().end());
    }

    var merged = Mp4Samples.read(uploads.contentOf(List.of("init.mp4", segmentName(2))));
    assertThat(merged.samplesOf(merged.videoTrack()))
        .as("the 12 s merged segment 2 keeps the encoder's keyframes after its first")
        .filteredOn(Mp4Samples.Sample::isSync)
        .hasSizeGreaterThan(1);
  }

  @Test
  @DisplayName(
      "Should show the source's moving pictures after a gap between pictures when an encode from"
          + " the start reads an MP4 that gives the picture after the gap the gap's duration")
  void shouldShowTheSourcesMovingPicturesAfterAPictureGapWhenAnEncodeReadsAnMp4WithBFrames()
      throws Exception {
    var source = pictureGapSource(PictureGapSource.MP4_WITH_B_FRAMES);

    var uploads =
        execute(engine(softwareCapabilities()), source.getParent(), pictureGapJob(source).build());

    // FFmpeg's constant-rate conversion repeated the picture after the gap for the 5.083 s that
    // the MP4 gives as its duration, and showed 4 distinct pictures in these 5 s.
    var afterTheGap =
        decodedPictures(uploads.contentOf(List.of("init.mp4", segmentName(3)))).stream()
            .filter(
                picture -> picture.isWithin(Duration.ofMillis(31_200), Duration.ofMillis(36_200)))
            .toList();
    assertThat(afterTheGap).as("5 s of pictures at 24 fps").hasSize(120);
    assertThat(afterTheGap.stream().map(DecodedPicture::md5).distinct().count())
        .as("the distinct pictures of the source's moving test pattern")
        .isGreaterThan(afterTheGap.size() / 2);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(PictureGapSource.class)
  @DisplayName(
      "Should open the replacement's first segment on its listed start with the start attempt's"
          + " picture and frame count, and its audio on that start, when an encode reads from the"
          + " server's keyframe before a gap between pictures")
  void
      shouldOpenTheReplacementWithTheStartAttemptsPictureWhenAnEncodeReadsFromTheKeyframeBeforeAGap(
          PictureGapSource shape) throws Exception {
    var source = pictureGapSource(shape);
    var startAttempt =
        execute(engine(softwareCapabilities()), source.getParent(), pictureGapJob(source).build());
    var replacementJob = pictureGapJob(source);
    replacementJob.getExecutionBuilder().setStartSequenceNumber(3).setSeekPositionSeconds(30);
    setSeekPosition(
        replacementJob.getExecutionBuilder(),
        decodeTimeOfTheLastKeyframePresentedBy(source, new BigDecimal("29.5")));

    var replacement =
        execute(engine(softwareCapabilities()), source.getParent(), replacementJob.build());

    assertSegment3OpensWithTheStartAttemptsPictureAndAudio(startAttempt, replacement);
  }

  // An attempt that encodes a profile 5 source reads the server's keyframe position as every other
  // encoded replacement does. Read from one period early instead, 27 s, inside the gap, the MP4
  // demuxer landed on the keyframe after the gap, whose decode time lies before 27 s, and the
  // replacement lost the picture held from 25.958 s. Both attempts run libx264 on one thread, as
  // the encode of the copy feeds it frames at its own pace.
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"mp4", "mkv", "ts"})
  @DisplayName(
      "Should open a profile 5 replacement's first segment with the start attempt's picture, frame"
          + " count and audio when it reads from the server's keyframe before a gap between"
          + " pictures longer than a period")
  void
      shouldOpenAProfile5ReplacementWithTheStartAttemptsPictureWhenItReadsFromTheServersKeyframeBeforeAGapLongerThanAPeriod(
          String container) throws Exception {
    var source = hevcPictureGapSource(container);
    var engine = engine(softwareCapabilities(), withX264ThreadsOfOne(new ProcessBuilderLauncher()));
    var startAttempt = execute(engine, source.getParent(), profile5PictureGapJob(source).build());
    var replacementJob = profile5PictureGapJob(source);
    replacementJob.getExecutionBuilder().setStartSequenceNumber(3).setSeekPositionSeconds(30);
    setSeekPosition(
        replacementJob.getExecutionBuilder(),
        decodeTimeOfTheLastKeyframePresentedBy(source, new BigDecimal("29.5")));

    var replacement = execute(engine, source.getParent(), replacementJob.build());

    assertSegment3OpensWithTheStartAttemptsPictureAndAudio(startAttempt, replacement);
  }

  // The gap job for a Dolby Vision profile 5 source whose RPUs may start samples, which the worker
  // encodes from a copy of its video. The source holds no RPU, so the mover leaves every sample as
  // it is.
  private static VariantJob.Builder profile5PictureGapJob(Path source) {
    var job = pictureGapJob(source);
    withProfile5Source(job, DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);
    return job;
  }

  // The replacement's segment 3 opens on a keyframe where the start attempt's does, with the same
  // frame count and first picture, the picture held from 25.958 s, and its audio joins the start
  // attempt's on segment 3's start.
  private static void assertSegment3OpensWithTheStartAttemptsPictureAndAudio(
      MediaUploads startAttempt, MediaUploads replacement) throws Exception {
    var startSegment = startAttempt.contentOf(List.of("init.mp4", segmentName(3)));
    var replacementSegment = replacement.contentOf(List.of("init.mp4", segmentName(3)));
    var started = Mp4Samples.read(startSegment);
    var replaced = Mp4Samples.read(replacementSegment);
    var startedVideo = started.samplesOf(started.videoTrack());
    var replacedVideo = replaced.samplesOf(replaced.videoTrack());
    assertThat(replacedVideo.getFirst().isSync()).as("the segment opens at a keyframe").isTrue();
    assertThat(replacedVideo.getFirst().presentationTime())
        .as("the replacement's segment 3 opens where the start attempt's does")
        .isEqualTo(startedVideo.getFirst().presentationTime());
    assertThat(replacedVideo).as("segment 3's frames").hasSameSizeAs(startedVideo);
    assertThat(decodedPictures(replacementSegment).getFirst().md5())
        .as("segment 3's first picture, the picture held from 25.958 s")
        .isEqualTo(decodedPictures(startSegment).getFirst().md5());
    assertAudioJoinsOnSegment3sStart(startAttempt, replaced);
  }

  // With the server's keyframe positions 20 s and 21 s, the 48 kHz source's AAC and AC-3 audio
  // starts 9 s and 8 s of whole frames before 30 s. A job that names stereo encodes Opus with
  // libopus and FLAC in frames of 2,400 samples.
  @ParameterizedTest(name = "{0}, keyframe {1}, named stereo {2}")
  @CsvSource({
    "ac3,, false",
    "eac3,, false",
    "libopus,, false",
    "flac,, false",
    "alac,, false",
    "aac, 20, false",
    "ac3, 21, false",
    "opus,, true",
    "flac,, true",
    "aac, 20, true"
  })
  @DisplayName(
      "Should open the replacement's audio with a frame on its first segment's start and leave no"
          + " gap after the start attempt's audio when an encoded replacement attempt encodes its"
          + " audio")
  void shouldOpenTheReplacementsAudioOnItsFirstSegmentsStartWhenAnEncodedReplacementEncodesAudio(
      String codec, BigDecimal keyframePosition, boolean namedStereo) throws Exception {
    var source = pictureGapSource(PictureGapSource.MP4_WITHOUT_B_FRAMES);
    var startAttempt =
        execute(
            engine(softwareCapabilities()),
            source.getParent(),
            namingStereoWhen(namedStereo, pictureGapAudioJob(source, codec)).build());

    var replacement =
        execute(
            engine(softwareCapabilities()),
            source.getParent(),
            namingStereoWhen(
                    namedStereo, pictureGapAudioReplacementJob(source, codec, keyframePosition))
                .build());

    var replaced = Mp4Samples.read(replacement.contentOf(List.of("init.mp4", segmentName(3))));
    assertAudioJoinsOnSegment3sStart(startAttempt, replaced);
  }

  // Segment 3 starts at 2,700,012/90,000 s, 30.0001333... s, which falls between two samples of the
  // 48 kHz source: sample 1,440,006.4. The replacement's audio must open on sample 1,440,007, the
  // first at or after that start, as a lower variant that starts beside a copy does. A trim at the
  // start rounded to a microsecond, 30.000134 s, rounds to sample 1,440,006, before the start.
  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"aac", "ac3", "libopus", "flac"})
  @DisplayName(
      "Should open the replacement's audio on the first sample at or after its first segment's"
          + " exact start and leave no gap after the start attempt's audio")
  void shouldOpenTheReplacementsAudioOnTheFirstSampleAtOrAfterItsFirstSegmentsExactStart(
      String codec) throws Exception {
    var source = pictureGapSource(PictureGapSource.MP4_WITHOUT_B_FRAMES);
    var startAttempt =
        execute(
            engine(softwareCapabilities()),
            source.getParent(),
            exactStartGapAudioJob(source, codec).build());
    var replacementJob = exactStartGapAudioJob(source, codec);
    replacementJob.getExecutionBuilder().setStartSequenceNumber(3).setSeekPositionSeconds(30);

    var replacement =
        execute(engine(softwareCapabilities()), source.getParent(), replacementJob.build());

    var replaced = Mp4Samples.read(replacement.contentOf(List.of("init.mp4", segmentName(3))));
    var sample = new BigFraction(1, replaced.audioTrack().timescale());
    var firstSampleAtOrAfterTheStart =
        sample.ticksIn(new BigFraction(2_700_012, MPEG_TS_TICKS_PER_SECOND).ceilingIn(sample));
    assertThat(replaced.audioTrack().timescale()).isEqualTo(48_000);
    assertThat(replaced.samplesOf(replaced.audioTrack()).getFirst().presentationTime())
        .as("the replacement's audio opens on the first sample at or after the exact start")
        .isEqualTo(firstSampleAtOrAfterTheStart);
    var before = Mp4Samples.read(startAttempt.contentOf(List.of("init.mp4", segmentName(2))));
    var lastAudioBefore = before.samplesOf(before.audioTrack()).getLast();
    assertThat(lastAudioBefore.end())
        .as("the start attempt's segment 2 audio reaches the replacement's, by less than a frame")
        .isGreaterThanOrEqualTo(firstSampleAtOrAfterTheStart)
        .isLessThan(firstSampleAtOrAfterTheStart + lastAudioBefore.duration());
  }

  // The gap source's job on exact starts in place of slots 0, 3, 6 and 10: segment 3 starts 12
  // ticks of 1/90,000 s after 30 s.
  private static VariantJob.Builder exactStartGapAudioJob(Path source, String codec) {
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getDecisionBuilder().getAudioBuilder().setCodec(codec);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(SLOT_SECONDS)
        .setMediaSegmentCount(4)
        .addAllSegmentStartTicks(List.of(0L, 810_000L, 1_620_000L, 2_700_012L))
        .setSegmentStartTimeBase(
            Fraction.newBuilder().setNumerator(1).setDenominator(MPEG_TS_TICKS_PER_SECOND));
    return job;
  }

  // FLAC and ALAC are lossless, so the replacement's audio decodes to the start attempt's samples
  // only when its frames carry the source's samples from the listed start on, at the times that
  // the start attempt gives them. Timestamps alone would also pass audio taken from a frame
  // earlier.
  @ParameterizedTest(name = "{0}, keyframe {1}")
  @CsvSource({"flac,", "alac,", "flac, 20"})
  @DisplayName(
      "Should decode the replacement's audio from its first segment's start to the start attempt's"
          + " samples when an encoded replacement attempt encodes lossless audio")
  void
      shouldDecodeTheReplacementsAudioToTheStartAttemptsSamplesWhenAReplacementEncodesLosslessAudio(
          String codec, BigDecimal keyframePosition) throws Exception {
    var source = pictureGapSource(PictureGapSource.MP4_WITHOUT_B_FRAMES);
    var startAttempt =
        execute(
            engine(softwareCapabilities()),
            source.getParent(),
            pictureGapAudioJob(source, codec).build());

    var replacement =
        execute(
            engine(softwareCapabilities()),
            source.getParent(),
            pictureGapAudioReplacementJob(source, codec, keyframePosition).build());

    assertThat(
            decodedAudioFrom30To36Seconds(
                replacement.contentOf(List.of("init.mp4", segmentName(3)))))
        .as("the MD5 of the replacement's decoded samples from 30 s to 36 s")
        .isEqualTo(
            decodedAudioFrom30To36Seconds(
                startAttempt.contentOf(List.of("init.mp4", segmentName(2), segmentName(3)))));
  }

  // White noise in eight 24-bit channels at 96 kHz leaves FLAC nothing to compress, about 2.3 MB of
  // audio a second. The source's only keyframe lies 96 s before the replacement's first segment,
  // so audio encoded from a second after that keyframe, or copied from it, would fill the first
  // fragment far past the producer's 128 MiB cap before the first picture. Without the server's
  // keyframe position, the seek one period early lands on that keyframe too, also for an attempt
  // that holds the last frame and so starts its video and audio at that seek.
  @ParameterizedTest(name = "{0}, keyframe position {1}, holds the last frame: {2}")
  @CsvSource({
    "TRANSCODE_MODE_FULL_TRANSCODE, 0, false",
    "TRANSCODE_MODE_VIDEO_TRANSCODE, 0, false",
    "TRANSCODE_MODE_VIDEO_TRANSCODE, , false",
    "TRANSCODE_MODE_FULL_TRANSCODE, , true",
    "TRANSCODE_MODE_VIDEO_TRANSCODE, , true"
  })
  @DisplayName(
      "Should complete an encoded replacement attempt of high-bitrate lossless audio, encoded or"
          + " copied, when the keyframe its seek reads lies far before its first segment")
  void shouldCompleteAReplacementOfHighBitrateLosslessAudioWhenTheKeyframeLiesFarBeforeIt(
      TranscodeMode mode, BigDecimal keyframePosition, boolean holdsLastFrame) throws Exception {
    var source = oneKeyframeEightChannelNoiseSource();
    var job = seekableSourceJob(source, mode);
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setCodec("flac")
        .setChannels(8)
        .setBitrateBitsPerSecond(0);
    job.getExecutionBuilder().setMediaSegmentCount(17).setStartSequenceNumber(16);
    job.getExecutionBuilder().getAdvertisedEndBuilder().setSeconds(102);
    job.getDecisionBuilder().getVideoBuilder().setHoldLastFrameToAdvertisedEnd(holdsLastFrame);
    if (keyframePosition != null) {
      setSeekPosition(job.getExecutionBuilder(), keyframePosition);
    }

    var completion =
        executeToCompletion(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(completion.uploads().names()).contains(segmentName(16));
  }

  // The replacement decodes its video from a keyframe far before its first segment at 24 s: the
  // server's keyframe at zero, or, without it, the keyframe that its seek one period early reads.
  // FFmpeg's whole output must start the copied audio at or after 18 s, one period before that
  // segment, and its copied packets in segment 4 must match the start attempt's, which one FFmpeg
  // reads from one input, on that attempt's timeline. The replacement reads its audio from the
  // input that its video seeks: the source input of one FFmpeg, or of the encode of a profile 5
  // copy. The MPEG-TS sources start at 11.4 s, and their audio starts 0.5 s after their video.
  @ParameterizedTest(name = "{0} with {1}, {2}, keyframe position {3}")
  @CsvSource({
    "mp4, aac, libx264, 0",
    "mkv, flac, libx264, 0",
    "ts, aac, libx264, 0",
    "ts, ac3, libx264, 0",
    "mp4, aac, libx264,",
    "mkv, flac, libx265, 0",
    "ts, ac3, libx265, 0",
    "mkv, flac, libx265,"
  })
  @DisplayName(
      "Should start the copied audio one period before the first segment and copy the start"
          + " attempt's packets at its times when an encoded replacement's keyframe lies far back")
  void shouldStartTheCopiedAudioOnePeriodBackAndMatchTheStartAttemptWhenTheKeyframeLiesFarBack(
      String container, String codec, String videoEncoder, BigDecimal keyframePosition)
      throws Exception {
    var source = oneKeyframeDelayedAudioSource(container, codec, videoEncoder);
    var startAttempt =
        execute(engine(softwareCapabilities()), source.getParent(), copiedAudioJob(source, codec));
    var replacementJob = copiedAudioJob(source, codec).toBuilder();
    replacementJob.getExecutionBuilder().setStartSequenceNumber(4);
    if (keyframePosition != null) {
      setSeekPosition(replacementJob.getExecutionBuilder(), keyframePosition);
    }

    if (videoEncoder.equals("libx265")) {
      withProfile5Source(
          replacementJob, DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);
    }

    var launcher = new TeeingProcessLauncher(new ProcessBuilderLauncher());

    var replacement =
        execute(
            engine(softwareCapabilities(), launcher), source.getParent(), replacementJob.build());

    var output = Mp4Samples.read(launcher.outputOf(fromProto(replacementJob.getJobAttemptId())));
    assertThat(output.samplesOf(output.audioTrack()).getFirst().presentationTime())
        .as("the start of FFmpeg's first copied audio packet")
        .isGreaterThanOrEqualTo(18 * output.audioTrack().timescale());
    var replaced = Mp4Samples.read(replacement.contentOf(List.of("init.mp4", segmentName(4))));
    var started = Mp4Samples.read(startAttempt.contentOf(List.of("init.mp4", segmentName(4))));
    assertThat(replaced.samplesOf(replaced.audioTrack()))
        .as("the replacement's copied audio packets in segment 4")
        .isNotEmpty()
        .isEqualTo(started.samplesOf(started.audioTrack()));
  }

  // A job that encodes the source's video and copies its audio over five 6 s segments.
  private static VariantJob copiedAudioJob(Path source, String codec) {
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE);
    job.getDecisionBuilder().getAudioBuilder().setCodec(codec).setBitrateBitsPerSecond(0);
    job.getExecutionBuilder().setMediaSegmentCount(5);
    return job.build();
  }

  // 30 s of video from the encoder whose only keyframe is its first picture, and stereo pink noise
  // in the codec from 0.5 s on. An MPEG-TS source starts 10 s after the muxer's own start.
  private static synchronized Path oneKeyframeDelayedAudioSource(
      String container, String codec, String videoEncoder) throws Exception {
    var source =
        seekableSources.resolve(
            "one-keyframe-" + videoEncoder + "-delayed-" + codec + "." + container);
    if (!generatedSeekableSources.contains(source)) {
      var arguments =
          new ArrayList<>(
              List.of(
                  "-f",
                  "lavfi",
                  "-i",
                  "testsrc=size=64x36:rate=24:duration=30",
                  "-itsoffset",
                  "0.5",
                  "-f",
                  "lavfi",
                  "-i",
                  "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5:duration=29.5,"
                      + "aformat=channel_layouts=stereo",
                  "-c:v",
                  videoEncoder));
      arguments.addAll(
          switch (videoEncoder) {
            case "libx265" -> List.of("-x265-params", "keyint=-1:scenecut=0:log-level=error");
            default -> List.of("-x264-params", "keyint=infinite:scenecut=0");
          });
      arguments.addAll(List.of("-c:a", codec));
      arguments.addAll(
          switch (container) {
            case "ts" -> List.of("-output_ts_offset", "10");
            default -> List.<String>of();
          });
      runFfmpeg(arguments, source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // 102 s of H.264 whose only keyframe is its first picture, and independent white noise in eight
  // 24-bit FLAC channels at 96 kHz.
  private static synchronized Path oneKeyframeEightChannelNoiseSource() throws Exception {
    var source = seekableSources.resolve("one-keyframe-eight-channel-noise.mkv");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24",
              "-f",
              "lavfi",
              "-i",
              "aevalsrc=random(0)*2-1|random(1)*2-1|random(2)*2-1|random(3)*2-1"
                  + "|random(4)*2-1|random(5)*2-1|random(6)*2-1|random(7)*2-1:s=96000:c=7.1",
              "-t",
              "102",
              "-c:v",
              "libx264",
              "-x264-params",
              "keyint=infinite:scenecut=0",
              "-c:a",
              "flac",
              "-sample_fmt",
              "s32",
              "-bits_per_raw_sample",
              "24"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // The encode of a profile 5 source's copy reads the audio from the source, a second input beside
  // the copy's video, so its FLAC frames carry the source's samples at the start attempt's times
  // only when that input's seek and timestamps match the single FFmpeg's.
  @ParameterizedTest(name = "keyframe {0}")
  @NullSource
  @ValueSource(strings = "20")
  @DisplayName(
      "Should decode a profile 5 replacement's audio from its first segment's start to the start"
          + " attempt's samples when the encode of the source's copy reads lossless audio from the"
          + " source")
  void
      shouldDecodeAProfile5ReplacementsAudioToTheStartAttemptsSamplesWhenTheEncodeOfTheCopyReadsLosslessAudioFromTheSource(
          BigDecimal keyframePosition) throws Exception {
    var source = hevcPictureGapSource("mp4");
    var startJob = pictureGapAudioJob(source, "flac");
    withProfile5Source(startJob, DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);
    var startAttempt =
        execute(engine(softwareCapabilities()), source.getParent(), startJob.build());
    var replacementJob = pictureGapAudioReplacementJob(source, "flac", keyframePosition);
    withProfile5Source(
        replacementJob, DolbyVisionRpuPlacement.DOLBY_VISION_RPU_PLACEMENT_STARTS_SAMPLE);

    var replacement =
        execute(engine(softwareCapabilities()), source.getParent(), replacementJob.build());

    assertThat(
            decodedAudioFrom30To36Seconds(
                replacement.contentOf(List.of("init.mp4", segmentName(3)))))
        .as("the MD5 of the replacement's decoded samples from 30 s to 36 s")
        .isEqualTo(
            decodedAudioFrom30To36Seconds(
                startAttempt.contentOf(List.of("init.mp4", segmentName(2), segmentName(3)))));
  }

  // The job, naming the stereo layout for its audio when namedStereo holds.
  private static VariantJob.Builder namingStereoWhen(boolean namedStereo, VariantJob.Builder job) {
    if (namedStereo) {
      job.getDecisionBuilder()
          .getAudioBuilder()
          .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO);
    }

    return job;
  }

  // A job that encodes the gap source's audio into this codec from the start.
  private static VariantJob.Builder pictureGapAudioJob(Path source, String codec) {
    var job = pictureGapJob(source);
    job.getDecisionBuilder().getAudioBuilder().setCodec(codec);
    return job;
  }

  // That job's replacement attempt from segment 3, which carries the server's keyframe position
  // when one is given.
  private static VariantJob.Builder pictureGapAudioReplacementJob(
      Path source, String codec, BigDecimal keyframePosition) {
    var job = pictureGapAudioJob(source, codec);
    job.getExecutionBuilder().setStartSequenceNumber(3).setSeekPositionSeconds(30);
    if (keyframePosition != null) {
      setSeekPosition(job.getExecutionBuilder(), keyframePosition);
    }

    return job;
  }

  // The MD5 of the media's decoded audio samples from 30 s to 36 s on the media's own timeline.
  private static String decodedAudioFrom30To36Seconds(byte[] media) throws Exception {
    var file = Files.createTempFile(longSources, "decoded", ".mp4");
    Files.write(file, media);
    return runToCompletion(
            List.of(
                "ffmpeg",
                "-nostdin",
                "-v",
                "error",
                "-copyts",
                "-i",
                file.toString(),
                "-map",
                "0:a:0",
                "-af",
                "atrim=start=30:end=36",
                "-f",
                "md5",
                "-"))
        .strip();
  }

  // Segment 3's listed start, 30 s, opens the replacement's audio with a frame, and the start
  // attempt's segment 2 audio reaches that start, by less than a frame, so the junction has no gap.
  private static void assertAudioJoinsOnSegment3sStart(
      MediaUploads startAttempt, Mp4Samples.Movie replaced) {
    var listedStart = 30 * replaced.audioTrack().timescale();
    assertThat(replaced.samplesOf(replaced.audioTrack()).getFirst().presentationTime())
        .as("the replacement's audio opens on the listed start")
        .isEqualTo(listedStart);
    var before = Mp4Samples.read(startAttempt.contentOf(List.of("init.mp4", segmentName(2))));
    var lastAudioBefore = before.samplesOf(before.audioTrack()).getLast();
    assertThat(lastAudioBefore.end())
        .as("the start attempt's segment 2 audio reaches the listed start, by less than a frame")
        .isGreaterThanOrEqualTo(listedStart)
        .isLessThan(listedStart + lastAudioBefore.duration());
  }

  // The decode time, as the worker's keyframe probe reports it, of the last keyframe that
  // presents at or before the time: the keyframe that the server sends an encoded replacement
  // attempt whose first segment starts 0.5 s after that time.
  private static BigDecimal decodeTimeOfTheLastKeyframePresentedBy(Path source, BigDecimal time)
      throws Exception {
    var keyframe =
        videoPacketsOnZeroBasedTimeline(source).stream()
            .filter(VideoPacket::keyframe)
            .filter(packet -> packet.presentationTime().compareTo(time) <= 0)
            .max(Comparator.comparing(VideoPacket::presentationTime))
            .orElseThrow();
    return probedDecodeTime(source, keyframe);
  }

  // A source whose pictures stop at 25.958 s and resume at 31.042 s, cut on the list [0, 3, 6, 10]
  // of 3 s slots: segment 3 starts at 30 s inside the gap, and the gap covers all of slot 9.
  private static VariantJob.Builder pictureGapJob(Path source) {
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(SLOT_SECONDS)
        .setMediaSegmentCount(TEN_SECOND_KEYFRAME_SEGMENT_STARTS.size())
        .addAllSegmentStartSlots(TEN_SECOND_KEYFRAME_SEGMENT_STARTS);
    return job;
  }

  private enum PictureGapSource {
    // libx264's B-frames give the keyframe after the gap a decode time 5 s before its presentation
    // time, and the MP4 muxer gives the picture after the gap the gap's 5.083 s duration.
    MP4_WITH_B_FRAMES("gap.mp4"),
    MP4_WITHOUT_B_FRAMES("gap-without-b-frames.mp4"),
    MATROSKA("gap.mkv"),
    MPEG_TS("gap.ts");

    private final String fileName;

    PictureGapSource(String fileName) {
      this.fileName = fileName;
    }
  }

  // 40 s of a moving test pattern at 24 fps and a 440 Hz tone. Every picture in [26 s, 31 s) is
  // dropped and the stream keeps variable-rate timestamps, so the picture at 25.958 s lasts until
  // 31.042 s. Keyframes lie at 0, 10 and 20 s and on the first picture after the gap. The Matroska
  // and MPEG-TS sources carry the B-frame MP4's streams unchanged.
  private static synchronized Path pictureGapSource(PictureGapSource shape) throws Exception {
    var withBFrames = seekableSources.resolve(PictureGapSource.MP4_WITH_B_FRAMES.fileName);
    if (!generatedSeekableSources.contains(withBFrames)) {
      runFfmpeg(pictureGapArguments(2), withBFrames);
      generatedSeekableSources.add(withBFrames);
    }

    var source = seekableSources.resolve(shape.fileName);
    if (generatedSeekableSources.contains(source)) {
      return source;
    }

    switch (shape) {
      case MP4_WITHOUT_B_FRAMES -> runFfmpeg(pictureGapArguments(0), source);
      case MATROSKA, MPEG_TS ->
          runFfmpeg(List.of("-i", withBFrames.toString(), "-map", "0", "-c", "copy"), source);
      case MP4_WITH_B_FRAMES -> throw new IllegalStateException("generated above");
    }

    generatedSeekableSources.add(source);
    return source;
  }

  private static List<String> pictureGapArguments(int bFrames) {
    return pictureGapArguments(
        List.of(
            "-c:v",
            "libx264",
            "-preset",
            "veryfast",
            "-x264-params",
            "keyint=100000:min-keyint=1:scenecut=0:bframes=" + bFrames));
  }

  // The gap source's pictures, tone and keyframes, with the arguments of its video encoder.
  private static List<String> pictureGapArguments(List<String> videoEncoder) {
    var arguments =
        new ArrayList<>(
            List.of(
                "-f",
                "lavfi",
                "-i",
                "testsrc2=size=64x36:rate=24",
                "-f",
                "lavfi",
                "-i",
                "sine=frequency=440:sample_rate=48000",
                "-t",
                "40",
                "-vf",
                "select=not(between(t\\,26\\,31))",
                "-fps_mode",
                "passthrough"));
    arguments.addAll(videoEncoder);
    arguments.addAll(List.of("-force_key_frames", "0,10,20,31", "-c:a", "aac", "-b:a", "96k"));
    return arguments;
  }

  // The gap source's pictures and tone as 10-bit HEVC from libx265 with 2 B-frames, in MP4, and
  // that MP4's streams unchanged in Matroska and MPEG-TS.
  private static synchronized Path hevcPictureGapSource(String container) throws Exception {
    var mp4 =
        buildFixtureOnce(
            seekableSources.resolve("gap-hevc.mp4"),
            output ->
                runFfmpeg(
                    pictureGapArguments(
                        List.of(
                            "-pix_fmt",
                            "yuv420p10le",
                            "-c:v",
                            "libx265",
                            "-x265-params",
                            "keyint=100000:min-keyint=1:scenecut=0:bframes=2:log-level=error",
                            "-forced-idr",
                            "1",
                            "-tag:v",
                            "hvc1")),
                    output));
    if (container.equals("mp4")) {
      return mp4;
    }

    return buildFixtureOnce(
        seekableSources.resolve("gap-hevc." + container),
        output -> runFfmpeg(List.of("-i", mp4.toString(), "-map", "0", "-c", "copy"), output));
  }

  // The media's decoded pictures in presentation order, each with its presentation time and the
  // MD5 of its picture.
  private static List<DecodedPicture> decodedPictures(byte[] media) throws Exception {
    var file = Files.createTempFile(longSources, "decoded", ".mp4");
    Files.write(file, media);
    var lines =
        runToCompletion(
                List.of(
                    "ffmpeg",
                    "-nostdin",
                    "-v",
                    "error",
                    "-copyts",
                    "-i",
                    file.toString(),
                    "-map",
                    "0:v:0",
                    "-fps_mode",
                    "passthrough",
                    "-f",
                    "framemd5",
                    "-"))
            .lines()
            .toList();
    var timeBase =
        lines.stream()
            .filter(line -> line.startsWith("#tb 0: "))
            .map(line -> line.substring("#tb 0: ".length()).split("/"))
            .findFirst()
            .orElseThrow();
    return lines.stream()
        .filter(line -> !line.startsWith("#") && !line.isBlank())
        .map(line -> line.split(",\\s*"))
        .map(
            fields ->
                new DecodedPicture(
                    Duration.ofNanos(
                        new BigDecimal(fields[1])
                            .multiply(new BigDecimal(timeBase[0]))
                            .movePointRight(9)
                            .divide(new BigDecimal(timeBase[1]), 0, RoundingMode.HALF_EVEN)
                            .longValueExact()),
                    fields[5]))
        .toList();
  }

  private record DecodedPicture(Duration presentationTime, String md5) {

    private boolean isWithin(Duration from, Duration until) {
      return presentationTime.compareTo(from) >= 0 && presentationTime.compareTo(until) < 0;
    }
  }

  @ParameterizedTest(name = "encoded at {0}/1001 fps")
  @ValueSource(ints = {30_000, 24_000})
  @DisplayName(
      "Should start each encoded segment on the first output frame at or after the copied"
          + " segment's exact start when an encode and a copy of an MPEG-TS source carry the copy's"
          + " exact starts")
  void shouldStartEachEncodedSegmentOnTheFirstOutputFrameAtOrAfterTheCopiedSegmentsExactStart(
      int frameRateNumerator) throws Exception {
    var source = exactStartMpegTsSource();
    var starts = exactStarts(source);
    var copy = exactStartJob(source, TranscodeMode.TRANSCODE_MODE_REMUX, starts);
    var encode = exactStartJob(source, TranscodeMode.TRANSCODE_MODE_VIDEO_TRANSCODE, starts);
    encode
        .getExecutionBuilder()
        .setOutputFrameRate(
            Fraction.newBuilder().setNumerator(frameRateNumerator).setDenominator(1001));
    var engine = engine(softwareCapabilities());

    var copied = execute(engine, source.getParent(), copy.build());
    var encoded = execute(engine, source.getParent(), encode.build());

    assertThat(copied.names()).containsExactlyElementsOf(uploadNames(starts.size()));
    assertThat(encoded.names()).containsExactlyElementsOf(uploadNames(starts.size()));
    for (var number = 0; number < starts.size(); number++) {
      var start = new BigFraction(starts.get(number), MPEG_TS_TICKS_PER_SECOND);
      var copiedStart = firstVideoSampleOf(copied, number);
      var encodedStart = firstVideoSampleOf(encoded, number);
      var frame = new BigFraction(1001, frameRateNumerator);
      assertThat(copiedStart)
          .as("copied segment %d opens at its exact start", number)
          .isEqualTo(start);
      assertThat(encodedStart)
          .as("encoded segment %d opens on the first output frame at or after its start", number)
          .isEqualTo(start.ceilingIn(frame));
      assertThat(encodedStart.minus(copiedStart))
          .as("encoded segment %d starts less than one output frame after the copied one", number)
          .isLessThan(frame);
    }
  }

  @Test
  @DisplayName(
      "Should open every copied segment at its listed keyframe and split the audio at the exact"
          + " starts when a copy of an MPEG-TS source with audio carries exact starts")
  void shouldOpenEveryCopiedSegmentAtItsListedKeyframeWhenAnMpegTsCopyWithAudioCarriesExactStarts()
      throws Exception {
    var source = exactStartMpegTsSourceWithAudio();
    var starts = exactStarts(source);
    var job = exactStartJob(source, TranscodeMode.TRANSCODE_MODE_REMUX, starts);
    job.getDecisionBuilder().getAudioBuilder().setMode(AudioMode.AUDIO_MODE_COPY);

    var uploads = execute(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(uploads.names()).containsExactlyElementsOf(uploadNames(starts.size()));
    assertThat(firstVideoSampleOf(uploads, 0))
        .as("segment 0 opens at the first keyframe, after the AAC priming that starts the timeline")
        .isGreaterThan(new BigFraction(0, 1));
    assertAudioStartsInsideItsExactInterval(uploads, 0, starts);
    for (var number = 1; number < starts.size(); number++) {
      assertThat(firstVideoSampleOf(uploads, number))
          .as("segment %d opens at its listed keyframe", number)
          .isEqualTo(new BigFraction(starts.get(number), MPEG_TS_TICKS_PER_SECOND));
      assertAudioStartsInsideItsExactInterval(uploads, number, starts);
    }
  }

  // A replacement attempt that copies the video seeks to its first segment's keyframe, whose exact
  // start lies between two samples of the 48 kHz audio. Its encoded audio must open on the first
  // sample at or after that start, where the start attempt's audio reaches, so the junction has no
  // gap. AC-3, E-AC-3 and Opus encoders need samples before that start for their lead, with or
  // without the server's keyframe position.
  @ParameterizedTest(name = "{0}, server position {1}")
  @CsvSource({
    "aac, true",
    "ac3, true",
    "eac3, true",
    "libopus, true",
    "ac3, false",
    "eac3, false",
    "libopus, false"
  })
  @DisplayName(
      "Should open a copy's encoded audio on the first sample at or after each segment's exact"
          + " start when a replacement attempt copies the video and encodes the audio")
  void shouldOpenACopysEncodedAudioOnTheFirstSampleAtOrAfterEachExactStartWhenAReplacementStarts(
      String codec, boolean sendsTheServersPosition) throws Exception {
    var source = exactStartMpegTsSourceWithAudio();
    var starts = exactStarts(source);
    var startAttempt =
        execute(
            engine(softwareCapabilities()),
            source.getParent(),
            copyEncodingAudioJob(source, starts, codec).build());

    for (var number = 1; number < starts.size(); number++) {
      var start = new BigFraction(starts.get(number), MPEG_TS_TICKS_PER_SECOND);
      var replacementJob = copyEncodingAudioJob(source, starts, codec);
      replacementJob.getExecutionBuilder().setStartSequenceNumber(number);
      if (sendsTheServersPosition) {
        replacementJob
            .getExecutionBuilder()
            .setSeekPositionSeconds(Math.toIntExact(new BigFraction(1, 1).ticksIn(start)));
        setSeekPosition(
            replacementJob.getExecutionBuilder(),
            new BigDecimal(starts.get(number))
                .divide(BigDecimal.valueOf(MPEG_TS_TICKS_PER_SECOND), 9, RoundingMode.CEILING));
      }

      var replacement =
          execute(engine(softwareCapabilities()), source.getParent(), replacementJob.build());

      var replaced =
          Mp4Samples.read(replacement.contentOf(List.of("init.mp4", segmentName(number))));
      var sample = new BigFraction(1, replaced.audioTrack().timescale());
      var firstSampleAtOrAfterTheStart = sample.ticksIn(start.ceilingIn(sample));
      assertThat(replaced.samplesOf(replaced.audioTrack()).getFirst().presentationTime())
          .as("segment %d's audio opens on the first sample at or after its exact start", number)
          .isEqualTo(firstSampleAtOrAfterTheStart);
      var before =
          Mp4Samples.read(startAttempt.contentOf(List.of("init.mp4", segmentName(number - 1))));
      var lastAudioBefore = before.samplesOf(before.audioTrack()).getLast();
      assertThat(lastAudioBefore.end())
          .as("segment %d's audio in the start attempt reaches the replacement's", number - 1)
          .isGreaterThanOrEqualTo(firstSampleAtOrAfterTheStart)
          .isLessThan(firstSampleAtOrAfterTheStart + lastAudioBefore.duration());
    }
  }

  // A copy of the source's video on its exact starts that encodes its audio in stereo.
  private static VariantJob.Builder copyEncodingAudioJob(
      Path source, List<Long> starts, String codec) {
    var job = exactStartJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE, starts);
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setMode(AudioMode.AUDIO_MODE_TRANSCODE)
        .setCodec(codec)
        .setChannels(2)
        .setBitrateBitsPerSecond(128_000);
    return job;
  }

  // Segment 0's audio may start before zero with the AAC priming frame; the last segment's audio
  // runs to the end of the media.
  private static void assertAudioStartsInsideItsExactInterval(
      MediaUploads uploads, int number, List<Long> starts) {
    var movie = Mp4Samples.read(uploads.contentOf(List.of("init.mp4", segmentName(number))));
    var audioTimescale = movie.audioTrack().timescale();
    var start = new BigFraction(starts.get(number), MPEG_TS_TICKS_PER_SECOND);
    var end =
        Optional.of(number + 1)
            .filter(next -> next < starts.size())
            .map(next -> new BigFraction(starts.get(next), MPEG_TS_TICKS_PER_SECOND));
    assertThat(movie.samplesOf(movie.audioTrack()))
        .extracting(sample -> new BigFraction(sample.presentationTime(), audioTimescale))
        .as("segment %d's audio starts inside its exact interval", number)
        .isNotEmpty()
        .allMatch(time -> number == 0 || time.compareTo(start) >= 0)
        .allMatch(time -> end.isEmpty() || time.compareTo(end.orElseThrow()) < 0);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"mkv", "avi"})
  @DisplayName(
      "Should open every copied segment at its listed keyframe when a copy carries exact starts in"
          + " the source's own time base")
  void shouldOpenEveryCopiedSegmentAtItsListedKeyframeWhenACopyCarriesExactStartsInItsTimeBase(
      String container) throws Exception {
    var source = exactStartSource(container);
    var timeBase = videoTimeBase(source);
    var starts = exactStarts(source);
    var job = exactStartJob(source, TranscodeMode.TRANSCODE_MODE_REMUX, starts);
    job.getExecutionBuilder()
        .setSegmentStartTimeBase(
            Fraction.newBuilder()
                .setNumerator(timeBase.numerator().intValueExact())
                .setDenominator(timeBase.denominator().intValueExact()));

    var uploads = execute(engine(softwareCapabilities()), source.getParent(), job.build());

    assertThat(uploads.names()).containsExactlyElementsOf(uploadNames(starts.size()));
    for (var number = 0; number < starts.size(); number++) {
      assertThat(firstVideoSampleOf(uploads, number))
          .as("segment %d opens at its listed keyframe", number)
          .isEqualTo(timeBase.times(starts.get(number)));
    }
  }

  // The job copies or encodes the source on 3 s slots with the given exact starts, which the
  // caller lists in the source video stream's time base: 1/90000 unless it sets another.
  private static VariantJob.Builder exactStartJob(
      Path source, TranscodeMode mode, List<Long> starts) {
    var job = seekableSourceJob(source, mode);
    job.getDecisionBuilder().getAudioBuilder().setMode(AudioMode.AUDIO_MODE_NONE);
    job.getExecutionBuilder()
        .setTargetSegmentDurationSeconds(SLOT_SECONDS)
        .setMediaSegmentCount(starts.size())
        .addAllSegmentStartTicks(starts)
        .setSegmentStartTimeBase(
            Fraction.newBuilder().setNumerator(1).setDenominator(MPEG_TS_TICKS_PER_SECOND));
    return job;
  }

  // The first video sample of the segment, which must be a keyframe, at its exact time in seconds.
  private static BigFraction firstVideoSampleOf(MediaUploads uploads, int number) {
    var movie = Mp4Samples.read(uploads.contentOf(List.of("init.mp4", segmentName(number))));
    var first = movie.samplesOf(movie.videoTrack()).getFirst();
    assertThat(first.isSync()).as("segment %d opens at a keyframe", number).isTrue();
    return new BigFraction(first.presentationTime(), movie.videoTrack().timescale());
  }

  // The server's exact starts for the source: 0, then each later keyframe's presentation tick on
  // the zero-based timeline, as ADR 0032 stores it, that presents at least 2 s after the previous
  // start and 2 s before the end of the media.
  private static List<Long> exactStarts(Path source) throws Exception {
    var timeBase = videoTimeBase(source);
    var origin = earliestStreamStart(source);
    var end = timeBase.ticksIn(mediaDuration(source));
    var twoSeconds = timeBase.ticksIn(new BigFraction(2, 1));
    var starts = new ArrayList<Long>();
    starts.add(0L);
    var keyframes =
        ffprobe(VIDEO_KEYFRAME_PTS, source)
            .lines()
            .map(line -> line.split(","))
            .filter(parts -> parts[2].startsWith("K"))
            .mapToLong(parts -> presentationTick(parts) - origin)
            .toArray();
    for (var tick : keyframes) {
      if (tick >= starts.getLast() + twoSeconds && tick <= end - twoSeconds) {
        starts.add(tick);
      }
    }

    return List.copyOf(starts);
  }

  private static final List<String> VIDEO_KEYFRAME_PTS =
      List.of("-select_streams", "v:0", "-show_entries", "packet=pts,dts,flags");

  // AVI packets carry only a decode time, which equals the presentation time without B-frames.
  private static long presentationTick(String[] packet) {
    if (packet[0].equals("N/A")) {
      return Long.parseLong(packet[1]);
    }

    return Long.parseLong(packet[0]);
  }

  // The earliest start of the source's streams in ticks of its video time base, which
  // -start_at_zero subtracts; every stream of these sources shares that time base.
  private static long earliestStreamStart(Path source) throws Exception {
    return ffprobe(List.of("-show_entries", "stream=start_pts"), source)
        .lines()
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .mapToLong(line -> Long.parseLong(line.replace(",", "")))
        .min()
        .orElseThrow();
  }

  private static BigFraction mediaDuration(Path source) throws Exception {
    var seconds = new BigDecimal(ffprobe(List.of("-show_entries", "format=duration"), source));
    return new BigFraction(
        seconds.unscaledValue().longValueExact(),
        BigInteger.TEN.pow(seconds.scale()).longValueExact());
  }

  private static BigFraction videoTimeBase(Path source) throws Exception {
    var parts =
        ffprobe(List.of("-select_streams", "v:0", "-show_entries", "stream=time_base"), source)
            .lines()
            .findFirst()
            .orElseThrow()
            .split("/");
    return new BigFraction(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
  }

  // An H.264 source at 30000/1001 fps with a keyframe every 101 frames, 3.37003... s, which is not
  // a whole number of microseconds and falls between two frames at 24000/1001 fps, and no B-frames,
  // so that every frame lies on the 30000/1001 grid from zero.
  private static Path exactStartMpegTsSource() throws Exception {
    return exactStartSource("exact-starts.ts", List.of(), List.of("-f", "mpegts"));
  }

  // The same video with AAC audio, whose priming starts the timeline before the first frame, so
  // that its keyframes fall on no slot boundary.
  private static Path exactStartMpegTsSourceWithAudio() throws Exception {
    return exactStartSource(
        "exact-starts-audio.ts",
        List.of("-f", "lavfi", "-i", "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5"),
        List.of("-c:a", "aac", "-f", "mpegts"));
  }

  // The same video in Matroska, whose time base is 1/1000, or AVI, whose time base is the frame
  // rate's inverse, 1001/30000.
  private static Path exactStartSource(String container) throws Exception {
    return exactStartSource("exact-starts." + container, List.of(), List.of());
  }

  private static synchronized Path exactStartSource(
      String name, List<String> audioInput, List<String> output) throws Exception {
    var source = seekableSources.resolve(name);
    if (generatedSeekableSources.contains(source)) {
      return source;
    }

    var arguments =
        Stream.of(
                List.of("-f", "lavfi", "-i", "testsrc=size=64x36:rate=30000/1001"),
                audioInput,
                List.of("-t", "14", "-c:v", "libx264", "-bf", "0"),
                List.of("-x264-params", "keyint=101:min-keyint=101:scenecut=0"),
                output)
            .flatMap(List::stream)
            .toList();
    runFfmpeg(arguments, source);
    generatedSeekableSources.add(source);
    return source;
  }

  private static final int MPEG_TS_TICKS_PER_SECOND = 90_000;

  // An exact non-negative fraction of seconds.
  private record BigFraction(BigInteger numerator, BigInteger denominator)
      implements Comparable<BigFraction> {

    BigFraction(long numerator, long denominator) {
      this(BigInteger.valueOf(numerator), BigInteger.valueOf(denominator));
    }

    BigFraction {
      var divisor = numerator.gcd(denominator);
      numerator = numerator.divide(divisor);
      denominator = denominator.divide(divisor);
    }

    BigFraction times(long ticks) {
      return new BigFraction(numerator.multiply(BigInteger.valueOf(ticks)), denominator);
    }

    BigFraction minus(BigFraction other) {
      return new BigFraction(
          numerator.multiply(other.denominator).subtract(other.numerator.multiply(denominator)),
          denominator.multiply(other.denominator));
    }

    // The first whole multiple of the step at or after this time.
    BigFraction ceilingIn(BigFraction step) {
      var steps = numerator.multiply(step.denominator);
      var size = denominator.multiply(step.numerator);
      var count = steps.add(size).subtract(BigInteger.ONE).divide(size);
      return new BigFraction(step.numerator.multiply(count), step.denominator);
    }

    // This time base's ticks in a time, rounded down.
    long ticksIn(BigFraction time) {
      return time.numerator
          .multiply(denominator)
          .divide(time.denominator.multiply(numerator))
          .longValueExact();
    }

    @Override
    public int compareTo(BigFraction other) {
      return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
    }
  }

  // Segment N's listed interval in ticks of the timescale; segment 0 keeps the AAC priming frame
  // from before zero, and the last runs to the end of the media.
  private static ListedInterval listedInterval(int number, long timescale) {
    var starts = TEN_SECOND_KEYFRAME_SEGMENT_STARTS;
    var start = starts.get(number) * SLOT_SECONDS * timescale;
    var end = Long.MAX_VALUE;
    if (number + 1 < starts.size()) {
      end = starts.get(number + 1) * SLOT_SECONDS * timescale;
    }

    var from = start;
    if (number == 0) {
      from = Long.MIN_VALUE;
    }

    return new ListedInterval(start, timescale, new AudioInterval(from, end));
  }

  private record ListedInterval(long start, long timescale, AudioInterval interval) {}

  // The seekable source's frames and noise for 35 s with a keyframe every 240 frames, 10 s at
  // 24 fps, which libx264 with the default keyint=250 also leaves for a static scene: on 3 s slots,
  // slots 1, 2, 4, 5, 7, 8, 9 and 11 hold no keyframe.
  private static synchronized Path tenSecondKeyframeSource() throws Exception {
    var matroska = seekableSources.resolve("ten-second-keyframes.mkv");
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
              "35",
              "-c:v",
              "libx264",
              "-x264-params",
              "keyint=240:min-keyint=240:scenecut=0",
              "-c:a",
              "aac"),
          matroska);
      generatedSeekableSources.add(matroska);
    }

    return matroska;
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

  // The seekable source's frames and noise as libx265 HEVC, which reorders frames, with the same
  // keyframes. The MP4 and MPEG-TS sources carry the Matroska source's streams unchanged.
  private static synchronized Path seekableHevcSource(String container) throws Exception {
    var matroska = seekableSources.resolve("seekable-hevc.mkv");
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
              "libx265",
              "-x265-params",
              "keyint=48:min-keyint=48:log-level=error",
              "-c:a",
              "aac"),
          matroska);
      generatedSeekableSources.add(matroska);
    }

    var source = seekableSources.resolve("seekable-hevc." + container);
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(List.of("-i", matroska.toString(), "-c", "copy"), source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // libx265 HEVC with B-frames whose every third picture starts about 0.2 s late, and pink noise,
  // in Matroska. Its demuxer gives every video packet the track's default duration, whatever the
  // gap to the next picture, and FFmpeg's frame-rate conversion repeats and drops pictures by
  // those durations.
  private static synchronized Path variableFrameRateHevcSource() throws Exception {
    var source = seekableSources.resolve("variable-frame-rate-hevc.mkv");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc2=size=64x36:rate=24",
              "-f",
              "lavfi",
              "-i",
              "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5",
              "-frames:v",
              "72",
              "-t",
              "8",
              "-vf",
              "setpts=PTS+0.2*floor(N/3)/TB",
              "-c:v",
              "libx265",
              "-x265-params",
              "keyint=12:min-keyint=12:bframes=4:log-level=error",
              "-c:a",
              "aac"),
          source);
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

  private static VariantJob.Builder videoEndsBeforeAudioJob(Path source, TranscodeMode mode) {
    var job = seekableSourceJob(source, mode);
    job.getExecutionBuilder().setMediaSegmentCount(VIDEO_ENDS_BEFORE_AUDIO_SEGMENT_COUNT);
    return job;
  }

  // H.264 at 64x36 and 24 fps with a keyframe every 2 s for 64 s, and pink noise for 84 s: the
  // video ends inside segment 10, and the audio runs on through segment 13.
  private static synchronized Path videoEndsBeforeAudioSource() throws Exception {
    var source = seekableSources.resolve("video-ends-before-audio.mp4");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24:duration=64",
              "-f",
              "lavfi",
              "-i",
              "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5:duration=84",
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

  // The source whose video ends before its audio, with AC-3 audio that turns from stereo to 5.1 at
  // 70 s, inside segment 11: the audio decoder's frames change layout after the video's end.
  private static synchronized Path videoEndsBeforeAudioThatChangesLayoutSource() throws Exception {
    var source = seekableSources.resolve("video-ends-before-audio-that-changes-layout.mp4");
    if (!generatedSeekableSources.contains(source)) {
      var stereo = seekableSources.resolve("stereo-before-the-layout-change.ac3");
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5:duration=70,"
                  + "pan=stereo|c0=c0|c1=c0",
              "-c:a",
              "ac3"),
          stereo);
      var surround = seekableSources.resolve("surround-after-the-layout-change.ac3");
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5:duration=14,"
                  + "pan=5.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0",
              "-c:a",
              "ac3"),
          surround);
      var audio = seekableSources.resolve("audio-that-changes-layout.ac3");
      Files.write(audio, Files.readAllBytes(stereo));
      Files.write(audio, Files.readAllBytes(surround), StandardOpenOption.APPEND);
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24:duration=64",
              "-i",
              audio.toString(),
              "-c:v",
              "libx264",
              "-g",
              "48",
              "-keyint_min",
              "48",
              "-c:a",
              "copy"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // H.264 at 64x36 and 24 fps with a keyframe every 2 s, and 5.1 AC-3 whose six channels play 300
  // to 800 Hz at half of full scale, both for 12 s: a downmix that sums them can exceed full scale.
  private static synchronized Path loudSurroundAc3Source() throws Exception {
    return toneSource("loud-surround.mp4", "5.1", List.of("-c:a", "ac3", "-b:a", "448k"));
  }

  // H.264 at 64x36 and 24 fps with a keyframe every 2 s, and 16-bit 7.1(wide) FLAC whose eight
  // channels play 300 to 1,000 Hz at half of full scale, both for 12 s.
  private static synchronized Path wideSevenPointOneFlacSource() throws Exception {
    return toneSource(
        "wide-seven-point-one.mkv", "7.1(wide)", List.of("-c:a", "flac", "-sample_fmt", "s16"));
  }

  // 12 s of H.264 and of a tone on each channel of the layout, from 300 Hz up in steps of 100 Hz.
  private static Path toneSource(String name, String layout, List<String> audio) throws Exception {
    var channels = layout.equals("5.1") ? 6 : 8;
    var source = seekableSources.resolve(name);
    if (!generatedSeekableSources.contains(source)) {
      var tones =
          IntStream.range(0, channels)
              .mapToObj(channel -> "0.5*sin(2*PI*" + (300 + 100 * channel) + "*t)")
              .collect(Collectors.joining("|"));
      var arguments =
          new ArrayList<>(
              List.of(
                  "-f",
                  "lavfi",
                  "-i",
                  "testsrc=size=64x36:rate=24:duration=12",
                  "-f",
                  "lavfi",
                  "-i",
                  "aevalsrc=" + tones + ":s=48000:d=12:c=" + layout,
                  "-c:v",
                  "libx264",
                  "-g",
                  "48",
                  "-keyint_min",
                  "48"));
      arguments.addAll(audio);
      runFfmpeg(arguments, source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // H.264 at 64x36 and 24 fps with a keyframe every 2 s for 12 s, and AC-3 that plays 440 Hz in
  // mono for 6 s and then 440 Hz on the left and 880 Hz on the right for 6 s.
  private static synchronized Path monoThenStereoSource() throws Exception {
    var source = seekableSources.resolve("mono-then-stereo.mp4");
    if (!generatedSeekableSources.contains(source)) {
      var mono = seekableSources.resolve("six-seconds-of-mono.ac3");
      runFfmpeg(List.of("-f", "lavfi", "-i", "sine=f=440:r=48000:d=6", "-c:a", "ac3"), mono);
      var stereo = seekableSources.resolve("six-seconds-of-stereo.ac3");
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "sine=f=440:r=48000:d=6",
              "-f",
              "lavfi",
              "-i",
              "sine=f=880:r=48000:d=6",
              "-filter_complex",
              "[0][1]join=inputs=2:channel_layout=stereo",
              "-c:a",
              "ac3"),
          stereo);
      var audio = seekableSources.resolve("mono-then-stereo.ac3");
      Files.write(audio, Files.readAllBytes(mono));
      Files.write(audio, Files.readAllBytes(stereo), StandardOpenOption.APPEND);
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24:duration=12",
              "-i",
              audio.toString(),
              "-c:v",
              "libx264",
              "-g",
              "48",
              "-keyint_min",
              "48",
              "-c:a",
              "copy"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // H.264 at 64x36 and 24 fps with a keyframe every 2 s, and 16-bit stereo FLAC at 96 kHz of a
  // 1 kHz tone at 99% of full scale, both for 12 s.
  private static synchronized Path loudIntegerStereoFlacSource() throws Exception {
    var source = seekableSources.resolve("loud-integer-stereo-flac.mkv");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24:duration=12",
              "-f",
              "lavfi",
              "-i",
              "aevalsrc=0.99*sin(2*PI*1000*t)|0.99*sin(2*PI*1000*t):s=96000:d=12:c=stereo",
              "-c:v",
              "libx264",
              "-g",
              "48",
              "-keyint_min",
              "48",
              "-c:a",
              "flac",
              "-sample_fmt",
              "s16"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // H.264 at 64x36 and 24 fps with a keyframe every 2 s, and 16-bit 5.1 FLAC at 96 kHz whose six
  // channels play 300 to 800 Hz, both for 12 s.
  private static synchronized Path integerSurroundFlacSource() throws Exception {
    var source = seekableSources.resolve("integer-surround-flac.mkv");
    if (!generatedSeekableSources.contains(source)) {
      var arguments =
          new ArrayList<>(List.of("-f", "lavfi", "-i", "testsrc=size=64x36:rate=24:duration=12"));
      for (var frequency = 300; frequency <= 800; frequency += 100) {
        arguments.addAll(List.of("-f", "lavfi", "-i", "sine=f=" + frequency + ":r=96000:d=12"));
      }

      arguments.addAll(
          List.of(
              "-filter_complex",
              "[1][2][3][4][5][6]join=inputs=6:channel_layout=5.1[a]",
              "-map",
              "0:v",
              "-map",
              "[a]",
              "-c:v",
              "libx264",
              "-g",
              "48",
              "-keyint_min",
              "48",
              "-c:a",
              "flac",
              "-sample_fmt",
              "s16"));
      runFfmpeg(arguments, source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // The source whose video ends before its audio, with quad(side) AC-3 audio whose four channels
  // play 300, 500, 700 and 900 Hz.
  private static synchronized Path videoEndsBeforeQuadSideAudioSource() throws Exception {
    var source = seekableSources.resolve("video-ends-before-quad-side-audio.mp4");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24:duration=64",
              "-f",
              "lavfi",
              "-i",
              "sine=f=300:r=48000:d=84",
              "-f",
              "lavfi",
              "-i",
              "sine=f=500:r=48000:d=84",
              "-f",
              "lavfi",
              "-i",
              "sine=f=700:r=48000:d=84",
              "-f",
              "lavfi",
              "-i",
              "sine=f=900:r=48000:d=84",
              "-filter_complex",
              "[1][2][3][4]join=inputs=4:channel_layout=quad(side)[a]",
              "-map",
              "0:v",
              "-map",
              "[a]",
              "-c:v",
              "libx264",
              "-g",
              "48",
              "-keyint_min",
              "48",
              "-c:a",
              "ac3",
              "-b:a",
              "384k"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // The source whose video ends before its audio, with AC-3 audio that plays 440 Hz in mono until
  // 70 s and then 440 Hz on the left and 880 Hz on the right.
  private static synchronized Path videoEndsBeforeAudioThatTurnsFromMonoToStereoSource()
      throws Exception {
    var source = seekableSources.resolve("video-ends-before-audio-that-turns-stereo.mp4");
    if (!generatedSeekableSources.contains(source)) {
      var mono = seekableSources.resolve("mono-before-the-turn.ac3");
      runFfmpeg(List.of("-f", "lavfi", "-i", "sine=f=440:r=48000:d=70", "-c:a", "ac3"), mono);
      var stereo = seekableSources.resolve("stereo-after-the-turn.ac3");
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "sine=f=440:r=48000:d=14",
              "-f",
              "lavfi",
              "-i",
              "sine=f=880:r=48000:d=14",
              "-filter_complex",
              "[0][1]join=inputs=2:channel_layout=stereo",
              "-c:a",
              "ac3"),
          stereo);
      var audio = seekableSources.resolve("audio-that-turns-stereo.ac3");
      Files.write(audio, Files.readAllBytes(mono));
      Files.write(audio, Files.readAllBytes(stereo), StandardOpenOption.APPEND);
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24:duration=64",
              "-i",
              audio.toString(),
              "-c:v",
              "libx264",
              "-g",
              "48",
              "-keyint_min",
              "48",
              "-c:a",
              "copy"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // The source whose video ends before its audio, with a title that names a time base no stream
  // has, a comment that reads as a packet of stream 0 ending far past the source's end, and a tag
  // whose name prints FFmpeg's stream mapping header on a line of its own before a packet's record.
  // FFmpeg writes all three to its error output among its logs, where the framecrc goes too.
  private static synchronized Path videoEndsBeforeAudioSourceWithFramecrcShapedMetadata()
      throws Exception {
    var source = seekableSources.resolve("video-ends-before-audio-framecrc-metadata-tags.mp4");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-i",
              videoEndsBeforeAudioSource().toString(),
              "-c",
              "copy",
              "-movflags",
              "use_metadata_tags",
              "-metadata",
              "title=Sample #tb 0: 1/1000000000",
              "-metadata",
              "comment=0, 999999999, 999999999, 1, 1000, 0x0a1b2c3d",
              "-metadata",
              "x\nStream mapping:\nrecord=0, 888888888, 888888888, 1, 1000, 0x0a1b2c3d"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // Pads the encoded audio up to the end of the job's last advertised segment of 6 s.
  private static void paddedTo(VariantJob.Builder job, int segmentCount) {
    job.getExecutionBuilder().setMediaSegmentCount(segmentCount);
    job.getExecutionBuilder().getAdvertisedEndBuilder().setSeconds(segmentCount * 6L);
    job.getDecisionBuilder().getAudioBuilder().setPadWithSilenceToAdvertisedEnd(true);
  }

  // A job that encodes the audio of the source whose audio ends 80 s before its video into this
  // codec at two channels and pads it, holding the last frame too when it encodes the video.
  private static VariantJob.Builder audioEndsLongBeforeVideoPaddedJob(
      Path source, TranscodeMode mode, String codec) {
    var job = seekableSourceJob(source, mode);
    paddedTo(job, AUDIO_ENDS_LONG_BEFORE_VIDEO_SEGMENT_COUNT);
    if (mode == TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE) {
      job.getDecisionBuilder().getVideoBuilder().setHoldLastFrameToAdvertisedEnd(true);
    }

    job.getDecisionBuilder()
        .getAudioBuilder()
        .setCodec(codec)
        .setChannels(2)
        .setBitrateBitsPerSecond(192_000);
    return job;
  }

  // H.264 at 64x36 and 24 fps with a keyframe every 2 s for 64 s, and 1,764 s of pink noise as
  // 5.1 AC-3 at 384 kb/s: the video ends inside segment 10, and the audio runs on through
  // segment 293.
  private static synchronized Path longAudioAfterVideoEndSource() throws Exception {
    var source = seekableSources.resolve("long-audio-after-video-end.mp4");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24:duration=64",
              "-f",
              "lavfi",
              "-i",
              "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5:duration=1764,"
                  + "pan=5.1|c0=c0|c1=c0|c2=c0|c3=c0|c4=c0|c5=c0",
              "-c:v",
              "libx264",
              "-g",
              "48",
              "-keyint_min",
              "48",
              "-c:a",
              "ac3",
              "-b:a",
              "384k"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // H.264 at 64x36 and 30 fps with a keyframe every 2 s, and pink noise, both for 10 s.
  private static synchronized Path thirtyFramesPerSecondSource() throws Exception {
    var source = seekableSources.resolve("thirty-frames-per-second.mp4");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=30:duration=10",
              "-f",
              "lavfi",
              "-i",
              "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5:duration=10",
              "-c:v",
              "libx264",
              "-g",
              "60",
              "-c:a",
              "aac"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // A stream copy session that encodes the FLAC source's mono audio to FLAC, from segment 0.
  private static VariantJob.Builder flacAudioEndsBeforeVideoJob(Path source) {
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    job.getExecutionBuilder().setMediaSegmentCount(FLAC_SOURCE_SEGMENT_COUNT);
    job.getDecisionBuilder().getAudioBuilder().setCodec("flac").setChannels(1);
    return job;
  }

  // H.264 at 64x36 and 24 fps with a keyframe every 2 s for 18 s, and a 440 Hz sine that ends at
  // 10 s as 16-bit mono FLAC, which decodes to the same samples wherever FFmpeg reads it.
  private static synchronized Path flacAudioEndsBeforeVideoSource() throws Exception {
    var source = seekableSources.resolve("flac-audio-ends-before-video.mkv");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24:duration=18",
              "-f",
              "lavfi",
              "-i",
              "sine=frequency=440:sample_rate=" + FLAC_SOURCE_SAMPLE_RATE + ":duration=10",
              "-c:v",
              "libx264",
              "-g",
              "48",
              "-c:a",
              "flac",
              "-sample_fmt",
              "s16"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // The video of the FLAC source above, an 880 Hz sine that runs for the video's 18 s, then the
  // 440 Hz sine that ends at 10 s, both as 16-bit mono FLAC.
  private static synchronized Path flacSecondAudioEndsBeforeVideoSource() throws Exception {
    var source = seekableSources.resolve("flac-second-audio-ends-before-video.mkv");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24:duration=18",
              "-f",
              "lavfi",
              "-i",
              "sine=frequency=880:sample_rate=" + FLAC_SOURCE_SAMPLE_RATE + ":duration=18",
              "-f",
              "lavfi",
              "-i",
              "sine=frequency=440:sample_rate=" + FLAC_SOURCE_SAMPLE_RATE + ":duration=10",
              "-map",
              "0:v",
              "-map",
              "1:a",
              "-map",
              "2:a",
              "-c:v",
              "libx264",
              "-g",
              "48",
              "-c:a",
              "flac",
              "-sample_fmt",
              "s16"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // A job that downmixes the 5.1 source whose audio ends at 10 s to named stereo AAC through the
  // limiter, holding the last frame too when it encodes the video.
  private static VariantJob.Builder surroundAudioEndsBeforeVideoNamedStereoJob(
      Path source, TranscodeMode mode) {
    var job = seekableSourceJob(source, mode);
    if (mode == TranscodeMode.TRANSCODE_MODE_FULL_TRANSCODE) {
      job.getDecisionBuilder().getVideoBuilder().setHoldLastFrameToAdvertisedEnd(true);
    }

    job.getDecisionBuilder()
        .getAudioBuilder()
        .setChannelLayout(AudioChannelLayout.AUDIO_CHANNEL_LAYOUT_STEREO)
        .setBitrateBitsPerSecond(192_000);
    return job;
  }

  // H.264 at 64x36 and 60 fps, 360 frames whose last starts at 5.983 s, and 15 s of pink noise.
  private static synchronized Path sixtyFramesPerSecondEndingBeforeABoundarySource()
      throws Exception {
    var source = seekableSources.resolve("sixty-fps-ending-before-a-boundary.mp4");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=60:duration=5.99",
              "-f",
              "lavfi",
              "-i",
              "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5:duration=15",
              "-c:v",
              "libx264",
              "-g",
              "120",
              "-c:a",
              "aac"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  // H.264 at 64x36 and 24 fps with a keyframe every 2 s, and pink noise, both for 90 s, with the
  // index at the front and only the first half of the file: FFmpeg reads the streams to where the
  // data stops, partway through, and exits cleanly.
  private static synchronized Path truncatedSource() throws Exception {
    var source = seekableSources.resolve("truncated.mp4");
    if (!generatedSeekableSources.contains(source)) {
      var whole = seekableSources.resolve("whole-before-truncation.mp4");
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24:duration=90",
              "-f",
              "lavfi",
              "-i",
              "anoisesrc=color=pink:sample_rate=48000:amplitude=0.5:duration=90",
              "-c:v",
              "libx264",
              "-g",
              "48",
              "-keyint_min",
              "48",
              "-c:a",
              "aac",
              "-movflags",
              "+faststart"),
          whole);
      var bytes = Files.readAllBytes(whole);
      Files.write(source, Arrays.copyOf(bytes, bytes.length / 2));
      generatedSeekableSources.add(source);
    }

    return source;
  }

  private static VariantJob.Builder surroundAudioEndsBeforeVideoJob(Path source) {
    var job = seekableSourceJob(source, TranscodeMode.TRANSCODE_MODE_AUDIO_TRANSCODE);
    job.getExecutionBuilder().setMediaSegmentCount(FLAC_SOURCE_SEGMENT_COUNT);
    job.getDecisionBuilder()
        .getAudioBuilder()
        .setCodec("ac3")
        .setChannels(2)
        .setBitrateBitsPerSecond(192_000);
    return job;
  }

  // H.264 at 64x36 and 24 fps with a keyframe every 2 s for 18 s, and pink noise that ends at 10 s
  // as 96 kHz 16-bit 5.1 FLAC.
  private static synchronized Path surroundAudioEndsBeforeVideoSource() throws Exception {
    var source = seekableSources.resolve("surround-audio-ends-before-video.mkv");
    if (!generatedSeekableSources.contains(source)) {
      runFfmpeg(
          List.of(
              "-f",
              "lavfi",
              "-i",
              "testsrc=size=64x36:rate=24:duration=18",
              "-f",
              "lavfi",
              "-i",
              "anoisesrc=color=pink:sample_rate=96000:amplitude=0.5:duration=10,"
                  + "aformat=sample_fmts=s16:channel_layouts=5.1(side)",
              "-c:v",
              "libx264",
              "-g",
              "48",
              "-c:a",
              "flac"),
          source);
      generatedSeekableSources.add(source);
    }

    return source;
  }

  private List<Packet> audioPacketsOf(MediaUploads uploads) throws Exception {
    return FfprobePackets.of(uploads.contentOf(uploads.names()), root).stream()
        .filter(Packet::isAudio)
        .toList();
  }

  // The first audio stream of the media, decoded to 16-bit little-endian samples.
  private byte[] decodedAudio(byte[] media) throws Exception {
    var file = Files.createTempFile(root, "decoded", ".media");
    Files.write(file, media);
    return decodedAudio(file, "0:a:0");
  }

  // The stream of the media that the map names, decoded to 16-bit little-endian samples.
  private byte[] decodedAudio(Path media, String map) throws Exception {
    var samples = Files.createTempFile(root, "decoded", ".s16le");
    runFfmpeg(List.of("-i", media.toString(), "-map", map, "-f", "s16le"), samples);
    return Files.readAllBytes(samples);
  }

  private static String segmentName(int number) {
    return "segment" + number + ".m4s";
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

    private boolean showsBefore(VideoPacket other) {
      return presentationTime.compareTo(other.presentationTime) < 0;
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
    var video =
        VideoDecision.newBuilder()
            .setStreamIndex(0)
            .setMode(VideoMode.VIDEO_MODE_ENCODE)
            .setLevel(level)
            .setSourceDynamicRange(VideoDynamicRange.getDefaultInstance())
            .setOutputDynamicRange(sdrDynamicRange());
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
    return executeToCompletion(engine, sourceRoot, job, uploadStarted).uploads();
  }

  private CompletedExecution executeToCompletion(
      FfmpegTranscodeEngine engine, Path sourceRoot, VariantJob job) throws Exception {
    return executeToCompletion(engine, sourceRoot, job, _ -> {});
  }

  private CompletedExecution executeToCompletion(
      FfmpegTranscodeEngine engine, Path sourceRoot, VariantJob job, Consumer<String> uploadStarted)
      throws Exception {
    try (var controlPlane = new MediaControlPlane(uploadStarted);
        var worker = workerBuilder(sourceRoot).engine(engine).ffprobe(probeExecutor()).build()) {
      worker.start("127.0.0.1", controlPlane.port());
      var registeredWorker = controlPlane.registration.get(5, TimeUnit.SECONDS).getWorker();
      controlPlane.responses.onNext(
          EstablishWorkerSessionResponse.newBuilder()
              .setStartVariant(
                  StartVariantCommand.newBuilder().setTarget(registeredWorker).setJob(job))
              .build());

      var report = controlPlane.completed.get(JOB_LIMIT.toSeconds(), TimeUnit.SECONDS);
      assertThat(report.getJobAttemptId()).isEqualTo(job.getJobAttemptId());
      return new CompletedExecution(
          report,
          new MediaUploads(List.copyOf(controlPlane.names), Map.copyOf(controlPlane.segments)),
          List.copyOf(controlPlane.progress),
          controlPlane.completedAt.get());
    }
  }

  private record CompletedExecution(
      JobAttemptCompleted report,
      MediaUploads uploads,
      List<TimedProgress> progress,
      long completedAtNanos) {

    // The longest time between two progress reports, or between the last one and the completion.
    Duration longestSilence() {
      var times = new ArrayList<Long>();
      progress.forEach(report -> times.add(report.receivedAtNanos()));
      times.add(completedAtNanos);
      return IntStream.range(1, times.size())
          .mapToObj(index -> Duration.ofNanos(times.get(index) - times.get(index - 1)))
          .max(Comparator.naturalOrder())
          .orElseThrow();
    }
  }

  // A progress report and when the control plane received it, on System.nanoTime.
  private record TimedProgress(long receivedAtNanos, JobAttemptProgress report) {

    Duration outputEnd() {
      var end = report.getOutputEnd();
      return Duration.ofSeconds(end.getSeconds(), end.getNanos());
    }
  }

  private static SourceStreamEnd streamEnd(int streamIndex, Duration endTime) {
    var streamEnd = SourceStreamEnd.newBuilder().setStreamIndex(streamIndex);
    streamEnd.getEndTimeBuilder().setSeconds(endTime.getSeconds()).setNanos(endTime.getNano());
    return streamEnd.build();
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

  // SVT-AV1's output bytes differ from run to run under its production threading. At a level of
  // parallelism of one (lp=1), two encodes of the same input write the same bytes, which a
  // comparison with a reference encode needs.
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
    return new TranscodeCapabilityService("ffmpeg", ProcessBuilder::start);
  }

  // Lists no hardware encoder, so that each codec family runs its software encoder.
  private static TranscodeCapabilityService softwareCapabilities() {
    return new TranscodeCapabilityService(
        "ffmpeg",
        command -> {
          if (command.command().contains("-encoders")) {
            return new ProcessBuilder("true").start();
          }

          return command.start();
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

  // The build writes a sibling file, which takes the fixture's name only once the build succeeds,
  // so that a later case builds the fixture again instead of reading a failed build's output.
  private static synchronized Path buildFixtureOnce(Path fixture, FixtureBuild build)
      throws Exception {
    if (Files.exists(fixture)) {
      return fixture;
    }

    try (var partial =
        new PartialFile(fixture.resolveSibling("partial-" + fixture.getFileName()))) {
      build.writeTo(partial.path());
      Files.move(partial.path(), fixture, StandardCopyOption.ATOMIC_MOVE);
    }

    return fixture;
  }

  @FunctionalInterface
  private interface FixtureBuild {
    void writeTo(Path output) throws Exception;
  }

  // Closing deletes what a failed build wrote. A failure to delete the file joins the build's own
  // failure as a suppressed exception instead of replacing that failure.
  private record PartialFile(Path path) implements AutoCloseable {
    @Override
    public void close() throws IOException {
      Files.deleteIfExists(path);
    }
  }

  private static void runFfmpeg(List<String> arguments, Path output) throws Exception {
    runToCompletion(
        Stream.of(
                List.of("ffmpeg", "-nostdin", "-v", "error", "-y"),
                arguments,
                List.of(output.toString()))
            .flatMap(List::stream)
            .toList(),
        output);
  }

  // Returns the command's combined output after it exits successfully. A command still running
  // after two minutes fails with a report of how far it got.
  private static String runToCompletion(List<String> command, Path... outputs) throws Exception {
    var log = Files.createTempFile(longSources, command.getFirst(), ".log");
    var started = System.nanoTime();
    try (var owned =
        new OwnedProcess(
            new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start())) {
      var process = owned.process();
      if (!process.waitFor(2, TimeUnit.MINUTES)) {
        throw new AssertionError(
            UnfinishedCommand.builder()
                .command(command)
                .process(process)
                .ranFor(
                    Duration.ofNanos(System.nanoTime() - started).truncatedTo(ChronoUnit.MILLIS))
                .log(log)
                .outputs(List.of(outputs))
                .build()
                .report());
      }

      var output = Files.readString(log);
      assertThat(process.exitValue()).as(output).isZero();
      return output;
    }
  }

  // Closing kills the process and waits for it to end, because buildFixtureOnce deletes a failed
  // build's file next. join() does not return on an interrupt, which stays set for the caller. A
  // process that outlasts the guard fails the call, as a suppressed exception when the call has
  // already failed.
  private record OwnedProcess(Process process) implements AutoCloseable {
    private static final Duration EXIT_AFTER_KILL_GUARD = Duration.ofMinutes(1);

    @Override
    public void close() {
      try {
        process
            .destroyForcibly()
            .onExit()
            .orTimeout(EXIT_AFTER_KILL_GUARD.toMillis(), TimeUnit.MILLISECONDS)
            .join();
      } catch (CompletionException timeout) {
        throw new AssertionError(
            "PID %d was still running %s after it was killed"
                .formatted(process.pid(), EXIT_AFTER_KILL_GUARD),
            timeout);
      }
    }
  }

  // A command that is still running, with what it has done so far: its run time and CPU time, the
  // size of each output it writes, and the end of its log. A file that cannot be read says so in
  // the report, so that the timeout still fails with the rest of the report.
  @Builder
  private record UnfinishedCommand(
      List<String> command, Process process, Duration ranFor, Path log, List<Path> outputs) {

    private static final int LOG_TAIL_LINES = 40;

    String report() {
      var report = new ArrayList<String>();
      report.add("%s did not finish: %s".formatted(command.getFirst(), String.join(" ", command)));
      report.add("It ran for %s and used %s of CPU time.".formatted(ranFor, cpuTime()));
      for (var output : outputs) {
        report.add(sizeOf(output));
      }

      report.add(sizeOf(log));
      report.add("The end of its log:");
      report.addAll(lastLines(log));
      return String.join(System.lineSeparator(), report);
    }

    private String cpuTime() {
      return process.info().totalCpuDuration().map(Duration::toString).orElse("an unknown amount");
    }

    private static String sizeOf(Path file) {
      if (Files.notExists(file)) {
        return file + " does not exist";
      }

      try {
        return "%s holds %d bytes".formatted(file, Files.size(file));
      } catch (IOException exception) {
        return "%s could not be measured: %s".formatted(file, exception);
      }
    }

    private static List<String> lastLines(Path log) {
      try {
        var lines = new String(Files.readAllBytes(log), StandardCharsets.UTF_8).lines().toList();
        return lines.subList(Math.max(0, lines.size() - LOG_TAIL_LINES), lines.size());
      } catch (IOException exception) {
        return List.of("The log could not be read: " + exception);
      }
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

  private static void serveUntilThePacketScanOpens(
      Path fifo, byte[] media, CompletableFuture<ProcessHandle> scanReady) {
    while (!Thread.currentThread().isInterrupted()) {
      // Opening a FIFO for writing waits for a reader.
      try (var output = Files.newOutputStream(fifo)) {
        var scan = packetScanProcess(fifo);
        if (scan.isPresent()) {
          scanReady.complete(scan.get());
          new CountDownLatch(1).await();
        }

        output.write(media);
      } catch (InterruptedException _) {
        Thread.currentThread().interrupt();
      } catch (IOException _) {
        // A reader that stops early closes the pipe; the next read opens it again.
      }
    }
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
    return requestBuilder().setProbeVersion(FfprobeExecutor.PROBE_VERSION).build();
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
    private final AtomicLong completedAt = new AtomicLong();
    private final CompletableFuture<JobAttemptFailed> failed = new CompletableFuture<>();
    private final List<TimedProgress> progress = new CopyOnWriteArrayList<>();
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

          if (message.hasJobAttemptProgress()) {
            progress.add(new TimedProgress(System.nanoTime(), message.getJobAttemptProgress()));
          }

          if (message.hasJobAttemptCompleted()) {
            completedAt.set(System.nanoTime());
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
