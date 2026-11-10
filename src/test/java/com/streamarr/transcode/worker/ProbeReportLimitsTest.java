package com.streamarr.transcode.worker;

import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.failure;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.BitrateScanChunk;
import build.buf.gen.streamarr.transcode.v1.BitrateScanChunkResult;
import build.buf.gen.streamarr.transcode.v1.BitrateScanChunkStreams;
import build.buf.gen.streamarr.transcode.v1.KeyframePositions;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailable;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailableReason;
import build.buf.gen.streamarr.transcode.v1.MeasuredStream;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import build.buf.gen.streamarr.transcode.v1.ProbeContainerInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import build.buf.gen.streamarr.transcode.v1.ProbeMediaInfo;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.StreamMeasurementIntervals;
import build.buf.gen.streamarr.transcode.v1.StreamMeasurements;
import build.buf.gen.streamarr.transcode.v1.WorkerSessionAccepted;
import com.streamarr.transcode.probe.ProbeReports;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Probe Report Limits Tests")
class ProbeReportLimitsTest {

  private static final ProbeRequest PROBE = requestBuilder().build();
  private static final ProbeRequest KEYFRAME_PACKET_SCAN =
      requestBuilder().setScanKeyframePackets(true).build();
  private static final ProbeRequest BITRATE_SCAN_CHUNK =
      requestBuilder().setBitrateScanChunk(BitrateScanChunk.getDefaultInstance()).build();

  @Test
  @DisplayName("Should send the probe's own report when the server stated no limit")
  void shouldSendTheProbesOwnReportWhenTheServerStatedNoLimit() {
    var report = reportOf(PROBE, 5, List.of(5, 5));

    assertThat(ProbeReportLimits.NONE.apply(PROBE, report)).isSameAs(report);
    assertThat(ProbeReportLimits.of(WorkerSessionAccepted.getDefaultInstance()))
        .isEqualTo(ProbeReportLimits.NONE);
  }

  @Test
  @DisplayName("Should read the three limits on a probe report from the accepted worker session")
  void shouldReadTheThreeLimitsOnAProbeReportFromTheAcceptedWorkerSession() {
    var accepted =
        WorkerSessionAccepted.newBuilder()
            .setKeyframeLimit(150_000)
            .setMeasuredKeyframeIntervalLimit(-1)
            .setProbeReportLengthLimitBytes(16L << 20)
            .build();

    assertThat(ProbeReportLimits.of(accepted))
        .isEqualTo(
            ProbeReportLimits.builder()
                .keyframes(150_000)
                // The contract's 32-bit limits are unsigned.
                .measuredKeyframeIntervals(4_294_967_295L)
                .reportLengthBytes(16L << 20)
                .build());
  }

  @ParameterizedTest(name = "scanning keyframe packets: {0}")
  @ValueSource(booleans = {false, true})
  @DisplayName(
      "Should report the keyframe positions when the chosen video stream holds exactly as many"
          + " keyframes as the keyframe limit")
  void shouldReportTheKeyframePositionsWhenTheStreamHoldsExactlyTheKeyframeLimit(boolean scan) {
    var request = scan ? KEYFRAME_PACKET_SCAN : PROBE;
    var report = reportOf(request, 3, List.of(3));
    var limits = ProbeReportLimits.builder().keyframes(3).build();

    assertThat(limits.apply(request, report)).isSameAs(report);
  }

  @ParameterizedTest(name = "scanning keyframe packets: {0}")
  @ValueSource(booleans = {false, true})
  @DisplayName(
      "Should report the keyframes as unavailable for too many keyframes, with the container and"
          + " streams and without positions or measurement, when the stream holds more keyframes"
          + " than the keyframe limit")
  void shouldReportTheKeyframesAsUnavailableWhenTheStreamHoldsMoreThanTheKeyframeLimit(
      boolean scan) {
    var request = scan ? KEYFRAME_PACKET_SCAN : PROBE;
    var report = reportOf(request, 4, List.of(4, 4));
    var limits = ProbeReportLimits.builder().keyframes(3).build();

    var sent = limits.apply(request, report);

    assertThat(sent)
        .isEqualTo(
            ProbeReports.resultOnly(
                resultOf(request)
                    .setMedia(
                        mediaWithOneStream()
                            .setKeyframes(
                                ProbeKeyframes.newBuilder()
                                    .setUnavailable(
                                        KeyframesUnavailable.newBuilder()
                                            .setReason(
                                                KeyframesUnavailableReason
                                                    .KEYFRAMES_UNAVAILABLE_REASON_TOO_MANY_KEYFRAMES))))
                    .build()));
  }

  @ParameterizedTest(name = "scanning keyframe packets: {0}")
  @ValueSource(booleans = {false, true})
  @DisplayName(
      "Should report the measurement when its streams hold exactly as many keyframe intervals in"
          + " all as the interval limit")
  void shouldReportTheMeasurementWhenItsStreamsHoldExactlyTheIntervalLimit(boolean scan) {
    var request = scan ? KEYFRAME_PACKET_SCAN : PROBE;
    var report = reportOf(request, 3, List.of(3, 3));
    var limits = ProbeReportLimits.builder().measuredKeyframeIntervals(6).build();

    assertThat(limits.apply(request, report)).isSameAs(report);
  }

  @ParameterizedTest(name = "scanning keyframe packets: {0}")
  @ValueSource(booleans = {false, true})
  @DisplayName(
      "Should leave the measurement out and keep the keyframe positions when the measured streams"
          + " hold more keyframe intervals in all than the interval limit")
  void shouldLeaveTheMeasurementOutAndKeepThePositionsWhenTheStreamsHoldMoreThanTheIntervalLimit(
      boolean scan) {
    var request = scan ? KEYFRAME_PACKET_SCAN : PROBE;
    var report = reportOf(request, 3, List.of(3, 3));
    var limits = ProbeReportLimits.builder().measuredKeyframeIntervals(5).build();

    var sent = limits.apply(request, report);

    assertThat(sent).isEqualTo(reportOf(request, 3, List.of()));
    assertThat(sent.getResult().getMedia().hasStreamMeasurements()).isFalse();
    assertThat(sent.getKeyframePresentationTimeDeltasList()).hasSize(3);
  }

  @Test
  @DisplayName(
      "Should report a bitrate scan chunk's measurement when its streams hold exactly as many"
          + " keyframe intervals in all as the interval limit")
  void shouldReportABitrateScanChunksMeasurementWhenItsStreamsHoldExactlyTheIntervalLimit() {
    var report = chunkReportOf(List.of(2, 2));
    var limits = ProbeReportLimits.builder().measuredKeyframeIntervals(4).build();

    assertThat(limits.apply(BITRATE_SCAN_CHUNK, report)).isSameAs(report);
  }

  @Test
  @DisplayName(
      "Should fail a bitrate scan chunk as too large when its measured streams hold more keyframe"
          + " intervals in all than the interval limit")
  void shouldFailABitrateScanChunkAsTooLargeWhenItsStreamsHoldMoreThanTheIntervalLimit() {
    var report = chunkReportOf(List.of(2, 3));
    var limits = ProbeReportLimits.builder().measuredKeyframeIntervals(4).build();

    assertThat(limits.apply(BITRATE_SCAN_CHUNK, report))
        .isEqualTo(
            ProbeReports.resultOnly(
                failure(BITRATE_SCAN_CHUNK, ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE)));
  }

  @ParameterizedTest(name = "{0} bytes beyond the length limit")
  @ValueSource(ints = {-1, 0})
  @DisplayName("Should send the report when its encoding is no longer than the length limit")
  void shouldSendTheReportWhenItsEncodingIsNoLongerThanTheLengthLimit(int bytesBeyondLimit) {
    var report = reportOf(PROBE, 3, List.of(3));
    var limits =
        ProbeReportLimits.builder()
            .reportLengthBytes(report.getSerializedSize() - bytesBeyondLimit)
            .build();

    assertThat(limits.apply(PROBE, report)).isSameAs(report);
  }

  @Test
  @DisplayName(
      "Should fail the probe as too large when its encoded report is one byte longer than the"
          + " length limit")
  void shouldFailTheProbeAsTooLargeWhenItsEncodedReportIsOneByteLongerThanTheLengthLimit() {
    var report = reportOf(PROBE, 3, List.of(3));
    var limits =
        ProbeReportLimits.builder().reportLengthBytes(report.getSerializedSize() - 1L).build();

    assertThat(limits.apply(PROBE, report))
        .isEqualTo(
            ProbeReports.resultOnly(failure(PROBE, ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE)));
  }

  @Test
  @DisplayName(
      "Should check the length of the report that the keyframe limit and the interval limit leave"
          + " when the probe's own report is longer than the length limit")
  void shouldCheckTheLengthOfTheReportThatTheKeyframeAndIntervalLimitsLeave() {
    var report = reportOf(PROBE, 1_000, List.of(1_000));
    var withoutMeasurement = reportOf(PROBE, 1_000, List.of());
    var limits =
        ProbeReportLimits.builder()
            .measuredKeyframeIntervals(999)
            .reportLengthBytes(withoutMeasurement.getSerializedSize())
            .build();

    assertThat(report.getSerializedSize()).isGreaterThan(withoutMeasurement.getSerializedSize());
    assertThat(limits.apply(PROBE, report)).isEqualTo(withoutMeasurement);
  }

  // A probe's or a keyframe packet scan's report: this many keyframes of the chosen video stream,
  // and a measurement of one stream for each entry, which holds that many keyframe intervals.
  private static ProbeAttemptReport reportOf(
      ProbeRequest request, int keyframes, List<Integer> intervalsOfEachMeasuredStream) {
    var media =
        mediaWithOneStream()
            .setKeyframes(
                ProbeKeyframes.newBuilder()
                    .setPositions(KeyframePositions.newBuilder().setKeyframeCount(keyframes)));
    var report = ProbeAttemptReport.newBuilder();
    for (var keyframe = 0; keyframe < keyframes; keyframe++) {
      report.addKeyframePresentationTimeDeltas(1_000).addKeyframeDecodeTimeDeltas(1_000);
    }

    if (!intervalsOfEachMeasuredStream.isEmpty()) {
      var measurements = StreamMeasurements.newBuilder();
      for (var stream = 0; stream < intervalsOfEachMeasuredStream.size(); stream++) {
        measurements.addStreams(MeasuredStream.newBuilder().setStreamIndex(stream));
      }

      media.setStreamMeasurements(measurements);
      report.addAllStreamMeasurementIntervals(intervalsOf(intervalsOfEachMeasuredStream));
    }

    return report.setResult(resultOf(request).setMedia(media)).build();
  }

  private static ProbeAttemptReport chunkReportOf(List<Integer> intervalsOfEachMeasuredStream) {
    return ProbeAttemptReport.newBuilder()
        .setResult(
            resultOf(BITRATE_SCAN_CHUNK)
                .setBitrateScanChunk(
                    BitrateScanChunkResult.newBuilder()
                        .setMeasured(BitrateScanChunkStreams.getDefaultInstance())))
        .addAllStreamMeasurementIntervals(intervalsOf(intervalsOfEachMeasuredStream))
        .build();
  }

  private static List<StreamMeasurementIntervals> intervalsOf(
      List<Integer> intervalsOfEachMeasuredStream) {
    return intervalsOfEachMeasuredStream.stream()
        .map(
            intervals -> {
              var stream = StreamMeasurementIntervals.newBuilder();
              for (var interval = 0; interval < intervals; interval++) {
                stream.addKeyframeIntervalBytes(4_000).addKeyframeIntervalPacketCounts(12);
              }

              return stream.build();
            })
        .toList();
  }

  private static ProbeAttemptResult.Builder resultOf(ProbeRequest request) {
    return ProbeAttemptResult.newBuilder()
        .setProbeAttemptId(request.getProbeAttemptId())
        .setProbeVersion(request.getProbeVersion());
  }

  private static ProbeMediaInfo.Builder mediaWithOneStream() {
    return ProbeMediaInfo.newBuilder()
        .setContainer(ProbeContainerInfo.newBuilder().setFormat("matroska,webm"))
        .addStreams(ProbeStreamInfo.newBuilder().setIndex(0).setCodecType("video"))
        .setChosenVideoStreamIndex(0);
  }
}
