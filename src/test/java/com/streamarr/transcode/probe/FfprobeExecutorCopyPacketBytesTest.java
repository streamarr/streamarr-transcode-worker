package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.ffprobeDump;
import static com.streamarr.transcode.probe.RecordedFfprobe.fixture;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static com.streamarr.transcode.probe.RecordedFfprobe.recordedAnswers;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.probe.RecordedFfprobe.Answers;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Copy Packet Bytes Tests")
class FfprobeExecutorCopyPacketBytesTest {

  // An x264 avcC record that holds an SPS and a PPS, under an avc1 sample entry.
  private static final String AVC1_MP4 = "mp4-sdr8-bt709.mp4";
  private static final String AVC1_RECORD_HEADER = "0142 c00a ffe1";

  // An MP4 track whose two avc1 sample entries hold an 8-bit and a 10-bit record.
  private static final String TWO_ENTRY_H264_MP4 = "mp4-h264-two-sample-entries.mp4";

  // An x265 hvcC record that holds a VPS, an SPS, a PPS and x265's information SEI message.
  private static final String X265_MATROSKA = "mkv-pipe-hevc-10bit.mkv";

  // The hvcC record's lengthSizeMinusOne sits in its 22nd byte, and its count of NAL unit arrays
  // in the next.
  private static final int HVCC_LENGTH_SIZE = 21;
  // The avcC record's first SPS length follows its 6 header bytes.
  private static final int AVC_FIRST_SPS_LENGTH = 6;
  private static final int HVCC_ARRAY_COUNT = 22;
  private static final int HEVC_VIDEO_PARAMETER_SET = 32;
  private static final int HEVC_SEQUENCE_PARAMETER_SET = 33;
  private static final int HEVC_PICTURE_PARAMETER_SET = 34;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Nested
  @DisplayName("Recorded Sources")
  class RecordedSourceTests {

    // An MP4 or MOV source counts only with its movie box, from which the probe reads how many
    // sample entries the track holds. Matroska sources have no sample entries, so the probe reads
    // no file for them.
    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "mp4-sdr8-bt709.mp4",
          "mp4-dovi-p9-dva1.mp4",
          "mkv-120fps.mkv",
          "mp4-dovi-p5-dvh1.mp4",
          "mkv-pipe-hevc-10bit.mkv",
        })
    @DisplayName(
        "Should report that a copy keeps every packet's bytes when the record holds the parameter"
            + " sets that its header names")
    void
        shouldReportThatACopyKeepsEveryPacketsBytesWhenTheRecordHoldsTheParameterSetsThatItsHeaderNames(
            String fixture) {
      var video = recordedVideo(fixture);

      assertThat(copyKeepsPacketBytes(video)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          // The record header names another level than the SPS.
          "mp4-avc-record-level.mp4",
          "mp4-h264-mixed-nal-coding.mp4",
          "mp4-hevc-record-level.mp4",
          // The hvcC holds mastering display or content light level.
          "mkv-hdr10-static.mkv",
          "mkv-hdr10-fate.mkv",
          // The sample entry keeps parameter sets in the samples.
          "mp4-dovi-p5-hev1.mp4",
          "mp4-dovi-p7-two-tracks.mp4",
          // The record holds no NAL unit.
          "mkv-hevc-parameter-sets-in-samples.mkv",
          "mkv-h264-parameter-sets-in-samples.mkv",
          // FLV can carry a new record in any packet, and only the filter delivers it.
          "flv-h264-sei-without-trailing-bits.flv",
          // The extradata is Annex B or no record.
          "mpegts-hevc-ac3.ts",
          "mpegts-aac-he.ts",
          "avi-h264-unknown-extradata.avi",
        })
    @DisplayName(
        "Should report that a copy may change packet bytes when the source needs the Annex B"
            + " filter")
    void shouldReportThatACopyMayChangePacketBytesWhenTheSourceNeedsTheAnnexBFilter(
        String fixture) {
      var video = recordedVideo(fixture);

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streamsOtherThanH264OrHevcVideo")
    @DisplayName("Should report no copy fact when the stream is neither H.264 nor HEVC video")
    void shouldReportNoCopyFactWhenTheStreamIsNeitherH264NorHevcVideo(RecordedStream recorded) {
      var stream = streamOf(recorded.fixture(), recorded.index());

      assertThat(stream.hasCopyKeepsPacketBytes()).isFalse();
    }

    static Stream<RecordedStream> streamsOtherThanH264OrHevcVideo() {
      return Stream.of(
          RecordedStream.builder().fixture("mp4-av1-10bit.mp4").index(0).build(),
          RecordedStream.builder().fixture("mkv-av1-10bit.mkv").index(0).build(),
          RecordedStream.builder().fixture("mkv-eac3-atmos-dts-hd-ma.mkv").index(1).build(),
          RecordedStream.builder().fixture("mp4-audio-codecs.mp4").index(1).build());
    }
  }

  @Nested
  @DisplayName("Sample Entries")
  class SampleEntryTests {

    // ffprobe describes a track by its first sample entry. Without the filter, a later entry's
    // parameter sets never reach the copy's record. The first entry of each source passes every
    // other rule.
    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          TWO_ENTRY_H264_MP4,
          "mp4-hevc-two-sample-entries.mp4",
          "mov-hevc-extradata-reload.mov",
        })
    @DisplayName(
        "Should report that a copy may change packet bytes when the track holds more than one"
            + " sample entry")
    void shouldReportThatACopyMayChangePacketBytesWhenTheTrackHoldsMoreThanOneSampleEntry(
        String fixture) {
      var video = recordedVideo(fixture);

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadableMovieBoxes")
    @DisplayName(
        "Should report that a copy may change packet bytes when the probe cannot read the movie"
            + " box")
    void shouldReportThatACopyMayChangePacketBytesWhenTheProbeCannotReadTheMovieBox(Path source) {
      var video = probe(recordedAnswers(AVC1_MP4).source(source).build()).getStreams(0);

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    static Stream<Named<Path>> unreadableMovieBoxes() {
      return Stream.of(
          Named.of("no file", Path.of("/media/source")),
          Named.of(
              "a Matroska file", resource("/probe/matroska-heads/mkv-statistics-mkvmerge.mkv")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streamIdsWithoutATrack")
    @DisplayName(
        "Should report that a copy may change packet bytes when the movie box holds no track for"
            + " the stream")
    void shouldReportThatACopyMayChangePacketBytesWhenTheMovieBoxHoldsNoTrackForTheStream(
        UnaryOperator<ObjectNode> edit) {
      var recorded = fixture(AVC1_MP4);
      var output = (ObjectNode) MAPPER.readTree(recorded);
      edit.apply((ObjectNode) output.path("streams").get(0));

      var video = edited(AVC1_MP4, recorded, MAPPER.writeValueAsString(output));

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    static Stream<Named<UnaryOperator<ObjectNode>>> streamIdsWithoutATrack() {
      return Stream.of(
          Named.of("another track ID", stream -> stream.put("id", "0x2")),
          Named.of(
              "no stream id",
              stream -> {
                stream.remove("id");
                return stream;
              }));
    }

    @Test
    @DisplayName(
        "Should report that a copy may change packet bytes when another stream that ffprobe reports"
            + " has the stream's track ID")
    void
        shouldReportThatACopyMayChangePacketBytesWhenAnotherStreamThatFfprobeReportsHasTheStreamsTrackId() {
      // FFmpeg also reads a trak outside the movie box, whose sample entries the probe does not
      // count, so a track ID that two streams carry names no single track.
      var recorded = fixture(AVC1_MP4);
      var output = (ObjectNode) MAPPER.readTree(recorded);
      ((ArrayNode) output.path("streams"))
          .addObject()
          .put("index", 1)
          .put("codec_type", "audio")
          .put("codec_name", "aac")
          .put("id", "0x1");

      var video = edited(AVC1_MP4, recorded, MAPPER.writeValueAsString(output));

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherTrackPositions")
    @DisplayName(
        "Should report that a copy may change packet bytes when another track of the movie box has"
            + " the track's ID")
    void shouldReportThatACopyMayChangePacketBytesWhenAnotherTrackOfTheMovieBoxHasTheTracksId(
        boolean otherTrackFirst, @TempDir Path directory) throws IOException {
      // FFmpeg reads every trak, so a one-entry trak that repeats the two-entry track's ID must not
      // hide the second entry.
      var source = directory.resolve("repeated-track-id.mp4");
      Files.write(source, movieBoxWithAvc1Track(TWO_ENTRY_H264_MP4, otherTrackFirst));

      var video = probe(recordedAnswers(TWO_ENTRY_H264_MP4).source(source).build()).getStreams(0);

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    static Stream<Named<Boolean>> otherTrackPositions() {
      return Stream.of(
          Named.of("the other track first", true), Named.of("the other track last", false));
    }

    /**
     * The fixture's movie box with the track box of {@link #AVC1_MP4}, which holds one sample entry
     * under the same track ID, before or after the fixture's own track.
     */
    private static byte[] movieBoxWithAvc1Track(String fixture, boolean otherTrackFirst)
        throws IOException {
      var file = Files.readAllBytes(resource("/probe/mp4-movie-boxes/" + fixture));
      var donor = Files.readAllBytes(resource("/probe/mp4-movie-boxes/" + AVC1_MP4));
      var movie = BoxRange.find(file, 0, file.length, "moov");
      var donorMovie = BoxRange.find(donor, 0, donor.length, "moov");
      var track = BoxRange.find(donor, donorMovie.payload(), donorMovie.end(), "trak");
      var trackBytes = Arrays.copyOfRange(donor, track.start(), track.end());
      var children = Arrays.copyOfRange(file, movie.payload(), movie.end());

      var edited = new ByteArrayOutputStream();
      edited.write(file, 0, movie.start());
      edited.write(
          ByteBuffer.allocate(8)
              .putInt(8 + children.length + trackBytes.length)
              .put("moov".getBytes(StandardCharsets.ISO_8859_1))
              .array());
      edited.write(otherTrackFirst ? trackBytes : children);
      edited.write(otherTrackFirst ? children : trackBytes);
      return edited.toByteArray();
    }
  }

  @Nested
  @DisplayName("Edited Records")
  class EditedRecordTests {

    @ParameterizedTest(name = "lengthSizeMinusOne {0}")
    @ValueSource(strings = {"fc", "fd", "fe"})
    @DisplayName(
        "Should report that a copy may change packet bytes when the NAL unit lengths take other"
            + " than 4 bytes")
    void shouldReportThatACopyMayChangePacketBytesWhenTheNalUnitLengthsTakeOtherThan4Bytes(
        String lengthSizeByte) {
      var video = editedAvc1(AVC1_RECORD_HEADER, "0142 c00a " + lengthSizeByte + "e1");

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"avc3", "dvav", "avc2", "H264"})
    @DisplayName(
        "Should report that a copy may change packet bytes when an H.264 sample entry is other"
            + " than avc1 or dva1")
    void shouldReportThatACopyMayChangePacketBytesWhenAnH264SampleEntryIsOtherThanAvc1OrDva1(
        String sampleEntry) {
      var video =
          editedAvc1(
              "\"codec_tag_string\": \"avc1\"",
              "\"codec_tag_string\": \"%s\"".formatted(sampleEntry));

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @Test
    @DisplayName("Should report that a copy may change packet bytes when the avcC holds no PPS")
    void shouldReportThatACopyMayChangePacketBytesWhenTheAvcCHoldsNoPps() {
      // The record's PPS count, the byte after its SPS, becomes 0.
      var video = editedAvc1("244d 4001 0004 68ce 0fc8", "244d 4000 0004 68ce 0fc8");

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @Test
    @DisplayName(
        "Should report that a copy may change packet bytes when the avcC ends inside its PPS")
    void shouldReportThatACopyMayChangePacketBytesWhenTheAvcCEndsInsideItsPps() {
      var video = editedAvc1("244d 4001 0004 68ce 0fc8", "244d 4001 0005 68ce 0fc8");

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @Test
    @DisplayName(
        "Should report that a copy may change packet bytes when an hvcC SEI message runs past its"
            + " NAL unit")
    void shouldReportThatACopyMayChangePacketBytesWhenAnHvcCSeiMessageRunsPastItsNalUnit() {
      // x265's information message claims one byte more than its NAL unit holds.
      var recorded = fixture(X265_MATROSKA);
      var video =
          edited(X265_MATROSKA, recorded, recorded.replace("ffff ffff 012c", "ffff ffff 022c"));

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @Test
    @DisplayName(
        "Should report that a copy may change packet bytes when the hvcC holds an empty NAL unit")
    void shouldReportThatACopyMayChangePacketBytesWhenTheHvcCHoldsAnEmptyNalUnit() {
      // One more array, of type 35, that holds one NAL unit of length 0.
      var video =
          editedRecord(
              X265_MATROSKA,
              hvcC -> {
                var edited = Arrays.copyOf(hvcC, hvcC.length + 5);
                edited[HVCC_ARRAY_COUNT]++;
                System.arraycopy(new byte[] {35, 0, 1, 0, 0}, 0, edited, hvcC.length, 5);
                return edited;
              });

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @ParameterizedTest(name = "lengthSizeMinusOne {0}")
    @ValueSource(ints = {0, 1, 2})
    @DisplayName(
        "Should report that a copy may change packet bytes when the hvcC's NAL unit lengths take"
            + " other than 4 bytes")
    void shouldReportThatACopyMayChangePacketBytesWhenTheHvcCsNalUnitLengthsTakeOtherThan4Bytes(
        int lengthSizeMinusOne) {
      var video =
          editedRecord(
              X265_MATROSKA,
              hvcC -> {
                var edited = hvcC.clone();
                edited[HVCC_LENGTH_SIZE] =
                    (byte) ((edited[HVCC_LENGTH_SIZE] & 0xFC) | lengthSizeMinusOne);
                return edited;
              });

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @Test
    @DisplayName(
        "Should report that a copy may change packet bytes when an hvcC array names another type"
            + " than its NAL unit")
    void shouldReportThatACopyMayChangePacketBytesWhenAnHvcCArrayNamesAnotherTypeThanItsNalUnit() {
      // The VPS array, the record's first, names the prefix SEI type. FFmpeg's hvcC writer sorts
      // NAL units by the type that their array names.
      var video =
          editedRecord(
              X265_MATROSKA,
              hvcC -> {
                var edited = hvcC.clone();
                edited[HVCC_ARRAY_COUNT + 1] = (byte) ((edited[HVCC_ARRAY_COUNT + 1] & 0xC0) | 39);
                return edited;
              });

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @Test
    @DisplayName(
        "Should report that a copy may change packet bytes when the avcC names a"
            + " configurationVersion other than 1")
    void
        shouldReportThatACopyMayChangePacketBytesWhenTheAvcCNamesAConfigurationVersionOtherThan1() {
      var video = editedAvc1(AVC1_RECORD_HEADER, "0242 c00a ffe1");

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @Test
    @DisplayName(
        "Should report that a copy may change packet bytes when the hvcC names a"
            + " configurationVersion other than 1")
    void
        shouldReportThatACopyMayChangePacketBytesWhenTheHvcCNamesAConfigurationVersionOtherThan1() {
      var video =
          editedRecord(
              X265_MATROSKA,
              hvcC -> {
                var edited = hvcC.clone();
                edited[0] = 2;
                return edited;
              });

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @ParameterizedTest(name = "without NAL unit type {0}")
    @ValueSource(
        ints = {HEVC_VIDEO_PARAMETER_SET, HEVC_SEQUENCE_PARAMETER_SET, HEVC_PICTURE_PARAMETER_SET})
    @DisplayName(
        "Should report that a copy may change packet bytes when the hvcC holds no VPS, SPS or PPS")
    void shouldReportThatACopyMayChangePacketBytesWhenTheHvcCHoldsNoVpsSpsOrPps(int type) {
      var video = editedRecord(X265_MATROSKA, hvcC -> withoutArray(hvcC, type));

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @Test
    @DisplayName(
        "Should report that a copy may change packet bytes when the hvcC ends inside its last NAL"
            + " unit")
    void shouldReportThatACopyMayChangePacketBytesWhenTheHvcCEndsInsideItsLastNalUnit() {
      var video = editedRecord(X265_MATROSKA, hvcC -> Arrays.copyOf(hvcC, hvcC.length - 1));

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @Test
    @DisplayName(
        "Should report that a copy may change packet bytes when an hvcC SEI NAL unit ends without"
            + " its RBSP trailing bits")
    void
        shouldReportThatACopyMayChangePacketBytesWhenAnHvcCSeiNalUnitEndsWithoutItsRbspTrailingBits() {
      // x265's information message is the record's last NAL unit, and its last byte holds the
      // trailing bits.
      var video =
          editedRecord(
              X265_MATROSKA,
              hvcC -> {
                var edited = hvcC.clone();
                edited[edited.length - 1] = (byte) 0x81;
                return edited;
              });

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @Test
    @DisplayName(
        "Should report that a copy may change packet bytes when the avcC ends after its SPS list")
    void shouldReportThatACopyMayChangePacketBytesWhenTheAvcCEndsAfterItsSpsList() {
      // FFmpeg reads the missing PPS count as 0, so the record holds no PPS.
      var video =
          editedRecord(
              AVC1_MP4, avcC -> Arrays.copyOf(avcC, AVC_FIRST_SPS_LENGTH + 2 + spsLength(avcC)));

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @Test
    @DisplayName(
        "Should report that a copy keeps every packet's bytes when zero bytes follow an hvcC SEI"
            + " NAL unit's RBSP trailing bits")
    void
        shouldReportThatACopyKeepsEveryPacketsBytesWhenZeroBytesFollowAnHvcCSeiNalUnitsRbspTrailingBits() {
      // x265's information message is the record's last NAL unit. FFmpeg's NAL unit reader drops
      // the zero bytes after the trailing bits.
      var video =
          editedRecord(
              X265_MATROSKA,
              hvcC -> {
                var edited = Arrays.copyOf(hvcC, hvcC.length + 1);
                var lengthOffset = lastNalUnitLengthOffset(hvcC);
                var length = ByteBuffer.wrap(hvcC, lengthOffset, 2).getShort();
                ByteBuffer.wrap(edited, lengthOffset, 2).putShort((short) (length + 1));
                return edited;
              });

      assertThat(copyKeepsPacketBytes(video)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("avcCNalUnitsThatDoNotReachTheDecoder")
    @DisplayName(
        "Should report that a copy may change packet bytes when an avcC NAL unit does not reach the"
            + " decoder")
    void shouldReportThatACopyMayChangePacketBytesWhenAnAvcCNalUnitDoesNotReachTheDecoder(
        RecordEdit edit) {
      var video = editedAvc1(edit.recordedText(), edit.editedText());

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    static Stream<RecordEdit> avcCNalUnitsThatDoNotReachTheDecoder() {
      return Stream.of(
          RecordEdit.builder()
              .condition("a PPS whose forbidden_zero_bit is set")
              .recordedText("244d 4001 0004 68ce 0fc8")
              .editedText("244d 4001 0004 e8ce 0fc8")
              .build(),
          RecordEdit.builder()
              .condition("an empty second PPS")
              .recordedText("244d 4001 0004 68ce 0fc8")
              .editedText("244d 4002 0004 68ce 0fc8 0000")
              .build());
    }

    private static int spsLength(byte[] avcC) {
      return Short.toUnsignedInt(ByteBuffer.wrap(avcC, AVC_FIRST_SPS_LENGTH, 2).getShort());
    }

    /** The offset of the length field of the hvcC record's last NAL unit. */
    private static int lastNalUnitLengthOffset(byte[] hvcC) {
      var nalUnitArrays = ByteBuffer.wrap(hvcC).position(HVCC_ARRAY_COUNT + 1);
      var lengthOffset = -1;
      for (var array = 0; array < Byte.toUnsignedInt(hvcC[HVCC_ARRAY_COUNT]); array++) {
        nalUnitArrays.get();
        var units = Short.toUnsignedInt(nalUnitArrays.getShort());
        for (var unit = 0; unit < units; unit++) {
          lengthOffset = nalUnitArrays.position();
          var length = Short.toUnsignedInt(nalUnitArrays.getShort());
          nalUnitArrays.position(nalUnitArrays.position() + length);
        }
      }

      assertThat(nalUnitArrays.hasRemaining()).as("the last NAL unit ends the record").isFalse();
      return lengthOffset;
    }

    /** The hvcC record without its array of NAL units of the given type. */
    private static byte[] withoutArray(byte[] hvcC, int type) {
      var kept = new ByteArrayOutputStream();
      kept.write(hvcC, 0, HVCC_ARRAY_COUNT + 1);
      var nalUnitArrays = ByteBuffer.wrap(hvcC).position(HVCC_ARRAY_COUNT + 1);
      for (var array = 0; array < Byte.toUnsignedInt(hvcC[HVCC_ARRAY_COUNT]); array++) {
        var start = nalUnitArrays.position();
        var arrayType = nalUnitArrays.get() & 0x3F;
        var units = Short.toUnsignedInt(nalUnitArrays.getShort());
        for (var unit = 0; unit < units; unit++) {
          var length = Short.toUnsignedInt(nalUnitArrays.getShort());
          nalUnitArrays.position(nalUnitArrays.position() + length);
        }

        if (arrayType != type) {
          kept.write(hvcC, start, nalUnitArrays.position() - start);
        }
      }

      var edited = kept.toByteArray();
      assertThat(edited)
          .as("the record holds an array of type %d", type)
          .hasSizeLessThan(hvcC.length);
      edited[HVCC_ARRAY_COUNT]--;
      return edited;
    }

    private static ProbeStreamInfo editedRecord(String fixture, UnaryOperator<byte[]> edit) {
      var recorded = fixture(fixture);
      var output = (ObjectNode) MAPPER.readTree(recorded);
      var stream = (ObjectNode) output.path("streams").get(0);
      var configurationRecord = Extradata.of(stream).orElseThrow();
      stream.put("extradata", ffprobeDump(edit.apply(configurationRecord)).replace("\\n", "\n"));
      return edited(fixture, recorded, MAPPER.writeValueAsString(output));
    }

    private static ProbeStreamInfo editedAvc1(String recordedText, String editedText) {
      var recorded = fixture(AVC1_MP4);
      return edited(AVC1_MP4, recorded, recorded.replace(recordedText, editedText));
    }
  }

  /** Probes the recording with the source's movie box, which an MP4 or MOV source needs. */
  private static ProbeStreamInfo recordedVideo(String fixture) {
    return probe(withMovieBox(fixture, recordedAnswers(fixture)).build()).getStreams(0);
  }

  /** Probes the edited output of the recording with the source's movie box. */
  private static ProbeStreamInfo edited(String fixture, String recorded, String edited) {
    assertThat(edited).isNotEqualTo(recorded);
    return probe(withMovieBox(fixture, recordedAnswers(fixture).ffprobeOutput(edited)).build())
        .getStreams(0);
  }

  private static Answers.AnswersBuilder withMovieBox(
      String fixture, Answers.AnswersBuilder answers) {
    if (!fixture.endsWith(".mp4") && !fixture.endsWith(".mov")) {
      return answers;
    }

    return answers.source(resource("/probe/mp4-movie-boxes/" + fixture));
  }

  private static Path resource(String name) {
    var resource = FfprobeExecutorCopyPacketBytesTest.class.getResource(name);
    assertThat(resource).as(name).isNotNull();
    try {
      return Path.of(resource.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }

  private static boolean copyKeepsPacketBytes(ProbeStreamInfo video) {
    assertThat(video.hasCopyKeepsPacketBytes()).as("the probe reports the copy fact").isTrue();
    return video.getCopyKeepsPacketBytes();
  }

  @Builder
  private record RecordedStream(@NonNull String fixture, int index) {
    @Override
    public String toString() {
      return fixture + " stream " + index;
    }
  }

  /** A box of an MP4 file: where it starts, where its payload starts and where it ends. */
  private record BoxRange(int start, int payload, int end) {

    static BoxRange find(byte[] bytes, int from, int to, String type) {
      var position = from;
      while (position + 8 <= to) {
        var size = ByteBuffer.wrap(bytes, position, 4).getInt();
        var boxType = new String(bytes, position + 4, 4, StandardCharsets.ISO_8859_1);
        assertThat(size).as("a 32-bit box size").isGreaterThanOrEqualTo(8);
        if (boxType.equals(type)) {
          return new BoxRange(position, position + 8, position + size);
        }

        position += size;
      }

      throw new AssertionError("no " + type + " box");
    }
  }

  @Builder
  private record RecordEdit(
      @NonNull String condition, @NonNull String recordedText, @NonNull String editedText) {
    @Override
    public String toString() {
      return condition;
    }
  }
}
