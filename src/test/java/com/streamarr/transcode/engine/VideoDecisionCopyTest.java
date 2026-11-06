package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.streamarr.transcode.engine.PlannedCopyOutput.DolbyVisionOutput;
import com.streamarr.transcode.engine.PlannedCopyOutput.SampleEntry;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Video Decision Copy Tests")
class VideoDecisionCopyTest {

  // The record that a normalized copy writes comes only from the planned Dolby Vision output.
  @Test
  @DisplayName(
      "Should refuse a copy that normalizes its Dolby Vision record when it plans no Dolby Vision"
          + " output")
  void shouldRefuseACopyThatNormalizesItsRecordWhenItPlansNoDolbyVisionOutput() {
    var builder =
        VideoDecisionFixtures.copyBuilder()
            .codecString("hvc1.2.4.L153.90")
            .plannedOutput(Optional.of(PlannedCopyOutputs.pqOutputBuilder().build()))
            .bitstreamRewrites(Set.of(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD));

    assertThatIllegalArgumentException().isThrownBy(builder::build);
  }

  // The conversion writes profile 8.1 RPUs, so the record that describes them is the normalized
  // profile 8.1 record and nothing else (ADR 0036).
  @ParameterizedTest(name = "{0}")
  @MethodSource("conversionsWithoutANormalizedProfile81Output")
  @DisplayName(
      "Should refuse a copy that converts profile 7 to profile 8.1 when it does not plan a"
          + " normalized profile 8.1 output")
  void shouldRefuseACopyThatConvertsProfile7ToProfile81WhenItDoesNotPlanANormalizedProfile81Output(
      String description, VideoDecision.Copy.CopyBuilder builder) {
    assertThatIllegalArgumentException().isThrownBy(builder::build);
  }

  static Stream<Arguments> conversionsWithoutANormalizedProfile81Output() {
    var profile5 =
        DolbyVisionOutput.builder()
            .profile(5)
            .level(6)
            .baseLayerCompatibilityId(0)
            .sampleEntry(SampleEntry.DVH1)
            .build();
    var profile8OnSdr =
        DolbyVisionOutput.builder()
            .profile(8)
            .level(6)
            .baseLayerCompatibilityId(2)
            .sampleEntry(SampleEntry.HVC1)
            .build();
    return Stream.of(
        Arguments.of(
            "no planned output",
            conversion(BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1)),
        Arguments.of(
            "a record kept as the source declares it",
            conversion(BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1)
                .plannedOutput(outputWith(PlannedCopyOutputs.profile81Output()))),
        Arguments.of(
            "a profile 5 output",
            conversion(
                    BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1,
                    BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD)
                .plannedOutput(outputWith(profile5))),
        Arguments.of(
            "a profile 8 output on an SDR base",
            conversion(
                    BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1,
                    BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD)
                .plannedOutput(outputWith(profile8OnSdr))));
  }

  @Test
  @DisplayName("Should list the conversion when the copy plans a normalized profile 8.1 output")
  void shouldListTheConversionWhenTheCopyPlansANormalizedProfile81Output() {
    var copy =
        conversion(
                BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1,
                BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD)
            .plannedOutput(outputWith(PlannedCopyOutputs.profile81Output()))
            .build();

    assertThat(copy.lists(BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1)).isTrue();
  }

  private static VideoDecision.Copy.CopyBuilder conversion(BitstreamRewrite... rewrites) {
    return VideoDecisionFixtures.copyBuilder()
        .codecString("hvc1.2.4.L153.90")
        .bitstreamRewrites(Set.of(rewrites));
  }

  private static Optional<PlannedCopyOutput> outputWith(DolbyVisionOutput dolbyVision) {
    return Optional.of(
        PlannedCopyOutputs.pqOutputBuilder().dolbyVision(Optional.of(dolbyVision)).build());
  }
}
