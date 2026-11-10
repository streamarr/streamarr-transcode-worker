package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailable;
import build.buf.gen.streamarr.transcode.v1.KeyframesUnavailableReason;
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

  static Stream<Named<String>> scansWithoutKeyframe() {
    return Stream.of(
        Named.of(
            "only packets without the keyframe flag",
            """
            0, 126000, 133500, 3750, 100, 0x00000000, F=0x0
            0, 129750, 144750, 3750, 100, 0x00000000, F=0x0
            """),
        Named.of("no packet", ""));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("scansWithoutKeyframe")
  @DisplayName("Should report keyframes unavailable when the packet scan finds no keyframe")
  void shouldReportKeyframesUnavailableWhenPacketScanFindsNoKeyframe(
      String packetScan, @TempDir Path directory) throws IOException {
    var source = Files.createFile(directory.resolve("source.ts"));
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            command -> {
              if (command.tool() == ProbeCommand.ProbeTool.FFMPEG) {
                return printing(packetScan);
              }

              if (command.arguments().contains("-read_intervals")) {
                return printing("");
              }

              return printing(MPEGTS_STREAMS);
            });

    var report = executor.probe(source, request());

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
