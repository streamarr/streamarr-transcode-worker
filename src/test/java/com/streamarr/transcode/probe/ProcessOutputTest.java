package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Process Output Tests")
class ProcessOutputTest {

  @Test
  @DisplayName("Should reject the output when it has no standard output bytes")
  void shouldRejectOutputWhenItHasNoStandardOutputBytes() {
    assertThatNullPointerException().isThrownBy(() -> new ProcessOutput(null, 0));
  }
}
