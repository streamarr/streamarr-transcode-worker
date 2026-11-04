package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.InitializationSegmentFixtures.initializationSegment;
import static com.streamarr.transcode.engine.IsoBoxes.box;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Video Output Check Tests")
class VideoOutputCheckTest {

  // Main at level 3.0, 8-bit 4:2:0, with one VPS, SPS and PPS in the record.
  private static final String SYNTHETIC_HEVC_CODEC_STRING = "hvc1.1.6.L90.90";
  private static final String HVCC =
      "0101600000009000000000005AF000FCFDF8F800000F"
          + "03"
          + "A0000100024001"
          + "A1000100024201"
          + "A2000100024401";

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

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"dvcC", "dvvC", "dvwC"})
  @DisplayName(
      "Should refuse the copy as an output mismatch when it strips Dolby Vision and its sample"
          + " entry still holds a Dolby Vision configuration record")
  void shouldRefuseTheCopyWhenItStripsDolbyVisionAndItsSampleEntryStillHoldsARecord(
      String recordType) {
    var copy = copyStrippingDolbyVision();
    var segment = hevcSegmentWith(box(recordType, new byte[24]));

    assertThatThrownBy(() -> VideoOutputCheck.requireMatch(copy, segment))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.OUTPUT_MISMATCH));
  }

  @Test
  @DisplayName(
      "Should accept the copy when it strips Dolby Vision and its sample entry holds no Dolby"
          + " Vision configuration record")
  void shouldAcceptTheCopyWhenItStripsDolbyVisionAndItsSampleEntryHoldsNoRecord() {
    var copy = copyStrippingDolbyVision();
    var segment = hevcSegmentWith(new byte[0]);

    assertThatNoException().isThrownBy(() -> VideoOutputCheck.requireMatch(copy, segment));
  }

  @Test
  @DisplayName(
      "Should accept a copy that keeps Dolby Vision when its sample entry holds the configuration"
          + " record")
  void shouldAcceptACopyThatKeepsDolbyVisionWhenItsSampleEntryHoldsTheRecord() {
    var copy = VideoDecisionFixtures.copyOf(SYNTHETIC_HEVC_CODEC_STRING);
    var segment = hevcSegmentWith(box("dvcC", new byte[24]));

    assertThatNoException().isThrownBy(() -> VideoOutputCheck.requireMatch(copy, segment));
  }

  private static VideoDecision.Copy copyStrippingDolbyVision() {
    return VideoDecisionFixtures.copyBuilder()
        .codecString(SYNTHETIC_HEVC_CODEC_STRING)
        .bitstreamRewrites(Set.of(BitstreamRewrite.STRIP_DOLBY_VISION))
        .build();
  }

  // An initialization segment whose hvc1 sample entry holds the record and then the given boxes.
  private static InitializationSegment hevcSegmentWith(byte[] boxesAfterTheRecord) {
    var sampleEntry =
        box("hvc1", new byte[78], box("hvcC", HexFormat.of().parseHex(HVCC)), boxesAfterTheRecord);
    return new InitializationSegment(HevcCopies.initializationSegment(sampleEntry));
  }
}
