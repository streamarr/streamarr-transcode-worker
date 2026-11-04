package com.streamarr.transcode.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import lombok.NonNull;

/**
 * The true-peak ceiling that ADR 0050's limiter keeps a downmix's summed channels below: −1 dBTP
 * for FLAC and ALAC, and −3 dBTP for every lossy downmix, whose coding can raise the decoded peaks.
 * Each limiter uses 5 ms attack and 50 ms release settings, adds no make-up gain and removes its
 * own delay.
 */
sealed interface DownmixLimiter {

  String LIMITER_TIMING = ":attack=5:release=50:level=0:latency=1";
  String MINUS_1_DBFS = "0.891251";
  String MINUS_3_DBFS = "0.707946";

  /**
   * The filter chains whose labelled outputs the limiters of a downmix with this many limited
   * groups read.
   */
  Optional<String> sources(int groups);

  /**
   * The limiter of one group of one or two channels, from the labelled input to the labelled
   * output. Each group of a downmix has its own index, which keeps its labels unique.
   */
  String limit(int group, int channels, String input, String output);

  static DownmixLimiter forLossy(OptionalInt sampleRate) {
    return new TruePeak(MINUS_3_DBFS, sampleRate);
  }

  static DownmixLimiter forLossless(OptionalInt sampleRate) {
    return new TruePeak(MINUS_1_DBFS, sampleRate);
  }

  /**
   * Two limiters in series. Each feeds alimiter the group beside short-FIR copies of it at the
   * group's own sample rate; each copy reads the waveform at one instant between a sample and the
   * next, so alimiter holds the ceiling there too, and no resampler touches the group itself. The
   * first stage's copies are seven Kaiser-windowed sinc phases and the four phases of the BS.1770-5
   * Annex 2 filter; the second stage reads the first stage's output through the four Annex 2 phases
   * alone. One stage is not enough: alimiter's gain moves across the twelve samples that the Annex
   * 2 filter spans, so the filter does not read what the limiter held, and the second stage turns
   * that error into a second-order one.
   */
  record TruePeak(@NonNull String limit, @NonNull OptionalInt sampleRate)
      implements DownmixLimiter {

    private static final String FILTER =
        "afir=gtype=none:irnorm=-1:irgain=1:irfmt=mono:precision=double:minp=128:maxp=128";

    // amerge orders inputs with disjoint layouts by channel order, so naming the group FL or FL+FR
    // and each copy after it in channel order keeps the group first.
    private static final List<String> ONE_CHANNEL =
        List.of("FL", "FR", "FC", "LFE", "BL", "BR", "FLC", "FRC", "BC", "SL", "SR", "TC");
    private static final List<String> TWO_CHANNELS =
        List.of(
            "FL+FR", "FC+LFE", "BL+BR", "FLC+FRC", "SL+SR", "TFL+TFR", "TBL+TBR", "DL+DR", "WL+WR",
            "SDL+SDR", "TSL+TSR", "BFL+BFR");

    @Override
    public Optional<String> sources(int groups) {
      var chains = new ArrayList<String>();
      var first = DownmixLimiterPhases.firstStage();
      IntStream.range(0, first.size())
          .forEach(vector -> chains.add(source(first.get(vector), "ir1v" + vector, groups)));
      var second = DownmixLimiterPhases.secondStage();
      IntStream.range(0, second.size())
          .forEach(vector -> chains.add(source(second.get(vector), "ir2v" + vector, groups)));
      return Optional.of(String.join(";", chains));
    }

    @Override
    public String limit(int group, int channels, String input, String output) {
      var between = "t" + group + "m";
      var first =
          new Stage(
              "t" + group + "a",
              DownmixLimiterPhases.FIRST_STAGE_DELAY,
              labels("ir1v", DownmixLimiterPhases.firstStage().size(), group));
      var second =
          new Stage(
              "t" + group + "b",
              DownmixLimiterPhases.SECOND_STAGE_DELAY,
              labels("ir2v", DownmixLimiterPhases.secondStage().size(), group));
      return stage(first, channels, input, between)
          + ";"
          + stage(second, channels, between, output);
    }

    private static String alimiter(String limit) {
      return "alimiter=limit=" + limit + LIMITER_TIMING;
    }

    private static List<String> labels(String vector, int vectors, int group) {
      return IntStream.range(0, vectors).mapToObj(index -> vector + index + "g" + group).toList();
    }

    private record Stage(String tag, int delay, List<String> responses) {

      String label(String name, int copy) {
        return "[" + tag + name + copy + "]";
      }
    }

    // The group is delayed by the filters' integer delay, so that each copy lines up with it. Each
    // copy's input is padded by that delay, so that its filter reaches the group's last samples,
    // and the delay is trimmed after the limiter, so that the group keeps its sample count and its
    // timestamps.
    private String stage(Stage stage, int channels, String input, String output) {
      var copies = stage.responses().size();
      var graph = new StringBuilder();
      graph
          .append('[')
          .append(input)
          .append("]aformat=sample_fmts=dblp,asplit=")
          .append(copies + 1);
      IntStream.rangeClosed(0, copies).forEach(copy -> graph.append(stage.label("s", copy)));
      graph.append(';').append(stage.label("s", 0));
      graph.append("adelay=").append(stage.delay()).append("S:all=1,");
      graph.append(channelmap(channels, 0)).append(stage.label("x", 0));
      for (var copy = 1; copy <= copies; copy++) {
        graph.append(';').append(stage.label("s", copy));
        graph.append("apad=pad_len=").append(stage.delay()).append(stage.label("p", copy));
        graph.append(';').append(stage.label("p", copy));
        graph.append('[').append(stage.responses().get(copy - 1)).append(']');
        graph.append(FILTER).append(',').append(channelmap(channels, copy));
        graph.append(stage.label("x", copy));
      }

      // adelay can stamp its leading silence before the first timestamp of its input reaches it.
      // amerge takes each frame's timestamp from its first input and orders the channels by
      // name, so the delayed group joins last and still comes out first.
      graph.append(';');
      IntStream.rangeClosed(1, copies).forEach(copy -> graph.append(stage.label("x", copy)));
      graph.append(stage.label("x", 0));
      graph.append("amerge=inputs=").append(copies + 1).append(',').append(alimiter(limit));
      graph.append(',').append(channels == 1 ? "pan=mono|c0=FL" : "pan=stereo|c0=FL|c1=FR");
      graph.append(",atrim=start_sample=").append(stage.delay());
      graph.append(",asetpts=round(PTS-").append(stage.delay()).append("/(SR*TB))");
      return graph.append('[').append(output).append(']').toString();
    }

    private static String channelmap(int channels, int copy) {
      if (channels == 1) {
        var name = ONE_CHANNEL.get(copy);
        return "channelmap=map=0-" + name + ":channel_layout=" + name;
      }

      var name = TWO_CHANNELS.get(copy);
      var pair = name.split("\\+");
      return "channelmap=map=0-" + pair[0] + "|1-" + pair[1] + ":channel_layout=" + name;
    }

    // Each vector as an impulse response at the group's sample rate. aevalsrc evaluates one term
    // per nonzero tap, written in the shortest decimal form that parses back to the same value.
    private String source(double[] taps, String label, int groups) {
      var terms =
          IntStream.range(0, taps.length)
              .filter(tap -> taps[tap] != 0.0)
              .mapToObj(tap -> taps[tap] + "*eq(n," + tap + ")")
              .toList();
      var chain =
          "aevalsrc=exprs='" + sum(terms) + "':s=" + rate() + ",atrim=end_sample=" + taps.length;
      var outputs =
          IntStream.range(0, groups)
              .mapToObj(group -> "[" + label + "g" + group + "]")
              .collect(Collectors.joining());
      return groups == 1 ? chain + outputs : chain + ",asplit=" + groups + outputs;
    }

    // The copies' impulse responses run at the group's rate, which FFmpeg would otherwise
    // resample. A downmix that sums nothing builds no limiter, so it needs no rate.
    private int rate() {
      return sampleRate.orElseThrow(
          () -> new IllegalStateException("A true-peak limiter needs the source sample rate"));
    }

    // FFmpeg's expression parser fails with ENOMEM on a flat sum of about a hundred terms, so the
    // terms are summed as a balanced tree. Each sample holds one nonzero term, so the order of the
    // additions cannot change its value.
    private static String sum(List<String> terms) {
      if (terms.size() == 1) {
        return terms.getFirst();
      }

      var half = terms.size() / 2;
      return "("
          + sum(terms.subList(0, half))
          + ")+("
          + sum(terms.subList(half, terms.size()))
          + ")";
    }
  }
}
