package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.EbmlElements.CUES;
import static com.streamarr.transcode.probe.EbmlElements.CUE_POINT;
import static com.streamarr.transcode.probe.EbmlElements.CUE_TIME;
import static com.streamarr.transcode.probe.EbmlElements.CUE_TRACK;
import static com.streamarr.transcode.probe.EbmlElements.CUE_TRACK_POSITIONS;
import static com.streamarr.transcode.probe.EbmlElements.DURATION;
import static com.streamarr.transcode.probe.EbmlElements.INFO;
import static com.streamarr.transcode.probe.EbmlElements.MUXING_APP;
import static com.streamarr.transcode.probe.EbmlElements.SEEK_HEAD;
import static com.streamarr.transcode.probe.EbmlElements.SEGMENT;
import static com.streamarr.transcode.probe.EbmlElements.TIMESTAMP_SCALE;
import static com.streamarr.transcode.probe.EbmlElements.TRACKS;
import static com.streamarr.transcode.probe.EbmlElements.VOID;
import static com.streamarr.transcode.probe.EbmlElements.concat;
import static com.streamarr.transcode.probe.EbmlElements.document;
import static com.streamarr.transcode.probe.EbmlElements.element;
import static com.streamarr.transcode.probe.EbmlElements.header;
import static com.streamarr.transcode.probe.EbmlElements.unsigned;
import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

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
import com.streamarr.transcode.probe.EbmlElements.TrackEntry;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.Singular;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
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

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiterString = "|",
      textBlock =
          """
          no track id                  | N/A      | {"index":0,"codec_type":"video","codec_name":"h264"}
          a non-hexadecimal track id   | 0.000000 | {"index":0,"codec_type":"video","codec_name":"h264","id":"0xZZ"}
          an unreadable track id       | 0.000000 | {"index":0,"codec_type":"video","codec_name":"h264","id":"0xzz"}
          """)
  @DisplayName(
      "Should require a packet scan when ffprobe reports no usable track id for an MP4 stream")
  void shouldRequirePacketScanWhenFfprobeReportsNoUsableTrackIdForMp4Stream(
      String trackIdCase, String startTime, String stream) {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(
                """
                {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","start_time":"%s"},
                 "streams":[%s]}
                """
                    .formatted(startTime, stream))
            .build();

    var report = ffprobe.executor().probe(fixture("mp4-bframes-editlist.mp4"), keyframeRequest());

    assertThat(report.result().getMedia().getKeyframes().getPacketScanRequired())
        .as(trackIdCase)
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
                #tb 0: 1/90000
                0,     126000,     133500,     3750,      100, 0x00000000
                0,     129750,     144750,     3750,      100, 0x00000000, F=0x0
                0,     189750,     197250,     3750,      100, 0x00000000
                0,     313500,     321000,     3750,      100, 0x00000000
                0,     388500,     396000,     3750,      100, 0x00000000
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
                0, 0, -9223372036854775808, 1, 100, 0x00000000
                0, 1, -9223372036854775808, 1, 100, 0x00000000, F=0x0
                0, 18, -9223372036854775808, 1, 100, 0x00000000
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
                0, -9223372036854775808, 0, 42, 100, 0x00000000
                0, 625, 708, 42, 100, 0x00000000
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
  @CsvSource(
      delimiter = ';',
      value = {
        "0, 189750, -9223372036854775808, 3750, 100, 0x00000000",
        "0, -9223372036854775808, 197250, 3750, 100, 0x00000000"
      })
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
                0, 126000, 133500, 3750, 100, 0x00000000
                0, 129750, -9223372036854775808, 3750, 100, 0x00000000, F=0x0
                %s
                0, 313500, 321000, 3750, 100, 0x00000000
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
  @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  @DisplayName(
      "Should stop FFmpeg and report the keyframes unavailable when the packet scan finds a keyframe"
          + " without a time")
  void shouldStopFfmpegAndReportKeyframesUnavailableWhenPacketScanFindsKeyframeWithoutTime() {
    var ffmpeg =
        new EndlessPacketScan(
            "0, 126000, -9223372036854775808, 3750, 100, 0x00000000\n",
            "0, 129750, 144750, 3750, 100, 0x00000000, F=0x0\n");
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            command ->
                command.tool() == ProbeCommand.ProbeTool.FFMPEG
                    ? ffmpeg
                    : new CompletedProcess(MPEGTS_STREAMS, 0, false));

    var report = executor.probe(fixture("mpegts.ts"), packetScanRequest());

    assertThat(report.result().getMedia().getKeyframes().getUnavailable().getReason())
        .isEqualTo(
            KeyframesUnavailableReason.KEYFRAMES_UNAVAILABLE_REASON_KEYFRAME_WITHOUT_TIMESTAMP);
    assertThat(ffmpeg.isAlive()).isFalse();
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      value = {
        "0, 4004, 4004, 1001, 100, 0x00000002",
        "0, 4004, 6006, 1001, 100, 0x00000002",
        "0, 6006, 4004, 1001, 100, 0x00000002",
        "0, 2002, 6006, 1001, 100, 0x00000002",
        "0, 6006, 2002, 1001, 100, 0x00000002"
      })
  @DisplayName(
      "Should report invalid media when a scanned keyframe's times do not both come after the"
          + " previous keyframe's")
  void shouldReportInvalidMediaWhenScannedKeyframeTimesDoNotBothComeAfterPreviousKeyframe(
      String keyframePacket) {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MPEGTS_STREAMS)
            .packetScan(
                """
                0, 0, 0, 1001, 100, 0x00000000
                0, 1001, 1001, 1001, 100, 0x00000000, F=0x0
                0, 2002, 2002, 1001, 100, 0x00000000
                0, 4004, 4004, 1001, 100, 0x00000001
                %s
                0, 8008, 8008, 1001, 100, 0x00000000
                """
                    .formatted(keyframePacket))
            .build();

    var report = ffprobe.executor().probe(fixture("mpegts.ts"), packetScanRequest());

    assertThat(report)
        .isEqualTo(
            ProbeAttemptReport.resultOnly(
                ProbeAttemptResult.newBuilder()
                    .setProbeAttemptId(report.result().getProbeAttemptId())
                    .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
                    .setFailure(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA)
                    .build()));
  }

  @Test
  @DisplayName(
      "Should report invalid media when a Matroska keyframe repeats the previous keyframe's"
          + " presentation time")
  void shouldReportInvalidMediaWhenMatroskaKeyframeRepeatsPreviousKeyframePresentationTime() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MATROSKA_STREAMS)
            .packetScan(
                """
                0, 0, 0, 42, 100, 0x00000000
                0, 12012, 12012, 42, 119090, 0x00000001
                0, 12013, 12012, 42, 119090, 0x00000002
                """)
            .build();

    var report = ffprobe.executor().probe(fixture("mkv-pipe.mkv"), packetScanRequest());

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
    assertThat(report.keyframePositionsParts()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should report keyframe positions when Matroska keyframes repeat a decode time and their"
          + " presentation times increase")
  void
      shouldReportKeyframePositionsWhenMatroskaKeyframesRepeatDecodeTimeAndPresentationIncreases() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MATROSKA_STREAMS)
            .packetScan(
                """
                0, 0, 0, 42, 100, 0x00000000
                0, 0, 625, 42, 100, 0x00000000
                """)
            .build();

    var report = ffprobe.executor().probe(fixture("mkv-pipe.mkv"), packetScanRequest());

    assertThat(report.keyframePositionsParts())
        .singleElement()
        .satisfies(
            part -> {
              assertThat(part.getPresentationTimeDeltasList()).containsExactly(23L, 625L);
              assertThat(part.getDecodeTimeDeltasList()).containsExactly(23L, 625L);
            });
  }

  @Test
  @Timeout(value = 10, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
  @DisplayName(
      "Should stop FFmpeg and report invalid media when the packet scan finds a keyframe at the"
          + " previous keyframe's times")
  void shouldStopFfmpegAndReportInvalidMediaWhenPacketScanFindsKeyframeAtPreviousKeyframeTimes() {
    var ffmpeg =
        new EndlessPacketScan(
            "0, 0, 0, 1001, 100, 0x00000000\n", "0, 2002, 2002, 1001, 100, 0x00000000\n");
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            command ->
                command.tool() == ProbeCommand.ProbeTool.FFMPEG
                    ? ffmpeg
                    : new CompletedProcess(MPEGTS_STREAMS, 0, false));

    var report = executor.probe(fixture("mpegts.ts"), packetScanRequest());

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
    assertThat(ffmpeg.isAlive()).isFalse();
  }

  @Test
  @DisplayName(
      "Should report keyframe positions without a keyframe when the packet scan finds no keyframe"
          + " packet")
  void shouldReportKeyframePositionsWithoutKeyframeWhenPacketScanFindsNoKeyframePacket() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MPEGTS_STREAMS)
            .packetScan(
                """
                0, 126000, 133500, 3750, 100, 0x00000000, F=0x0
                0, 129750, 144750, 3750, 100, 0x00000000, F=0x0
                """)
            .build();

    var report = ffprobe.executor().probe(fixture("mpegts.ts"), packetScanRequest());

    assertThat(report.result().getMedia().getKeyframes())
        .isEqualTo(
            ProbeKeyframes.newBuilder()
                .setPositions(
                    KeyframePositions.newBuilder()
                        .setStreamIndex(0)
                        .setTimeBaseNumerator(1)
                        .setTimeBaseDenominator(90_000)
                        .setSource(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN)
                        .setKeyframeCount(0))
                .build());
    assertThat(report.keyframePositionsParts()).isEmpty();
  }

  @Test
  @DisplayName("Should report an execution failure when the packet scan exits with an error")
  void shouldReportExecutionFailureWhenPacketScanExitsWithError() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MPEGTS_STREAMS)
            .packetScan("0, 126000, 133500, 3750, 100, 0x00000000\n")
            .packetScanExitCode(1)
            .build();

    var report = ffprobe.executor().probe(fixture("mpegts.ts"), packetScanRequest());

    assertThat(report)
        .isEqualTo(
            ProbeAttemptReport.resultOnly(
                ProbeAttemptResult.newBuilder()
                    .setProbeAttemptId(report.result().getProbeAttemptId())
                    .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
                    .setFailure(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED)
                    .build()));
  }

  @Test
  @DisplayName(
      "Should report an execution failure when FFmpeg reports a demuxing error after the keyframes"
          + " it printed")
  void shouldReportExecutionFailureWhenFfmpegReportsDemuxingErrorAfterKeyframesItPrinted() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MPEGTS_STREAMS)
            .packetScan(
                """
                0, 126000, 133500, 3750, 100, 0x00000000
                0, 189750, 197250, 3750, 100, 0x00000000
                """)
            .packetScanDiagnostics(
                "[in#0/mpegts @ 0x1] Error during demuxing: Input/output error\n")
            .build();

    var report = ffprobe.executor().probe(fixture("mpegts.ts"), packetScanRequest());

    assertThat(report)
        .isEqualTo(
            ProbeAttemptReport.resultOnly(
                ProbeAttemptResult.newBuilder()
                    .setProbeAttemptId(report.result().getProbeAttemptId())
                    .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
                    .setFailure(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED)
                    .build()));
  }

  @Test
  @DisplayName(
      "Should report keyframe positions when FFmpeg reports a diagnostic other than a demuxing"
          + " error")
  void shouldReportKeyframePositionsWhenFfmpegReportsDiagnosticOtherThanDemuxingError() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(MPEGTS_STREAMS)
            .packetScan("0, 126000, 133500, 3750, 100, 0x00000000\n")
            .packetScanDiagnostics("[h264 @ 0x1] non-existing PPS 0 referenced\n")
            .build();

    var report = ffprobe.executor().probe(fixture("mpegts.ts"), packetScanRequest());

    assertThat(report.result().getMedia().getKeyframes().getPositions().getKeyframeCount())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Should report an execution failure when FFmpeg prints an unreadable packet line")
  void shouldReportExecutionFailureWhenFfmpegPrintsUnreadablePacketLine() {
    var ffprobe =
        ScriptedFfprobe.builder().streams(MPEGTS_STREAMS).packetScan("0, 126000, 133500\n").build();

    var report = ffprobe.executor().probe(fixture("mpegts.ts"), packetScanRequest());

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
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
            .packetScan("0, 0, 0, 3750, 100, 0x00000000\n")
            .build();

    var report = ffprobe.executor().probe(fixture("mpegts.ts"), packetScanRequest());

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
  }

  @Test
  @DisplayName(
      "Should take each keyframe's decode time from its presentation time when the scanned stream"
          + " does not reorder frames")
  void shouldTakeEachKeyframeDecodeTimeFromPresentationTimeWhenScannedStreamDoesNotReorderFrames() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(
                """
                {"format":{"format_name":"mpegts","start_time":"0.000000"},
                 "streams":[{"index":0,"codec_type":"video","codec_name":"h264",
                             "time_base":"1/90000","has_b_frames":0}]}
                """)
            .packetScan(
                """
                0, -9223372036854775808, 0, 3750, 100, 0x00000000
                0, 3750, 3750, 3750, 100, 0x00000000, F=0x0
                0, -9223372036854775808, 67500, 3750, 100, 0x00000000
                """)
            .build();

    var report = ffprobe.executor().probe(fixture("mpegts.ts"), packetScanRequest());

    assertThat(report.keyframePositionsParts())
        .singleElement()
        .satisfies(
            part -> {
              assertThat(part.getPresentationTimeDeltasList()).containsExactly(0L, 67_500L);
              assertThat(part.getDecodeTimeDeltasList()).containsExactly(0L, 67_500L);
            });
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {"h264-sei-1.h264", "hevc-food.hevc", "h264-ref_10.avi", "avi-bframes.avi"})
  @DisplayName(
      "Should report the keyframes unavailable when the recorded packet scan of a stream that"
          + " reorders frames finds a keyframe whose demuxer gives it no presentation time")
  void
      shouldReportTheKeyframesUnavailableWhenTheRecordedPacketScanOfAStreamThatReordersFramesFindsAKeyframeWhoseDemuxerGivesItNoPresentationTime(
          String recording) {
    var report = recordedScan(recording).executor().probe(Path.of(recording), packetScanRequest());

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

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {"hevc-pir.hevc | 0", "avi.avi | 0, 18, 33, 20"})
  @DisplayName(
      "Should report the scanned keyframe positions when the recorded packet scan gives each"
          + " keyframe both of its times")
  void shouldReportTheScannedKeyframePositionsWhenTheRecordedPacketScanGivesEachKeyframeBothTimes(
      String recording, String deltas) {
    // These streams do not reorder frames, and FFmpeg fills the time their demuxer leaves out, so
    // the probe's own rule for a missing time is left to the scripted scans.
    var expected = Arrays.stream(deltas.split(", ")).map(Long::valueOf).toList();

    var report = recordedScan(recording).executor().probe(Path.of(recording), packetScanRequest());

    assertThat(report.result().getMedia().getKeyframes().getPositions().getSource())
        .isEqualTo(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_PACKET_SCAN);
    assertThat(report.keyframePositionsParts())
        .singleElement()
        .satisfies(
            part -> {
              assertThat(part.getPresentationTimeDeltasList()).isEqualTo(expected);
              assertThat(part.getDecodeTimeDeltasList()).isEqualTo(expected);
            });
  }

  @Test
  @DisplayName(
      "Should report the keyframes unavailable when ffprobe does not say whether the scanned stream"
          + " reorders frames")
  void shouldReportKeyframesUnavailableWhenFfprobeDoesNotSayWhetherScannedStreamReordersFrames() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(
                """
                {"format":{"format_name":"avi","start_time":"0.000000"},
                 "streams":[{"index":0,"codec_type":"video","codec_name":"h264",
                             "time_base":"1/24"}]}
                """)
            .packetScan("0, 0, -9223372036854775808, 1, 100, 0x00000000\n")
            .build();

    var report = ffprobe.executor().probe(fixture("avi.avi"), packetScanRequest());

    assertThat(report.result().getMedia().getKeyframes().getUnavailable().getReason())
        .isEqualTo(
            KeyframesUnavailableReason.KEYFRAMES_UNAVAILABLE_REASON_KEYFRAME_WITHOUT_TIMESTAMP);
    assertThat(report.keyframePositionsParts()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should report the scanned keyframe times unchanged when ffprobe gives the source no start"
          + " time")
  void shouldReportScannedKeyframeTimesUnchangedWhenFfprobeGivesSourceNoStartTime() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(
                """
                {"format":{"format_name":"mpegts","start_time":"N/A"},
                 "streams":[{"index":0,"codec_type":"video","codec_name":"h264",
                             "time_base":"1/90000","has_b_frames":2}]}
                """)
            .packetScan(
                """
                0, 126000, 133500, 3750, 100, 0x00000000
                0, 189750, 197250, 3750, 100, 0x00000000
                """)
            .build();

    var report = ffprobe.executor().probe(fixture("mpegts.ts"), packetScanRequest());

    assertThat(report.keyframePositionsParts())
        .singleElement()
        .satisfies(
            part -> {
              assertThat(part.getPresentationTimeDeltasList()).containsExactly(133_500L, 63_750L);
              assertThat(part.getDecodeTimeDeltasList()).containsExactly(126_000L, 63_750L);
            });
  }

  @Test
  @DisplayName("Should scan the first video stream when another stream comes before it")
  void shouldScanFirstVideoStreamWhenAnotherStreamComesBeforeIt() {
    var ffprobe =
        ScriptedFfprobe.builder()
            .streams(
                """
                {"format":{"format_name":"mpegts","start_time":"0.000000"},
                 "streams":[
                  {"index":0,"codec_type":"audio","codec_name":"aac","time_base":"1/90000"},
                  {"index":1,"codec_type":"video","codec_name":"h264","time_base":"1/90000",
                   "has_b_frames":2,"disposition":{"attached_pic":0}}]}
                """)
            .packetScan("1, 0, 0, 3750, 100, 0x00000000\n")
            .build();

    var report = ffprobe.executor().probe(fixture("mpegts.ts"), packetScanRequest());

    assertThat(report.result().getMedia().getKeyframes().getPositions().getStreamIndex())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("Should report an execution failure when FFmpeg's diagnostics cannot be read")
  void shouldReportExecutionFailureWhenFfmpegDiagnosticsCannotBeRead() {
    var ffmpeg =
        new CompletedProcess("0, 126000, 133500, 3750, 100, 0x00000000\n", 0, false)
            .withUnreadableDiagnostics();
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            command ->
                command.tool() == ProbeCommand.ProbeTool.FFMPEG
                    ? ffmpeg
                    : new CompletedProcess(MPEGTS_STREAMS, 0, false));

    var report = executor.probe(fixture("mpegts.ts"), packetScanRequest());

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
  }

  @Nested
  @DisplayName("Matroska Cues Tests")
  class MatroskaCuesTests {

    private static final String ONE_VIDEO_STREAM =
        """
        {"format":{"format_name":"matroska,webm","start_time":"0.000000"},
         "streams":[{"index":0,"codec_type":"video","codec_name":"hevc","time_base":"1/1000",
                     "disposition":{"attached_pic":0}}]}
        """;

    @TempDir Path temporaryDirectory;

    @ParameterizedTest(name = "{0}")
    @MethodSource("infosThatFfmpegReadsInMilliseconds")
    @DisplayName(
        "Should report the cue times in milliseconds when the Info's last TimestampScale is empty,"
            + " zero or missing")
    void shouldReportTheCueTimesInMillisecondsWhenTheInfosLastTimestampScaleIsEmptyZeroOrMissing(
        String description, byte[] info) throws IOException {
      var report = probe(document(info, videoTrack(1), cues(cue(0, 1), cue(2_000, 1))));

      assertThat(report.result().getMedia().getKeyframes().getPositions())
          .as(description)
          .isEqualTo(cuePositions(1_000, 2));
      assertThat(report.keyframePositionsParts())
          .singleElement()
          .extracting(ProbeKeyframePositionsPart::getPresentationTimeDeltasList)
          .isEqualTo(List.of(0L, 2_000L));
    }

    static Stream<Arguments> infosThatFfmpegReadsInMilliseconds() {
      return Stream.of(
          Arguments.of(
              "an empty repeat",
              info(element(TIMESTAMP_SCALE, unsigned(1_000)), element(TIMESTAMP_SCALE))),
          Arguments.of(
              "a zero repeat",
              info(
                  element(TIMESTAMP_SCALE, unsigned(1_000)),
                  element(TIMESTAMP_SCALE, new byte[1]))),
          // FFmpeg reads an empty float as zero.
          Arguments.of("no TimestampScale and an empty Duration", info(element(DURATION))));
    }

    @Test
    @DisplayName("Should report the cue times in the last TimestampScale when the Info repeats it")
    void shouldReportTheCueTimesInTheLastTimestampScaleWhenTheInfoRepeatsIt() throws IOException {
      var info =
          info(
              element(TIMESTAMP_SCALE, unsigned(1_000_000)),
              element(TIMESTAMP_SCALE, unsigned(100_000)));

      var report = probe(document(info, videoTrack(1), cues(cue(41, 1))));

      assertThat(report.result().getMedia().getKeyframes().getPositions())
          .isEqualTo(cuePositions(10_000, 1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedChildrenAheadOfARepeat")
    @DisplayName(
        "Should require a packet scan when a malformed child precedes a repeat in the Info or a"
            + " CuePoint")
    void shouldRequireAPacketScanWhenAMalformedChildPrecedesARepeatInTheInfoOrACuePoint(
        String description, byte[] file) throws IOException {
      var report = probe(file);

      assertThat(report.result().getMedia().getKeyframes())
          .as(description)
          .isEqualTo(
              ProbeKeyframes.newBuilder()
                  .setPacketScanRequired(
                      KeyframePacketScanReason
                          .KEYFRAME_PACKET_SCAN_REASON_INCOMPLETE_CONTAINER_INDEX)
                  .build());
    }

    static Stream<Arguments> malformedChildrenAheadOfARepeat() {
      // FFmpeg's parse of the element stops at the malformed child, so it never reads the repeat.
      var malformedDuration =
          info(
              element(TIMESTAMP_SCALE, unsigned(1_000_000)),
              element(DURATION, new byte[3]),
              element(TIMESTAMP_SCALE, unsigned(1_000)));
      var malformedCueTrack =
          element(
              CUE_POINT,
              element(CUE_TIME, unsigned(2_000)),
              element(CUE_TRACK_POSITIONS, element(CUE_TRACK, unsigned(1))),
              element(CUE_TRACK_POSITIONS, element(CUE_TRACK, new byte[9])),
              element(CUE_TIME, unsigned(5_000)));
      return Stream.of(
          Arguments.of(
              "a three-byte Duration ahead of a TimestampScale",
              document(malformedDuration, videoTrack(1), cues(cue(0, 1)))),
          Arguments.of(
              "a nine-byte CueTrack ahead of a CueTime",
              document(info(), videoTrack(1), element(CUES, malformedCueTrack, cue(10_000, 1)))));
    }

    @Test
    @DisplayName("Should report the cue times when a Void element precedes the Segment")
    void shouldReportTheCueTimesWhenAVoidElementPrecedesTheSegment() throws IOException {
      var file =
          concat(
              header(),
              element(VOID, new byte[4]),
              element(SEGMENT, info(), videoTrack(1), cues(cue(0, 1), cue(2_000, 1))));

      var report = probe(file);

      assertThat(report.result().getMedia().getKeyframes().getPositions())
          .isEqualTo(cuePositions(1_000, 2));
    }

    @Test
    @DisplayName(
        "Should report the cue times of the chosen track when FFmpeg skips an earlier video"
            + " TrackEntry without a CodecID")
    void shouldReportTheCueTimesOfTheChosenTrackWhenFfmpegSkipsAnEarlierVideoTrackEntry()
        throws IOException {
      // FFmpeg makes no stream of a video track without a CodecID, so ffprobe's first video
      // stream is track 2.
      var tracks =
          element(
              TRACKS,
              TrackEntry.video().codecId(null).build().bytes(),
              TrackEntry.video().number(2).build().bytes());

      var report = probe(document(info(), tracks, cues(cue(0, 1, 2), cue(500, 1), cue(2_000, 2))));

      assertThat(report.keyframePositionsParts())
          .singleElement()
          .extracting(ProbeKeyframePositionsPart::getPresentationTimeDeltasList)
          .isEqualTo(List.of(0L, 2_000L));
    }

    @Test
    @DisplayName(
        "Should report the cue times when a malformed element follows the Info, Tracks and Cues")
    void shouldReportTheCueTimesWhenAMalformedElementFollowsTheInfoTracksAndCues()
        throws IOException {
      // A zero byte starts no EBML element ID.
      var malformed = element(SEEK_HEAD, new byte[] {0x00, 0x00});

      var report =
          probe(document(info(), videoTrack(1), cues(cue(0, 1), cue(2_000, 1)), malformed));

      assertThat(report.result().getMedia().getKeyframes().getPositions())
          .isEqualTo(cuePositions(1_000, 2));
    }

    /**
     * Probes the file for its keyframes, with ffprobe finding the last cue's keyframe where the
     * Cues place it.
     */
    private ProbeAttemptReport probe(byte[] file) throws IOException {
      var source = Files.write(temporaryDirectory.resolve("source.mkv"), file);
      var lastKeyframes =
          Map.of(
              "2.000000%+#1", "{\"packets\":[{\"pts\":2000,\"flags\":\"K__\"}]}",
              "0.004100%+#1", "{\"packets\":[{\"pts\":41,\"flags\":\"K__\"}]}");
      return ScriptedFfprobe.builder()
          .streams(ONE_VIDEO_STREAM)
          .packets(lastKeyframes)
          .build()
          .executor()
          .probe(source, keyframeRequest());
    }

    private static KeyframePositions cuePositions(int denominator, int keyframeCount) {
      return KeyframePositions.newBuilder()
          .setStreamIndex(0)
          .setTimeBaseNumerator(1)
          .setTimeBaseDenominator(denominator)
          .setSource(KeyframePositionSource.KEYFRAME_POSITION_SOURCE_CONTAINER_INDEX)
          .setKeyframeCount(keyframeCount)
          .build();
    }

    /** An Info written by libavformat, which writes a cue for every video keyframe. */
    private static byte[] info(byte[]... children) {
      return element(
          INFO,
          element(MUXING_APP, "Lavf62.12.103".getBytes(StandardCharsets.UTF_8)),
          concat(children));
    }

    private static byte[] videoTrack(long number) {
      return element(TRACKS, TrackEntry.video().number(number).build().bytes());
    }

    private static byte[] cues(byte[]... points) {
      return element(CUES, points);
    }

    private static byte[] cue(long time, long... tracks) {
      var children = new ArrayList<byte[]>();
      children.add(element(CUE_TIME, unsigned(time)));
      for (var track : tracks) {
        children.add(element(CUE_TRACK_POSITIONS, element(CUE_TRACK, unsigned(track))));
      }

      return element(CUE_POINT, children.toArray(byte[][]::new));
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

  private static ProbeRequest packetScanRequest() {
    return keyframeRequest().toBuilder().setScanKeyframePackets(true).build();
  }

  private static ProbeRequest keyframeRequest() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
        .build();
  }

  /**
   * The probe's ffprobe output and packet scan that the pinned worker image recorded into {@code
   * src/test/resources/keyframes/scans}.
   */
  private static ScriptedFfprobe recordedScan(String recording) {
    var diagnostics =
        fixture("scans/" + recording + ".json").resolveSibling(recording + ".diagnostics");
    try {
      return ScriptedFfprobe.builder()
          .streams(Files.readString(fixture("scans/" + recording + ".json")))
          .packetScan(Files.readString(fixture("scans/" + recording + ".framecrc")))
          .packetScanDiagnostics(Files.exists(diagnostics) ? Files.readString(diagnostics) : "")
          .build();
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
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
   * Answers the probe command with recorded stream JSON, a last-keyframe read with the packets
   * recorded for its exact read interval and {@code packetReadExitCode}, and FFmpeg's packet scan
   * with recorded framecrc lines and diagnostics. Any other read interval finds no packet.
   */
  @Builder
  private record ScriptedFfprobe(
      String streams,
      @Singular Map<String, String> packets,
      int packetReadExitCode,
      String packetScan,
      String packetScanDiagnostics,
      int packetScanExitCode,
      boolean cancelAfterStreams) {

    FfprobeExecutor executor() {
      return new FfprobeExecutor(new ObjectMapper(), this::answer);
    }

    private Process answer(ProbeCommand command) {
      var arguments = command.arguments();
      var interval = arguments.indexOf("-read_intervals");
      if (interval >= 0) {
        return new CompletedProcess(
            packets.getOrDefault(arguments.get(interval + 1), NO_PACKETS),
            packetReadExitCode,
            false);
      }

      if (command.tool() == ProbeCommand.ProbeTool.FFMPEG) {
        assertThat(packetScan).as("a scripted packet scan").isNotNull();
        return new CompletedProcess(packetScan, packetScanExitCode, false)
            .withDiagnostics(packetScanDiagnostics == null ? "" : packetScanDiagnostics);
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
    private InputStream stderr = InputStream.nullInputStream();

    private CompletedProcess(String stdout, int exitCode, boolean cancelOnExit) {
      this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
      this.exitCode = exitCode;
      this.cancelOnExit = cancelOnExit;
    }

    private CompletedProcess withDiagnostics(String diagnostics) {
      stderr = new ByteArrayInputStream(diagnostics.getBytes(StandardCharsets.UTF_8));
      return this;
    }

    private CompletedProcess withUnreadableDiagnostics() {
      stderr =
          new InputStream() {
            @Override
            public int read() throws IOException {
              throw new IOException("Diagnostics pipe unavailable");
            }
          };
      return this;
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
      return stderr;
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

  /**
   * A packet scan that prints its first line and then repeats the next line until the process is
   * destroyed, as FFmpeg does while it reads a long source. Like the JDK's process, destroying it
   * closes its standard error, so a blocked read of standard error fails.
   */
  private static final class EndlessPacketScan extends Process {
    private final CountDownLatch destroyed = new CountDownLatch(1);
    private final InputStream stdout;

    private EndlessPacketScan(String firstLine, String repeatedLine) {
      var first = firstLine.getBytes(StandardCharsets.UTF_8);
      var repeated = repeatedLine.getBytes(StandardCharsets.UTF_8);
      this.stdout =
          new InputStream() {
            private long position;

            @Override
            public int read() {
              if (destroyed.getCount() == 0) {
                return -1;
              }

              var index = position++;
              if (index < first.length) {
                return first[(int) index];
              }

              return repeated[(int) ((index - first.length) % repeated.length)];
            }
          };
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
      return new InputStream() {
        @Override
        public int read() throws IOException {
          try {
            destroyed.await();
          } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
          }

          throw new IOException("Stream closed");
        }
      };
    }

    @Override
    public int waitFor() throws InterruptedException {
      destroyed.await();
      return 137;
    }

    @Override
    public int exitValue() {
      if (isAlive()) {
        throw new IllegalThreadStateException("FFmpeg is still scanning");
      }

      return 137;
    }

    @Override
    public boolean isAlive() {
      return destroyed.getCount() > 0;
    }

    @Override
    public void destroy() {
      destroyed.countDown();
    }
  }
}
