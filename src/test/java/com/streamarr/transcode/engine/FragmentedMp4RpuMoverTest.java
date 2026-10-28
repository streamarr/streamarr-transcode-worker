package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.HevcCopies.aud;
import static com.streamarr.transcode.engine.HevcCopies.fragmentBuilder;
import static com.streamarr.transcode.engine.HevcCopies.hevcSampleEntry;
import static com.streamarr.transcode.engine.HevcCopies.initializationSegment;
import static com.streamarr.transcode.engine.HevcCopies.nalWithLength;
import static com.streamarr.transcode.engine.HevcCopies.rpu;
import static com.streamarr.transcode.engine.HevcCopies.sample;
import static com.streamarr.transcode.engine.HevcCopies.slice;
import static com.streamarr.transcode.engine.HevcCopies.suffixSei;
import static com.streamarr.transcode.engine.IsoBoxes.box;
import static com.streamarr.transcode.engine.IsoBoxes.concat;
import static com.streamarr.transcode.engine.IsoBoxes.fullBox;
import static com.streamarr.transcode.engine.IsoBoxes.u32;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Fragmented MP4 RPU Mover Tests")
class FragmentedMp4RpuMoverTest {

  private static final byte[] AUDIO_FRAME = {0x21, 0x10, 0x04};

  @Test
  @DisplayName(
      "Should move each RPU that starts a sample to the end of the previous picture's sample, across"
          + " fragments, when the RPUs belong to the picture before")
  void
      shouldMoveEachRpuThatStartsASampleToTheEndOfThePreviousPicturesSampleAcrossFragmentsWhenTheRpusBelongToThePictureBefore()
          throws IOException {
    var copy =
        stream(
            fragmentBuilder().video(0, sample(aud(), slice(0))).audio(0, AUDIO_FRAME).build(),
            fragmentBuilder()
                .video(1001, sample(aud(), rpu(0), slice(1)))
                .audio(1024, AUDIO_FRAME)
                .build(),
            fragmentBuilder().video(2002, sample(aud(), rpu(1), slice(2))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved))
        .containsExactly(
            hex(aud(), slice(0), rpu(0)), hex(aud(), slice(1), rpu(1)), hex(aud(), slice(2)));
    assertThat(timingOf(moved)).isEqualTo(timingOf(copy));
    assertThat(audioSamplesOf(moved)).isEqualTo(audioSamplesOf(copy));
  }

  @Test
  @DisplayName(
      "Should drop the RPU that starts the first sample when the RPUs belong to the picture before")
  void shouldDropTheRpuThatStartsTheFirstSampleWhenTheRpusBelongToThePictureBefore()
      throws IOException {
    var copy =
        stream(
            fragmentBuilder().video(0, sample(aud(), rpu(9), slice(0))).build(),
            fragmentBuilder().video(1001, sample(aud(), rpu(0), slice(1))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved))
        .containsExactly(hex(aud(), slice(0), rpu(0)), hex(aud(), slice(1)));
  }

  @Test
  @DisplayName(
      "Should move an RPU that follows a slice to the end of its sample when another NAL unit"
          + " follows it")
  void shouldMoveAnRpuThatFollowsASliceToTheEndOfItsSampleWhenAnotherNalUnitFollowsIt()
      throws IOException {
    var copy = stream(fragmentBuilder().video(0, sample(slice(0), rpu(0), suffixSei(0))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(slice(0), suffixSei(0), rpu(0)));
  }

  @Test
  @DisplayName(
      "Should keep the RPU that ends a sample last when an earlier RPU follows the sample's slice")
  void shouldKeepTheRpuThatEndsASampleLastWhenAnEarlierRpuFollowsTheSamplesSlice()
      throws IOException {
    var copy =
        stream(fragmentBuilder().video(0, sample(slice(0), rpu(8), suffixSei(0), rpu(0))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(slice(0), suffixSei(0), rpu(8), rpu(0)));
  }

  @Test
  @DisplayName("Should pass the copy unchanged when every RPU ends its own picture's sample")
  void shouldPassTheCopyUnchangedWhenEveryRpuEndsItsOwnPicturesSample() throws IOException {
    var copy =
        stream(
            fragmentBuilder()
                .video(0, sample(aud(), slice(0), rpu(0)))
                .audio(0, AUDIO_FRAME)
                .build(),
            fragmentBuilder().audio(1024, AUDIO_FRAME).build(),
            fragmentBuilder()
                .video(1001, sample(aud(), slice(1), rpu(1)), sample(slice(2), rpu(2)))
                .build());

    assertThat(move(copy)).isEqualTo(copy);
  }

  @Test
  @DisplayName(
      "Should keep the fragments in order when audio fragments arrive while the previous picture"
          + " awaits its RPU")
  void shouldKeepTheFragmentsInOrderWhenAudioFragmentsArriveWhileThePreviousPictureAwaitsItsRpu()
      throws IOException {
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0))).build(),
            fragmentBuilder().audio(0, new byte[] {1}).build(),
            fragmentBuilder().audio(1024, new byte[] {2}).build(),
            fragmentBuilder().video(1001, sample(rpu(0), slice(1))).build());

    var moved = move(copy);

    var fragments = Mp4Stream.read(Mp4Stream.readerOf(moved)).fragments();
    assertThat(fragments)
        .extracting(
            fragment -> fragment.videoSamples().size() + "v" + fragment.audioSamples().size())
        .containsExactly("1v0", "0v1", "0v1", "1v0");
    assertThat(videoSamplesOf(moved)).containsExactly(hex(slice(0), rpu(0)), hex(slice(1)));
    assertThat(audioSamplesOf(moved)).containsExactly("01", "02");
  }

  @Test
  @DisplayName(
      "Should pass on what it holds, and drop the next RPU that starts a sample, when audio past"
          + " the previous picture exceeds what the mover holds")
  void
      shouldPassOnWhatItHoldsAndDropTheNextRpuThatStartsASampleWhenAudioPastThePreviousPictureExceedsWhatTheMoverHolds()
          throws IOException {
    var half = new byte[Math.toIntExact(FragmentedMp4RpuMover.MAXIMUM_HELD_BYTES / 2)];
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0))).build(),
            fragmentBuilder().audio(0, half).build(),
            fragmentBuilder().audio(1024, half).build(),
            fragmentBuilder().video(1001, sample(rpu(0), slice(1))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(slice(0)), hex(slice(1)));
    assertThat(Mp4Stream.read(Mp4Stream.readerOf(moved)).fragments()).hasSize(4);
  }

  @Test
  @DisplayName(
      "Should copy the RPU of a sample that holds no slice behind the previous picture and keep that"
          + " sample's bytes when the RPUs belong to the picture before")
  void
      shouldCopyTheRpuOfASampleThatHoldsNoSliceBehindThePreviousPictureAndKeepThatSamplesBytesWhenTheRpusBelongToThePictureBefore()
          throws IOException {
    var copy =
        stream(
            fragmentBuilder().video(0, sample(rpu(9), slice(0))).build(),
            fragmentBuilder().video(1001, sample(rpu(0))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(slice(0), rpu(0)), hex(rpu(0)));
  }

  @Test
  @DisplayName(
      "Should end the previous picture with only the last RPU that the samples after it move there"
          + " when several samples carry RPUs for it")
  void
      shouldEndThePreviousPictureWithOnlyTheLastRpuThatTheSamplesAfterItMoveThereWhenSeveralSamplesCarryRpusForIt()
          throws IOException {
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0))).build(),
            fragmentBuilder().video(1001, sample(rpu(1), rpu(2))).build(),
            fragmentBuilder().video(2002, sample(rpu(3))).build(),
            fragmentBuilder().video(3003, sample(rpu(4), slice(1))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved))
        .containsExactly(hex(slice(0), rpu(4)), hex(rpu(1), rpu(2)), hex(rpu(3)), hex(slice(1)));
  }

  @Test
  @DisplayName(
      "Should keep the bytes of a sample it cannot delimit, and drop the next RPU that starts a"
          + " sample, when the RPUs belong to the picture before")
  void
      shouldKeepTheBytesOfASampleItCannotDelimitAndDropTheNextRpuThatStartsASampleWhenTheRpusBelongToThePictureBefore()
          throws IOException {
    var undelimited = concat(slice(0), u32(64));
    var copy =
        stream(
            fragmentBuilder().video(0, undelimited).build(),
            fragmentBuilder().video(1001, sample(rpu(0), slice(1))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(undelimited), hex(slice(1)));
  }

  @ParameterizedTest(name = "{0}-byte lengths")
  @ValueSource(ints = {1, 2})
  @DisplayName("Should delimit NAL units by the length size that the hvcC declares")
  void shouldDelimitNalUnitsByTheLengthSizeThatTheHvcCDeclares(int lengthSize) throws IOException {
    var shortRpu = nalWithLength(lengthSize, 62, 25, 7);
    var shortSlice = nalWithLength(lengthSize, HevcCopies.TRAIL_R, 0xAF, 1);
    var copy =
        concat(
            initializationSegment(hevcSampleEntry("hvc1", lengthSize)),
            fragmentBuilder().video(0, shortSlice, concat(shortRpu, shortSlice)).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(shortSlice, shortRpu), hex(shortSlice));
  }

  @Test
  @DisplayName("Should fail as an output mismatch when the copy declares video other than HEVC")
  void shouldFailAsAnOutputMismatchWhenTheCopyDeclaresVideoOtherThanHevc() {
    var copy =
        concat(
            initializationSegment(box("avc1", new byte[78], box("avcC", new byte[7]))),
            fragmentBuilder().video(0, sample(slice(0))).build());

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> move(copy))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.OUTPUT_MISMATCH);
  }

  @Test
  @DisplayName("Should fail as an unexpected box when a traf holds a box FFmpeg does not write")
  void shouldFailAsAnUnexpectedBoxWhenATrafHoldsABoxFfmpegDoesNotWrite() {
    var withSampleDependencies =
        fragmentBuilder()
            .video(0, sample(slice(0)))
            .extraVideoTrafBox(fullBox("sdtp", 0, new byte[1]))
            .build();

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> move(concat(initializationSegment(), withSampleDependencies)))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.UNEXPECTED_BOX);
  }

  @Test
  @DisplayName(
      "Should fail as an unexpected box when a tfhd counts data offsets from other than its moof")
  void shouldFailAsAnUnexpectedBoxWhenATfhdCountsDataOffsetsFromOtherThanItsMoof() {
    // Without default-base-is-moof, the first traf's data still counts from its moof's first byte.
    var moof = moofCountingFromItsStart(0);
    moof = moofCountingFromItsStart(moof.length + 8);
    var copy = concat(initializationSegment(), moof, box("mdat", new byte[1]));

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> move(copy))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.UNEXPECTED_BOX);
  }

  @ParameterizedTest(name = "{0} runs")
  @ValueSource(ints = {2, 4096})
  @DisplayName(
      "Should fail as a malformed box when the runs of a fragment place more sample bytes than its"
          + " mdat holds")
  void shouldFailAsAMalformedBoxWhenTheRunsOfAFragmentPlaceMoreSampleBytesThanItsMdatHolds(
      int runs) {
    var picture = sample(rpu(0), aud(), slice(0));
    var moof = moofOfRunsOverOneSample(runs, picture.length, 0);
    moof = moofOfRunsOverOneSample(runs, picture.length, moof.length + 8);
    var copy = concat(initializationSegment(), moof, box("mdat", picture));

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> move(copy))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.MALFORMED_BOX);
  }

  @Test
  @DisplayName(
      "Should fail as exceeding the segment cap when a fragment declares more samples than the"
          + " mover's fragment budget holds")
  void
      shouldFailAsExceedingTheSegmentCapWhenAFragmentDeclaresMoreSamplesThanTheMoversFragmentBudgetHolds() {
    var samples = FragmentedMp4RpuMover.MAXIMUM_FRAGMENT_BYTES / Fragment.HEAP_BYTES_PER_SAMPLE + 1;
    var moof = moofOfEmptySamples(samples, 0);
    moof = moofOfEmptySamples(samples, moof.length + 8);
    var copy = concat(initializationSegment(), moof, box("mdat", new byte[0]));

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> move(copy))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.EXCEEDS_SEGMENT_CAP);
  }

  @Test
  @DisplayName(
      "Should keep a sample's bytes when it holds more NAL units than an HEVC picture's sample can")
  void shouldKeepASamplesBytesWhenItHoldsMoreNalUnitsThanAnHevcPicturesSampleCan()
      throws IOException {
    var units = new byte[HevcNalUnit.MAXIMUM_NAL_UNITS_PER_SAMPLE + 1][];
    units[0] = rpu(0);
    Arrays.fill(units, 1, units.length - 1, aud());
    units[units.length - 1] = slice(0);
    var copy = stream(fragmentBuilder().video(0, sample(units)).build());

    assertThat(move(copy)).isEqualTo(copy);
  }

  @Test
  @DisplayName("Should fail as the end of the file in a box body when the copy ends inside an mdat")
  void shouldFailAsTheEndOfTheFileInABoxBodyWhenTheCopyEndsInsideAnMdat() {
    var copy = stream(fragmentBuilder().video(0, sample(slice(0))).build());
    var truncated = Arrays.copyOf(copy, copy.length - 2);

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> move(truncated))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.END_OF_FILE_IN_BOX_BODY);
  }

  @Test
  @DisplayName("Should write nothing when the copy is empty")
  void shouldWriteNothingWhenTheCopyIsEmpty() throws IOException {
    assertThat(move(new byte[0])).isEmpty();
  }

  private static byte[] moofCountingFromItsStart(int dataOffset) {
    return box(
        "moof",
        fullBox("mfhd", 0, u32(1)),
        box(
            "traf",
            fullBox("tfhd", IsoBoxes.TFHD_DEFAULT_SAMPLE_SIZE, u32(1), u32(1)),
            fullBox("tfdt", 0, u32(0)),
            fullBox("trun", IsoBoxes.TRUN_DATA_OFFSET, u32(1), u32(dataOffset))));
  }

  // Runs of one video sample each, which all place that sample at the same data offset.
  private static byte[] moofOfRunsOverOneSample(int runs, int sampleBytes, int dataOffset) {
    var run = fullBox("trun", IsoBoxes.TRUN_DATA_OFFSET, u32(1), u32(dataOffset));
    var trafChildren = new byte[runs + 2][];
    trafChildren[0] =
        fullBox(
            "tfhd",
            IsoBoxes.TFHD_DEFAULT_BASE_IS_MOOF
                | IsoBoxes.TFHD_DEFAULT_SAMPLE_DURATION
                | IsoBoxes.TFHD_DEFAULT_SAMPLE_SIZE
                | IsoBoxes.TFHD_DEFAULT_SAMPLE_FLAGS,
            u32(IsoBoxes.VIDEO_TRACK_ID),
            u32(HevcCopies.VIDEO_SAMPLE_DURATION),
            u32(sampleBytes),
            u32(IsoBoxes.SYNC_SAMPLE_FLAGS));
    trafChildren[1] = fullBox("tfdt", IsoBoxes.VERSION_1, IsoBoxes.u64(0));
    Arrays.fill(trafChildren, 2, trafChildren.length, run);
    return box("moof", fullBox("mfhd", 0, u32(1)), box("traf", trafChildren));
  }

  private static byte[] moofOfEmptySamples(long samples, int dataOffset) {
    return box(
        "moof",
        fullBox("mfhd", 0, u32(1)),
        box(
            "traf",
            fullBox(
                "tfhd",
                IsoBoxes.TFHD_DEFAULT_BASE_IS_MOOF
                    | IsoBoxes.TFHD_DEFAULT_SAMPLE_DURATION
                    | IsoBoxes.TFHD_DEFAULT_SAMPLE_SIZE,
                u32(IsoBoxes.VIDEO_TRACK_ID),
                u32(HevcCopies.VIDEO_SAMPLE_DURATION),
                u32(0)),
            fullBox("tfdt", IsoBoxes.VERSION_1, IsoBoxes.u64(0)),
            fullBox("trun", IsoBoxes.TRUN_DATA_OFFSET, u32(samples), u32(dataOffset))));
  }

  private static byte[] stream(byte[]... fragments) {
    return concat(initializationSegment(), concat(fragments));
  }

  private static byte[] move(byte[] copy) throws IOException {
    var output = new ByteArrayOutputStream();
    FragmentedMp4RpuMover.copy(new ByteArrayInputStream(copy), output);
    return output.toByteArray();
  }

  private static List<String> videoSamplesOf(byte[] stream) throws IOException {
    return Mp4Stream.read(Mp4Stream.readerOf(stream)).fragments().stream()
        .flatMap(fragment -> bytesOf(fragment, fragment.videoSamples()).stream())
        .toList();
  }

  private static List<String> audioSamplesOf(byte[] stream) throws IOException {
    return Mp4Stream.read(Mp4Stream.readerOf(stream)).fragments().stream()
        .flatMap(fragment -> bytesOf(fragment, fragment.audioSamples()).stream())
        .toList();
  }

  private static List<String> bytesOf(Fragment fragment, List<Sample> samples) {
    return samples.stream()
        .map(
            sample ->
                HexFormat.of()
                    .formatHex(
                        fragment.mediaDataBox(),
                        sample.mediaDataPosition(),
                        sample.mediaDataPosition() + Math.toIntExact(sample.size())))
        .toList();
  }

  // Every sample's track, decode time, composition offset, duration and flags, in order.
  private static List<String> timingOf(byte[] stream) throws IOException {
    return Mp4Stream.read(Mp4Stream.readerOf(stream)).fragments().stream()
        .flatMap(
            fragment ->
                Stream.concat(
                    fragment.videoSamples().stream().map(sample -> "video " + timing(sample)),
                    fragment.audioSamples().stream().map(sample -> "audio " + timing(sample))))
        .toList();
  }

  private static String timing(Sample sample) {
    return sample.decodeTime()
        + "/"
        + sample.compositionOffset()
        + "/"
        + sample.duration()
        + "/"
        + Integer.toHexString(sample.flags());
  }

  private static String hex(byte[]... nalUnits) {
    return HexFormat.of().formatHex(concat(nalUnits));
  }
}
