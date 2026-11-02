package com.streamarr.transcode.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * ADR 0050's downmix filter: floating point, the named matrix in FFmpeg's pan filter, and a limiter
 * on only the output channels that sum two or more source channels. The limiter links the two
 * channels of a left-right pair and limits every other summed channel on its own; channelsplit
 * takes the summed channels out and join puts them back, so every other channel passes through
 * unchanged.
 */
final class DownmixFilter {

  private DownmixFilter() {}

  /**
   * The downmix filter. {@code quantizer}, when present, follows the limiter on each summed group,
   * so that the channels that join takes unchanged are not dithered.
   */
  static String of(
      SpeakerLayout output,
      Map<Speaker, Map<Speaker, Double>> matrix,
      DownmixLimiter limiter,
      Optional<String> quantizer) {
    var mix = "aformat=sample_fmts=fltp," + pan(output, matrix);
    var groups = summedGroups(output, matrix);
    if (groups.isEmpty()) {
      return mix;
    }

    var limit = limiter.filter() + quantizer.map(filter -> "," + filter).orElse("");
    if (groups.size() == 1 && groups.getFirst().size() == output.channelCount()) {
      return mix + "," + limit;
    }

    return mix + "," + splitAndLimit(output, groups, limit);
  }

  /**
   * Whether every output channel either holds one source channel at its own level, is silent, or
   * sums two or more source channels.
   */
  static boolean onlySummedChannelsChange(Map<Speaker, Map<Speaker, Double>> matrix) {
    return matrix.values().stream()
        .allMatch(
            row -> row.size() != 1 || Double.compare(row.values().iterator().next(), 1.0) == 0);
  }

  private static String pan(SpeakerLayout output, Map<Speaker, Map<Speaker, Double>> matrix) {
    var any = matrix.values().stream().flatMap(row -> row.keySet().stream()).findFirst();
    var channels = new ArrayList<String>();
    matrix.forEach((speaker, row) -> channels.add(speaker + "=" + terms(row, any)));
    return "pan=" + output.name() + "|" + String.join("|", channels);
  }

  // An output channel that no source channel reaches is silent.
  private static String terms(Map<Speaker, Double> row, Optional<Speaker> any) {
    if (row.isEmpty()) {
      return "0*" + any.orElseThrow();
    }

    return row.entrySet().stream()
        .map(entry -> term(entry.getKey(), entry.getValue()))
        .collect(Collectors.joining("+"));
  }

  private static String term(Speaker speaker, double gain) {
    if (Double.compare(gain, 1.0) == 0) {
      return speaker.name();
    }

    return String.format(Locale.ROOT, "%.7f*%s", gain, speaker);
  }

  // The summed channels, as linked pairs and single channels, in the output layout's order.
  private static List<List<Speaker>> summedGroups(
      SpeakerLayout output, Map<Speaker, Map<Speaker, Double>> matrix) {
    var summed = output.speakers().stream().filter(s -> matrix.get(s).size() > 1).toList();
    var groups = new ArrayList<List<Speaker>>();
    for (var speaker : summed) {
      var partner = rightOf(speaker).filter(summed::contains);
      if (partner.isPresent()) {
        groups.add(List.of(speaker, partner.orElseThrow()));
        continue;
      }

      if (leftOf(speaker).filter(summed::contains).isEmpty()) {
        groups.add(List.of(speaker));
      }
    }

    return groups;
  }

  private static String splitAndLimit(
      SpeakerLayout output, List<List<Speaker>> groups, String limit) {
    var speakers = output.speakers();
    var graph = new StringBuilder("channelsplit=channel_layout=").append(output.name());
    for (var index = 0; index < speakers.size(); index++) {
      graph.append("[c").append(index).append(']');
    }

    var joined = new ArrayList<String>();
    var map = new ArrayList<String>();
    for (var group = 0; group < groups.size(); group++) {
      var channels = groups.get(group);
      graph.append(';');
      channels.forEach(s -> graph.append("[c").append(speakers.indexOf(s)).append(']'));
      if (channels.size() == 2) {
        graph.append("join=inputs=2:channel_layout=stereo:map=0.0-FL|1.0-FR,");
      }

      graph.append(limit).append("[g").append(group).append(']');
    }

    for (var speaker : speakers) {
      var group = groupOf(speaker, groups);
      if (group.isEmpty()) {
        map.add(joined.size() + ".0-" + speaker);
        joined.add("[c" + speakers.indexOf(speaker) + "]");
        continue;
      }

      var channels = group.orElseThrow();
      if (channels.getFirst() != speaker) {
        continue;
      }

      var input = joined.size();
      joined.add("[g" + groups.indexOf(channels) + "]");
      for (var channel = 0; channel < channels.size(); channel++) {
        map.add(input + "." + channel + "-" + channels.get(channel));
      }
    }

    graph.append(';').append(String.join("", joined));
    graph
        .append("join=inputs=")
        .append(joined.size())
        .append(":channel_layout=")
        .append(output.name())
        .append(":map=")
        .append(String.join("|", map));
    return graph.toString();
  }

  private static Optional<List<Speaker>> groupOf(Speaker speaker, List<List<Speaker>> groups) {
    return groups.stream().filter(group -> group.contains(speaker)).findFirst();
  }

  private static Optional<Speaker> rightOf(Speaker speaker) {
    return switch (speaker) {
      case FL -> Optional.of(Speaker.FR);
      case BL -> Optional.of(Speaker.BR);
      case FLC -> Optional.of(Speaker.FRC);
      case SL -> Optional.of(Speaker.SR);
      case TFL -> Optional.of(Speaker.TFR);
      case TBL -> Optional.of(Speaker.TBR);
      case DL -> Optional.of(Speaker.DR);
      case WL -> Optional.of(Speaker.WR);
      case SDL -> Optional.of(Speaker.SDR);
      case TSL -> Optional.of(Speaker.TSR);
      case BFL -> Optional.of(Speaker.BFR);
      case SSL -> Optional.of(Speaker.SSR);
      case TTL -> Optional.of(Speaker.TTR);
      case BIL -> Optional.of(Speaker.BIR);
      default -> Optional.empty();
    };
  }

  private static Optional<Speaker> leftOf(Speaker speaker) {
    return Arrays.stream(Speaker.values())
        .filter(left -> rightOf(left).filter(speaker::equals).isPresent())
        .findFirst();
  }
}
