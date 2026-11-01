package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.InitializationSegmentFixtures.initializationSegment;
import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Video Output Check Tests")
class VideoOutputCheckTest {

  @Test
  @DisplayName(
      "Should accept an encode when its initialization segment declares deinterlaced video")
  void shouldAcceptAnEncodeWhenItsInitializationSegmentDeclaresDeinterlacedVideo() {
    var encode = VideoDecisionFixtures.encodeBuilder().codecString("hvc1.1.6.L123.90").build();
    var segment = initializationSegment("libx265-1080i-deinterlaced.mp4");

    assertThatNoException().isThrownBy(() -> VideoOutputCheck.requireMatch(encode, segment));
  }

  // The server never copies an interlaced source, and a copy's sample entry repeats what the
  // source declared, so only an encode must declare progressive video.
  @Test
  @DisplayName("Should accept a copy when its initialization segment declares interlaced video")
  void shouldAcceptACopyWhenItsInitializationSegmentDeclaresInterlacedVideo() {
    var copy = VideoDecisionFixtures.copyOf("hvc1.1.6.L120.90");
    var segment = initializationSegment("libx265-1080i-woven.mp4");

    assertThatNoException().isThrownBy(() -> VideoOutputCheck.requireMatch(copy, segment));
  }
}
