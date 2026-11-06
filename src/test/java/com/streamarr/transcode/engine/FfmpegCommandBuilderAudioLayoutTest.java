package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
          .endsWith(",channelmap=map=FL-FL|FR-FR|FC-FC|LFE-LFE|SL-BL|SR-BR:channel_layout=5.1");
    }

    @Test
    @DisplayName("Should map the back centre to the back centre when 6.1 becomes 6.1(back)")
    void shouldMapTheBackCentreToTheBackCentreWhen61Becomes61Back() {
      var cmd = command(encode("alac", "6.1(back)"), source("flac", "6.1"));

      assertThat(audioFilter(cmd))
          .endsWith(
              ",channelmap=map=FL-FL|FR-FR|FC-FC|LFE-LFE|SL-BL|SR-BR|BC-BC"
                  + ":channel_layout=6.1(back)");
    }

    @Test
    @DisplayName("Should pad with silent channels through pan when FLAC carries no 4.0 layout")
    void shouldPadWithSilentChannelsThroughPanWhenFlacCarriesNo40Layout() {
      var cmd = command(encode("flac", "6.1"), source("pcm_s16le", "4.0"));

      assertThat(audioFilter(cmd))
          .endsWith(",pan=6.1|FL=FL|FR=FR|FC=FC|LFE=0*FL|BC=BC|SL=0*FL|SR=0*FL");
      assertThat(cmd).containsSubsequence("-ch_layout", "6.1");
    }

    @Test
    @DisplayName("Should rename the side pair inside the padding when ALAC pads 6.0 to 6.1(back)")
    void shouldRenameTheSidePairInsideThePaddingWhenAlacPads60To61Back() {
      var cmd = command(encode("alac", "6.1(back)"), source("flac", "6.0"));

      assertThat(audioFilter(cmd))
          .endsWith(",pan=6.1(back)|FL=FL|FR=FR|FC=FC|LFE=0*FL|BL=SL|BR=SR|BC=BC");
    }

    @Test
    @DisplayName(
        "Should resample and dither before the pad when the encode pads a resampled layout, so the added channels stay silent")
    void shouldResampleAndDitherBeforeThePadWhenTheEncodePadsAResampledLayout() {
      var source =
          sourceBuilder("flac", "4.0")
              .sampleFormat(Optional.of("s32"))
              .bitDepth(OptionalInt.of(24))
              .sampleRate(OptionalInt.of(96_000))
              .build();
      var audio = encodeBuilder("flac", "6.1").outputSampleRate(OptionalInt.of(48_000)).build();

      var cmd = command(audio, Optional.of(source));

      assertThat(audioFilter(cmd))
          .isEqualTo(
              "aresample=osr=48000:osf=s32:output_sample_bits=24:dither_method=triangular:"
                  + TUNED_RESAMPLER
                  + ",pan=6.1|FL=FL|FR=FR|FC=FC|LFE=0*FL|BC=BC|SL=0*FL|SR=0*FL");
    }

    @Test
    @DisplayName("Should read a stream without a layout as FFmpeg's default for its channel count")
    void shouldReadAStreamWithoutALayoutAsFfmpegsDefaultForItsChannelCount() {
      var pcm = SourceAudio.builder().codec("pcm_s24le").channels(6).bitDepth(OptionalInt.of(24));

      var cmd = command(encode("flac", "5.1"), Optional.of(pcm.build()));

      assertThat(audioFilter(cmd)).startsWith("aresample=");
    }
  }

  @Nested
  @DisplayName("Downmix")
  class DownmixTests {

    private static final String FLOAT_PAN = "aformat=sample_fmts=fltp,pan=";
    private static final String LIMIT_TIMING = ":attack=5:release=50:level=0:latency=1";
    private static final String MINUS_3_DBFS = "alimiter=limit=0.707946" + LIMIT_TIMING;
    private static final String MINUS_1_DBFS = "alimiter=limit=0.891251" + LIMIT_TIMING;
    private static final String MINUS_1_DBTP_STAGE =
        Pattern.quote("amerge=inputs=") + "\\d+," + Pattern.quote(MINUS_1_DBFS);
    private static final String MINUS_3_DBTP_STAGE =
        Pattern.quote("amerge=inputs=") + "\\d+," + Pattern.quote(MINUS_3_DBFS);
    private static final String TRIMMED_SECOND_STAGE =
        "atrim=start_sample=6,asetpts=round(PTS-6/(SR*TB))";
    private static final String DITHERED_16_BITS =
        "aresample=osf=s16:dither_method=triangular:" + TUNED_RESAMPLER;
    private static final String DITHERED_24_BITS =
        "aresample=osf=s32:output_sample_bits=24:dither_method=triangular:" + TUNED_RESAMPLER;

    @Test
    @DisplayName(
        "Should mix the centre and surrounds at -3 dB and limit the pair at -3 dBTP when stereo is"
            + " lossy")
    void shouldMixTheCentreAndSurroundsAtMinus3DbAndLimitThePairAtMinus3DbtpWhenStereoIsLossy() {
      var cmd = command(encode("aac", "stereo"), source("dts", "5.1(side)"));

      var filter = audioFilter(cmd);
      assertThat(filter)
          .startsWith(
              FLOAT_PAN
                  + "stereo|FL=FL+0.7071068*FC+0.7071068*SL|FR=FR+0.7071068*FC+0.7071068*SR"
                  + "[mix];aevalsrc=")
          .endsWith(TRIMMED_SECOND_STAGE + "[l0];[l0]anull,aresample=" + TUNED_RESAMPLER);
      assertThat(filter.split(MINUS_3_DBTP_STAGE, -1)).hasSize(3);
      assertThat(cmd).containsSubsequence("-ch_layout", "stereo");
    }

    @Test
    @DisplayName("Should mix each side's two surrounds at -3 dB each when 7.1 becomes stereo")
    void shouldMixEachSidesTwoSurroundsAtMinus3DbEachWhen71BecomesStereo() {
      var cmd = command(encode("aac", "stereo"), source("truehd", "7.1"));

      assertThat(audioFilter(cmd))
          .startsWith(
              FLOAT_PAN
                  + "stereo|FL=FL+0.7071068*FC+0.5000000*BL+0.5000000*SL"
                  + "|FR=FR+0.7071068*FC+0.5000000*BR+0.5000000*SR[mix];");
    }

    @Test
    @DisplayName(
        "Should fold the low-frequency channel into stereo when the source has no surround")
    void shouldFoldTheLowFrequencyChannelIntoStereoWhenTheSourceHasNoSurround() {
      var cmd = command(encode("aac", "stereo"), source("flac", "2.1"));

      assertThat(audioFilter(cmd))
          .startsWith(FLOAT_PAN + "stereo|FL=FL+0.7071068*LFE|FR=FR+0.7071068*LFE[mix];aevalsrc=")
          .contains(MINUS_3_DBFS);
    }

    @Test
    @DisplayName("Should mix at the track's Lo/Ro levels when AC-3 becomes mono")
    void shouldMixAtTheTracksLoRoLevelsWhenAc3BecomesMono() {
      var source =
          sourceBuilder("ac3", "5.1(side)")
              .loRoLevels(Optional.of(new SourceAudio.LoRoLevels(0.594604, 0.5)))
              .build();

      var cmd = command(encode("aac", "mono"), Optional.of(source));

      assertThat(audioFilter(cmd))
          .startsWith(
              FLOAT_PAN
                  + "mono|FC=0.7071068*FL+0.7071068*FR+0.8408970*FC"
                  + "+0.3535534*SL+0.3535534*SR[mix];aevalsrc=")
          .contains(MINUS_3_DBFS);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"flac", "alac", "aac"})
    @DisplayName(
        "Should leave channels out at the source rate when the probe gave no rate and nothing is"
            + " summed")
    void shouldLeaveChannelsOutAtTheSourceRateWhenTheProbeGaveNoRateAndNothingIsSummed(
        String codec) {
      var source = sourceBuilder("flac", "5.1(side)").sampleRate(OptionalInt.empty()).build();

      var cmd = command(encode(codec, "5.0"), Optional.of(source));

      assertThat(audioFilter(cmd))
          .startsWith(FLOAT_PAN + "5.0|FL=FL|FR=FR|FC=FC|BL=SL|BR=SR")
          .doesNotContain("alimiter", "aresample=osr");
    }

    @Test
    @DisplayName(
        "Should mix and limit at 48 kHz when a lossy encode downmixes a track the probe gave no"
            + " rate")
    void shouldMixAndLimitAt48KhzWhenALossyEncodeDownmixesATrackTheProbeGaveNoRate() {
      var source = sourceBuilder("dts", "5.1(side)").sampleRate(OptionalInt.empty()).build();

      var cmd = command(encode("aac", "stereo"), Optional.of(source));

      assertThat(audioFilter(cmd))
          .startsWith("aresample=osr=48000:" + TUNED_RESAMPLER + "," + FLOAT_PAN)
          .contains(":s=48000,atrim=end_sample=128")
          .doesNotContain(":s=44100");
    }

    @Test
    @DisplayName(
        "Should limit only the summed surround pair at -1 dBTP when FLAC writes E-AC-3 7.1 as 5.1")
    void shouldLimitOnlyTheSummedSurroundPairAtMinus1DbtpWhenFlacWritesEac371As51() {
      var source =
          sourceBuilder("eac3", "7.1")
              .loRoLevels(Optional.of(new SourceAudio.LoRoLevels(0.5, 0.5)))
              .build();

      var cmd = command(encode("flac", "5.1"), Optional.of(source));

      var filter = audioFilter(cmd);
      assertThat(filter)
          .startsWith(
              FLOAT_PAN
                  + "5.1|FL=FL|FR=FR|FC=FC|LFE=LFE"
                  + "|BL=0.7071068*BL+0.7071068*SL|BR=0.7071068*BR+0.7071068*SR,"
                  + "channelsplit=channel_layout=5.1[c0][c1][c2][c3][c4][c5];aevalsrc=")
          .contains(
              ";[c4][c5]join=inputs=2:channel_layout=stereo:map=0.0-FL|1.0-FR[j0];"
                  + "[j0]aformat=sample_fmts=dblp,asplit=12")
          .endsWith(
              TRIMMED_SECOND_STAGE
                  + "[g0];[c0][c1][c2][c3][g0]join=inputs=5:channel_layout=5.1"
                  + ":map=0.0-FL|1.0-FR|2.0-FC|3.0-LFE|4.0-BL|4.1-BR,"
                  + DITHERED_16_BITS);
      assertThat(filter.split(MINUS_1_DBTP_STAGE, -1)).hasSize(3);
    }

    @Test
    @DisplayName(
        "Should mix the back centre into each surround at -3 dB and limit the pair at -3 dBTP when"
            + " 6.1 becomes AAC 5.1")
    void
        shouldMixTheBackCentreIntoEachSurroundAtMinus3DbAndLimitThePairAtMinus3DbtpWhen61BecomesAac51() {
      var cmd = command(encode("aac", "5.1"), source("dts", "6.1"));

      var filter = audioFilter(cmd);
      assertThat(filter)
          .startsWith(
              FLOAT_PAN
                  + "5.1|FL=FL|FR=FR|FC=FC|LFE=LFE|BL=0.7071068*BC+SL|BR=0.7071068*BC+SR,"
                  + "channelsplit=channel_layout=5.1[c0][c1][c2][c3][c4][c5];aevalsrc=")
          .contains(
              ";[c4][c5]join=inputs=2:channel_layout=stereo:map=0.0-FL|1.0-FR[j0];"
                  + "[j0]aformat=sample_fmts=dblp,asplit=12")
          .doesNotContain(MINUS_1_DBFS);
      assertThat(filter.split(MINUS_3_DBTP_STAGE, -1)).hasSize(3);
    }

    @Test
    @DisplayName("Should link the front pair alone when the front-of-centre channels fold into it")
    void shouldLinkTheFrontPairAloneWhenTheFrontOfCentreChannelsFoldIntoIt() {
      var cmd = command(encode("aac", "5.0"), source("flac", "7.0(front)"));

      assertThat(audioFilter(cmd))
          .contains("pan=5.0|FL=FL+0.7071068*FLC|FR=FR+0.7071068*FRC|FC=FC|BL=SL|BR=SR,")
          .contains("[c0][c1]join=inputs=2:channel_layout=stereo:map=0.0-FL|1.0-FR[j0];")
          .contains("[g0][c2][c3][c4]join=inputs=4:channel_layout=5.0");
    }

    @Test
    @DisplayName("Should leave channels out without a limiter or dither when nothing is summed")
    void shouldLeaveChannelsOutWithoutALimiterOrDitherWhenNothingIsSummed() {
      var source = sourceBuilder("flac", "5.1(side)").bitDepth(OptionalInt.of(16)).build();

      var cmd = command(encode("flac", "5.0"), Optional.of(source));

      assertThat(audioFilter(cmd))
          .isEqualTo(
              FLOAT_PAN + "5.0|FL=FL|FR=FR|FC=FC|BL=SL|BR=SR,aresample=osf=s16:" + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName("Should dither the summed pair to 16 bits when a FLAC downmix sums a 16-bit track")
    void shouldDitherTheSummedPairTo16BitsWhenAFlacDownmixSumsA16BitTrack() {
      var source = sourceBuilder("flac", "5.1(side)").bitDepth(OptionalInt.of(16)).build();

      var cmd = command(encode("flac", "stereo"), Optional.of(source));

      assertThat(audioFilter(cmd))
          .endsWith(
              TRIMMED_SECOND_STAGE
                  + "[l0];[l0]"
                  + DITHERED_16_BITS
                  + ",aresample=osf=s16:"
                  + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName(
        "Should dither every channel when a FLAC downmix scales a channel that it does not sum")
    void shouldDitherEveryChannelWhenAFlacDownmixScalesAChannelThatItDoesNotSum() {
      var source = sourceBuilder("flac", "mono").bitDepth(OptionalInt.of(16)).build();

      var cmd = command(encode("flac", "stereo"), Optional.of(source));

      assertThat(audioFilter(cmd))
          .isEqualTo(FLOAT_PAN + "stereo|FL=0.7071068*FC|FR=0.7071068*FC," + DITHERED_16_BITS);
    }

    @Test
    @DisplayName(
        "Should quantize the summed pair with TPDF dither at 24 bits when FLAC writes 24-bit 7.1 as 5.1")
    void shouldQuantizeTheSummedPairWithTpdfDitherAt24BitsWhenFlacWrites24Bit71As51() {
      var cmd = command(encode("flac", "5.1"), Optional.of(hiResSource("7.1", 48_000)));

      assertThat(audioFilter(cmd))
          .endsWith(
              TRIMMED_SECOND_STAGE
                  + "[l0];[l0]"
                  + DITHERED_24_BITS
                  + "[g0];[c0][c1][c2][c3][g0]join=inputs=5:channel_layout=5.1"
                  + ":map=0.0-FL|1.0-FR|2.0-FC|3.0-LFE|4.0-BL|4.1-BR,"
                  + "aresample=osf=s32:"
                  + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName(
        "Should dither every channel at 24 bits when a 24-bit FLAC downmix scales a channel that it does not sum")
    void shouldDitherEveryChannelAt24BitsWhenA24BitFlacDownmixScalesAChannelThatItDoesNotSum() {
      var cmd = command(encode("flac", "stereo"), Optional.of(hiResSource("mono", 48_000)));

      assertThat(audioFilter(cmd))
          .isEqualTo(FLOAT_PAN + "stereo|FL=0.7071068*FC|FR=0.7071068*FC," + DITHERED_24_BITS);
    }

    @Test
    @DisplayName("Should keep every sample when a 24-bit FLAC downmix only leaves channels out")
    void shouldKeepEverySampleWhenA24BitFlacDownmixOnlyLeavesChannelsOut() {
      var cmd = command(encode("flac", "5.0"), Optional.of(hiResSource("5.1(side)", 48_000)));

      assertThat(audioFilter(cmd))
          .isEqualTo(
              FLOAT_PAN + "5.0|FL=FL|FR=FR|FC=FC|BL=SL|BR=SR,aresample=osf=s32:" + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName(
        "Should limit at the named rate and dither every channel when FLAC steps a 96 kHz downmix to 48 kHz")
    void shouldLimitAtTheNamedRateAndDitherEveryChannelWhenFlacStepsA96KhzDownmixTo48Khz() {
      var audio = encodeBuilder("flac", "5.1").outputSampleRate(OptionalInt.of(48_000)).build();

      var cmd = command(audio, Optional.of(hiResSource("7.1", 96_000)));

      assertThat(audioFilter(cmd))
          .startsWith("aresample=osr=48000:" + TUNED_RESAMPLER + "," + FLOAT_PAN)
          .contains(":s=48000,atrim=end_sample=128")
          .doesNotContain("osr=192000", ":s=96000")
          .endsWith(":map=0.0-FL|1.0-FR|2.0-FC|3.0-LFE|4.0-BL|4.1-BR," + DITHERED_24_BITS);
    }

    @Test
    @DisplayName("Should dither every channel when FLAC writes a drop-only downmix of AC-3")
    void shouldDitherEveryChannelWhenFlacWritesADropOnlyDownmixOfAc3() {
      var cmd = command(encode("flac", "5.0"), source("ac3", "5.1(side)"));

      assertThat(audioFilter(cmd))
          .isEqualTo(FLOAT_PAN + "5.0|FL=FL|FR=FR|FC=FC|BL=SL|BR=SR," + DITHERED_16_BITS);
    }

    @Test
    @DisplayName("Should leave the surrounds out without a limiter when AC-3 signals a zero level")
    void shouldLeaveTheSurroundsOutWithoutALimiterWhenAc3SignalsAZeroLevel() {
      var source =
          sourceBuilder("ac3", "quad(side)")
              .loRoLevels(Optional.of(new SourceAudio.LoRoLevels(0.707107, 0.0)))
              .build();

      var cmd = command(encode("aac", "stereo"), Optional.of(source));

      assertThat(audioFilter(cmd))
          .isEqualTo(FLOAT_PAN + "stereo|FL=FL|FR=FR,aresample=" + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName("Should resample before the matrix when a lossy downmix changes the sample rate")
    void shouldResampleBeforeTheMatrixWhenALossyDownmixChangesTheSampleRate() {
      var source = sourceBuilder("flac", "5.1(side)").sampleRate(OptionalInt.of(96_000)).build();

      var cmd = command(encode("aac", "stereo"), Optional.of(source));

      assertThat(audioFilter(cmd))
          .startsWith("aresample=osr=48000:" + TUNED_RESAMPLER + "," + FLOAT_PAN)
          .contains(":s=48000,atrim=end_sample=128")
          .endsWith(TRIMMED_SECOND_STAGE + "[l0];[l0]anull,aresample=" + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName("Should leave the downmix to FFmpeg when the source layout has an unknown channel")
    void shouldLeaveTheDownmixToFfmpegWhenTheSourceLayoutHasAnUnknownChannel() {
      var source =
          SourceAudio.builder()
              .codec("pcm_s24le")
              .channels(3)
              .channelLayout(Optional.of("3 channels (FL+FR+USR63)"))
              .build();

      var cmd = command(encode("aac", "stereo"), Optional.of(source));

      assertThat(audioFilter(cmd)).isEqualTo("aresample=" + TUNED_RESAMPLER);
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
    @DisplayName(
        "Should decode the audio stream that the job names without compression when it is Dolby")
    void shouldDecodeTheAudioStreamThatTheJobNamesWithoutCompressionWhenItIsDolby() {
      var audio = encodeBuilder("aac", "stereo").streamIndex(OptionalInt.of(2)).build();

      var cmd = command(audio, source("eac3", "5.1(side)"));

      assertThat(cmd)
          .containsSubsequence("-drc_scale:2", "0", "-target_level:2", "-31", "-i")
          .containsSequence("-map", "0:2?")
          .doesNotContain("-drc_scale:a:0", "0:a:0");
    }

    @Test
    @DisplayName("Should pass no decoder options when the source is not AC-3 or E-AC-3")
    void shouldPassNoDecoderOptionsWhenTheSourceIsNotAc3OrEac3() {
      var cmd = command(encode("aac", "5.1"), source("dts", "5.1(side)"));

      assertThat(cmd).isNotEmpty().doesNotContain("-drc_scale:a:0", "-target_level:a:0");
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
      "pcm_s16le, s16, 16, s16p",
      "pcm_f32le, flt, 32, s32p:output_sample_bits=24:dither_method=triangular",
      "pcm_s32le, s32, 32, s32p:output_sample_bits=24:dither_method=triangular",
      "truehd, s32, 24, s32p",
      "flac, s16, 16, s16p"
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
    @CsvSource({"pcm_s16be, s16", "pcm_s24le, s32", "flac, s32"})
    @DisplayName("Should follow the codec name when the probe gives a lossless track no depth")
    void shouldFollowTheCodecNameWhenTheProbeGivesALosslessTrackNoDepth(
        String codec, String written) {
      var cmd = command(encode("flac", "stereo"), source(codec, "stereo"));

      assertThat(audioFilter(cmd)).isEqualTo("aresample=osf=" + written + ":" + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName(
        "Should resample with the tuned settings and dither to 24 bits when the job names 48 kHz for 96 kHz FLAC")
    void shouldResampleWithTheTunedSettingsAndDitherTo24BitsWhenTheJobNames48KhzFor96KhzFlac() {
      var audio = encodeBuilder("flac", "stereo").outputSampleRate(OptionalInt.of(48_000)).build();

      var cmd = command(audio, Optional.of(hiResFlac(96_000, 24)));

      assertThat(audioFilter(cmd))
          .isEqualTo(
              "aresample=osr=48000:osf=s32:output_sample_bits=24:dither_method=triangular:"
                  + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName("Should keep every sample when the job names the source's rate and depth")
    void shouldKeepEverySampleWhenTheJobNamesTheSourcesRateAndDepth() {
      var audio =
          encodeBuilder("alac", "stereo")
              .outputSampleRate(OptionalInt.of(96_000))
              .outputBitDepth(OptionalInt.of(24))
              .build();

      var cmd = command(audio, Optional.of(hiResFlac(96_000, 24)));

      assertThat(audioFilter(cmd)).isEqualTo("aresample=osf=s32p:" + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName("Should write 16 bits with TPDF dither when the job names 16 bits for 24-bit FLAC")
    void shouldWrite16BitsWithTpdfDitherWhenTheJobNames16BitsFor24BitFlac() {
      var audio = encodeBuilder("flac", "stereo").outputBitDepth(OptionalInt.of(16)).build();

      var cmd = command(audio, Optional.of(hiResFlac(48_000, 24)));

      assertThat(audioFilter(cmd))
          .isEqualTo("aresample=osf=s16:dither_method=triangular:" + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName(
        "Should resample in floating point with TPDF dither when the job resamples 16-bit FLAC")
    void shouldResampleInFloatingPointWithTpdfDitherWhenTheJobResamples16BitFlac() {
      var audio = encodeBuilder("flac", "stereo").outputSampleRate(OptionalInt.of(48_000)).build();

      var cmd = command(audio, Optional.of(hiResFlac(44_100, 16)));

      assertThat(audioFilter(cmd))
          .isEqualTo(
              "aresample=osr=48000:internal_sample_fmt=fltp:osf=s16:dither_method=triangular:"
                  + TUNED_RESAMPLER);
    }

    @Test
    @DisplayName("Should write the named rate when a lossy encode names one")
    void shouldWriteTheNamedRateWhenALossyEncodeNamesOne() {
      var audio = encodeBuilder("aac", "stereo").outputSampleRate(OptionalInt.of(44_100)).build();

      var cmd = command(audio, Optional.of(hiResFlac(88_200, 24)));

      assertThat(audioFilter(cmd)).isEqualTo("aresample=osr=44100:" + TUNED_RESAMPLER);
    }

    @ParameterizedTest(name = "{0} Hz to {1} Hz")
    @CsvSource({"48000, 0, 2400", "22050, 0, 1102", "96000, 48000, 2400", "8000, 0, 400"})
    @DisplayName("Should give FLAC frames of the written sample rate divided by 20")
    void shouldGiveFlacFramesOfTheWrittenSampleRateDividedBy20(
        int sourceRate, int namedRate, String frameSize) {
      var audio = encodeBuilder("flac", "stereo");
      if (namedRate > 0) {
        audio.outputSampleRate(OptionalInt.of(namedRate));
      }

      var cmd = command(audio.build(), Optional.of(hiResFlac(sourceRate, 24)));

      assertThat(cmd).containsSubsequence("-frame_size:a", frameSize);
    }

    @Test
    @DisplayName("Should leave the frame size to the encoder when ALAC encodes")
    void shouldLeaveTheFrameSizeToTheEncoderWhenAlacEncodes() {
      var cmd = command(encode("alac", "stereo"), Optional.of(hiResFlac(48_000, 24)));

      assertThat(cmd).isNotEmpty().doesNotContain("-frame_size:a");
    }

    @Test
    @DisplayName("Should leave the frame size to the encoder when the probe gives FLAC no rate")
    void shouldLeaveTheFrameSizeToTheEncoderWhenTheProbeGivesFlacNoRate() {
      var source = sourceBuilder("flac", "stereo").sampleRate(OptionalInt.empty()).build();

      var cmd = command(encode("flac", "stereo"), Optional.of(source));

      assertThat(cmd).isNotEmpty().doesNotContain("-frame_size:a");
    }

    @Test
    @DisplayName("Should give FLAC no bitrate when the job carries one")
    void shouldGiveFlacNoBitrateWhenTheJobCarriesOne() {
      var cmd = command(encode("flac", "stereo"), source("flac", "stereo"));

      assertThat(cmd).isNotEmpty().doesNotContain("-b:a");
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

  @Nested
  @DisplayName("Early End")
  class EarlyEndTests {

    @ParameterizedTest(name = "{0} {1} from {2} {3}")
    @CsvSource({
      "aac, stereo, dts, 5.1(side)",
      "flac, 5.0, flac, 5.0(side)",
      "eac3, 5.1, truehd, 5.1(side)",
      "libopus, 7.0, truehd, 7.0"
    })
    @DisplayName(
        "Should pad after the named layout's filters in its one filter chain when an encode in a"
            + " named layout pads its audio")
    void shouldPadAfterTheNamedLayoutsFiltersInItsOneFilterChainWhenANamedLayoutEncodePads(
        String codec, String layout, String sourceCodec, String sourceLayout) {
      var unpadded = command(encode(codec, layout), source(sourceCodec, sourceLayout));
      var padded =
          command(
              encodeBuilder(codec, layout).padsWithSilenceToAdvertisedEnd(true).build(),
              source(sourceCodec, sourceLayout),
              Optional.of(Duration.ofSeconds(66)));

      assertThat(audioFilter(padded))
          .isEqualTo(
              audioFilter(unpadded)
                  + ",apad,asetpts='if(isnan(PTS),0/TB+N/SR/TB,PTS)',atrim=start=0:end=67");
      assertThat(padded).containsSubsequence("-ch_layout", layout);
    }

    // A replacement from segment 10 starts at 60 s and seeks one 6 s period earlier, so the
    // encoder's input starts its lead plus the whole frames that fit with it in 6 s before 60 s.
    @ParameterizedTest(name = "{0} {1}")
    @CsvSource(
        delimiter = ';',
        value = {
          "aac; stereo; 1024; 0",
          "eac3; 5.1; 1536; 1280",
          "opus; 5.1; SR/50; SR*27/2000",
          "flac; stereo; 2400; 0"
        })
    @DisplayName(
        "Should start a frame of the encoder that the named layout's options run on the first"
            + " segment's start, after the layout's filters, when an encoded replacement holds the"
            + " last frame")
    void shouldStartAFrameOfTheNamedLayoutsEncoderOnTheFirstSegmentWhenAReplacementHolds(
        String codec, String layout, String samplesPerFrame, String lead) {
      var audio = encode(codec, layout);
      var held = VideoDecisionFixtures.encodeBuilder().holdsLastFrameToAdvertisedEnd(true).build();

      var cmd = encodeCommand(audio, held);

      var wholeFrames = "floor(6*SR/(" + samplesPerFrame + "))";
      if (!lead.equals("0")) {
        wholeFrames = "max(0,floor((6*SR-(" + lead + "))/(" + samplesPerFrame + ")))";
      }

      var shift = "round((" + lead + "+" + wholeFrames + "*(" + samplesPerFrame + "))/SR/TB)";
      assertThat(audioFilter(cmd))
          .isEqualTo(
              audioFilter(command(audio, source("dts", "5.1(side)")))
                  + ",asetpts='PTS+"
                  + shift
                  + "',atrim=start=60,asetpts='PTS-"
                  + shift
                  + "'");
    }

    private List<String> encodeCommand(AudioDecision audio, VideoDecision video) {
      var decision =
          TranscodeDecision.builder()
              .transcodeMode(TranscodeMode.FULL_TRANSCODE)
              .videoCodecFamily("h264")
              .videoDecision(video)
              .audioDecision(audio)
              .subtitleDecision(SubtitleDecisions.EXCLUDED)
              .build();
      var request =
          request(decision)
              .sourceAudio(source("dts", "5.1(side)"))
              .startSequenceNumber(10)
              .advertisedEnd(Optional.of(Duration.ofSeconds(66)))
              .build();
      var job = TranscodeJob.builder().request(request).videoEncoder("libx264").build();
      return ((FfmpegPipeline.SingleProcess) builder.buildPipeline(job)).command();
    }
  }

  @ParameterizedTest(name = "stream {0}")
  @CsvSource({"'', a:0, 1:a:0", "2, 2, 1:2?"})
  @DisplayName(
      "Should encode the named layout of the job's audio stream from the source input of the"
          + " encode, and none in the source copy, when the job moves Dolby Vision RPUs")
  void shouldEncodeTheNamedLayoutOfTheJobsAudioStreamFromTheEncodesSourceInputWhenItMovesRpus(
      String streamIndex, String specifier, String map) {
    var audio =
        encodeBuilder("opus", "5.1")
            .streamIndex(
                streamIndex.isEmpty()
                    ? OptionalInt.empty()
                    : OptionalInt.of(Integer.parseInt(streamIndex)))
            .build();
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
            .audioDecision(audio)
            .subtitleDecision(SubtitleDecisions.EXCLUDED)
            .build();
    var request = request(decision).sourceAudio(source("ac3", "5.1(side)")).build();

    var pipeline =
        (FfmpegPipeline.RpuMovingPipe)
            builder.buildPipeline(
                TranscodeJob.builder().request(request).videoEncoder("libx265").build());

    assertThat(pipeline.copyCommand()).doesNotContain("-drc_scale:" + specifier, "-c:a");
    assertThat(pipeline.encodeCommand())
        .containsSubsequence("pipe:0", "-drc_scale:" + specifier, "0", "-i", "/media/movie.mkv")
        .containsSequence("-map", map)
        .containsSubsequence("-c:a", "libopus", "-af")
        .containsSubsequence("-ch_layout", "5.1");
  }

  private List<String> command(AudioDecision audio, Optional<SourceAudio> source) {
    return command(audio, source, Optional.empty());
  }

  private List<String> command(
      AudioDecision audio, Optional<SourceAudio> source, Optional<Duration> advertisedEnd) {
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
            .request(request(decision).sourceAudio(source).advertisedEnd(advertisedEnd).build())
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
    return encodeBuilder(codec, layout).build();
  }

  private static AudioDecision.AudioDecisionBuilder encodeBuilder(String codec, String layout) {
    var named = SpeakerLayout.named(layout);
    return AudioDecision.builder()
        .mode(AudioMode.TRANSCODE)
        .codec(codec)
        .channels(named.channelCount())
        .bitrate(448_000)
        .channelLayout(Optional.of(named));
  }

  private static SourceAudio hiResSource(String layout, int sampleRate) {
    return sourceBuilder("flac", layout)
        .sampleFormat(Optional.of("s32"))
        .bitDepth(OptionalInt.of(24))
        .sampleRate(OptionalInt.of(sampleRate))
        .build();
  }

  private static SourceAudio hiResFlac(int sampleRate, int bitDepth) {
    return sourceBuilder("flac", "stereo")
        .sampleFormat(Optional.of(bitDepth == 16 ? "s16" : "s32"))
        .bitDepth(OptionalInt.of(bitDepth))
        .sampleRate(OptionalInt.of(sampleRate))
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
