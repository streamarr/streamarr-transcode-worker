package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.IsoBoxes.box;
import static com.streamarr.transcode.engine.IsoBoxes.concat;
import static com.streamarr.transcode.engine.IsoBoxes.fourcc;
import static com.streamarr.transcode.engine.IsoBoxes.ftyp;
import static com.streamarr.transcode.engine.IsoBoxes.fullBox;
import static com.streamarr.transcode.engine.IsoBoxes.u32;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.TrackCodec.Audio;
import com.streamarr.transcode.engine.TrackCodec.Video;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Reads initialization segments that the pinned worker image wrote;
 * src/test/resources/initialization-segments/README.md describes each one and how to record them
 * again.
 */
@Tag("UnitTest")
@DisplayName("Codec Configuration Reader Tests")
class CodecConfigurationReaderTest {

  // The 22 bytes of an hvcC before its array count: Main at level 3.0, 8-bit 4:2:0.
  private static final String HVCC_FIELDS = "0101600000009000000000005AF000FCFDF8F800000F";

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "libx264-1080p60.mp4, avc1.64002A",
    "libx264-1080p30.mp4, avc1.640028",
    "libsvtav1-1080p60.mp4, av01.0.09M.08",
    "libsvtav1-1080p30.mp4, av01.0.08M.08",
    "copy-hvc1.mp4, hvc1.1.6.L120.90",
    "copy-hev1.mp4, hev1.1.6.L120.90"
  })
  @DisplayName(
      "Should build the video codec string from the sample entry and its decoder configuration"
          + " record when the initialization segment is 8-bit 4:2:0")
  void
      shouldBuildTheVideoCodecStringFromTheSampleEntryAndItsDecoderConfigurationRecordWhenTheInitializationSegmentIsEightBitFourTwoZero(
          String file, String codecString) {
    var tracks = CodecConfigurationReader.tracksOf(initializationSegment(file));

    assertThat(tracks)
        .first()
        .isEqualTo(new Video(codecString, Optional.of(PictureFormat.EIGHT_BIT_420)));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "libx264-1080p60.mp4, avc1.64002A",
    "libsvtav1-1080p60.mp4, av01.0.09M.08",
    "copy-hvc1.mp4, hvc1.1.6.L120.90"
  })
  @ResourceLock(Resources.LOCALE)
  @DisplayName("Should write ASCII digits when the default format locale writes other digits")
  void shouldWriteAsciiDigitsWhenTheDefaultFormatLocaleWritesOtherDigits(
      String file, String codecString) {
    var segment = initializationSegment(file);
    var formatLocale = Locale.getDefault(Locale.Category.FORMAT);
    Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("ar-EG"));
    try {
      assertThat(CodecConfigurationReader.tracksOf(segment))
          .first()
          .extracting(TrackCodec::codecString)
          .isEqualTo(codecString);
    } finally {
      Locale.setDefault(Locale.Category.FORMAT, formatLocale);
    }
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "libx264-high10.mp4, avc1.6E000A, 10, CHROMA_420",
    "libx264-high444.mp4, avc1.F4000A, 8, CHROMA_444",
    "libsvtav1-10bit.mp4, av01.0.00M.10, 10, CHROMA_420"
  })
  @DisplayName(
      "Should read the bit depth and chroma subsampling when the initialization segment is not"
          + " 8-bit 4:2:0")
  void
      shouldReadTheBitDepthAndChromaSubsamplingWhenTheInitializationSegmentIsNotEightBitFourTwoZero(
          String file, String codecString, int bitDepth, ChromaSubsampling chromaSubsampling) {
    var tracks = CodecConfigurationReader.tracksOf(initializationSegment(file));

    assertThat(tracks)
        .first()
        .isEqualTo(
            new Video(codecString, Optional.of(new PictureFormat(bitDepth, chromaSubsampling))));
  }

  @Test
  @DisplayName("Should build the AAC codec string from the audio sample entry's esds")
  void shouldBuildTheAacCodecStringFromTheAudioSampleEntrysEsds() {
    var tracks = CodecConfigurationReader.tracksOf(initializationSegment("libx264-1080p60.mp4"));

    assertThat(tracks).last().isEqualTo(new Audio("mp4a.40.2"));
  }

  @Test
  @DisplayName(
      "Should name only the sample entry, without a picture format, when the worker does not read"
          + " the video sample entry")
  void
      shouldNameOnlyTheSampleEntryWithoutAPictureFormatWhenTheWorkerDoesNotReadTheVideoSampleEntry() {
    var segment = withVideoSampleEntry(box("vp09", new byte[78]));

    assertThat(CodecConfigurationReader.tracksOf(segment))
        .containsExactly(new Video("vp09", Optional.empty()));
  }

  @Test
  @DisplayName("Should refuse the initialization segment when the hvcC box is empty")
  void shouldRefuseTheInitializationSegmentWhenTheHvccBoxIsEmpty() {
    var segment = withVideoSampleEntry(box("hvc1", new byte[78], box("hvcC")));

    assertThatThrownBy(() -> CodecConfigurationReader.tracksOf(segment))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.MALFORMED_BOX));
  }

  @Test
  @DisplayName(
      "Should refuse the initialization segment when a video track declares no sample entry")
  void shouldRefuseTheInitializationSegmentWhenAVideoTrackDeclaresNoSampleEntry() {
    var segment = withStsd(fullBox("stsd", 0, u32(0)));

    assertThatThrownBy(() -> CodecConfigurationReader.tracksOf(segment))
        .isInstanceOf(FragmentedMp4Exception.class);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "Baseline 4:2:0 without a format extension, 42C01E, '', avc1.42C01E, 8, CHROMA_420",
    "High 4:2:2 at 10 bits, 7A001F, FEFAFA00, avc1.7A001F, 10, CHROMA_422",
    "monochrome High at 8 bits, 640014, FCF8F800, avc1.640014, 8, MONOCHROME"
  })
  @DisplayName("Should read the AVC format extension when the profile requires one")
  void shouldReadTheAvcFormatExtensionWhenTheProfileRequiresOne(
      String description,
      String profileConstraintsAndLevel,
      String extension,
      String codecString,
      int bitDepth,
      ChromaSubsampling chromaSubsampling) {
    var avcC =
        box(
            "avcC",
            hex("01" + profileConstraintsAndLevel + "FFE100026742010002" + "68CE"),
            hex(extension));

    assertThat(
            CodecConfigurationReader.tracksOf(
                withVideoSampleEntry(box("avc1", new byte[78], avcC))))
        .containsExactly(
            new Video(codecString, Optional.of(new PictureFormat(bitDepth, chromaSubsampling))));
  }

  @Test
  @DisplayName(
      "Should leave the picture format unknown when a High profile avcC lacks its format extension")
  void shouldLeaveThePictureFormatUnknownWhenAHighProfileAvccLacksItsFormatExtension() {
    var avcC = box("avcC", hex("01640028FFE100026742010002" + "68CE"));

    assertThat(
            CodecConfigurationReader.tracksOf(
                withVideoSampleEntry(box("avc1", new byte[78], avcC))))
        .containsExactly(new Video("avc1.640028", Optional.empty()));
  }

  @ParameterizedTest(name = "{1}")
  @CsvSource({
    "2D00, av01.1.13M.08, 8, CHROMA_444",
    "4DE8, av01.2.13H.12, 12, CHROMA_422",
    "4D48, av01.2.13M.10, 10, CHROMA_422",
    "0D5C, av01.0.13M.10, 10, MONOCHROME"
  })
  @DisplayName("Should read the AV1 profile, tier, bit depth and chroma subsampling from av1C")
  void shouldReadTheAv1ProfileTierBitDepthAndChromaSubsamplingFromAv1c(
      String profileLevelAndFlags,
      String codecString,
      int bitDepth,
      ChromaSubsampling chromaSubsampling) {
    var av1C = box("av1C", hex("81" + profileLevelAndFlags + "00"));

    assertThat(
            CodecConfigurationReader.tracksOf(
                withVideoSampleEntry(box("av01", new byte[78], av1C))))
        .containsExactly(
            new Video(codecString, Optional.of(new PictureFormat(bitDepth, chromaSubsampling))));
  }

  @ParameterizedTest(name = "{2}")
  @CsvSource({
    "41, 900000000000, hvc1.A1.4.L93.90",
    "A2, B00000000001, hvc1.B2.4.H93.B0.0.0.0.0.1",
    "E2, 000000000000, hvc1.C2.4.H93"
  })
  @DisplayName("Should name the profile space, tier and constraint bytes that hvcC declares")
  void shouldNameTheProfileSpaceTierAndConstraintBytesThatHvccDeclares(
      String spaceTierAndProfile, String constraints, String codecString) {
    var hvcC =
        box(
            "hvcC",
            hex("01" + spaceTierAndProfile + "20000000" + constraints + "5D" + "F000FCFEFAF8"));

    assertThat(
            CodecConfigurationReader.tracksOf(
                withVideoSampleEntry(box("hvc1", new byte[78], hvcC))))
        .containsExactly(
            new Video(
                codecString, Optional.of(new PictureFormat(10, ChromaSubsampling.CHROMA_422))));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "an escaped audio object type, 0, 00, 40, F840, mp4a.40.34",
    "a dependent stream with a URL, 0, E0, 40, 1010, mp4a.40.2",
    "MP3, 0, 00, 6B, '', mp4a.6B",
    "AAC without a decoder specific info, 0, 00, 40, '', mp4a.40",
    "version 1 of the sound description, 1, 00, 40, 1010, mp4a.40.2",
    "version 2 of the sound description, 2, 00, 40, 1010, mp4a.40.2"
  })
  @DisplayName("Should build the mp4a codec string from the esds descriptors")
  void shouldBuildTheMp4aCodecStringFromTheEsdsDescriptors(
      String description,
      int soundDescriptionVersion,
      String esFlags,
      String objectType,
      String audioSpecificConfig,
      String codecString) {
    var optionalFields = "";
    if (!esFlags.equals("00")) {
      optionalFields = "0001" + "01" + "61" + "0002";
    }

    var decoderSpecificInfo = "";
    if (!audioSpecificConfig.isEmpty()) {
      decoderSpecificInfo = "0580808002" + audioSpecificConfig;
    }

    var esds =
        fullBox(
            "esds",
            0,
            hex(
                "0380808000"
                    + "0001"
                    + esFlags
                    + optionalFields
                    + "0480808000"
                    + objectType
                    + "15"
                    + "0000000000000000000000"
                    + decoderSpecificInfo));
    var fields = concat(new byte[8], hex("%04X".formatted(soundDescriptionVersion)), new byte[18]);
    var extra = new byte[] {};
    if (soundDescriptionVersion == 1) {
      extra = new byte[16];
    }

    if (soundDescriptionVersion == 2) {
      extra = new byte[36];
    }

    var segment = withAudioSampleEntry(box("mp4a", fields, extra, esds));

    assertThat(CodecConfigurationReader.tracksOf(segment)).containsExactly(new Audio(codecString));
  }

  @Test
  @DisplayName(
      "Should read the descriptor after four size bytes when every size byte announces another")
  void shouldReadTheDescriptorAfterFourSizeBytesWhenEverySizeByteAnnouncesAnother() {
    var esds =
        fullBox(
            "esds",
            0,
            hex(
                "0380808080"
                    + "000100"
                    + "0480808000"
                    + "4015"
                    + "0000000000000000000000"
                    + "05808080021190"));
    var segment = withAudioSampleEntry(box("mp4a", new byte[28], esds));

    assertThat(CodecConfigurationReader.tracksOf(segment)).containsExactly(new Audio("mp4a.40.2"));
  }

  @Test
  @DisplayName(
      "Should refuse the initialization segment as a malformed box when a video sample entry ends"
          + " before its fields do")
  void
      shouldRefuseTheInitializationSegmentAsAMalformedBoxWhenAVideoSampleEntryEndsBeforeItsFieldsDo() {
    var segment = withVideoSampleEntry(box("avc1", new byte[77]));

    assertThatThrownBy(() -> CodecConfigurationReader.tracksOf(segment))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.MALFORMED_BOX));
  }

  @Test
  @DisplayName("Should name only the sample entry when an audio track is not AAC")
  void shouldNameOnlyTheSampleEntryWhenAnAudioTrackIsNotAac() {
    var segment = withAudioSampleEntry(box("ac-3", new byte[28]));

    assertThat(CodecConfigurationReader.tracksOf(segment)).containsExactly(new Audio("ac-3"));
  }

  @Test
  @DisplayName("Should leave out a track that is neither video nor audio")
  void shouldLeaveOutATrackThatIsNeitherVideoNorAudio() {
    var segment = withTrack("subt", fullBox("stsd", 0, u32(1), box("wvtt", new byte[8])));

    assertThat(CodecConfigurationReader.tracksOf(segment)).isEmpty();
  }

  @Test
  @DisplayName("Should refuse the initialization segment when an esds descriptor is out of place")
  void shouldRefuseTheInitializationSegmentWhenAnEsdsDescriptorIsOutOfPlace() {
    var esds = fullBox("esds", 0, hex("0480808000"));
    var segment = withAudioSampleEntry(box("mp4a", new byte[28], esds));

    assertThatThrownBy(() -> CodecConfigurationReader.tracksOf(segment))
        .isInstanceOf(FragmentedMp4Exception.class);
  }

  @Test
  @DisplayName(
      "Should read the first video track without parsing an audio track when the audio track's"
          + " esds is malformed")
  void shouldReadTheFirstVideoTrackWithoutParsingAnAudioTrackWhenTheAudioTracksEsdsIsMalformed() {
    var misplacedDescriptor = box("mp4a", new byte[28], fullBox("esds", 0, hex("0480808000")));
    var avcC = box("avcC", hex("01640028FFE100026742010002" + "68CE"));
    var segment =
        new InitializationSegment(
            concat(
                ftyp(),
                box(
                    "moov",
                    trak("soun", fullBox("stsd", 0, u32(1), misplacedDescriptor)),
                    trak("vide", fullBox("stsd", 0, u32(1), box("avc1", new byte[78], avcC))))));

    assertThat(
            CodecConfigurationReader.firstVideoSampleEntryOf(segment)
                .map(CodecConfigurationReader::videoOf))
        .contains(new Video("avc1.640028", Optional.empty()));
  }

  @Test
  @DisplayName("Should find no video when the initialization segment holds only an audio track")
  void shouldFindNoVideoWhenTheInitializationSegmentHoldsOnlyAnAudioTrack() {
    var segment = withAudioSampleEntry(box("ac-3", new byte[28]));

    assertThat(CodecConfigurationReader.firstVideoSampleEntryOf(segment)).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"copy-hvc1.mp4", "libx264-1080p60.mp4", "libsvtav1-1080p60.mp4"})
  @DisplayName(
      "Should find the parameter sets that the sample entry promises when the pinned image wrote"
          + " the record")
  void shouldFindTheParameterSetsThatTheSampleEntryPromisesWhenThePinnedImageWroteTheRecord(
      String file) {
    var sampleEntry =
        CodecConfigurationReader.firstVideoSampleEntryOf(initializationSegment(file)).orElseThrow();

    assertThat(CodecConfigurationReader.holdsPromisedParameterSets(sampleEntry)).isTrue();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "no array, '', false",
    "every parameter set, 20 21 22, true",
    "a SEI array beside every parameter set, 20 21 22 27, true",
    "no VPS, 21 22, false",
    "no SPS, 20 22, false",
    "no PPS, 20 21, false"
  })
  @DisplayName(
      "Should find the parameter sets that hvc1 promises only when hvcC holds a VPS, an SPS and a"
          + " PPS")
  void shouldFindTheParameterSetsThatHvc1PromisesOnlyWhenHvccHoldsAVpsAnSpsAndAPps(
      String description, String arrayTypes, boolean holds) {
    var sampleEntry = videoSampleEntryOf(box("hvc1", new byte[78], hvcCWithArrays(arrayTypes)));

    assertThat(CodecConfigurationReader.holdsPromisedParameterSets(sampleEntry)).isEqualTo(holds);
  }

  @Test
  @DisplayName(
      "Should find a promised parameter set missing when an hvcC array declares no NAL unit")
  void shouldFindAPromisedParameterSetMissingWhenAnHvccArrayDeclaresNoNalUnit() {
    var hvcC =
        box("hvcC", hex(HVCC_FIELDS + "03" + "A0000100024001" + "A10000" + "A2000100024401"));
    var sampleEntry = videoSampleEntryOf(box("hvc1", new byte[78], hvcC));

    assertThat(CodecConfigurationReader.holdsPromisedParameterSets(sampleEntry)).isFalse();
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "an SPS and a PPS, E10002674201000268CE, true",
    "no SPS, E001000268CE, false",
    "no PPS, E10002674200, false"
  })
  @DisplayName(
      "Should find the parameter sets that avc1 promises only when avcC holds an SPS and a PPS")
  void shouldFindTheParameterSetsThatAvc1PromisesOnlyWhenAvccHoldsAnSpsAndAPps(
      String description, String parameterSets, boolean holds) {
    var avcC = box("avcC", hex("0142C00AFF" + parameterSets));
    var sampleEntry = videoSampleEntryOf(box("avc1", new byte[78], avcC));

    assertThat(CodecConfigurationReader.holdsPromisedParameterSets(sampleEntry)).isEqualTo(holds);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"hev1", "dvhe", "avc3", "dvav"})
  @DisplayName(
      "Should find no promised parameter set missing when the sample entry lets the samples carry"
          + " the parameter sets")
  void shouldFindNoPromisedParameterSetMissingWhenTheSampleEntryLetsTheSamplesCarryTheParameterSets(
      String sampleEntryType) {
    var configuration = hvcCWithArrays("");
    if (sampleEntryType.startsWith("avc") || sampleEntryType.equals("dvav")) {
      configuration = box("avcC", hex("0142C00AFFE000"));
    }

    var sampleEntry = videoSampleEntryOf(box(sampleEntryType, new byte[78], configuration));

    assertThat(CodecConfigurationReader.holdsPromisedParameterSets(sampleEntry)).isTrue();
  }

  @Test
  @DisplayName(
      "Should refuse the record as a malformed box when an hvc1 hvcC ends inside a NAL unit")
  void shouldRefuseTheRecordAsAMalformedBoxWhenAnHvc1HvccEndsInsideANalUnit() {
    var hvcC = box("hvcC", hex(HVCC_FIELDS + "01" + "A0000100094001"));
    var sampleEntry = videoSampleEntryOf(box("hvc1", new byte[78], hvcC));

    assertThatThrownBy(() -> CodecConfigurationReader.holdsPromisedParameterSets(sampleEntry))
        .isInstanceOfSatisfying(
            FragmentedMp4Exception.class,
            exception -> assertThat(exception.getReason()).isEqualTo(Reason.MALFORMED_BOX));
  }

  // An hvcC whose arrays each hold one two-byte NAL unit of each listed hexadecimal type.
  private static byte[] hvcCWithArrays(String arrayTypes) {
    var types = List.<String>of();
    if (!arrayTypes.isBlank()) {
      types = List.of(arrayTypes.split(" "));
    }

    var arrays = new StringBuilder("%02X".formatted(types.size()));
    for (var type : types) {
      var nalUnitType = Integer.parseInt(type, 16);
      arrays.append("%02X0001".formatted(0x80 | nalUnitType));
      arrays.append("0002%02X01".formatted(nalUnitType << 1));
    }

    return box("hvcC", hex(HVCC_FIELDS + arrays));
  }

  private static BoxView videoSampleEntryOf(byte[] sampleEntry) {
    return CodecConfigurationReader.firstVideoSampleEntryOf(withVideoSampleEntry(sampleEntry))
        .orElseThrow();
  }

  private static InitializationSegment withVideoSampleEntry(byte[] sampleEntry) {
    return withStsd(fullBox("stsd", 0, u32(1), sampleEntry));
  }

  private static InitializationSegment withAudioSampleEntry(byte[] sampleEntry) {
    return withTrack("soun", fullBox("stsd", 0, u32(1), sampleEntry));
  }

  private static InitializationSegment withStsd(byte[] stsd) {
    return withTrack("vide", stsd);
  }

  private static InitializationSegment withTrack(String handler, byte[] stsd) {
    return new InitializationSegment(concat(ftyp(), box("moov", trak(handler, stsd))));
  }

  private static byte[] trak(String handler, byte[] stsd) {
    var hdlr = fullBox("hdlr", 0, u32(0), fourcc(handler), new byte[12], new byte[1]);
    return box("trak", box("mdia", hdlr, box("minf", box("stbl", stsd))));
  }

  private static byte[] hex(String digits) {
    return HexFormat.of().parseHex(digits);
  }

  private static InitializationSegment initializationSegment(String file) {
    try (var stream =
        CodecConfigurationReaderTest.class.getResourceAsStream(
            "/initialization-segments/" + file)) {
      assertThat(stream).as("initialization segment %s", file).isNotNull();
      return new InitializationSegment(stream.readAllBytes());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
