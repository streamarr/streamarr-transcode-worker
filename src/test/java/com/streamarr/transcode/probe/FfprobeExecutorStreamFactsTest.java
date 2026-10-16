package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.probeFixture;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Stream Facts Tests")
class FfprobeExecutorStreamFactsTest {

  @Nested
  @DisplayName("Chosen Video Stream")
  class ChosenVideoStreamTests {

    @Test
    @DisplayName(
        "Should choose the video stream after the cover art when the cover art comes first")
    void shouldChooseTheVideoStreamAfterTheCoverArtWhenTheCoverArtComesFirst() {
      var media = probeFixture("mp4-cover-art-first.mp4");

      assertThat(media.getChosenVideoStreamIndex()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should choose the first video stream when no cover art precedes it")
    void shouldChooseTheFirstVideoStreamWhenNoCoverArtPrecedesIt() {
      var media = probeFixture("mp4-titled.mp4");

      assertThat(media.hasChosenVideoStreamIndex()).isTrue();
      assertThat(media.getChosenVideoStreamIndex()).isZero();
    }
  }
}
