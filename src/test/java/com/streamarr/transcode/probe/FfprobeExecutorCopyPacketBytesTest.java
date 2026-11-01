package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.ffprobeDump;
import static com.streamarr.transcode.probe.RecordedFfprobe.fixture;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static com.streamarr.transcode.probe.RecordedFfprobe.recordedAnswers;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;
import lombok.Builder;
import lombok.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Copy Packet Bytes Tests")
class FfprobeExecutorCopyPacketBytesTest {

  // An x264 avcC record that holds an SPS and a PPS, under an avc1 sample entry.
  private static final String AVC1_MP4 = "mp4-sdr8-bt709.mp4";
  private static final String AVC1_RECORD_HEADER = "0142 c00a ffe1";

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

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "mp4-sdr8-bt709.mp4",
          "mp4-dovi-p9-dva1.mp4",
          "mkv-120fps.mkv",
          "flv-h264-sei-without-trailing-bits.flv",
          "mp4-dovi-p5-dvh1.mp4",
          "mov-hevc-extradata-reload.mov",
          "mkv-pipe-hevc-10bit.mkv",
        })
    @DisplayName(
        "Should report that a copy keeps every packet's bytes when the record holds the parameter"
            + " sets that its header names")
    void
        shouldReportThatACopyKeepsEveryPacketsBytesWhenTheRecordHoldsTheParameterSetsThatItsHeaderNames(
            String fixture) {
      var video = streamOf(fixture, 0);

      assertThat(copyKeepsPacketBytes(video)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          // The record header names another level than the SPS.
          "mp4-avc-record-level.mp4",
          "mp4-h264-mixed-nal-coding.mp4",
          "mp4-hevc-record-level.mp4",
          // The hvcC holds mastering display, content light level or ambient viewing environment.
          "mkv-hdr10-static.mkv",
          "mkv-hdr10-fate.mkv",
          "mov-phone-60.mov",
          // The sample entry keeps parameter sets in the samples.
          "mp4-dovi-p5-hev1.mp4",
          "mp4-dovi-p7-two-tracks.mp4",
          // The record holds no NAL unit.
          "mkv-hevc-parameter-sets-in-samples.mkv",
          "mkv-h264-parameter-sets-in-samples.mkv",
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
      var video = streamOf(fixture, 0);

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
              record -> {
                var edited = Arrays.copyOf(record, record.length + 5);
                edited[HVCC_ARRAY_COUNT]++;
                System.arraycopy(new byte[] {35, 0, 1, 0, 0}, 0, edited, record.length, 5);
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
              record -> {
                var edited = record.clone();
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
              record -> {
                var edited = record.clone();
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
              record -> {
                var edited = record.clone();
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
      var video = editedRecord(X265_MATROSKA, record -> withoutArray(record, type));

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @Test
    @DisplayName(
        "Should report that a copy may change packet bytes when the hvcC ends inside its last NAL"
            + " unit")
    void shouldReportThatACopyMayChangePacketBytesWhenTheHvcCEndsInsideItsLastNalUnit() {
      var video = editedRecord(X265_MATROSKA, record -> Arrays.copyOf(record, record.length - 1));

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
              record -> {
                var edited = record.clone();
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
              AVC1_MP4,
              record -> Arrays.copyOf(record, AVC_FIRST_SPS_LENGTH + 2 + spsLength(record)));

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
              record -> {
                var edited = Arrays.copyOf(record, record.length + 1);
                var lengthOffset = lastNalUnitLengthOffset(record);
                var length = ByteBuffer.wrap(record, lengthOffset, 2).getShort();
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
      var record = ByteBuffer.wrap(hvcC).position(HVCC_ARRAY_COUNT + 1);
      var lengthOffset = -1;
      for (var array = 0; array < Byte.toUnsignedInt(hvcC[HVCC_ARRAY_COUNT]); array++) {
        record.get();
        var units = Short.toUnsignedInt(record.getShort());
        for (var unit = 0; unit < units; unit++) {
          lengthOffset = record.position();
          var length = Short.toUnsignedInt(record.getShort());
          record.position(record.position() + length);
        }
      }

      assertThat(record.hasRemaining()).as("the last NAL unit ends the record").isFalse();
      return lengthOffset;
    }

    /** The hvcC record without its array of NAL units of the given type. */
    private static byte[] withoutArray(byte[] hvcC, int type) {
      var kept = new ByteArrayOutputStream();
      kept.write(hvcC, 0, HVCC_ARRAY_COUNT + 1);
      var record = ByteBuffer.wrap(hvcC).position(HVCC_ARRAY_COUNT + 1);
      for (var array = 0; array < Byte.toUnsignedInt(hvcC[HVCC_ARRAY_COUNT]); array++) {
        var start = record.position();
        var arrayType = record.get() & 0x3F;
        var units = Short.toUnsignedInt(record.getShort());
        for (var unit = 0; unit < units; unit++) {
          var length = Short.toUnsignedInt(record.getShort());
          record.position(record.position() + length);
        }

        if (arrayType != type) {
          kept.write(hvcC, start, record.position() - start);
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
      var record = Extradata.of(stream).orElseThrow();
      stream.put("extradata", ffprobeDump(edit.apply(record)).replace("\\n", "\n"));
      return edited(fixture, recorded, MAPPER.writeValueAsString(output));
    }

    private static ProbeStreamInfo editedAvc1(String recordedText, String editedText) {
      var recorded = fixture(AVC1_MP4);
      return edited(AVC1_MP4, recorded, recorded.replace(recordedText, editedText));
    }
  }

  private static ProbeStreamInfo edited(String fixture, String recorded, String edited) {
    assertThat(edited).isNotEqualTo(recorded);
    return probe(recordedAnswers(fixture).ffprobeOutput(edited).build()).getStreams(0);
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

  @Builder
  private record RecordEdit(
      @NonNull String condition, @NonNull String recordedText, @NonNull String editedText) {
    @Override
    public String toString() {
      return condition;
    }
  }
}
