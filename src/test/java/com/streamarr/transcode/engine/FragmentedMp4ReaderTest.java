package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.IsoBoxes.box;
import static com.streamarr.transcode.engine.IsoBoxes.concat;
import static com.streamarr.transcode.engine.IsoBoxes.ftyp;
import static com.streamarr.transcode.engine.IsoBoxes.header;
import static com.streamarr.transcode.engine.IsoBoxes.largeSizeBox;
import static com.streamarr.transcode.engine.IsoBoxes.largeSizeHeader;
import static com.streamarr.transcode.engine.IsoBoxes.mdat;
import static com.streamarr.transcode.engine.IsoBoxes.moof;
import static com.streamarr.transcode.engine.IsoBoxes.videoAndAudioMoov;
import static com.streamarr.transcode.engine.IsoBoxes.videoTraf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
class FragmentedMp4ReaderTest {

  private static final long SEGMENT_CAP = 16L * 1024 * 1024;

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
  @DisplayName("Should fail when a box other than mdat follows a moof")
  void shouldFailWhenABoxOtherThanMdatFollowsAMoof() {
    var reader = readerOf(concat(ftyp(), videoAndAudioMoov(), videoMoof(0), videoMoof(1)));

    assertFailure(reader, Reason.UNEXPECTED_BOX);
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
