package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.FfmpegRecordings.bytesOf;
import static com.streamarr.transcode.engine.FfmpegRecordings.recording;
import static com.streamarr.transcode.engine.IsoBoxes.concat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.engine.FfmpegRecordings.Recording;
import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.SyntheticSequenceHeader.OperatingPoint;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * An AV1 encode's initialization segment goes out with the first fragment that carries video, its
 * av1C holding that fragment's first sequence header in place of the one FFmpeg wrote. SVT-AV1
 * writes that one into FFmpeg's extradata before it chooses its coding tools. The recordings are
 * SVT-AV1 encodes of the recorder's synthetic sources; the synthetic segments declare AV1 Main at
 * level 4.0, 8-bit 4:2:0, as {@code libsvtav1-1080p30.mp4} does.
 */
@Tag("UnitTest")
@DisplayName("Segment Assembler AV1 Sequence Header Tests")
class SegmentAssemblerAv1SequenceHeaderTest {

  private static final String RECORDED_CODEC_STRING = "av01.0.00M.08";
  // SVT-AV1's sequence header in the recordings: FFmpeg's av1C and every keyframe's in-band copy,
  // which sets enable_intra_edge_filter, enable_interintra_compound, enable_warped_motion,
  // enable_cdef and enable_restoration where the av1C clears them.
  private static final String WRITTEN_SEQUENCE_HEADER = "0a0e02000005157f8c02f81010101040";
  private static final String IN_BAND_SEQUENCE_HEADER = "0a0e02000005157f8c4af91010101040";
  private static final String START_RECORDING = "09-svtav1-vfr.fmp4";
  private static final String REPLACEMENT_RECORDING = "09-svtav1-vfr-seek30.fmp4";

  private static final String SYNTHETIC_CODEC_STRING = "av01.0.08M.08";
  private static final String SYNTHETIC_AV1C_FIELDS = "81080C00";
  private static final String TEMPORAL_DELIMITER = "1200";
  private static final String FRAME = "3203102030";
  private static final String CONTENT_LIGHT_LEVEL_METADATA = "2A060103E8019080";
  private static final byte[] AUDIO_SAMPLE = {0x21, 0x10, 0x05};
  // sample_is_non_sync_sample among a sample's flags (ISO/IEC 14496-12 8.8.3.1).
  private static final int SAMPLE_IS_NON_SYNC_SAMPLE = 0x0001_0000;

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        START_RECORDING,
        REPLACEMENT_RECORDING,
        "12-svtav1-cfr-missed-forced-keyframe.fmp4"
      })
  @DisplayName(
      "Should deliver an av1C whose configOBUs are every keyframe's in-band sequence header when"
          + " SVT-AV1 wrote another sequence header into the av1C")
  void shouldDeliverAnAv1CWhoseConfigObusAreTheInBandSequenceHeaderWhenSvtAv1WroteAnother(
      String file) {
    var stream = read(file);
    assertThat(hex(configurationObusOf(stream.initializationSegment())))
        .isEqualTo(WRITTEN_SEQUENCE_HEADER);
    assertThat(keyframeSamplesOf(stream))
        .isNotEmpty()
        .allSatisfy(sample -> assertThat(hex(sample)).contains(IN_BAND_SEQUENCE_HEADER));

    var delivered = deliveredInitializationSegmentOf(recording(file));

    assertThat(hex(configurationObusOf(delivered))).isEqualTo(IN_BAND_SEQUENCE_HEADER);
  }

  @Test
  @DisplayName(
      "Should deliver byte-identical initialization segments when two attempts of one SVT-AV1"
          + " encode start at different segments")
  void shouldDeliverByteIdenticalInitializationSegmentsWhenTwoAttemptsStartAtDifferentSegments() {
    var startAttempt = recording(START_RECORDING);
    var replacementAttempt = recording(REPLACEMENT_RECORDING);
    assertThat(replacementAttempt.startSequenceNumber())
        .isGreaterThan(startAttempt.startSequenceNumber());

    var delivered = deliveredInitializationSegmentOf(startAttempt);

    assertThat(deliveredInitializationSegmentOf(replacementAttempt)).isEqualTo(delivered);
    assertThat(delivered).isNotEqualTo(read(START_RECORDING).initializationSegment());
  }

  @Test
  @DisplayName(
      "Should keep the av1C's codec string and picture format when the rewrite replaces its"
          + " sequence header")
  void shouldKeepTheAv1CsCodecStringAndPictureFormatWhenTheRewriteReplacesItsSequenceHeader() {
    var written = read(START_RECORDING).initializationSegment();

    var delivered = deliveredInitializationSegmentOf(recording(START_RECORDING));

    assertThat(videoOf(delivered)).isEqualTo(videoOf(written));
    assertThat(videoOf(delivered).codecString()).isEqualTo(RECORDED_CODEC_STRING);
  }

  @Test
  @DisplayName(
      "Should hold the initialization segment's bytes until the first fragment when the attempt"
          + " encodes AV1")
  void shouldHoldTheInitializationSegmentsBytesUntilTheFirstFragmentWhenTheAttemptEncodesAv1() {
    var recording = recording(START_RECORDING);
    var stream = read(START_RECORDING);
    var assembler = assemblerFor(recording);

    var initializationClosing = assembler.accept(stream.initializationSegment());
    var heldBytes = assembler.heldBytes();
    var firstClosing = assembler.accept(stream.fragments().getFirst());

    var readBytes = stream.initializationSegment().bytes().length;
    assertThat(initializationClosing.closedSegments()).isEmpty();
    assertThat(heldBytes).isEqualTo(readBytes);
    assertThat(firstClosing.closedSegments().getFirst())
        .isInstanceOfSatisfying(
            ClosedSegment.Initialization.class,
            initialization -> assertThat(initialization.heldBytes()).isEqualTo(readBytes));
    assertThat(assembler.heldBytes()).isEqualTo(stream.fragments().getFirst().heldBytes());
  }

  @Test
  @DisplayName("Should deliver an AV1 copy's initialization segment at once and unchanged")
  void shouldDeliverAnAv1CopysInitializationSegmentAtOnceAndUnchanged() {
    var segment = av1SegmentWithConfigurationObus(WRITTEN_SEQUENCE_HEADER);
    var assembler =
        assemblerFor(VideoDecisionFixtures.copyOf(SYNTHETIC_CODEC_STRING), Optional.empty());

    var closed = assembler.accept(segment).closedSegments();

    assertThat(closed)
        .singleElement()
        .isInstanceOfSatisfying(
            ClosedSegment.Initialization.class,
            initialization -> assertThat(initialization.segment()).isEqualTo(segment));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("configurationObusAndTheirRewrite")
  @DisplayName(
      "Should put the in-band sequence header ahead of the av1C's other configOBUs in place of"
          + " any sequence header FFmpeg wrote")
  void shouldPutTheInBandSequenceHeaderAheadOfTheOtherConfigObusInPlaceOfTheWrittenOne(
      String description, String configurationObus, String temporalUnit, String delivered) {
    var closed =
        closedSegmentsOf(
            av1SegmentWithConfigurationObus(configurationObus),
            keyframeFragment(HexFormat.of().parseHex(temporalUnit)));

    assertThat(closed.getFirst())
        .isInstanceOfSatisfying(
            ClosedSegment.Initialization.class,
            initialization ->
                assertThat(hex(configurationObusOf(initialization.segment())))
                    .isEqualToIgnoringCase(delivered));
  }

  static Stream<Arguments> configurationObusAndTheirRewrite() {
    var header = sequenceHeader().build();
    var sized = hex(header.obu());
    var unsized = hex(header.toBuilder().sized(false).build().obu());
    var extended = hex(header.toBuilder().extended(true).build().obu());
    var written = hex(header.toBuilder().orderHint(false).build().obu());
    return Stream.of(
        Arguments.of(
            "a written sequence header and a metadata OBU",
            written + CONTENT_LIGHT_LEVEL_METADATA,
            TEMPORAL_DELIMITER + sized + FRAME,
            sized + CONTENT_LIGHT_LEVEL_METADATA),
        Arguments.of("no configOBUs", "", sized + FRAME, sized),
        Arguments.of(
            "an in-band sequence header with an extension byte",
            written,
            extended + FRAME,
            extended),
        // The sequence header is the sample's last OBU, so its payload runs to the sample's end.
        Arguments.of(
            "an in-band sequence header without a size field", written, FRAME + unsized, sized));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("inBandHeadersOtherThanTheAv1CsFields")
  @DisplayName(
      "Should refuse the initialization segment as an output mismatch when the in-band sequence"
          + " header declares other codec fields than the av1C")
  void shouldRefuseTheInitializationSegmentWhenTheInBandHeaderDeclaresOtherCodecFields(
      String description, SyntheticSequenceHeader inBand) {
    var written = hex(sequenceHeader().build().obu());

    assertRefused(
        av1SegmentWithConfigurationObus(written),
        keyframeFragment(inBand.obu()),
        Reason.OUTPUT_MISMATCH);
  }

  static Stream<Arguments> inBandHeadersOtherThanTheAv1CsFields() {
    return Stream.of(
        Arguments.of("another level", sequenceHeader().operatingPoints(levelOf(9)).build()),
        Arguments.of(
            "the high tier",
            sequenceHeader().operatingPoints(List.of(new OperatingPoint(8, 1))).build()),
        Arguments.of("another profile", sequenceHeader().profile(1).build()),
        Arguments.of("10 bits", sequenceHeader().highBitDepth(true).build()),
        Arguments.of("monochrome", sequenceHeader().monochrome(true).build()),
        Arguments.of(
            "another chroma sample position", sequenceHeader().chromaSamplePosition(1).build()));
  }

  @Test
  @DisplayName(
      "Should refuse the initialization segment as an output mismatch when the first video sample"
          + " holds no sequence header")
  void shouldRefuseTheInitializationSegmentWhenTheFirstVideoSampleHoldsNoSequenceHeader() {
    assertRefused(
        av1SegmentWithConfigurationObus(WRITTEN_SEQUENCE_HEADER),
        keyframeFragment(HexFormat.of().parseHex(TEMPORAL_DELIMITER + FRAME)),
        Reason.OUTPUT_MISMATCH);
  }

  @Test
  @DisplayName(
      "Should refuse the initialization segment as an output mismatch when the AV1 encode's av1C"
          + " ends before its configOBUs")
  void shouldRefuseTheInitializationSegmentWhenTheAv1CEndsBeforeItsConfigObus() {
    var av1C = IsoBoxes.box("av1C", HexFormat.of().parseHex("81080C"));
    var segment =
        new InitializationSegment(
            HevcCopies.initializationSegment(IsoBoxes.box("av01", new byte[78], av1C)));

    assertRefused(
        segment, keyframeFragment(sequenceHeader().build().obu()), Reason.OUTPUT_MISMATCH);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("unreadableObus")
  @DisplayName(
      "Should refuse the initialization segment as a malformed box when the av1C's configOBUs or"
          + " the in-band sequence header cannot be read")
  void shouldRefuseTheInitializationSegmentAsMalformedWhenAnObuCannotBeRead(
      String description, String configurationObus, String temporalUnit) {
    assertRefused(
        av1SegmentWithConfigurationObus(configurationObus),
        keyframeFragment(HexFormat.of().parseHex(temporalUnit)),
        Reason.MALFORMED_BOX);
  }

  static Stream<Arguments> unreadableObus() {
    var sized = hex(sequenceHeader().build().obu());
    return Stream.of(
        Arguments.of("a configOBU whose size runs past the av1C", "2A0904B5", sized + FRAME),
        // A sequence header OBU of 2 payload bytes ends inside max_frame_width_minus_1.
        Arguments.of("a truncated in-band sequence header", sized, "0A020000" + FRAME));
  }

  @Test
  @DisplayName(
      "Should deliver the initialization segment with the first fragment that carries video when"
          + " an audio-only fragment comes first")
  void shouldDeliverTheInitializationSegmentWithTheFirstFragmentThatCarriesVideo() {
    var assembler = syntheticEncodeAssembler();
    var output =
        streamOf(
            av1SegmentWithConfigurationObus(WRITTEN_SEQUENCE_HEADER),
            HevcCopies.fragmentBuilder().audio(0, AUDIO_SAMPLE).build(),
            keyframeFragment(sequenceHeader().build().obu()));

    assembler.accept(output.initializationSegment());
    var audioOnly = assembler.accept(output.fragments().getFirst()).closedSegments();
    var withVideo = assembler.accept(output.fragments().get(1)).closedSegments();

    assertThat(audioOnly).isEmpty();
    assertThat(withVideo).first().isInstanceOf(ClosedSegment.Initialization.class);
  }

  @Test
  @DisplayName(
      "Should deliver nothing and hold nothing when the output ends before the AV1 encode's first"
          + " video sample")
  void shouldDeliverNothingAndHoldNothingWhenTheOutputEndsBeforeTheFirstVideoSample() {
    var assembler = syntheticEncodeAssembler();
    assembler.accept(av1SegmentWithConfigurationObus(WRITTEN_SEQUENCE_HEADER));

    var closed = assembler.finish();

    assertThat(closed).isEmpty();
    assertThat(assembler.heldBytes()).isZero();
  }

  @Test
  @DisplayName(
      "Should hold nothing and deliver no initialization segment when the held fragments are"
          + " discarded before the first video sample")
  void shouldHoldNothingWhenTheHeldFragmentsAreDiscardedBeforeTheFirstVideoSample() {
    var assembler = syntheticEncodeAssembler();
    assembler.accept(av1SegmentWithConfigurationObus(WRITTEN_SEQUENCE_HEADER));

    assembler.discardHeldFragments();

    assertThat(assembler.heldBytes()).isZero();
    assertThat(assembler.finish()).isEmpty();
  }

  private static SyntheticSequenceHeader.SyntheticSequenceHeaderBuilder sequenceHeader() {
    return SyntheticSequenceHeader.builder();
  }

  private static List<OperatingPoint> levelOf(int level) {
    return List.of(new OperatingPoint(level, 0));
  }

  private static void assertRefused(InitializationSegment segment, byte[] fragment, Reason reason) {
    var assembler = syntheticEncodeAssembler();
    var output = streamOf(segment, fragment);
    assembler.accept(output.initializationSegment());
    var first = output.fragments().getFirst();

    assertThatThrownBy(() -> assembler.accept(first))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(reason));
  }

  private static List<ClosedSegment> closedSegmentsOf(
      InitializationSegment segment, byte[] fragment) {
    var assembler = syntheticEncodeAssembler();
    var output = streamOf(segment, fragment);
    var closed = new ArrayList<>(assembler.accept(output.initializationSegment()).closedSegments());
    output.fragments().forEach(each -> closed.addAll(assembler.accept(each).closedSegments()));
    return closed;
  }

  private static byte[] keyframeFragment(byte[] temporalUnit) {
    return HevcCopies.fragmentBuilder()
        .startsWithKeyframe(true)
        .video(0, temporalUnit)
        .audio(0, AUDIO_SAMPLE)
        .build();
  }

  private static Mp4Stream streamOf(InitializationSegment segment, byte[]... fragments) {
    try {
      return Mp4Stream.read(Mp4Stream.readerOf(concat(segment.bytes(), concat(fragments))));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static InitializationSegment av1SegmentWithConfigurationObus(String obus) {
    var av1C = IsoBoxes.box("av1C", HexFormat.of().parseHex(SYNTHETIC_AV1C_FIELDS + obus));
    return new InitializationSegment(
        HevcCopies.initializationSegment(IsoBoxes.box("av01", new byte[78], av1C)));
  }

  private static SegmentAssembler syntheticEncodeAssembler() {
    var encode =
        VideoDecisionFixtures.encodeBuilder()
            .codecProfile(VideoCodecProfile.AV1_MAIN)
            .tier(Optional.of(VideoCodecTier.MAIN))
            .level(40)
            .codecString(SYNTHETIC_CODEC_STRING)
            .build();
    return assemblerFor(encode, Optional.empty());
  }

  private static SegmentAssembler assemblerFor(Recording recording) {
    return SegmentAssembler.builder()
        .intervals(SegmentIntervals.onPlainGrid(recording.period()))
        .startSequenceNumber(recording.startSequenceNumber())
        .maximumSegmentBytes(Mp4Stream.SEGMENT_CAP)
        .encodedFrameRate(Optional.of(recording.source().videoFrameRate()))
        .videoDecision(VideoDecisionFixtures.forRecording(recording))
        .requiresSoundTrack(true)
        .paddedAudioEnd(Optional.empty())
        .build();
  }

  private static SegmentAssembler assemblerFor(
      VideoDecision decision, Optional<OutputFrameRate> encodedFrameRate) {
    return SegmentAssembler.builder()
        .intervals(SegmentIntervals.onPlainGrid(1))
        .startSequenceNumber(0)
        .maximumSegmentBytes(Mp4Stream.SEGMENT_CAP)
        .encodedFrameRate(encodedFrameRate)
        .videoDecision(decision)
        .paddedAudioEnd(Optional.empty())
        .build();
  }

  // The initialization segment that the assembler delivers of the recording.
  private static InitializationSegment deliveredInitializationSegmentOf(Recording recording) {
    var stream = read(recording.file());
    var assembler = assemblerFor(recording);
    var closed = new ArrayList<>(assembler.accept(stream.initializationSegment()).closedSegments());
    for (var fragment : stream.fragments()) {
      closed.addAll(assembler.accept(fragment).closedSegments());
      if (!closed.isEmpty()) {
        break;
      }
    }

    assertThat(closed).first().isInstanceOf(ClosedSegment.Initialization.class);
    return ((ClosedSegment.Initialization) closed.getFirst()).segment();
  }

  private static Mp4Stream read(String file) {
    try {
      return Mp4Stream.read(Mp4Stream.readerOf(bytesOf(file)));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static List<ByteBuffer> keyframeSamplesOf(Mp4Stream stream) {
    return stream.fragments().stream()
        .flatMap(
            fragment ->
                fragment.videoSamples().stream()
                    .filter(sample -> (sample.flags() & SAMPLE_IS_NON_SYNC_SAMPLE) == 0)
                    .map(sample -> sampleBytesOf(fragment, sample)))
        .toList();
  }

  private static ByteBuffer sampleBytesOf(Fragment fragment, Sample sample) {
    return ByteBuffer.wrap(
        fragment.mediaDataBox(), sample.mediaDataPosition(), Math.toIntExact(sample.size()));
  }

  private static ByteBuffer configurationObusOf(InitializationSegment segment) {
    var av1C =
        CodecConfigurationReader.firstVideoSampleEntryOf(segment)
            .orElseThrow()
            .after(CodecConfigurationReader.VISUAL_SAMPLE_ENTRY_BYTES)
            .requiredChild("av1C");
    return av1C.after(4).body();
  }

  private static TrackCodec.Video videoOf(InitializationSegment segment) {
    return CodecConfigurationReader.videoOf(
        CodecConfigurationReader.firstVideoSampleEntryOf(segment).orElseThrow());
  }

  private static String hex(ByteBuffer bytes) {
    var copy = new byte[bytes.remaining()];
    bytes.duplicate().get(copy);
    return hex(copy);
  }

  private static String hex(byte[] bytes) {
    return HexFormat.of().formatHex(bytes);
  }
}
