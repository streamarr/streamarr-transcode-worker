package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.transcode.probe.KeyframeIndex.Listed;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Keyframe Index Tests")
class KeyframeIndexTest {

  @Test
  @DisplayName("Should reject a listed index when it has no time base")
  void shouldRejectListedIndexWhenItHasNoTimeBase() {
    assertThatNullPointerException().isThrownBy(() -> new Listed(null, List.of()));
  }

  @Test
  @DisplayName("Should reject a listed index when it has no keyframe list")
  void shouldRejectListedIndexWhenItHasNoKeyframeList() {
    assertThatNullPointerException().isThrownBy(() -> new Listed(new TimeBase(1, 1_000), null));
  }
}
