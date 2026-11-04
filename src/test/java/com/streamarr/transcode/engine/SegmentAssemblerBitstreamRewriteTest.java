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
import static com.streamarr.transcode.engine.InitializationSegmentFixtures.bytesOfInitializationSegment;
import static com.streamarr.transcode.engine.IsoBoxes.concat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.fixtures.Mp4Samples;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
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
            .bitstreamRewrites(EnumSet.allOf(BitstreamRewrite.class))
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
            EnumSet.allOf(BitstreamRewrite.class),
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

  @ParameterizedTest(name = "{0}")
  @MethodSource("av1RecordsThatHoldAStrippedObu")
  @DisplayName(
      "Should refuse the initialization segment as an output mismatch when its av1C holds a"
          + " metadata OBU that the copy strips")
  void shouldRefuseTheInitializationSegmentWhenItsAv1CHoldsAMetadataObuThatTheCopyStrips(
      String description, BitstreamRewrite rewrite, String configurationObus) {
    var assembler = assemblerFor(copyOf(AV1_CODEC_STRING, rewrite));
    var segment = av1SegmentWithConfigurationObus(configurationObus);

    assertThatThrownBy(() -> assembler.accept(segment))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.OUTPUT_MISMATCH));
  }

  static Stream<Arguments> av1RecordsThatHoldAStrippedObu() {
    return Stream.of(
        Arguments.of(
            "a Dolby Vision RPU",
            BitstreamRewrite.STRIP_DOLBY_VISION,
            CONTENT_LIGHT_LEVEL_METADATA + DOLBY_VISION_METADATA),
        Arguments.of("HDR10+", BitstreamRewrite.STRIP_HDR10_PLUS, HDR10_PLUS_METADATA));
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

  @Test
  @DisplayName(
      "Should refuse the initialization segment as an output mismatch when the copy lists a"
          + " rewrite for H.264 video")
  void shouldRefuseTheInitializationSegmentAsAnOutputMismatchWhenTheCopyListsARewriteForH264() {
    var assembler = assemblerFor(copyOf("avc1.640028", BitstreamRewrite.STRIP_DOLBY_VISION));
    var segment = new InitializationSegment(bytesOfInitializationSegment("libx264-1080p30.mp4"));

    assertThatThrownBy(() -> assembler.accept(segment))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.OUTPUT_MISMATCH));
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
        av1Case("an OBU header with its forbidden bit", stripAv1, "92" + "00"),
        av1Case("an OBU size past the sample", stripAv1, "2A0904B5"),
        av1Case("an OBU size field past the sample", stripAv1, "2A80"),
        av1Case("an OBU size field of 9 bytes", stripAv1, "2A" + "80".repeat(8) + "01"),
        av1Case("an OBU extension byte past the sample", stripAv1, "2C"),
        av1Case("a sized OBU's extension byte past the sample", stripAv1, "2E"));
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

  private static VideoDecision.Copy copyOf(String codecString, BitstreamRewrite rewrite) {
    return VideoDecisionFixtures.copyBuilder()
        .codecString(codecString)
        .bitstreamRewrites(Set.of(rewrite))
        .build();
  }

  private static SegmentAssembler assemblerFor(VideoDecision decision) {
    return SegmentAssembler.builder()
        .periodSeconds(1)
        .startSequenceNumber(0)
        .maximumSegmentBytes(Mp4Stream.SEGMENT_CAP)
        .encodedFrameRate(Optional.empty())
        .videoDecision(decision)
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
    var delivered = new ByteArrayOutputStream();
    delivered.writeBytes(initialization);
    closed.stream().skip(1).forEach(segment -> delivered.writeBytes(bytesOf(segment)));
    var movie = Mp4Samples.read(delivered.toByteArray());
    return new DeliveredSamples(
        hexOf(movie, movie.videoTrack()),
        movie.hasAudio() ? hexOf(movie, movie.audioTrack()) : List.of());
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
