package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.KeyframePacketScanReason;
import build.buf.gen.streamarr.transcode.v1.KeyframePositionSource;
import build.buf.gen.streamarr.transcode.v1.KeyframePositions;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailable;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailableReason;
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
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
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
         "time_base":"1/1000","has_b_frames":2,"disposition":{"attached_pic":0}},
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
  private static final String MPEGTS_STREAMS =
      """
      {"format":{"format_name":"mpegts","start_time":"1.460111","duration":"4.000000"},
       "streams":[
        {"index":0,"codec_type":"video","codec_name":"h264","id":"0x100","r_frame_rate":"24/1",
         "time_base":"1/90000","has_b_frames":2,"disposition":{"attached_pic":0}},
        {"index":1,"codec_type":"audio","codec_name":"aac","id":"0x101","time_base":"1/90000",
         "disposition":{"attached_pic":0}}]}
      """;
  private static final String MP4_FORMAT = "mov,mp4,m4a,3gp,3g2,mj2";
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @DisplayName("Should report no video stream when every video stream is an attached picture")
  void shouldReportNoVideoStreamWhenEveryVideoStreamIsAttachedPicture(boolean scanKeyframePackets) {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(
                """
                {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2"},
                 "streams":[{"index":0,"codec_type":"video","codec_name":"mjpeg","id":"0x1",
                             "disposition":{"attached_pic":1}}]}
                """)
            .build();

    var request = keyframeRequest().toBuilder().setScanKeyframePackets(scanKeyframePackets).build();

    var report = ffprobe.executor().probe(fixture("mp4-bframes-editlist.mp4"), request);

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

  @Test
  @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  @DisplayName(
      "Should require a packet scan when the decode durations declare more samples than the track has")
  void shouldRequirePacketScanWhenDecodeDurationsDeclareMoreSamplesThanTrackHas(
      @TempDir Path directory) throws Exception {
    var source =
        Files.write(
            directory.resolve("source.mp4"),
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(-1, 1)
                .sampleSizes(2, 100)
                .syncSamples(List.of(1L))
                .build()
                .bytes());
    var ffprobe = ScriptedFfprobe.builder().streams(streamsOf(MP4_FORMAT)).build();

    var report = ffprobe.executor().probe(source, keyframeRequest());

    assertThat(report.result().getMedia().getKeyframes())
        .isEqualTo(
            ProbeKeyframes.newBuilder()
                .setPacketScanRequired(
                    KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX)
                .build());
  }

  @ParameterizedTest(name = "exit code {1}: {0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "{\"packets\":[{\"pts\":35840,\"dts\":34816,\"flags\":\"K__\"}]} | 1",
        "{\"packets\":[{\"pts\":35328,\"dts\":34816,\"flags\":\"K__\"}]} | 0",
        "{\"packets\":[{\"pts\":35840,\"dts\":34816,\"flags\":\"___\"}]} | 0"
      })
  @DisplayName(
      "Should require a packet scan when the read at the last indexed keyframe finds no such keyframe")
  void shouldRequirePacketScanWhenReadAtLastIndexedKeyframeFindsNoSuchKeyframe(
      String packets, int exitCode) {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MP4_STREAMS)
            .packet("2.916667%+#1", packets)
            .packetReadExitCode(exitCode)
            .build();

    var report = ffprobe.executor().probe(fixture("mp4-bframes-editlist.mp4"), keyframeRequest());

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
  @DisplayName("Should keep the index times when ffprobe reports no container start time")
  void shouldKeepIndexTimesWhenFfprobeReportsNoContainerStartTime() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MP4_STREAMS.replace("\"start_time\":\"0.000000\",", ""))
            .packet("2.916667%+#1", MP4_LAST_KEYFRAME)
            .build();
    var request = keyframeRequest();

    var report = ffprobe.executor().probe(fixture("mp4-bframes-editlist.mp4"), request);

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
      "Should report the first video stream that is not a picture when audio and a picture precede it")
  void shouldReportFirstVideoStreamThatIsNotPictureWhenAudioAndPicturePrecedeIt() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(
                """
                {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","start_time":"0.000000"},
                 "streams":[
                  {"index":0,"codec_type":"audio","codec_name":"aac","id":"0x2",
                   "disposition":{"attached_pic":0}},
                  {"index":1,"codec_type":"video","codec_name":"mjpeg","id":"0x3",
                   "disposition":{"attached_pic":1}},
                  {"index":2,"codec_type":"video","codec_name":"h264","id":"0x1",
                   "disposition":{"attached_pic":0}}]}
                """)
            .packet("2.916667%+#1", MP4_LAST_KEYFRAME)
            .build();

    var report = ffprobe.executor().probe(fixture("mp4-bframes-editlist.mp4"), keyframeRequest());

    assertThat(report.result().getMedia().getKeyframes().getPositions())
        .extracting(KeyframePositions::getStreamIndex, KeyframePositions::getKeyframeCount)
        .containsExactly(2, 4);
  }

  @Test
  @DisplayName(
      "Should require a packet scan when ffprobe reports an MP4 track id that is not hexadecimal")
  void shouldRequirePacketScanWhenFfprobeReportsMp4TrackIdThatIsNotHexadecimal() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(
                """
                {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","start_time":"0.000000"},
                 "streams":[{"index":0,"codec_type":"video","codec_name":"h264","id":"0xZZ"}]}
                """)
            .build();

    var report = ffprobe.executor().probe(fixture("mp4-bframes-editlist.mp4"), keyframeRequest());

    assertThat(report.result().getMedia().getKeyframes().getPacketScanRequired())
        .isEqualTo(KeyframePacketScanReason.KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX);
  }

  @Test
  @DisplayName("Should report no video stream when a keyframe probe finds only audio")
  void shouldReportNoVideoStreamWhenKeyframeProbeFindsOnlyAudio() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(
                """
                {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","start_time":"0.000000"},
                 "streams":[{"index":0,"codec_type":"audio","codec_name":"aac","id":"0x2"}]}
                """)
            .build();

    var report = ffprobe.executor().probe(fixture("mp4-bframes-editlist.mp4"), keyframeRequest());

    assertThat(report)
        .extracting(
            found -> found.result().getFailure(), ProbeAttemptReport::keyframePositionsParts)
        .containsExactly(ProbeFailure.PROBE_FAILURE_NO_VIDEO_STREAM, List.of());
  }

  @Test
  @DisplayName("Should report keyframe positions from a packet scan when the request asks for one")
  void shouldReportKeyframePositionsFromPacketScanWhenRequestAsksForOne() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MPEGTS_STREAMS)
            .packetScan(
                """
                pts=133500|dts=126000|flags=K__|
                pts=144750|dts=129750|flags=___|
                pts=197250|dts=189750|flags=K__|
                pts=321000|dts=313500|flags=K__|
                pts=396000|dts=388500|flags=K__|
                """)
            .build();
    var request = packetScanRequest();

    var report = ffprobe.executor().probe(fixture("mpegts.ts"), request);

    assertThat(report.result().getMedia().getKeyframes())
        .isEqualTo(
            ProbeKeyframes.newBuilder()
                .setPositions(
                    KeyframePositions.newBuilder()
                        .setStreamIndex(0)
                        .setTimeBaseNumerator(1)
                        .setTimeBaseDenominator(90_000)
                        .setSource(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN)
                        .setKeyframeCount(4))
                .build());
    // The container starts at 1.460111 s, which is 131,410 ticks, at the first audio packet.
    assertThat(report.keyframePositionsParts())
        .containsExactly(
            ProbeKeyframePositionsPart.newBuilder()
                .setProbeAttemptId(request.getProbeAttemptId())
                .addAllPresentationTimeDeltas(List.of(2_090L, 63_750L, 123_750L, 75_000L))
                .addAllDecodeTimeDeltas(List.of(-5_410L, 63_750L, 123_750L, 75_000L))
                .build());
  }

  @Test
  @DisplayName(
      "Should take each keyframe's presentation time from its decode time when the scanned stream"
          + " does not reorder frames")
  void shouldTakeEachKeyframePresentationTimeFromDecodeTimeWhenScannedStreamDoesNotReorderFrames() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(
                """
                {"format":{"format_name":"avi","start_time":"0.000000"},
                 "streams":[{"index":0,"codec_type":"video","codec_name":"h264",
                             "time_base":"1/24","has_b_frames":0}]}
                """)
            .packetScan(
                """
                pts=N/A|dts=0|flags=K__
                pts=N/A|dts=1|flags=___
                pts=N/A|dts=18|flags=K__
                """)
            .build();

    var report = ffprobe.executor().probe(fixture("avi.avi"), packetScanRequest());

    assertThat(report.keyframePositionsParts())
        .singleElement()
        .satisfies(
            part -> {
              assertThat(part.getPresentationTimeDeltasList()).containsExactly(0L, 18L);
              assertThat(part.getDecodeTimeDeltasList()).containsExactly(0L, 18L);
            });
  }

  @Test
  @DisplayName(
      "Should report each Matroska keyframe's decode time as its presentation time when a packet"
          + " scan reads a Matroska source")
  void shouldReportEachMatroskaKeyframeDecodeTimeAsPresentationTimeWhenPacketScanReadsMatroska() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MATROSKA_STREAMS)
            .packetScan(
                """
                pts=0|dts=N/A|flags=K__
                pts=708|dts=625|flags=K__
                """)
            .build();

    var report = ffprobe.executor().probe(fixture("mkv-pipe.mkv"), packetScanRequest());

    assertThat(report.keyframePositionsParts())
        .singleElement()
        .satisfies(
            part -> {
              assertThat(part.getPresentationTimeDeltasList()).containsExactly(23L, 708L);
              assertThat(part.getDecodeTimeDeltasList()).containsExactly(23L, 708L);
            });
  }

  @ParameterizedTest
  @CsvSource({"pts=N/A|dts=189750|flags=K__", "pts=197250|dts=N/A|flags=K__"})
  @DisplayName(
      "Should report the keyframes unavailable when a keyframe packet has no presentation or decode"
          + " time")
  void shouldReportKeyframesUnavailableWhenKeyframePacketHasNoPresentationOrDecodeTime(
      String keyframePacket) {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MPEGTS_STREAMS)
            .packetScan(
                """
                pts=133500|dts=126000|flags=K__|
                pts=N/A|dts=129750|flags=___|
                %s
                pts=321000|dts=313500|flags=K__|
                """
                    .formatted(keyframePacket))
            .build();

    var report = ffprobe.executor().probe(fixture("mpegts.ts"), packetScanRequest());

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
  @DisplayName("Should report an execution failure when the packet scan exits with an error")
  void shouldReportExecutionFailureWhenPacketScanExitsWithError() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MPEGTS_STREAMS)
            .packetScan("pts=133500|dts=126000|flags=K__|\n")
            .packetScanExitCode(1)
            .build();

    var report = ffprobe.executor().probe(fixture("mpegts.ts"), packetScanRequest());

    assertThat(report)
        .isEqualTo(
            ProbeAttemptReport.resultOnly(
                ProbeAttemptResult.newBuilder()
                    .setProbeAttemptId(report.result().getProbeAttemptId())
                    .setProbeVersion(FfprobeExecutor.KEYFRAME_POSITIONS_PROBE_VERSION)
                    .setFailure(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED)
                    .build()));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "\"index\":0",
        "\"index\":0,\"time_base\":\"0:1\"",
        "\"index\":0,\"time_base\":\"1/x\""
      })
  @DisplayName(
      "Should report an execution failure when ffprobe gives the scanned stream no readable time"
          + " base")
  void shouldReportExecutionFailureWhenFfprobeGivesScannedStreamNoReadableTimeBase(
      String description) {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(
                """
                {"format":{"format_name":"mpegts","start_time":"0.000000"},
                 "streams":[{"codec_type":"video",%s}]}
                """
                    .formatted(description))
            .packetScan("pts=0|dts=0|flags=K__\n")
            .build();

    var report = ffprobe.executor().probe(fixture("mpegts.ts"), packetScanRequest());

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
  }

  private static String streamsOf(String formatName) {
    return """
        {"format":{"format_name":"%s","start_time":"0.000000"},
         "streams":[{"index":0,"codec_type":"video","codec_name":"h264","id":"0x1",
                     "disposition":{"attached_pic":0}}]}
        """
        .formatted(formatName);
  }

  private static ProbeRequest packetScanRequest() {
    return keyframeRequest().toBuilder().setScanKeyframePackets(true).build();
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
   * recorded for its exact read interval and {@code packetReadExitCode}. Any other read interval
   * finds no packet.
   */
  @Builder
  private record ScriptedFfprobe(
      String streams,
      @Singular Map<String, String> packets,
      int packetReadExitCode,
      String packetScan,
      int packetScanExitCode,
      boolean cancelAfterStreams) {

    FfprobeExecutor executor() {
      return new FfprobeExecutor(new ObjectMapper(), this::answer);
    }

    private Process answer(List<String> arguments) {
      var interval = arguments.indexOf("-read_intervals");
      if (interval >= 0) {
        return new CompletedProcess(
            packets.getOrDefault(arguments.get(interval + 1), NO_PACKETS),
            packetReadExitCode,
            false);
      }

      if (arguments.contains("packet=pts,dts,flags")) {
        assertThat(packetScan).as("a scripted packet scan").isNotNull();
        return new CompletedProcess(packetScan, packetScanExitCode, false);
      }

      return new CompletedProcess(streams, 0, cancelAfterStreams);
    }
  }

  /**
   * A process that has already exited with {@code exitCode}. With {@code cancelOnExit}, the attempt
   * is cancelled when the executor finds that the process has exited, after it has read the whole
   * output.
   */
  private static final class CompletedProcess extends Process {
    private final InputStream stdout;
    private final int exitCode;
    private final boolean cancelOnExit;

    private CompletedProcess(String stdout, int exitCode, boolean cancelOnExit) {
      this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
      this.exitCode = exitCode;
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
      return exitCode;
    }

    @Override
    public int exitValue() {
      return exitCode;
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
