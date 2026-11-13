package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.SampleEntryBoxes.bodiesOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.fixtures.TopLevelBoxes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Initialization Segment Static HDR Metadata Tests")
class InitializationSegmentStaticHdrMetadataTest {

  private static final Path NVENC_RECORDINGS = Path.of("src/test/resources/nvenc");

  // ISO/IEC 14496-12 12.1.6: the green, blue and red primaries and the white point as x and y in
  // units of 0.00002, then the maximum and the minimum luminance in units of 0.0001 cd/m².
  private static final StaticHdrMetadata JOB_METADATA =
      new StaticHdrMetadata(
          PlannedCopyOutput.MasteringDisplay.builder()
              .red(new PlannedCopyOutput.Chromaticity(34000, 16000))
              .green(new PlannedCopyOutput.Chromaticity(13250, 34500))
              .blue(new PlannedCopyOutput.Chromaticity(7500, 3000))
              .whitePoint(new PlannedCopyOutput.Chromaticity(15635, 16450))
              .maxLuminance(10_000_000)
              .minLuminance(50)
              .build(),
          new PlannedCopyOutput.ContentLightLevel(1000, 400));
  private static final String JOB_MDCV = "33C286C41D4C0BB884D03E803D1340420098968000000032";
  // ISO/IEC 14496-12 12.1.5: MaxCLL and then MaxFALL, in cd/m².
  private static final String JOB_CLLI = "03E80190";

  private static final StaticHdrMetadata OTHER_METADATA =
      new StaticHdrMetadata(
          PlannedCopyOutput.MasteringDisplay.builder()
              .red(new PlannedCopyOutput.Chromaticity(35400, 14600))
              .green(new PlannedCopyOutput.Chromaticity(8500, 39850))
              .blue(new PlannedCopyOutput.Chromaticity(6550, 2300))
              .whitePoint(new PlannedCopyOutput.Chromaticity(15635, 16450))
              .maxLuminance(40_000_000)
              .minLuminance(100)
              .build(),
          new PlannedCopyOutput.ContentLightLevel(4000, 1000));
  private static final String OTHER_MDCV = "21349BAA199608FC8A4839083D13404202625A0000000064";
  private static final String OTHER_CLLI = "0FA003E8";

  @Test
  @DisplayName(
      "Should hold one mastering display box and one content light level box with the job's values"
          + " in the HEVC sample entry when an encode's output is PQ")
  void shouldHoldTheJobsStaticHdrMetadataBoxesInTheHevcSampleEntryWhenAnEncodesOutputIsPq()
      throws IOException {
    var rewritten =
        InitializationSegmentRewrite.rewrite(
            pqEncode(JOB_METADATA), recorded("hevc_nvenc-1080p-pq-extended-first"));

    assertThat(bodiesOf(rewritten, "mdcv")).containsExactly(JOB_MDCV);
    assertThat(bodiesOf(rewritten, "clli")).containsExactly(JOB_CLLI);
  }

  @Test
  @DisplayName(
      "Should hold the job's static HDR metadata boxes in the AV1 sample entry when an AV1"
          + " encode's output is PQ")
  void shouldHoldTheJobsStaticHdrMetadataBoxesInTheAv1SampleEntryWhenAnAv1EncodesOutputIsPq()
      throws IOException {
    var encode =
        hdrEncode(VideoDynamicRange.CompatibleBase.PQ)
            .codecProfile(VideoCodecProfile.AV1_MAIN)
            .level(40)
            .codecString("av01.0.08M.10")
            .staticHdrMetadata(Optional.of(JOB_METADATA))
            .build();

    var rewritten = InitializationSegmentRewrite.rewrite(encode, recordedAv1());

    assertThat(bodiesOf(rewritten, "av1C")).hasSize(1);
    assertThat(bodiesOf(rewritten, "mdcv")).containsExactly(JOB_MDCV);
    assertThat(bodiesOf(rewritten, "clli")).containsExactly(JOB_CLLI);
  }

  @Test
  @DisplayName(
      "Should replace the static HDR metadata boxes with the job's values when the segment that"
          + " FFmpeg wrote already holds boxes with other values")
  void shouldReplaceTheStaticHdrMetadataBoxesWithTheJobsValuesWhenTheSegmentHoldsOtherValues()
      throws IOException {
    var holdingOtherValues = holdingBoxesOf(OTHER_METADATA);

    var rewritten =
        InitializationSegmentRewrite.rewrite(pqEncode(JOB_METADATA), holdingOtherValues);

    assertThat(bodiesOf(holdingOtherValues, "mdcv")).containsExactly(OTHER_MDCV);
    assertThat(bodiesOf(holdingOtherValues, "clli")).containsExactly(OTHER_CLLI);
    assertThat(bodiesOf(rewritten, "mdcv")).containsExactly(JOB_MDCV);
    assertThat(bodiesOf(rewritten, "clli")).containsExactly(JOB_CLLI);
  }

  @ParameterizedTest
  @ValueSource(strings = {"hlg", "sdr10"})
  @DisplayName(
      "Should hold no static HDR metadata box when an encode's output is HLG or SDR, also when the"
          + " segment that FFmpeg wrote holds them")
  void shouldHoldNoStaticHdrMetadataBoxWhenAnEncodesOutputIsHlgOrSdr(String range)
      throws IOException {
    var encode = recordedEncode(range).build();

    var rewritten =
        InitializationSegmentRewrite.rewrite(
            encode, recorded("hevc_nvenc-1080p-" + range + "-extended-first"));
    var rewrittenFromBoxes =
        InitializationSegmentRewrite.rewrite(encode, holdingBoxesOf(OTHER_METADATA));

    assertThat(bodiesOf(rewritten, "mdcv")).isEmpty();
    assertThat(bodiesOf(rewritten, "clli")).isEmpty();
    assertThat(bodiesOf(rewrittenFromBoxes, "mdcv")).isEmpty();
    assertThat(bodiesOf(rewrittenFromBoxes, "clli")).isEmpty();
  }

  @Test
  @DisplayName(
      "Should deliver the same bytes when a first attempt and its replacement write the same"
          + " segment for a PQ job")
  void shouldDeliverTheSameBytesWhenAFirstAttemptAndItsReplacementEncodeTheSamePqJob()
      throws IOException {
    var first =
        InitializationSegmentRewrite.rewrite(
            pqEncode(JOB_METADATA), recorded("hevc_nvenc-1080p-pq-extended-first"));
    var replacement =
        InitializationSegmentRewrite.rewrite(
            pqEncode(JOB_METADATA), recorded("hevc_nvenc-1080p-pq-extended-replacement"));

    assertThat(bodiesOf(replacement, "mdcv")).containsExactly(JOB_MDCV);
    assertThat(replacement.bytes()).isEqualTo(first.bytes());
  }

  @Test
  @DisplayName(
      "Should keep the static HDR metadata boxes that the segment holds when the video is copied")
  void shouldKeepTheStaticHdrMetadataBoxesThatTheSegmentHoldsWhenTheVideoIsCopied()
      throws IOException {
    var holdingOtherValues = holdingBoxesOf(OTHER_METADATA);

    var rewritten =
        InitializationSegmentRewrite.rewrite(
            VideoDecisionFixtures.copyOf("hvc1.2.4.L120.90"), holdingOtherValues);

    assertThat(bodiesOf(rewritten, "mdcv")).containsExactly(OTHER_MDCV);
    assertThat(bodiesOf(rewritten, "clli")).containsExactly(OTHER_CLLI);
  }

  // A segment whose sample entry holds the metadata's boxes, as a PQ encode of it delivers.
  private static InitializationSegment holdingBoxesOf(StaticHdrMetadata metadata)
      throws IOException {
    return InitializationSegmentRewrite.rewrite(
        pqEncode(metadata), recorded("hevc_nvenc-1080p-pq-extended-first"));
  }

  private static VideoDecision.Encode pqEncode(StaticHdrMetadata metadata) {
    return recordedEncode("pq").staticHdrMetadata(Optional.of(metadata)).build();
  }

  private static VideoDecision.Encode.EncodeBuilder recordedEncode(String range) {
    return NvencRecordedEncodes.encodeOf("hevc_nvenc", range)
        .tier(Optional.of(VideoCodecTier.MAIN))
        .level(120)
        .codecString("hvc1.2.4.L120.90");
  }

  private static VideoDecision.Encode.EncodeBuilder hdrEncode(
      VideoDynamicRange.CompatibleBase base) {
    var range =
        new VideoDynamicRange(
            base, VideoDynamicRange.Presence.ABSENT, new VideoDynamicRange.DolbyVision.Absent());
    return VideoDecision.Encode.builder()
        .sourceCodecFamily(Optional.of(CodecFamily.HEVC))
        .sourceDynamicRange(range)
        .outputDynamicRange(range)
        .tier(Optional.of(VideoCodecTier.MAIN))
        .outputPictureFormat(new PictureFormat(10, ChromaSubsampling.CHROMA_420));
  }

  private static InitializationSegment recorded(String recording) throws IOException {
    return new InitializationSegment(
        Files.readAllBytes(NVENC_RECORDINGS.resolve(recording + ".init.mp4")));
  }

  private static InitializationSegment recordedAv1() throws IOException {
    try (var resource =
        InitializationSegmentStaticHdrMetadataTest.class.getResourceAsStream(
            "/fmp4/09-svtav1-vfr.fmp4")) {
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
