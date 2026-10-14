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
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
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

  @Test
  @DisplayName("Should name the profile space and constraint bytes when hvcC sets them")
  void shouldNameTheProfileSpaceAndConstraintBytesWhenHvccSetsThem() {
    var hvcC = box("hvcC", hex("01" + "A2" + "20000000" + "B00000000001" + "5D" + "F000FCFEFAF8"));

    assertThat(
            CodecConfigurationReader.tracksOf(
                withVideoSampleEntry(box("hvc1", new byte[78], hvcC))))
        .containsExactly(
            new Video(
                "hvc1.B2.4.H93.B0.0.0.0.0.1",
                Optional.of(new PictureFormat(10, ChromaSubsampling.CHROMA_422))));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "an escaped audio object type, 0, 00, 40, F840, mp4a.40.34",
    "a dependent stream with a URL, 0, E0, 40, 1010, mp4a.40.2",
    "MP3, 0, 00, 6B, '', mp4a.6B",
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
    var hdlr = fullBox("hdlr", 0, u32(0), fourcc(handler), new byte[12], new byte[1]);
    var trak = box("trak", box("mdia", hdlr, box("minf", box("stbl", stsd))));
    return new InitializationSegment(concat(ftyp(), box("moov", trak)));
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
