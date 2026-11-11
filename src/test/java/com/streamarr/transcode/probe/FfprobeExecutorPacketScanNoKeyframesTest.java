package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailable;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailableReason;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeKeyframes;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.fakes.ScriptedProcess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;

// worker_session.proto: KeyframePositions.keyframe_count is at least 1, and
// KEYFRAMES_UNAVAILABLE_REASON_NO_KEYFRAMES reports a packet scan that found no keyframe.
@Tag("UnitTest")
@Timeout(60)
@DisplayName("Ffprobe Executor Packet Scan No Keyframes Tests")
class FfprobeExecutorPacketScanNoKeyframesTest {

  private static final String MPEGTS_STREAMS =
      """
      {"format":{"format_name":"mpegts","start_time":"1.460111","duration":"4.000000"},
       "streams":[
        {"index":0,"codec_type":"video","codec_name":"h264","id":"0x100","r_frame_rate":"24/1",
         "time_base":"1/90000","has_b_frames":2,"disposition":{"attached_pic":0}}]}
      """;
  private static final String PACKETS_WITHOUT_KEYFRAME_FLAG =
      """
      0, 126000, 133500, 3750, 100, 0x00000000, F=0x0
      0, 129750, 144750, 3750, 100, 0x00000000, F=0x0
      """;

  static Stream<Named<String>> scansWithoutKeyframe() {
    return Stream.of(
        Named.of("only packets without the keyframe flag", PACKETS_WITHOUT_KEYFRAME_FLAG),
        Named.of("no packet", ""));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scansWithoutKeyframe")
  @DisplayName("Should report keyframes unavailable when the packet scan finds no keyframe")
  void shouldReportKeyframesUnavailableWhenPacketScanFindsNoKeyframe(
      String packetScan, @TempDir Path directory) throws IOException {
    var source = Files.createFile(directory.resolve("source.ts"));

    var report = executorScanning(printing(packetScan)).probe(source, request());

    assertThat(report.result().getMedia().getKeyframes())
        .isEqualTo(
            ProbeKeyframes.newBuilder()
                .setUnavailable(
                    KeyframesUnavailable.newBuilder()
                        .setReason(
                            KeyframesUnavailableReason.KEYFRAMES_UNAVAILABLE_REASON_NO_KEYFRAMES))
                .build());
    assertThat(report.keyframePositionsParts()).isEmpty();
  }

  // A scan that stopped early has not read every packet, so it fails for a retry: the server keeps
  // keyframes reported unavailable and never copies the source.
  static Stream<Named<Process>> scansThatFailBeforeAKeyframe() {
    return Stream.of(
        Named.of(
            "FFmpeg exits with an error before it prints a packet",
            ScriptedProcess.builder().output(new byte[0]).exitCode(1).build()),
        Named.of(
            "FFmpeg reports a demuxing error after packets without the keyframe flag",
            ScriptedProcess.builder()
                .output(PACKETS_WITHOUT_KEYFRAME_FLAG.getBytes(StandardCharsets.UTF_8))
                .stderr("[in#0/mpegts @ 0x1] Error during demuxing: Input/output error\n")
                .build()));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scansThatFailBeforeAKeyframe")
  @DisplayName(
      "Should report an execution failure when the packet scan fails before it finds a keyframe")
  void shouldReportExecutionFailureWhenPacketScanFailsBeforeItFindsAKeyframe(
      Process packetScan, @TempDir Path directory) throws IOException {
    var source = Files.createFile(directory.resolve("source.ts"));

    var report = executorScanning(packetScan).probe(source, request());

    assertThat(report.result().getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
  }

  private static FfprobeExecutor executorScanning(Process packetScan) {
    return new FfprobeExecutor(
        new ObjectMapper(),
        command -> {
          if (command.tool() == ProbeCommand.ProbeTool.FFMPEG) {
            return packetScan;
          }

          if (command.arguments().contains("-read_intervals")) {
            return printing("");
          }

          return printing(MPEGTS_STREAMS);
        });
  }

  private static Process printing(String output) {
    return ScriptedProcess.builder().output(output.getBytes(StandardCharsets.UTF_8)).build();
  }

  private static ProbeRequest request() {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
        .setScanKeyframePackets(true)
        .build();
  }
}
