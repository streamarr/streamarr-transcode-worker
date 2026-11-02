package com.streamarr.transcode.probe;

import static com.streamarr.transcode.probe.RecordedFfprobe.probeOutput;
import static com.streamarr.transcode.probe.RecordedFfprobe.streamOf;
import static org.assertj.core.api.Assertions.assertThat;

import build.buf.gen.streamarr.transcode.v1.ProbeStreamInfo;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Ffprobe Executor Audio Facts Tests")
class FfprobeExecutorAudioFactsTest {

  // The STREAMINFO block of a stereo FLAC stream that FFmpeg encoded with -frame_size 144, as the
  // pinned ffprobe prints it with -show_data: the first two bytes hold the minimum block size.
  private static final String STREAMINFO_OF_144_SAMPLE_BLOCKS =
      "\\n00000000: 0090 0090 0000 4600 0075 0ac4 42f0 0000  ......F..u..B...\\n"
          + "00000010: ac44 e3d5 a524 00c8 5f97 8eeb d475 ec8a  .D...$.._....u..\\n"
          + "00000020: 10bf                                     ..\\n";

  // The same block with the 4-byte fLaC marker in front of it: 38 bytes, so not a STREAMINFO block.
  private static final String MARKED_STREAMINFO =
      "\\n00000000: 664c 6143 0090 0090 0000 4600 0075 0ac4  fLaC......F..u..\\n"
          + "00000010: 42f0 0000 ac44 e3d5 a524 00c8 5f97 8eeb  B....D...$.._...\\n"
          + "00000020: d475 ec8a 10bf                           .u....\\n";

  @Nested
  @DisplayName("Channel Layout")
  class ChannelLayoutTests {

    @Test
    @DisplayName("Should report ffprobe's layout name when an E-AC-3 stream holds 7.1")
    void shouldReportFfprobesLayoutNameWhenAnEac3StreamHolds71() {
      var audio = streamOf("mkv-eac3-atmos-dts-hd-ma.mkv", 1);

      assertThat(audio.getChannelLayout()).isEqualTo("7.1");
    }

    @Test
    @DisplayName("Should report ffprobe's description unchanged when the layout has no name")
    void shouldReportFfprobesDescriptionUnchangedWhenTheLayoutHasNoName() {
      // The pinned ffprobe's description of a WAV file whose channel mask names FL, FR and TFL.
      var audio = audioOf("\"channels\":3,\"channel_layout\":\"3 channels (FL+FR+TFL)\"");

      assertThat(audio.getChannelLayout()).isEqualTo("3 channels (FL+FR+TFL)");
    }

    @Test
    @DisplayName("Should report no layout but the channels when ffprobe prints no channel layout")
    void shouldReportNoLayoutButTheChannelsWhenFfprobePrintsNoChannelLayout() {
      // The pinned ffprobe prints no channel_layout for 4-channel PCM in Matroska.
      var audio = audioOf("\"codec_name\":\"pcm_s16le\",\"channels\":4,\"sample_fmt\":\"s16\"");

      assertThat(audio.hasChannelLayout()).isFalse();
      assertThat(audio.getChannels()).isEqualTo(4);
    }

    @Test
    @DisplayName("Should report no layout when ffprobe prints unknown")
    void shouldReportNoLayoutWhenFfprobePrintsUnknown() {
      var audio = audioOf("\"channels\":4,\"channel_layout\":\"unknown\"");

      assertThat(audio.hasChannelLayout()).isFalse();
    }
  }

  @Nested
  @DisplayName("Sample Format and Bit Depth")
  class SampleFormatAndBitDepthTests {

    static Stream<Arguments> recordedLosslessStreams() {
      return Stream.of(
          Arguments.of("mkv-eac3-atmos-dts-hd-ma.mkv", 2, "s32p", 24),
          Arguments.of("mp4-audio-codecs.mp4", 3, "s16", 16),
          Arguments.of("mp4-audio-codecs.mp4", 4, "s16p", 16));
    }

    @ParameterizedTest(name = "{0} stream {1}")
    @MethodSource("recordedLosslessStreams")
    @DisplayName("Should report the raw sample bits and sample format when the stream is lossless")
    void shouldReportTheRawSampleBitsAndSampleFormatWhenTheStreamIsLossless(
        String fixture, int index, String sampleFormat, int bitDepth) {
      var audio = streamOf(fixture, index);

      assertThat(audio.getSampleFormat()).isEqualTo(sampleFormat);
      assertThat(audio.getBitDepth()).isEqualTo(bitDepth);
    }

    @Test
    @DisplayName("Should report no bit depth but the sample format when the stream is AC-3")
    void shouldReportNoBitDepthButTheSampleFormatWhenTheStreamIsAc3() {
      // ffprobe prints no bits_per_raw_sample and a bits_per_sample of 0 for AC-3.
      var audio = streamOf("mp4-audio-codecs.mp4", 1);

      assertThat(audio.getSampleFormat()).isEqualTo("fltp");
      assertThat(audio.hasBitDepth()).isFalse();
    }

    @Test
    @DisplayName("Should report the bits per sample when ffprobe prints no raw sample bits")
    void shouldReportTheBitsPerSampleWhenFfprobePrintsNoRawSampleBits() {
      // The pinned ffprobe's fields for pcm_s16le in WAV.
      var audio =
          audioOf("\"codec_name\":\"pcm_s16le\",\"sample_fmt\":\"s16\",\"bits_per_sample\":16");

      assertThat(audio.getBitDepth()).isEqualTo(16);
    }

    @Test
    @DisplayName(
        "Should report 32 bits beside the flt format when the stream is floating-point PCM")
    void shouldReport32BitsBesideTheFltFormatWhenTheStreamIsFloatingPointPcm() {
      var audio =
          audioOf(
              "\"codec_name\":\"pcm_f32le\",\"sample_fmt\":\"flt\",\"bits_per_raw_sample\":\"0\","
                  + "\"bits_per_sample\":32");

      assertThat(audio.getSampleFormat()).isEqualTo("flt");
      assertThat(audio.getBitDepth()).isEqualTo(32);
    }

    @Test
    @DisplayName("Should prefer the raw sample bits when ffprobe prints both depths")
    void shouldPreferTheRawSampleBitsWhenFfprobePrintsBothDepths() {
      var audio = audioOf("\"bits_per_raw_sample\":\"20\",\"bits_per_sample\":24");

      assertThat(audio.getBitDepth()).isEqualTo(20);
    }

    @Test
    @DisplayName("Should report no bit depth when ffprobe prints N/A and 0")
    void shouldReportNoBitDepthWhenFfprobePrintsNaAnd0() {
      var audio = audioOf("\"bits_per_raw_sample\":\"N/A\",\"bits_per_sample\":0");

      assertThat(audio.hasBitDepth()).isFalse();
    }

    @Test
    @DisplayName("Should report no sample format when ffprobe prints unknown")
    void shouldReportNoSampleFormatWhenFfprobePrintsUnknown() {
      var audio = audioOf("\"sample_fmt\":\"unknown\"");

      assertThat(audio.hasSampleFormat()).isFalse();
    }

    @Test
    @DisplayName("Should report no audio facts when the stream is a subtitle")
    void shouldReportNoAudioFactsWhenTheStreamIsASubtitle() {
      var subtitle =
          parsedStream(
              """
              {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"},
                          {"index":1,"codec_type":"subtitle","codec_name":"subrip",
                           "sample_fmt":"s16","bits_per_sample":16,
                           "channel_layout":"stereo","extradata":"%s"}]}
              """
                  .formatted(STREAMINFO_OF_144_SAMPLE_BLOCKS),
              1);

      assertThat(subtitle.hasChannelLayout()).isFalse();
      assertThat(subtitle.hasSampleFormat()).isFalse();
      assertThat(subtitle.hasBitDepth()).isFalse();
      assertThat(subtitle.hasFlacMinimumBlockSizeSamples()).isFalse();
    }
  }

  @Nested
  @DisplayName("Dispositions")
  class DispositionTests {

    static Stream<Arguments> audioDispositions() {
      return Stream.of(
          Arguments.of("comment", (Predicate<ProbeStreamInfo>) ProbeStreamInfo::getIsComment),
          Arguments.of(
              "visual_impaired", (Predicate<ProbeStreamInfo>) ProbeStreamInfo::getIsVisualImpaired),
          Arguments.of(
              "descriptions", (Predicate<ProbeStreamInfo>) ProbeStreamInfo::getIsDescriptions),
          Arguments.of("original", (Predicate<ProbeStreamInfo>) ProbeStreamInfo::getIsOriginal),
          Arguments.of("dub", (Predicate<ProbeStreamInfo>) ProbeStreamInfo::getIsDub),
          Arguments.of(
              "clean_effects", (Predicate<ProbeStreamInfo>) ProbeStreamInfo::getIsCleanEffects),
          Arguments.of("dependent", (Predicate<ProbeStreamInfo>) ProbeStreamInfo::getIsDependent));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("audioDispositions")
    @DisplayName("Should report only the flag that ffprobe sets when one disposition is set")
    void shouldReportOnlyTheFlagThatFfprobeSetsWhenOneDispositionIsSet(
        String disposition, Predicate<ProbeStreamInfo> flag) {
      var audio = audioOf(dispositions(disposition));

      assertThat(flag.test(audio)).as(disposition).isTrue();
      assertThat(sevenFlags(audio)).filteredOn(Boolean::booleanValue).hasSize(1);
    }

    @Test
    @DisplayName("Should report all seven flags as false when ffprobe sets none of them")
    void shouldReportAllSevenFlagsAsFalseWhenFfprobeSetsNoneOfThem() {
      // The second AAC stream of mp4-titled.mp4 sets no disposition.
      var audio = streamOf("mp4-titled.mp4", 2);

      assertThat(presentFlags(audio)).containsOnly(true);
      assertThat(sevenFlags(audio)).containsOnly(false);
    }

    @Test
    @DisplayName("Should report all seven flags as false when ffprobe prints no disposition")
    void shouldReportAllSevenFlagsAsFalseWhenFfprobePrintsNoDisposition() {
      var audio = audioOf("\"codec_name\":\"aac\"");

      assertThat(presentFlags(audio)).containsOnly(true);
      assertThat(sevenFlags(audio)).containsOnly(false);
    }

    @Test
    @DisplayName("Should report the commentary flag alone when a subtitle stream is commentary")
    void shouldReportTheCommentaryFlagAloneWhenASubtitleStreamIsCommentary() {
      var subtitle =
          parsedStream(
              """
              {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"},
                          {"index":1,"codec_type":"subtitle","codec_name":"subrip",
                           "disposition":{"comment":1,"dub":1,"visual_impaired":1}}]}
              """,
              1);

      assertThat(subtitle.hasIsComment()).isTrue();
      assertThat(subtitle.getIsComment()).isTrue();
      assertThat(presentFlags(subtitle))
          .containsExactly(true, false, false, false, false, false, false);
    }

    @Test
    @DisplayName("Should report a false commentary flag when a subtitle stream sets no disposition")
    void shouldReportAFalseCommentaryFlagWhenASubtitleStreamSetsNoDisposition() {
      var subtitle = streamOf("mp4-default-handlers.mp4", 2);

      assertThat(subtitle.hasIsComment()).isTrue();
      assertThat(subtitle.getIsComment()).isFalse();
    }

    @Test
    @DisplayName("Should report none of the seven flags when the stream is video")
    void shouldReportNoneOfTheSevenFlagsWhenTheStreamIsVideo() {
      var video = streamOf("mp4-titled.mp4", 0);

      assertThat(presentFlags(video)).containsOnly(false);
    }

    private static String dispositions(String set) {
      return "\"disposition\":{\"default\":0,\"comment\":%d,\"visual_impaired\":%d,"
              .formatted(flag(set, "comment"), flag(set, "visual_impaired"))
          + "\"descriptions\":%d,\"original\":%d,\"dub\":%d,\"clean_effects\":%d,\"dependent\":%d}"
              .formatted(
                  flag(set, "descriptions"),
                  flag(set, "original"),
                  flag(set, "dub"),
                  flag(set, "clean_effects"),
                  flag(set, "dependent"));
    }

    private static int flag(String set, String disposition) {
      return set.equals(disposition) ? 1 : 0;
    }

    private static List<Boolean> sevenFlags(ProbeStreamInfo stream) {
      return List.of(
          stream.getIsComment(),
          stream.getIsVisualImpaired(),
          stream.getIsDescriptions(),
          stream.getIsOriginal(),
          stream.getIsDub(),
          stream.getIsCleanEffects(),
          stream.getIsDependent());
    }

    private static List<Boolean> presentFlags(ProbeStreamInfo stream) {
      return List.of(
          stream.hasIsComment(),
          stream.hasIsVisualImpaired(),
          stream.hasIsDescriptions(),
          stream.hasIsOriginal(),
          stream.hasIsDub(),
          stream.hasIsCleanEffects(),
          stream.hasIsDependent());
    }
  }

  @Nested
  @DisplayName("FLAC Minimum Block Size")
  class FlacMinimumBlockSizeTests {

    @Test
    @DisplayName("Should report FFmpeg's 4,608 samples when the FLAC stream uses its default frame")
    void shouldReportFfmpegs4608SamplesWhenTheFlacStreamUsesItsDefaultFrame() {
      var flac = streamOf("mp4-audio-codecs.mp4", 3);

      assertThat(flac.getFlacMinimumBlockSizeSamples()).isEqualTo(4608);
    }

    @Test
    @DisplayName("Should report 144 samples when the STREAMINFO block starts with 0x0090")
    void shouldReport144SamplesWhenTheStreaminfoBlockStartsWith0x0090() {
      var flac = audioOf(flacWithExtradata(STREAMINFO_OF_144_SAMPLE_BLOCKS));

      assertThat(flac.getFlacMinimumBlockSizeSamples()).isEqualTo(144);
    }

    @Test
    @DisplayName("Should report no block size when the FLAC extradata is not 34 bytes long")
    void shouldReportNoBlockSizeWhenTheFlacExtradataIsNot34BytesLong() {
      var flac = audioOf(flacWithExtradata(MARKED_STREAMINFO));

      assertThat(flac.hasFlacMinimumBlockSizeSamples()).isFalse();
    }

    @Test
    @DisplayName("Should report no block size when the FLAC stream has no extradata")
    void shouldReportNoBlockSizeWhenTheFlacStreamHasNoExtradata() {
      var flac = audioOf(flacWithExtradata("\\n"));

      assertThat(flac.hasFlacMinimumBlockSizeSamples()).isFalse();
    }

    @Test
    @DisplayName("Should report no block size when a stream that is not FLAC has 34 bytes")
    void shouldReportNoBlockSizeWhenAStreamThatIsNotFlacHas34Bytes() {
      var alac =
          audioOf(
              "\"codec_name\":\"alac\",\"extradata\":\"%s\""
                  .formatted(STREAMINFO_OF_144_SAMPLE_BLOCKS));

      assertThat(alac.hasFlacMinimumBlockSizeSamples()).isFalse();
    }

    private static String flacWithExtradata(String extradata) {
      return "\"codec_name\":\"flac\",\"extradata\":\"%s\"".formatted(extradata);
    }
  }

  // The audio stream of an ffprobe output whose second stream carries the given fields.
  private static ProbeStreamInfo audioOf(String fields) {
    return parsedStream(
        """
        {"streams":[{"index":0,"codec_type":"video","time_base":"1/1000"},
                    {"index":1,"codec_type":"audio",%s}]}
        """
            .formatted(fields),
        1);
  }

  private static ProbeStreamInfo parsedStream(String ffprobeOutput, int index) {
    return probeOutput(ffprobeOutput).getStreams(index);
  }
}
