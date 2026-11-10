package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
  private static final List<String> NVENC_ENCODERS = List.of("h264_nvenc", "hevc_nvenc");
  private static final List<String> DEFAULT_LADDER_RUNGS = List.of("1080p", "720p", "480p", "360p");

  @Test
  @DisplayName(
      "Should reach each recorded output check verdict with the worker's own check when NVENC"
          + " recordings are committed")
  void
      shouldReachEachRecordedOutputCheckVerdictWithTheWorkersOwnCheckWhenNvencRecordingsAreCommitted()
          throws IOException {
    var recordings = committedRecordings().get("recordings");
    assertThat(recordings).as("the committed recordings").isNotEmpty();

    var softly = new SoftAssertions();
    for (var recording : recordings) {
      softly
          .assertThat(passesTheWorkersCheck(recording))
          .as("the worker's output check of %s", recording.get("name").asString())
          .isEqualTo(recording.get("outputCheck").get("passes").asBoolean());
    }

    softly.assertAll();
  }

  @Test
  @DisplayName(
      "Should show the keyframes ADR 0037 asks of a keyframe-verified encoder in every extended"
          + " recording when the worker marks an NVENC encoder keyframe-verified")
  void
      shouldShowTheKeyframesAdr0037AsksOfAKeyframeVerifiedEncoderInEveryExtendedRecordingWhenTheWorkerMarksAnNvencEncoderKeyframeVerified() {
    var document = committedRecordings();
    var verified = new ArrayList<JsonNode>();
    for (var recording : document.get("recordings")) {
      if (FfmpegCommandBuilder.isKeyframeVerified(recording.get("plan").get("encoder").asString())
          && recording.get("plan").get("recipe").asString().equals("extended")) {
        verified.add(recording);
      }
    }

    assertThat(verified).as("the extended recordings of keyframe-verified encoders").isNotEmpty();
    var softly = new SoftAssertions();
    for (var recording : verified) {
      var name = recording.get("name").asString();
      var verdicts = recording.get("verdicts");
      softly
          .assertThat(verdicts.get("everyForcedKeyframeIsASyncSample").asBoolean())
          .as("every forced keyframe of %s is a sync sample", name)
          .isTrue();
      softly
          .assertThat(verdicts.get("everyForcedKeyframeStartsAClosedGop").asBoolean())
          .as("every forced keyframe of %s starts a closed GOP", name)
          .isTrue();
      softly
          .assertThat(recording.get("outputCheck").get("passes").asBoolean())
          .as("the output check of %s", name)
          .isTrue();
      var gopRestart = verdicts.get("gopRestart");
      if (!gopRestart.isNull()) {
        softly
            .assertThat(gopRestart.get("restartsAtForcedKeyframe").asBoolean())
            .as("the GOP count of %s restarts at the forced keyframe", name)
            .isTrue();
        softly
            .assertThat(gopRestart.path("nextKeyframeStartsClosedGop").asBoolean(false))
            .as("the keyframe the GOP of %s places for the missed one starts a closed GOP", name)
            .isTrue();
      }
    }

    var verifiedNames =
        verified.stream().map(recording -> recording.get("name").asString()).toList();
    for (var pair : document.get("initializationSegmentPairs")) {
      if (verifiedNames.contains(pair.get("replacement").asString())) {
        softly
            .assertThat(pair.get("identical").asBoolean())
            .as("the initialization segment of %s", pair.get("replacement").asString())
            .isTrue();
      }
    }

    softly.assertAll();
  }

  @Test
  @DisplayName(
      "Should hold every extended recording at every default-ladder rung, with its replacement pair"
          + " and its GOP restart, when the worker marks an NVENC encoder keyframe-verified")
  void
      shouldHoldEveryExtendedRecordingAtEveryDefaultLadderRungWithItsReplacementPairAndItsGopRestartWhenTheWorkerMarksAnNvencEncoderKeyframeVerified() {
    var required =
        NVENC_ENCODERS.stream()
            .filter(FfmpegCommandBuilder::isKeyframeVerified)
            .flatMap(encoder -> DEFAULT_LADDER_RUNGS.stream().map(rung -> encoder + "-" + rung))
            .toList();

    assertThat(required).as("the keyframe-verified NVENC encoders' rungs").isNotEmpty();
    assertHoldsTheExtendedRecordingsOf(required);
  }

  @Test
  @DisplayName(
      "Should hold the extended recordings at 1080p of every NVENC preset on the preset scale when"
          + " the worker passes a keyframe-verified NVENC encoder the scale's preset")
  void
      shouldHoldTheExtendedRecordingsAt1080pOfEveryNvencPresetOnThePresetScaleWhenTheWorkerPassesAKeyframeVerifiedNvencEncoderTheScalesPreset() {
    // Every recording at a rung takes Automatic's preset, so its prefix names no preset.
    var automatic = EncoderPreset.FASTER.nvencPreset();
    var required =
        NVENC_ENCODERS.stream()
            .filter(FfmpegCommandBuilder::isKeyframeVerified)
            .flatMap(
                encoder ->
                    Arrays.stream(EncoderPreset.values())
                        .map(EncoderPreset::nvencPreset)
                        .distinct()
                        .map(
                            preset ->
                                preset.equals(automatic)
                                    ? encoder + "-1080p"
                                    : encoder + "-1080p-" + preset))
            .toList();

    assertThat(required).as("the keyframe-verified NVENC encoders' presets").hasSize(12);
    assertHoldsTheExtendedRecordingsOf(required);
  }

  @Test
  @DisplayName(
      "Should hold the Main 10 extended recordings at every default-ladder rung of each encode"
          + " feature's range when the worker tests an NVENC encoder for that feature")
  void
      shouldHoldTheMain10ExtendedRecordingsAtEveryDefaultLadderRungOfEachEncodeFeaturesRangeWhenTheWorkerTestsAnNvencEncoderForThatFeature() {
    var required =
        NVENC_ENCODERS.stream()
            .flatMap(
                encoder ->
                    FfmpegCommandBuilder.testedEncodeFeatures(encoder).stream()
                        .map(NvencRecordingsTest::recordedRangeOf)
                        .distinct()
                        .flatMap(
                            range ->
                                DEFAULT_LADDER_RUNGS.stream()
                                    .map(rung -> encoder + "-" + rung + "-" + range)))
            .toList();

    assertThat(required).as("the tested NVENC encoders' rungs and ranges").hasSize(12);
    assertHoldsTheExtendedRecordingsOf(required);
  }

  @Test
  @DisplayName(
      "Should declare 10 bits and no sample with static HDR metadata in every Main 10 recording"
          + " when its source carries a mastering display and a content light level")
  void
      shouldDeclareTenBitsAndNoSampleWithStaticHdrMetadataInEveryMain10RecordingWhenItsSourceCarriesAMasteringDisplayAndAContentLightLevel() {
    var tenBit = new ArrayList<JsonNode>();
    for (var recording : committedRecordings().get("recordings")) {
      if (recording.get("plan").get("bitDepth").asInt() == 10) {
        tenBit.add(recording);
      }
    }

    assertThat(tenBit).as("the Main 10 recordings").isNotEmpty();
    var softly = new SoftAssertions();
    for (var recording : tenBit) {
      var name = recording.get("name").asString();
      softly
          .assertThat(
              recording.get("initializationSegment").get("pictureFormat").get("bitDepth").asInt())
          .as("the bit depth that %s declares", name)
          .isEqualTo(10);
      softly
          .assertThat(recording.get("facts").get("staticHdrMetadataSamples").asInt())
          .as("the samples of %s that carry static HDR metadata", name)
          .isZero();
    }

    softly.assertAll();
  }

  // The range whose recordings show the encoder's keyframes for the feature. The reshape of Dolby
  // Vision into PQ runs before the encoder, which takes the arguments of a PQ encode.
  private static String recordedRangeOf(EncodeFeature feature) {
    return switch (feature) {
      case TEN_BIT -> "sdr10";
      case PQ, DOLBY_VISION_TO_PQ -> "pq";
      case HLG -> "hlg";
    };
  }

  // Each prefix needs its extended recording from the start, its replacement pair and its
  // missed-keyframe recording's GOP restart.
  private static void assertHoldsTheExtendedRecordingsOf(List<String> prefixes) {
    var document = committedRecordings();
    var recorded = new ArrayList<String>();
    var withGopRestart = new ArrayList<String>();
    for (var recording : document.get("recordings")) {
      recorded.add(recording.get("name").asString());
      if (!recording.get("verdicts").get("gopRestart").isNull()) {
        withGopRestart.add(recording.get("name").asString());
      }
    }

    var paired = new ArrayList<String>();
    document
        .get("initializationSegmentPairs")
        .forEach(pair -> paired.add(pair.get("replacement").asString()));
    assertThat(recorded)
        .containsAll(prefixes.stream().map(prefix -> prefix + "-extended-first").toList());
    assertThat(paired)
        .containsAll(prefixes.stream().map(prefix -> prefix + "-extended-replacement").toList());
    assertThat(withGopRestart)
        .containsAll(prefixes.stream().map(prefix -> prefix + "-extended-missed").toList());
  }

  private static JsonNode committedRecordings() {
    var results = RESULTS.resolve("recordings.json");
    assertThat(results)
        .as("the recordings of record-nvenc.sh, which a keyframe-verified NVENC encoder needs")
        .exists();
    return JsonMapper.builder().build().readTree(results.toFile());
  }

  private static boolean passesTheWorkersCheck(JsonNode recording) throws IOException {
    var bytes =
        Files.readAllBytes(
            RESULTS.resolve(recording.get("initializationSegment").get("file").asString()));
    var decision = encodeDecidedFor(recording.get("plan"));
    try {
      // The producer checks the initialization segment that it delivers, after its rewrite.
      VideoOutputCheck.requireMatch(
          decision,
          InitializationSegmentRewrite.rewrite(decision, new InitializationSegment(bytes)));
      return true;
    } catch (FragmentedMp4Exception _) {
      return false;
    }
  }

  private static VideoDecision.Encode encodeDecidedFor(JsonNode plan) {
    var tier = Optional.<VideoCodecTier>empty();
    if (!plan.get("tier").isNull()) {
      tier =
          Optional.of(VideoCodecTier.valueOf(plan.get("tier").asString().toUpperCase(Locale.ROOT)));
    }

    return NvencRecordedEncodes.encodeOf(
            plan.get("encoder").asString(), plan.get("range").asString())
        .tier(tier)
        .level(plan.get("level").asInt())
        .codecString(plan.get("expectedCodecString").asString())
        .build();
  }
}
