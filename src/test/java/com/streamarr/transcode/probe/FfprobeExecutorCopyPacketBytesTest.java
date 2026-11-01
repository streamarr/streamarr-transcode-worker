package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.ffprobeDump;
import static com.streamarr.transcode.probe.RecordedFfprobe.fixture;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static com.streamarr.transcode.probe.RecordedFfprobe.recordedAnswers;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import java.util.Arrays;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
  private static final int HVCC_ARRAY_COUNT = 22;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Nested
  @DisplayName("Recorded Sources")
  class RecordedSourceTests {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "mp4-sdr8-bt709.mp4, 0",
      "mp4-dovi-p9-dva1.mp4, 0",
      "mkv-120fps.mkv, 0",
      "flv-h264-sei-without-trailing-bits.flv, 0",
      "mp4-dovi-p5-dvh1.mp4, 0",
      "mov-hevc-extradata-reload.mov, 0",
      "mkv-pipe-hevc-10bit.mkv, 0",
    })
    @DisplayName(
        "Should report that a copy keeps every packet's bytes when the record holds the parameter"
            + " sets that its header names")
    void
        shouldReportThatACopyKeepsEveryPacketsBytesWhenTheRecordHoldsTheParameterSetsThatItsHeaderNames(
            String fixture, int index) {
      var video = streamOf(fixture, index);

      assertThat(copyKeepsPacketBytes(video)).isTrue();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      // The record header names another level than the SPS.
      "mp4-avc-record-level.mp4, 0",
      "mp4-h264-mixed-nal-coding.mp4, 0",
      "mp4-hevc-record-level.mp4, 0",
      // The hvcC holds mastering display, content light level or ambient viewing environment.
      "mkv-hdr10-static.mkv, 0",
      "mkv-hdr10-fate.mkv, 0",
      "mov-phone-60.mov, 0",
      // The sample entry keeps parameter sets in the samples.
      "mp4-dovi-p5-hev1.mp4, 0",
      "mp4-dovi-p7-two-tracks.mp4, 0",
      // The record holds no NAL unit.
      "mkv-hevc-parameter-sets-in-samples.mkv, 0",
      "mkv-h264-parameter-sets-in-samples.mkv, 0",
      // The extradata is Annex B or no record.
      "mpegts-hevc-ac3.ts, 0",
      "mpegts-aac-he.ts, 0",
      "avi-h264-unknown-extradata.avi, 0",
    })
    @DisplayName(
        "Should report that a copy may change packet bytes when the source needs the Annex B"
            + " filter")
    void shouldReportThatACopyMayChangePacketBytesWhenTheSourceNeedsTheAnnexBFilter(
        String fixture, int index) {
      var video = streamOf(fixture, index);

      assertThat(copyKeepsPacketBytes(video)).isFalse();
    }

    @ParameterizedTest(name = "{0} stream {1}")
    @CsvSource({
      "mp4-av1-10bit.mp4, 0",
      "mkv-av1-10bit.mkv, 0",
      "mkv-eac3-atmos-dts-hd-ma.mkv, 1",
      "mp4-audio-codecs.mp4, 1",
    })
    @DisplayName("Should report no copy fact when the stream is neither H.264 nor HEVC video")
    void shouldReportNoCopyFactWhenTheStreamIsNeitherH264NorHevcVideo(String fixture, int index) {
      var stream = streamOf(fixture, index);

      assertThat(stream.hasCopyKeepsPacketBytes()).isFalse();
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

    private static ProbeStreamInfo edited(String fixture, String recorded, String edited) {
      assertThat(edited).isNotEqualTo(recorded);
      return probe(recordedAnswers(fixture).ffprobeOutput(edited).build()).getStreams(0);
    }
  }

  private static boolean copyKeepsPacketBytes(ProbeStreamInfo video) {
    assertThat(video.hasCopyKeepsPacketBytes()).as("the probe reports the copy fact").isTrue();
    return video.getCopyKeepsPacketBytes();
  }
}
