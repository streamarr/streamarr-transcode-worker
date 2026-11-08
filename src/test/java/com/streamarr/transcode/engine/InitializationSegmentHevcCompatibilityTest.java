package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.streamarr.transcode.fixtures.TopLevelBoxes;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
class InitializationSegmentHevcCompatibilityTest {

  @Test
  @DisplayName(
      "Should declare Main 10 compatibility in the record and parameter sets when encoding Main")
  void shouldDeclareMain10CompatibilityInTheRecordAndParameterSetsWhenEncodingMain()
      throws IOException {
    var decision =
        VideoDecisionFixtures.encodeBuilder()
            .codecProfile(VideoCodecProfile.HEVC_MAIN)
            .tier(Optional.of(VideoCodecTier.MAIN))
            .level(30)
            .codecString("hvc1.1.6.L90.90")
            .build();
    var rewritten = InitializationSegmentRewrite.rewrite(decision, recordedInitialization());
    var record = recordOf(rewritten);
    assertThat(record.getInt(2)).isEqualTo(0x60000000);
    record.position(22);
    var arrays = Byte.toUnsignedInt(record.get());
    for (var array = 0; array < arrays; array++) {
      var type = record.get() & 0x3F;
      var count = Short.toUnsignedInt(record.getShort());
      for (var unit = 0; unit < count; unit++) {
        var size = Short.toUnsignedInt(record.getShort());
        assertParameterCompatibility(type, record.slice(record.position(), size));
        record.position(record.position() + size);
      }
    }
    assertThat(record.hasRemaining()).isFalse();
  }

  @Test
  @DisplayName("Should preserve the source compatibility when copying Main video")
  void shouldPreserveTheSourceCompatibilityWhenCopyingMainVideo() throws IOException {
    var original = recordedInitialization();
    var rewritten =
        InitializationSegmentRewrite.rewrite(
            VideoDecisionFixtures.copyOf("hvc1.1.2.L90.90"), original);
    assertThat(rewritten.bytes()).isEqualTo(original.bytes());
  }

  @Test
  @DisplayName(
      "Should preserve canonical signaling when the encode already declares Main 10 compatibility")
  void shouldPreserveCanonicalSignalingWhenTheEncodeAlreadyDeclaresMain10Compatibility()
      throws IOException {
    var once = InitializationSegmentRewrite.rewrite(mainEncode(), recordedInitialization());
    assertThat(InitializationSegmentRewrite.rewrite(mainEncode(), once).bytes())
        .isEqualTo(once.bytes());
  }

  @Test
  @DisplayName(
      "Should deliver the same configuration when only the VPS already declares Main 10 compatibility")
  void shouldDeliverTheSameConfigurationWhenOnlyTheVpsAlreadyDeclaresMain10Compatibility()
      throws IOException {
    var expected = InitializationSegmentRewrite.rewrite(mainEncode(), recordedInitialization());
    var mixed = recordedInitialization();
    recordOf(mixed).put(35, (byte) 0x60);
    assertThat(InitializationSegmentRewrite.rewrite(mainEncode(), mixed).bytes())
        .isEqualTo(expected.bytes());
  }

  @ParameterizedTest
  @CsvSource({"1,2", "1,33", "12,93", "6,176", "16,254", "17,249", "18,249", "2,16"})
  @DisplayName(
      "Should reject a genuine output mismatch when profile tier level constraints or picture format differ")
  void shouldRejectAGenuineOutputMismatchWhenProfileTierLevelConstraintsOrPictureFormatDiffer(
      int offset, int value) throws IOException {
    var original = recordedInitialization();
    recordOf(original).put(offset, (byte) value);
    var rewritten = InitializationSegmentRewrite.rewrite(mainEncode(), original);
    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> VideoOutputCheck.requireMatch(mainEncode(), rewritten))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(FragmentedMp4Exception.Reason.OUTPUT_MISMATCH);
  }

  @ParameterizedTest
  @CsvSource({"28,66", "34,2", "35,16", "27,1", "27,2"})
  @DisplayName("Should refuse normalization when a parameter set cannot declare Main compatibility")
  void shouldRefuseNormalizationWhenAParameterSetCannotDeclareMainCompatibility(
      int offset, int value) throws IOException {
    var original = recordedInitialization();
    // The recording's first array is one VPS, beginning after the 23-byte header and array fields.
    recordOf(original).put(offset, (byte) value);
    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> InitializationSegmentRewrite.rewrite(mainEncode(), original))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(FragmentedMp4Exception.Reason.OUTPUT_MISMATCH);
  }

  private static VideoDecision.Encode mainEncode() {
    return VideoDecisionFixtures.encodeBuilder()
        .codecProfile(VideoCodecProfile.HEVC_MAIN)
        .tier(Optional.of(VideoCodecTier.MAIN))
        .level(30)
        .codecString("hvc1.1.6.L90.90")
        .build();
  }

  private static void assertParameterCompatibility(int type, ByteBuffer unit) {
    if (type != 32 && type != 33) {
      return;
    }
    var rbsp =
        ByteBuffer.wrap(HevcEmulationPrevention.unescaped(unit.slice(2, unit.remaining() - 2)));
    assertThat(rbsp.getInt(type == 32 ? 5 : 2)).isEqualTo(0x60000000);
  }

  private static ByteBuffer recordOf(InitializationSegment segment) {
    return CodecConfigurationReader.firstVideoSampleEntryOf(segment)
        .orElseThrow()
        .after(CodecConfigurationReader.VISUAL_SAMPLE_ENTRY_BYTES)
        .requiredChild("hvcC")
        .body();
  }

  private static InitializationSegment recordedInitialization() throws IOException {
    try (var resource =
        InitializationSegmentHevcCompatibilityTest.class.getResourceAsStream(
            "/hevc-compatibility/a2000-main.fmp4")) {
      assertThat(resource).isNotNull();
      var recording = resource.readAllBytes();
      var end =
          TopLevelBoxes.of(recording).stream()
              .filter(box -> box.type().equals("moov"))
              .findFirst()
              .orElseThrow()
              .end();
      return new InitializationSegment(Arrays.copyOf(recording, end));
    }
  }
}
