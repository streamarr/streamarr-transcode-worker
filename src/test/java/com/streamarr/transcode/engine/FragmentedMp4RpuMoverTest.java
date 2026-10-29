package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.HevcCopies.aud;
import static com.streamarr.transcode.engine.HevcCopies.endOfSequenceUnit;
import static com.streamarr.transcode.engine.HevcCopies.fragmentBuilder;
import static com.streamarr.transcode.engine.HevcCopies.hevcSampleEntry;
import static com.streamarr.transcode.engine.HevcCopies.initializationSegment;
import static com.streamarr.transcode.engine.HevcCopies.parameterSet;
import static com.streamarr.transcode.engine.HevcCopies.rpu;
import static com.streamarr.transcode.engine.HevcCopies.rpuUnit;
import static com.streamarr.transcode.engine.HevcCopies.sample;
import static com.streamarr.transcode.engine.HevcCopies.slice;
import static com.streamarr.transcode.engine.HevcCopies.sliceUnit;
import static com.streamarr.transcode.engine.HevcCopies.suffixSei;
import static com.streamarr.transcode.engine.IsoBoxes.box;
import static com.streamarr.transcode.engine.IsoBoxes.concat;
import static com.streamarr.transcode.engine.IsoBoxes.fullBox;
import static com.streamarr.transcode.engine.IsoBoxes.u32;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.streamarr.transcode.engine.FragmentedMp4Exception.Reason;
import com.sun.management.ThreadMXBean;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;
import lombok.Builder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@Tag("UnitTest")
@DisplayName("Fragmented MP4 RPU Mover Tests")
class FragmentedMp4RpuMoverTest {

  private static final byte[] AUDIO_FRAME = {0x21, 0x10, 0x04};
  private static final TimeBase TRACK_TIME_BASE = new TimeBase(1, 24_000);
  private static final int NON_SYNC_SAMPLE = 0x0001_0000;
  // ADR 0013: what the mover holds beyond the fragment it reads, and the NAL units of a sample.
  private static final int MOST_HELD_BYTES = 32 * 1024 * 1024;
  private static final int MOST_NAL_UNITS_PER_SAMPLE = 4096;

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
  }

  @Test
  @DisplayName(
      "Should keep every sample's decode time, composition offset, duration and flags, and every"
          + " audio sample, when RPUs move across fragments")
  void
      shouldKeepEverySamplesDecodeTimeCompositionOffsetDurationAndFlagsAndEveryAudioSampleWhenRpusMoveAcrossFragments()
          throws IOException {
    var copy =
        stream(
            fragmentBuilder()
                .startsWithKeyframe(true)
                .video(0, sample(aud(), slice(0)))
                .audio(0, AUDIO_FRAME, AUDIO_FRAME)
                .build(),
            fragmentBuilder()
                .video(1001, sample(aud(), rpu(0), slice(1)), sample(rpu(1), slice(2)))
                .audio(2048, AUDIO_FRAME)
                .build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).isNotEqualTo(videoSamplesOf(copy));
    assertThat(timingOf(moved)).isEqualTo(timingOf(copy));
    assertThat(audioSamplesOf(moved)).isEqualTo(audioSamplesOf(copy));
  }

  @Test
  @DisplayName("Should keep each moof's mfhd when it rewrites the moof's video trafs")
  void shouldKeepEachMoofsMfhdWhenItRewritesTheMoofsVideoTrafs() throws IOException {
    var copy =
        stream(
            fragmentBuilder().video(0, sample(aud(), slice(0))).build(),
            fragmentBuilder().video(1001, sample(aud(), rpu(0), slice(1))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).isNotEqualTo(videoSamplesOf(copy));
    assertThat(moofChildrenOtherThanTrafsOf(moved))
        .isEqualTo(moofChildrenOtherThanTrafsOf(copy))
        .hasSize(2);
  }

  @Test
  @DisplayName(
      "Should end the previous picture's sample with the moved RPU after the RPU it already held"
          + " when the stream mixes the conforming and the shifted layouts")
  void
      shouldEndThePreviousPicturesSampleWithTheMovedRpuAfterTheRpuItAlreadyHeldWhenTheStreamMixesTheConformingAndTheShiftedLayouts()
          throws IOException {
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0), rpu(0))).build(),
            fragmentBuilder().video(1001, sample(rpu(5), slice(1))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(slice(0), rpu(0), rpu(5)), hex(slice(1)));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"hvc1", "hev1", "dvh1", "dvhe"})
  @DisplayName(
      "Should move RPUs when the copy's video sample entry is any that FFmpeg writes of HEVC")
  void shouldMoveRpusWhenTheCopysVideoSampleEntryIsAnyThatFfmpegWritesOfHevc(String sampleEntry)
      throws IOException {
    var copy =
        concat(
            initializationSegment(hevcSampleEntry(sampleEntry, 4)),
            fragmentBuilder().video(0, sample(slice(0))).build(),
            fragmentBuilder().video(1001, sample(rpu(0), slice(1))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(slice(0), rpu(0)), hex(slice(1)));
  }

  @Test
  @DisplayName(
      "Should drop the RPU that starts the first sample when the RPUs belong to the picture before")
  void shouldDropTheRpuThatStartsTheFirstSampleWhenTheRpusBelongToThePictureBefore()
      throws IOException {
    var copy =
        stream(
            fragmentBuilder().video(0, sample(aud(), rpu(9), slice(0))).build(),
            fragmentBuilder().video(1001, sample(aud(), slice(1))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(aud(), slice(0)), hex(aud(), slice(1)));
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
    "type 63 with rpu_nal_prefix 25, 7E 01 19 00",
    "type 62 with a prefix other than 25, 7C 01 18 00",
    "type 62 without a payload byte, 7C 01"
  })
  @DisplayName(
      "Should pass the copy unchanged when a NAL unit that starts a sample is no RPU that FFmpeg's"
          + " decoder reads")
  void shouldPassTheCopyUnchangedWhenANalUnitThatStartsASampleIsNoRpuThatFfmpegsDecoderReads(
      String description, String notAnRpu) throws IOException {
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0))).build(),
            fragmentBuilder().video(1001, sample(unit(notAnRpu), slice(1))).build());

    assertThat(move(copy)).as(description).isEqualTo(copy);
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({"VPS, 32", "SPS, 33", "PPS, 34"})
  @DisplayName(
      "Should move an RPU behind the previous picture when a parameter set rather than a slice"
          + " precedes it in its sample")
  void
      shouldMoveAnRpuBehindThePreviousPictureWhenAParameterSetRatherThanASlicePrecedesItInItsSample(
          String description, int parameterSetType) throws IOException {
    var parameterSet = parameterSet(parameterSetType);
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0))).build(),
            fragmentBuilder().video(1001, sample(parameterSet, rpu(1), slice(1))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved))
        .as(description)
        .containsExactly(hex(slice(0), rpu(1)), hex(parameterSet, slice(1)));
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "forbidden_zero_bit 1, 82 01 AF 01",
    "nuh_temporal_id_plus1 0, 02 00 AF 01",
    "nuh_layer_id 63, 03 F9 AF 01"
  })
  @DisplayName(
      "Should move an RPU behind the previous picture when a slice that FFmpeg's splitter drops"
          + " precedes it")
  void shouldMoveAnRpuBehindThePreviousPictureWhenASliceThatFfmpegsSplitterDropsPrecedesIt(
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
      "Should move an RPU to the end of its sample when a start code inside a dropped NAL unit's"
          + " length begins a NAL unit that reaches the decoder")
  void
      shouldMoveAnRpuToTheEndOfItsSampleWhenAStartCodeInsideADroppedNalUnitsLengthBeginsANalUnitThatReachesTheDecoder()
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
      "Should keep a length that holds a parameter set in place when a start code inside it begins"
          + " the RPU that starts the sample")
  void
      shouldKeepALengthThatHoldsAParameterSetInPlaceWhenAStartCodeInsideItBeginsTheRpuThatStartsTheSample()
          throws IOException {
    // Moving the length behind the previous picture would take the PPS ahead of the RPU with it.
    var ppsThenRpu = unit("44 01 C1 01 00 00 01 7C 01 19 01");
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0))).build(),
            fragmentBuilder().video(1001, sample(ppsThenRpu, slice(1))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(slice(0)), hex(ppsThenRpu, slice(1)));
  }

  @Test
  @DisplayName(
      "Should move a length that starts the sample behind the previous picture when a start code"
          + " inside its RPU begins another RPU")
  void
      shouldMoveALengthThatStartsTheSampleBehindThePreviousPictureWhenAStartCodeInsideItsRpuBeginsAnotherRpu()
          throws IOException {
    // FFmpeg's HEVC decoder reads only the last of the two RPUs, and the length holds nothing else.
    var twoRpus = unit("7C 01 19 08 00 00 01 7C 01 19 00");
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0))).build(),
            fragmentBuilder().video(1001, sample(twoRpus, slice(1))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(slice(0), twoRpus), hex(slice(1)));
  }

  @Test
  @DisplayName(
      "Should keep a length that holds a slice in place when a start code inside it begins an RPU"
          + " that another slice follows")
  void
      shouldKeepALengthThatHoldsASliceInPlaceWhenAStartCodeInsideItBeginsAnRpuThatAnotherSliceFollows()
          throws IOException {
    // FFmpeg reads the slice, the RPU and then the next slice of the same picture, so moving the
    // whole length behind that slice would reverse the picture's slices.
    var sliceThenRpu = unit("02 01 AF 01 00 00 01 7C 01 19 00");
    var copy = stream(fragmentBuilder().video(0, sample(sliceThenRpu, slice(0))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(sliceThenRpu, slice(0)));
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
    var shortRpu = rpuUnit().lengthSize(1).picture(7).build();
    var shortSlice = sliceUnit().lengthSize(1).picture(1).build();
    var endOfSequence = endOfSequenceUnit().lengthSize(1).build();
    var copy =
        concat(
            initializationSegment(hevcSampleEntry("hvc1", 1)),
            fragmentBuilder().video(0, concat(shortSlice, shortRpu, endOfSequence)).build());

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
    var firstSlice = sliceUnit().lengthSize(lengthSize).picture(0).build();
    var secondSlice = sliceUnit().lengthSize(lengthSize).picture(1).build();
    var rpu = rpuUnit().lengthSize(lengthSize).picture(0).build();
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
      "Should keep a sample's bytes when it holds a length of 0 that FFmpeg's splitter reads, which"
          + " rejects the whole sample")
  void shouldKeepASamplesBytesWhenItHoldsALengthOf0ThatFfmpegsSplitterReads() throws IOException {
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
    var half = new byte[MOST_HELD_BYTES / 2];
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
    var filler = suffixSeiOf(MOST_HELD_BYTES / 2);
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
      "Should copy nothing behind the previous picture from a sample that holds no slice when a"
          + " start code inside a parameter set's length begins its RPU")
  void
      shouldCopyNothingBehindThePreviousPictureFromASampleThatHoldsNoSliceWhenAStartCodeInsideAParameterSetsLengthBeginsItsRpu()
          throws IOException {
    // Copying the length behind the previous picture would put the PPS ahead of the RPU there.
    var ppsThenRpu = unit("44 01 C1 01 00 00 01 7C 01 19 01");
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0))).build(),
            fragmentBuilder().video(1001, sample(ppsThenRpu)).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(slice(0)), hex(ppsThenRpu));
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
            fragmentBuilder().video(2002, sample(rpu(3), rpu(4), slice(1))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved))
        .containsExactly(hex(slice(0), rpu(4)), hex(rpu(1), rpu(2)), hex(slice(1)));
  }

  @Test
  @DisplayName(
      "Should keep the bytes of a sample it cannot delimit, and drop the next RPU that starts a"
          + " sample, when the RPUs belong to the picture before")
  void
      shouldKeepTheBytesOfASampleItCannotDelimitAndDropTheNextRpuThatStartsASampleWhenTheRpusBelongToThePictureBefore()
          throws IOException {
    var undelimited = concat(slice(1), u32(64));
    var copy =
        stream(
            fragmentBuilder().video(0, sample(slice(0))).build(),
            fragmentBuilder().video(1001, undelimited).build(),
            fragmentBuilder().video(2002, sample(rpu(1), slice(2))).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved))
        .containsExactly(hex(slice(0)), hex(undelimited), hex(slice(2)));
  }

  @ParameterizedTest(name = "{0}-byte lengths")
  @ValueSource(ints = {1, 2})
  @DisplayName(
      "Should delimit NAL units by the length size that the hvcC declares when it declares lengths"
          + " shorter than 4 bytes")
  void
      shouldDelimitNalUnitsByTheLengthSizeThatTheHvcCDeclaresWhenItDeclaresLengthsShorterThan4Bytes(
          int lengthSize) throws IOException {
    var shortRpu = rpuUnit().lengthSize(lengthSize).picture(7).build();
    var shortSlice = sliceUnit().lengthSize(lengthSize).picture(1).build();
    var copy =
        concat(
            initializationSegment(hevcSampleEntry("hvc1", lengthSize)),
            fragmentBuilder().video(0, shortSlice, concat(shortRpu, shortSlice)).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(shortSlice, shortRpu), hex(shortSlice));
  }

  @Test
  @DisplayName(
      "Should fail as a malformed box when the hvcC declares NAL unit lengths of 3 bytes, which"
          + " ISO/IEC 14496-15 does not allow")
  void shouldFailAsAMalformedBoxWhenTheHvcCDeclaresNalUnitLengthsOf3Bytes() {
    var copy =
        concat(
            initializationSegment(hevcSampleEntry("hvc1", 3)),
            fragmentBuilder().video(0, sample(slice(0))).build());

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> move(copy))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.MALFORMED_BOX);
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
                .startsWithKeyframe(true)
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
        .isEqualTo(
            timingOf(copy).stream()
                .map(timing -> timing.video() ? timing.withDuration(1000) : timing)
                .toList());
    assertThat(videoSamplesOf(moved)).isEqualTo(videoSamplesOf(copy));
    assertThat(audioSamplesOf(moved)).isEqualTo(audioSamplesOf(copy));
  }

  @ParameterizedTest(name = "duration {0}")
  @ValueSource(longs = {0, -1001})
  @DisplayName(
      "Should pass a fragment unchanged when the source's packet leaves its duration unknown")
  void shouldPassAFragmentUnchangedWhenTheSourcesPacketLeavesItsDurationUnknown(long duration)
      throws IOException {
    var copy = stream(fragmentBuilder().video(0, sample(slice(0))).build());

    var moved = move(copy, timingsOf(packetIn(TRACK_TIME_BASE).keyframe(false).duration(duration)));

    assertThat(moved).isEqualTo(copy);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("keyframeMismatches")
  @DisplayName(
      "Should fail as unusable video timing when a source packet's keyframe flag disagrees with its"
          + " sample's sync flag")
  void shouldFailAsUnusableVideoTimingWhenASourcePacketsKeyframeFlagDisagreesWithItsSamplesSyncFlag(
      VideoPacketTimings timings) {
    // The copy's first video sample is a sync sample and its second is not.
    var copy =
        stream(
            fragmentBuilder()
                .startsWithKeyframe(true)
                .video(0, sample(slice(0)), sample(slice(1)))
                .build());

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> move(copy, timings))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.UNUSABLE_VIDEO_TIMING);
  }

  static Stream<Named<VideoPacketTimings>> keyframeMismatches() {
    return Stream.of(
        Named.of(
            "a keyframe where its sample is no sync sample",
            timingsOf(
                packetIn(TRACK_TIME_BASE).keyframe(true).duration(1001),
                packetIn(TRACK_TIME_BASE).keyframe(true).duration(1001))),
        Named.of(
            "no keyframe where its sample is a sync sample",
            timingsOf(
                packetIn(TRACK_TIME_BASE).keyframe(false).duration(1001),
                packetIn(TRACK_TIME_BASE).keyframe(false).duration(1001))));
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
  @DisplayName(
      "Should fail as unusable video timing when a source packet's duration in the video track's"
          + " timescale exceeds a signed 64-bit count")
  void
      shouldFailAsUnusableVideoTimingWhenASourcePacketsDurationInTheVideoTracksTimescaleExceedsASigned64BitCount() {
    var copy = stream(fragmentBuilder().video(0, sample(slice(0))).build());
    // 768,614,336,404,565 seconds are 2^64 + 8,384 ticks at 24,000 ticks a second.
    var timings =
        timingsOf(packetIn(new TimeBase(1, 1)).keyframe(false).duration(768_614_336_404_565L));

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> move(copy, timings))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.UNUSABLE_VIDEO_TIMING);
  }

  @Test
  @DisplayName("Should fail as an unexpected box when a traf holds a box FFmpeg does not write")
  void shouldFailAsAnUnexpectedBoxWhenATrafHoldsABoxFfmpegDoesNotWrite() {
    var withSampleDependencies =
        fragmentBuilder()
            .video(0, sample(slice(0)))
            .extraVideoTrafBox(fullBox("sdtp", 0, new byte[1]))
            .build();
    var copy = concat(initializationSegment(), withSampleDependencies);

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> move(copy))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.UNEXPECTED_BOX);
  }

  @Test
  @DisplayName("Should fail as an unexpected box when a moof holds a box FFmpeg does not write")
  void shouldFailAsAnUnexpectedBoxWhenAMoofHoldsABoxFfmpegDoesNotWrite() {
    var withProtection =
        fragmentBuilder()
            .video(0, sample(slice(0)))
            .extraMoofBox(fullBox("pssh", 0, new byte[20]))
            .build();

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> move(concat(initializationSegment(), withProtection)))
        .extracting(FragmentedMp4Exception::getReason)
        .isEqualTo(Reason.UNEXPECTED_BOX);
  }

  @Test
  @DisplayName(
      "Should fail as an unexpected box when a tfhd that counts from its moof also declares a base"
          + " data offset")
  void shouldFailAsAnUnexpectedBoxWhenATfhdThatCountsFromItsMoofAlsoDeclaresABaseDataOffset() {
    // The base data offset is where the moof starts, so the run places its sample in the mdat.
    var moof = moofWithBaseDataOffset(0);
    moof = moofWithBaseDataOffset(moof.length + 8);
    var copy = concat(initializationSegment(), moof, box("mdat", new byte[1]));

    assertThatExceptionOfType(FragmentedMp4Exception.class)
        .isThrownBy(() -> move(copy))
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
    var copy =
        stream(fragmentBuilder().video(0, sliceRpuThenAuds(MOST_NAL_UNITS_PER_SAMPLE + 1)).build());

    assertThat(move(copy)).isEqualTo(copy);
  }

  @Test
  @DisplayName(
      "Should move an RPU to the end of its sample when the sample holds as many NAL units as an"
          + " HEVC picture's sample can")
  void shouldMoveAnRpuToTheEndOfItsSampleWhenTheSampleHoldsAsManyNalUnitsAsAnHevcPicturesSampleCan()
      throws IOException {
    var auds = new byte[MOST_NAL_UNITS_PER_SAMPLE - 2][];
    Arrays.fill(auds, aud());
    var copy =
        stream(fragmentBuilder().video(0, sliceRpuThenAuds(MOST_NAL_UNITS_PER_SAMPLE)).build());

    var moved = move(copy);

    assertThat(videoSamplesOf(moved)).containsExactly(hex(slice(0), sample(auds), rpu(0)));
  }

  @Test
  @DisplayName(
      "Should allocate no more than for zeros when a length holds a start code every 3 bytes")
  void shouldAllocateNoMoreThanForZerosWhenALengthHoldsAStartCodeEvery3Bytes() throws IOException {
    var lengthBytes = 3 * 1024 * 1024;
    var startCodes = new byte[lengthBytes];
    for (var index = 2; index < lengthBytes; index += 3) {
      startCodes[index] = 1;
    }

    var zeros =
        stream(fragmentBuilder().video(0, sample(slice(0), unitOf(new byte[lengthBytes]))).build());
    var everyThirdByte =
        stream(fragmentBuilder().video(0, sample(slice(0), unitOf(startCodes))).build());
    move(zeros);

    var allocatedForZeros = allocatedBytesOfMoving(zeros);
    var allocatedForStartCodes = allocatedBytesOfMoving(everyThirdByte);

    assertThat(allocatedForStartCodes)
        .as("the bytes allocated for %d start codes", lengthBytes / 3)
        .isLessThan(allocatedForZeros + lengthBytes / 3);
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

  private static byte[] moofWithBaseDataOffset(int dataOffset) {
    return box(
        "moof",
        fullBox("mfhd", 0, u32(1)),
        box(
            "traf",
            fullBox(
                "tfhd",
                IsoBoxes.TFHD_BASE_DATA_OFFSET
                    | IsoBoxes.TFHD_DEFAULT_BASE_IS_MOOF
                    | IsoBoxes.TFHD_DEFAULT_SAMPLE_SIZE,
                u32(IsoBoxes.VIDEO_TRACK_ID),
                IsoBoxes.u64(initializationSegment().length),
                u32(1)),
            fullBox("tfdt", 0, u32(0)),
            fullBox("trun", IsoBoxes.TRUN_DATA_OFFSET, u32(1), u32(dataOffset))));
  }

  // A slice, the RPU behind it and then AUDs, this many NAL units in all.
  private static byte[] sliceRpuThenAuds(int nalUnits) {
    var units = new byte[nalUnits][];
    units[0] = slice(0);
    units[1] = rpu(0);
    Arrays.fill(units, 2, nalUnits, aud());
    return sample(units);
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
  private static List<SampleTiming> timingOf(byte[] stream) throws IOException {
    return Mp4Stream.read(Mp4Stream.readerOf(stream)).fragments().stream()
        .flatMap(
            fragment ->
                Stream.concat(
                    fragment.videoSamples().stream().map(sample -> SampleTiming.of(true, sample)),
                    fragment.audioSamples().stream().map(sample -> SampleTiming.of(false, sample))))
        .toList();
  }

  @Builder(toBuilder = true)
  private record SampleTiming(
      boolean video, long decodeTime, long compositionOffset, long duration, int flags) {

    static SampleTiming of(boolean video, Sample sample) {
      return SampleTiming.builder()
          .video(video)
          .decodeTime(sample.decodeTime())
          .compositionOffset(sample.compositionOffset())
          .duration(sample.duration())
          .flags(sample.flags())
          .build();
    }

    SampleTiming withDuration(long duration) {
      return toBuilder().duration(duration).build();
    }
  }

  // The children of each moof other than its trafs, as type and body.
  private static List<String> moofChildrenOtherThanTrafsOf(byte[] stream) throws IOException {
    return Mp4Stream.read(Mp4Stream.readerOf(stream)).fragments().stream()
        .map(fragment -> fragment.boxes().getFirst())
        .flatMap(
            moof ->
                new BoxView("moof", ByteBuffer.wrap(moof, 8, moof.length - 8)).children().stream())
        .filter(child -> !child.type().equals("traf"))
        .map(child -> child.type() + " " + HexFormat.of().formatHex(bytesOf(child.body())))
        .toList();
  }

  private static byte[] bytesOf(ByteBuffer body) {
    var bytes = new byte[body.remaining()];
    body.duplicate().get(bytes);
    return bytes;
  }

  // A length-prefixed suffix SEI NAL unit whose payload holds this many zero bytes.
  private static byte[] suffixSeiOf(int payloadBytes) {
    var unit = ByteBuffer.allocate(4 + 2 + payloadBytes);
    unit.putInt(2 + payloadBytes).put((byte) (HevcCopies.SUFFIX_SEI << 1)).put((byte) 1);
    return unit.array();
  }

  // A NAL unit of the given bytes after a 4-byte length.
  private static byte[] unit(String bytes) {
    return unitOf(HexFormat.ofDelimiter(" ").parseHex(bytes));
  }

  private static byte[] unitOf(byte[] unit) {
    return concat(u32(unit.length), unit);
  }

  // The heap that this thread allocates while the mover moves the copy, garbage included.
  private static long allocatedBytesOfMoving(byte[] copy) throws IOException {
    var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();
    var before = threads.getCurrentThreadAllocatedBytes();
    move(copy);
    return threads.getCurrentThreadAllocatedBytes() - before;
  }

  private static String hex(byte[]... nalUnits) {
    return HexFormat.of().formatHex(concat(nalUnits));
  }
}
