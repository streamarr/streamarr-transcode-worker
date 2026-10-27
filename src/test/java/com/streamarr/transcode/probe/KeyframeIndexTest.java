package com.streamarr.transcode.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.streamarr.transcode.probe.KeyframeIndex.KeyframeTime;
import com.streamarr.transcode.probe.KeyframeIndex.Listed;
import com.streamarr.transcode.probe.KeyframeIndex.TimeBase;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("Keyframe Index Tests")
class KeyframeIndexTest {

  private static final KeyframeTime PREVIOUS = new KeyframeTime(4_004, 4_004);

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

  @Test
  @DisplayName("Should place a keyframe after another when both of its times are greater")
  void shouldPlaceKeyframeAfterAnotherWhenBothOfItsTimesAreGreater() {
    assertThat(new KeyframeTime(6_006, 6_006).isAfter(PREVIOUS)).isTrue();
  }

  @ParameterizedTest
  @CsvSource({"4004, 4004", "6006, 4004", "4004, 6006", "6006, 2002", "2002, 6006"})
  @DisplayName("Should not place a keyframe after another when either of its times is not greater")
  void shouldNotPlaceKeyframeAfterAnotherWhenEitherOfItsTimesIsNotGreater(
      long presentationTime, long decodeTime) {
    assertThat(new KeyframeTime(presentationTime, decodeTime).isAfter(PREVIOUS)).isFalse();
  }
}
