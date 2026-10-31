package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the worker's own output check over the initialization segments that record-nvenc.sh records
 * on an NVIDIA GPU host (src/test/resources/nvenc), so that each recorded verdict of the check of
 * streamarr/streamarr-transcode-worker#25 is the verdict the producer would reach.
 */
@Tag("UnitTest")
@DisplayName("NVENC Recordings Tests")
class NvencRecordingsTest {

  private static final Path RESULTS = Path.of("src", "test", "resources", "nvenc");

  @Test
  @DisplayName(
      "Should reach each recorded output check verdict with the worker's own check when NVENC"
          + " recordings are committed")
  void
      shouldReachEachRecordedOutputCheckVerdictWithTheWorkersOwnCheckWhenNvencRecordingsAreCommitted()
          throws IOException {
    var results = RESULTS.resolve("recordings.json");
    assumeTrue(
        Files.exists(results),
        "no NVENC recordings yet: run src/test/fmp4-recorder/record-nvenc.sh on an NVIDIA GPU host");
    var recordings = JsonMapper.builder().build().readTree(results.toFile()).get("recordings");
    assertThat(recordings).as("the recordings in %s", results).isNotEmpty();

    var softly = new SoftAssertions();
    for (var recording : recordings) {
      softly
          .assertThat(passesTheWorkersCheck(recording))
          .as("the worker's output check of %s", recording.get("name").asString())
          .isEqualTo(recording.get("outputCheck").get("passes").asBoolean());
    }

    softly.assertAll();
  }

  private static boolean passesTheWorkersCheck(JsonNode recording) throws IOException {
    var bytes =
        Files.readAllBytes(
            RESULTS.resolve(recording.get("initializationSegment").get("file").asString()));
    var decision = encodeDecidedFor(recording.get("plan"));
    try {
      VideoOutputCheck.requireMatch(decision, new InitializationSegment(bytes));
      return true;
    } catch (FragmentedMp4Exception _) {
      return false;
    }
  }

  private static VideoDecision.Encode encodeDecidedFor(JsonNode plan) {
    var hevc = plan.get("encoder").asString().startsWith("hevc");
    var tier = Optional.<VideoCodecTier>empty();
    if (!plan.get("tier").isNull()) {
      tier =
          Optional.of(VideoCodecTier.valueOf(plan.get("tier").asString().toUpperCase(Locale.ROOT)));
    }

    return VideoDecision.Encode.builder()
        .sourceCodecFamily(Optional.of(CodecFamily.H264))
        .sourceDynamicRange(VideoDynamicRange.sdr())
        .outputDynamicRange(VideoDynamicRange.sdr())
        .codecProfile(hevc ? VideoCodecProfile.HEVC_MAIN : VideoCodecProfile.H264_HIGH)
        .tier(tier)
        .level(plan.get("level").asInt())
        .codecString(plan.get("expectedCodecString").asString())
        .build();
  }
}
