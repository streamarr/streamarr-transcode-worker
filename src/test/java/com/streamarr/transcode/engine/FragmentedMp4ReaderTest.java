package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.IsoBoxes.NON_SYNC_SAMPLE_FLAGS;
import static com.streamarr.transcode.engine.IsoBoxes.SYNC_SAMPLE_FLAGS;
import static com.streamarr.transcode.engine.IsoBoxes.VIDEO_TRACK_ID;
import static com.streamarr.transcode.engine.IsoBoxes.audioTraf;
import static com.streamarr.transcode.engine.IsoBoxes.box;
import static com.streamarr.transcode.engine.IsoBoxes.concat;
import static com.streamarr.transcode.engine.IsoBoxes.ftyp;
import static com.streamarr.transcode.engine.IsoBoxes.fullBox;
import static com.streamarr.transcode.engine.IsoBoxes.header;
import static com.streamarr.transcode.engine.IsoBoxes.largeSizeBox;
import static com.streamarr.transcode.engine.IsoBoxes.largeSizeHeader;
import static com.streamarr.transcode.engine.IsoBoxes.mdat;
import static com.streamarr.transcode.engine.IsoBoxes.moof;
import static com.streamarr.transcode.engine.IsoBoxes.moov;
import static com.streamarr.transcode.engine.IsoBoxes.u32;
import static com.streamarr.transcode.engine.IsoBoxes.u64;
import static com.streamarr.transcode.engine.IsoBoxes.videoAndAudioMoov;
import static com.streamarr.transcode.engine.IsoBoxes.videoTraf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.streamarr.transcode.engine.IsoBoxes.Track;
import com.streamarr.transcode.engine.IsoBoxes.TrackFragment;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
class FragmentedMp4ReaderTest {

  private static final long SEGMENT_CAP = 16L * 1024 * 1024;
  private static final long VIDEO_TIMESCALE = 24_000;

  @Test
  @DisplayName("Should read the initialization segment when the stream starts with ftyp and moov")
  void shouldReadTheInitializationSegmentWhenTheStreamStartsWithFtypAndMoov() throws IOException {
    var initialization = concat(ftyp(), videoAndAudioMoov());
    var reader = readerOf(initialization);

    assertThat(reader.next()).contains(new InitializationSegment(initialization));
  }

  @Test
  @DisplayName("Should read each fragment as its moof and mdat when they follow the initialization")
  void shouldReadEachFragmentAsItsMoofAndMdatWhenTheyFollowTheInitialization() throws IOException {
    var firstMoof = moof(videoTraf().baseMediaDecodeTime(0L).build());
    var firstMdat = mdat(64);
    var secondMoof = moof(videoTraf().baseMediaDecodeTime(24_000L).build());
    var secondMdat = mdat(32);
    var reader =
        readerOf(concat(ftyp(), videoAndAudioMoov(), firstMoof, firstMdat, secondMoof, secondMdat));

    reader.next();

    assertThat(nextFragment(reader).boxes()).containsExactly(firstMoof, firstMdat);
    assertThat(nextFragment(reader).boxes()).containsExactly(secondMoof, secondMdat);
  }

  @Test
  @DisplayName("Should report the end when the stream ends on a box boundary after a fragment")
  void shouldReportTheEndWhenTheStreamEndsOnABoxBoundaryAfterAFragment() throws IOException {
    var reader = readerOf(concat(ftyp(), videoAndAudioMoov(), videoMoof(0), mdat(8)));

    reader.next();
    reader.next();

    assertThat(reader.next()).isEmpty();
    assertThat(reader.next()).isEmpty();
  }

  @Test
  @DisplayName("Should report the end when the stream is empty")
  void shouldReportTheEndWhenTheStreamIsEmpty() throws IOException {
    assertThat(readerOf(new byte[0]).next()).isEmpty();
  }

  @Test
  @DisplayName("Should report a fragment's byte length when it holds a moof and an mdat")
  void shouldReportAFragmentsByteLengthWhenItHoldsAMoofAndAnMdat() throws IOException {
    var moof = videoMoof(0);
    var reader = readerOf(concat(ftyp(), videoAndAudioMoov(), moof, mdat(100)));

    reader.next();

    assertThat(nextFragment(reader).byteLength()).isEqualTo(moof.length + 108L);
  }

  @Test
  @DisplayName("Should fail when the stream ends inside a box header")
  void shouldFailWhenTheStreamEndsInsideABoxHeader() {
    var moof = videoMoof(0);
    var reader = readerOf(concat(ftyp(), videoAndAudioMoov(), Arrays.copyOf(moof, 5)));

    assertFailure(reader, Reason.END_OF_FILE_IN_BOX_HEADER);
  }

  @Test
  @DisplayName("Should fail when the stream ends inside a 64-bit box header")
  void shouldFailWhenTheStreamEndsInsideA64BitBoxHeader() {
    var header = largeSizeHeader(4096, "moof");
    var reader = readerOf(concat(ftyp(), videoAndAudioMoov(), Arrays.copyOf(header, 12)));

    assertFailure(reader, Reason.END_OF_FILE_IN_BOX_HEADER);
  }

  @Test
  @DisplayName("Should fail when the stream ends inside a box body")
  void shouldFailWhenTheStreamEndsInsideABoxBody() {
    var mdat = mdat(64);
    var reader =
        readerOf(
            concat(
                ftyp(), videoAndAudioMoov(), videoMoof(0), Arrays.copyOf(mdat, mdat.length - 1)));

    assertFailure(reader, Reason.END_OF_FILE_IN_BOX_BODY);
  }

  @Test
  @DisplayName("Should fail when the stream ends after a moof with no mdat")
  void shouldFailWhenTheStreamEndsAfterAMoofWithNoMdat() {
    var reader = readerOf(concat(ftyp(), videoAndAudioMoov(), videoMoof(0)));

    assertFailure(reader, Reason.END_OF_FILE_AFTER_MOVIE_FRAGMENT);
  }

  @Test
  @DisplayName("Should read an mdat as it arrived when the mdat declares a 64-bit size")
  void shouldReadAnMdatAsItArrivedWhenTheMdatDeclaresA64BitSize() throws IOException {
    var moof = videoMoof(0);
    var mdat = largeSizeBox("mdat", new byte[40]);
    var reader = readerOf(concat(ftyp(), videoAndAudioMoov(), moof, mdat));

    reader.next();

    assertThat(nextFragment(reader).boxes()).containsExactly(moof, mdat);
  }

  @Test
  @DisplayName("Should fail when a box declares size zero")
  void shouldFailWhenABoxDeclaresSizeZero() {
    var reader =
        readerOf(concat(ftyp(), videoAndAudioMoov(), videoMoof(0), header(0, "mdat"), new byte[8]));

    assertFailure(reader, Reason.UNSIZED_BOX);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 7})
  @DisplayName("Should fail when a box declares a size smaller than its header")
  void shouldFailWhenABoxDeclaresASizeSmallerThanItsHeader(long size) {
    var reader =
        readerOf(concat(ftyp(), videoAndAudioMoov(), largeSizeHeader(size, "moof"), new byte[16]));

    assertFailure(reader, Reason.MALFORMED_BOX);
  }

  @Test
  @DisplayName("Should fail before reading the body when a box exceeds the segment cap")
  void shouldFailBeforeReadingTheBodyWhenABoxExceedsTheSegmentCap() {
    var reader =
        new FragmentedMp4Reader(
            new ByteArrayInputStream(concat(ftyp(), videoAndAudioMoov(), header(1025, "moof"))),
            1024);

    assertFailure(reader, Reason.EXCEEDS_SEGMENT_CAP);
  }

  @ParameterizedTest
  @ValueSource(longs = {1L << 40, Long.MAX_VALUE, -1})
  @DisplayName("Should fail without allocating when a box declares a size far above the heap")
  void shouldFailWithoutAllocatingWhenABoxDeclaresASizeFarAboveTheHeap(long size) {
    var reader =
        readerOf(concat(ftyp(), videoAndAudioMoov(), videoMoof(0), largeSizeHeader(size, "mdat")));

    assertFailure(reader, Reason.EXCEEDS_SEGMENT_CAP);
  }

  @Test
  @DisplayName("Should fail when a fragment's moof and mdat together exceed the segment cap")
  void shouldFailWhenAFragmentsMoofAndMdatTogetherExceedTheSegmentCap() {
    var initialization = concat(ftyp(), videoAndAudioMoov());
    var moof = videoMoof(0);
    var cap = Math.max(initialization.length, moof.length + 100);
    var reader =
        new FragmentedMp4Reader(
            new ByteArrayInputStream(concat(initialization, moof, mdat(cap - moof.length))), cap);

    assertFailure(reader, Reason.EXCEEDS_SEGMENT_CAP);
  }

  @Test
  @DisplayName("Should fail when the initialization segment exceeds the segment cap")
  void shouldFailWhenTheInitializationSegmentExceedsTheSegmentCap() {
    var initialization = concat(ftyp(), videoAndAudioMoov());
    var reader =
        new FragmentedMp4Reader(
            new ByteArrayInputStream(initialization), initialization.length - 1L);

    assertFailure(reader, Reason.EXCEEDS_SEGMENT_CAP);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, -1, Integer.MAX_VALUE})
  @DisplayName("Should reject a segment cap when no array can hold it")
  void shouldRejectASegmentCapWhenNoArrayCanHoldIt(long cap) {
    var stream = new ByteArrayInputStream(new byte[0]);

    assertThatIllegalArgumentException().isThrownBy(() -> new FragmentedMp4Reader(stream, cap));
  }

  @Test
  @DisplayName("Should fail when the stream starts with a box other than ftyp")
  void shouldFailWhenTheStreamStartsWithABoxOtherThanFtyp() {
    var reader = readerOf(concat(videoMoof(0), mdat(8)));

    assertFailure(reader, Reason.MISSING_INITIALIZATION_SEGMENT);
  }

  @Test
  @DisplayName("Should fail when a box other than moov follows ftyp")
  void shouldFailWhenABoxOtherThanMoovFollowsFtyp() {
    var reader = readerOf(concat(ftyp(), videoMoof(0), mdat(8)));

    assertFailure(reader, Reason.MISSING_INITIALIZATION_SEGMENT);
  }

  @Test
  @DisplayName("Should fail when the stream ends after ftyp")
  void shouldFailWhenTheStreamEndsAfterFtyp() {
    assertFailure(readerOf(ftyp()), Reason.MISSING_INITIALIZATION_SEGMENT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"ftyp", "moov"})
  @DisplayName("Should fail when an initialization box arrives after the initialization segment")
  void shouldFailWhenAnInitializationBoxArrivesAfterTheInitializationSegment(String type) {
    var reader = readerOf(concat(ftyp(), videoAndAudioMoov(), videoMoof(0), mdat(8), box(type)));

    assertFailure(reader, Reason.MISPLACED_INITIALIZATION_SEGMENT);
  }

  @ParameterizedTest
  @ValueSource(strings = {"mdat", "free", "styp", "sidx", "mfra"})
  @DisplayName("Should fail when a box other than moof starts a fragment")
  void shouldFailWhenABoxOtherThanMoofStartsAFragment(String type) {
    var reader = readerOf(concat(ftyp(), videoAndAudioMoov(), box(type)));

    assertFailure(reader, Reason.UNEXPECTED_BOX);
  }

  @Test
  @DisplayName("Should describe an unprintable box type with printable characters when it fails")
  void shouldDescribeAnUnprintableBoxTypeWithPrintableCharactersWhenItFails() {
    var type = concat(new byte[] {'\n', (byte) 0xC3}, "o!".getBytes(StandardCharsets.ISO_8859_1));
    var unexpected = concat(ftyp(), videoAndAudioMoov(), u32(8), type);

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> readToEnd(readerOf(unexpected)))
        .withMessageContaining("??o!")
        .withMessageNotContaining("\n");
  }

  @Test
  @DisplayName("Should fail when a box other than mdat follows a moof")
  void shouldFailWhenABoxOtherThanMdatFollowsAMoof() {
    var reader = readerOf(concat(ftyp(), videoAndAudioMoov(), videoMoof(0), videoMoof(1)));

    assertFailure(reader, Reason.UNEXPECTED_BOX);
  }

  @Test
  @DisplayName(
      "Should start a fragment at tfdt plus the first sample's signed offset when trun is v1")
  void shouldStartAFragmentAtTfdtPlusTheFirstSamplesSignedOffsetWhenTrunIsV1() throws IOException {
    var traf = syncVideoTraf().decodeTimeVersion(1).baseMediaDecodeTime(48_048L).runVersion(1);

    assertThat(videoStartOf(traf.compositionOffset(-2002)))
        .contains(new VideoStart(46_046, VIDEO_TIMESCALE, true));
  }

  @Test
  @DisplayName("Should read the first sample's offset as unsigned when trun is version 0")
  void shouldReadTheFirstSamplesOffsetAsUnsignedWhenTrunIsVersion0() throws IOException {
    var traf = syncVideoTraf().baseMediaDecodeTime(0L).runVersion(0).compositionOffset(0x8000_0000);

    assertThat(videoStartOf(traf)).map(VideoStart::presentationTime).contains(2_147_483_648L);
  }

  @Test
  @DisplayName("Should read tfdt as an unsigned 32-bit decode time when tfdt is version 0")
  void shouldReadTfdtAsAnUnsigned32BitDecodeTimeWhenTfdtIsVersion0() throws IOException {
    var traf = syncVideoTraf().decodeTimeVersion(0).baseMediaDecodeTime(0xFFFF_FFF0L);

    assertThat(videoStartOf(traf)).map(VideoStart::presentationTime).contains(4_294_967_280L);
  }

  @Test
  @DisplayName("Should keep a negative decode time when a version 1 tfdt holds one")
  void shouldKeepANegativeDecodeTimeWhenAVersion1TfdtHoldsOne() throws IOException {
    var traf = syncVideoTraf().decodeTimeVersion(1).baseMediaDecodeTime(-1024L);

    assertThat(videoStartOf(traf)).map(VideoStart::presentationTime).contains(-1024L);
  }

  @Test
  @DisplayName("Should start a fragment at tfdt when trun carries no composition offsets")
  void shouldStartAFragmentAtTfdtWhenTrunCarriesNoCompositionOffsets() throws IOException {
    var traf = syncVideoTraf().decodeTimeVersion(1).baseMediaDecodeTime(24_024L);

    assertThat(videoStartOf(traf)).map(VideoStart::presentationTime).contains(24_024L);
  }

  @Test
  @DisplayName("Should read the video timescale when the media header uses 64-bit times")
  void shouldReadTheVideoTimescaleWhenTheMediaHeaderUses64BitTimes() throws IOException {
    var moov = moov(Track.video().headerVersion(1).timescale(90_000).build());

    assertThat(videoStartOf(moov, moof(syncVideoTraf().baseMediaDecodeTime(0L).build())))
        .map(VideoStart::timescale)
        .contains(90_000L);
  }

  @Test
  @DisplayName(
      "Should mark a fragment sync when first-sample flags are sync over a non-sync default")
  void shouldMarkAFragmentSyncWhenFirstSampleFlagsAreSyncOverANonSyncDefault() throws IOException {
    var traf =
        videoTraf()
            .baseMediaDecodeTime(0L)
            .firstSampleFlags(SYNC_SAMPLE_FLAGS)
            .defaultSampleFlags(NON_SYNC_SAMPLE_FLAGS);

    assertThat(videoStartOf(traf)).map(VideoStart::syncSample).contains(true);
  }

  @Test
  @DisplayName(
      "Should mark a fragment non-sync when first-sample flags are non-sync over sync flags")
  void shouldMarkAFragmentNonSyncWhenFirstSampleFlagsAreNonSyncOverSyncFlags() throws IOException {
    var traf =
        videoTraf()
            .baseMediaDecodeTime(0L)
            .firstSampleFlags(NON_SYNC_SAMPLE_FLAGS)
            .sampleFlags(SYNC_SAMPLE_FLAGS);

    assertThat(videoStartOf(traf)).map(VideoStart::syncSample).contains(false);
  }

  @ParameterizedTest
  @CsvSource({"0x02000000, true", "0x01010000, false"})
  @DisplayName("Should read sync from the first sample's flags when trun has no first-sample flags")
  void shouldReadSyncFromTheFirstSamplesFlagsWhenTrunHasNoFirstSampleFlags(
      String sampleFlags, boolean sync) throws IOException {
    var traf =
        videoTraf()
            .baseMediaDecodeTime(0L)
            .sampleCount(3)
            .sampleFlags(Integer.decode(sampleFlags))
            .defaultSampleFlags(sync ? NON_SYNC_SAMPLE_FLAGS : SYNC_SAMPLE_FLAGS);

    assertThat(videoStartOf(traf)).map(VideoStart::syncSample).contains(sync);
  }

  @ParameterizedTest
  @CsvSource({"0x02000000, true", "0x01010000, false"})
  @DisplayName("Should read sync from the tfhd default when trun carries no sample flags")
  void shouldReadSyncFromTheTfhdDefaultWhenTrunCarriesNoSampleFlags(
      String defaultFlags, boolean sync) throws IOException {
    var moov = moov(Track.video().defaultSampleFlags(sync ? NON_SYNC_SAMPLE_FLAGS : 0).build());
    var traf = videoTraf().baseMediaDecodeTime(0L).defaultSampleFlags(Integer.decode(defaultFlags));

    assertThat(videoStartOf(moov, moof(traf.build()))).map(VideoStart::syncSample).contains(sync);
  }

  @ParameterizedTest
  @CsvSource({"0x02000000, true", "0x01010000, false"})
  @DisplayName("Should read sync from the trex default when neither trun nor tfhd carries flags")
  void shouldReadSyncFromTheTrexDefaultWhenNeitherTrunNorTfhdCarriesFlags(
      String trexFlags, boolean sync) throws IOException {
    var moov = moov(Track.video().defaultSampleFlags(Integer.decode(trexFlags)).build());

    assertThat(videoStartOf(moov, moof(videoTraf().baseMediaDecodeTime(0L).build())))
        .map(VideoStart::syncSample)
        .contains(sync);
  }

  @Test
  @DisplayName("Should read the tfhd default flags when every optional tfhd field precedes them")
  void shouldReadTheTfhdDefaultFlagsWhenEveryOptionalTfhdFieldPrecedesThem() throws IOException {
    var moov = moov(Track.video().defaultSampleFlags(SYNC_SAMPLE_FLAGS).build());
    var tfhd =
        fullBox(
            "tfhd",
            0x00003B,
            u32(VIDEO_TRACK_ID),
            u64(4096),
            u32(1),
            u32(1001),
            u32(100),
            u32(NON_SYNC_SAMPLE_FLAGS));
    var trun = fullBox("trun", 1 << 24, u32(1));

    assertThat(videoStartOf(moov, box("moof", box("traf", tfhd, tfdt(3003), trun))))
        .contains(new VideoStart(3003, VIDEO_TIMESCALE, false));
  }

  @Test
  @DisplayName("Should read the first sample's flags and offset when every per-sample field is set")
  void shouldReadTheFirstSamplesFlagsAndOffsetWhenEveryPerSampleFieldIsSet() throws IOException {
    var trun =
        fullBox(
            "trun",
            1 << 24 | 0x000F01,
            u32(2),
            u32(0),
            u32(1001),
            u32(100),
            u32(NON_SYNC_SAMPLE_FLAGS),
            u32(-1001),
            u32(1001),
            u32(90),
            u32(SYNC_SAMPLE_FLAGS),
            u32(0));
    var moov = moov(Track.video().defaultSampleFlags(SYNC_SAMPLE_FLAGS).build());
    var traf = box("traf", tfhd(VIDEO_TRACK_ID), tfdt(3003), trun);

    assertThat(videoStartOf(moov, box("moof", traf)))
        .contains(new VideoStart(2002, VIDEO_TIMESCALE, false));
  }

  @Test
  @DisplayName("Should read the video start when an audio traf precedes the video traf")
  void shouldReadTheVideoStartWhenAnAudioTrafPrecedesTheVideoTraf() throws IOException {
    var moof =
        moof(
            audioTraf().baseMediaDecodeTime(-1024L).decodeTimeVersion(1).build(),
            syncVideoTraf().baseMediaDecodeTime(0L).build());

    assertThat(videoStartOf(videoAndAudioMoov(), moof))
        .contains(new VideoStart(0, VIDEO_TIMESCALE, true));
  }

  @Test
  @DisplayName("Should give a fragment no video start when it carries only audio")
  void shouldGiveAFragmentNoVideoStartWhenItCarriesOnlyAudio() throws IOException {
    var moof = moof(audioTraf().baseMediaDecodeTime(96_000L).build());

    assertThat(videoStartOf(videoAndAudioMoov(), moof)).isEmpty();
  }

  @Test
  @DisplayName("Should give a fragment no video start when its video traf has no samples")
  void shouldGiveAFragmentNoVideoStartWhenItsVideoTrafHasNoSamples() throws IOException {
    var moof =
        moof(
            videoTraf().baseMediaDecodeTime(0L).sampleCount(0).build(),
            audioTraf().baseMediaDecodeTime(0L).build());

    assertThat(videoStartOf(videoAndAudioMoov(), moof)).isEmpty();
  }

  @Test
  @DisplayName("Should give fragments no video start when the movie has no video track")
  void shouldGiveFragmentsNoVideoStartWhenTheMovieHasNoVideoTrack() throws IOException {
    var moov = moov(Track.audio().build());

    assertThat(videoStartOf(moov, moof(audioTraf().baseMediaDecodeTime(0L).build()))).isEmpty();
  }

  @Test
  @DisplayName("Should read the first sample of the next run when an earlier run has no samples")
  void shouldReadTheFirstSampleOfTheNextRunWhenAnEarlierRunHasNoSamples() throws IOException {
    var emptyRun = fullBox("trun", 1 << 24 | 0x000004, u32(0), u32(NON_SYNC_SAMPLE_FLAGS));
    var run = fullBox("trun", 1 << 24 | 0x000804, u32(1), u32(SYNC_SAMPLE_FLAGS), u32(-1001));
    var traf = box("traf", tfhd(VIDEO_TRACK_ID), tfdt(3003), emptyRun, run);

    assertThat(videoStartOf(videoAndAudioMoov(), box("moof", traf)))
        .contains(new VideoStart(2002, VIDEO_TIMESCALE, true));
  }

  @Test
  @DisplayName("Should read nested boxes when they declare 64-bit sizes")
  void shouldReadNestedBoxesWhenTheyDeclare64BitSizes() throws IOException {
    var traf = largeSizeBox("traf", concat(tfhd(VIDEO_TRACK_ID), tfdt(1001), syncRun()));

    assertThat(videoStartOf(videoAndAudioMoov(), box("moof", traf)))
        .contains(new VideoStart(1001, VIDEO_TIMESCALE, true));
  }

  @Test
  @DisplayName("Should fail when the movie declares more than one video track")
  void shouldFailWhenTheMovieDeclaresMoreThanOneVideoTrack() {
    var moov = moov(Track.video().build(), Track.video().trackId(3).build());

    assertFailure(readerOf(concat(ftyp(), moov)), Reason.MULTIPLE_VIDEO_TRACKS);
  }

  @Test
  @DisplayName("Should fail when the movie declares no defaults for its video track")
  void shouldFailWhenTheMovieDeclaresNoDefaultsForItsVideoTrack() {
    var moov = box("moov", Track.video().build().trak(), box("mvex", Track.audio().build().trex()));

    assertFailure(readerOf(concat(ftyp(), moov)), Reason.MALFORMED_BOX);
  }

  @Test
  @DisplayName("Should fail when the video track's timescale is zero")
  void shouldFailWhenTheVideoTracksTimescaleIsZero() {
    assertFailure(
        readerOf(concat(ftyp(), moov(Track.video().timescale(0).build()))), Reason.MALFORMED_BOX);
  }

  @Test
  @DisplayName("Should fail when a video traf holds samples but no tfdt")
  void shouldFailWhenAVideoTrafHoldsSamplesButNoTfdt() {
    var moof = moof(syncVideoTraf().build());

    assertFailure(
        readerOf(concat(ftyp(), videoAndAudioMoov(), moof, mdat(8))), Reason.MALFORMED_BOX);
  }

  @Test
  @DisplayName("Should fail when a video traf's fields end before its flags promise")
  void shouldFailWhenAVideoTrafsFieldsEndBeforeItsFlagsPromise() {
    var traf = box("traf", fullBox("tfhd", 0x000020, u32(VIDEO_TRACK_ID)));

    assertFailure(
        readerOf(concat(ftyp(), videoAndAudioMoov(), box("moof", traf), mdat(8))),
        Reason.MALFORMED_BOX);
  }

  @Test
  @DisplayName("Should fail when the video start does not fit a signed 64-bit time")
  void shouldFailWhenTheVideoStartDoesNotFitASigned64BitTime() {
    var moof =
        moof(
            syncVideoTraf()
                .decodeTimeVersion(1)
                .baseMediaDecodeTime(Long.MAX_VALUE)
                .runVersion(1)
                .compositionOffset(1)
                .build());

    assertFailure(
        readerOf(concat(ftyp(), videoAndAudioMoov(), moof, mdat(8))), Reason.MALFORMED_BOX);
  }

  @ParameterizedTest
  @ValueSource(longs = {0, 4, 4096})
  @DisplayName("Should fail when a nested box's size does not fit inside its parent")
  void shouldFailWhenANestedBoxsSizeDoesNotFitInsideItsParent(long size) {
    var moof = box("moof", header(size, "traf"), new byte[8]);

    assertFailure(
        readerOf(concat(ftyp(), videoAndAudioMoov(), moof, mdat(8))), Reason.MALFORMED_BOX);
  }

  private static void assertFailure(FragmentedMp4Reader reader, Reason reason) {
    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> readToEnd(reader))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(reason);
  }

  private static List<Mp4Unit> readToEnd(FragmentedMp4Reader reader) throws IOException {
    var units = new ArrayList<Mp4Unit>();
    for (var unit = reader.next(); unit.isPresent(); unit = reader.next()) {
      units.add(unit.orElseThrow());
    }

    return units;
  }

  private static TrackFragment.TrackFragmentBuilder syncVideoTraf() {
    return videoTraf().firstSampleFlags(SYNC_SAMPLE_FLAGS);
  }

  private static byte[] tfhd(int trackId) {
    return fullBox("tfhd", 0x020000, u32(trackId));
  }

  private static byte[] tfdt(long baseMediaDecodeTime) {
    return fullBox("tfdt", 1 << 24, u64(baseMediaDecodeTime));
  }

  private static byte[] syncRun() {
    return fullBox("trun", 1 << 24 | 0x000004, u32(1), u32(SYNC_SAMPLE_FLAGS));
  }

  private static Optional<VideoStart> videoStartOf(TrackFragment.TrackFragmentBuilder videoTraf)
      throws IOException {
    return videoStartOf(
        videoAndAudioMoov(), moof(videoTraf.build(), audioTraf().baseMediaDecodeTime(0L).build()));
  }

  private static Optional<VideoStart> videoStartOf(byte[] moov, byte[] moof) throws IOException {
    var reader = readerOf(concat(ftyp(), moov, moof, mdat(8)));
    reader.next();
    return nextFragment(reader).videoStart();
  }

  private static byte[] videoMoof(long baseMediaDecodeTime) {
    return moof(videoTraf().baseMediaDecodeTime(baseMediaDecodeTime).build());
  }

  private static Fragment nextFragment(FragmentedMp4Reader reader) throws IOException {
    return assertThat(reader.next())
        .get()
        .asInstanceOf(InstanceOfAssertFactories.type(Fragment.class))
        .actual();
  }

  private static FragmentedMp4Reader readerOf(byte[] bytes) {
    return new FragmentedMp4Reader(new ByteArrayInputStream(bytes), SEGMENT_CAP);
  }
}
