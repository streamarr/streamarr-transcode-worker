package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.fixture;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeOutput;
import static com.streamarr.transcode.probe.RecordedFfprobe.request;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import com.streamarr.transcode.fakes.ScriptedProcess;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.ObjectMapper;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Codec String Tests")
class FfprobeExecutorCodecStringTest {

  @Nested
  @DisplayName("Stream Copies")
  class StreamCopyTests {

    @ParameterizedTest(name = "{0} stream {1}: {2}")
    @MethodSource("copiedStreams")
    @DisplayName("Should match the copy's initialization segment when the worker copies the stream")
    void shouldMatchTheCopysInitializationSegmentWhenTheWorkerCopiesTheStream(
        String fixture, int index, String copyCodecString) {
      var stream = streamOf(fixture, index);

      assertThat(stream.getCodecString()).isEqualTo(copyCodecString);
    }

    /**
     * Each stream that copy-codec-strings.py copied with the worker's copy arguments, and the codec
     * string that it read from the copy's initialization segment.
     */
    static Stream<Arguments> copiedStreams() {
      var copies = new ObjectMapper().readTree(fixture("copy-codec-strings"));
      return copies.properties().stream()
          .flatMap(
              file ->
                  file.getValue().properties().stream()
                      .map(
                          copy ->
                              Arguments.of(
                                  file.getKey(),
                                  Integer.parseInt(copy.getKey()),
                                  copy.getValue().asString())));
    }
  }

  @Nested
  @DisplayName("Sample Entries")
  class SampleEntryTests {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "mp4-hdr10-static.mp4, hvc1.2.4.L30.90",
      "mp4-dovi-p5-dvh1.mp4, hvc1.2.4.L123.B0",
      "mp4-dovi-p9-dva1.mp4, avc1.640028",
      "mp4-dovi-p82.mp4, hvc1.2.4.H120.90",
    })
    @DisplayName("Should name the tag rule's sample entry when the source names another entry")
    void shouldNameTheTagRulesSampleEntryWhenTheSourceNamesAnotherEntry(
        String fixture, String codecString) {
      // The sources name hev1, dvh1, dva1 and hvc1 with a Dolby Vision 8.2 record.
      var video = streamOf(fixture, 0);

      assertThat(video.getCodecString()).isEqualTo(codecString);
    }

    @Test
    @DisplayName("Should report AAC LC when an ADTS stream in MPEG-TS carries HE-AAC")
    void shouldReportAacLcWhenAnAdtsStreamInMpegTsCarriesHeAac() {
      // FFmpeg builds the copy's AudioSpecificConfig from the ADTS profile, which says LC.
      var audio = streamOf("mpegts-aac-he.ts", 1);

      assertThat(audio.getAudioProfile()).isEqualTo("HE-AAC");
      assertThat(audio.getCodecString()).isEqualTo("mp4a.40.2");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "mpegts-aac-main.ts, mp4a.40.1",
      "mpegts-aac-ltp.ts, mp4a.40.4",
    })
    @DisplayName("Should report the ADTS profile's object type when an ADTS stream is Main or LTP")
    void shouldReportTheAdtsProfilesObjectTypeWhenAnAdtsStreamIsMainOrLtp(
        String fixture, String codecString) {
      var audio = streamOf(fixture, 1);

      assertThat(audio.getCodecString()).isEqualTo(codecString);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"SSR, mp4a.40.3", "HE-AACv2, mp4a.40.2"})
    @DisplayName("Should report the ADTS profile's object type when ffprobe names the profile")
    void shouldReportTheAdtsProfilesObjectTypeWhenFfprobeNamesTheProfile(
        String profile, String codecString) {
      // ADTS carries HE-AAC v2 as AAC LC with implicit SBR and parametric stereo.
      var audio = adtsStream("\"profile\":\"%s\",".formatted(profile));

      assertThat(audio.getCodecString()).isEqualTo(codecString);
    }

    @Test
    @DisplayName("Should report no codec string when an ADTS stream has no profile")
    void shouldReportNoCodecStringWhenAnAdtsStreamHasNoProfile() {
      var audio = adtsStream("");

      assertThat(audio.hasCodecString()).isFalse();
    }

    @Test
    @DisplayName("Should report the Opus sample entry when the stream is Opus")
    void shouldReportTheOpusSampleEntryWhenTheStreamIsOpus() {
      var audio = streamOf("mkv-pipe-hevc-10bit.mkv", 1);

      assertThat(audio.getCodecString()).isEqualTo("Opus");
    }
  }

  @Nested
  @DisplayName("Parameter Sets")
  class ParameterSetTests {

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      "mp4-avc-record-level.mp4, avc1.42C00A",
      "mp4-hevc-record-level.mp4, hvc1.1.6.L30.90",
    })
    @DisplayName(
        "Should take the level from the parameter sets when the record header names another")
    void shouldTakeTheLevelFromTheParameterSetsWhenTheRecordHeaderNamesAnother(
        String fixture, String codecString) {
      // The avcC header names level 5.1 and the hvcC header level 5.1 (153). The copy rebuilds
      // each record from the parameter sets, which name levels 1.0 and 1.0 (30).
      var video = streamOf(fixture, 0);

      assertThat(video.getCodecString()).isEqualTo(codecString);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
      "h264, 01 64 00 1f ff e1 00 40 67, avc1.64001F",
      "h264, 01 64 00 1f ff e1 00 00, avc1.64001F",
      "h264, 01 64 00 1f ff e1 00, avc1.64001F",
      "hevc, 01 02 20 00 00 00 b0 00 00 00 00 00 7b f0 00 fc fd fa fa 00 00 0f 01 a0 00 01 00 40,"
          + " hvc1.2.4.L123.B0",
      "hevc, 01 02 20 00 00 00 b0 00 00 00 00 00 7b f0 00 fc fd fa fa 00 00 0f 01 a0,"
          + " hvc1.2.4.L123.B0",
    })
    @DisplayName("Should take the record header when the record ends inside its parameter sets")
    void shouldTakeTheRecordHeaderWhenTheRecordEndsInsideItsParameterSets(
        String codec, String extradata, String codecString) {
      var video = stream(codec, extradata);

      assertThat(video.getCodecString()).isEqualTo(codecString);
    }
  }

  @Nested
  @DisplayName("Absent Codec Strings")
  class AbsentCodecStringTests {

    @ParameterizedTest(name = "{0} stream {1}")
    @CsvSource({
      "mkv-mp3-11khz.mkv, 1",
      "mkv-eac3-atmos-dts-hd-ma.mkv, 2",
      "mp4-cover-art-first.mp4, 0",
      "mp4-titled.mp4, 3",
    })
    @DisplayName("Should report no codec string when the worker has no rule for the codec")
    void shouldReportNoCodecStringWhenTheWorkerHasNoRuleForTheCodec(String fixture, int index) {
      // MP3, DTS, PNG cover art and a mov_text subtitle stream.
      var stream = streamOf(fixture, index);

      assertThat(stream.hasCodecString()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"h264", "hevc", "av1"})
    @DisplayName("Should report no codec string when a video stream has no extradata")
    void shouldReportNoCodecStringWhenAVideoStreamHasNoExtradata(String codec) {
      var video = stream(codec, "");

      assertThat(video.hasCodecString()).isFalse();
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
      "h264, 01 64",
      "h264, 00 00 01 67 64",
      "hevc, 01 02 20",
      "hevc, 00 00 01 42 01 01",
      "av1, 81 00",
      "av1, 0a 01 00",
      "av1, 12 00",
    })
    @DisplayName("Should report no codec string when the extradata ends before the profile does")
    void shouldReportNoCodecStringWhenTheExtradataEndsBeforeTheProfileDoes(
        String codec, String extradata) {
      var video = stream(codec, extradata);

      assertThat(video.hasCodecString()).isFalse();
    }
  }

  @Nested
  @DisplayName("Extradata Dumps")
  class ExtradataDumpTests {

    @ParameterizedTest(name = "{0}")
    @ValueSource(
        strings = {
          "\\nnot a dump\\n",
          "\\n00000000: 0164 001                                  .d.\\n",
        })
    @DisplayName("Should report no codec string when ffprobe's dump is malformed")
    void shouldReportNoCodecStringWhenFfprobesDumpIsMalformed(String dump) {
      var video =
          probeOutput(
                  """
                  {"streams":[{"index":0,"codec_type":"video","codec_name":"h264",
                               "time_base":"1/1000","extradata":"%s"}]}
                  """
                      .formatted(dump))
              .getStreams(0);

      assertThat(video.hasCodecString()).isFalse();
    }

    @ParameterizedTest(name = "exit code {0}")
    @CsvSource({"1, {\"streams\":[]}", "0, not json"})
    @DisplayName("Should fail the probe when ffprobe's extradata read fails")
    void shouldFailTheProbeWhenFfprobesExtradataReadFails(int exitCode, String output) {
      var executor =
          new FfprobeExecutor(
              new ObjectMapper(),
              command -> {
                var isExtradataRead = command.arguments().contains("-select_streams");
                return ScriptedProcess.builder()
                    .output(
                        (isExtradataRead ? output : fixture("mp4-hdr10-static.mp4"))
                            .getBytes(StandardCharsets.UTF_8))
                    .exitCode(isExtradataRead ? exitCode : 0)
                    .build();
              });

      var result = executor.probe(Path.of("/media/source"), request()).result();

      assertThat(result.getFailure()).isEqualTo(ProbeFailure.PROBE_FAILURE_EXECUTION_FAILED);
    }

    @Test
    @DisplayName("Should read every line when the extradata spans more than one line")
    void shouldReadEveryLineWhenTheExtradataSpansMoreThanOneLine() {
      // A 23-byte hvcC record whose general profile is Main 10 at level 4.1 (123).
      var video =
          stream("hevc", "01 02 20 00 00 00 b0 00 00 00 00 00 7b f0 00 fc fd fa fa 00 00 0f 00");

      assertThat(video.getCodecString()).isEqualTo("hvc1.2.4.L123.B0");
    }
  }

  @Nested
  @DisplayName("AV1 Sequence Headers")
  class Av1SequenceHeaderTests {

    @Test
    @DisplayName("Should read the level of a reduced still picture header when the OBU has one")
    void shouldReadTheLevelOfAReducedStillPictureHeaderWhenTheObuHasOne() {
      // seq_profile 1, still_picture 1, reduced_still_picture_header 1, seq_level_idx 5. 1x1
      // frames, no 128x128 superblocks or tools, then high_bitdepth 1.
      var header =
          new SequenceHeaderBits()
              .bits(3, 1)
              .bits(1, 1)
              .bits(1, 1)
              .bits(5, 5)
              .bits(4, 0)
              .bits(4, 0)
              .bits(1, 0)
              .bits(1, 0)
              .bits(3, 0)
              .bits(3, 0)
              .bits(1, 1);

      var video = stream("av1", header.obu());

      assertThat(video.getCodecString()).isEqualTo("av01.1.05M.10");
    }

    @Test
    @DisplayName(
        "Should read the first operating point when the header lists timing and two points")
    void shouldReadTheFirstOperatingPointWhenTheHeaderListsTimingAndTwoPoints() {
      // seq_profile 2 with timing info, a decoder model and two operating points. The first point
      // has level 13 and the high tier. The color config states 12 bits.
      var header =
          new SequenceHeaderBits()
              .bits(3, 2)
              .bits(1, 0)
              .bits(1, 0)
              .bits(1, 1)
              .bits(32, 1001)
              .bits(32, 60000)
              .bits(1, 1)
              .bits(3, 0b010)
              .bits(1, 1)
              .bits(5, 9)
              .bits(32, 1)
              .bits(5, 9)
              .bits(5, 9)
              .bits(1, 1)
              .bits(5, 1)
              .bits(12, 0x101)
              .bits(5, 13)
              .bits(1, 1)
              .bits(1, 1)
              .bits(10, 0)
              .bits(10, 0)
              .bits(1, 0)
              .bits(1, 1)
              .bits(4, 3)
              .bits(12, 0x102)
              .bits(5, 4)
              .bits(1, 0)
              .bits(1, 0)
              .bits(4, 10)
              .bits(4, 9)
              .bits(11, 1919)
              .bits(10, 1079)
              .bits(1, 0)
              .bits(1, 0)
              .bits(1, 1)
              .bits(1, 1)
              .bits(1, 0)
              .bits(1, 0)
              .bits(1, 0)
              .bits(1, 0)
              .bits(1, 1)
              .bits(1, 0)
              .bits(1, 0)
              .bits(1, 1)
              .bits(1, 0)
              .bits(1, 1)
              .bits(3, 6)
              .bits(1, 0)
              .bits(1, 1)
              .bits(1, 1)
              .bits(1, 1)
              .bits(1, 1);

      var video = stream("av1", header.obu());

      assertThat(video.getCodecString()).isEqualTo("av01.2.13H.12");
    }

    @Test
    @DisplayName("Should skip other OBUs when a temporal delimiter precedes the sequence header")
    void shouldSkipOtherObusWhenATemporalDelimiterPrecedesTheSequenceHeader() {
      // A temporal delimiter OBU with a size field of 0, then the IVF fixture's sequence header.
      var video = stream("av1", "12 00 0a 0b 02 00 00 05 15 7f 8c 4a f9 40 40");

      assertThat(video.getCodecString()).isEqualTo("av01.0.00M.10");
    }
  }

  /** The sequence header OBU's payload, written bit by bit as AV1 section 5.5 orders it. */
  private static final class SequenceHeaderBits {

    private final StringBuilder bits = new StringBuilder();

    SequenceHeaderBits bits(int count, long value) {
      for (var bit = count - 1; bit >= 0; bit--) {
        bits.append((value >> bit) & 1);
      }

      return this;
    }

    /** The OBU, with a header that names a sequence header and a one-byte size field. */
    String obu() {
      while (bits.length() % 8 != 0) {
        bits.append('0');
      }

      var payload = new byte[bits.length() / 8];
      for (var index = 0; index < payload.length; index++) {
        payload[index] = (byte) Integer.parseInt(bits.substring(index * 8, index * 8 + 8), 2);
      }

      return "0a "
          + HexFormat.of().formatHex(new byte[] {(byte) payload.length})
          + " "
          + HexFormat.ofDelimiter(" ").formatHex(payload);
    }
  }

  /** A video stream of the codec whose extradata ffprobe dumps as the given bytes. */
  /** An AAC stream without an AudioSpecificConfig, after a video stream, with the given fields. */
  private static ProbeStreamInfo adtsStream(String profileField) {
    return probeOutput(
            """
            {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"},
                        {"index":1,"codec_type":"audio","codec_name":"aac",%s"time_base":"1/90000"}]}
            """
                .formatted(profileField))
        .getStreams(1);
  }

  private static ProbeStreamInfo stream(String codec, String extradata) {
    var bytes = HexFormat.ofDelimiter(" ").parseHex(extradata);
    return probeOutput(
            """
            {"streams":[{"index":0,"codec_type":"video","codec_name":"%s","time_base":"1/1000",
                         "extradata":"%s"}]}
            """
                .formatted(codec, ffprobeDump(bytes)))
        .getStreams(0);
  }

  /**
   * The bytes as ffprobe's -show_data prints them, escaped for a JSON string: each line holds an
   * offset, up to 16 bytes in groups of two, and the bytes as ASCII after column 51.
   */
  private static String ffprobeDump(byte[] bytes) {
    var dump = new StringBuilder("\\n");
    for (var offset = 0; offset < bytes.length; offset += 16) {
      var line = new StringBuilder("%08x: ".formatted(offset));
      var end = Math.min(offset + 16, bytes.length);
      for (var index = offset; index < end; index++) {
        line.append("%02x".formatted(bytes[index]));
        if ((index - offset) % 2 == 1) {
          line.append(' ');
        }
      }

      line.append(" ".repeat(51 - line.length())).append(".".repeat(end - offset));
      dump.append(line).append("\\n");
    }

    return dump.toString();
  }
}
