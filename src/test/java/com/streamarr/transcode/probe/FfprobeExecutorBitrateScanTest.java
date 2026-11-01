package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.ffprobeDump;
import static com.streamarr.transcode.worker.support.BitrateScanChunks.intervalCounts;
import static com.streamarr.transcode.worker.support.BitrateScanChunks.request;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.BitrateScanChunk;
import build.buf.gen.streamarr.transcode.v1.BitrateScanChunkResult;
import build.buf.gen.streamarr.transcode.v1.BitrateScanChunkStream;
import build.buf.gen.streamarr.transcode.v1.BitrateScanChunkStreams;
import build.buf.gen.streamarr.transcode.v1.BitrateScanCopyChangesPackets;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbePriority;
import com.streamarr.transcode.worker.support.BitrateScanChunks.IntervalCounts;
import com.streamarr.transcode.worker.support.BitrateScanChunks.Keyframe;
import com.streamarr.transcode.worker.support.BitrateScanChunks.Positions;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.Singular;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Bitrate Scan Tests")
class FfprobeExecutorBitrateScanTest {

  private static final Path SOURCE = Path.of("/media/movie.mkv");
  private static final String LISTING_ENTRIES = "packet=stream_index,pts,dts,duration,size,flags";
  private static final String FROM_FILE_START = "";

  // An avcC record of High profile level 4.0 whose NAL units take 4-byte lengths, as its SPS names.
  private static final String KEPT_AVC_RECORD =
      "01 64 00 28 ff e1 00 06 67 64 00 28 ac d9 01 00 04 68 ee 3c 80";
  private static final String AUDIO_SPECIFIC_CONFIG = "11 90";

  // A Matroska source whose container starts at 0.5 s, so every demuxer time is 500 ms later than
  // the same time on the contract's zero-based timeline.
  private static final String MATROSKA_STREAMS =
      """
      {"format":{"format_name":"matroska,webm","start_time":"0.500000"},
       "streams":[
        {"index":0,"codec_type":"video","codec_name":"h264","time_base":"1/1000",
         "disposition":{"attached_pic":0}},
        {"index":1,"codec_type":"audio","codec_name":"%s","time_base":"1/1000",
         "sample_rate":"48000","disposition":{"attached_pic":0}},
        {"index":2,"codec_type":"subtitle","codec_name":"subrip","time_base":"1/1000"}]}
      """;

  // Keyframes at 0, 1, 2 and 3 s on the zero-based timeline, which is 500 ms on the demuxer's.
  private static final Positions POSITIONS =
      Positions.builder()
          .streamIndex(0)
          .timeBaseNumerator(1)
          .timeBaseDenominator(1000)
          .keyframe(new Keyframe(0, 0))
          .keyframe(new Keyframe(1000, 1000))
          .keyframe(new Keyframe(2000, 2000))
          .keyframe(new Keyframe(3000, 3000))
          .measuredStreamIndex(0)
          .measuredStreamIndex(1)
          .build();

  // Every packet of the source in file order: video every 500 ms with a keyframe every second,
  // audio every 250 ms, and a subtitle that no chunk measures. Audio leads the video by 125 ms.
  private static final String WHOLE_FILE =
      """
      stream_index=1|pts=375|dts=375|duration=250|size=10|flags=K__
      stream_index=0|pts=500|dts=500|duration=500|size=1000|flags=K__
      stream_index=1|pts=625|dts=625|duration=250|size=11|flags=K__
      stream_index=2|pts=700|dts=700|duration=900|size=40|flags=K__
      stream_index=1|pts=875|dts=875|duration=250|size=12|flags=K__
      stream_index=0|pts=1000|dts=1000|duration=500|size=200|flags=___
      stream_index=1|pts=1125|dts=1125|duration=250|size=13|flags=K__
      stream_index=1|pts=1375|dts=1375|duration=250|size=14|flags=K__
      stream_index=0|pts=1500|dts=1500|duration=500|size=1001|flags=K__
      stream_index=1|pts=1625|dts=1625|duration=250|size=15|flags=K__
      stream_index=1|pts=1875|dts=1875|duration=250|size=16|flags=K__
      stream_index=0|pts=2000|dts=2000|duration=500|size=201|flags=___
      stream_index=1|pts=2125|dts=2125|duration=250|size=17|flags=K__
      stream_index=1|pts=2375|dts=2375|duration=250|size=18|flags=K__
      stream_index=0|pts=2500|dts=2500|duration=500|size=1002|flags=K__
      stream_index=1|pts=2625|dts=2625|duration=250|size=19|flags=K__
      stream_index=1|pts=2875|dts=2875|duration=250|size=20|flags=K__
      stream_index=0|pts=3000|dts=3000|duration=500|size=202|flags=___
      stream_index=1|pts=3125|dts=3125|duration=250|size=21|flags=K__
      stream_index=1|pts=3375|dts=3375|duration=250|size=22|flags=K__
      stream_index=0|pts=3500|dts=3500|duration=500|size=1003|flags=K__
      stream_index=1|pts=3625|dts=3625|duration=250|size=23|flags=K__
      stream_index=1|pts=3875|dts=3875|duration=250|size=24|flags=K__
      stream_index=0|pts=4000|dts=4000|duration=500|size=203|flags=___
      """;

  @Test
  @DisplayName(
      "Should count each stream's packets into the chunk's intervals when the chunk starts the file")
  void shouldCountEachStreamsPacketsIntoTheChunksIntervalsWhenTheChunkStartsTheFile() {
    var ffprobe = keptAacSource().listing(FROM_FILE_START, WHOLE_FILE).build();
    var request = request(POSITIONS.chunk(0, 2));

    var report = ffprobe.executor().probe(SOURCE, request);

    // The first chunk counts the audio before its first keyframe in its first interval; the
    // second keyframe ends it.
    assertThat(intervalCounts(report.streamMeasurementParts()))
        .containsExactly(
            Map.entry(0, counts(List.of(1000L + 200, 1001L + 201), List.of(2L, 2L))),
            Map.entry(
                1, counts(List.of(10L + 11 + 12 + 13 + 14, 15L + 16 + 17 + 18), List.of(5L, 4L))));
    assertThat(report.streamMeasurementParts())
        .allSatisfy(
            part -> assertThat(part.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId()));
    assertThat(report.result().getBitrateScanChunk())
        .isEqualTo(
            BitrateScanChunkResult.newBuilder()
                .setMeasured(
                    BitrateScanChunkStreams.newBuilder()
                        .addStreams(
                            stream(0)
                                .setMeasuredBytes(2402)
                                .setPacketCount(4)
                                .setFirstPresentationTime(0)
                                .setEndTime(2000))
                        .addStreams(
                            stream(1)
                                .setMeasuredBytes(126)
                                .setPacketCount(9)
                                .setFirstPresentationTime(-125)
                                .setEndTime(2125)))
                .build());
  }

  @Test
  @DisplayName(
      "Should count every packet from the chunk's first keyframe to the file's end when the last"
          + " chunk is listed from the file's start")
  void shouldCountEveryPacketFromTheChunksFirstKeyframeToTheFilesEndWhenTheLastChunkIsListed() {
    var ffprobe = keptAacSource().listing(FROM_FILE_START, WHOLE_FILE).build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(2, 4)));

    assertThat(intervalCounts(report.streamMeasurementParts()))
        .containsExactly(
            Map.entry(0, counts(List.of(1002L + 202, 1003L + 203), List.of(2L, 2L))),
            Map.entry(1, counts(List.of(19L + 20 + 21 + 22, 23L + 24), List.of(4L, 2L))));
  }

  @Test
  @DisplayName(
      "Should count every audio packet once when the muxer places the audio more than the first"
          + " seek's margin ahead of the video")
  void shouldCountEveryAudioPacketOnceWhenTheMuxerPlacesTheAudioMoreThanTheFirstSeeksMarginAhead() {
    // Keyframes 20 s apart. The chunk's first keyframe presents at 40.5 s on the demuxer's
    // timeline, and the source's audio sits 15 s ahead of the video that presents with it.
    var positions =
        POSITIONS.toBuilder()
            .clearKeyframes()
            .keyframe(new Keyframe(0, 0))
            .keyframe(new Keyframe(20_000, 20_000))
            .keyframe(new Keyframe(40_000, 40_000))
            .keyframe(new Keyframe(60_000, 60_000))
            .build();
    var afterTheFirstSeek =
        """
        stream_index=1|pts=45500|dts=45500|duration=250|size=50|flags=K__
        stream_index=0|pts=30500|dts=30500|duration=500|size=999|flags=___
        """;
    var fromTheFileStart =
        """
        stream_index=0|pts=500|dts=500|duration=500|size=1000|flags=K__
        stream_index=1|pts=35500|dts=35500|duration=250|size=30|flags=K__
        stream_index=1|pts=40500|dts=40500|duration=250|size=40|flags=K__
        stream_index=0|pts=20500|dts=20500|duration=500|size=1001|flags=K__
        stream_index=1|pts=45500|dts=45500|duration=250|size=50|flags=K__
        stream_index=1|pts=60500|dts=60500|duration=250|size=60|flags=K__
        stream_index=0|pts=40500|dts=40500|duration=500|size=1002|flags=K__
        stream_index=0|pts=45500|dts=45500|duration=500|size=202|flags=___
        stream_index=0|pts=60500|dts=60500|duration=500|size=1003|flags=K__
        """;
    var ffprobe =
        keptAacSource()
            .listing("30.500000", afterTheFirstSeek)
            .listing(FROM_FILE_START, fromTheFileStart)
            .build();

    var report = ffprobe.executor().probe(SOURCE, request(positions.chunk(2, 3)));

    assertThat(intervalCounts(report.streamMeasurementParts()))
        .containsExactly(
            Map.entry(0, counts(List.of(1204L), List.of(2L))),
            Map.entry(1, counts(List.of(90L), List.of(2L))));
    assertThat(ffprobe.listingStarts()).containsExactly("30.500000", FROM_FILE_START);
  }

  @Test
  @DisplayName("Should count a packet at its decode time when the packet has no presentation time")
  void shouldCountAPacketAtItsDecodeTimeWhenThePacketHasNoPresentationTime() {
    var listing =
        """
        stream_index=0|pts=N/A|dts=500|duration=500|size=1000|flags=K__
        stream_index=1|pts=N/A|dts=1250|duration=250|size=10|flags=K__
        stream_index=0|pts=N/A|dts=1000|duration=500|size=200|flags=___
        stream_index=1|pts=N/A|dts=1625|duration=250|size=11|flags=K__
        stream_index=0|pts=N/A|dts=1500|duration=500|size=1001|flags=K__
        stream_index=0|pts=N/A|dts=2000|duration=500|size=201|flags=___
        stream_index=0|pts=N/A|dts=2500|duration=500|size=1002|flags=K__
        stream_index=1|pts=N/A|dts=2625|duration=250|size=12|flags=K__
        """;
    var ffprobe = keptAacSource().listing(FROM_FILE_START, listing).build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(0, 2)));

    assertThat(intervalCounts(report.streamMeasurementParts()))
        .containsExactly(
            Map.entry(0, counts(List.of(1000L + 200, 1001L + 201), List.of(2L, 2L))),
            Map.entry(1, counts(List.of(10L, 11L), List.of(1L, 1L))));
  }

  @Test
  @DisplayName(
      "Should keep each audio time within the chunk's keyframes on the zero-based timeline when the"
          + " container start is no whole tick of both time bases")
  void shouldKeepEachAudioTimeWithinTheChunksKeyframesOnTheZeroBasedTimelineWhenTheStartIsUneven() {
    // ffprobe prints the start in microseconds: 500.02 video ticks round to 500 and 24,000.96
    // audio ticks to 24,001. The audio packet at 72,000 presents with the video keyframe at 1,500
    // on the demuxer's timeline, but at 47,999 on the zero-based timeline: before the chunk's
    // first keyframe at 1 s, where the contract does not allow the chunk's audio to start.
    var streams =
        MATROSKA_STREAMS
            .formatted("aac")
            .replace("\"0.500000\"", "\"0.500020\"")
            .replace("\"aac\",\"time_base\":\"1/1000\"", "\"aac\",\"time_base\":\"1/48000\"");
    var listing =
        """
        stream_index=0|pts=500|dts=500|duration=500|size=1000|flags=K__
        stream_index=1|pts=60000|dts=60000|duration=12000|size=10|flags=K__
        stream_index=0|pts=1500|dts=1500|duration=500|size=1001|flags=K__
        stream_index=1|pts=72000|dts=72000|duration=12000|size=11|flags=K__
        stream_index=1|pts=84000|dts=84000|duration=12000|size=12|flags=K__
        stream_index=0|pts=2000|dts=2000|duration=500|size=201|flags=___
        stream_index=1|pts=96000|dts=96000|duration=12000|size=13|flags=K__
        stream_index=1|pts=108000|dts=108000|duration=12000|size=14|flags=K__
        stream_index=0|pts=2500|dts=2500|duration=500|size=1002|flags=K__
        stream_index=1|pts=120000|dts=120000|duration=12000|size=15|flags=K__
        stream_index=1|pts=132000|dts=132000|duration=12000|size=16|flags=K__
        """;
    var ffprobe = keptAacSource().streams(streams).listing(FROM_FILE_START, listing).build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(1, 2)));

    // The chunk's audio runs from 48,000 up to 96,000 on the zero-based timeline: 72,001 up to
    // 120,001 on the demuxer's.
    assertThat(report.result().getBitrateScanChunk().getMeasured().getStreams(1))
        .isEqualTo(
            stream(1)
                .setTimeBaseDenominator(48_000)
                .setMeasuredBytes(12 + 13 + 14 + 15)
                .setPacketCount(4)
                .setFirstPresentationTime(84_000 - 24_001)
                .setEndTime(132_000 - 24_001)
                .build());
  }

  @Test
  @DisplayName(
      "Should fail the chunk as invalid media when the packets end before the next chunk's first"
          + " keyframe")
  void shouldFailTheChunkAsInvalidMediaWhenThePacketsEndBeforeTheNextChunksFirstKeyframe() {
    // The listing holds the chunk's keyframes at 0, 1 and 2 s but ends before the end keyframe.
    var truncated = WHOLE_FILE.lines().limit(17).reduce("", (lines, line) -> lines + line + "\n");
    var ffprobe = keptAacSource().listing(FROM_FILE_START, truncated).build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(0, 3)));

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
    assertThat(report.streamMeasurementParts()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should fail the last chunk as invalid media when the packets end before its last keyframe")
  void shouldFailTheLastChunkAsInvalidMediaWhenThePacketsEndBeforeItsLastKeyframe() {
    var truncated = WHOLE_FILE.lines().limit(18).reduce("", (lines, line) -> lines + line + "\n");
    var ffprobe = keptAacSource().listing(FROM_FILE_START, truncated).build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(2, 4)));

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
  }

  @Test
  @DisplayName(
      "Should fail the chunk as invalid media when the source's demuxer reports an error during"
          + " the listing")
  void shouldFailTheChunkAsInvalidMediaWhenTheSourcesDemuxerReportsAnErrorDuringTheListing() {
    var ffprobe =
        keptAacSource()
            .listing(FROM_FILE_START, WHOLE_FILE)
            .listingDiagnostics(
                """
                [h264 @ 0x5581] non-existing PPS 0 referenced
                [matroska,webm @ 0x5582] File ended prematurely at pos. 169438 (0x295de)
                """)
            .build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(2, 4)));

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
  }

  @Test
  @DisplayName("Should measure the chunk when only a decoder logs an error during the listing")
  void shouldMeasureTheChunkWhenOnlyADecoderLogsAnErrorDuringTheListing() {
    var ffprobe =
        keptAacSource()
            .listing(FROM_FILE_START, WHOLE_FILE)
            .listingDiagnostics("[h264 @ 0x5581] non-existing PPS 0 referenced\n")
            .build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(2, 4)));

    assertThat(report.result().getBitrateScanChunk().hasMeasured()).isTrue();
  }

  static Stream<Arguments> copiesThatChangePackets() {
    return Stream.of(
        Arguments.of(
            "H.264 in Annex B form, without a decoder configuration record",
            source().streams(MATROSKA_STREAMS.formatted("aac")).extradata(1, AUDIO_SPECIFIC_CONFIG),
            0),
        Arguments.of(
            "AV1, whose OBUs the mp4 muxer may drop",
            source()
                .streams(MATROSKA_STREAMS.formatted("aac").replace("\"h264\"", "\"av1\""))
                .extradata(1, AUDIO_SPECIFIC_CONFIG),
            0),
        Arguments.of(
            "AAC without an AudioSpecificConfig",
            source().streams(MATROSKA_STREAMS.formatted("aac")).extradata(0, KEPT_AVC_RECORD),
            1),
        Arguments.of(
            "AAC whose first packet is an ADTS frame",
            keptAacSource().firstAudioPacket("ff f1 50 80 02 1f fc"),
            1),
        Arguments.of(
            "AAC whose first packet the read cannot print",
            keptAacSource().firstAudioPacketExitCode(1),
            1),
        Arguments.of(
            "AAC whose first packet read prints more than the read holds",
            keptAacSource().firstAudioPacketOutput("x".repeat((1 << 20) + 1)),
            1),
        Arguments.of(
            "E-AC-3 whose sample rate is 0",
            source()
                .streams(MATROSKA_STREAMS.formatted("eac3").replace("\"48000\"", "\"0\""))
                .extradata(0, KEPT_AVC_RECORD),
            1),
        Arguments.of(
            "E-AC-3 whose packet gives no duration",
            source()
                .streams(MATROSKA_STREAMS.formatted("eac3"))
                .extradata(0, KEPT_AVC_RECORD)
                .listing(
                    FROM_FILE_START,
                    """
                    stream_index=0|pts=500|dts=500|duration=500|size=1000|flags=K__
                    stream_index=1|pts=500|dts=500|duration=N/A|size=100|flags=K__
                    """),
            1),
        Arguments.of(
            "E-AC-3 whose packets hold 256 audio samples",
            source()
                .streams(MATROSKA_STREAMS.formatted("eac3"))
                .extradata(0, KEPT_AVC_RECORD)
                .listing(
                    FROM_FILE_START,
                    """
                    stream_index=0|pts=500|dts=500|duration=500|size=1000|flags=K__
                    stream_index=1|pts=500|dts=500|duration=5|size=100|flags=K__
                    """),
            1));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("copiesThatChangePackets")
  @DisplayName(
      "Should report the stream and no measurement parts when a copy would change a measured"
          + " stream's packets")
  void shouldReportTheStreamAndNoMeasurementPartsWhenACopyWouldChangeAMeasuredStreamsPackets(
      String description, ScriptedSource.ScriptedSourceBuilder source, int streamIndex) {
    var report = source.build().executor().probe(SOURCE, request(POSITIONS.chunk(0, 4)));

    assertThat(report.result().getBitrateScanChunk())
        .isEqualTo(
            BitrateScanChunkResult.newBuilder()
                .setCopyChangesPackets(
                    BitrateScanCopyChangesPackets.newBuilder().setStreamIndex(streamIndex))
                .build());
    assertThat(report.streamMeasurementParts()).isEmpty();
  }

  @Test
  @DisplayName("Should measure E-AC-3 when each of its packets holds 1,536 audio samples")
  void shouldMeasureEac3WhenEachOfItsPacketsHolds1536AudioSamples() {
    // 1,536 samples at 48 kHz last 32 ms.
    var ffprobe =
        source()
            .streams(MATROSKA_STREAMS.formatted("eac3"))
            .extradata(0, KEPT_AVC_RECORD)
            .listing(
                FROM_FILE_START,
                """
                stream_index=0|pts=500|dts=500|duration=500|size=1000|flags=K__
                stream_index=1|pts=500|dts=500|duration=32|size=100|flags=K__
                stream_index=1|pts=532|dts=532|duration=32|size=100|flags=K__
                """)
            .build();

    var report =
        ffprobe
            .executor()
            .probe(
                SOURCE,
                request(POSITIONS.chunk(0, 1).clearEndPresentationTime().clearEndDecodeTime()));

    assertThat(intervalCounts(report.streamMeasurementParts()))
        .containsEntry(1, counts(List.of(200L), List.of(2L)));
  }

  @Test
  @DisplayName(
      "Should report an unsupported version when the chunk names another measurement version")
  void shouldReportAnUnsupportedVersionWhenTheChunkNamesAnotherMeasurementVersion() {
    var ffprobe = keptAacSource().build();

    var report =
        ffprobe
            .executor()
            .probe(
                SOURCE,
                request(
                    POSITIONS
                        .chunk(0, 2)
                        .setMeasurementVersion(FfprobeExecutor.MEASUREMENT_VERSION + 1)));

    assertThat(report.result().getFailure())
        .isEqualTo(ProbeFailure.PROBE_FAILURE_UNSUPPORTED_VERSION);
  }

  static Stream<Arguments> malformedChunks() {
    return Stream.<Arguments>of(
        Arguments.of(
            "no keyframe",
            (UnaryOperator<BitrateScanChunk.Builder>)
                chunk -> chunk.clearPresentationTimeDeltas().clearDecodeTimeDeltas()),
        Arguments.of(
            "fewer decode times than presentation times",
            (UnaryOperator<BitrateScanChunk.Builder>)
                chunk -> chunk.clearDecodeTimeDeltas().addDecodeTimeDeltas(0)),
        Arguments.of(
            "an end presentation time without an end decode time",
            (UnaryOperator<BitrateScanChunk.Builder>) BitrateScanChunk.Builder::clearEndDecodeTime),
        Arguments.of(
            "a time base without a denominator",
            (UnaryOperator<BitrateScanChunk.Builder>) chunk -> chunk.setTimeBaseDenominator(0)),
        Arguments.of(
            "a time base without a numerator",
            (UnaryOperator<BitrateScanChunk.Builder>) chunk -> chunk.setTimeBaseNumerator(0)),
        Arguments.of(
            "measured streams without the chosen stream",
            (UnaryOperator<BitrateScanChunk.Builder>)
                chunk -> chunk.clearMeasuredStreamIndexes().addMeasuredStreamIndexes(1)),
        Arguments.of(
            "a stream measured twice",
            (UnaryOperator<BitrateScanChunk.Builder>) chunk -> chunk.addMeasuredStreamIndexes(1)),
        Arguments.of(
            "more keyframes than a measurement part carries",
            (UnaryOperator<BitrateScanChunk.Builder>)
                chunk -> {
                  for (var i = 0; i < 4_095; i++) {
                    chunk.addPresentationTimeDeltas(1).addDecodeTimeDeltas(1);
                  }

                  return chunk;
                }));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("malformedChunks")
  @DisplayName("Should report an execution failure and list no packet when the chunk is malformed")
  void shouldReportAnExecutionFailureAndListNoPacketWhenTheChunkIsMalformed(
      String description, UnaryOperator<BitrateScanChunk.Builder> malformation) {
    var ffprobe = keptAacSource().build();

    var report =
        ffprobe.executor().probe(SOURCE, request(malformation.apply(POSITIONS.chunk(0, 2))));

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
    assertThat(ffprobe.listingStarts()).isEmpty();
  }

  static Stream<Arguments> streamsTheSourceLacks() {
    return Stream.of(
        Arguments.of("a stream index the source does not hold", List.of(0, 7)),
        Arguments.of("a subtitle stream", List.of(0, 2)),
        Arguments.of("an audio stream as the chosen video stream", List.of(1, 0)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("streamsTheSourceLacks")
  @DisplayName(
      "Should report invalid media when the source does not hold the chunk's streams as it names"
          + " them")
  void shouldReportInvalidMediaWhenTheSourceDoesNotHoldTheChunksStreamsAsItNamesThem(
      String description, List<Integer> measured) {
    var ffprobe = keptAacSource().build();
    var chunk =
        POSITIONS
            .chunk(0, 2)
            .setChosenVideoStreamIndex(measured.getFirst())
            .clearMeasuredStreamIndexes()
            .addAllMeasuredStreamIndexes(measured);

    var report = ffprobe.executor().probe(SOURCE, request(chunk));

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
  }

  static Stream<Arguments> keyframeTimesBetweenTicks() {
    return Stream.<Arguments>of(
        Arguments.of(
            "a keyframe",
            (UnaryOperator<BitrateScanChunk.Builder>)
                chunk -> chunk.setPresentationTimeDeltas(1, 3001).setDecodeTimeDeltas(1, 3001)),
        Arguments.of(
            "the end keyframe",
            (UnaryOperator<BitrateScanChunk.Builder>)
                chunk -> chunk.setEndPresentationTime(6001).setEndDecodeTime(6001)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("keyframeTimesBetweenTicks")
  @DisplayName(
      "Should report invalid media when a keyframe time is no whole tick of the chosen stream's"
          + " time base")
  void shouldReportInvalidMediaWhenAKeyframeTimeIsNoWholeTickOfTheChosenStreamsTimeBase(
      String description, UnaryOperator<BitrateScanChunk.Builder> change) {
    var ffprobe = keptAacSource().listing(FROM_FILE_START, WHOLE_FILE).build();
    // In thirds of a millisecond, the keyframes at 0 and 1 s and the end at 2 s.
    var chunk =
        POSITIONS
            .chunk(0, 2)
            .setTimeBaseDenominator(3000)
            .setPresentationTimeDeltas(1, 3000)
            .setDecodeTimeDeltas(1, 3000)
            .setEndPresentationTime(6000)
            .setEndDecodeTime(6000);

    var report = ffprobe.executor().probe(SOURCE, request(change.apply(chunk)));

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
    assertThat(ffprobe.listingStarts()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should report a result too large when one interval holds more bytes than a measurement part"
          + " entry carries")
  void shouldReportAResultTooLargeWhenOneIntervalHoldsMoreBytesThanAMeasurementPartEntryCarries() {
    var listing =
        """
        stream_index=0|pts=500|dts=500|duration=500|size=4294967296|flags=K__
        stream_index=1|pts=500|dts=500|duration=250|size=10|flags=K__
        """;
    var ffprobe = keptAacSource().listing(FROM_FILE_START, listing).build();

    var report =
        ffprobe
            .executor()
            .probe(
                SOURCE,
                request(POSITIONS.chunk(0, 1).clearEndPresentationTime().clearEndDecodeTime()));

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("packetsWithoutTime")
  @DisplayName("Should report invalid media when a measured packet gives no time at all")
  void shouldReportInvalidMediaWhenAMeasuredPacketGivesNoTimeAtAll(
      String description, String listing) {
    var ffprobe = keptAacSource().listing(FROM_FILE_START, listing).build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(0, 4)));

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_INVALID_MEDIA);
  }

  static Stream<Arguments> packetsWithoutTime() {
    return Stream.of(
        Arguments.of(
            "an audio packet",
            """
            stream_index=0|pts=500|dts=500|duration=500|size=1000|flags=K__
            stream_index=1|pts=N/A|dts=N/A|duration=250|size=10|flags=K__
            """),
        Arguments.of(
            "a video packet",
            """
            stream_index=0|pts=500|dts=500|duration=500|size=1000|flags=K__
            stream_index=0|pts=N/A|dts=N/A|duration=500|size=200|flags=___
            """));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unreadableListingLines")
  @DisplayName(
      "Should report an execution failure when the listing prints an unreadable packet line")
  void shouldReportAnExecutionFailureWhenTheListingPrintsAnUnreadablePacketLine(
      String description, String line) {
    var ffprobe = keptAacSource().listing(FROM_FILE_START, line + "\n").build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(0, 4)));

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
  }

  static Stream<Arguments> unreadableListingLines() {
    return Stream.of(
        Arguments.of("a line without fields", "packet"),
        Arguments.of(
            "a line without a size", "stream_index=0|pts=500|dts=500|duration=500|flags=K__"),
        Arguments.of(
            "a line whose size is no number",
            "stream_index=0|pts=500|dts=500|duration=500|size=large|flags=K__"));
  }

  @Test
  @DisplayName(
      "Should report the stream's counts as zero and its times as absent when the chunk holds none"
          + " of its packets")
  void shouldReportTheStreamsCountsAsZeroAndItsTimesAsAbsentWhenTheChunkHoldsNoneOfItsPackets() {
    var videoOnly =
        """
        stream_index=0|pts=500|dts=500|duration=500|size=1000|flags=K__
        stream_index=0|pts=1500|dts=1500|duration=500|size=1001|flags=K__
        stream_index=0|pts=2500|dts=2500|duration=500|size=1002|flags=K__
        """;
    var ffprobe = keptAacSource().listing(FROM_FILE_START, videoOnly).build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(0, 2)));

    assertThat(report.result().getBitrateScanChunk().getMeasured().getStreams(1))
        .isEqualTo(stream(1).build());
    assertThat(intervalCounts(report.streamMeasurementParts()))
        .containsEntry(1, counts(List.of(0L, 0L), List.of(0L, 0L)));
  }

  @Test
  @DisplayName(
      "Should list the chunk again from the file's start when the seek lands after the chunk's"
          + " first keyframe")
  void shouldListTheChunkAgainFromTheFilesStartWhenTheSeekLandsAfterTheChunksFirstKeyframe() {
    var positions =
        POSITIONS.toBuilder()
            .clearKeyframes()
            .keyframe(new Keyframe(0, 0))
            .keyframe(new Keyframe(20_000, 20_000))
            .keyframe(new Keyframe(40_000, 40_000))
            .build();
    var afterTheSeek =
        """
        stream_index=0|pts=40500|dts=40500|duration=500|size=1002|flags=K__
        """;
    var fromTheFileStart =
        """
        stream_index=0|pts=500|dts=500|duration=500|size=1000|flags=K__
        stream_index=0|pts=20500|dts=20500|duration=500|size=1001|flags=K__
        stream_index=1|pts=20500|dts=20500|duration=250|size=10|flags=K__
        stream_index=0|pts=40500|dts=40500|duration=500|size=1002|flags=K__
        stream_index=1|pts=40500|dts=40500|duration=250|size=11|flags=K__
        """;
    var ffprobe =
        keptAacSource()
            .listing("10.500000", afterTheSeek)
            .listing(FROM_FILE_START, fromTheFileStart)
            .build();

    var report = ffprobe.executor().probe(SOURCE, request(positions.chunk(1, 2)));

    assertThat(intervalCounts(report.streamMeasurementParts()))
        .containsExactly(
            Map.entry(0, counts(List.of(1001L), List.of(1L))),
            Map.entry(1, counts(List.of(10L), List.of(1L))));
    assertThat(ffprobe.listingStarts()).containsExactly("10.500000", FROM_FILE_START);
  }

  @Test
  @DisplayName(
      "Should read on past the end keyframe until each audio stream passes the chunk's end when the"
          + " muxer places the audio behind the video")
  void shouldReadOnPastTheEndKeyframeUntilEachAudioStreamPassesTheChunksEndWhenAudioLags() {
    var listing =
        """
        stream_index=0|pts=500|dts=500|duration=500|size=1000|flags=K__
        stream_index=0|pts=1500|dts=1500|duration=500|size=1001|flags=K__
        stream_index=0|pts=2000|dts=2000|duration=500|size=201|flags=___
        stream_index=1|pts=500|dts=500|duration=250|size=10|flags=K__
        stream_index=0|pts=2500|dts=2500|duration=500|size=1002|flags=K__
        stream_index=1|pts=1500|dts=1500|duration=250|size=11|flags=K__
        stream_index=0|pts=3000|dts=3000|duration=500|size=202|flags=___
        stream_index=1|pts=2375|dts=2375|duration=250|size=12|flags=K__
        stream_index=1|pts=2500|dts=2500|duration=250|size=13|flags=K__
        stream_index=1|pts=2750|dts=2750|duration=250|size=14|flags=K__
        """;
    var ffprobe = keptAacSource().listing(FROM_FILE_START, listing).build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(0, 2)));

    assertThat(intervalCounts(report.streamMeasurementParts()))
        .containsExactly(
            Map.entry(0, counts(List.of(1000L, 1001L + 201), List.of(1L, 2L))),
            Map.entry(1, counts(List.of(10L, 11L + 12), List.of(1L, 2L))));
  }

  @Test
  @DisplayName(
      "Should measure a stream without reading its first packet when its codec reaches the"
          + " segment as its packets are")
  void shouldMeasureAStreamWithoutReadingItsFirstPacketWhenItsCodecReachesTheSegmentAsItIs() {
    var ffprobe =
        source()
            .streams(MATROSKA_STREAMS.formatted("opus"))
            .extradata(0, KEPT_AVC_RECORD)
            .listing(FROM_FILE_START, WHOLE_FILE)
            .build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(0, 2)));

    assertThat(report.result().getBitrateScanChunk().hasMeasured()).isTrue();
    assertThat(ffprobe.commands())
        .noneMatch(command -> command.arguments().contains("packet=data"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("aacFirstPacketsWithoutAdtsFrame")
  @DisplayName("Should measure AAC when its first packet is no ADTS frame")
  void shouldMeasureAacWhenItsFirstPacketIsNoAdtsFrame(String description, String firstPacket) {
    var ffprobe =
        keptAacSource().firstAudioPacket(firstPacket).listing(FROM_FILE_START, WHOLE_FILE).build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(0, 2)));

    assertThat(report.result().getBitrateScanChunk().hasMeasured()).isTrue();
  }

  static Stream<Arguments> aacFirstPacketsWithoutAdtsFrame() {
    return Stream.of(
        Arguments.of("no packet in the stream", null),
        Arguments.of("a packet of two bytes", "ff f1"),
        Arguments.of("a first byte of ones without the sync word's next four bits", "ff 01 50"),
        Arguments.of("a sync word's second byte without its first", "21 f1 50"));
  }

  @Test
  @DisplayName(
      "Should list the chunk once from a seek before its first keyframe when the seek reaches back"
          + " to the chunk's start")
  void shouldListTheChunkOnceFromASeekBeforeItsFirstKeyframeWhenTheSeekReachesBack() {
    var positions =
        POSITIONS.toBuilder()
            .clearKeyframes()
            .keyframe(new Keyframe(0, 0))
            .keyframe(new Keyframe(20_000, 20_000))
            .keyframe(new Keyframe(40_000, 40_000))
            .build();
    // The seek lands at a keyframe before the chunk. A packet without times and the packets
    // before the chunk's first keyframe count in no interval.
    var afterTheSeek =
        """
        stream_index=0|pts=10500|dts=10500|duration=500|size=900|flags=K__
        stream_index=1|pts=10500|dts=10500|duration=250|size=9|flags=K__
        stream_index=0|pts=N/A|dts=N/A|duration=500|size=901|flags=___
        stream_index=0|pts=11000|dts=11000|duration=500|size=902|flags=___
        stream_index=0|pts=20500|dts=20500|duration=500|size=1001|flags=K__
        stream_index=1|pts=20500|dts=20500|duration=250|size=10|flags=K__
        stream_index=0|pts=40500|dts=40500|duration=500|size=1002|flags=K__
        stream_index=1|pts=40500|dts=40500|duration=250|size=11|flags=K__
        """;
    var ffprobe = keptAacSource().listing("10.500000", afterTheSeek).build();

    var report = ffprobe.executor().probe(SOURCE, request(positions.chunk(1, 2)));

    assertThat(intervalCounts(report.streamMeasurementParts()))
        .containsExactly(
            Map.entry(0, counts(List.of(1001L), List.of(1L))),
            Map.entry(1, counts(List.of(10L), List.of(1L))));
    assertThat(ffprobe.listingStarts()).containsExactly("10.500000");
  }

  @Test
  @DisplayName(
      "Should report an execution failure when a measured stream's time base is unreadable")
  void shouldReportAnExecutionFailureWhenAMeasuredStreamsTimeBaseIsUnreadable() {
    var ffprobe =
        keptAacSource()
            .streams(
                MATROSKA_STREAMS
                    .formatted("aac")
                    .replace("\"aac\",\"time_base\":\"1/1000\"", "\"aac\",\"time_base\":\"none\""))
            .build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(0, 2)));

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
  }

  static Stream<Arguments> unreadableStreams() {
    return Stream.of(
        Arguments.of(
            "a missing source",
            """
            {"error":{"code":-2,"string":"No such file or directory"}}
            """,
            1,
            ProbeFailure.PROBE_FAILURE_SOURCE_UNAVAILABLE),
        Arguments.of(
            "output that is no JSON", "streams", 0, ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unreadableStreams")
  @DisplayName(
      "Should report the failure and list no packet when ffprobe cannot describe the streams")
  void shouldReportTheFailureAndListNoPacketWhenFfprobeCannotDescribeTheStreams(
      String description, String output, int exitCode, ProbeFailure failure) {
    var ffprobe = keptAacSource().streams(output).streamsExitCode(exitCode).build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(0, 2)));

    assertThat(report.result().getFailure()).isEqualTo(failure);
    assertThat(ffprobe.listingStarts()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should report an execution failure when the listing exits with an error before the chunk's"
          + " end")
  void shouldReportAnExecutionFailureWhenTheListingExitsWithAnErrorBeforeTheChunksEnd() {
    var ffprobe = keptAacSource().listing(FROM_FILE_START, WHOLE_FILE).listingExitCode(1).build();

    var report = ffprobe.executor().probe(SOURCE, request(POSITIONS.chunk(2, 4)));

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
  }

  @Test
  @DisplayName(
      "Should run every process of a chunk at the lowest CPU priority when its request names"
          + " playback priority")
  void shouldRunEveryProcessOfAChunkAtTheLowestCpuPriorityWhenItsRequestNamesPlaybackPriority() {
    var ffprobe = keptAacSource().listing(FROM_FILE_START, WHOLE_FILE).build();
    var chunk =
        request(POSITIONS.chunk(0, 2)).toBuilder()
            .setPriority(ProbePriority.PROBE_PRIORITY_PLAYBACK)
            .build();

    ffprobe.executor().probe(SOURCE, chunk);

    assertThat(ffprobe.commands())
        .isNotEmpty()
        .extracting(ProbeCommand::cpuPriority)
        .containsOnly(ProbeCommand.CpuPriority.LOWEST);
  }

  private static ScriptedSource.ScriptedSourceBuilder source() {
    return ScriptedSource.builder();
  }

  // H.264 and AAC that a stream copy keeps, whose first AAC packet holds raw AAC.
  private static ScriptedSource.ScriptedSourceBuilder keptAacSource() {
    return source()
        .streams(MATROSKA_STREAMS.formatted("aac"))
        .extradata(0, KEPT_AVC_RECORD)
        .extradata(1, AUDIO_SPECIFIC_CONFIG)
        .firstAudioPacket("21 1b 94 a5");
  }

  private static BitrateScanChunkStream.Builder stream(int index) {
    return BitrateScanChunkStream.newBuilder()
        .setStreamIndex(index)
        .setTimeBaseNumerator(1)
        .setTimeBaseDenominator(1000);
  }

  private static IntervalCounts counts(List<Long> bytes, List<Long> packets) {
    return new IntervalCounts(bytes, packets);
  }

  /**
   * Answers the chunk's ffprobe runs: the streams and format, each stream's extradata, the first
   * AAC packet, and a packet listing for each read interval start, {@code ""} for one from the
   * file's start. The listing's diagnostics and exit code are the same for every start.
   */
  @Builder
  record ScriptedSource(
      String streams,
      int streamsExitCode,
      @Singular("extradata") Map<Integer, String> extradata,
      String firstAudioPacket,
      String firstAudioPacketOutput,
      int firstAudioPacketExitCode,
      @Singular Map<String, String> listings,
      String listingDiagnostics,
      int listingExitCode,
      List<ProbeCommand> commands,
      List<String> listingStarts) {

    ScriptedSource {
      commands = new ArrayList<>();
      listingStarts = new ArrayList<>();
    }

    FfprobeExecutor executor() {
      return new FfprobeExecutor(new ObjectMapper(), this::answer);
    }

    private Process answer(ProbeCommand command) {
      commands.add(command);
      var arguments = command.arguments();
      if (arguments.contains(LISTING_ENTRIES)) {
        var interval = arguments.indexOf("-read_intervals");
        var start = interval < 0 ? FROM_FILE_START : arguments.get(interval + 1).replace("%", "");
        listingStarts.add(start);
        assertThat(listings).as("a listing from %s", start).containsKey(start);
        return new ScriptedProcess(
            listings.get(start),
            listingDiagnostics == null ? "" : listingDiagnostics,
            listingExitCode);
      }

      if (arguments.contains("packet=data")) {
        return new ScriptedProcess(
            firstAudioPacketOutput != null ? firstAudioPacketOutput : firstAudioPacketRead(),
            "",
            firstAudioPacketExitCode);
      }

      if (arguments.contains("stream=index,extradata")) {
        var described = new StringBuilder();
        extradata.forEach(
            (index, hex) ->
                described
                    .append(described.isEmpty() ? "" : ",")
                    .append("{\"index\":%d,\"extradata\":\"%s\"}".formatted(index, dump(hex))));
        return new ScriptedProcess("{\"streams\":[" + described + "]}", "", 0);
      }

      return new ScriptedProcess(streams, "", streamsExitCode);
    }

    private String firstAudioPacketRead() {
      return firstAudioPacket == null
          ? "{\"packets\":[]}"
          : "{\"packets\":[{\"data\":\"%s\"}]}".formatted(dump(firstAudioPacket));
    }

    private static String dump(String hex) {
      return ffprobeDump(HexFormat.ofDelimiter(" ").parseHex(hex));
    }
  }

  /** A process that has already exited, with its standard output and error. */
  private static final class ScriptedProcess extends Process {
    private final InputStream stdout;
    private final InputStream stderr;
    private final int exitCode;

    private ScriptedProcess(String stdout, String stderr, int exitCode) {
      this.stdout = new ByteArrayInputStream(stdout.getBytes(StandardCharsets.UTF_8));
      this.stderr = new ByteArrayInputStream(stderr.getBytes(StandardCharsets.UTF_8));
      this.exitCode = exitCode;
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
      return false;
    }

    @Override
    public void destroy() {
      // This process has already exited.
    }
  }
}
