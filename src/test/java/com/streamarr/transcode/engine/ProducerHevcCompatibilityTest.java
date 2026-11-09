package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.fakes.ScriptedProcess;
import com.streamarr.transcode.fakes.ScriptedProcessLauncher;
import com.streamarr.transcode.fixtures.TopLevelBoxes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Producer HEVC Compatibility Tests")
class ProducerHevcCompatibilityTest {

  @ParameterizedTest
  @ValueSource(strings = {"a2000-main.fmp4", "rtx5090-main.fmp4"})
  @DisplayName(
      "Should deliver the advertised Main compatibility when NVENC omits Main 10 compatibility")
  void shouldDeliverTheAdvertisedMainCompatibilityWhenNvencOmitsMain10Compatibility(String file)
      throws IOException {
    byte[] recording;
    try (var resource = getClass().getResourceAsStream("/hevc-compatibility/" + file)) {
      assertThat(resource).isNotNull();
      recording = resource.readAllBytes();
    }

    var sink = new RecordingSegmentSink();
    var process = ScriptedProcess.builder().output(recording).build();
    var decision =
        VideoDecisionFixtures.encodeBuilder()
            .codecProfile(VideoCodecProfile.HEVC_MAIN)
            .tier(Optional.of(VideoCodecTier.MAIN))
            .level(30)
            .codecString("hvc1.1.6.L90.90")
            .build();
    var producer =
        Producer.builder()
            .launcher(new ScriptedProcessLauncher(_ -> process))
            .pipeline(new FfmpegPipeline.SingleProcess(List.of("ffmpeg")))
            .jobAttemptId(UUID.randomUUID())
            .intervals(SegmentIntervals.onPlainGrid(6))
            .gracePeriod(Duration.ofSeconds(5))
            .stallTimeout(Duration.ofMinutes(1))
            .encodedFrameRate(Optional.of(new OutputFrameRate(24000, 1001)))
            .videoDecision(decision)
            .paddedAudioEnd(Optional.empty())
            .memoryBudget(SegmentMemoryBudget.forSlots(1))
            .pauses(new ProducerPauses())
            .sink(sink)
            .clock(MonotonicClock.system())
            .start();

    assertThat(producer.outcome())
        .succeedsWithin(Duration.ofSeconds(10))
        .isInstanceOf(Completed.class);
    assertThat(sink.acceptedNames()).hasSize(2).contains("init.mp4");
    assertThat(mediaBytes(sink.acceptedBytes())).isEqualTo(mediaBytes(recording));
    var delivered = new InitializationSegment(sink.contentOf("init.mp4"));
    assertThat(CodecConfigurationReader.tracksOf(delivered))
        .contains(
            new TrackCodec.Video("hvc1.1.6.L90.90", Optional.of(PictureFormat.EIGHT_BIT_420)));
  }

  private static byte[] mediaBytes(byte[] movie) {
    var samples = new ByteArrayOutputStream();
    TopLevelBoxes.of(movie).stream()
        .filter(box -> box.type().equals("mdat"))
        .forEach(box -> samples.write(movie, box.start() + 8, box.size() - 8));
    return samples.toByteArray();
  }
}
