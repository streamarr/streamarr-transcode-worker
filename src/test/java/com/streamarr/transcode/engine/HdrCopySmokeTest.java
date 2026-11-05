package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.engine.AttemptOutcome.Completed;
import com.streamarr.transcode.engine.AttemptOutcome.Failed;
import com.streamarr.transcode.fixtures.Mp4Samples;
import com.streamarr.transcode.fixtures.Mp4Samples.Sample;
import com.streamarr.transcode.worker.support.TeeingProcessLauncher;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs the producer over the FFmpeg on {@code PATH}, the locked build in CI, to check the
 * initialization segment of a copy whose output the server planned. The sources are synthetic
 * streams at 64x36 and 24 fps with a keyframe every second: libx265 PQ and HLG streams, the PQ
 * stream's samples carrying the mastering display and content light level messages that libx265
 * writes from the values of {@link PlannedCopyOutputs}, and SVT-AV1 PQ and HLG streams whose every
 * temporal unit carries an RPU in an ITU-T T.35 metadata OBU, as a Dolby Vision AV1 stream does.
 */
@Tag("SmokeTest")
@DisplayName("HDR Copy Smoke Tests")
class HdrCopySmokeTest {

  private static final int SOURCE_SECONDS = 4;
  private static final int FRAME_RATE = 24;
  private static final int PERIOD_SECONDS = 1;
  // libx265's 64x36 Main 10 at level 1.0 in the Main tier.
  private static final String CODEC_STRING = "hvc1.2.4.L30.90";
  // SVT-AV1's 64x36 Main 10-bit at level 2.0 in the Main tier.
  private static final String AV1_CODEC_STRING = "av01.0.00M.10";
  private static final Duration OUTCOME_LIMIT = Duration.ofSeconds(60);
  private static final Duration FRAGMENTATION_TARGET = Duration.ofMillis(250);
  private static final int PREFIX_SEI = 39;
  private static final int SUFFIX_SEI = 40;
  private static final int MASTERING_DISPLAY = 137;
  private static final int CONTENT_LIGHT_LEVEL = 144;
  // ITU-T H.273 table 3.
  private static final int PQ_TRANSFER = 16;
  private static final int HLG_TRANSFER = 18;
  private static final String PROFILE_5_CODEC_STRING = "dvh1.05.01";
  // The first 5 bytes of Dolby Vision records at level 1 with an RPU and a base layer: profile 5
  // with compatibility 0, profile 8 with compatibility 1, 4, and 6 with compression 3, and profile
  // 10 with compatibility 1 and 4.
  private static final String PROFILE_5_LEVEL_1 = "01000A0D00";
  private static final String PROFILE_8_LEVEL_1_PQ = "0100100D10";
  private static final String PROFILE_8_COMPATIBILITY_4 = "0100100D40";
  private static final String PROFILE_8_COMPATIBILITY_6 = "0100100D6C";
  private static final String PROFILE_10_LEVEL_1_PQ = "0100140D10";
  private static final String PROFILE_10_COMPATIBILITY_4 = "0100140D40";
  // AV1 6.2.2 and 6.7.1: the OBU types, and the metadata type of ITU-T T.35.
  private static final int OBU_FRAME_HEADER = 3;
  private static final int OBU_METADATA = 5;
  private static final int OBU_FRAME = 6;
  private static final String METADATA_TYPE_ITUT_T35 = "04";
  // Dolby's T.35 registration: country code 0xB5, provider code 0x003B and provider-oriented code
  // 0x00000800.
  private static final String DOLBY_VISION_T35 = "B5003B00000800";
  // The OBU header, its size byte, the metadata type and the registration come before the number.
  private static final int RPU_NUMBER_OFFSET = 10;
  private static final int IVF_FRAME_HEADER_BYTES = 12;
  private static final List<String> SAMPLE_ENTRY_PATH =
      List.of("moov", "trak", "mdia", "minf", "stbl", "stsd");

  @TempDir static Path root;
  private static Path hdr10;
  private static Path hlg;
  private static Path av1Pq;
  private static Path av1Hlg;

  @BeforeAll
  static void writeTheSources() throws Exception {
    av1Pq = av1Encoded("av1-pq", PQ_TRANSFER);
    av1Hlg = av1Encoded("av1-hlg", HLG_TRANSFER);
    hlg = encoded("hlg", "arib-std-b67", "");
    hdr10 =
        encoded(
            "hdr10",
            "smpte2084",
            ":master-display=" + x265MasteringDisplay() + ":max-cll=1000,400");
  }

  @ParameterizedTest(name = "starting at segment {0}")
  @ValueSource(ints = {0, 2})
  @DisplayName(
      "Should deliver the planned mastering display and content light level each as its own SEI"
          + " message in the hvcC when the copy plans an HDR10 output")
  void shouldDeliverThePlannedStaticMetadataAsSeiMessagesInTheHvccWhenTheCopyPlansHdr10(
      int firstSegment) {
    var delivered = copied(hdr10, hdr10Copy(both()), firstSegment);

    assertThat(seiMessagesOfTheRecord(delivered))
        .containsExactly(
            List.of(new SeiMessage(MASTERING_DISPLAY, masteringDisplayPayload())),
            List.of(new SeiMessage(CONTENT_LIGHT_LEVEL, contentLightLevelPayload())));
  }

  @Test
  @DisplayName(
      "Should deliver the same initialization segment from attempts that start at different"
          + " keyframes when the copy plans an HDR10 output")
  void shouldDeliverTheSameInitializationSegmentFromAttemptsThatStartAtDifferentKeyframes() {
    var fromStart = copied(hdr10, hdr10Copy(both()), 0);
    var replacement = copied(hdr10, hdr10Copy(both()), 2);

    assertThat(replacement).isEqualTo(fromStart);
  }

  // ADR 0036: the copy also keeps both messages in its samples, for players that read only those.
  @Test
  @DisplayName(
      "Should keep the static HDR messages in the keyframe samples when the copy writes them into"
          + " the hvcC")
  void shouldKeepTheStaticHdrMessagesInTheKeyframeSamplesWhenTheCopyWritesThemIntoTheHvcc() {
    var sink = copiedSink(hdr10, hdr10Copy(both()), 0);

    var movie = Mp4Samples.read(sink.acceptedBytes());
    var keyframes = movie.samplesOf(movie.videoTrack()).stream().filter(Sample::isSync).toList();
    assertThat(keyframes).hasSize(SOURCE_SECONDS / PERIOD_SECONDS);
    for (var keyframe : keyframes) {
      assertThat(seiPayloadTypesOf(keyframe.data()))
          .as("the SEI messages of the keyframe at %d", keyframe.decodeTime())
          .contains(MASTERING_DISPLAY, CONTENT_LIGHT_LEVEL);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("partialStaticMetadata")
  @DisplayName(
      "Should deliver only the static HDR messages whose values the copy plans when it plans one"
          + " or neither")
  void shouldDeliverOnlyThePlannedStaticHdrMessagesWhenTheCopyPlansOneOrNeither(
      PartialStaticMetadata partial) {
    var delivered = copied(hdr10, hdr10Copy(partial.planned()), 0);

    assertThat(seiMessagesOfTheRecord(delivered)).containsExactlyElementsOf(partial.messages());
  }

  static Stream<PartialStaticMetadata> partialStaticMetadata() {
    return Stream.of(
        new PartialStaticMetadata(
            "only the mastering display",
            PlannedCopyOutputs.pqOutputBuilder()
                .masteringDisplay(Optional.of(PlannedCopyOutputs.MASTERING_DISPLAY))
                .build(),
            List.of(List.of(new SeiMessage(MASTERING_DISPLAY, masteringDisplayPayload())))),
        new PartialStaticMetadata(
            "only the content light level",
            PlannedCopyOutputs.pqOutputBuilder()
                .contentLightLevel(Optional.of(PlannedCopyOutputs.CONTENT_LIGHT_LEVEL))
                .build(),
            List.of(List.of(new SeiMessage(CONTENT_LIGHT_LEVEL, contentLightLevelPayload())))),
        new PartialStaticMetadata(
            "neither message", PlannedCopyOutputs.pqOutputBuilder().build(), List.of()));
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(
      value = VideoDynamicRange.CompatibleBase.class,
      names = {"PQ", "HLG"})
  @DisplayName(
      "Should deliver a colr box with the planned range's transfer when the copy plans the range"
          + " of its source")
  void shouldDeliverAColrBoxWithThePlannedTransferWhenTheCopyPlansTheRangeOfItsSource(
      VideoDynamicRange.CompatibleBase base) {
    var colrCase =
        switch (base) {
          case PQ -> new ColrCase(hdr10, PlannedCopyOutputs.pq(), PQ_TRANSFER);
          case HLG -> new ColrCase(hlg, PlannedCopyOutputs.hlg(), HLG_TRANSFER);
          default -> throw new AssertionError(base);
        };

    var delivered =
        copied(
            colrCase.source(),
            hdr10Copy(PlannedCopyOutput.builder().dynamicRange(colrCase.range()).build()),
            0);

    assertThat(transferOf(delivered)).isEqualTo(colrCase.transfer());
  }

  // A source of the range, and the transfer that its copy's colr box declares.
  private record ColrCase(Path source, VideoDynamicRange range, int transfer) {}

  @Test
  @DisplayName(
      "Should fail the attempt as an output mismatch when the copy plans a PQ output of an HLG"
          + " source")
  void shouldFailTheAttemptAsAnOutputMismatchWhenTheCopyPlansAPqOutputOfAnHlgSource()
      throws Exception {
    var outcome = outcomeOf(hlg, hdr10Copy(PlannedCopyOutputs.pqOutputBuilder().build()));

    assertThat(outcome)
        .isInstanceOfSatisfying(
            Failed.class,
            failed -> assertThat(failed.reason()).isEqualTo(ProducerFailure.OUTPUT_MISMATCH));
  }

  // The question of risk 5 in the HDR output plan: the pinned FFmpeg keeps a 10-bit profile 8.1
  // record under hvc1, so a copy that does not normalize the record can carry the source's.
  @Test
  @DisplayName(
      "Should deliver the source's profile 8.1 record under hvc1 when the copy plans that Dolby"
          + " Vision output")
  void shouldDeliverTheSourcesProfile81RecordUnderHvc1WhenTheCopyPlansThatOutput()
      throws Exception {
    var source = withDolbyVisionRecord(hdr10, "hvc1", "dvvC", PROFILE_8_LEVEL_1_PQ);
    var copy =
        dolbyVisionCopy(
                CODEC_STRING,
                PlannedCopyOutputs.pq(),
                dolbyVisionOutput(8, 1, PlannedCopyOutput.SampleEntry.HVC1))
            .build();

    var initialization = copied(source, copy, 0);

    assertThat(sampleEntry(initialization).type()).isEqualTo("hvc1");
    assertThat(recordFieldsOf(initialization)).isEqualTo(new RecordFields("dvvC", 8, 1, 1, 0));
  }

  // The pinned FFmpeg drops the record from a profile 5 copy whatever the source's sample entry,
  // so before the worker wrote the record this attempt failed its output check.
  @ParameterizedTest(name = "from a {0} source")
  @ValueSource(strings = {"hev1", "dvh1"})
  @DisplayName(
      "Should deliver a dvh1 sample entry that holds its parameter sets and the profile 5 record"
          + " when the copy plans a dvh1 output and normalizes the record")
  void shouldDeliverADvh1SampleEntryWithTheProfile5RecordWhenTheCopyPlansADvh1Output(
      String sourceSampleEntry) throws Exception {
    var source = withDolbyVisionRecord(hdr10, sourceSampleEntry, "dvcC", PROFILE_5_LEVEL_1);
    var copy =
        dolbyVisionCopy(
                PROFILE_5_CODEC_STRING,
                PlannedCopyOutputs.pq(),
                dolbyVisionOutput(5, 0, PlannedCopyOutput.SampleEntry.DVH1))
            .keepsPacketBytes(sourceSampleEntry.equals("dvh1"))
            .bitstreamRewrites(Set.of(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD))
            .build();

    var sink = copiedSink(source, copy, 0);

    var initialization = sink.contentOf("init.mp4");
    var configuration = recordFieldsOf(initialization);
    assertThat(configuration).isEqualTo(new RecordFields("dvcC", 5, 1, 0, 0));
    assertThat(
            "%s.%02d.%02d"
                .formatted(
                    sampleEntry(initialization).type(),
                    configuration.profile(),
                    configuration.level()))
        .as("the codec string of the delivered sample entry and record")
        .isEqualTo(PROFILE_5_CODEC_STRING);
    assertThat(hvccArrayTypes(initialization)).contains(32, 33, 34);
    assertThat(probed(sink.acceptedBytes()))
        .contains("codec_tag_string=dvh1", "dv_profile=5", "dv_level=1");
  }

  // An MPEG-TS source carries the record's fields in a descriptor, which FFmpeg reads into the
  // same side data as an MP4 record, and its samples are Annex B.
  @ParameterizedTest(name = "from {0}")
  @ValueSource(strings = {"mp4", "mpegts"})
  @DisplayName(
      "Should deliver compatibility 1 and compression 0 when the copy normalizes a profile 8 record"
          + " with compatibility 6")
  void shouldDeliverCompatibility1AndCompression0WhenTheCopyNormalizesAProfile8Record(
      String container) throws Exception {
    var source = withDolbyVisionRecord(hdr10, "hvc1", "dvvC", PROFILE_8_COMPATIBILITY_6);
    if (container.equals("mpegts")) {
      source = remuxedToMpegTs(source);
    }

    var copy =
        dolbyVisionCopy(
                CODEC_STRING,
                PlannedCopyOutputs.pq(),
                dolbyVisionOutput(8, 1, PlannedCopyOutput.SampleEntry.HVC1))
            .keepsPacketBytes(container.equals("mp4"))
            .bitstreamRewrites(Set.of(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD))
            .build();

    var initialization = copied(source, copy, 0);

    assertThat(recordFieldsOf(initialization)).isEqualTo(new RecordFields("dvvC", 8, 1, 1, 0));
  }

  // The playlist's CODECS names the delivered sample entry, and its SUPPLEMENTAL-CODECS names the
  // delivered record's profile, level and compatible base.
  @ParameterizedTest(name = "{0}")
  @MethodSource("dolbyVisionCopies")
  @DisplayName(
      "Should deliver the sample entry and Dolby Vision record that the playlist's codec strings"
          + " name when the copy plans a Dolby Vision output")
  void shouldDeliverTheEntryAndRecordThatThePlaylistNamesWhenTheCopyPlansDolbyVision(
      DolbyVisionCopy dolbyVision) {
    var initialization = copied(dolbyVision.source(), dolbyVision.copy(), 0);

    assertThat(new PlaylistCodecs(codecsOf(initialization), supplementalCodecsOf(initialization)))
        .isEqualTo(dolbyVision.playlist());
  }

  // ADR 0037: every job attempt of a variant writes the same initialization segment.
  @ParameterizedTest(name = "{0}")
  @MethodSource("dolbyVisionCopies")
  @DisplayName(
      "Should deliver the same initialization segment from attempts that start at segment 0 and"
          + " segment 2 when the copy plans a Dolby Vision output")
  void shouldDeliverTheSameInitializationFromSegments0And2WhenTheCopyPlansDolbyVision(
      DolbyVisionCopy dolbyVision) {
    var fromStart = attempted(dolbyVision.source(), dolbyVision.copy(), 0);
    var replacement = attempted(dolbyVision.source(), dolbyVision.copy(), 2);

    assertThat(replacement.ffmpegVideoSamples())
        .as("the video samples that the replacement's FFmpeg wrote from a later keyframe")
        .hasSizeLessThan(fromStart.ffmpegVideoSamples().size())
        .first()
        .matches(Sample::isSync, "is a sync sample");
    assertThat(replacement.sink().contentOf("init.mp4"))
        .isEqualTo(fromStart.sink().contentOf("init.mp4"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("av1DolbyVisionCopies")
  @DisplayName(
      "Should keep each temporal unit's RPU in front of its frame when the copy plans a profile 10"
          + " output")
  void shouldKeepEachTemporalUnitsRpuInFrontOfItsFrameWhenTheCopyPlansAProfile10Output(
      DolbyVisionCopy dolbyVision) {
    var sink = copiedSink(dolbyVision.source(), dolbyVision.copy(), 0);

    var movie = Mp4Samples.read(sink.acceptedBytes());
    assertThat(movie.samplesOf(movie.videoTrack()))
        .extracting(sample -> rpusInFrontOfTheFrame(sample.data()))
        .containsExactlyElementsOf(
            IntStream.range(0, SOURCE_SECONDS * FRAME_RATE).mapToObj(List::of).toList());
  }

  static Stream<DolbyVisionCopy> dolbyVisionCopies() throws Exception {
    return Stream.of(
        DolbyVisionCopy.builder()
            .name("profile 5 under dvh1 from hev1")
            .source(withDolbyVisionRecord(hdr10, "hev1", "dvcC", PROFILE_5_LEVEL_1))
            .copy(profile5Copy().keepsPacketBytes(false).build())
            .playlist(new PlaylistCodecs(PROFILE_5_CODEC_STRING, Optional.empty()))
            .build(),
        DolbyVisionCopy.builder()
            .name("profile 5 under dvh1 from dvh1")
            .source(withDolbyVisionRecord(hdr10, "dvh1", "dvcC", PROFILE_5_LEVEL_1))
            .copy(profile5Copy().build())
            .playlist(new PlaylistCodecs(PROFILE_5_CODEC_STRING, Optional.empty()))
            .build(),
        DolbyVisionCopy.builder()
            .name("profile 8.1 under hvc1")
            .source(withDolbyVisionRecord(hdr10, "hvc1", "dvvC", PROFILE_8_LEVEL_1_PQ))
            .copy(profile8Copy(PlannedCopyOutputs.pq(), 1).build())
            .playlist(new PlaylistCodecs(CODEC_STRING, Optional.of("dvh1.08.01/db1p")))
            .build(),
        DolbyVisionCopy.builder()
            .name("profile 8.1 under hvc1 normalized from compatibility 6 in MPEG-TS")
            .source(
                remuxedToMpegTs(
                    withDolbyVisionRecord(hdr10, "hvc1", "dvvC", PROFILE_8_COMPATIBILITY_6)))
            .copy(
                profile8Copy(PlannedCopyOutputs.pq(), 1)
                    .keepsPacketBytes(false)
                    .bitstreamRewrites(Set.of(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD))
                    .build())
            .playlist(new PlaylistCodecs(CODEC_STRING, Optional.of("dvh1.08.01/db1p")))
            .build(),
        DolbyVisionCopy.builder()
            .name("profile 8.4 under hvc1")
            .source(withDolbyVisionRecord(hlg, "hvc1", "dvvC", PROFILE_8_COMPATIBILITY_4))
            .copy(profile8Copy(PlannedCopyOutputs.hlg(), 4).build())
            .playlist(new PlaylistCodecs(CODEC_STRING, Optional.of("dvh1.08.01/db4h")))
            .build(),
        DolbyVisionCopy.builder()
            .name("profile 10.1 under av01")
            .source(withDolbyVisionRecord(av1Pq, "av01", "dvvC", PROFILE_10_LEVEL_1_PQ))
            .copy(profile10Copy(PlannedCopyOutputs.pq(), 1).build())
            .playlist(new PlaylistCodecs(AV1_CODEC_STRING, Optional.of("dav1.10.01/db1p")))
            .build(),
        DolbyVisionCopy.builder()
            .name("profile 10.4 under av01")
            .source(withDolbyVisionRecord(av1Hlg, "av01", "dvvC", PROFILE_10_COMPATIBILITY_4))
            .copy(profile10Copy(PlannedCopyOutputs.hlg(), 4).build())
            .playlist(new PlaylistCodecs(AV1_CODEC_STRING, Optional.of("dav1.10.01/db4h")))
            .build());
  }

  static Stream<DolbyVisionCopy> av1DolbyVisionCopies() throws Exception {
    return dolbyVisionCopies()
        .filter(dolbyVision -> dolbyVision.copy().codecString().startsWith("av01"));
  }

  /**
   * A Dolby Vision copy of a source, and the CODECS and SUPPLEMENTAL-CODECS of the playlist that
   * announces it.
   */
  @Builder
  record DolbyVisionCopy(
      String name, Path source, VideoDecision.Copy copy, PlaylistCodecs playlist) {

    @Override
    public String toString() {
      return name;
    }
  }

  /** A playlist's CODECS and, for a copy on a compatible base, its SUPPLEMENTAL-CODECS. */
  record PlaylistCodecs(String codecs, Optional<String> supplementalCodecs) {}

  /** A Dolby Vision record's box type, profile, level, compatibility id and compression. */
  record RecordFields(String type, int profile, int level, int compatibility, int compression) {}

  // The planned output and the SEI messages of each SEI NAL unit in the delivered hvcC.
  record PartialStaticMetadata(
      String name, PlannedCopyOutput planned, List<List<SeiMessage>> messages) {

    @Override
    public String toString() {
      return name;
    }
  }

  /** One SEI message: its payload type and its payload, without emulation prevention bytes. */
  record SeiMessage(int payloadType, String payloadHex) {}

  private static PlannedCopyOutput both() {
    return PlannedCopyOutputs.pqOutputBuilder()
        .masteringDisplay(Optional.of(PlannedCopyOutputs.MASTERING_DISPLAY))
        .contentLightLevel(Optional.of(PlannedCopyOutputs.CONTENT_LIGHT_LEVEL))
        .build();
  }

  private static VideoDecision.Copy hdr10Copy(PlannedCopyOutput planned) {
    return VideoDecision.Copy.builder()
        .streamIndex(0)
        .codecString(CODEC_STRING)
        .keepsPacketBytes(true)
        .plannedOutput(Optional.of(planned))
        .build();
  }

  private static PlannedCopyOutput.DolbyVisionOutput dolbyVisionOutput(
      int profile, int compatibility, PlannedCopyOutput.SampleEntry sampleEntry) {
    return PlannedCopyOutput.DolbyVisionOutput.builder()
        .profile(profile)
        .level(1)
        .baseLayerCompatibilityId(compatibility)
        .sampleEntry(sampleEntry)
        .build();
  }

  private static VideoDecision.Copy.CopyBuilder dolbyVisionCopy(
      String codecString,
      VideoDynamicRange range,
      PlannedCopyOutput.DolbyVisionOutput dolbyVision) {
    return VideoDecision.Copy.builder()
        .streamIndex(0)
        .codecString(codecString)
        .keepsPacketBytes(true)
        .plannedOutput(
            Optional.of(
                PlannedCopyOutput.builder()
                    .dynamicRange(range)
                    .dolbyVision(Optional.of(dolbyVision))
                    .build()));
  }

  private static VideoDecision.Copy.CopyBuilder profile5Copy() {
    return dolbyVisionCopy(
            PROFILE_5_CODEC_STRING,
            PlannedCopyOutputs.pq(),
            dolbyVisionOutput(5, 0, PlannedCopyOutput.SampleEntry.DVH1))
        .bitstreamRewrites(Set.of(BitstreamRewrite.NORMALIZE_DOLBY_VISION_RECORD));
  }

  private static VideoDecision.Copy.CopyBuilder profile8Copy(
      VideoDynamicRange range, int compatibility) {
    return dolbyVisionCopy(
        CODEC_STRING,
        range,
        dolbyVisionOutput(8, compatibility, PlannedCopyOutput.SampleEntry.HVC1));
  }

  private static VideoDecision.Copy.CopyBuilder profile10Copy(
      VideoDynamicRange range, int compatibility) {
    return dolbyVisionCopy(
        AV1_CODEC_STRING,
        range,
        dolbyVisionOutput(10, compatibility, PlannedCopyOutput.SampleEntry.AV01));
  }

  // x265 takes the primaries as G, B, R and the white point, then the luminances.
  private static String x265MasteringDisplay() {
    var display = PlannedCopyOutputs.MASTERING_DISPLAY;
    return "G(%d,%d)B(%d,%d)R(%d,%d)WP(%d,%d)L(%d,%d)"
        .formatted(
            display.green().x(),
            display.green().y(),
            display.blue().x(),
            display.blue().y(),
            display.red().x(),
            display.red().y(),
            display.whitePoint().x(),
            display.whitePoint().y(),
            display.maxLuminance(),
            display.minLuminance());
  }

  // ITU-T H.265 D.2.28: green, blue and red, the white point, then the luminances.
  private static String masteringDisplayPayload() {
    return "33C286C41D4C0BB884D03E803D134042" + "00989680" + "00000032";
  }

  // ITU-T H.265 D.2.35: MaxCLL 1000 and MaxFALL 400.
  private static String contentLightLevelPayload() {
    return "03E80190";
  }

  private static Path encoded(String name, String transfer, String extraParameters)
      throws Exception {
    var elementary = root.resolve(name + ".hevc");
    runFfmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x36:rate=24:duration=" + SOURCE_SECONDS,
            "-c:v",
            "libx265",
            "-preset",
            "ultrafast",
            "-pix_fmt",
            "yuv420p10le",
            "-x265-params",
            "keyint=24:min-keyint=24:scenecut=0:bframes=0:log-level=error:colorprim=bt2020"
                + ":transfer="
                + transfer
                + ":colormatrix=bt2020nc"
                + extraParameters,
            "-f",
            "hevc"),
        elementary);
    var muxed = root.resolve(name + ".mp4");
    runFfmpeg(
        List.of(
            "-f",
            "hevc",
            "-framerate",
            "24",
            "-i",
            elementary.toString(),
            "-c",
            "copy",
            "-tag:v",
            "hvc1"),
        muxed);
    return muxed;
  }

  // An SVT-AV1 stream with an RPU in front of each temporal unit's frame, muxed into an av01 entry.
  private static Path av1Encoded(String name, int transfer) throws Exception {
    var ivf = root.resolve(name + ".ivf");
    runFfmpeg(
        List.of(
            "-f",
            "lavfi",
            "-i",
            "testsrc2=size=64x36:rate=" + FRAME_RATE + ":duration=" + SOURCE_SECONDS,
            "-c:v",
            "libsvtav1",
            "-preset",
            "8",
            "-pix_fmt",
            "yuv420p10le",
            "-svtav1-params",
            "keyint="
                + FRAME_RATE
                + ":scd=0:color-primaries=9:matrix-coefficients=9"
                + ":transfer-characteristics="
                + transfer,
            "-f",
            "ivf"),
        ivf);
    var withRpus = root.resolve(name + "-rpus.ivf");
    Files.write(withRpus, withRpuObus(Files.readAllBytes(ivf)));
    var muxed = root.resolve(name + ".mp4");
    runFfmpeg(List.of("-i", withRpus.toString(), "-c", "copy"), muxed);
    return muxed;
  }

  // IVF: a file header whose length sits at byte 6, then each frame's 4-byte little-endian size,
  // 8-byte timestamp and temporal unit. Each temporal unit gets an RPU that carries its number.
  private static byte[] withRpuObus(byte[] ivf) {
    var reading = ByteBuffer.wrap(ivf).order(ByteOrder.LITTLE_ENDIAN);
    var headerLength = Short.toUnsignedInt(reading.getShort(6));
    var rebuilt = new ByteArrayOutputStream();
    rebuilt.write(ivf, 0, headerLength);
    reading.position(headerLength);
    for (var number = 0; reading.remaining() >= IVF_FRAME_HEADER_BYTES; number++) {
      var temporalUnit = new byte[reading.getInt()];
      var timestamp = reading.getLong();
      reading.get(temporalUnit);
      var frameStart = firstFrame(ByteBuffer.wrap(temporalUnit)).start();
      var withRpu =
          IsoBoxes.concat(
              Arrays.copyOfRange(temporalUnit, 0, frameStart),
              rpuObu(number),
              Arrays.copyOfRange(temporalUnit, frameStart, temporalUnit.length));
      rebuilt.writeBytes(
          ByteBuffer.allocate(IVF_FRAME_HEADER_BYTES)
              .order(ByteOrder.LITTLE_ENDIAN)
              .putInt(withRpu.length)
              .putLong(timestamp)
              .array());
      rebuilt.writeBytes(withRpu);
    }

    return rebuilt.toByteArray();
  }

  // A metadata OBU with its size field that holds Dolby's T.35 registration and the number. A copy
  // passes an RPU's bytes on unread, so the number stands for the RPU's payload.
  private static byte[] rpuObu(int number) {
    var payload =
        HexFormat.of()
            .parseHex(METADATA_TYPE_ITUT_T35 + DOLBY_VISION_T35 + "%04X".formatted(number) + "80");
    return IsoBoxes.concat(new byte[] {OBU_METADATA << 3 | 0x02, (byte) payload.length}, payload);
  }

  // The numbers of the RPUs in front of the first frame of a delivered temporal unit.
  private static List<Integer> rpusInFrontOfTheFrame(ByteBuffer temporalUnit) {
    var numbers = new ArrayList<Integer>();
    for (var obu : obusOf(temporalUnit)) {
      if (obu.isFrame()) {
        return numbers;
      }

      if (obu.type() == OBU_METADATA) {
        var number = Short.toUnsignedInt(ByteBuffer.wrap(obu.bytes()).getShort(RPU_NUMBER_OFFSET));
        assertThat(obu.bytes()).as("the RPU numbered %d", number).isEqualTo(rpuObu(number));
        numbers.add(number);
      }
    }

    throw new AssertionError("the temporal unit holds no frame");
  }

  private static Obu firstFrame(ByteBuffer temporalUnit) {
    return obusOf(temporalUnit).stream()
        .filter(Obu::isFrame)
        .findFirst()
        .orElseThrow(() -> new AssertionError("the temporal unit holds no frame"));
  }

  // The OBUs of a temporal unit (AV1 5.3), each with its header and size field.
  private static List<Obu> obusOf(ByteBuffer temporalUnit) {
    var reading = temporalUnit.duplicate();
    var obus = new ArrayList<Obu>();
    while (reading.hasRemaining()) {
      var start = reading.position();
      var header = Byte.toUnsignedInt(reading.get());
      assertThat(header & 0x02).as("the OBU's has_size_field").isNotZero();
      if ((header & 0x04) != 0) {
        reading.get();
      }

      var size = leb128(reading);
      reading.position(reading.position() + size);
      var bytes = new byte[reading.position() - start];
      reading.get(start, bytes);
      obus.add(new Obu((header >> 3) & 0x0F, start - temporalUnit.position(), bytes));
    }

    return obus;
  }

  // An unsigned value, 7 bits a byte, least significant first (AV1 4.10.5).
  private static int leb128(ByteBuffer reading) {
    var value = 0;
    var shift = 0;
    int octet;
    do {
      octet = Byte.toUnsignedInt(reading.get());
      value |= (octet & 0x7F) << shift;
      shift += 7;
    } while ((octet & 0x80) != 0);

    return value;
  }

  /** An OBU's type, its offset in its temporal unit, and its bytes from its header on. */
  private record Obu(int type, int start, byte[] bytes) {

    boolean isFrame() {
      return type == OBU_FRAME_HEADER || type == OBU_FRAME;
    }
  }

  private static AttemptOutcome outcomeOf(Path source, VideoDecision.Copy copy) throws Exception {
    var producer =
        engine(new ProcessBuilderLauncher())
            .startProducer(
                request(source, copy, 0),
                new RecordingSegmentSink(),
                SegmentMemoryBudget.forSlots(1));
    return producer.outcome().get(OUTCOME_LIMIT.toSeconds(), TimeUnit.SECONDS);
  }

  private static byte[] copied(Path source, VideoDecision.Copy copy, int firstSegment) {
    return copiedSink(source, copy, firstSegment).contentOf("init.mp4");
  }

  private static RecordingSegmentSink copiedSink(
      Path source, VideoDecision.Copy copy, int firstSegment) {
    return attempted(source, copy, firstSegment).sink();
  }

  private static Attempt attempted(Path source, VideoDecision.Copy copy, int firstSegment) {
    var sink = new RecordingSegmentSink();
    var launcher = new TeeingProcessLauncher(new ProcessBuilderLauncher());
    var request = request(source, copy, firstSegment);

    var producer = engine(launcher).startProducer(request, sink, SegmentMemoryBudget.forSlots(1));

    assertThat(producer.outcome()).succeedsWithin(OUTCOME_LIMIT).isInstanceOf(Completed.class);
    return new Attempt(sink, launcher.outputOf(request.attemptId()));
  }

  /** A completed attempt's deliveries, and what its FFmpeg wrote to its standard output. */
  private record Attempt(RecordingSegmentSink sink, byte[] ffmpegOutput) {

    // FFmpeg's own video samples, before the producer drops any that precede the first segment.
    List<Sample> ffmpegVideoSamples() {
      var movie = Mp4Samples.read(ffmpegOutput);
      return movie.samplesOf(movie.videoTrack());
    }
  }

  private static FfmpegTranscodeEngine engine(ProcessLauncher launcher) {
    var capabilities = new TranscodeCapabilityService("ffmpeg", ProcessBuilder::start);
    capabilities.detectCapabilities();
    assertThat(capabilities.isFfmpegAvailable()).as(capabilities.getUnavailableReason()).isTrue();
    return FfmpegTranscodeEngine.builder()
        .commandBuilder(new FfmpegCommandBuilder("ffmpeg", FRAGMENTATION_TARGET))
        .capabilityService(capabilities)
        .launcher(launcher)
        .encoderStallTimeout(FfmpegTranscodeEngine.DEFAULT_ENCODER_STALL_TIMEOUT)
        .build();
  }

  // The source with the Dolby Vision record appended to its video sample entry, which takes the
  // given type. FFmpeg writes the moov behind the mdat, so no chunk offset moves.
  private static Path withDolbyVisionRecord(
      Path source, String sampleEntryType, String recordType, String recordFields)
      throws IOException {
    var configuration = box(recordType, HexFormat.of().parseHex(recordFields + "00".repeat(19)));
    var rebuilt = new ByteArrayOutputStream();
    var passedMdat = false;
    for (var top : children(new Box("file", Files.readAllBytes(source)), 0)) {
      passedMdat |= top.type().equals("mdat");
      var body = top.body();
      if (top.type().equals("moov")) {
        assertThat(passedMdat).as("the moov follows the mdat").isTrue();
        body = appended(top, SAMPLE_ENTRY_PATH.subList(1, SAMPLE_ENTRY_PATH.size()), configuration);
        body = withSampleEntryType(body, sampleEntryType);
      }

      rebuilt.writeBytes(box(top.type(), body));
    }

    var written =
        root.resolve(
            "%s-%s-%s.mp4".formatted(sampleEntryType, recordType, recordFields.toLowerCase()));
    Files.write(written, rebuilt.toByteArray());
    return written;
  }

  private static Path remuxedToMpegTs(Path source) throws Exception {
    var remuxed = root.resolve(source.getFileName() + ".ts");
    runFfmpeg(
        List.of("-i", source.toString(), "-c", "copy", "-strict", "unofficial", "-f", "mpegts"),
        remuxed);
    assertThat(probed(remuxed)).contains("dv_bl_signal_compatibility_id=6");
    return remuxed;
  }

  // The box's body with the child appended to the sample entry at the end of the path below it.
  private static byte[] appended(Box box, List<String> path, byte[] child) {
    var fields = fieldsOf(box.type());
    var body = new ByteArrayOutputStream();
    body.write(box.body(), 0, fields);
    for (var nested : children(box, fields)) {
      var nestedBody = nested.body();
      if (path.isEmpty() || nested.type().equals(path.getFirst())) {
        nestedBody = appendedBelow(nested, path, child);
      }

      body.writeBytes(box(nested.type(), nestedBody));
    }

    return body.toByteArray();
  }

  private static byte[] appendedBelow(Box nested, List<String> path, byte[] child) {
    if (path.isEmpty()) {
      return IsoBoxes.concat(nested.body(), child);
    }

    return appended(nested, path.subList(1, path.size()), child);
  }

  private static int fieldsOf(String type) {
    return switch (type) {
      case "stsd" -> 8;
      case "hvc1", "hev1", "dvh1", "av01" -> 78;
      default -> 0;
    };
  }

  // An AV1 source keeps the av01 entry that FFmpeg writes; an HEVC source's hvc1 entry takes the
  // type.
  private static byte[] withSampleEntryType(byte[] moov, String type) {
    if (type.equals("av01")) {
      return moov;
    }

    return renamedSampleEntry(moov, type);
  }

  // The moov body with its hvc1 sample entry's type replaced; the type takes the same 4 bytes.
  private static byte[] renamedSampleEntry(byte[] moov, String type) {
    var bytes = moov.clone();
    var hvc1 = "hvc1".getBytes(StandardCharsets.US_ASCII);
    for (var index = 0; index + 4 <= bytes.length; index++) {
      if (Arrays.equals(bytes, index, index + 4, hvc1, 0, 4)) {
        System.arraycopy(type.getBytes(StandardCharsets.US_ASCII), 0, bytes, index, 4);
        return bytes;
      }
    }

    throw new AssertionError("the moov holds no hvc1 sample entry");
  }

  private static byte[] box(String type, byte[] body) {
    return ByteBuffer.allocate(8 + body.length)
        .putInt(8 + body.length)
        .put(type.getBytes(StandardCharsets.US_ASCII))
        .put(body)
        .array();
  }

  // The fields of the one Dolby Vision record in the delivered sample entry (Dolby's ISO BMFF
  // spec 3.2).
  private static RecordFields recordFieldsOf(byte[] initialization) {
    var records =
        children(sampleEntry(initialization), 78).stream()
            .filter(child -> child.type().matches("dv[cvw]C"))
            .toList();
    assertThat(records).as("the Dolby Vision records").hasSize(1);
    var fields = records.getFirst().body();
    var profile = Byte.toUnsignedInt(fields[2]) >> 1;
    var level = ((fields[2] & 0x01) << 5) | (Byte.toUnsignedInt(fields[3]) >> 3);
    var compatibility = Byte.toUnsignedInt(fields[4]) >> 4;
    var compression = (fields[4] >> 2) & 0x03;
    return new RecordFields(records.getFirst().type(), profile, level, compatibility, compression);
  }

  // The NAL unit types of the hvcC arrays that hold at least one NAL unit.
  private static List<Integer> hvccArrayTypes(byte[] initialization) {
    var hvcC = ByteBuffer.wrap(child(sampleEntry(initialization), 78, "hvcC"));
    hvcC.position(22);
    var arrays = Byte.toUnsignedInt(hvcC.get());
    var types = new ArrayList<Integer>();
    for (var array = 0; array < arrays; array++) {
      var type = Byte.toUnsignedInt(hvcC.get()) & 0x3F;
      var units = Short.toUnsignedInt(hvcC.getShort());
      for (var unit = 0; unit < units; unit++) {
        var length = Short.toUnsignedInt(hvcC.getShort());
        hvcC.position(hvcC.position() + length);
      }

      if (units > 0) {
        types.add(type);
      }
    }

    return types;
  }

  // ffprobe's reading of the delivered stream's sample entry tag and Dolby Vision side data.
  private static String probed(byte[] delivered) throws Exception {
    var media = Files.createTempFile(root, "delivered", ".mp4");
    Files.write(media, delivered);
    return probed(media);
  }

  private static String probed(Path media) throws Exception {
    return runToCompletion(
        List.of(
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=codec_tag_string:stream_side_data",
            "-of",
            "default=nw=1",
            media.toString()));
  }

  // The transfer characteristics of the nclx colr box of the delivered sample entry.
  private static int transferOf(byte[] initialization) {
    var colr = ByteBuffer.wrap(child(sampleEntry(initialization), 78, "colr"));
    var colourType = new byte[4];
    colr.get(colourType);
    assertThat(new String(colourType, StandardCharsets.US_ASCII)).isEqualTo("nclx");
    colr.getShort();
    return Short.toUnsignedInt(colr.getShort());
  }

  private static TranscodeRequest request(Path source, VideoDecision.Copy copy, int firstSegment) {
    var codecFamily = "hevc";
    if (copy.codecString().startsWith("av01")) {
      codecFamily = "av1";
    }

    var seek = Optional.<Duration>empty();
    if (firstSegment > 0) {
      seek = Optional.of(Duration.ofSeconds((long) firstSegment * PERIOD_SECONDS));
    }

    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(source)
        .targetSegmentDuration(PERIOD_SECONDS)
        .transcodeDecision(
            TranscodeDecision.builder()
                .transcodeMode(TranscodeMode.REMUX)
                .videoCodecFamily(codecFamily)
                .videoDecision(copy)
                .audioDecision(AudioDecision.builder().mode(AudioMode.NONE).build())
                .subtitleDecision(SubtitleDecisions.EXCLUDED)
                .build())
        .width(64)
        .height(36)
        .bitrate(100_000L)
        .startSequenceNumber(firstSegment)
        .mediaSegmentCount(SOURCE_SECONDS / PERIOD_SECONDS)
        .seekPosition(seek)
        .build();
  }

  // The codec string that the delivered sample entry and its configuration records name: for hvc1
  // the HEVC string of ISO/IEC 14496-15 E.3, for av01 the AV1 string of the AV1 ISOBMFF binding's
  // codecs parameter, and for dvh1 the Dolby Vision string of its record.
  private static String codecsOf(byte[] initialization) {
    var entry = sampleEntry(initialization);
    return switch (entry.type()) {
      case "hvc1" -> hevcCodecsOf(child(entry, 78, "hvcC"));
      case "av01" -> av1CodecsOf(child(entry, 78, "av1C"));
      case "dvh1" -> dolbyVisionCodecsOf("dvh1", recordFieldsOf(initialization));
      default -> throw new AssertionError("no codec string for a " + entry.type() + " entry");
    };
  }

  // A base entry's Dolby Vision string with the brand of its compatible base; a dvh1 entry names
  // Dolby Vision in its CODECS and takes none.
  private static Optional<String> supplementalCodecsOf(byte[] initialization) {
    var entry = sampleEntry(initialization).type();
    if (entry.equals("dvh1")) {
      return Optional.empty();
    }

    var record = recordFieldsOf(initialization);
    var brand =
        switch (record.compatibility()) {
          case 1 -> "db1p";
          case 4 -> "db4h";
          default -> throw new AssertionError("no brand for compatibility " + record);
        };
    var dolbyVisionEntry =
        switch (entry) {
          case "hvc1" -> "dvh1";
          case "av01" -> "dav1";
          default -> throw new AssertionError("no Dolby Vision entry for " + entry);
        };
    return Optional.of(dolbyVisionCodecsOf(dolbyVisionEntry, record) + "/" + brand);
  }

  private static String dolbyVisionCodecsOf(String entry, RecordFields record) {
    return "%s.%02d.%02d".formatted(entry, record.profile(), record.level());
  }

  // The profile space and profile, the reversed compatibility flags, the tier and level, then the
  // constraint bytes up to the last nonzero one.
  private static String hevcCodecsOf(byte[] hvcC) {
    var reading = ByteBuffer.wrap(hvcC, 1, hvcC.length - 1);
    var profileFields = Byte.toUnsignedInt(reading.get());
    var compatibility = Integer.reverse(reading.getInt());
    var constraints = new byte[6];
    reading.get(constraints);
    var level = Byte.toUnsignedInt(reading.get());
    var space = List.of("", "A", "B", "C").get(profileFields >> 6);
    var tier = "L";
    if ((profileFields & 0x20) != 0) {
      tier = "H";
    }

    var codecs =
        new StringBuilder(
            "hvc1.%s%d.%s.%s%d"
                .formatted(
                    space,
                    profileFields & 0x1F,
                    Integer.toHexString(compatibility).toUpperCase(Locale.ROOT),
                    tier,
                    level));
    var last = constraints.length;
    while (last > 0 && constraints[last - 1] == 0) {
      last--;
    }

    for (var index = 0; index < last; index++) {
      codecs.append(".%02X".formatted(constraints[index]));
    }

    return codecs.toString();
  }

  // The profile, level and tier, then the bit depth.
  private static String av1CodecsOf(byte[] av1C) {
    var profile = Byte.toUnsignedInt(av1C[1]) >> 5;
    var flags = Byte.toUnsignedInt(av1C[2]);
    var tier = "M";
    if ((flags & 0x80) != 0) {
      tier = "H";
    }

    var bitDepth = 8;
    if ((flags & 0x40) != 0) {
      bitDepth = 10;
    }

    if (bitDepth == 10 && profile == 2 && (flags & 0x20) != 0) {
      bitDepth = 12;
    }

    return "av01.%d.%02d%s.%02d".formatted(profile, av1C[1] & 0x1F, tier, bitDepth);
  }

  // The messages of each SEI NAL unit in the hvcC of the delivered sample entry, in order.
  private static List<List<SeiMessage>> seiMessagesOfTheRecord(byte[] initialization) {
    var entry = sampleEntry(initialization);
    var hvcC = ByteBuffer.wrap(child(entry, 78, "hvcC"));
    hvcC.position(22);
    var arrays = Byte.toUnsignedInt(hvcC.get());
    var messages = new ArrayList<List<SeiMessage>>();
    for (var array = 0; array < arrays; array++) {
      var type = Byte.toUnsignedInt(hvcC.get()) & 0x3F;
      var units = Short.toUnsignedInt(hvcC.getShort());
      for (var unit = 0; unit < units; unit++) {
        var bytes = new byte[Short.toUnsignedInt(hvcC.getShort())];
        hvcC.get(bytes);
        if (type == PREFIX_SEI || type == SUFFIX_SEI) {
          messages.add(seiMessagesOf(bytes));
        }
      }
    }

    return messages;
  }

  // The payload types of the prefix SEI messages in a sample whose NAL units follow 4-byte lengths.
  private static List<Integer> seiPayloadTypesOf(ByteBuffer sample) {
    var data = sample.duplicate();
    var types = new ArrayList<Integer>();
    while (data.remaining() >= 4) {
      var unit = new byte[data.getInt()];
      data.get(unit);
      if (((Byte.toUnsignedInt(unit[0]) >> 1) & 0x3F) == PREFIX_SEI) {
        seiMessagesOf(unit).forEach(message -> types.add(message.payloadType()));
      }
    }

    return types;
  }

  private static List<SeiMessage> seiMessagesOf(byte[] nalUnit) {
    var rbsp = new ByteArrayOutputStream();
    var zeros = 0;
    for (var index = 2; index < nalUnit.length; index++) {
      var octet = Byte.toUnsignedInt(nalUnit[index]);
      if (zeros >= 2 && octet == 3) {
        zeros = 0;
        continue;
      }

      zeros = octet == 0 ? zeros + 1 : 0;
      rbsp.write(octet);
    }

    var reading = ByteBuffer.wrap(rbsp.toByteArray());
    var messages = new ArrayList<SeiMessage>();
    while (reading.remaining() > 1) {
      var type = codedValue(reading);
      var payload = new byte[codedValue(reading)];
      reading.get(payload);
      messages.add(new SeiMessage(type, HexFormat.of().withUpperCase().formatHex(payload)));
    }

    assertThat(Byte.toUnsignedInt(reading.get())).as("the RBSP stop byte").isEqualTo(0x80);
    return messages;
  }

  // An SEI payload type or size: each 0xFF byte adds 255 until another byte ends the value.
  private static int codedValue(ByteBuffer reading) {
    var value = 0;
    var octet = 0xFF;
    while (octet == 0xFF) {
      octet = Byte.toUnsignedInt(reading.get());
      value += octet;
    }

    return value;
  }

  // The first sample entry of the first track: its type, then its fields and child boxes.
  static Box sampleEntry(byte[] initialization) {
    var box = new Box("file", initialization);
    for (var type : SAMPLE_ENTRY_PATH) {
      var fields = type.equals("stsd") ? 8 : 0;
      box = new Box(type, child(box, 0, type));
      if (fields > 0) {
        return firstChild(box, fields);
      }
    }

    throw new AssertionError("unreachable");
  }

  // The body of the first child of the type, after the box's first fields.
  static byte[] child(Box box, int fields, String type) {
    return children(box, fields).stream()
        .filter(child -> child.type().equals(type))
        .findFirst()
        .map(Box::body)
        .orElseThrow(() -> new AssertionError(box.type() + " holds no " + type));
  }

  static List<Box> children(Box box, int fields) {
    var children = new ArrayList<Box>();
    var reading = ByteBuffer.wrap(box.body());
    reading.position(fields);
    while (reading.remaining() >= 8) {
      var size = (int) Integer.toUnsignedLong(reading.getInt());
      var type = new byte[4];
      reading.get(type);
      var body = new byte[size - 8];
      reading.get(body);
      children.add(new Box(new String(type, StandardCharsets.US_ASCII), body));
    }

    return children;
  }

  private static Box firstChild(Box box, int fields) {
    return children(box, fields).getFirst();
  }

  /** A box's type and body. */
  record Box(String type, byte[] body) {}

  private static void runFfmpeg(List<String> arguments, Path output) throws Exception {
    var command = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y"));
    command.addAll(arguments);
    command.add(output.toString());
    runToCompletion(command);
  }

  private static String runToCompletion(List<String> command)
      throws IOException, InterruptedException {
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertThat(process.waitFor(OUTCOME_LIMIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).as("%s: %s", command, output).isZero();
    return output;
  }
}
