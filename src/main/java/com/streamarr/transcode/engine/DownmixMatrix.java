package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.Speaker.BL;
import static com.streamarr.transcode.engine.Speaker.BR;
import static com.streamarr.transcode.engine.Speaker.FC;
import static com.streamarr.transcode.engine.Speaker.FL;
import static com.streamarr.transcode.engine.Speaker.FR;
import static com.streamarr.transcode.engine.Speaker.LFE;
import static com.streamarr.transcode.engine.Speaker.SL;
import static com.streamarr.transcode.engine.Speaker.SR;

import com.streamarr.transcode.engine.SourceAudio.LoRoLevels;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * ADR 0050's downmix matrix: ITU-R BS.775's equations at ATSC A/52's default levels, or, in a
 * stereo or mono downmix, at the Lo/Ro levels that an AC-3 or E-AC-3 track signals. The matrix is
 * not normalized.
 */
final class DownmixMatrix {

  static final double MINUS_3_DB = Math.sqrt(0.5);
  private static final double CENTRE_LINE = 0.5;

  private DownmixMatrix() {}

  /** Each output channel's gain on each source channel, in the output layout's order. */
  static Map<Speaker, Map<Speaker, Double>> of(
      SpeakerLayout source, SpeakerLayout output, Optional<LoRoLevels> levels) {
    if (output.channelCount() == 1) {
      return mono(source, levels);
    }

    var gains = new Gains(source, output, output.channelCount() <= 2 ? levels : Optional.empty());
    source.speakers().forEach(gains::add);
    return gains.matrix;
  }

  // Mono is 0.7071 × (left + right) of the stereo matrix.
  private static Map<Speaker, Map<Speaker, Double>> mono(
      SpeakerLayout source, Optional<LoRoLevels> levels) {
    var row = new EnumMap<Speaker, Double>(Speaker.class);
    of(source, SpeakerLayout.named("stereo"), levels)
        .values()
        .forEach(
            stereo ->
                stereo.forEach(
                    (speaker, gain) -> row.merge(speaker, MINUS_3_DB * gain, Double::sum)));
    return Map.of(FC, row);
  }

  private static final class Gains {

    private final SpeakerLayout source;
    private final SpeakerLayout output;
    private final double centreLevel;
    private final double surroundLevel;
    private final Map<Speaker, Map<Speaker, Double>> matrix = new LinkedHashMap<>();

    Gains(SpeakerLayout source, SpeakerLayout output, Optional<LoRoLevels> levels) {
      this.source = source;
      this.output = output;
      this.centreLevel = levels.map(LoRoLevels::center).orElse(MINUS_3_DB);
      this.surroundLevel = levels.map(LoRoLevels::surround).orElse(MINUS_3_DB);
      output.speakers().forEach(speaker -> matrix.put(speaker, new EnumMap<>(Speaker.class)));
    }

    void add(Speaker speaker) {
      if (output.has(speaker) && !mergesIntoOneSurround(speaker)) {
        mix(speaker, speaker, 1.0);
        return;
      }

      switch (speaker) {
        case FL -> mix(speaker, FL, 1.0);
        case FR -> mix(speaker, FR, 1.0);
        case FC -> centre(speaker);
        case LFE, LFE2 -> lowFrequency(speaker);
        case SL, BL -> surroundLeft(speaker, pairGain(SL, BL));
        case SR, BR -> surroundRight(speaker, pairGain(SR, BR));
        case FLC, WL, TFL, BFL, DL, BIL -> mix(speaker, FL, MINUS_3_DB);
        case FRC, WR, TFR, BFR, DR, BIR -> mix(speaker, FR, MINUS_3_DB);
        case TSL, TBL, SDL, SSL, TTL -> surroundLeft(speaker, MINUS_3_DB);
        case TSR, TBR, SDR, SSR, TTR -> surroundRight(speaker, MINUS_3_DB);
        case BC -> surroundPair(speaker, MINUS_3_DB);
        case TBC -> surroundPair(speaker, CENTRE_LINE);
        case TFC, BFC, TC -> {
          mix(speaker, FL, CENTRE_LINE);
          mix(speaker, FR, CENTRE_LINE);
        }
      }
    }

    // A side and a back channel on one side form LS or RS when the output has only one of them.
    private boolean mergesIntoOneSurround(Speaker speaker) {
      return switch (speaker) {
        case SL, BL -> source.has(SL) && source.has(BL) && !(output.has(SL) && output.has(BL));
        case SR, BR -> source.has(SR) && source.has(BR) && !(output.has(SR) && output.has(BR));
        default -> false;
      };
    }

    private double pairGain(Speaker side, Speaker back) {
      return source.has(side) && source.has(back) ? MINUS_3_DB : 1.0;
    }

    private void centre(Speaker speaker) {
      if (output.has(FC)) {
        mix(speaker, FC, 1.0);
        return;
      }

      mix(speaker, FL, centreLevel);
      mix(speaker, FR, centreLevel);
    }

    // The low-frequency channel folds into the front pair only when the source has no surround.
    private void lowFrequency(Speaker speaker) {
      if (output.has(LFE)) {
        mix(speaker, LFE, 1.0);
        return;
      }

      if (source.speakers().stream().anyMatch(Gains::isSurround)) {
        return;
      }

      mix(speaker, FL, MINUS_3_DB);
      mix(speaker, FR, MINUS_3_DB);
    }

    private void surroundPair(Speaker speaker, double gain) {
      surroundLeft(speaker, gain);
      surroundRight(speaker, gain);
    }

    private void surroundLeft(Speaker speaker, double gain) {
      surround(speaker, gain, SL, BL, FL);
    }

    private void surroundRight(Speaker speaker, double gain) {
      surround(speaker, gain, SR, BR, FR);
    }

    private void surround(Speaker speaker, double gain, Speaker side, Speaker back, Speaker front) {
      if (output.has(side)) {
        mix(speaker, side, gain);
        return;
      }

      if (output.has(back)) {
        mix(speaker, back, gain);
        return;
      }

      mix(speaker, front, surroundLevel * gain);
    }

    private void mix(Speaker from, Speaker to, double gain) {
      matrix.get(to).merge(from, gain, Double::sum);
    }

    private static boolean isSurround(Speaker speaker) {
      return switch (speaker) {
        case SL, SR, BL, BR, BC, TSL, TSR, TBL, TBR, TBC, SDL, SDR, SSL, SSR, TTL, TTR -> true;
        default -> false;
      };
    }
  }
}
