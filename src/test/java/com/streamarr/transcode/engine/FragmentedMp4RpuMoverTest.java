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
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Fragmented MP4 RPU Mover Tests")
class FragmentedMp4RpuMoverTest {

  private static final byte[] AUDIO_FRAME = {0x21, 0x10, 0x04};
  private static final TimeBase TRACK_TIME_BASE = new TimeBase(1, 24_000);
  private static final int NON_SYNC_SAMPLE = 0x0001_0000;

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

  @ParameterizedTest(name = "{0}")
  @CsvSource({"TemporalId 1, 0x7C, 0x02", "layer 1, 0x7C, 0x09", "layer 32, 0x7D, 0x01"})
  @DisplayName(
      "Should pass the copy unchanged when its type 62 NAL units lie off layer 0 or TemporalId 0")
  void shouldPassTheCopyUnchangedWhenItsType62NalUnitsLieOffLayer0OrTemporalId0(
      String description, int firstHeaderByte, int secondHeaderByte) throws IOException {
    // FFmpeg's HEVC decoder reads a type 62 NAL unit as an RPU only on layer 0 and TemporalId 0.
    var unit = rpu(9);
    unit[4] = (byte) firstHeaderByte;
    unit[5] = (byte) secondHeaderByte;
    var copy =
        stream(
            fragmentBuilder().video(0, sample(aud(), slice(0))).build(),
            fragmentBuilder()
                .video(1001, sample(aud(), unit, slice(1)), sample(slice(2), unit, suffixSei(2)))
                .build());

    assertThat(move(copy)).as(description).isEqualTo(copy);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "forbidden_zero_bit 1, C6 01 50",
    "nuh_temporal_id_plus1 0, 46 00 50",
    "nuh_layer_id 63, 47 F9 50",
    "one byte, 46"
  })
  @DisplayName(
      "Should pass the copy unchanged when a NAL unit that FFmpeg's splitter drops follows the RPU"
          + " that ends its picture")
  void
      shouldPassTheCopyUnchangedWhenANalUnitThatFfmpegsSplitterDropsFollowsTheRpuThatEndsItsPicture(
          String description, String dropped) throws IOException {
    var copy =
        stream(
            fragmentBuilder()
                .video(
                    0,
                    sample(slice(0), rpu(0), unit(dropped)),
                    sample(slice(1), rpu(1), unit(dropped), unit(dropped)))
                .build());

    assertThat(move(copy)).as(description).isEqualTo(copy);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "forbidden_zero_bit 1, 82 01 AF 01",
    "nuh_temporal_id_plus1 0, 02 00 AF 01",
    "nuh_layer_id 63, 03 F9 AF 01"
  })
  @DisplayName(
      "Should move the RPU that a slice which FFmpeg's splitter drops precedes behind the previous"
          + " picture")
  void shouldMoveTheRpuThatASliceWhichFfmpegsSplitterDropsPrecedesBehindThePreviousPicture(
      String description, String dropped) throws IOException {
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0))).build(),
            fragmentBuilder().video(1001, sample(unit(dropped), rpu(0), slice(1))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved))
        .as(description)
        .containsExactly(hex(slice(0), rpu(0)), hex(unit(dropped), slice(1)));
  }

  @Test
  @DisplayName(
      "Should move an RPU that starts a sample behind the previous picture when the sample holds a"
          + " NAL unit of one byte, which FFmpeg's splitter drops")
  void
      shouldMoveAnRpuThatStartsASampleBehindThePreviousPictureWhenTheSampleHoldsANalUnitOfOneByteWhichFfmpegsSplitterDrops()
          throws IOException {
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0))).build(),
            fragmentBuilder().video(1001, sample(rpu(0), slice(1), unit("46"))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved))
        .containsExactly(hex(slice(0), rpu(0)), hex(slice(1), unit("46")));
  }

  @Test
  @DisplayName(
      "Should keep the RPU that FFmpeg's decoder reads last when NAL units that FFmpeg's splitter"
          + " drops end the sample")
  void shouldKeepTheRpuThatFfmpegsDecoderReadsLastWhenNalUnitsThatFfmpegsSplitterDropsEndTheSample()
      throws IOException {
    var dropped = unit("46 00 50");
    var copy =
        stream(
            fragmentBuilder()
                .video(0, sample(slice(0), rpu(8), suffixSei(0), rpu(0), dropped))
                .build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved))
        .containsExactly(hex(slice(0), suffixSei(0), rpu(8), rpu(0), dropped));
  }

  @Test
  @DisplayName(
      "Should move an RPU behind the NAL unit that a start code inside a dropped NAL unit's length"
          + " begins, which FFmpeg's splitter hands to the decoder")
  void
      shouldMoveAnRpuBehindTheNalUnitThatAStartCodeInsideADroppedNalUnitsLengthBeginsWhichFfmpegsSplitterHandsToTheDecoder()
          throws IOException {
    // FFmpeg ends the dropped NAL unit at the start code and reads the AUD that follows it.
    var embedding = unit("C6 01 50 00 00 01 46 01 50");
    var copy = stream(fragmentBuilder().video(0, sample(slice(0), rpu(0), embedding)).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(slice(0), embedding, rpu(0)));
  }

  @Test
  @DisplayName(
      "Should keep an RPU with its picture when a start code inside a dropped NAL unit's length"
          + " begins the picture's slice")
  void
      shouldKeepAnRpuWithItsPictureWhenAStartCodeInsideADroppedNalUnitsLengthBeginsThePicturesSlice()
          throws IOException {
    // FFmpeg drops the NAL unit whose forbidden_zero_bit is 1 and decodes the slice behind it.
    var embedding = unit("82 01 AF 01 00 00 01 02 01 AF 01");
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0), rpu(0))).build(),
            fragmentBuilder().video(1001, sample(embedding, rpu(1))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved))
        .containsExactly(hex(slice(0), rpu(0)), hex(embedding, rpu(1)));
  }

  @Test
  @DisplayName(
      "Should pass the copy unchanged when a start code inside a dropped NAL unit's length begins"
          + " the RPU that ends the sample")
  void
      shouldPassTheCopyUnchangedWhenAStartCodeInsideADroppedNalUnitsLengthBeginsTheRpuThatEndsTheSample()
          throws IOException {
    // FFmpeg drops the NAL unit whose forbidden_zero_bit is 1 and reads the RPU behind it last.
    var endingRpu = unit("C6 01 50 00 00 01 7C 01 19 00");
    var copy = stream(fragmentBuilder().video(0, sample(slice(0), rpu(9), endingRpu)).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(slice(0), rpu(9), endingRpu));
  }

  @Test
  @DisplayName(
      "Should pass the copy unchanged when the last NAL unit of a sample takes fewer than 4 bytes"
          + " with its length, which FFmpeg's splitter never reads")
  void
      shouldPassTheCopyUnchangedWhenTheLastNalUnitOfASampleTakesFewerThan4BytesWithItsLengthWhichFfmpegsSplitterNeverReads()
          throws IOException {
    var shortRpu = nalWithLength(1, 62, 25, 7);
    var shortSlice = nalWithLength(1, HevcCopies.TRAIL_R, 0xAF, 1);
    var shortAud = nalWithLength(1, HevcCopies.AUD);
    var copy =
        concat(
            initializationSegment(hevcSampleEntry("hvc1", 1)),
            fragmentBuilder().video(0, concat(shortSlice, shortRpu, shortAud)).build());

    assertThat(move(copy)).isEqualTo(copy);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "a 1-byte length of 0, 1, 00 46",
    "a 1-byte length that runs past the end, 1, FF 46 01",
    "1 byte under 4-byte lengths, 4, 00",
    "3 bytes under 4-byte lengths, 4, FF FF FF"
  })
  @DisplayName(
      "Should move an RPU that starts a sample behind the previous picture when 1 to 3 bytes, which"
          + " FFmpeg's splitter drops whatever they hold, end the sample")
  void shouldMoveAnRpuThatStartsASampleBehindThePreviousPictureWhen1To3BytesEndTheSample(
      String description, int lengthSize, String tail) throws IOException {
    var firstSlice = nalWithLength(lengthSize, HevcCopies.TRAIL_R, 0xAF, 0);
    var secondSlice = nalWithLength(lengthSize, HevcCopies.TRAIL_R, 0xAF, 1);
    var rpu = nalWithLength(lengthSize, 62, 25, 0);
    var dropped = HexFormat.ofDelimiter(" ").parseHex(tail);
    var copy =
        concat(
            initializationSegment(hevcSampleEntry("hvc1", lengthSize)),
            fragmentBuilder().video(0, firstSlice, concat(rpu, secondSlice, dropped)).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved))
        .as(description)
        .containsExactly(hex(firstSlice, rpu), hex(secondSlice, dropped));
  }

  @Test
  @DisplayName(
      "Should keep the 1 to 3 bytes that FFmpeg's splitter drops last when an RPU moves to the end"
          + " of their sample")
  void shouldKeepThe1To3BytesThatFfmpegsSplitterDropsLastWhenAnRpuMovesToTheEndOfTheirSample()
      throws IOException {
    var dropped = new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    var copy = stream(fragmentBuilder().video(0, sample(slice(0), rpu(0), aud(), dropped)).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(slice(0), aud(), rpu(0), dropped));
  }

  @Test
  @DisplayName(
      "Should keep the 1 to 3 bytes that FFmpeg's splitter drops last when an RPU moves behind"
          + " their picture")
  void shouldKeepThe1To3BytesThatFfmpegsSplitterDropsLastWhenAnRpuMovesBehindTheirPicture()
      throws IOException {
    var dropped = new byte[] {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0), dropped)).build(),
            fragmentBuilder().video(1001, sample(rpu(0), slice(1))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved))
        .containsExactly(hex(slice(0), rpu(0), dropped), hex(slice(1)));
  }

  @Test
  @DisplayName(
      "Should keep the bytes of a sample that holds a length of 0, which makes FFmpeg reject the"
          + " whole sample")
  void shouldKeepTheBytesOfASampleThatHoldsALengthOf0WhichMakesFfmpegRejectTheWholeSample()
      throws IOException {
    var rejected = sample(rpu(0), slice(1), u32(0), aud());
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0))).build(),
            fragmentBuilder().video(1001, rejected).build());

    assertThat(videoSamplesOf(move(copy))).containsExactly(hex(slice(0)), hex(rejected));
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
      "Should keep each RPU behind the previous picture when consecutive fragments together exceed"
          + " what the mover holds but the previous picture's fragment alone does not")
  void
      shouldKeepEachRpuBehindThePreviousPictureWhenConsecutiveFragmentsTogetherExceedWhatTheMoverHoldsButThePreviousPicturesFragmentAloneDoesNot()
          throws IOException {
    var filler = suffixSeiOf(Math.toIntExact(FragmentedMp4RpuMover.MAXIMUM_HELD_BYTES / 2));
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0), filler)).build(),
            fragmentBuilder().video(1001, sample(rpu(0), slice(1), filler)).build(),
            fragmentBuilder().video(2002, sample(rpu(1), slice(2))).build());

    var moved = move(copy);

    // The test's RPUs and slices are units of one length, so this is each sample's last unit.
    var unitLength = hex(rpu(0)).length();
    assertThat(videoSamplesOf(moved))
        .extracting(sample -> sample.substring(sample.length() - unitLength))
        .containsExactly(hex(rpu(0)), hex(rpu(1)), hex(slice(2)));
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
  @DisplayName(
      "Should give each video sample its source packet's duration, keeping every sample's decode"
          + " time, composition offset and flags, when one run holds several video samples")
  void
      shouldGiveEachVideoSampleItsSourcePacketsDurationKeepingEverySamplesDecodeTimeCompositionOffsetAndFlagsWhenOneRunHoldsSeveralVideoSamples()
          throws IOException {
    var copy =
        stream(
            fragmentBuilder()
                .video(0, sample(slice(0)), sample(slice(1)), sample(slice(2)))
                .audio(0, AUDIO_FRAME)
                .build());

    var moved =
        move(
            copy,
            timingsOf(
                packetIn(TRACK_TIME_BASE).keyframe(true).duration(1000),
                packetIn(TRACK_TIME_BASE).keyframe(false).duration(1000),
                packetIn(TRACK_TIME_BASE).keyframe(false).duration(1000)));

    assertThat(timingOf(moved))
        .containsExactly(
            "video 0/0/1000/" + Integer.toHexString(IsoBoxes.SYNC_SAMPLE_FLAGS),
            "video 1001/1001/1000/" + Integer.toHexString(IsoBoxes.NON_SYNC_SAMPLE_FLAGS),
            "video 2003/2002/1000/" + Integer.toHexString(IsoBoxes.NON_SYNC_SAMPLE_FLAGS),
            "audio 0/0/1024/" + Integer.toHexString(IsoBoxes.SYNC_SAMPLE_FLAGS));
    assertThat(videoSamplesOf(moved)).isEqualTo(videoSamplesOf(copy));
    assertThat(audioSamplesOf(moved)).isEqualTo(audioSamplesOf(copy));
  }

  @Test
  @DisplayName(
      "Should convert each source packet's duration into the video track's timescale when the"
          + " source's time base differs")
  void
      shouldConvertEachSourcePacketsDurationIntoTheVideoTracksTimescaleWhenTheSourcesTimeBaseDiffers()
          throws IOException {
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0))).build(),
            fragmentBuilder().video(1001, sample(slice(1))).build());
    var source = new TimeBase(1, 12_000);

    var moved =
        move(
            copy,
            timingsOf(
                packetIn(source).keyframe(false).duration(492),
                packetIn(source).keyframe(false).duration(5_000)));

    assertThat(timingOf(moved))
        .extracting(timing -> timing.substring(0, timing.lastIndexOf('/')))
        .containsExactly("video 0/2002/984", "video 1001/2002/10000");
  }

  @Test
  @DisplayName(
      "Should pass a fragment unchanged when the source's packet leaves its duration unknown")
  void shouldPassAFragmentUnchangedWhenTheSourcesPacketLeavesItsDurationUnknown()
      throws IOException {
    var copy = stream(fragmentBuilder().video(0, sample(slice(0))).build());

    var moved = move(copy, timingsOf(packetIn(TRACK_TIME_BASE).keyframe(false).duration(0)));

    assertThat(moved).isEqualTo(copy);
  }

  @ParameterizedTest(name = "source keyframe {0}")
  @ValueSource(booleans = {true, false})
  @DisplayName(
      "Should fail as unusable video timing when a source packet is a keyframe where its sample is"
          + " no sync sample, or the reverse")
  void
      shouldFailAsUnusableVideoTimingWhenASourcePacketIsAKeyframeWhereItsSampleIsNoSyncSampleOrTheReverse(
          boolean firstIsKeyframe) {
    var copy = stream(fragmentBuilder().video(0, sample(slice(0)), sample(slice(1))).build());
    var timings =
        timingsOf(
            packetIn(TRACK_TIME_BASE).keyframe(firstIsKeyframe).duration(1001),
            packetIn(TRACK_TIME_BASE).keyframe(true).duration(1001));

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> move(copy, timings))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.UNUSABLE_VIDEO_TIMING);
  }

  @Test
  @DisplayName(
      "Should fail as unusable video timing when a source packet's duration does not convert"
          + " exactly into the video track's timescale")
  void
      shouldFailAsUnusableVideoTimingWhenASourcePacketsDurationDoesNotConvertExactlyIntoTheVideoTracksTimescale() {
    var copy = stream(fragmentBuilder().video(0, sample(slice(0))).build());
    var timings = timingsOf(packetIn(new TimeBase(1, 7_000)).keyframe(false).duration(1));

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> move(copy, timings))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.UNUSABLE_VIDEO_TIMING);
  }

  @Test
  @DisplayName(
      "Should fail as unusable video timing when a source packet lasts longer than a run can state")
  void shouldFailAsUnusableVideoTimingWhenASourcePacketLastsLongerThanARunCanState() {
    var copy = stream(fragmentBuilder().video(0, sample(slice(0))).build());
    var timings = timingsOf(packetIn(new TimeBase(1, 1)).keyframe(false).duration(200_000));

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> move(copy, timings))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.UNUSABLE_VIDEO_TIMING);
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
    return move(copy, timingsAsCopied(copy));
  }

  private static byte[] move(byte[] copy, VideoPacketTimings timings) throws IOException {
    var output = new ByteArrayOutputStream();
    FragmentedMp4RpuMover.copy(new ByteArrayInputStream(copy), timings, output);
    return output.toByteArray();
  }

  // The timing the copy's own video samples state, read once the mover asks for it, since the
  // mover refuses some copies before it reaches a sample.
  private static VideoPacketTimings timingsAsCopied(byte[] copy) {
    var remaining = new ArrayDeque<VideoPacketTiming>();
    var read = new boolean[1];
    return () -> {
      if (!read[0]) {
        read[0] = true;
        Mp4Stream.read(Mp4Stream.readerOf(copy)).fragments().stream()
            .flatMap(fragment -> fragment.videoSamples().stream())
            .map(
                sample ->
                    packetIn(TRACK_TIME_BASE)
                        .keyframe(!BoxFields.isSet(sample.flags(), NON_SYNC_SAMPLE))
                        .duration(sample.duration())
                        .build())
            .forEach(remaining::add);
      }

      return nextOf(remaining);
    };
  }

  private static VideoPacketTimings timingsOf(
      VideoPacketTiming.VideoPacketTimingBuilder... packets) {
    var remaining = new ArrayDeque<VideoPacketTiming>();
    Arrays.stream(packets)
        .map(VideoPacketTiming.VideoPacketTimingBuilder::build)
        .forEach(remaining::add);
    return () -> nextOf(remaining);
  }

  private static VideoPacketTiming nextOf(Deque<VideoPacketTiming> remaining) {
    assertThat(remaining).as("the source's packets left for the copy's video samples").isNotEmpty();
    return remaining.removeFirst();
  }

  private static VideoPacketTiming.VideoPacketTimingBuilder packetIn(TimeBase timeBase) {
    return VideoPacketTiming.builder().timeBase(timeBase);
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

  // A length-prefixed suffix SEI NAL unit whose payload holds this many zero bytes.
  private static byte[] suffixSeiOf(int payloadBytes) {
    var unit = ByteBuffer.allocate(4 + 2 + payloadBytes);
    unit.putInt(2 + payloadBytes).put((byte) (HevcCopies.SUFFIX_SEI << 1)).put((byte) 1);
    return unit.array();
  }

  // A NAL unit of the given bytes after a 4-byte length.
  private static byte[] unit(String bytes) {
    var unit = HexFormat.ofDelimiter(" ").parseHex(bytes);
    return concat(u32(unit.length), unit);
  }

  private static String hex(byte[]... nalUnits) {
    return HexFormat.of().formatHex(concat(nalUnits));
  }
}
