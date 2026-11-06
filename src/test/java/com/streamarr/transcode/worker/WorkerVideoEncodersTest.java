package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.VideoEncodeFeature;
import com.streamarr.transcode.engine.CodecFamily;
import com.streamarr.transcode.engine.EncodeFeature;
import com.streamarr.transcode.engine.EncoderBackend;
import com.streamarr.transcode.engine.WorkerVerifiedEncoder;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Worker Video Encoders Tests")
class WorkerVideoEncodersTest {

  @Test
  @DisplayName(
      "Should advertise each encode feature that the encoder passed when the worker registers")
  void shouldAdvertiseEachEncodeFeatureThatTheEncoderPassedWhenTheWorkerRegisters() {
    var encoder =
        WorkerVerifiedEncoder.builder()
            .backend(
                EncoderBackend.builder()
                    .codecFamily(CodecFamily.HEVC)
                    .encoder("libx265")
                    .hardware(false)
                    .build())
            .keyframeVerified(true)
            .encodeFeatures(Set.of(EncodeFeature.values()))
            .build();

    var advertised = WorkerVideoEncoders.toProto(encoder);

    assertThat(advertised.getEncodeFeaturesList())
        .containsExactly(
            VideoEncodeFeature.VIDEO_ENCODE_FEATURE_TEN_BIT,
            VideoEncodeFeature.VIDEO_ENCODE_FEATURE_PQ,
            VideoEncodeFeature.VIDEO_ENCODE_FEATURE_HLG,
            VideoEncodeFeature.VIDEO_ENCODE_FEATURE_DOLBY_VISION_TO_PQ);
  }

  @Test
  @DisplayName("Should advertise no encode feature when the encoder passed only the 8-bit SDR test")
  void shouldAdvertiseNoEncodeFeatureWhenTheEncoderPassedOnlyThe8BitSdrTest() {
    var encoder =
        WorkerVerifiedEncoder.builder()
            .backend(
                EncoderBackend.builder()
                    .codecFamily(CodecFamily.H264)
                    .encoder("libx264")
                    .hardware(false)
                    .build())
            .keyframeVerified(true)
            .build();

    var advertised = WorkerVideoEncoders.toProto(encoder);

    assertThat(advertised.getEncodeFeaturesList()).isEmpty();
  }
}
