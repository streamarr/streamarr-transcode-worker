package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.Speaker.BL;
import static com.streamarr.transcode.engine.Speaker.BR;
import static com.streamarr.transcode.engine.Speaker.SL;
import static com.streamarr.transcode.engine.Speaker.SR;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * How an encode turns the source's channels into the named output layout. A side pair and a back
 * pair count as one surround pair (ADR 0050), so an output that holds every source channel under
 * the same name or the other pair's name keeps every sample: a rename maps the channels with
 * channelmap, and padding adds silent channels with pan (ADR 0043).
 */
sealed interface ChannelStage {

  /** The filter that the stage runs; empty when FFmpeg reads the source in the output layout. */
  Optional<String> filter();

  static ChannelStage of(SourceAudio source, SpeakerLayout output) {
    var decoded = source.speakerLayout();
    if (decoded.isEmpty()) {
      return new Remix();
    }

    var layout = decoded.orElseThrow();
    var sources = new LinkedHashMap<Speaker, Optional<Speaker>>();
    output.speakers().forEach(speaker -> sources.put(speaker, sourceOf(speaker, layout, output)));
    var used = new HashSet<Speaker>();
    sources.values().forEach(kept -> kept.ifPresent(used::add));
    if (!used.containsAll(layout.speakers())) {
      return new Remix();
    }

    if (sources.values().stream().anyMatch(Optional::isEmpty)) {
      return new Pad(output, sources);
    }

    if (output.speakers().equals(layout.speakers())) {
      return new Unchanged();
    }

    return new Rename(output, sources);
  }

  // The source channel that the output channel holds unchanged: the same channel, or the other
  // surround pair's channel on its side when the source has only that pair and the output lacks it.
  private static Optional<Speaker> sourceOf(
      Speaker speaker, SpeakerLayout source, SpeakerLayout output) {
    if (source.has(speaker)) {
      return Optional.of(speaker);
    }

    return surroundPartner(speaker).filter(partner -> source.has(partner) && !output.has(partner));
  }

  private static Optional<Speaker> surroundPartner(Speaker speaker) {
    return switch (speaker) {
      case BL -> Optional.of(SL);
      case BR -> Optional.of(SR);
      case SL -> Optional.of(BL);
      case SR -> Optional.of(BR);
      default -> Optional.empty();
    };
  }

  /** The source already has the output layout. */
  record Unchanged() implements ChannelStage {
    @Override
    public Optional<String> filter() {
      return Optional.empty();
    }
  }

  /**
   * Every output channel holds one source channel unchanged. FFmpeg's pan filter with every gain 1
   * copies channels by position, which moves a 6.1 source's back centre into the back left of
   * 6.1(back), so the rename uses channelmap.
   */
  record Rename(SpeakerLayout output, Map<Speaker, Optional<Speaker>> sources)
      implements ChannelStage {
    @Override
    public Optional<String> filter() {
      var map =
          sources.entrySet().stream()
              .map(entry -> entry.getValue().orElseThrow() + "-" + entry.getKey())
              .collect(Collectors.joining("|"));
      return Optional.of("channelmap=map=" + map + ":channel_layout=" + output.name());
    }
  }

  /** The output holds every source channel unchanged, and silence in the channels it adds. */
  record Pad(SpeakerLayout output, Map<Speaker, Optional<Speaker>> sources)
      implements ChannelStage {
    @Override
    public Optional<String> filter() {
      var anySource = sources.values().stream().flatMap(Optional::stream).findFirst().orElseThrow();
      var channels = new ArrayList<String>();
      sources.forEach(
          (speaker, source) ->
              channels.add(speaker + "=" + source.map(Speaker::name).orElse("0*" + anySource)));
      return Optional.of("pan=" + output.name() + "|" + String.join("|", channels));
    }
  }

  /** The output lacks a source channel, so the encode mixes or leaves out source channels. */
  record Remix() implements ChannelStage {
    @Override
    public Optional<String> filter() {
      return Optional.empty();
    }
  }
}
