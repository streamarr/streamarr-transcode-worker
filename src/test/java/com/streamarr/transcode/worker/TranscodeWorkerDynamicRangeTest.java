package com.streamarr.transcode.worker;

import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.requestBuilder;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.start;
import static com.streamarr.transcode.worker.support.WorkerProbeFixtures.workerBuilder;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ChromaticityCoordinates;
import build.buf.gen.streamarr.transcode.v1.CompatibleBase;
import build.buf.gen.streamarr.transcode.v1.ContentLightLevel;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionConfiguration;
import build.buf.gen.streamarr.transcode.v1.DolbyVisionPresent;
import build.buf.gen.streamarr.transcode.v1.MasteringDisplayColorVolume;
import build.buf.gen.streamarr.transcode.v1.Presence;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.probe.FfprobeExecutor;
import com.streamarr.transcode.worker.support.ScriptedWorkerRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
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
@DisplayName("Transcode Worker Dynamic Range Tests")
@Timeout(value = 1, unit = TimeUnit.MINUTES)
class TranscodeWorkerDynamicRangeTest {

  @TempDir Path root;

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "mp4-hevc-hdr10.mp4, COMPATIBLE_BASE_PQ",
    "mov-phone-hlg-dovi-p84.mov, COMPATIBLE_BASE_HLG",
    "mp4-sdr8-bt709.mp4, COMPATIBLE_BASE_SDR",
    "mp4-dovi-p82.mp4, COMPATIBLE_BASE_SDR",
    "mp4-dovi-p9-avc1.mp4, COMPATIBLE_BASE_SDR",
    "mp4-dovi-p5-dvh1.mp4, COMPATIBLE_BASE_NONE",
    "mp4-dovi-p5-dvh1-no-record.mp4, COMPATIBLE_BASE_NONE",
    "mp4-dovi-p5-hev1.mp4, COMPATIBLE_BASE_NONE",
    "mp4-hevc-sdr10-untagged.mp4, COMPATIBLE_BASE_UNSPECIFIED",
  })
  @DisplayName("Should derive the compatible base from the transfer when the file is recorded")
  void shouldDeriveTheCompatibleBaseFromTheTransferWhenTheFileIsRecorded(
      String fixture, CompatibleBase expected) throws Exception {
    var video = recordedVideo(fixture);

    assertThat(video.getDynamicRange().getCompatibleBase()).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("declaredConfigurations")
  @DisplayName("Should keep the whole configuration record when the stream declares one")
  void shouldKeepTheWholeConfigurationRecordWhenTheStreamDeclaresOne(
      String fixture, DolbyVisionConfiguration expected) throws Exception {
    var video = recordedVideo(fixture);

    assertThat(video.getDynamicRange().getDolbyVisionPresent())
        .isEqualTo(
            DolbyVisionPresent.newBuilder()
                .setDeclared(expected)
                .setObservedRpu(Presence.PRESENCE_UNSPECIFIED)
                .build());
  }

  private static Stream<Arguments> declaredConfigurations() {
    return Stream.of(
        Arguments.of(
            "mp4-dovi-p5-dvh1.mp4",
            declaredConfiguration()
                .setProfile(5)
                .setLevel(5)
                .setBaseLayerCompatibilityId(0)
                .build()),
        Arguments.of(
            "mp4-dovi-p5-hev1.mp4",
            declaredConfiguration()
                .setProfile(5)
                .setLevel(4)
                .setBaseLayerCompatibilityId(0)
                .build()),
        Arguments.of(
            "mp4-dovi-p81.mp4",
            declaredConfiguration()
                .setProfile(8)
                .setLevel(5)
                .setBaseLayerCompatibilityId(1)
                .build()),
        Arguments.of(
            "mp4-dovi-p82.mp4",
            declaredConfiguration()
                .setProfile(8)
                .setLevel(3)
                .setBaseLayerCompatibilityId(2)
                .build()),
        Arguments.of(
            "mp4-dovi-p84-hlg.mp4",
            declaredConfiguration()
                .setProfile(8)
                .setLevel(5)
                .setBaseLayerCompatibilityId(4)
                .build()),
        Arguments.of(
            "mov-phone-hlg-dovi-p84.mov",
            declaredConfiguration()
                .setProfile(8)
                .setLevel(4)
                .setBaseLayerCompatibilityId(4)
                .build()),
        Arguments.of(
            "mp4-dovi-p9-avc1.mp4",
            declaredConfiguration()
                .setProfile(9)
                .setLevel(3)
                .setBaseLayerCompatibilityId(2)
                .build()),
        Arguments.of(
            "mp4-dovi-p9-dva1.mp4",
            declaredConfiguration()
                .setProfile(9)
                .setLevel(3)
                .setBaseLayerCompatibilityId(2)
                .build()),
        Arguments.of(
            "mpegts-dovi-p7.ts",
            declaredConfiguration()
                .setProfile(7)
                .setLevel(6)
                .setBaseLayerCompatibilityId(6)
                .setEnhancementLayer(true)
                .build()));
  }

  private static DolbyVisionConfiguration.Builder declaredConfiguration() {
    return DolbyVisionConfiguration.newBuilder()
        .setBaseLayer(true)
        .setEnhancementLayer(false)
        .setRpu(true);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"mp4-hdr10-static.mp4", "mkv-hdr10-static.mkv"})
  @DisplayName(
      "Should report the SEI units that the encoder wrote when the container declares them")
  void shouldReportTheSeiUnitsThatTheEncoderWroteWhenTheContainerDeclaresThem(String fixture)
      throws Exception {
    // x265 wrote G(13250,34500) B(7500,3000) R(34000,16000) WP(15635,16450) L(10000000,50).
    var video = recordedVideo(fixture);

    assertThat(video.getMasteringDisplay()).isEqualTo(x265MasteringDisplay(50));
  }

  @Test
  @DisplayName("Should round each Matroska float to the nearest unit when the values are inexact")
  void shouldRoundEachMatroskaFloatToTheNearestUnitWhenTheValuesAreInexact() throws Exception {
    // FATE's hdr10tags-both.mkv stores the same primaries and white point as 32-bit floats, a
    // minimum luminance of 0.01 cd/m^2 and a maximum of 1000 cd/m^2.
    var video = recordedVideo("mkv-hdr10-fate.mkv");

    assertThat(video.getMasteringDisplay()).isEqualTo(x265MasteringDisplay(100));
  }

  /**
   * The primaries, white point and maximum luminance that the HDR10 fixtures' x265 encode wrote,
   * with the given minimum luminance.
   */
  private static MasteringDisplayColorVolume x265MasteringDisplay(long minLuminance) {
    return MasteringDisplayColorVolume.newBuilder()
        .setRed(coordinates(34000, 16000))
        .setGreen(coordinates(13250, 34500))
        .setBlue(coordinates(7500, 3000))
        .setWhitePoint(coordinates(15635, 16450))
        .setMaxLuminance(10_000_000)
        .setMinLuminance((int) minLuminance)
        .build();
  }

  private static ChromaticityCoordinates coordinates(int x, int y) {
    return ChromaticityCoordinates.newBuilder().setX(x).setY(y).build();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "mp4-hdr10-static.mp4, 1000, 400",
    "mkv-hdr10-static.mkv, 1000, 400",
    "mkv-hdr10-fate.mkv, 1000, 300",
    "mp4-hdr10-maxfall-zero.mp4, 1000, 0",
  })
  @DisplayName("Should report MaxCLL and MaxFALL when the container declares them")
  void shouldReportMaxCllAndMaxFallWhenTheContainerDeclaresThem(
      String fixture, int maxCll, int maxFall) throws Exception {
    var video = recordedVideo(fixture);

    assertThat(video.getContentLightLevel().getMaxContentLightLevel()).isEqualTo(maxCll);
    assertThat(video.getContentLightLevel().getMaxFrameAverageLightLevel()).isEqualTo(maxFall);
  }

  @ParameterizedTest(name = "MaxCLL 0, MaxFALL {0}")
  @ValueSource(ints = {400, 0})
  @DisplayName("Should keep the light level present when MaxCLL is declared as zero")
  void shouldKeepTheLightLevelPresentWhenMaxCllIsDeclaredAsZero(int maxFall) throws Exception {
    var video =
        probeVideo(
            """
            {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000",
              "side_data_list":[{"side_data_type":"Content light level metadata",
                "max_content":0,"max_average":%d}]}]}
            """
                .formatted(maxFall));

    assertThat(video.hasContentLightLevel()).isTrue();
    assertThat(video.getContentLightLevel())
        .isEqualTo(
            ContentLightLevel.newBuilder()
                .setMaxContentLightLevel(0)
                .setMaxFrameAverageLightLevel(maxFall)
                .build());
  }

  private ProbeStreamInfo recordedVideo(String fixture) throws Exception {
    try (var recorded = getClass().getResourceAsStream("/probe/" + fixture + ".json")) {
      assertThat(recorded).as("recorded fixture %s", fixture).isNotNull();
      return probeVideo(new String(recorded.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private ProbeStreamInfo probeVideo(String output) throws Exception {
    Files.writeString(root.resolve("movie.mkv"), "media");
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

    try (var worker = workerBuilder(root).runtime(runtime).ffprobe(executor).build()) {
      worker.start("localhost", 1);
      start(runtime.connection(), request);
      runtime.probes().drain();

      assertThat(runtime.connection().results())
          .singleElement()
          .satisfies(
              result -> {
                assertThat(result.getProbeAttemptId()).isEqualTo(request.getProbeAttemptId());
                assertThat(result.hasMedia()).as("the worker reports a successful probe").isTrue();
              });
      return runtime.connection().results().getFirst().getMedia().getStreams(0);
    }
  }
}
