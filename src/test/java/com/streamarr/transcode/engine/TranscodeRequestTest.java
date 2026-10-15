package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

@Tag("UnitTest")
@DisplayName("Transcode Request Tests")
class TranscodeRequestTest {

  private static TranscodeRequest.TranscodeRequestBuilder request(TranscodeMode mode) {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(Path.of("/media/movie.mkv"))
        .transcodeDecision(TranscodeDecision.builder().transcodeMode(mode).build());
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"VIDEO_TRANSCODE", "FULL_TRANSCODE"})
  @DisplayName("Should reject the request when a video encode has no encoder preset")
  void shouldRejectRequestWhenVideoEncodeHasNoEncoderPreset(TranscodeMode mode) {
    var request = request(mode);

    assertThatIllegalArgumentException()
        .isThrownBy(request::build)
        .withMessageContaining("encoder preset");
  }

  @ParameterizedTest
  @EnumSource(
      value = TranscodeMode.class,
      names = {"REMUX", "AUDIO_TRANSCODE"})
  @DisplayName("Should accept the request when a video copy has no encoder preset")
  void shouldAcceptRequestWhenVideoCopyHasNoEncoderPreset(TranscodeMode mode) {
    var request = request(mode).build();

    assertThat(request.transcodeDecision().transcodeMode()).isEqualTo(mode);
  }

  @Test
  @DisplayName("Should carry no seek position when the request is built without one")
  void shouldCarryNoSeekPositionWhenTheRequestIsBuiltWithoutOne() {
    var request = request(TranscodeMode.REMUX).build();

    assertThat(request.seekPosition()).isEmpty();
  }

  @Test
  @DisplayName("Should reject the request when its seek position is null")
  void shouldRejectTheRequestWhenItsSeekPositionIsNull() {
    var builder = request(TranscodeMode.REMUX);

    assertThatNullPointerException()
        .isThrownBy(() -> builder.seekPosition(null))
        .withMessageContaining("seekPosition");
  }
}
