package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.HevcCopies.PREFIX_SEI;
import static com.streamarr.transcode.engine.HevcCopies.SUFFIX_SEI;
import static com.streamarr.transcode.engine.HevcCopies.aud;
import static com.streamarr.transcode.engine.HevcCopies.enhancementLayer;
import static com.streamarr.transcode.engine.HevcCopies.fragmentBuilder;
import static com.streamarr.transcode.engine.HevcCopies.nalUnit;
import static com.streamarr.transcode.engine.HevcCopies.rpu;
import static com.streamarr.transcode.engine.HevcCopies.sample;
import static com.streamarr.transcode.engine.HevcCopies.slice;
import static com.streamarr.transcode.engine.HevcCopies.suffixSei;
import static com.streamarr.transcode.engine.InitializationSegmentFixtures.bytesOfInitializationSegment;
import static com.streamarr.transcode.engine.IsoBoxes.concat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.fixtures.Mp4Samples;
import com.sun.management.ThreadMXBean;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The assembler applies the sample rewrites that a copy lists to every video sample that it
 * delivers, and leaves the audio samples as they arrived. The HEVC samples follow the 4-byte
 * lengths of {@code copy-hvc1.mp4}'s record, and the AV1 samples are OBUs with size fields, as
 * {@code libsvtav1-1080p30.mp4}'s track carries them.
 */
@Tag("UnitTest")
@DisplayName("Segment Assembler Bitstream Rewrite Tests")
class SegmentAssemblerBitstreamRewriteTest {

  private static final String HEVC_SEGMENT = "copy-hvc1.mp4";
  private static final String HEVC_CODEC_STRING = "hvc1.1.6.L120.90";
  private static final String AV1_SEGMENT = "libsvtav1-1080p30.mp4";
  private static final String AV1_CODEC_STRING = "av01.0.08M.08";
  private static final byte[] AUDIO_SAMPLE = {0x21, 0x10, 0x05};
  // A rewrite that drops most of a sample's units allocates the fragment's own copy, the kept
  // units' buffer and the output; a sample's bytes bound each of them, its unit count none.
  private static final int MOST_ALLOCATED_BYTES_PER_SAMPLE_BYTE = 4;
  // An SEI rewrite unescapes, parses and escapes each unit again, a few copies of its bytes, and
  // the room for the kept units doubles as they outgrow the sample; a sample's bytes bound each of
  // them, its unit count none. The sample below allocates about 21 times its bytes; one copy of
  // the output per growing unit allocated about 340 times.
  private static final int MOST_ALLOCATED_BYTES_PER_REWRITTEN_SEI_SAMPLE_BYTE = 64;
  private static final String H264_SEGMENT = "libx264-1080p30.mp4";
  private static final String H264_CODEC_STRING = "avc1.640028";
  // H.264 NAL unit headers (ITU-T H.264 table 7-1): an SEI NAL unit, and an IDR slice with
  // nal_ref_idc 3.
  private static final String H264_SEI = "06";
  private static final String H264_IDR_SLICE = "65";

  // HDR10+ (ITU-T T.35, country 0xB5, provider 0x003C, provider-oriented code 0x0001,
  // application 4), and a user-data-unregistered message whose payload holds 00 00 01, which its
  // NAL unit escapes as 00 00 03 01.
  private static final String HDR10_PLUS_MESSAGE = "0408B5003C0001040100";
  private static final String UNREGISTERED_MESSAGE_ESCAPED =
      "0514" + "11".repeat(16) + "0000030120";
  private static final String RBSP_STOP = "80";

  // A temporal delimiter, metadata OBUs of type ITU-T T.35 for a Dolby Vision RPU (provider 0x003B,
  // provider-oriented code 0x00000800) and for HDR10+, one of type HDR_CLL, and a frame OBU, each
  // with its size field.
  private static final String TEMPORAL_DELIMITER = "1200";
  private static final String DOLBY_VISION_METADATA = "2A0B04B5003B00000800AABB80";
  private static final String HDR10_PLUS_METADATA = "2A0A04B5003C00010401CC80";
  private static final String CONTENT_LIGHT_LEVEL_METADATA = "2A060103E8019080";
  private static final String FRAME = "3203102030";

  @Test
  @DisplayName(
      "Should deliver no NAL unit of type 62 or 63 when the copy strips Dolby Vision from samples"
          + " that hold several enhancement-layer units")
  void shouldDeliverNoNalUnitOfType62Or63WhenTheCopyStripsDolbyVisionFromSamplesWithSeveralUnits()
      throws IOException {
    var keyframe =
        sample(
            aud(),
            enhancementLayer(0),
            slice(0),
            enhancementLayer(0),
            enhancementLayer(0),
            rpu(0),
            enhancementLayer(0));
    var picture = sample(aud(), slice(1), enhancementLayer(1), enhancementLayer(1), rpu(1));
    var fragment =
        fragmentBuilder()
            .startsWithKeyframe(true)
            .video(0, keyframe, picture)
            .audio(0, AUDIO_SAMPLE, AUDIO_SAMPLE)
            .build();

    var delivered =
        deliveredSamples(
            copyOf(HEVC_CODEC_STRING, BitstreamRewrite.STRIP_DOLBY_VISION), HEVC_SEGMENT, fragment);

    assertThat(delivered.video())
        .containsExactly(hex(sample(aud(), slice(0))), hex(sample(aud(), slice(1))));
    assertThat(delivered.audio()).containsExactly(hex(AUDIO_SAMPLE), hex(AUDIO_SAMPLE));
  }

  @Test
  @DisplayName(
      "Should keep the RPUs and enhancement-layer units when the copy strips only HDR10+ from"
          + " its samples")
  void shouldKeepTheRpusAndEnhancementLayerUnitsWhenTheCopyStripsOnlyHdr10Plus()
      throws IOException {
    var keyframe = sample(aud(), slice(0), enhancementLayer(0), rpu(0));

    var delivered =
        deliveredSamples(
            copyOf(HEVC_CODEC_STRING, BitstreamRewrite.STRIP_HDR10_PLUS),
            HEVC_SEGMENT,
            fragmentBuilder().startsWithKeyframe(true).video(0, keyframe).build());

    assertThat(delivered.video()).containsExactly(hex(keyframe));
  }

  // A profile 5 copy keeps Dolby Vision and strips HDR10+ by default (ADR 0036), and its samples
  // follow the record's lengths under the dvh1 entry that the initialization rewrite writes.
  @Test
  @DisplayName(
      "Should strip HDR10+ from the samples when the copy plans a dvh1 output and strips HDR10+")
  void shouldStripHdr10PlusFromTheSamplesWhenTheCopyPlansADvh1Output() throws IOException {
    var keyframe = sample(aud(), seiOf(HDR10_PLUS_MESSAGE), slice(0), rpu(0));
    var profile5 =
        PlannedCopyOutput.DolbyVisionOutput.builder()
            .profile(5)
            .level(6)
            .baseLayerCompatibilityId(0)
            .sampleEntry(PlannedCopyOutput.SampleEntry.DVH1)
            .build();
    var copy =
        VideoDecisionFixtures.copyBuilder()
            .codecString("dvh1.05.06")
            .plannedOutput(
                Optional.of(
                    PlannedCopyOutput.builder()
                        .dynamicRange(
                            new VideoDynamicRange(
                                VideoDynamicRange.CompatibleBase.NONE,
                                VideoDynamicRange.Presence.ABSENT,
                                new VideoDynamicRange.DolbyVision.Unspecified()))
                        .dolbyVision(Optional.of(profile5))
                        .build()))
            .bitstreamRewrites(
                Set.of(
                    BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD,
                    BitstreamRewrite.STRIP_HDR10_PLUS))
            .build();

    var delivered =
        deliveredSamples(
            copy,
            HEVC_SEGMENT,
            fragmentBuilder().startsWithKeyframe(true).video(0, keyframe).build());

    assertThat(delivered.video()).containsExactly(hex(sample(aud(), slice(0), rpu(0))));
  }

  @Test
  @DisplayName(
      "Should deliver every sample of a fragment whose first video sample keeps its bytes when the"
          + " copy rewrites a later one")
  void shouldDeliverEverySampleOfAFragmentWhoseFirstVideoSampleKeepsItsBytes() throws IOException {
    var kept = sample(aud(), slice(0));
    var rewritten =
        sample(aud(), seiOf(HDR10_PLUS_MESSAGE + UNREGISTERED_MESSAGE_ESCAPED), slice(1));
    var last = sample(aud(), slice(2));
    var fragment =
        fragmentBuilder()
            .startsWithKeyframe(true)
            .video(0, kept, rewritten, last)
            .audio(0, AUDIO_SAMPLE, new byte[] {0x21, 0x11})
            .build();

    var delivered =
        deliveredSamples(
            copyOf(HEVC_CODEC_STRING, BitstreamRewrite.STRIP_HDR10_PLUS), HEVC_SEGMENT, fragment);

    assertThat(delivered.video())
        .containsExactly(
            hex(kept),
            hex(sample(aud(), seiOf(UNREGISTERED_MESSAGE_ESCAPED), slice(1))),
            hex(last));
    assertThat(delivered.audio()).containsExactly(hex(AUDIO_SAMPLE), "2111");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("seiNalUnitsAndWhatAnHdr10PlusStripKeeps")
  @DisplayName(
      "Should remove only the HDR10+ messages of an SEI NAL unit when the copy strips HDR10+")
  void shouldRemoveOnlyTheHdr10PlusMessagesOfAnSeiNalUnitWhenTheCopyStripsHdr10Plus(
      String description, String messages, Optional<String> keptMessages) throws IOException {
    var keyframe = sample(aud(), seiOf(messages), slice(0));
    var expected = new ArrayList<byte[]>(List.of(aud()));
    keptMessages.map(SegmentAssemblerBitstreamRewriteTest::seiOf).ifPresent(expected::add);
    expected.add(slice(0));

    var delivered =
        deliveredSamples(
            copyOf(HEVC_CODEC_STRING, BitstreamRewrite.STRIP_HDR10_PLUS),
            HEVC_SEGMENT,
            fragmentBuilder().startsWithKeyframe(true).video(0, keyframe).build());

    assertThat(delivered.video()).containsExactly(hex(concat(expected.toArray(byte[][]::new))));
  }

  static Stream<Arguments> seiNalUnitsAndWhatAnHdr10PlusStripKeeps() {
    return Stream.of(
        Arguments.of(
            "HDR10+ and another message",
            HDR10_PLUS_MESSAGE + UNREGISTERED_MESSAGE_ESCAPED,
            Optional.of(UNREGISTERED_MESSAGE_ESCAPED)),
        Arguments.of(
            "another message and HDR10+",
            UNREGISTERED_MESSAGE_ESCAPED + HDR10_PLUS_MESSAGE,
            Optional.of(UNREGISTERED_MESSAGE_ESCAPED)),
        Arguments.of("only HDR10+", HDR10_PLUS_MESSAGE, Optional.empty()),
        Arguments.of(
            "only another message",
            UNREGISTERED_MESSAGE_ESCAPED,
            Optional.of(UNREGISTERED_MESSAGE_ESCAPED)),
        Arguments.of(
            "a T.35 message of another provider",
            "0408B5003B0001040100",
            Optional.of("0408B5003B0001040100")));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("av1RewritesAndTheObusTheyKeep")
  @DisplayName("Should remove the T.35 metadata OBUs that the rewrites name from AV1 samples")
  void shouldRemoveTheT35MetadataObusThatTheRewritesNameFromAv1Samples(
      Set<BitstreamRewrite> rewrites, List<String> keptObus) throws IOException {
    var temporalUnit =
        HexFormat.of()
            .parseHex(
                TEMPORAL_DELIMITER
                    + DOLBY_VISION_METADATA
                    + HDR10_PLUS_METADATA
                    + CONTENT_LIGHT_LEVEL_METADATA
                    + FRAME);
    var copy =
        VideoDecisionFixtures.copyBuilder()
            .codecString(AV1_CODEC_STRING)
            .bitstreamRewrites(rewrites)
            .build();

    var delivered =
        deliveredSamples(
            copy,
            AV1_SEGMENT,
            fragmentBuilder().startsWithKeyframe(true).video(0, temporalUnit).build());

    assertThat(delivered.video()).containsExactly(String.join("", keptObus));
  }

  @Test
  @DisplayName(
      "Should strip the T.35 metadata OBUs that the rewrites name when their metadata type takes"
          + " two bytes")
  void shouldStripTheT35MetadataObusThatTheRewritesNameWhenTheirMetadataTypeTakesTwoBytes()
      throws IOException {
    // metadata_type is a leb128 (AV1 4.10.5), so 84 00 also encodes type 4, ITU-T T.35.
    var temporalUnit =
        HexFormat.of()
            .parseHex(
                TEMPORAL_DELIMITER
                    + "2A0C8400B5003B00000800AABB80"
                    + "2A0B8400B5003C00010401CC80"
                    + FRAME);
    var copy =
        VideoDecisionFixtures.copyBuilder()
            .codecString(AV1_CODEC_STRING)
            .bitstreamRewrites(
                EnumSet.of(BitstreamRewrite.STRIP_DOLBY_VISION, BitstreamRewrite.STRIP_HDR10_PLUS))
            .build();

    var delivered =
        deliveredSamples(
            copy,
            AV1_SEGMENT,
            fragmentBuilder().startsWithKeyframe(true).video(0, temporalUnit).build());

    assertThat(delivered.video()).containsExactly(TEMPORAL_DELIMITER + FRAME);
  }

  @Test
  @DisplayName(
      "Should remove the HDR10+ message of a suffix SEI NAL unit and keep its other message when"
          + " the copy strips HDR10+")
  void shouldRemoveTheHdr10PlusMessageOfASuffixSeiNalUnitWhenTheCopyStripsHdr10Plus()
      throws IOException {
    var keyframe =
        sample(aud(), slice(0), suffixSeiOf(HDR10_PLUS_MESSAGE + UNREGISTERED_MESSAGE_ESCAPED));

    var delivered =
        deliveredSamples(
            copyOf(HEVC_CODEC_STRING, BitstreamRewrite.STRIP_HDR10_PLUS),
            HEVC_SEGMENT,
            fragmentBuilder().startsWithKeyframe(true).video(0, keyframe).build());

    assertThat(delivered.video())
        .containsExactly(hex(sample(aud(), slice(0), suffixSeiOf(UNREGISTERED_MESSAGE_ESCAPED))));
  }

  static Stream<Arguments> av1RewritesAndTheObusTheyKeep() {
    return Stream.of(
        Arguments.of(
            EnumSet.of(BitstreamRewrite.STRIP_DOLBY_VISION),
            List.of(TEMPORAL_DELIMITER, HDR10_PLUS_METADATA, CONTENT_LIGHT_LEVEL_METADATA, FRAME)),
        Arguments.of(
            EnumSet.of(BitstreamRewrite.STRIP_HDR10_PLUS),
            List.of(
                TEMPORAL_DELIMITER, DOLBY_VISION_METADATA, CONTENT_LIGHT_LEVEL_METADATA, FRAME)),
        Arguments.of(
            EnumSet.of(BitstreamRewrite.STRIP_DOLBY_VISION, BitstreamRewrite.STRIP_HDR10_PLUS),
            List.of(TEMPORAL_DELIMITER, CONTENT_LIGHT_LEVEL_METADATA, FRAME)));
  }

  @ParameterizedTest(name = "payload type {0}")
  @ValueSource(ints = {133, 157, 159, 162, 214})
  @DisplayName(
      "Should refuse a fragment as an output mismatch when a nesting SEI message holds HDR10+ that"
          + " the copy strips")
  void shouldRefuseAFragmentWhenANestingSeiMessageHoldsHdr10PlusThatTheCopyStrips(int payloadType)
      throws IOException {
    // The five SEI payload types of ITU-T H.265 that nest other SEI messages: scalable, regional,
    // MCTS extraction information, bitstream partition and processing-order nesting. Each holds a
    // flags byte, then a nested HDR10+ message.
    var nesting = HexFormat.of().toHexDigits((byte) payloadType) + "0B" + "C0" + HDR10_PLUS_MESSAGE;
    var keyframe = sample(aud(), seiOf(nesting), slice(0));
    var output =
        Mp4Stream.read(
            Mp4Stream.readerOf(
                concat(
                    bytesOfInitializationSegment(HEVC_SEGMENT),
                    fragmentBuilder().startsWithKeyframe(true).video(0, keyframe).build())));
    var assembler = assemblerFor(copyOf(HEVC_CODEC_STRING, BitstreamRewrite.STRIP_HDR10_PLUS));
    assembler.accept(output.initializationSegment());
    var fragment = output.fragments().getFirst();

    assertThatThrownBy(() -> assembler.accept(fragment))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.OUTPUT_MISMATCH));
  }

  @Test
  @DisplayName(
      "Should refuse a fragment as an output mismatch when an enhancement-layer NAL unit that the"
          + " copy keeps wraps HDR10+ that it strips")
  void shouldRefuseAFragmentWhenAnEnhancementLayerNalUnitThatTheCopyKeepsWrapsHdr10Plus()
      throws IOException {
    // A type 63 NAL unit that wraps a prefix SEI NAL unit (header 4E 01) of the enhancement layer.
    var wrapped = nalUnit(63, HexFormat.of().parseHex("4E01" + HDR10_PLUS_MESSAGE + RBSP_STOP));
    var keyframe = sample(aud(), slice(0), wrapped);
    var output =
        Mp4Stream.read(
            Mp4Stream.readerOf(
                concat(
                    bytesOfInitializationSegment(HEVC_SEGMENT),
                    fragmentBuilder().startsWithKeyframe(true).video(0, keyframe).build())));
    var assembler = assemblerFor(copyOf(HEVC_CODEC_STRING, BitstreamRewrite.STRIP_HDR10_PLUS));
    assembler.accept(output.initializationSegment());
    var fragment = output.fragments().getFirst();

    assertThatThrownBy(() -> assembler.accept(fragment))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.OUTPUT_MISMATCH));
  }

  // FFmpeg copies the source's metadata OBUs into av1C's configOBUs, which the AV1 binding permits.
  @ParameterizedTest(name = "{0}")
  @MethodSource("av1RecordsThatHoldAStrippedObu")
  @DisplayName(
      "Should deliver the av1C without the metadata OBUs that the copy strips and with every other"
          + " OBU when its av1C holds a metadata OBU that the copy strips")
  void shouldDeliverTheAv1CWithoutTheMetadataObusThatTheCopyStripsWhenItsAv1CHoldsOne(
      String description, BitstreamRewrite rewrite, String configurationObus, String keptObus) {
    var assembler = assemblerFor(copyOf(AV1_CODEC_STRING, rewrite));

    var closed =
        assembler.accept(av1SegmentWithConfigurationObus(configurationObus)).closedSegments();

    assertThat(closed)
        .singleElement()
        .isInstanceOfSatisfying(
            ClosedSegment.Initialization.class,
            delivered ->
                assertThat(delivered.segment().bytes())
                    .isEqualTo(av1SegmentWithConfigurationObus(keptObus).bytes()));
  }

  static Stream<Arguments> av1RecordsThatHoldAStrippedObu() {
    return Stream.of(
        Arguments.of(
            "a Dolby Vision RPU",
            BitstreamRewrite.STRIP_DOLBY_VISION,
            CONTENT_LIGHT_LEVEL_METADATA + DOLBY_VISION_METADATA,
            CONTENT_LIGHT_LEVEL_METADATA),
        Arguments.of("HDR10+", BitstreamRewrite.STRIP_HDR10_PLUS, HDR10_PLUS_METADATA, ""));
  }

  @Test
  @DisplayName(
      "Should refuse the initialization segment as a malformed box when the copy strips av1C's"
          + " configOBUs and one of them cannot be read")
  void shouldRefuseTheInitializationSegmentAsMalformedWhenAnAv1CConfigurationObuCannotBeRead() {
    var assembler = assemblerFor(copyOf(AV1_CODEC_STRING, BitstreamRewrite.STRIP_HDR10_PLUS));
    // A metadata OBU whose size runs past the record.
    var segment = av1SegmentWithConfigurationObus("2A0904B5");

    assertThatThrownBy(() -> assembler.accept(segment))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.MALFORMED_BOX));
  }

  // The record's codec fields fit in 3 bytes, so the output check reads it; it holds no configOBUs.
  @Test
  @DisplayName(
      "Should deliver the av1C unchanged when the copy strips av1C's configOBUs and the record ends"
          + " before them")
  void shouldDeliverTheAv1CUnchangedWhenTheRecordEndsBeforeItsConfigurationObus() {
    var assembler = assemblerFor(copyOf(AV1_CODEC_STRING, BitstreamRewrite.STRIP_HDR10_PLUS));
    var av1C = IsoBoxes.box("av1C", HexFormat.of().parseHex("81080C"));
    var segment =
        new InitializationSegment(
            HevcCopies.initializationSegment(IsoBoxes.box("av01", new byte[78], av1C)));

    var closed = assembler.accept(segment).closedSegments();

    assertThat(closed)
        .singleElement()
        .isInstanceOfSatisfying(
            ClosedSegment.Initialization.class,
            delivered -> assertThat(delivered.segment().bytes()).isEqualTo(segment.bytes()));
  }

  @Test
  @DisplayName(
      "Should deliver the initialization segment when its av1C holds only metadata OBUs that the"
          + " copy keeps")
  void shouldDeliverTheInitializationSegmentWhenItsAv1CHoldsOnlyMetadataObusThatTheCopyKeeps() {
    var assembler = assemblerFor(copyOf(AV1_CODEC_STRING, BitstreamRewrite.STRIP_HDR10_PLUS));
    var segment =
        av1SegmentWithConfigurationObus(CONTENT_LIGHT_LEVEL_METADATA + DOLBY_VISION_METADATA);

    assertThat(assembler.accept(segment).closedSegments()).hasSize(1);
  }

  // H.264 carries no Dolby Vision that a copy strips: ADR 0036 never strips profile 9.
  @Test
  @DisplayName(
      "Should refuse the initialization segment as an output mismatch when the copy strips Dolby"
          + " Vision from H.264 video")
  void
      shouldRefuseTheInitializationSegmentAsAnOutputMismatchWhenTheCopyStripsDolbyVisionFromH264() {
    var assembler = assemblerFor(copyOf(H264_CODEC_STRING, BitstreamRewrite.STRIP_DOLBY_VISION));
    var segment = new InitializationSegment(bytesOfInitializationSegment(H264_SEGMENT));

    assertThatThrownBy(() -> assembler.accept(segment))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.OUTPUT_MISMATCH));
  }

  // An H.264 SEI NAL unit (ITU-T H.264 7.3.2.3) holds its messages in the same syntax as HEVC's,
  // after a 1-byte header of type 6.
  @Test
  @DisplayName(
      "Should remove only the HDR10+ message of an H.264 SEI NAL unit when the copy strips HDR10+"
          + " from H.264 video")
  void shouldRemoveOnlyTheHdr10PlusMessageOfAnH264SeiNalUnitWhenTheCopyStripsHdr10Plus()
      throws IOException {
    var slice = h264NalUnit(H264_IDR_SLICE + "88840021");
    var keyframe =
        sample(
            h264NalUnit(H264_SEI + HDR10_PLUS_MESSAGE + UNREGISTERED_MESSAGE_ESCAPED + RBSP_STOP),
            slice);
    var picture = sample(h264NalUnit(H264_SEI + HDR10_PLUS_MESSAGE + RBSP_STOP), slice);

    var delivered =
        deliveredSamples(
            copyOf(H264_CODEC_STRING, BitstreamRewrite.STRIP_HDR10_PLUS),
            H264_SEGMENT,
            fragmentBuilder().startsWithKeyframe(true).video(0, keyframe, picture).build());

    assertThat(delivered.video())
        .containsExactly(
            hex(sample(h264NalUnit(H264_SEI + UNREGISTERED_MESSAGE_ESCAPED + RBSP_STOP), slice)),
            hex(slice));
  }

  // An H.264 NAL unit after the 4-byte length that libx264's avcC declares.
  private static byte[] h264NalUnit(String hex) {
    var unit = HexFormat.of().parseHex(hex);
    return concat(IsoBoxes.u32(unit.length), unit);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("samplesThatCannotBeRewritten")
  @DisplayName("Should refuse a fragment as malformed when a video sample cannot be rewritten")
  void shouldRefuseAFragmentAsMalformedWhenAVideoSampleCannotBeRewritten(
      String description, VideoDecision.Copy copy, String initializationSegment, byte[] sample)
      throws IOException {
    var output =
        Mp4Stream.read(
            Mp4Stream.readerOf(
                concat(
                    bytesOfInitializationSegment(initializationSegment),
                    fragmentBuilder().startsWithKeyframe(true).video(0, sample).build())));
    var assembler = assemblerFor(copy);
    assembler.accept(output.initializationSegment());
    var fragment = output.fragments().getFirst();

    assertThatThrownBy(() -> assembler.accept(fragment))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.MALFORMED_BOX));
  }

  static Stream<Arguments> samplesThatCannotBeRewritten() {
    var stripDolbyVision = copyOf(HEVC_CODEC_STRING, BitstreamRewrite.STRIP_DOLBY_VISION);
    var stripHdr10Plus = copyOf(HEVC_CODEC_STRING, BitstreamRewrite.STRIP_HDR10_PLUS);
    var stripAv1 = copyOf(AV1_CODEC_STRING, BitstreamRewrite.STRIP_DOLBY_VISION);
    return Stream.of(
        hevcCase(
            "a NAL unit length past the sample",
            stripDolbyVision,
            concat(aud(), IsoBoxes.u32(64), new byte[] {0x02, 0x01})),
        hevcCase("a NAL unit length of 0", stripDolbyVision, concat(aud(), IsoBoxes.u32(0))),
        hevcCase(
            "an SEI NAL unit without its stop bit",
            stripHdr10Plus,
            nalUnit(PREFIX_SEI, HexFormat.of().parseHex(HDR10_PLUS_MESSAGE))),
        hevcCase(
            "an SEI message whose payload runs past its NAL unit",
            stripHdr10Plus,
            nalUnit(PREFIX_SEI, HexFormat.of().parseHex("0420B5003C80"))),
        hevcCase(
            "an SEI message that ends inside its payload size",
            stripHdr10Plus,
            nalUnit(PREFIX_SEI, HexFormat.of().parseHex("04FF80"))),
        hevcCase(
            "an SEI message size coded by 8421505 bytes of 0xFF, past what an int holds, under a"
                + " conversion that strips HDR10+",
            profile7ConversionCopy(BitstreamRewrite.STRIP_HDR10_PLUS),
            nalUnit(PREFIX_SEI, seiMessageWithSizeCodedBy(8_421_505))),
        av1Case("an OBU header with its forbidden bit", stripAv1, "92" + "00"),
        av1Case("an OBU size past the sample", stripAv1, "2A0904B5"),
        av1Case("an OBU size field past the sample", stripAv1, "2A80"),
        av1Case("an OBU size field of 9 bytes", stripAv1, "2A" + "80".repeat(8) + "01"),
        av1Case("an OBU extension byte past the sample", stripAv1, "2C"),
        av1Case("a sized OBU's extension byte past the sample", stripAv1, "2E"));
  }

  // An HDR10+ message whose size takes that many 0xFF bytes and a final 0, then the stop byte.
  private static byte[] seiMessageWithSizeCodedBy(int bytesOf0xFF) {
    var size = new byte[bytesOf0xFF];
    Arrays.fill(size, (byte) 0xFF);
    return concat(new byte[] {0x04}, size, HexFormat.of().parseHex("00" + RBSP_STOP));
  }

  private static Arguments hevcCase(String description, VideoDecision.Copy copy, byte[] sample) {
    return Arguments.of(description, copy, HEVC_SEGMENT, sample);
  }

  private static Arguments av1Case(String description, VideoDecision.Copy copy, String obus) {
    return Arguments.of(description, copy, AV1_SEGMENT, HexFormat.of().parseHex(obus));
  }

  @Test
  @DisplayName(
      "Should strip a Dolby Vision metadata OBU that has an extension byte or runs to the end of"
          + " its sample without a size field")
  void shouldStripADolbyVisionMetadataObuWithAnExtensionByteOrWithoutASizeField()
      throws IOException {
    // The first OBU sets obu_extension_flag and carries its extension byte; the last has no
    // obu_has_size_field, so its payload runs to the end of the sample.
    var temporalUnit =
        HexFormat.of()
            .parseHex(
                TEMPORAL_DELIMITER
                    + "2E000B04B5003B00000800AABB80"
                    + FRAME
                    + "2804B5003B0000080080");

    var delivered =
        deliveredSamples(
            copyOf(AV1_CODEC_STRING, BitstreamRewrite.STRIP_DOLBY_VISION),
            AV1_SEGMENT,
            fragmentBuilder().startsWithKeyframe(true).video(0, temporalUnit).build());

    assertThat(delivered.video()).containsExactly(TEMPORAL_DELIMITER + FRAME);
  }

  // An initialization segment of AV1 Main at level 4.0, 8-bit 4:2:0, whose av1C holds the OBUs.
  private static InitializationSegment av1SegmentWithConfigurationObus(String obus) {
    var av1C = IsoBoxes.box("av1C", HexFormat.of().parseHex("81080C00" + obus));
    return new InitializationSegment(
        HevcCopies.initializationSegment(IsoBoxes.box("av01", new byte[78], av1C)));
  }

  // ADR 0036: the converted RPU is the last NAL unit of its sample, behind a suffix SEI NAL unit
  // that the source holds after its RPU, so that the decoder reads it with the picture.
  @Test
  @DisplayName(
      "Should deliver each picture's RPU converted to profile 8.1 as its sample's last NAL unit and"
          + " no enhancement-layer unit when the copy converts profile 7")
  void
      shouldDeliverEachPicturesRpuConvertedToProfile81LastAndNoEnhancementLayerUnitWhenTheCopyConvertsProfile7()
          throws IOException {
    var felRpu = SyntheticRpus.profile7().nalUnit();
    var keyframe =
        sample(
            aud(),
            enhancementLayer(0),
            slice(0),
            enhancementLayer(0),
            felRpu,
            enhancementLayer(0),
            suffixSei(0));
    var picture = sample(aud(), slice(1), felRpu, enhancementLayer(1));
    var fragment =
        fragmentBuilder()
            .startsWithKeyframe(true)
            .video(0, keyframe, picture)
            .audio(0, AUDIO_SAMPLE)
            .build();

    var delivered = deliveredSamples(profile7ConversionCopy(), HEVC_SEGMENT, fragment);

    var converted = SyntheticRpus.nalUnit(SyntheticRpus.convertedProfile7());
    assertThat(delivered.video())
        .containsExactly(
            hex(sample(aud(), slice(0), suffixSei(0), converted)),
            hex(sample(aud(), slice(1), converted)));
    assertThat(delivered.audio()).containsExactly(hex(AUDIO_SAMPLE));
  }

  // The profile 8.1 colour matrices take emulation prevention bytes that the source's did not, so
  // a converted sample can outgrow the source's, and the fragment makes room for it.
  @Test
  @DisplayName("Should deliver every sample when the converted RPUs are longer than the source's")
  void shouldDeliverEverySampleWhenTheConvertedRpusAreLongerThanTheSources() throws IOException {
    var source = SyntheticRpus.profile7().previousRpu(0).nalUnit();
    var converted = SyntheticRpus.nalUnit(SyntheticRpus.convertedPreviousRpu());
    assertThat(converted).hasSizeGreaterThan(source.length);
    var fragment =
        fragmentBuilder()
            .startsWithKeyframe(true)
            .video(0, sample(aud(), slice(0), source), sample(aud(), slice(1), source))
            .audio(0, AUDIO_SAMPLE, AUDIO_SAMPLE)
            .build();

    var delivered = deliveredSamples(profile7ConversionCopy(), HEVC_SEGMENT, fragment);

    assertThat(delivered.video())
        .containsExactly(
            hex(sample(aud(), slice(0), converted)), hex(sample(aud(), slice(1), converted)));
    assertThat(delivered.audio()).containsExactly(hex(AUDIO_SAMPLE), hex(AUDIO_SAMPLE));
  }

  @Test
  @DisplayName(
      "Should strip the HDR10+ message and convert the RPU when the copy converts profile 7 and"
          + " strips HDR10+")
  void shouldStripTheHdr10PlusMessageAndConvertTheRpuWhenTheCopyConvertsProfile7AndStripsHdr10Plus()
      throws IOException {
    var keyframe =
        sample(aud(), seiOf(HDR10_PLUS_MESSAGE), slice(0), SyntheticRpus.profile7().nalUnit());

    var delivered =
        deliveredSamples(
            profile7ConversionCopy(BitstreamRewrite.STRIP_HDR10_PLUS),
            HEVC_SEGMENT,
            fragmentBuilder().startsWithKeyframe(true).video(0, keyframe).build());

    assertThat(delivered.video())
        .containsExactly(
            hex(sample(aud(), slice(0), SyntheticRpus.nalUnit(SyntheticRpus.convertedProfile7()))));
  }

  // A Dolby Vision picture without an RPU can flash in a player, so the copy neither drops nor
  // repeats an RPU: it fails the attempt, and the server moves to the copy with Dolby Vision
  // stripped.
  @ParameterizedTest(name = "{0}")
  @MethodSource("samplesWithoutOneRpuThatConverts")
  @DisplayName(
      "Should fail the attempt as a conversion failure when a sample holds no single RPU that"
          + " converts to profile 8.1")
  void shouldFailTheAttemptAsAConversionFailureWhenASampleHoldsNoSingleRpuThatConvertsToProfile81(
      String description, byte[] sample) throws IOException {
    var output =
        Mp4Stream.read(
            Mp4Stream.readerOf(
                concat(
                    bytesOfInitializationSegment(HEVC_SEGMENT),
                    fragmentBuilder().startsWithKeyframe(true).video(0, sample).build())));
    var assembler = assemblerFor(profile7ConversionCopy());
    assembler.accept(output.initializationSegment());
    var fragment = output.fragments().getFirst();

    assertThatThrownBy(() -> assembler.accept(fragment))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception ->
                assertThat(exception.getReason()).isEqualTo(Reason.DOLBY_VISION_CONVERSION_FAILED));
  }

  static Stream<Arguments> samplesWithoutOneRpuThatConverts() {
    var felRpu = SyntheticRpus.profile7().nalUnit();
    return Stream.of(
        Arguments.of("a sample without an RPU", sample(aud(), slice(0), enhancementLayer(0))),
        Arguments.of("a sample with two RPUs", sample(aud(), slice(0), felRpu, felRpu)),
        Arguments.of("an RPU cut short", sample(aud(), slice(0), rpu(0))),
        Arguments.of(
            "a NAL unit of one byte beside an RPU",
            sample(aud(), new byte[] {0, 0, 0, 1, 0x7C}, slice(0), felRpu)),
        Arguments.of(
            "an RPU in a NAL unit of layer 1, which the decoder skips",
            sample(aud(), slice(0), withSecondHeaderByte(felRpu, 0x09))),
        Arguments.of(
            "an RPU in a NAL unit with temporal id 1, which the decoder skips",
            sample(aud(), slice(0), withSecondHeaderByte(felRpu, 0x02))),
        Arguments.of(
            "an RPU with a block list of 32 extension blocks of no bytes",
            sample(aud(), slice(0), SyntheticRpus.profile7().emptyBlocks(32).nalUnit())),
        Arguments.of(
            "an RPU with floating-point coefficients",
            sample(
                aud(), slice(0), SyntheticRpus.profile7().floatingPointCoefficients().nalUnit())),
        Arguments.of(
            "an RPU that names previous RPU 16, above the 15 that the decoder reads",
            sample(aud(), slice(0), SyntheticRpus.profile7().previousRpu(16).nalUnit())),
        Arguments.of(
            "an RPU that names previous RPU 4294967295, which no ue(v) writes",
            sample(
                aud(), slice(0), SyntheticRpus.profile7().previousRpu(4_294_967_295L).nalUnit())),
        Arguments.of(
            "an RPU with a piece of mapping_idc 4294967296, which is 0 in 32 bits",
            sample(
                aud(),
                slice(0),
                SyntheticRpus.profile7().lumaMappingIdc(4_294_967_296L).nalUnit())),
        Arguments.of(
            "a profile 5 RPU",
            sample(aud(), slice(0), SyntheticRpus.profile7().profile5().nalUnit())));
  }

  // A sample's unit count must not size the rewrite's memory: a sample of a million small
  // enhancement-layer units, well under the segment cap, would otherwise hold an object per unit,
  // many times the sample's own bytes and outside the producer's budget.
  @Test
  @DisplayName(
      "Should allocate no more than a few times the sample's bytes when a sample holds a million"
          + " enhancement-layer units")
  void
      shouldAllocateNoMoreThanAFewTimesTheSamplesBytesWhenASampleHoldsAMillionEnhancementLayerUnits()
          throws IOException {
    var sample = sampleOfEnhancementLayerUnits(1_000_000);
    var initialization = bytesOfInitializationSegment(HEVC_SEGMENT);
    var output =
        Mp4Stream.read(
            Mp4Stream.readerOf(
                concat(
                    initialization,
                    fragmentBuilder().startsWithKeyframe(true).video(0, sample).build())));
    var assembler = assemblerFor(profile7ConversionCopy());
    var closed = new ArrayList<>(assembler.accept(output.initializationSegment()).closedSegments());
    var fragment = output.fragments().getFirst();
    var thread = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    var before = thread.getCurrentThreadAllocatedBytes();
    closed.addAll(assembler.accept(fragment).closedSegments());
    var allocated = thread.getCurrentThreadAllocatedBytes() - before;

    closed.addAll(assembler.finish());
    var converted = SyntheticRpus.nalUnit(SyntheticRpus.convertedProfile7());
    assertThat(allocated).isLessThan((long) MOST_ALLOCATED_BYTES_PER_SAMPLE_BYTE * sample.length);
    assertThat(videoSamplesOf(initialization, closed))
        .containsExactly(hex(sample(aud(), slice(0), converted)));
  }

  // An SEI NAL unit grows when the rewrite escapes a run of zeros that the source left unescaped
  // behind the HDR10+ message it strips, so the kept units can outgrow the sample; the room for
  // them must grow geometrically, or every growing unit copies the whole output so far.
  @Test
  @DisplayName(
      "Should allocate no more than a few times the sample's bytes when every SEI NAL unit grows as"
          + " the copy strips HDR10+ from it")
  void
      shouldAllocateNoMoreThanAFewTimesTheSamplesBytesWhenEverySeiNalUnitGrowsAsTheCopyStripsHdr10PlusFromIt()
          throws IOException {
    var unregistered = "05" + "FFFFFFFF00" + "00".repeat(1_020);
    var sei = seiOf(HDR10_PLUS_MESSAGE + unregistered);
    var rpu = SyntheticRpus.profile7().nalUnit();
    var sample = concat(aud(), slice(0), repeated(sei, 800), rpu);
    var initialization = bytesOfInitializationSegment(HEVC_SEGMENT);
    var output =
        Mp4Stream.read(
            Mp4Stream.readerOf(
                concat(
                    initialization,
                    fragmentBuilder().startsWithKeyframe(true).video(0, sample).build())));
    var assembler = assemblerFor(profile7ConversionCopy(BitstreamRewrite.STRIP_HDR10_PLUS));
    var closed = new ArrayList<>(assembler.accept(output.initializationSegment()).closedSegments());
    var fragment = output.fragments().getFirst();
    var thread = (ThreadMXBean) ManagementFactory.getThreadMXBean();

    var before = thread.getCurrentThreadAllocatedBytes();
    closed.addAll(assembler.accept(fragment).closedSegments());
    var allocated = thread.getCurrentThreadAllocatedBytes() - before;

    closed.addAll(assembler.finish());
    var keptSei =
        nalUnit(
            PREFIX_SEI,
            HevcEmulationPrevention.escaped(HexFormat.of().parseHex(unregistered + RBSP_STOP)));
    var converted = SyntheticRpus.nalUnit(SyntheticRpus.convertedProfile7());
    assertThat(allocated)
        .isLessThan((long) MOST_ALLOCATED_BYTES_PER_REWRITTEN_SEI_SAMPLE_BYTE * sample.length);
    assertThat(videoSamplesOf(initialization, closed))
        .containsExactly(hex(concat(aud(), slice(0), repeated(keptSei, 800), converted)));
  }

  private static byte[] repeated(byte[] unit, int count) {
    var repeated = ByteBuffer.allocate(count * unit.length);
    for (var index = 0; index < count; index++) {
      repeated.put(unit);
    }

    return repeated.array();
  }

  // The AUD, the slice, that many enhancement-layer units and the picture's RPU.
  private static byte[] sampleOfEnhancementLayerUnits(int count) {
    var unit = enhancementLayer(0);
    var rpu = SyntheticRpus.profile7().nalUnit();
    var sample =
        ByteBuffer.allocate(aud().length + slice(0).length + count * unit.length + rpu.length);
    sample.put(aud()).put(slice(0));
    for (var index = 0; index < count; index++) {
      sample.put(unit);
    }

    return sample.put(rpu).array();
  }

  // The length-prefixed NAL unit with its second header byte, nuh_layer_id's low bits and
  // nuh_temporal_id_plus1, replaced.
  private static byte[] withSecondHeaderByte(byte[] nalUnit, int value) {
    var changed = nalUnit.clone();
    changed[5] = (byte) value;
    return changed;
  }

  // A copy of single-track profile 7 that the server planned as profile 8.1 at level 6 on its PQ
  // base: the conversion, the normalized record, and whatever else the test lists.
  private static VideoDecision.Copy profile7ConversionCopy(BitstreamRewrite... alsoListed) {
    var rewrites =
        EnumSet.of(
            BitstreamRewrite.CONVERT_DOLBY_VISION_PROFILE_7_TO_8_1,
            BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD);
    rewrites.addAll(List.of(alsoListed));
    return VideoDecisionFixtures.copyBuilder()
        .codecString(HEVC_CODEC_STRING)
        .plannedOutput(
            Optional.of(
                PlannedCopyOutputs.pqOutputBuilder()
                    .dolbyVision(Optional.of(PlannedCopyOutputs.profile81Output()))
                    .build()))
        .bitstreamRewrites(rewrites)
        .build();
  }

  private static VideoDecision.Copy copyOf(String codecString, BitstreamRewrite rewrite) {
    return VideoDecisionFixtures.copyBuilder()
        .codecString(codecString)
        .bitstreamRewrites(Set.of(rewrite))
        .build();
  }

  private static SegmentAssembler assemblerFor(VideoDecision decision) {
    return SegmentAssembler.builder()
        .intervals(SegmentIntervals.onPlainGrid(1))
        .startSequenceNumber(0)
        .maximumSegmentBytes(Mp4Stream.SEGMENT_CAP)
        .encodedFrameRate(Optional.empty())
        .videoDecision(decision)
        .paddedAudioEnd(Optional.empty())
        .build();
  }

  // The video and audio samples of every media segment that the assembler delivers of the
  // initialization segment and fragment, each as hexadecimal digits.
  private static DeliveredSamples deliveredSamples(
      VideoDecision decision, String initializationSegment, byte[] fragment) throws IOException {
    var initialization = bytesOfInitializationSegment(initializationSegment);
    var output = Mp4Stream.read(Mp4Stream.readerOf(concat(initialization, fragment)));
    var assembler = assemblerFor(decision);
    var closed = new ArrayList<>(assembler.accept(output.initializationSegment()).closedSegments());
    output.fragments().forEach(each -> closed.addAll(assembler.accept(each).closedSegments()));
    closed.addAll(assembler.finish());
    var movie = movieOf(initialization, closed);
    return new DeliveredSamples(
        hexOf(movie, movie.videoTrack()),
        movie.hasAudio() ? hexOf(movie, movie.audioTrack()) : List.of());
  }

  private static List<String> videoSamplesOf(byte[] initialization, List<ClosedSegment> closed) {
    var movie = movieOf(initialization, closed);
    return hexOf(movie, movie.videoTrack());
  }

  // The initialization segment and every closed media segment after the assembler's own
  // initialization segment, read back as a movie.
  private static Mp4Samples.Movie movieOf(byte[] initialization, List<ClosedSegment> closed) {
    var delivered = new ByteArrayOutputStream();
    delivered.writeBytes(initialization);
    closed.stream().skip(1).forEach(segment -> delivered.writeBytes(bytesOf(segment)));
    return Mp4Samples.read(delivered.toByteArray());
  }

  private static List<String> hexOf(Mp4Samples.Movie movie, Mp4Samples.Track track) {
    return movie.samplesOf(track).stream()
        .map(Mp4Samples.Sample::data)
        .map(SegmentAssemblerBitstreamRewriteTest::hex)
        .toList();
  }

  private static byte[] bytesOf(ClosedSegment segment) {
    var bytes = new ByteArrayOutputStream();
    for (ByteBuffer part : segment.write(new ProductionTimer(MonotonicClock.system())).content()) {
      var copy = new byte[part.remaining()];
      part.get(copy);
      bytes.writeBytes(copy);
    }

    return bytes.toByteArray();
  }

  private static byte[] seiOf(String escapedMessages) {
    return nalUnit(PREFIX_SEI, HexFormat.of().parseHex(escapedMessages + RBSP_STOP));
  }

  private static byte[] suffixSeiOf(String escapedMessages) {
    return nalUnit(SUFFIX_SEI, HexFormat.of().parseHex(escapedMessages + RBSP_STOP));
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().withUpperCase().formatHex(bytes);
  }

  private static String hex(ByteBuffer buffer) {
    var bytes = new byte[buffer.remaining()];
    buffer.duplicate().get(bytes);
    return hex(bytes);
  }

  private record DeliveredSamples(List<String> video, List<String> audio) {}
}
