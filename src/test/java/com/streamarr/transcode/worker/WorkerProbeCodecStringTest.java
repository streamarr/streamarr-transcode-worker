package com.streamarr.transcode.worker;

import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilderWithoutFfprobe;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Worker Probe Codec String Tests")
class WorkerProbeCodecStringTest {

  @TempDir Path root;

  @ParameterizedTest(name = "{0}: {1}")
  @CsvSource({
    "mp4-sdr8-bt709.mp4, avc1.42C00A",
    "mp4-av1-10bit.mp4, av01.0.00M.10",
    "mp4-hdr10-static.mp4, hvc1.2.4.L30.90",
    "mp4-dovi-p5-dvh1.mp4, hvc1.2.4.L123.B0",
    "mp4-dovi-p9-dva1.mp4, avc1.640028",
    "mp4-dovi-p82.mp4, hvc1.2.4.L60.90",
  })
  @DisplayName("Should publish the copied codec string when the server probes a recorded stream")
  void shouldPublishTheCopiedCodecStringWhenTheServerProbesARecordedStream(
      String fixture, String expected) throws Exception {
    // Expected strings were read from copied initialization segments in copy-codec-strings.json.
    try (var recorded = getClass().getResourceAsStream("/probe/" + fixture + ".json")) {
      assertThat(recorded).isNotNull();
      var video = probe(new String(recorded.readAllBytes(), StandardCharsets.UTF_8));

      assertThat(video.getCodecString()).isEqualTo(expected);
    }
  }

  @Test
  @DisplayName("Should merge HEVC parameter sets when their profile, tier and flags disagree")
  void shouldMergeHevcParameterSetsWhenTheirProfileTierAndFlagsDisagree() throws Exception {
    // FFmpeg hvcc_update_ptl intersects flags, keeps the highest profile, and resets the level
    // when a higher tier arrives: https://ffmpeg.org/doxygen/trunk/hevc_8c_source.html.
    var vps =
        profile()
            .profileId(2)
            .level(153)
            .compatibility(0x60000000L)
            .constraints(0xb00000000000L)
            .build();
    var sps =
        profile()
            .profileId(1)
            .highTier(true)
            .level(120)
            .compatibility(0xa0000000L)
            .constraints(0xd00000000000L)
            .build();

    var video =
        probeHevc(
            List.of(
                parameterSet().type(32).profile(vps).build(),
                parameterSet().type(33).profile(sps).build()));

    assertThat(video.getCodecString()).isEqualTo("hvc1.A2.4.H120.90");
  }

  @Test
  @DisplayName("Should keep the highest HEVC level when both parameter sets use the same tier")
  void shouldKeepTheHighestHevcLevelWhenBothParameterSetsUseTheSameTier() throws Exception {
    var vps = profile().profileId(1).highTier(true).level(150).build();
    var sps = profile().profileId(2).highTier(true).level(120).build();

    var video =
        probeHevc(
            List.of(
                parameterSet().type(32).profile(vps).build(),
                parameterSet().type(33).profile(sps).build()));

    assertThat(video.getCodecString()).isEqualTo("hvc1.A2.6.H150.90");
  }

  @ParameterizedTest(name = "layer {0}")
  @ValueSource(ints = {1, 32})
  @DisplayName("Should ignore enhancement-layer HEVC parameter sets when the base layer is present")
  void shouldIgnoreEnhancementLayerHevcParameterSetsWhenTheBaseLayerIsPresent(int layer)
      throws Exception {
    var base = profile().profileSpace(0).profileId(1).level(93).build();
    var enhancement =
        profile()
            .profileSpace(3)
            .profileId(31)
            .highTier(true)
            .level(255)
            .compatibility(0)
            .constraints(0)
            .build();

    var video =
        probeHevc(
            List.of(
                parameterSet().type(32).profile(base).build(),
                parameterSet().type(33).profile(base).build(),
                parameterSet().type(32).layerId(layer).profile(enhancement).build(),
                parameterSet().type(33).layerId(layer).profile(enhancement).build()));

    assertThat(video.getCodecString()).isEqualTo("hvc1.1.6.L93.90");
  }

  private ProbeStreamInfo probeHevc(List<ParameterSet> parameterSets) throws Exception {
    var nalUnits = new ByteArrayOutputStream();
    for (var parameterSet : parameterSets) {
      nalUnits.writeBytes(new byte[] {0, 0, 0, 1});
      nalUnits.writeBytes(parameterSet.nal());
    }

    var json = new ObjectMapper().createObjectNode();
    json.putArray("streams")
        .addObject()
        .put("index", 0)
        .put("codec_type", "video")
        .put("codec_name", "hevc")
        .put("time_base", "1/90000")
        .put("has_b_frames", 0)
        .put("extradata", dump(nalUnits.toByteArray()));
    return probe(json.toString());
  }

  private ProbeStreamInfo probe(String output) throws Exception {
    Files.writeString(root.resolve("movie.mkv"), "source");
    var runtime = new ScriptedWorkerRuntime();
    var executor =
        new FfprobeExecutor(
            new ObjectMapper(),
            command ->
                ScriptedProcess.builder()
                    .output(
                        switch (command.tool()) {
                          case FFPROBE -> output.getBytes(StandardCharsets.UTF_8);
                          case FFMPEG -> new byte[0];
                        })
                    .build());
    var request = requestBuilder().setScanKeyframePackets(true).build();
    try (var worker =
        workerBuilderWithoutFfprobe(root).ffprobe(executor).runtime(runtime).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().results())
          .singleElement()
          .satisfies(
              result -> {
                assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_UNSPECIFIED);
                assertThat(result.hasMedia()).isTrue();
              });
      return runtime.connection().results().getFirst().getMedia().getStreams(0);
    }
  }

  private static Profile.ProfileBuilder profile() {
    return Profile.builder()
        .profileSpace(1)
        .compatibility(0x60000000L)
        .constraints(0x900000000000L);
  }

  private static ParameterSet.ParameterSetBuilder parameterSet() {
    return ParameterSet.builder().layerId(0);
  }

  @Builder
  private record Profile(
      int profileSpace,
      int profileId,
      boolean highTier,
      int level,
      long compatibility,
      long constraints) {
    byte[] bytes() {
      var bytes = ByteBuffer.allocate(12);
      bytes.put((byte) ((profileSpace << 6) | (highTier ? 0x20 : 0) | profileId));
      bytes.putInt((int) compatibility);
      bytes.putShort((short) (constraints >>> 32));
      bytes.putInt((int) constraints);
      bytes.put((byte) level);
      return bytes.array();
    }
  }

  @Builder
  private record ParameterSet(int type, int layerId, Profile profile) {
    byte[] nal() {
      var bytes = new ByteArrayOutputStream();
      bytes.write((type << 1) | (layerId >>> 5));
      bytes.write(((layerId & 31) << 3) | 1);
      var payload = new ByteArrayOutputStream();
      payload.writeBytes(type == 32 ? new byte[] {12, 1, -1, -1} : new byte[] {1});
      payload.writeBytes(profile.bytes());
      var zeros = 0;
      for (var value : payload.toByteArray()) {
        if (zeros >= 2 && Byte.toUnsignedInt(value) <= 3) {
          bytes.write(3);
          zeros = 0;
        }

        bytes.write(value);
        zeros = value == 0 ? zeros + 1 : 0;
      }

      return bytes.toByteArray();
    }
  }

  private static String dump(byte[] bytes) {
    var dump = new StringBuilder("\n");
    for (var offset = 0; offset < bytes.length; offset += 16) {
      var end = Math.min(offset + 16, bytes.length);
      var hex = HexFormat.of().formatHex(bytes, offset, end);
      dump.append("%08x: %-42s %s%n".formatted(offset, hex, ".".repeat(end - offset)));
    }

    return dump.toString();
  }
}
