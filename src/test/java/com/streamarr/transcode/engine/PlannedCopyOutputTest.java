package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Planned Copy Output Tests")
class PlannedCopyOutputTest {

  // ADR 0036: a planned output never carries an UNSPECIFIED value.
  @Test
  @DisplayName("Should refuse a planned output when its compatible base is unspecified")
  void shouldRefuseAPlannedOutputWhenItsCompatibleBaseIsUnspecified() {
    var builder = PlannedCopyOutput.builder().dynamicRange(VideoDynamicRange.unspecified());

    assertThatIllegalArgumentException().isThrownBy(builder::build);
  }
}
