package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Encoder Preference Tests")
class EncoderPreferenceTest {

  @ParameterizedTest(name = "shuffled with seed {0}")
  @ValueSource(longs = {1, 2, 3, 4, 5})
  @DisplayName(
      "Should put keyframe-verified hardware encoders first, other hardware encoders next and the"
          + " software encoder last when one codec family has several usable encoders")
  void
      shouldPutKeyframeVerifiedHardwareEncodersFirstOtherHardwareEncodersNextAndTheSoftwareEncoderLastWhenOneCodecFamilyHasSeveralUsableEncoders(
          long seed) {
    var preferred =
        List.of(
            hardware("h264_qsv", true),
            hardware("h264_vaapi", true),
            hardware("h264_nvenc", false),
            hardware("h264_amf", false),
            software("libx264"));
    var shuffled = new ArrayList<>(preferred);
    Collections.shuffle(shuffled, new Random(seed));

    shuffled.sort(EncoderPreference.ORDER);

    assertThat(shuffled).containsExactlyElementsOf(preferred);
  }

  @Test
  @DisplayName(
      "Should prefer NVENC, then QSV, then AMF, then VideoToolbox, then VAAPI when the hardware"
          + " encoders are equally verified")
  void
      shouldPreferNvencThenQsvThenAmfThenVideoToolboxThenVaapiWhenTheHardwareEncodersAreEquallyVerified() {
    var preferred =
        List.of(
            hardware("hevc_nvenc", false),
            hardware("hevc_qsv", false),
            hardware("hevc_amf", false),
            hardware("hevc_videotoolbox", false),
            hardware("hevc_vaapi", false));
    var reversed = new ArrayList<>(preferred.reversed());

    reversed.sort(EncoderPreference.ORDER);

    assertThat(reversed).containsExactlyElementsOf(preferred);
  }

  @Test
  @DisplayName("Should rank a hardware encoder after VAAPI when the order does not name its API")
  void shouldRankAHardwareEncoderAfterVaapiWhenTheOrderDoesNotNameItsApi() {
    var encoders =
        new ArrayList<>(List.of(hardware("h264_rkmpp", false), hardware("h264_vaapi", false)));

    encoders.sort(EncoderPreference.ORDER);

    assertThat(encoders)
        .extracting(encoder -> encoder.backend().encoder())
        .containsExactly("h264_vaapi", "h264_rkmpp");
  }

  @Test
  @DisplayName(
      "Should prefer a hardware encoder that is not keyframe-verified when the software encoder is")
  void shouldPreferAHardwareEncoderThatIsNotKeyframeVerifiedWhenTheSoftwareEncoderIs() {
    var encoders = new ArrayList<>(List.of(software("libx264"), hardware("h264_nvenc", false)));

    encoders.sort(EncoderPreference.ORDER);

    assertThat(encoders.getFirst().backend().encoder()).isEqualTo("h264_nvenc");
  }

  private static WorkerVerifiedEncoder hardware(String encoder, boolean keyframeVerified) {
    return WorkerVerifiedEncoder.builder()
        .backend(
            EncoderBackend.builder()
                .codecFamily(CodecFamily.ofEncoder(encoder).orElseThrow())
                .encoder(encoder)
                .hardware(true)
                .build())
        .keyframeVerified(keyframeVerified)
        .build();
  }

  private static WorkerVerifiedEncoder software(String encoder) {
    return WorkerVerifiedEncoder.builder()
        .backend(
            EncoderBackend.builder()
                .codecFamily(CodecFamily.ofEncoder(encoder).orElseThrow())
                .encoder(encoder)
                .hardware(false)
                .build())
        .keyframeVerified(true)
        .build();
  }
}
