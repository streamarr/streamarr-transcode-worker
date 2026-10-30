package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.ffprobeDump;
import static com.streamarr.transcode.probe.RecordedFfprobe.fixture;
import static com.streamarr.transcode.probe.RecordedFfprobe.probe;
import static com.streamarr.transcode.probe.RecordedFfprobe.probeOutput;
import static com.streamarr.transcode.probe.RecordedFfprobe.recordedAnswers;
import static com.streamarr.transcode.probe.RecordedFfprobe.request;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeFailure;
import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import build.buf.gen.streamarr.transcode.v1.VideoCodecProfile;
import com.streamarr.transcode.fakes.ScriptedProcess;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
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

  private static final Set<String> SESSION_COVERED_VIDEO_FIXTURES =
      Set.of(
          "mp4-sdr8-bt709.mp4",
          "mp4-av1-10bit.mp4",
          "mp4-hdr10-static.mp4",
          "mp4-dovi-p5-dvh1.mp4",
          "mp4-dovi-p9-dva1.mp4",
          "mp4-dovi-p82.mp4");

  // Matroska whose decoder configuration record holds no NAL unit, and whose samples repeat the
  // parameter sets: 10-bit HEVC, and H.264.
  private static final String HEVC_SAMPLE_PARAMETER_SETS = "mkv-hevc-parameter-sets-in-samples.mkv";
  private static final String AVC_SAMPLE_PARAMETER_SETS = "mkv-h264-parameter-sets-in-samples.mkv";

  // An avcC header and the SPS of that fixture's first sample, which names Constrained Baseline
  // level 1.0.
  private static final String CONSTRAINED_BASELINE_SPS_RECORD =
      "01 42 c0 0a ff e1 00 16 67 42 c0 0a da 11 fe 7c 04 40 00 00 03 00 40 00 00 0c 03 c4 89 a8";

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
                      .filter(
                          copy ->
                              !copy.getKey().equals("0")
                                  || !SESSION_COVERED_VIDEO_FIXTURES.contains(file.getKey()))
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
    @DisplayName("Should read the escaped object type when the config escapes it")
    void shouldReadTheEscapedObjectTypeWhenTheConfigEscapesIt() {
      // ISO/IEC 14496-3, 1.6.2.1: object type 31 escapes to 32 plus the next 6 bits. 11111 001010
      // names object type 42, USAC.
      var audio =
          probeOutput(
                  """
                  {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"},
                              {"index":1,"codec_type":"audio","codec_name":"aac",
                               "extradata":"\\n00000000: f940 0000                                .@..\\n"}]}
                  """)
              .getStreams(1);

      assertThat(audio.getCodecString()).isEqualTo("mp4a.40.42");
    }

    @Test
    @DisplayName("Should report no codec string when the config ends inside an escaped object type")
    void shouldReportNoCodecStringWhenTheConfigEndsInsideAnEscapedObjectType() {
      var audio =
          probeOutput(
                  """
                  {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"},
                              {"index":1,"codec_type":"audio","codec_name":"aac",
                               "extradata":"\\n00000000: f8                                       .\\n"}]}
                  """)
              .getStreams(1);

      assertThat(audio.hasCodecString()).isFalse();
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("absentOrIncompleteParameterSets")
    @DisplayName("Should report no codec string when parameter sets are absent or incomplete")
    void shouldReportNoCodecStringWhenParameterSetsAreAbsentOrIncomplete(
        String codec, String extradata) {
      var video = stream(codec, extradata);

      assertThat(video.hasCodecString()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      HEVC_SAMPLE_PARAMETER_SETS + ", VIDEO_CODEC_PROFILE_HEVC_MAIN_10, hvc1.2.4.L30.90",
      AVC_SAMPLE_PARAMETER_SETS + ", VIDEO_CODEC_PROFILE_H264_CONSTRAINED_BASELINE, avc1.42C00A",
    })
    @DisplayName(
        "Should take the codec string from the first sample when the record holds no NAL unit")
    void shouldTakeTheCodecStringFromTheFirstSampleWhenTheRecordHoldsNoNalUnit(
        String fixture, VideoCodecProfile profile, String codecString) {
      // The copy's Annex B filter gives the mp4 muxer no parameter set, so the muxer builds the
      // copy's record from the parameter sets that the first sample repeats.
      var video = streamOf(fixture, 0);

      assertThat(video.getCodecProfile()).isEqualTo(profile);
      assertThat(video.getCodecString()).isEqualTo(codecString);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {HEVC_SAMPLE_PARAMETER_SETS, AVC_SAMPLE_PARAMETER_SETS})
    @DisplayName(
        "Should report no codec string when the record holds no NAL unit and no packet was read")
    void shouldReportNoCodecStringWhenTheRecordHoldsNoNalUnitAndNoPacketWasRead(String fixture) {
      var video = probe(recordedAnswers(fixture).firstPacketsExitCode(1).build()).getStreams(0);

      assertThat(video.hasCodecString()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
      // One IDR slice NAL unit after its 4-byte length.
      HEVC_SAMPLE_PARAMETER_SETS + ", 00000000: 0000 0004 2601 af80",
      AVC_SAMPLE_PARAMETER_SETS + ", 00000000: 0000 0004 6588 843a",
    })
    @DisplayName(
        "Should report no codec string when the record holds no NAL unit and the first sample no"
            + " parameter set")
    void shouldReportNoCodecStringWhenTheRecordHoldsNoNalUnitAndTheFirstSampleNoParameterSet(
        String fixture, String firstSample) {
      var video =
          probe(recordedAnswers(fixture).firstPackets(packets(firstSample)).build()).getStreams(0);

      assertThat(video.hasCodecString()).isFalse();
    }

    @Test
    @DisplayName(
        "Should report no codec string when the avcC record holds no NAL unit and the first sample"
            + " an SPS but no PPS")
    void shouldReportNoCodecStringWhenTheAvcCRecordHoldsNoNalUnitAndTheFirstSampleAnSpsButNoPps() {
      // FFmpeg's avcC writer needs a PPS as well as an SPS, so the muxer writes an avcC box
      // without a record. The sample holds an SPS that names Constrained Baseline level 1.0, then
      // an IDR slice, each after its 4-byte length.
      var video =
          probe(
                  recordedAnswers(AVC_SAMPLE_PARAMETER_SETS)
                      .firstPackets(packets("00000000: 0000 0004 6742 c00a 0000 0004 6588 843a"))
                      .build())
              .getStreams(0);

      assertThat(video.hasCodecString()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("extradataOtherThanAnAvcCRecordWithoutNalUnits")
    @DisplayName(
        "Should report no codec string when the first sample holds the parameter sets but the"
            + " extradata is no whole avcC record without NAL units")
    void shouldReportNoCodecStringWhenTheExtradataIsNoWholeAvcCRecordWithoutNalUnits(
        String extradata) {
      // The recorded first sample holds an SPS and a PPS. The copy's Annex B filter gives the
      // muxer a record's parameter sets, so the muxer reads no sample, and without an SPS that it
      // can read writes an avcC box without a record.
      var recorded = fixture(AVC_SAMPLE_PARAMETER_SETS);
      var edited =
          recorded.replace(
              "\"\\n00000000: 0142 c00a ffe0 00                        .B.....\\n\"",
              "\"%s\"".formatted(ffprobeDump(HexFormat.ofDelimiter(" ").parseHex(extradata))));
      assertThat(edited).isNotEqualTo(recorded);

      var video =
          probe(recordedAnswers(AVC_SAMPLE_PARAMETER_SETS).ffprobeOutput(edited).build())
              .getStreams(0);

      assertThat(video.hasCodecString()).isFalse();
    }

    @Test
    @DisplayName("Should report no codec string when the record holds a NAL unit but no VPS or SPS")
    void shouldReportNoCodecStringWhenTheRecordHoldsANalUnitButNoVpsOrSps() {
      // The copy's Annex B filter gives the muxer the record's PPS, so the muxer reads no sample
      // and writes an hvcC box without a record.
      var recorded = fixture(HEVC_SAMPLE_PARAMETER_SETS);
      var withPictureParameterSet =
          recorded.replace(
              "00000010: fdfa fa00 000f 00                        .......",
              "00000010: fdfa fa00 000f 01a2 0001 0006 4401 c073  ............D..s\\n"
                  + "00000020: c189                                     ..");
      assertThat(withPictureParameterSet).isNotEqualTo(recorded);

      var video =
          probe(
                  recordedAnswers(HEVC_SAMPLE_PARAMETER_SETS)
                      .ffprobeOutput(withPictureParameterSet)
                      .build())
              .getStreams(0);

      assertThat(video.hasCodecString()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("avcCRecordsWithAnSpsButNoPpsThatFfmpegReads")
    @DisplayName(
        "Should report no codec string when the avcC record holds an SPS but no PPS that FFmpeg"
            + " reads")
    void shouldReportNoCodecStringWhenTheAvcCRecordHoldsAnSpsButNoPpsThatFfmpegReads(
        String pictureParameterSets) {
      // The copy's Annex B filter refuses a record that FFmpeg cannot read whole, and otherwise
      // gives the muxer the record's SPS, so the muxer reads no sample, although the recorded
      // first sample holds a PPS. FFmpeg's avcC writer needs a PPS as well, so the copy's avcC box
      // holds no record.
      var recorded = fixture(AVC_SAMPLE_PARAMETER_SETS);
      var withSequenceParameterSet =
          recorded.replace(
              "\"\\n00000000: 0142 c00a ffe0 00                        .B.....\\n\"",
              "\"%s\""
                  .formatted(
                      ffprobeDump(
                          HexFormat.ofDelimiter(" ")
                              .parseHex(CONSTRAINED_BASELINE_SPS_RECORD + pictureParameterSets))));
      assertThat(withSequenceParameterSet).isNotEqualTo(recorded);

      var video =
          probe(
                  recordedAnswers(AVC_SAMPLE_PARAMETER_SETS)
                      .ffprobeOutput(withSequenceParameterSet)
                      .build())
              .getStreams(0);

      assertThat(video.getCodecProfile())
          .isEqualTo(VideoCodecProfile.VIDEO_CODEC_PROFILE_H264_CONSTRAINED_BASELINE);
      assertThat(video.hasCodecString()).isFalse();
    }

    private static Stream<Arguments> avcCRecordsWithAnSpsButNoPpsThatFfmpegReads() {
      return Stream.of(
          Arguments.of(Named.of("no PPS", " 00")),
          Arguments.of(Named.of("no PPS count", "")),
          Arguments.of(Named.of("a PPS that ends early", " 01 00 04 68 ce")),
          Arguments.of(Named.of("a counted PPS of 0 bytes", " 01 00 00")));
    }

    @Test
    @DisplayName(
        "Should report no codec string when Annex B extradata holds no SPS and the first sample"
            + " holds the parameter sets")
    void shouldReportNoCodecStringWhenAnnexBExtradataHoldsNoSps() {
      // The Annex B filter passes Annex B extradata through, so the muxer reads this access unit
      // delimiter rather than the sample, and writes an avcC box without a record. Its bytes would
      // count no SPS and no PPS if they were an avcC record.
      var video =
          probe(
                  recordedAnswers(AVC_SAMPLE_PARAMETER_SETS)
                      .ffprobeOutput(
                          fixture(AVC_SAMPLE_PARAMETER_SETS)
                              .replace(
                                  "0142 c00a ffe0 00                        .B.....",
                                  "0000 0109 f000 00                        ......."))
                      .firstPackets(packets("00000000: 0000 0001 6742 c00a 0000 0001 68ce 0fc8"))
                      .build())
              .getStreams(0);

      assertThat(video.hasCodecString()).isFalse();
    }

    private static String packets(String dump) {
      return """
          {"packets":[{"pts":0,"data":"\\n%s\\n"}]}
          """
          .formatted(dump);
    }

    private static Stream<Arguments> extradataOtherThanAnAvcCRecordWithoutNalUnits() {
      return Stream.of(
          Arguments.of(
              Named.of(
                  "avcC record with a PPS but no SPS", "01 42 c0 0a ff e0 01 00 04 68 ce 0f c8")),
          Arguments.of(
              Named.of(
                  "avcC record with an SPS that ends before its level",
                  "01 42 c0 0a ff e1 00 02 67 42 01 00 04 68 ce 0f c8")),
          Arguments.of(
              Named.of("avcC record that ends before its PPS count", "01 42 c0 0a ff e0")));
    }

    private static Stream<Arguments> absentOrIncompleteParameterSets() {
      return Stream.of(
          Arguments.of(Named.of("AVC without parameter sets", "h264"), "01 42 c0 33 ff e0 00"),
          Arguments.of(
              Named.of("HEVC without parameter sets", "hevc"),
              "01 02 20 00 00 00 b0 00 00 00 00 00 7b f0 00 fc fd fa fa 00 00 0f 00"),
          Arguments.of(
              Named.of("AVC truncated parameter set", "h264"), "01 64 00 1f ff e1 00 40 67"),
          Arguments.of(Named.of("AVC empty parameter set", "h264"), "01 64 00 1f ff e1 00 00"),
          Arguments.of(
              Named.of("AVC truncated parameter-set length", "h264"), "01 64 00 1f ff e1 00"),
          Arguments.of(
              Named.of("HEVC truncated parameter set", "hevc"),
              "01 02 20 00 00 00 b0 00 00 00 00 00 7b f0 00 fc fd fa fa 00 00 0f 01 a0 00 01 00 40"),
          Arguments.of(
              Named.of("HEVC truncated parameter-set array", "hevc"),
              "01 02 20 00 00 00 b0 00 00 00 00 00 7b f0 00 fc fd fa fa 00 00 0f 01 a0"));
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
      "av1, 0e",
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
      var video = streamOf("mp4-hevc-record-level.mp4", 0);

      assertThat(video.getCodecString()).isEqualTo("hvc1.1.6.L30.90");
    }
  }

  @Nested
  @DisplayName("AV1 Sequence Headers")
  class Av1SequenceHeaderTests {

    @Test
    @DisplayName(
        "Should read the first packet's sequence header when the container holds no av1C record")
    void shouldReadTheFirstPacketsSequenceHeaderWhenTheContainerHoldsNoAv1CRecord() {
      // ffprobe prints the sequence header OBU that it extracts from the first packet as the
      // stream's extradata, and the mp4 muxer builds the copy's av1C record from the same OBU.
      var video = streamOf("mkv-av1-without-codec-private.mkv", 0);

      assertThat(video.getCodecString()).isEqualTo("av01.0.00M.10");
    }

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
              .field("seq_profile", 3, 2)
              .field("still_picture", 1, 0)
              .field("reduced_still_picture_header", 1, 0)
              .field("timing_info_present_flag", 1, 1)
              .field("num_units_in_display_tick", 32, 1001)
              .field("time_scale", 32, 60000)
              .field("equal_picture_interval", 1, 1)
              .field("num_ticks_per_picture_minus_1 uvlc", 3, 0b010)
              .field("decoder_model_info_present_flag", 1, 1)
              .field("buffer_delay_length_minus_1", 5, 9)
              .field("num_units_in_decoding_tick", 32, 1)
              .field("buffer_removal_time_length_minus_1", 5, 9)
              .field("frame_presentation_time_length_minus_1", 5, 9)
              .field("initial_display_delay_present_flag", 1, 1)
              .field("operating_points_cnt_minus_1", 5, 1)
              .field("operating_point_idc[0]", 12, 0x101)
              .field("seq_level_idx[0]", 5, 13)
              .field("seq_tier[0]", 1, 1)
              .field("decoder_model_present_for_this_op[0]", 1, 1)
              .field("decoder_buffer_delay[0]", 10, 0)
              .field("encoder_buffer_delay[0]", 10, 0)
              .field("low_delay_mode_flag[0]", 1, 0)
              .field("initial_display_delay_present_for_this_op[0]", 1, 1)
              .field("initial_display_delay_minus_1[0]", 4, 3)
              .field("operating_point_idc[1]", 12, 0x102)
              .field("seq_level_idx[1]", 5, 4)
              .field("decoder_model_present_for_this_op[1]", 1, 0)
              .field("initial_display_delay_present_for_this_op[1]", 1, 0)
              .field("frame_width_bits_minus_1", 4, 10)
              .field("frame_height_bits_minus_1", 4, 10)
              .field("max_frame_width_minus_1", 11, 1919)
              .field("max_frame_height_minus_1", 11, 1079)
              .field("frame_id_numbers_present_flag", 1, 0)
              .field("use_128x128_superblock", 1, 0)
              .field("enable_filter_intra", 1, 1)
              .field("enable_intra_edge_filter", 1, 1)
              .field("enable_interintra_compound", 1, 0)
              .field("enable_masked_compound", 1, 0)
              .field("enable_warped_motion", 1, 0)
              .field("enable_dual_filter", 1, 0)
              .field("enable_order_hint", 1, 1)
              .field("enable_jnt_comp", 1, 0)
              .field("enable_ref_frame_mvs", 1, 0)
              .field("seq_choose_screen_content_tools", 1, 1)
              .field("seq_choose_integer_mv", 1, 0)
              .field("seq_force_integer_mv", 1, 1)
              .field("order_hint_bits_minus_1", 3, 6)
              .field("enable_superres", 1, 0)
              .field("enable_cdef", 1, 1)
              .field("enable_restoration", 1, 1)
              .field("high_bitdepth", 1, 1)
              .field("twelve_bit", 1, 1);

      var video = stream("av1", header.obu());

      assertThat(video.getCodecString()).isEqualTo("av01.2.13H.12");
    }

    @Test
    @DisplayName("Should read past a 32-zero uvlc when the timing info states an equal interval")
    void shouldReadPastA32ZeroUvlcWhenTheTimingInfoStatesAnEqualInterval() {
      // AV1 section 4.10.3: uvlc() reads up to its one bit and, after 32 or more zeros, no value
      // bits. Then no decoder model, one operating point at level 9 in the main tier with an
      // initial display delay, frame ID numbers, order hints and screen content tools that the
      // header selects.
      var header =
          new SequenceHeaderBits()
              .bits(3, 0)
              .bits(1, 0)
              .bits(1, 0)
              .bits(1, 1)
              .bits(32, 1001)
              .bits(32, 60000)
              .bits(1, 1)
              .bits(32, 0)
              .bits(1, 1)
              .bits(1, 0)
              .bits(1, 1)
              .bits(5, 0)
              .bits(12, 0)
              .bits(5, 9)
              .bits(1, 0)
              .bits(1, 1)
              .bits(4, 2)
              .frameSize()
              .bits(1, 1)
              .bits(4, 0)
              .bits(3, 0)
              .bits(3, 0)
              .bits(4, 0)
              .bits(1, 1)
              .bits(2, 0)
              .bits(1, 1)
              .bits(1, 1)
              .bits(3, 6)
              .bits(3, 0)
              .bits(1, 1);

      var video = stream("av1", header.obu());

      assertThat(video.getCodecString()).isEqualTo("av01.0.09M.10");
    }

    @Test
    @DisplayName("Should read the header after the extension byte when the OBU has no size field")
    void shouldReadTheHeaderAfterTheExtensionByteWhenTheObuHasNoSizeField() {
      // One operating point at level 8, which carries a tier bit, without order hints. The header
      // forces screen content tools and leaves integer motion vectors to each frame.
      var header =
          new SequenceHeaderBits()
              .bits(3, 0)
              .bits(1, 0)
              .bits(1, 0)
              .bits(1, 0)
              .bits(1, 0)
              .bits(5, 0)
              .bits(12, 0)
              .bits(5, 8)
              .bits(1, 1)
              .frameSize()
              .bits(1, 0)
              .bits(3, 0)
              .bits(4, 0)
              .bits(1, 0)
              .bits(1, 0)
              .bits(1, 1)
              .bits(1, 0)
              .bits(1, 0)
              .bits(3, 0)
              .bits(1, 0);

      var video = stream("av1", "0c 00 " + header.payload());

      assertThat(video.getCodecString()).isEqualTo("av01.0.08H.08");
    }

    @Test
    @DisplayName("Should read no twelve-bit flag when a profile 2 header states 8 bits")
    void shouldReadNoTwelveBitFlagWhenAProfile2HeaderStates8Bits() {
      // A reduced still picture header at level 5, then high_bitdepth 0 and padding bits of 1.
      var header =
          new SequenceHeaderBits()
              .bits(3, 2)
              .bits(1, 1)
              .bits(1, 1)
              .bits(5, 5)
              .frameSize()
              .bits(3, 0)
              .bits(3, 0)
              .bits(1, 0)
              .bits(7, 0x7f);

      var video = stream("av1", header.obu());

      assertThat(video.getCodecString()).isEqualTo("av01.2.05M.08");
    }

    @Test
    @DisplayName("Should read a size field of two bytes when the OBU continues its leb128 value")
    void shouldReadASizeFieldOfTwoBytesWhenTheObuContinuesItsLeb128Value() {
      // The IVF fixture's 11-byte sequence header with its size written as 8b 00.
      var video = stream("av1", "0a 8b 00 00 00 00 05 15 7f 8c 4a f9 40 40");

      assertThat(video.getCodecString()).isEqualTo("av01.0.00M.10");
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

    SequenceHeaderBits field(String name, int count, long value) {
      assertThat(value).as(name + " fits its " + count + " bits").isBetween(0L, (1L << count) - 1);
      return bits(count, value);
    }

    SequenceHeaderBits bits(int count, long value) {
      for (var bit = count - 1; bit >= 0; bit--) {
        bits.append((value >> bit) & 1);
      }

      return this;
    }

    /**
     * Frames of 1x1 pixels: frame_width_bits_minus_1 and frame_height_bits_minus_1 of 0, then one
     * bit each for max_frame_width_minus_1 and max_frame_height_minus_1.
     */
    SequenceHeaderBits frameSize() {
      return bits(4, 0).bits(4, 0).bits(1, 0).bits(1, 0);
    }

    /** The OBU, with a header that names a sequence header and a one-byte size field. */
    String obu() {
      var payload = bytes();
      return "0a "
          + HexFormat.of().formatHex(new byte[] {(byte) payload.length})
          + " "
          + HexFormat.ofDelimiter(" ").formatHex(payload);
    }

    /** The payload alone, padded with zero bits to a whole byte. */
    String payload() {
      return HexFormat.ofDelimiter(" ").formatHex(bytes());
    }

    private byte[] bytes() {
      while (bits.length() % 8 != 0) {
        bits.append('0');
      }

      var payload = new byte[bits.length() / 8];
      for (var index = 0; index < payload.length; index++) {
        payload[index] = (byte) Integer.parseInt(bits.substring(index * 8, index * 8 + 8), 2);
      }

      return payload;
    }
  }

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

  /** A video stream of the codec whose extradata ffprobe dumps as the given bytes. */
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
}
