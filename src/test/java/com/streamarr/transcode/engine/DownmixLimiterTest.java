package com.streamarr.transcode.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Downmix Limiter Tests")
class DownmixLimiterTest {

  private static final String LOSSLESS_LIMITER =
      "alimiter=limit=0.891251:attack=5:release=50:level=0:latency=1";
  private static final Pattern IMPULSE_RESPONSE =
      Pattern.compile("aevalsrc=exprs='([^']*)':s=(\\d+),atrim=end_sample=(\\d+)");
  private static final Pattern TERM =
      Pattern.compile("(-?[0-9.]+(?:E-?[0-9]+)?)\\*eq\\(n,(\\d+)\\)");
  private static final Pattern FILTERED_COPY = Pattern.compile("\\[([^\\]]+)\\]\\[[^\\]]+\\]afir=");
  // Linux refuses a single argument longer than 128 KiB (MAX_ARG_STRLEN).
  private static final int LONGEST_ARGUMENT = 128 * 1024;

  @Test
  @DisplayName("Should limit the group in two stages without a resampler when FLAC writes stereo")
  void shouldLimitTheGroupInTwoStagesWithoutAResamplerWhenFlacWritesStereo() {
    var filter = lossless("5.1", "stereo", 48_000);

    assertThat(occurrences(filter, LOSSLESS_LIMITER)).isEqualTo(2);
    assertThat(occurrences(filter, "afir=")).isEqualTo(11 + 4);
    assertThat(occurrences(filter, "atrim=start_sample=64,asetpts=round(PTS-64/(SR*TB))"))
        .isEqualTo(1);
    assertThat(occurrences(filter, "atrim=start_sample=6,asetpts=round(PTS-6/(SR*TB))"))
        .isEqualTo(1);
    assertThat(filter).contains("adelay=64S:all=1", "adelay=6S:all=1", "pan=stereo|c0=FL|c1=FR");
    assertThat(filter).doesNotContain("aresample");
  }

  @Test
  @DisplayName(
      "Should pad each copy's input before its filter so the filter reaches the last samples")
  void shouldPadEachCopysInputBeforeItsFilterSoTheFilterReachesTheLastSamples() {
    var filter = lossless("5.1", "stereo", 48_000);

    var copies = FILTERED_COPY.matcher(filter).results().map(match -> match.group(1)).toList();

    assertThat(copies).hasSize(15);
    assertThat(copies.stream().filter(label -> filter.contains("apad=pad_len=64[" + label + "]")))
        .hasSize(11);
    assertThat(copies.stream().filter(label -> filter.contains("apad=pad_len=6[" + label + "]")))
        .hasSize(4);
  }

  @Test
  @DisplayName("Should give each filter the measured vector at the group's sample rate")
  void shouldGiveEachFilterTheMeasuredVectorAtTheGroupsSampleRate() {
    var filter = lossless("5.1", "mono", 44_100);

    var responses = IMPULSE_RESPONSE.matcher(filter).results().toList();

    var expected = new ArrayList<>(DownmixLimiterPhases.firstStage());
    expected.addAll(DownmixLimiterPhases.secondStage());
    assertThat(responses).hasSize(expected.size());
    for (var vector = 0; vector < expected.size(); vector++) {
      var response = responses.get(vector);
      assertThat(response.group(2)).isEqualTo("44100");
      var taps = new double[Integer.parseInt(response.group(3))];
      TERM.matcher(response.group(1))
          .results()
          .forEach(
              term -> taps[Integer.parseInt(term.group(2))] = Double.parseDouble(term.group(1)));

      assertThat(taps).as("vector %d", vector).containsExactly(expected.get(vector));
    }
  }

  @Test
  @DisplayName("Should take the one channel back by name when the group is mono")
  void shouldTakeTheOneChannelBackByNameWhenTheGroupIsMono() {
    var filter = lossless("5.1", "mono", 48_000);

    assertThat(occurrences(filter, "pan=mono|c0=FL")).isEqualTo(2);
    assertThat(filter).contains("channelmap=map=0-TC:channel_layout=TC");
  }

  @Test
  @DisplayName("Should share one impulse response per vector across groups when many are summed")
  void shouldShareOneImpulseResponsePerVectorAcrossGroupsWhenManyAreSummed() {
    var filter = lossless("22.2", "7.1", 48_000);

    var groups = occurrences(filter, LOSSLESS_LIMITER) / 2;
    assertThat(groups).isGreaterThan(2);
    assertThat(IMPULSE_RESPONSE.matcher(filter).results()).hasSize(15);
    assertThat(occurrences(filter, ",asplit=" + groups + "[ir")).isEqualTo(15);
    assertThat(filter.getBytes(StandardCharsets.UTF_8).length).isLessThan(LONGEST_ARGUMENT);
  }

  private static String lossless(String source, String output, int sampleRate) {
    var outputLayout = SpeakerLayout.named(output);
    return DownmixFilter.of(
        outputLayout,
        DownmixMatrix.of(SpeakerLayout.named(source), outputLayout, Optional.empty()),
        DownmixLimiter.forLossless(OptionalInt.of(sampleRate)),
        Optional.empty());
  }

  private static int occurrences(String text, String part) {
    return List.of(text.split(Pattern.quote(part), -1)).size() - 1;
  }
}
