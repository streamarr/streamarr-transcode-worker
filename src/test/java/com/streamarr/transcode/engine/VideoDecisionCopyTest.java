package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
}
