package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("UnitTest")
@DisplayName("FFmpeg Command Builder Audio Layout Tests")
class FfmpegCommandBuilderAudioLayoutTest {

  private static final String TUNED_RESAMPLER =
      "filter_size=128:phase_shift=14:cutoff=0.95:kaiser_beta=14";

  private final FfmpegCommandBuilder builder =
      new FfmpegCommandBuilder("ffmpeg", Duration.ofSeconds(1));

  @Nested
  @DisplayName("Channel Layout")
  class ChannelLayoutTests {

    @Test
    @DisplayName("Should name the layout instead of a channel count when the job names a layout")
    void shouldNameTheLayoutInsteadOfAChannelCountWhenTheJobNamesALayout() {
      var cmd = command(encode("aac", "quad"), source("aac", "quad"));

      assertThat(cmd).containsSubsequence("-ch_layout", "quad").doesNotContain("-ac");
      assertThat(audioFilter(cmd)).isEqualTo("aresample=" + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName("Should keep the channel count when the job names no layout")
    void shouldKeepTheChannelCountWhenTheJobNamesNoLayout() {
      var audio =
          AudioDecision.builder()
              .mode(AudioMode.TRANSCODE)
              .codec("aac")
              .channels(4)
              .bitrate(256_000)
              .build();

      var cmd = command(audio, Optional.empty());

      assertThat(cmd).containsSubsequence("-ac", "4").doesNotContain("-ch_layout", "-af");
    }

    @Test
    @DisplayName("Should map the surround pair with channelmap when the output only renames it")
    void shouldMapTheSurroundPairWithChannelmapWhenTheOutputOnlyRenamesIt() {
      var cmd = command(encode("aac", "5.1"), source("truehd", "5.1(side)"));

      assertThat(audioFilter(cmd))
          .startsWith("channelmap=map=FL-FL|FR-FR|FC-FC|LFE-LFE|SL-BL|SR-BR:channel_layout=5.1,");
    }

    @Test
    @DisplayName("Should map the back centre to the back centre when 6.1 becomes 6.1(back)")
    void shouldMapTheBackCentreToTheBackCentreWhen61Becomes61Back() {
      var cmd = command(encode("alac", "6.1(back)"), source("flac", "6.1"));

      assertThat(audioFilter(cmd))
          .startsWith(
              "channelmap=map=FL-FL|FR-FR|FC-FC|LFE-LFE|SL-BL|SR-BR|BC-BC"
                  + ":channel_layout=6.1(back),");
    }

    @Test
    @DisplayName("Should pad with silent channels through pan when FLAC carries no 4.0 layout")
    void shouldPadWithSilentChannelsThroughPanWhenFlacCarriesNo40Layout() {
      var cmd = command(encode("flac", "6.1"), source("pcm_s16le", "4.0"));

      assertThat(audioFilter(cmd))
          .startsWith("pan=6.1|FL=FL|FR=FR|FC=FC|LFE=0*FL|BC=BC|SL=0*FL|SR=0*FL,");
      assertThat(cmd).containsSubsequence("-ch_layout", "6.1");
    }

    @Test
    @DisplayName("Should rename the side pair inside the padding when ALAC pads 6.0 to 6.1(back)")
    void shouldRenameTheSidePairInsideThePaddingWhenAlacPads60To61Back() {
      var cmd = command(encode("alac", "6.1(back)"), source("flac", "6.0"));

      assertThat(audioFilter(cmd))
          .startsWith("pan=6.1(back)|FL=FL|FR=FR|FC=FC|LFE=0*FL|BL=SL|BR=SR|BC=BC,");
    }

    @Test
    @DisplayName("Should read a stream without a layout as FFmpeg's default for its channel count")
    void shouldReadAStreamWithoutALayoutAsFfmpegsDefaultForItsChannelCount() {
      var pcm = SourceAudio.builder().codec("pcm_s24le").channels(6).bitDepth(OptionalInt.of(24));

      var cmd = command(encode("flac", "5.1"), Optional.of(pcm.build()));

      assertThat(audioFilter(cmd)).startsWith("aresample=");
    }

    @Test
    @DisplayName("Should add no channel filter when the output lacks a source channel")
    void shouldAddNoChannelFilterWhenTheOutputLacksASourceChannel() {
      var cmd = command(encode("aac", "stereo"), source("aac", "5.1"));

      assertThat(audioFilter(cmd)).startsWith("aresample=");
      assertThat(cmd).containsSubsequence("-ch_layout", "stereo");
    }
  }

  @Nested
  @DisplayName("Sample Processing")
  class SampleProcessingTests {

    @ParameterizedTest(name = "{0}")
    @CsvSource({"ac3", "eac3"})
    @DisplayName("Should decode without compression at the dialogue level when the source is Dolby")
    void shouldDecodeWithoutCompressionAtTheDialogueLevelWhenTheSourceIsDolby(String codec) {
      var cmd = command(encode("aac", "5.1"), source(codec, "5.1(side)"));

      assertThat(cmd)
          .containsSubsequence(
              "-drc_scale:a:0", "0", "-target_level:a:0", "-31", "-i", "/media/movie.mkv");
    }

    @Test
    @DisplayName("Should pass no decoder options when the source is not AC-3 or E-AC-3")
    void shouldPassNoDecoderOptionsWhenTheSourceIsNotAc3OrEac3() {
      var cmd = command(encode("aac", "5.1"), source("dts", "5.1(side)"));

      assertThat(cmd).doesNotContain("-drc_scale:a:0", "-target_level:a:0");
    }

    @ParameterizedTest(name = "{0} Hz")
    @CsvSource({"96000, osr=48000:", "32000, osr=48000:", "44100, ''", "48000, ''"})
    @DisplayName("Should write 48 kHz from any rate but 44.1 and 48 kHz when the encode is lossy")
    void shouldWrite48KhzFromAnyRateBut441And48KhzWhenTheEncodeIsLossy(
        int sampleRate, String rate) {
      var source = sourceBuilder("flac", "stereo").sampleRate(OptionalInt.of(sampleRate)).build();

      var cmd = command(encode("ac3", "stereo"), Optional.of(source));

      assertThat(audioFilter(cmd)).isEqualTo("aresample=" + rate + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName("Should keep the source's rate when FLAC encodes 96 kHz")
    void shouldKeepTheSourcesRateWhenFlacEncodes96Khz() {
      var source =
          sourceBuilder("flac", "stereo")
              .sampleFormat(Optional.of("s32"))
              .bitDepth(OptionalInt.of(24))
              .sampleRate(OptionalInt.of(96_000))
              .build();

      var cmd = command(encode("flac", "stereo"), Optional.of(source));

      assertThat(audioFilter(cmd)).isEqualTo("aresample=osf=s32:" + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName("Should write 16 bits with TPDF dither when FLAC encodes decoded lossy audio")
    void shouldWrite16BitsWithTpdfDitherWhenFlacEncodesDecodedLossyAudio() {
      var cmd = command(encode("flac", "stereo"), source("eac3", "stereo"));

      assertThat(audioFilter(cmd))
          .isEqualTo("aresample=osf=s16:dither_method=triangular:" + TUNED_RESAMPLER);
    }

    @ParameterizedTest(name = "{0}, {1}, {2} bits")
    @CsvSource({
      "pcm_s24le, s32, 24, s32p",
      "pcm_s16le, s16, 16, s16p:dither_method=triangular",
      "pcm_f32le, flt, 32, s32p",
      "truehd, s32, 24, s32p",
      "flac, s16, 16, s16p:dither_method=triangular"
    })
    @DisplayName("Should keep a lossless track's depth up to 24 bits when ALAC encodes it")
    void shouldKeepALosslessTracksDepthUpTo24BitsWhenAlacEncodesIt(
        String codec, String sampleFormat, int bitDepth, String written) {
      var source =
          sourceBuilder(codec, "stereo")
              .sampleFormat(Optional.of(sampleFormat))
              .bitDepth(OptionalInt.of(bitDepth))
              .build();

      var cmd = command(encode("alac", "stereo"), Optional.of(source));

      assertThat(audioFilter(cmd)).isEqualTo("aresample=osf=" + written + ":" + TUNED_RESAMPLER);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"DTS-HD MA, s32", "DTS, s16:dither_method=triangular"})
    @DisplayName("Should count only DTS-HD MA as lossless when FLAC encodes DTS")
    void shouldCountOnlyDtsHdMaAsLosslessWhenFlacEncodesDts(String profile, String written) {
      var source =
          sourceBuilder("dts", "5.1(side)")
              .profile(Optional.of(profile))
              .sampleFormat(Optional.of("s32p"))
              .bitDepth(OptionalInt.of(24))
              .build();

      var cmd = command(encode("flac", "5.1(side)"), Optional.of(source));

      assertThat(audioFilter(cmd)).isEqualTo("aresample=osf=" + written + ":" + TUNED_RESAMPLER);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"pcm_s16be, s16:dither_method=triangular", "pcm_s24le, s32", "flac, s32"})
    @DisplayName("Should follow the codec name when the probe gives a lossless track no depth")
    void shouldFollowTheCodecNameWhenTheProbeGivesALosslessTrackNoDepth(
        String codec, String written) {
      var cmd = command(encode("flac", "stereo"), source(codec, "stereo"));

      assertThat(audioFilter(cmd)).isEqualTo("aresample=osf=" + written + ":" + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName("Should give FLAC no bitrate when the job carries one")
    void shouldGiveFlacNoBitrateWhenTheJobCarriesOne() {
      var cmd = command(encode("flac", "stereo"), source("flac", "stereo"));

      assertThat(cmd).doesNotContain("-b:a");
    }
  }

  @Nested
  @DisplayName("Opus")
  class OpusTests {

    @Test
    @DisplayName(
        "Should encode with libopus in mapping family 1 when Opus has more than 2 channels")
    void shouldEncodeWithLibopusInMappingFamily1WhenOpusHasMoreThan2Channels() {
      var cmd = command(encode("opus", "5.1"), source("aac", "5.1"));

      assertThat(cmd)
          .containsSubsequence("-c:a", "libopus")
          .containsSubsequence("-vbr", "constrained")
          .containsSubsequence("-mapping_family", "1")
          .containsSubsequence("-b:a", "448k");
      assertThat(audioFilter(cmd)).isEqualTo("aresample=osr=48000:" + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName("Should leave the mapping family to libopus when Opus encodes stereo")
    void shouldLeaveTheMappingFamilyToLibopusWhenOpusEncodesStereo() {
      var cmd = command(encode("opus", "stereo"), source("aac", "stereo"));

      assertThat(cmd).containsSubsequence("-c:a", "libopus").doesNotContain("-mapping_family");
    }
  }

  @Test
  @DisplayName(
      "Should encode the named layout in the source copy when the job moves Dolby Vision RPUs")
  void shouldEncodeTheNamedLayoutInTheSourceCopyWhenTheJobMovesDolbyVisionRpus() {
    var decision =
        TranscodeDecision.builder()
            .transcodeMode(TranscodeMode.FULL_TRANSCODE)
            .videoCodecFamily("hevc")
            .videoDecision(
                VideoDecisionFixtures.encodeBuilder()
                    .sourceCodecFamily(Optional.of(CodecFamily.HEVC))
                    .sourceDynamicRange(
                        new VideoDynamicRange(
                            VideoDynamicRange.CompatibleBase.NONE,
                            VideoDynamicRange.Presence.ABSENT,
                            new VideoDynamicRange.DolbyVision.Present(
                                Optional.empty(),
                                VideoDynamicRange.Presence.PRESENT,
                                VideoDynamicRange.DolbyVisionRpuPlacement.UNSPECIFIED)))
                    .sourcePacketsCarryPresentationTimes(true)
                    .build())
            .audioDecision(encode("opus", "5.1"))
            .subtitleDecision(SubtitleDecisions.EXCLUDED)
            .build();
    var request = request(decision).sourceAudio(source("ac3", "5.1(side)")).build();

    var pipeline =
        (FfmpegPipeline.RpuMovingPipe)
            builder.buildPipeline(
                TranscodeJob.builder().request(request).videoEncoder("libx265").build());

    assertThat(pipeline.copyCommand())
        .containsSubsequence("-drc_scale:a:0", "0", "-i")
        .containsSubsequence("-c:a", "libopus", "-af")
        .containsSubsequence("-ch_layout", "5.1");
  }

  private List<String> command(AudioDecision audio, Optional<SourceAudio> source) {
    var decision =
        TranscodeDecision.builder()
            .transcodeMode(TranscodeMode.AUDIO_TRANSCODE)
            .videoCodecFamily("h264")
            .videoDecision(VideoDecisionFixtures.forMode(TranscodeMode.AUDIO_TRANSCODE))
            .audioDecision(audio)
            .subtitleDecision(SubtitleDecisions.EXCLUDED)
            .build();
    var job =
        TranscodeJob.builder()
            .request(request(decision).sourceAudio(source).build())
            .videoEncoder("copy")
            .build();
    return ((FfmpegPipeline.SingleProcess) builder.buildPipeline(job)).command();
  }

  private static TranscodeRequest.TranscodeRequestBuilder request(TranscodeDecision decision) {
    return TranscodeRequest.builder()
        .sessionId(UUID.randomUUID())
        .sourcePath(Path.of("/media/movie.mkv"))
        .targetSegmentDuration(6)
        .outputFrameRate(Optional.of(new OutputFrameRate(24_000, 1001)))
        .mediaSegmentCount(11)
        .transcodeDecision(decision)
        .encoderPreset(EncoderPreset.FASTER)
        .width(1920)
        .height(1080)
        .bitrate(5_000_000L);
  }

  private static AudioDecision encode(String codec, String layout) {
    var named = SpeakerLayout.named(layout);
    return AudioDecision.builder()
        .mode(AudioMode.TRANSCODE)
        .codec(codec)
        .channels(named.channelCount())
        .bitrate(448_000)
        .channelLayout(Optional.of(named))
        .build();
  }

  private static Optional<SourceAudio> source(String codec, String layout) {
    return Optional.of(sourceBuilder(codec, layout).build());
  }

  private static SourceAudio.SourceAudioBuilder sourceBuilder(String codec, String layout) {
    return SourceAudio.builder()
        .codec(codec)
        .channels(SpeakerLayout.named(layout).channelCount())
        .channelLayout(Optional.of(layout))
        .sampleRate(OptionalInt.of(48_000));
  }

  private static String audioFilter(List<String> cmd) {
    assertThat(cmd).containsOnlyOnce("-af");
    return cmd.get(cmd.indexOf("-af") + 1);
  }
}
