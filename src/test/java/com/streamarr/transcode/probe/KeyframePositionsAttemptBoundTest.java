package com.streamarr.transcode.probe;

import static com.streamarr.transcode.protocol.ProtoUuid.toProto;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptReport;
import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeRequest;
import com.streamarr.transcode.fakes.ScriptedProcess;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

// The worker reports at most 1,048,576 keyframe positions for one probe attempt.
@Tag("UnitTest")
@Timeout(120)
@DisplayName("Keyframe Positions Attempt Bound Tests")
class KeyframePositionsAttemptBoundTest {

  private static final int MAXIMUM_KEYFRAMES_PER_ATTEMPT = 1_048_576;
  private static final int ONE_KEYFRAME_TOO_MANY = MAXIMUM_KEYFRAMES_PER_ATTEMPT + 1;

  @Test
  @DisplayName(
      "Should send at most 1,048,576 keyframe positions in an attempt when an MP4 without a"
          + " sync-sample table holds 1,048,577 samples")
  void shouldSendAtMost1048576KeyframePositionsWhenMp4WithoutSyncSampleTableHolds1048577Samples(
      @TempDir Path directory) throws IOException {
    // No stss: every sample is a sync sample, as in an intra-only track.
    var source =
        Files.write(
            directory.resolve("source.mp4"),
            SyntheticMp4.builder()
                .mediaTimescale(1_000)
                .sampleDurations(ONE_KEYFRAME_TOO_MANY, 1)
                .build()
                .bytes());
    var lastKeyframe = ONE_KEYFRAME_TOO_MANY - 1;
    var executor =
        executor(
            command -> {
              if (command.arguments().contains("-read_intervals")) {
                return printing(
                    "{\"packets\":[{\"pts\":%d,\"dts\":%d,\"flags\":\"K__\"}]}"
                        .formatted(lastKeyframe, lastKeyframe));
              }

              return printing(
                  """
                  {"format":{"format_name":"mov,mp4,m4a,3gp,3g2,mj2","start_time":"0.000000"},
                   "streams":[{"index":0,"codec_type":"video","id":"0x1"}]}
                  """);
            });

    var report = executor.probe(source, request(false));

    assertThat(sentKeyframePositions(report))
        .as(
            "keyframe positions across the attempt's parts (result: failure %s, keyframes %s)",
            report.getResult().getFailure(), report.getResult().getMedia().getKeyframes())
        .isLessThanOrEqualTo(MAXIMUM_KEYFRAMES_PER_ATTEMPT);
    assertThat(report.getResult().getFailure())
        .isEqualTo(ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE);
  }

  @Test
  @DisplayName(
      "Should send at most 1,048,576 keyframe positions in an attempt when a packet scan finds"
          + " 1,048,577 keyframes")
  void shouldSendAtMost1048576KeyframePositionsWhenPacketScanFinds1048577Keyframes(
      @TempDir Path directory) throws IOException {
    var source = Files.createFile(directory.resolve("source.ts"));
    var scan = new StringBuilder();
    for (var keyframe = 0; keyframe < ONE_KEYFRAME_TOO_MANY; keyframe++) {
      scan.append("0, ").append(keyframe).append(", ").append(keyframe).append(", 1, 1, 0x0\n");
    }

    var packetLines = scan.toString();
    var executor =
        executor(
            command -> {
              if (command.tool() == ProbeCommand.ProbeTool.FFMPEG) {
                return printing(packetLines);
              }

              if (command.arguments().contains("-read_intervals")) {
                return printing("");
              }

              return printing(
                  """
                  {"format":{"format_name":"mpegts","start_time":"0.000000"},
                   "streams":[{"index":0,"codec_type":"video","codec_name":"h264",
                               "time_base":"1/90000","has_b_frames":0,
                               "disposition":{"attached_pic":0}}]}
                  """);
            });

    var report = executor.probe(source, request(true));

    assertThat(sentKeyframePositions(report))
        .as(
            "keyframe positions across the attempt's parts (result: failure %s, keyframes %s)",
            report.getResult().getFailure(), report.getResult().getMedia().getKeyframes())
        .isLessThanOrEqualTo(MAXIMUM_KEYFRAMES_PER_ATTEMPT);
    assertThat(report.getResult().getFailure())
        .isEqualTo(ProbeFailure.PROBE_FAILURE_RESULT_TOO_LARGE);
  }

  private static long sentKeyframePositions(ProbeAttemptReport report) {
    return report.getKeyframeDecodeTimeDeltasCount();
  }

  private static FfprobeExecutor executor(Function<ProbeCommand, Process> answers) {
    return new FfprobeExecutor(new ObjectMapper(), answers);
  }

  private static Process printing(String output) {
    return ScriptedProcess.builder().output(output.getBytes(StandardCharsets.UTF_8)).build();
  }

  private static ProbeRequest request(boolean scanKeyframePackets) {
    return ProbeRequest.newBuilder()
        .setProbeAttemptId(toProto(UUID.randomUUID()))
        .setProbeVersion(FfprobeExecutor.PROBE_VERSION)
        .setScanKeyframePackets(scanKeyframePackets)
        .build();
  }
}
