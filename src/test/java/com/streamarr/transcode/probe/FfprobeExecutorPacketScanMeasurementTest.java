package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.MeasuredStream;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamMeasurementPart;
import build.buf.gen.streamarr.transcode.v1.StreamMeasurementSource;
import build.buf.gen.streamarr.transcode.v1.StreamMeasurements;
import com.streamarr.transcode.fakes.ScriptedProcess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

// ADR 0054: a keyframe packet scan measures the chosen video stream and every audio stream in the
// read that finds the keyframes. A chosen video packet falls in the keyframe interval of its decode
// order, an audio packet in the interval that holds its presentation time, and a packet before the
// first keyframe in the first interval. The worker reports no measurement when a copy would change
// a measured stream's packets. FFmpeg's framecrc lines are: output stream, decode time,
// presentation time, duration, size and checksum, then the flags of a packet that is not only a
// keyframe.
@Tag("UnitTest")
@Timeout(60)
@DisplayName("Ffprobe Executor Packet Scan Measurement Tests")
class FfprobeExecutorPacketScanMeasurementTest {

  private static final String MPEG2_VIDEO =
      "{\"index\":0,\"codec_type\":\"video\",\"codec_name\":\"mpeg2video\","
          + "\"time_base\":\"1/90000\",\"has_b_frames\":1}";
  private static final String AC3_AUDIO =
      "{\"index\":1,\"codec_type\":\"audio\",\"codec_name\":\"ac3\","
          + "\"sample_rate\":\"48000\",\"time_base\":\"1/90000\"}";
  private static final String MP2_AUDIO =
      "{\"index\":2,\"codec_type\":\"audio\",\"codec_name\":\"mp2\","
          + "\"sample_rate\":\"44100\",\"time_base\":\"1/90000\"}";

  /** MPEG-2 video, AC-3 and MP2 in MPEG-TS: a stream copy keeps every packet of each stream. */
  private static final String MPEG2_AC3_MP2 = mpegTs(MPEG2_VIDEO, AC3_AUDIO, MP2_AUDIO);

  /** MPEG-4 part 2 video without B-frames and MP3 in AVI, as FFmpeg's AVI demuxer times them. */
  private static final String MPEG4_MP3_AVI =
      """
      {"format":{"format_name":"avi","start_time":"0.000000"},
       "streams":[
        {"index":0,"codec_type":"video","codec_name":"mpeg4","time_base":"1/24",
         "has_b_frames":0},
        {"index":1,"codec_type":"audio","codec_name":"mp3","sample_rate":"44100",
         "time_base":"32/1225"}]}
      """;

  private static final String NO_TIME = Long.toString(Long.MIN_VALUE);

  private Path source;

  @BeforeEach
  void createSource(@TempDir Path directory) throws IOException {
    source = Files.createFile(directory.resolve("source.media"));
  }

  // Video: the I-frame at 7,500 opens the first interval, and the B- and P-frames after it in
  // decode order stay in it, though the B-frame presents first. The I-frame at 18,750 opens the
  // second. AC-3 at 0 presents before the first keyframe, so it falls in the first interval, and
  // MP2 at 18,750 presents exactly at the second keyframe. Each duration runs from the stream's
  // first presentation time to the end of its last packet.
  @Test
  @DisplayName(
      "Should measure the chosen video stream by decode order and each audio stream by"
          + " presentation time when a keyframe packet scan reads a source that a copy keeps")
  void shouldMeasureVideoByDecodeOrderAndAudioByPresentationTimeWhenScanReadsSourceThatCopyKeeps() {
    var scan =
        ScriptedScan.builder()
            .streams(MPEG2_AC3_MP2)
            .packetScan(
                """
                #tb 0: 1/90000
                #tb 1: 1/90000
                #tb 2: 1/90000
                0,          0,       7500,     3750,     4000, 0x00000000
                1,          0,          0,     2880,      768, 0x00000000
                0,       3750,       3750,     3750,      400, 0x00000000, F=0x0
                2,       3000,       3000,     2351,      417, 0x00000000
                0,       7500,      11250,     3750,      500, 0x00000000, F=0x0
                1,       2880,       2880,     2880,      768, 0x00000000
                0,      11250,      18750,     3750,     6000, 0x00000000
                0,      15000,      15000,     3750,      450, 0x00000000, F=0x0
                2,      18750,      18750,     2351,      417, 0x00000000
                1,      20160,      20160,     2880,      768, 0x00000000
                """)
            .build();
    var request = request();

    var report = scan.executor().probe(source, request);

    assertThat(report.result().getMedia().getStreamMeasurements())
        .isEqualTo(
            StreamMeasurements.newBuilder()
                .setSource(StreamMeasurementSource.STREAM_MEASUREMENT_SOURCE_KEYFRAME_PACKET_SCAN)
                .addStreams(measured(0, 11_350, 5).setMeasuredDuration(18_750))
                .addStreams(measured(1, 2_304, 3).setMeasuredDuration(23_040))
                .addStreams(measured(2, 834, 2).setMeasuredDuration(18_101))
                .build());
    assertThat(report.streamMeasurementParts())
        .containsExactly(
            part(request, 0)
                .addAllKeyframeIntervalBytes(List.of(4_900, 6_450))
                .addAllKeyframeIntervalPacketCounts(List.of(3, 2))
                .build(),
            part(request, 1)
                .addAllKeyframeIntervalBytes(List.of(1_536, 768))
                .addAllKeyframeIntervalPacketCounts(List.of(2, 1))
                .build(),
            part(request, 2)
                .addAllKeyframeIntervalBytes(List.of(417, 417))
                .addAllKeyframeIntervalPacketCounts(List.of(1, 1))
                .build());
  }

  // FFmpeg interleaves its output by decode time, so audio that presents at or after a keyframe can
  // come out before that keyframe when the keyframe decodes earlier than it presents. The AC-3
  // packet at 18,750 presents at the second keyframe and the one at 15,000 before it; both come
  // out before that keyframe.
  @Test
  @DisplayName(
      "Should count an audio packet in the interval that holds its presentation time when FFmpeg"
          + " prints it before the keyframe that opens that interval")
  void shouldCountAudioPacketInIntervalHoldingItsTimeWhenFfmpegPrintsItBeforeThatKeyframe() {
    var scan =
        ScriptedScan.builder()
            .streams(MPEG2_AC3_MP2)
            .packetScan(
                """
                0,          0,          0,     3750,     1000, 0x00000000
                1,      18750,      18750,     2880,      768, 0x00000000
                1,      15000,      15000,     2880,      700, 0x00000000
                2,          0,          0,     2351,      417, 0x00000000
                0,       3750,      18750,     3750,     2000, 0x00000000
                """)
            .build();
    var request = request();

    var report = scan.executor().probe(source, request);

    assertThat(report.streamMeasurementParts())
        .filteredOn(part -> part.getStreamIndex() == 1)
        .containsExactly(
            part(request, 1)
                .addAllKeyframeIntervalBytes(List.of(700, 768))
                .addAllKeyframeIntervalPacketCounts(List.of(1, 1))
                .build());
  }

  // Each AC-3 packet presents before the video's latest decode time, so the scan counts it at once
  // and holds none of them, though there are more than it would hold waiting.
  @Test
  @DisplayName(
      "Should measure every audio packet when more of them than the scan holds waiting present"
          + " before the video's latest decode time")
  void shouldMeasureEveryAudioPacketWhenMoreThanScanHoldsPresentBeforeVideosLatestDecodeTime() {
    var audioPackets = WAITING_AUDIO_PACKETS_THAT_THE_SCAN_HOLDS + 1;
    var packetScan =
        new StringBuilder("0, 0, 0, 3750, 4000, 0x00000000\n2, 0, 0, 2351, 417, 0x00000000\n");
    for (var packet = 1L; packet <= audioPackets; packet++) {
      packetScan
          .append("0, %d, %d, 3750, 1, 0x00000000, F=0x0\n".formatted(packet * 3750, packet * 3750))
          .append(
              "1, %d, %d, 2880, 1, 0x00000000\n"
                  .formatted(packet * 3750 - 3750, packet * 3750 - 3750));
    }

    var keyframe = (audioPackets + 1L) * 3750;
    packetScan.append("0, %d, %d, 3750, 6000, 0x00000000\n".formatted(keyframe, keyframe));
    var scan =
        ScriptedScan.builder().streams(MPEG2_AC3_MP2).packetScan(packetScan.toString()).build();
    var request = request();

    var report = scan.executor().probe(source, request);

    assertThat(report.streamMeasurementParts())
        .filteredOn(part -> part.getStreamIndex() == 1)
        .containsExactly(
            part(request, 1)
                .addAllKeyframeIntervalBytes(List.of(audioPackets, 0))
                .addAllKeyframeIntervalPacketCounts(List.of(audioPackets, 0))
                .build());
  }

  static Stream<Named<String>> scansWhereAudioPacketWaitsForTheKeyframeAfterIt() {
    return Stream.of(
        // The AC-3 packet at 20,000 comes before any video packet, and the video packet without a
        // decode time tells nothing of how far the video decoded.
        Named.of(
            "no video packet before it gives a decode time past it",
            """
            1,      20000,      20000,     2880,      768, 0x00000000
            0,          0,          0,     3750,     1000, 0x00000000
            0,         %s,      30000,     3750,      500, 0x00000000, F=0x0
            2,          0,          0,     2351,      417, 0x00000000
            0,       3750,      18750,     3750,     2000, 0x00000000
            """
                .formatted(NO_TIME)),
        // A keyframe can present at the video's latest decode time, as the one at 3,750 does.
        Named.of(
            "it presents at the video's latest decode time",
            """
            0,          0,          0,     3750,     1000, 0x00000000
            0,       3750,      11250,     3750,      500, 0x00000000, F=0x0
            1,       3750,       3750,     2880,      768, 0x00000000
            2,          0,          0,     2351,      417, 0x00000000
            0,       3750,       3750,     3750,     2000, 0x00000000
            """));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scansWhereAudioPacketWaitsForTheKeyframeAfterIt")
  @DisplayName(
      "Should count an audio packet in the interval of the keyframe printed after it when that"
          + " keyframe can still present at or before the packet")
  void shouldCountAudioPacketInIntervalOfKeyframePrintedAfterItWhenKeyframeCanPresentBeforeIt(
      String packetScan) {
    var scan = ScriptedScan.builder().streams(MPEG2_AC3_MP2).packetScan(packetScan).build();
    var request = request();

    var report = scan.executor().probe(source, request);

    assertThat(report.streamMeasurementParts())
        .filteredOn(part -> part.getStreamIndex() == 1)
        .containsExactly(
            part(request, 1)
                .addAllKeyframeIntervalBytes(List.of(0, 768))
                .addAllKeyframeIntervalPacketCounts(List.of(0, 1))
                .build());
  }

  // FFmpeg's stream copy drops each stream's packets before its first keyframe unless -copyinkf
  // keeps them, as in a recording that starts inside a group of pictures. The P-frame before the
  // first keyframe falls in the first interval with the keyframe and the B-frame after it.
  @Test
  @DisplayName(
      "Should count the video packets before the first keyframe in the first interval when a"
          + " recording starts inside a group of pictures")
  void shouldCountVideoPacketsBeforeFirstKeyframeInFirstIntervalWhenRecordingStartsInsideAGroup() {
    var scan =
        ScriptedScan.builder()
            .streams(MPEG2_AC3_MP2)
            .packetScan(
                """
                0,          0,       3750,     3750,      300, 0x00000000, F=0x0
                0,       3750,      11250,     3750,     4000, 0x00000000
                0,       7500,       7500,     3750,      500, 0x00000000, F=0x0
                0,      11250,      18750,     3750,     6000, 0x00000000
                1,          0,          0,     2880,      768, 0x00000000
                2,          0,          0,     2351,      417, 0x00000000
                """)
            .build();
    var request = request();

    var report = scan.executor().probe(source, request);

    assertThat(scan.packetScanArguments()).contains("-copyinkf");
    assertThat(report.streamMeasurementParts())
        .filteredOn(part -> part.getStreamIndex() == 0)
        .containsExactly(
            part(request, 0)
                .addAllKeyframeIntervalBytes(List.of(4_800, 6_000))
                .addAllKeyframeIntervalPacketCounts(List.of(3, 1))
                .build());
  }

  // FFmpeg's stream copy passes a video packet whose source gave it no presentation time on with
  // its
  // decode time, and the packet that decodes last here presents last: 22,500 + 3,750 - 0.
  @Test
  @DisplayName(
      "Should end a stream's measured duration at its last packet's decode time when that packet"
          + " has no presentation time")
  void shouldEndMeasuredDurationAtLastPacketsDecodeTimeWhenThatPacketHasNoPresentationTime() {
    var scan =
        ScriptedScan.builder()
            .streams(MPEG2_AC3_MP2)
            .packetScan(
                """
                0,          0,          0,     3750,     4000, 0x00000000
                0,      22500, %s,     3750,      500, 0x00000000, F=0x0
                1,          0,          0,     2880,      768, 0x00000000
                2,          0,          0,     2351,      417, 0x00000000
                """
                    .formatted(NO_TIME))
            .build();

    var report = scan.executor().probe(source, request());

    assertThat(report.result().getMedia().getStreamMeasurements().getStreams(0))
        .isEqualTo(measured(0, 4_500, 2).setMeasuredDuration(26_250).build());
  }

  // The keyframes are frames 0 and 17, at 0 s and 17 / 24 s. An MP3 tick lasts 32 / 1,225 s, so
  // 17 / 24 s falls between ticks 27 and 28: the packet at tick 27 presents before the second
  // keyframe and the packet at tick 28 after it.
  @Test
  @DisplayName(
      "Should place each audio packet by its presentation time in its own time base when the audio"
          + " and video time bases differ")
  void shouldPlaceEachAudioPacketByItsPresentationTimeInItsOwnTimeBaseWhenTimeBasesDiffer() {
    var scan =
        ScriptedScan.builder()
            .streams(MPEG4_MP3_AVI)
            .packetScan(
                """
                0,          0,          0,        1,     1770, 0x00000000
                1,         27,         27,        1,      104, 0x00000000
                1,         28,         28,        1,      105, 0x00000000
                0,         17,         17,        1,     1500, 0x00000000
                """)
            .build();
    var request = request();

    var report = scan.executor().probe(source, request);

    assertThat(report.result().getMedia().getStreamMeasurements().getStreams(1))
        .isEqualTo(
            MeasuredStream.newBuilder()
                .setStreamIndex(1)
                .setMeasuredBytes(209)
                .setPacketCount(2)
                .setTimeBaseNumerator(32)
                .setTimeBaseDenominator(1_225)
                .setMeasuredDuration(2)
                .build());
    assertThat(report.streamMeasurementParts())
        .filteredOn(part -> part.getStreamIndex() == 1)
        .containsExactly(
            part(request, 1)
                .addAllKeyframeIntervalBytes(List.of(104, 105))
                .addAllKeyframeIntervalPacketCounts(List.of(1, 1))
                .build());
  }

  /** One source whose keyframe packet scan must report its keyframes but no measurement. */
  @Builder
  private record Unmeasured(String streams, String packetScan, String firstAacPacket) {}

  private static final String KEYFRAMES_AND_THREE_STREAMS =
      """
      0,          0,          0,     3750,     4000, 0x00000000
      1,          0,          0,     2880,      768, 0x00000000
      2,          0,          0,     2351,      417, 0x00000000
      0,       3750,      18750,     3750,     6000, 0x00000000
      """;

  /** FFmpeg's output streams when the scan leaves out the first audio stream. */
  private static final String KEYFRAMES_AND_THE_SECOND_AUDIO_STREAM =
      """
      0,          0,          0,     3750,     4000, 0x00000000
      1,          0,          0,     2351,      417, 0x00000000
      0,       3750,      18750,     3750,     6000, 0x00000000
      """;

  private static final String AAC_WITH_AUDIO_SPECIFIC_CONFIG =
      audio1(
          "\"codec_name\":\"aac\",\"sample_rate\":\"48000\",\"time_base\":\"1/90000\","
              + "\"extradata\":\"00000000: 1190\"");

  static Stream<Named<Unmeasured>> sourcesThatGetNoMeasurement() {
    var fourGibibytes = new StringBuilder(KEYFRAMES_AND_THREE_STREAMS);
    fourGibibytes.append("0,  7500,  7500, 3750, 4294967290, 0x00000000, F=0x0\n");
    var waiting = new StringBuilder("0, 0, 0, 3750, 4000, 0x00000000\n2, 0, 0, 2351, 417, 0\n");
    for (var packet = 0; packet <= WAITING_AUDIO_PACKETS_THAT_THE_SCAN_HOLDS; packet++) {
      waiting.append(
          "1, %d, %d, 2880, 768, 0x00000000\n".formatted(packet * 2880L, packet * 2880L));
    }

    waiting.append("0, 3750, 18750, 3750, 6000, 0x00000000\n");
    return Stream.of(
        unmeasured(
            "H.264 without a decoder configuration record, as MPEG-TS carries it",
            Unmeasured.builder()
                .streams(
                    mpegTs(
                        video("\"codec_name\":\"h264\",\"time_base\":\"1/90000\""),
                        AC3_AUDIO,
                        MP2_AUDIO))),
        unmeasured(
            "AV1 video, whose temporal delimiters the mp4 muxer drops",
            Unmeasured.builder()
                .streams(
                    mpegTs(
                        video("\"codec_name\":\"av1\",\"time_base\":\"1/90000\""),
                        AC3_AUDIO,
                        MP2_AUDIO))),
        unmeasured(
            "AAC without an AudioSpecificConfig, as ADTS frames carry it",
            Unmeasured.builder()
                .streams(
                    withFirstAudio(
                        "\"codec_name\":\"aac\",\"sample_rate\":\"48000\","
                            + "\"time_base\":\"1/90000\""))),
        unmeasured(
            "AAC whose first packet starts with an ADTS header",
            Unmeasured.builder()
                .streams(mpegTs(MPEG2_VIDEO, AAC_WITH_AUDIO_SPECIFIC_CONFIG, MP2_AUDIO))
                .firstAacPacket(
                    """
                    {"packets":[{"data":"\\n00000000: fff1 5080 2b7f fc21  ......."}]}""")),
        unmeasured(
            "AAC whose first packet read fails",
            Unmeasured.builder()
                .streams(mpegTs(MPEG2_VIDEO, AAC_WITH_AUDIO_SPECIFIC_CONFIG, MP2_AUDIO))
                .firstAacPacket(FAILED_READ)),
        unmeasured(
            "E-AC-3 whose packet holds three of the six blocks that the mp4 muxer joins",
            Unmeasured.builder()
                .streams(
                    withFirstAudio(
                        "\"codec_name\":\"eac3\",\"sample_rate\":\"48000\","
                            + "\"time_base\":\"1/90000\""))
                .packetScan(
                    KEYFRAMES_AND_THREE_STREAMS.replace(
                        "1,          0,          0,     2880,      768",
                        "1,          0,          0,     1440,      384"))),
        unmeasured(
            "an audio stream without a sample rate, which FFmpeg cannot copy",
            Unmeasured.builder()
                .streams(withFirstAudio("\"codec_name\":\"ac3\",\"time_base\":\"1/90000\""))
                .packetScan(KEYFRAMES_AND_THE_SECOND_AUDIO_STREAM)),
        unmeasured(
            "an audio stream without an index, which the scan cannot map",
            Unmeasured.builder()
                .streams(
                    mpegTs(
                        MPEG2_VIDEO,
                        "{\"codec_type\":\"audio\",\"codec_name\":\"ac3\","
                            + "\"sample_rate\":\"48000\",\"time_base\":\"1/90000\"}",
                        MP2_AUDIO))
                .packetScan(KEYFRAMES_AND_THE_SECOND_AUDIO_STREAM)),
        unmeasured(
            "an audio time base whose denominator exceeds the contract's range",
            Unmeasured.builder()
                .streams(
                    withFirstAudio(
                        "\"codec_name\":\"ac3\",\"sample_rate\":\"48000\","
                            + "\"time_base\":\"1/2147483648\""))),
        unmeasured(
            "an audio time base whose numerator exceeds the contract's range",
            Unmeasured.builder()
                .streams(
                    withFirstAudio(
                        "\"codec_name\":\"ac3\",\"sample_rate\":\"48000\","
                            + "\"time_base\":\"2147483648/90000\""))),
        unmeasured(
            "a measured packet with neither a presentation time nor a decode time",
            Unmeasured.builder()
                .packetScan(
                    KEYFRAMES_AND_THREE_STREAMS
                        + "2, %s, %s, 2351, 417, 0x00000000\n".formatted(NO_TIME, NO_TIME))),
        unmeasured(
            "a keyframe interval that holds more bytes than an unsigned 32-bit count carries",
            Unmeasured.builder().packetScan(fourGibibytes.toString())),
        // The video decodes no later than 3,750, so these AC-3 packets wait until the scan ends.
        unmeasured(
            "an audio interval counted at the scan's end beyond an unsigned 32-bit count",
            Unmeasured.builder()
                .packetScan(
                    KEYFRAMES_AND_THREE_STREAMS
                        + "1, 40000, 40000, 2880, 2000000000, 0x00000000\n"
                        + "1, 42880, 42880, 2880, 2000000000, 0x00000000\n"
                        + "1, 45760, 45760, 2880, 2000000000, 0x00000000\n")),
        unmeasured(
            "an audio stream that has no packet",
            Unmeasured.builder()
                .packetScan(
                    """
                    0,          0,          0,     3750,     4000, 0x00000000
                    1,          0,          0,     2880,      768, 0x00000000
                    0,       3750,      18750,     3750,     6000, 0x00000000
                    """)),
        unmeasured(
            "a packet whose end is beyond the long range",
            Unmeasured.builder()
                .packetScan(
                    KEYFRAMES_AND_THREE_STREAMS
                        + "2, 9223372036854775000, 9223372036854775000, 2351, 417, 0\n")),
        unmeasured(
            "a span from a stream's first presentation time to its end beyond the long range",
            Unmeasured.builder()
                .packetScan(
                    KEYFRAMES_AND_THREE_STREAMS
                        + "2, -9000000000000000000, -9000000000000000000, 2351, 417, 0\n"
                        + "2, 9000000000000000000, 9000000000000000000, 2351, 417, 0\n")),
        // FFmpeg's muxing step never gives a packet a decode time after its presentation time, so a
        // keyframe never presents before a decode time that the scan has passed. The AC-3 packet at
        // 3,750 was counted in the first interval once the video decoded at 7,500, and the keyframe
        // that then presents at 3,750 would have held it.
        unmeasured(
            "a keyframe that presents at an audio packet that the scan already counted",
            Unmeasured.builder()
                .packetScan(
                    """
                    0,          0,          0,     3750,     4000, 0x00000000
                    1,       3750,       3750,     2880,      768, 0x00000000
                    2,          0,          0,     2351,      417, 0x00000000
                    0,       7500,       7500,     3750,      500, 0x00000000, F=0x0
                    0,       7501,       3750,     3750,     6000, 0x00000000
                    """)),
        unmeasured(
            "more audio packets waiting for their keyframe interval than the scan holds",
            Unmeasured.builder().packetScan(waiting.toString())));
  }

  private static final String FAILED_READ = "the read fails";

  /** The scan holds at most this many audio packets whose keyframe interval it cannot yet tell. */
  private static final int WAITING_AUDIO_PACKETS_THAT_THE_SCAN_HOLDS = 1 << 18;

  private static String mpegTs(String video, String firstAudio, String secondAudio) {
    return "{\"format\":{\"format_name\":\"mpegts\",\"start_time\":\"0.000000\"},"
        + "\"streams\":[%s,%s,%s]}".formatted(video, firstAudio, secondAudio);
  }

  private static String video(String fields) {
    return "{\"index\":0,\"codec_type\":\"video\",\"has_b_frames\":1,%s}".formatted(fields);
  }

  private static String audio1(String fields) {
    return "{\"index\":1,\"codec_type\":\"audio\",%s}".formatted(fields);
  }

  private static String withFirstAudio(String fields) {
    return mpegTs(MPEG2_VIDEO, audio1(fields), MP2_AUDIO);
  }

  private static Named<Unmeasured> unmeasured(String name, Unmeasured.UnmeasuredBuilder source) {
    return Named.of(name, source.build());
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("sourcesThatGetNoMeasurement")
  @DisplayName(
      "Should report the keyframes without a measurement when the scan cannot measure every"
          + " packet that a copy delivers")
  void shouldReportKeyframesWithoutMeasurementWhenScanCannotMeasureEveryPacketThatCopyDelivers(
      Unmeasured unmeasured) {
    var scan =
        ScriptedScan.builder()
            .streams(unmeasured.streams() == null ? MPEG2_AC3_MP2 : unmeasured.streams())
            .packetScan(
                unmeasured.packetScan() == null
                    ? KEYFRAMES_AND_THREE_STREAMS
                    : unmeasured.packetScan())
            .firstAacPacket(unmeasured.firstAacPacket())
            .build();

    var report = scan.executor().probe(source, request());

    assertThat(report.result().getMedia().getKeyframes().getPositions().getKeyframeCount())
        .isEqualTo(2);
    assertThat(report.result().getMedia().hasStreamMeasurements()).isFalse();
    assertThat(report.streamMeasurementParts()).isEmpty();
  }

  private static MeasuredStream.Builder measured(int streamIndex) {
    return MeasuredStream.newBuilder()
        .setStreamIndex(streamIndex)
        .setTimeBaseNumerator(1)
        .setTimeBaseDenominator(90_000);
  }

  private static MeasuredStream.Builder measured(int streamIndex, long bytes, long packets) {
    return measured(streamIndex).setMeasuredBytes(bytes).setPacketCount(packets);
  }

  private static ProbeStreamMeasurementPart.Builder part(ProbeRequest request, int streamIndex) {
    return ProbeStreamMeasurementPart.newBuilder()
        .setProbeAttemptId(request.getProbeAttemptId())
        .setStreamIndex(streamIndex);
  }

  private static ProbeRequest request() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
        .setScanKeyframePackets(true)
        .build();
  }

  /**
   * Answers the probe's ffprobe runs with the stream descriptions, a read of an AAC stream's first
   * packet with {@code firstAacPacket}, or a failure for {@link #FAILED_READ}, every other read of
   * packets with nothing, and FFmpeg's packet scan with the framecrc lines.
   */
  @Builder
  private record ScriptedScan(
      String streams, String packetScan, String firstAacPacket, List<String> packetScanArguments) {

    ScriptedScan {
      packetScanArguments = new ArrayList<>();
    }

    FfprobeExecutor executor() {
      return new FfprobeExecutor(new ObjectMapper(), this::answer);
    }

    private Process answer(ProbeCommand command) {
      var arguments = command.arguments();
      if (command.tool() == ProbeCommand.ProbeTool.FFMPEG) {
        packetScanArguments.addAll(arguments);
        return printing(packetScan, 0);
      }

      if (arguments.contains("packet=data")) {
        return firstAacPacketRead();
      }

      if (arguments.contains("-read_intervals")) {
        return printing("", 0);
      }

      return printing(streams, 0);
    }

    private Process firstAacPacketRead() {
      if (FAILED_READ.equals(firstAacPacket)) {
        return printing("", 1);
      }

      return printing(firstAacPacket == null ? "{\"packets\":[]}" : firstAacPacket, 0);
    }

    private static Process printing(String output, int exitCode) {
      return ScriptedProcess.builder()
          .output(output.getBytes(StandardCharsets.UTF_8))
          .exitCode(exitCode)
          .build();
    }
  }
}
