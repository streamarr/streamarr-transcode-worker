package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import build.buf.gen.streamarr.transcode.v1.KeyframePositionSource;
import build.buf.gen.streamarr.transcode.v1.KeyframePositions;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeContainerInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframePositionsPart;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.google.protobuf.Duration;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.Builder;
import lombok.Singular;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Keyframe Tests")
class FfprobeExecutorKeyframeTest {

  private static final String MP4_STREAMS =
      """
      {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","start_time":"0.000000",
                 "duration":"4.000000"},
       "streams":[
        {"index":0,"codec_type":"video","codec_name":"h264","id":"0x1","r_frame_rate":"24/1",
         "disposition":{"attached_pic":0}},
        {"index":1,"codec_type":"audio","codec_name":"aac","id":"0x2",
         "disposition":{"attached_pic":0}}]}
      """;
  private static final String MATROSKA_STREAMS =
      """
      {"format":{"format_name":"matroska,webm","start_time":"-0.023000","duration":"4.023000"},
       "streams":[
        {"index":0,"codec_type":"video","codec_name":"h264","r_frame_rate":"24/1",
         "disposition":{"attached_pic":0}},
        {"index":1,"codec_type":"audio","codec_name":"aac","disposition":{"attached_pic":0}}]}
      """;
  private static final String MP4_LAST_KEYFRAME =
      """
      {"packets":[{"pts":35840,"dts":34816,"flags":"K__"}]}
      """;
  private static final String MATROSKA_LAST_KEYFRAME =
      """
      {"packets":[{"pts":2917,"flags":"K__"}]}
      """;
  private static final String NO_PACKETS =
      """
      {"packets":[]}
      """;

  @Test
  @DisplayName(
      "Should report keyframe positions from the sync-sample table when the source is an MP4 with B-frames and an edit list")
  void shouldReportKeyframePositionsFromSyncSampleTableWhenSourceIsMp4WithBFramesAndEditList() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MP4_STREAMS)
            .packet("2.916667%+#1", MP4_LAST_KEYFRAME)
            .build();
    var request = keyframeRequest();

    var report = ffprobe.executor().probe(fixture("mp4-bframes-editlist.mp4"), request);

    assertThat(report.result().getMedia().getKeyframes())
        .isEqualTo(
            ProbeKeyframes.newBuilder()
                .setPositions(
                    KeyframePositions.newBuilder()
                        .setStreamIndex(0)
                        .setTimeBaseNumerator(1)
                        .setTimeBaseDenominator(12_288)
                        .setSource(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_CONTAINER_INDEX)
                        .setKeyframeCount(4))
                .build());
    assertThat(report.keyframePositionsParts())
        .containsExactly(
            ProbeKeyframePositionsPart.newBuilder()
                .setProbeAttemptId(request.getProbeAttemptId())
                .addAllPresentationTimeDeltas(List.of(0L, 8_704L, 16_896L, 10_240L))
                .addAllDecodeTimeDeltas(List.of(-1_024L, 8_704L, 16_896L, 10_240L))
                .build());
  }

  @Test
  @DisplayName(
      "Should report keyframe positions from the Cues when a libavformat Matroska source lists every keyframe")
  void shouldReportKeyframePositionsFromCuesWhenLibavformatMatroskaSourceListsEveryKeyframe() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MATROSKA_STREAMS)
            .packet("2.917000%+#1", MATROSKA_LAST_KEYFRAME)
            .build();
    var request = keyframeRequest();

    var report = ffprobe.executor().probe(fixture("mkv-libavformat.mkv"), request);

    assertThat(report.result().getMedia().getKeyframes())
        .isEqualTo(
            ProbeKeyframes.newBuilder()
                .setPositions(
                    KeyframePositions.newBuilder()
                        .setStreamIndex(0)
                        .setTimeBaseNumerator(1)
                        .setTimeBaseDenominator(1_000)
                        .setSource(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_CONTAINER_INDEX)
                        .setKeyframeCount(4))
                .build());
    // The container starts 23 ms before the video, at the first audio packet.
    assertThat(report.keyframePositionsParts())
        .containsExactly(
            ProbeKeyframePositionsPart.newBuilder()
                .setProbeAttemptId(request.getProbeAttemptId())
                .addAllPresentationTimeDeltas(List.of(23L, 708L, 1_375L, 834L))
                .addAllDecodeTimeDeltas(List.of(23L, 708L, 1_375L, 834L))
                .build());
  }

  @ParameterizedTest
  @CsvSource({
    "mpegts.ts, mpegts",
    "avi.avi, avi",
    "mkv-pipe.mkv, 'matroska,webm'",
    "mp4-fragmented.mp4, 'mov,mp4,m4a,3gp,3g2,mj2'"
  })
  @DisplayName("Should require a packet scan when the source has no container index")
  void shouldRequirePacketScanWhenSourceHasNoContainerIndex(String fixture, String formatName) {
    var ffprobe = ScriptedFfprobe.builder().streams(streamsOf(formatName)).build();

    var report = ffprobe.executor().probe(fixture(fixture), keyframeRequest());

    assertThat(report.result().getMedia().getKeyframes())
        .isEqualTo(
            ProbeKeyframes.newBuilder()
                .setPacketScanRequired(
                    KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_NO_CONTAINER_INDEX)
                .build());
    assertThat(report.keyframePositionsParts()).isEmpty();
  }

  @ParameterizedTest
  @CsvSource({"mp4-truncated.mp4, 2.916667%+#1", "mkv-truncated-cues-front.mkv, 2.917000%+#1"})
  @DisplayName(
      "Should require a packet scan when a truncated source lists a last keyframe that cannot be read")
  void shouldRequirePacketScanWhenTruncatedSourceListsLastKeyframeThatCannotBeRead(
      String fixture, String lastKeyframeInterval) {
    var streams = fixture.endsWith(".mkv") ? MATROSKA_STREAMS : MP4_STREAMS;
    var ffprobe =
        ScriptedFfprobe.builder().streams(streams).packet(lastKeyframeInterval, NO_PACKETS).build();

    var report = ffprobe.executor().probe(fixture(fixture), keyframeRequest());

    assertThat(report.result().getMedia().getKeyframes())
        .isEqualTo(
            ProbeKeyframes.newBuilder()
                .setPacketScanRequired(
                    KeyframePacketScanReason
                        .KEYFRAME_PACKET_SCAN_REASON_UNREADABLE_LAST_INDEXED_KEYFRAME)
                .build());
    assertThat(report.keyframePositionsParts()).isEmpty();
  }

  @Test
  @DisplayName("Should answer probe version 1 exactly as before when the source has an index")
  void shouldAnswerProbeVersionOneExactlyAsBeforeWhenSourceHasIndex() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MP4_STREAMS)
            .packet("2.916667%+#1", MP4_LAST_KEYFRAME)
            .build();
    var request =
        keyframeRequest().toBuilder().setProbeVersion(FfprobeExecutor.PROBE_VERSION).build();

    var report = ffprobe.executor().probe(fixture("mp4-bframes-editlist.mp4"), request);

    assertThat(report)
        .isEqualTo(
            ProbeAttemptReport.resultOnly(
                ProbeAttemptResult.newBuilder()
                    .setProbeAttemptId(request.getProbeAttemptId())
                    .setProbeVersion(1)
                    .setMedia(
                        ProbeMediaInfo.newBuilder()
                            .setContainer(
                                ProbeContainerInfo.newBuilder()
                                    .setFormat("mov,mp4,m4a,3gp,3g2,mj2")
                                    .setDuration(Duration.newBuilder().setSeconds(4)))
                            .addStreams(
                                ProbeStreamInfo.newBuilder()
                                    .setIndex(0)
                                    .setCodecType("video")
                                    .setCodec("h264")
                                    .setFramerate(24))
                            .addStreams(
                                ProbeStreamInfo.newBuilder()
                                    .setIndex(1)
                                    .setCodecType("audio")
                                    .setCodec("aac")))
                    .build()));
  }

  @Test
  @DisplayName("Should refuse a packet scan when the probe version reports no keyframes")
  void shouldRefusePacketScanWhenProbeVersionReportsNoKeyframes() {
    var ffprobe = ScriptedFfprobe.builder().streams(MP4_STREAMS).build();
    var request =
        keyframeRequest().toBuilder()
            .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
            .setScanKeyframePackets(true)
            .build();

    var report = ffprobe.executor().probe(fixture("mp4-bframes-editlist.mp4"), request);

    assertThat(report.result().getFailure())
        .isEqualTo(ProbeFailure.PROBE_FAILURE_UNSUPPORTED_VERSION);
  }

  @Test
  @DisplayName("Should report no video stream when every video stream is an attached picture")
  void shouldReportNoVideoStreamWhenEveryVideoStreamIsAttachedPicture() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(
                """
                {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2"},
                 "streams":[{"index":0,"codec_type":"video","codec_name":"mjpeg","id":"0x1",
                             "disposition":{"attached_pic":1}}]}
                """)
            .build();

    var report = ffprobe.executor().probe(fixture("mp4-bframes-editlist.mp4"), keyframeRequest());

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_NO_VIDEO_STREAM);
    assertThat(report.result().hasMedia()).isFalse();
  }

  @Test
  @DisplayName("Should require a packet scan when ffprobe reports no track id for an MP4 stream")
  void shouldRequirePacketScanWhenFfprobeReportsNoTrackIdForMp4Stream() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(
                """
                {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","start_time":"N/A"},
                 "streams":[{"index":0,"codec_type":"video","codec_name":"h264"}]}
                """)
            .build();

    var report = ffprobe.executor().probe(fixture("mp4-bframes-editlist.mp4"), keyframeRequest());

    assertThat(report.result().getMedia().getKeyframes().getPacketScanRequired())
        .isEqualTo(KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX);
  }

  @Test
  @DisplayName("Should split the keyframe positions into parts when the source has many keyframes")
  void shouldSplitKeyframePositionsIntoPartsWhenSourceHasManyKeyframes(@TempDir Path directory)
      throws Exception {
    var source =
        Files.write(
            directory.resolve("source.mp4"),
            SyntheticMp4.builder().mediaTimescale(1_000).sampleDurations(5_000, 1).build().bytes());
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(
                """
                {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","start_time":"0.000000"},
                 "streams":[{"index":0,"codec_type":"video","id":"0x1"}]}
                """)
            .packet("4.999000%+#1", "{\"packets\":[{\"pts\":4999,\"dts\":4999,\"flags\":\"K__\"}]}")
            .build();

    var report = ffprobe.executor().probe(source, keyframeRequest());

    assertThat(report.result().getMedia().getKeyframes().getPositions().getKeyframeCount())
        .isEqualTo(5_000);
    assertThat(report.keyframePositionsParts())
        .extracting(ProbeKeyframePositionsPart::getDecodeTimeDeltasCount)
        .containsExactly(4_096, 904);
    assertThat(report.keyframePositionsParts().get(1).getDecodeTimeDeltas(0)).isEqualTo(1);
  }

  @ParameterizedTest
  @CsvSource({"mp4-bframes-editlist.mp4, MP4", "mkv-libavformat.mkv, MATROSKA"})
  @DisplayName("Should report cancellation when the attempt is cancelled before the index read")
  void shouldReportCancellationWhenAttemptIsCancelledBeforeIndexRead(
      String fixture, String container) {
    var streams = "MP4".equals(container) ? MP4_STREAMS : MATROSKA_STREAMS;
    var ffprobe = ScriptedFfprobe.builder().streams(streams).cancelAfterStreams(true).build();

    try {
      var report = ffprobe.executor().probe(fixture(fixture), keyframeRequest());

      assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_CANCELLED);
      assertThat(report.keyframePositionsParts()).isEmpty();
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private static String streamsOf(String formatName) {
    return """
        {"format":{"format_name":"%s","start_time":"0.000000"},
         "streams":[{"index":0,"codec_type":"video","codec_name":"h264","id":"0x1",
                     "disposition":{"attached_pic":0}}]}
        """
        .formatted(formatName);
  }

  private static ProbeRequest keyframeRequest() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(FfprobeExecutor.KEYFRAME_POSITIONS_PROBE_VERSION)
        .build();
  }

  private static Path fixture(String name) {
    try {
      var resource = FfprobeExecutorKeyframeTest.class.getResource("/keyframes/" + name);
      assertThat(resource).as("fixture %s", name).isNotNull();
      return Path.of(resource.toURI());
    } catch (URISyntaxException exception) {
      throw new AssertionError(exception);
    }
  }

  /**
   * Answers the probe command with recorded stream JSON, and a last-keyframe read with the packets
   * recorded for its exact read interval. Any other read interval finds no packet.
   */
  @Builder
  private record ScriptedFfprobe(
      String streams, @Singular Map<String, String> packets, boolean cancelAfterStreams) {

    FfprobeExecutor executor() {
      return new FfprobeExecutor(new ObjectMapper(), this::answer);
    }

    private Process answer(List<String> arguments) {
      var interval = arguments.indexOf("-read_intervals");
      if (interval < 0) {
        return new CompletedProcess(streams, cancelAfterStreams);
      }

      return new CompletedProcess(
          packets.getOrDefault(arguments.get(interval + 1), NO_PACKETS), false);
    }
  }

  /**
   * A process that has already exited. With {@code cancelOnExit}, the attempt is cancelled when the
   * executor finds that the process has exited, after it has read the whole output.
   */
  private static final class CompletedProcess extends Process {
    private final InputStream stdout;
    private final boolean cancelOnExit;

    private CompletedProcess(String stdout, boolean cancelOnExit) {
      this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
      this.cancelOnExit = cancelOnExit;
    }

    @Override
    public OutputStream getOutputStream() {
      return OutputStream.nullOutputStream();
    }

    @Override
    public InputStream getInputStream() {
      return stdout;
    }

    @Override
    public InputStream getErrorStream() {
      return InputStream.nullInputStream();
    }

    @Override
    public int waitFor() {
      return 0;
    }

    @Override
    public int exitValue() {
      return 0;
    }

    @Override
    public boolean isAlive() {
      if (cancelOnExit) {
        Thread.currentThread().interrupt();
      }

      return false;
    }

    @Override
    public void destroy() {
      // This fake has already exited.
    }
  }
}
