package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import build.buf.gen.streamarr.transcode.v1.ProbeAttemptResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Probe Attempt Report Tests")
class ProbeAttemptReportTest {

  @Test
  @DisplayName("Should reject the report when it has no keyframe positions list")
  void shouldRejectReportWhenItHasNoKeyframePositionsList() {
    var builder = ProbeAttemptReport.builder().result(ProbeAttemptResult.getDefaultInstance());

    assertThatNullPointerException().isThrownBy(builder::build);
  }

  @Test
  @DisplayName("Should reject the report when it has no result")
  void shouldRejectReportWhenItHasNoResult() {
    var builder = ProbeAttemptReport.builder().keyframePositionsParts(List.of());

    assertThatNullPointerException().isThrownBy(builder::build);
  }

  @Test
  @DisplayName("Should reject a result-only report when it has no result")
  void shouldRejectResultOnlyReportWhenItHasNoResult() {
    assertThatNullPointerException().isThrownBy(() -> ProbeAttemptReport.resultOnly(null));
  }
}
