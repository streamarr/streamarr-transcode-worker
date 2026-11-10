package com.streamarr.transcode.worker;

import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.failure;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.processBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.sourceBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailable;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailableReason;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.StreamMeasurementIntervals;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.probe.ProbeCommand;
import com.streamarr.transcode.probe.ProbeReports;
import com.streamarr.transcode.worker.support.BitrateScanChunks;
import com.streamarr.transcode.worker.support.BitrateScanChunks.Keyframe;
import com.streamarr.transcode.worker.support.BitrateScanChunks.Positions;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Transcode Worker Keyframe Limit Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerKeyframeLimitTest {

  private static final ProbeKeyframes UNAVAILABLE_FOR_TOO_MANY_KEYFRAMES =
      ProbeKeyframes.newBuilder()
          .setUnavailable(
              KeyframesUnavailable.newBuilder()
                  .setReason(
                      KeyframesUnavailableReason.KEYFRAMES_UNAVAILABLE_REASON_TOO_MANY_KEYFRAMES))
          .build();

  // A Matroska source with one MPEG-2 video stream, whose packets a copy keeps, and its packet
  // listing.
  private static final Function<ProbeCommand, String> CHUNK_OF_TWO_KEYFRAMES =
      command ->
          command.arguments().contains("packet=stream_index,pts,dts,duration,size,flags")
              ? """
                stream_index=0|pts=0|dts=0|duration=500|size=1000|flags=K__
                stream_index=0|pts=500|dts=500|duration=500|size=200|flags=___
                stream_index=0|pts=1000|dts=1000|duration=500|size=1001|flags=K__
                stream_index=0|pts=1500|dts=1500|duration=500|size=201|flags=___
                """
              : """
                {"format":{"format_name":"matroska,webm","start_time":"0.000000"},
                 "streams":[{"index":0,"codec_type":"video","codec_name":"mpeg2video",
                             "time_base":"1/1000","disposition":{"attached_pic":0}}]}
                """;

  @TempDir Path tempDir;

  @Test
  @DisplayName(
      "Should report the keyframe positions and the measurement when the container index holds"
          + " exactly as many keyframes and measured keyframe intervals as the server's limits")
  void shouldReportThePositionsAndTheMeasurementWhenTheIndexHoldsExactlyTheServersLimits()
      throws Exception {
    var report =
        reportOfTheIndexedSource(
            WorkerSessionAccepted.newBuilder()
                .setKeyframeLimit(4)
                .setMeasuredKeyframeIntervalLimit(4));

    assertThat(report.getResult().getMedia().getKeyframes().getPositions().getKeyframeCount())
        .isEqualTo(4);
    assertThat(report.getKeyframePresentationTimeDeltasList()).hasSize(4);
    assertThat(report.getKeyframeDecodeTimeDeltasList()).hasSize(4);
    assertThat(report.getResult().getMedia().getStreamMeasurements().getStreamsCount())
        .isEqualTo(1);
    assertThat(report.getStreamMeasurementIntervalsList())
        .singleElement()
        .extracting(StreamMeasurementIntervals::getKeyframeIntervalBytesCount)
        .isEqualTo(4);
  }

  @Test
  @DisplayName(
      "Should report the keyframes as unavailable for too many keyframes, with the container and"
          + " the streams and without positions or measurement, when the container index holds"
          + " more keyframes than the keyframe limit")
  void shouldReportTheKeyframesAsUnavailableWhenTheIndexHoldsMoreKeyframesThanTheKeyframeLimit()
      throws Exception {
    var report = reportOfTheIndexedSource(WorkerSessionAccepted.newBuilder().setKeyframeLimit(3));

    var media = report.getResult().getMedia();
    assertThat(media.getKeyframes()).isEqualTo(UNAVAILABLE_FOR_TOO_MANY_KEYFRAMES);
    assertThat(media.getContainer().getFormat()).isEqualTo("mov,mp4,m4a,3gp,3g2,mj2");
    assertThat(media.getStreamsList())
        .singleElement()
        .satisfies(stream -> assertThat(stream.getCodecType()).isEqualTo("video"));
    assertThat(media.hasStreamMeasurements()).isFalse();
    assertThat(report).isEqualTo(ProbeReports.resultOnly(report.getResult()));
  }

  @Test
  @DisplayName(
      "Should leave the measurement out and keep the keyframe positions when the container index"
          + " measures more keyframe intervals than the interval limit")
  void shouldLeaveTheMeasurementOutAndKeepThePositionsWhenTheIndexMeasuresMoreThanTheIntervalLimit()
      throws Exception {
    var report =
        reportOfTheIndexedSource(
            WorkerSessionAccepted.newBuilder().setMeasuredKeyframeIntervalLimit(3));

    assertThat(report.getResult().getMedia().getKeyframes().getPositions().getKeyframeCount())
        .isEqualTo(4);
    assertThat(report.getKeyframePresentationTimeDeltasList())
        .containsExactly(0L, 8_704L, 16_896L, 10_240L);
    assertThat(report.getKeyframeDecodeTimeDeltasList()).hasSize(4);
    assertThat(report.getResult().getMedia().hasStreamMeasurements()).isFalse();
    assertThat(report.getStreamMeasurementIntervalsList()).isEmpty();
  }

  @Test
  @DisplayName(
      "Should report the scanned keyframe positions when a keyframe packet scan finds exactly as"
          + " many keyframes as the keyframe limit")
  void shouldReportTheScannedPositionsWhenAKeyframePacketScanFindsExactlyTheKeyframeLimit()
      throws Exception {
    var report = reportOfTheScannedSource(WorkerSessionAccepted.newBuilder().setKeyframeLimit(2));

    assertThat(report.getResult().getMedia().getKeyframes().getPositions().getKeyframeCount())
        .isEqualTo(2);
    assertThat(report.getKeyframePresentationTimeDeltasList()).containsExactly(0L, 180_000L);
  }

  @Test
  @DisplayName(
      "Should report the keyframes as unavailable for too many keyframes when a keyframe packet"
          + " scan finds more keyframes than the keyframe limit")
  void shouldReportTheKeyframesAsUnavailableWhenAKeyframePacketScanFindsMoreThanTheKeyframeLimit()
      throws Exception {
    var report = reportOfTheScannedSource(WorkerSessionAccepted.newBuilder().setKeyframeLimit(1));

    var media = report.getResult().getMedia();
    assertThat(media.getKeyframes()).isEqualTo(UNAVAILABLE_FOR_TOO_MANY_KEYFRAMES);
    assertThat(media.getContainer().getFormat()).isEqualTo("mpegts");
    assertThat(media.getStreamsList()).hasSize(1);
    assertThat(report).isEqualTo(ProbeReports.resultOnly(report.getResult()));
  }

  @Test
  @DisplayName(
      "Should report a bitrate scan chunk's measurement when the chunk measures exactly as many"
          + " keyframe intervals as the interval limit")
  void shouldReportABitrateScanChunksMeasurementWhenTheChunkMeasuresExactlyTheIntervalLimit()
      throws Exception {
    var report =
        reportOfTheChunk(
            chunkOfTwoKeyframes(),
            WorkerSessionAccepted.newBuilder().setMeasuredKeyframeIntervalLimit(2));

    assertThat(report.getResult().getBitrateScanChunk().hasMeasured()).isTrue();
    assertThat(report.getStreamMeasurementIntervalsList())
        .singleElement()
        .satisfies(
            intervals ->
                assertThat(intervals.getKeyframeIntervalBytesList()).containsExactly(1200, 1202));
  }

  @Test
  @DisplayName(
      "Should fail a bitrate scan chunk as too large when the chunk measures more keyframe"
          + " intervals than the interval limit")
  void shouldFailABitrateScanChunkAsTooLargeWhenTheChunkMeasuresMoreThanTheIntervalLimit()
      throws Exception {
    var chunk = chunkOfTwoKeyframes();

    var report =
        reportOfTheChunk(
            chunk, WorkerSessionAccepted.newBuilder().setMeasuredKeyframeIntervalLimit(1));

    assertThat(report)
        .isEqualTo(
            ProbeReports.resultOnly(failure(chunk, ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE)));
  }

  // An MP4 whose container index holds 4 keyframes of its one stream, which the worker measures
  // from the sample tables: 4 measured keyframe intervals.
  private ProbeAttemptReport reportOfTheIndexedSource(WorkerSessionAccepted.Builder limits)
      throws Exception {
    var fixture = getClass().getResource("/keyframes/mp4-bframes-editlist.mp4");
    assertThat(fixture).isNotNull();
    Files.copy(Path.of(fixture.toURI()), tempDir.resolve("movie.mp4"));
    return Probe.builder()
        .limits(limits)
        .ffprobe(
            command ->
                command.arguments().contains("-read_intervals")
                    ? """
                      {"packets":[{"pts":35840,"dts":34816,"flags":"K__"}]}
                      """
                    : """
                      {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2",
                                 "start_time":"0.000000"},
                       "streams":[{"index":0,"codec_type":"video","id":"0x1"}]}
                      """)
        .request(requestBuilder().setSource(sourceBuilder().setRelativeKey("movie.mp4")).build())
        .build()
        .report(tempDir);
  }

  // An MPEG-TS source whose keyframe packet scan finds 2 keyframes.
  private ProbeAttemptReport reportOfTheScannedSource(WorkerSessionAccepted.Builder limits)
      throws Exception {
    Files.writeString(tempDir.resolve("movie.ts"), "media");
    return Probe.builder()
        .limits(limits)
        .ffprobe(
            command ->
                command.tool() == ProbeCommand.ProbeTool.FFMPEG
                    ? """
                      0, 0, 0, 3750, 100, 0x00000000
                      0, 3750, 3750, 3750, 100, 0x00000000, F=0x0
                      0, 180000, 180000, 3750, 100, 0x00000000
                      """
                    : """
                      {"format":{"format_name":"mpegts","start_time":"0.000000"},
                       "streams":[{"index":0,"codec_type":"video",
                                   "time_base":"1/90000","has_b_frames":0}]}
                      """)
        .request(
            requestBuilder()
                .setScanKeyframePackets(true)
                .setSource(sourceBuilder().setRelativeKey("movie.ts"))
                .build())
        .build()
        .report(tempDir);
  }

  private ProbeAttemptReport reportOfTheChunk(
      ProbeRequest chunk, WorkerSessionAccepted.Builder limits) throws Exception {
    Files.writeString(tempDir.resolve("movie.mkv"), "media");
    return Probe.builder()
        .limits(limits)
        .ffprobe(CHUNK_OF_TWO_KEYFRAMES)
        .request(chunk)
        .build()
        .report(tempDir);
  }

  // The file's only chunk: its video stream's keyframes at 0 and 1 s, so the chunk measures 2
  // keyframe intervals.
  private static ProbeRequest chunkOfTwoKeyframes() {
    return BitrateScanChunks.request(
        Positions.builder()
            .streamIndex(0)
            .timeBaseNumerator(1)
            .timeBaseDenominator(1000)
            .keyframe(new Keyframe(0, 0))
            .keyframe(new Keyframe(1000, 1000))
            .measuredStreamIndex(0)
            .build()
            .chunk(0, 2));
  }

  /**
   * One probe attempt on a worker whose server states these limits when it accepts the worker
   * session, with what ffprobe and FFmpeg print for each command.
   */
  @Builder
  private record Probe(
      @NonNull WorkerSessionAccepted.Builder limits,
      @NonNull Function<ProbeCommand, String> ffprobe,
      @NonNull ProbeRequest request) {

    /** The one probe report that the worker sends for the attempt. */
    ProbeAttemptReport report(Path sourceRoot) throws Exception {
      var runtime = ScriptedWorkerRuntime.accepting(limits);
      var executor =
          new FfprobeExecutor(
              new ObjectMapper(),
              command -> processBuilder().stdout(ffprobe.apply(command)).build());

      try (var worker = workerBuilder(sourceRoot).runtime(runtime).ffprobe(executor).build()) {
        worker.start("localhost", 1);
        start(runtime.connection(), request);
        runtime.probes().drain();

        var reports = runtime.connection().reports();
        assertThat(reports).hasSize(1);
        return reports.getFirst();
      }
    }
  }
}
