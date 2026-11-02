package com.streamarr.transcode.engine;

import java.util.OptionalInt;
import lombok.NonNull;

/**
 * The ceiling that ADR 0050's limiter keeps a downmix's summed channels below: −1 dBTP for FLAC and
 * ALAC, −3 dBFS for a lossy stereo or mono downmix, and −1 dBFS for a lossy downmix to more than
 * two channels. Each limiter looks ahead 5 ms, releases over 50 ms, adds no make-up gain and
 * removes its own delay.
 */
sealed interface DownmixLimiter {

  String LIMITER_TIMING = ":attack=5:release=50:level=0:latency=1";
  double MINUS_1_DBFS = 0.891251;
  double MINUS_3_DBFS = 0.707946;
  int TRUE_PEAK_OVERSAMPLING = 4;

  /** The filter chain that limits one channel or one linked left-right pair. */
  String filter();

  static DownmixLimiter forLossy(SpeakerLayout output) {
    return new SamplePeak(output.channelCount() <= 2 ? MINUS_3_DBFS : MINUS_1_DBFS);
  }

  static DownmixLimiter forLossless(SourceAudio source) {
    return new TruePeak(source.sampleRate());
  }

  private static String alimiter(double limit) {
    return "alimiter=limit=" + limit + LIMITER_TIMING;
  }

  record SamplePeak(double limit) implements DownmixLimiter {
    @Override
    public String filter() {
      return alimiter(limit);
    }
  }

  /**
   * Resamples the summed channels to four times their rate, limits them at −1 dBFS, and resamples
   * them back, so the ceiling holds between samples.
   */
  record TruePeak(@NonNull OptionalInt sampleRate) implements DownmixLimiter {
    @Override
    public String filter() {
      var rate =
          sampleRate.orElseThrow(
              () -> new IllegalStateException("A true-peak limiter needs the source sample rate"));
      return AudioEncodeOptions.resample(rate * TRUE_PEAK_OVERSAMPLING)
          + ","
          + alimiter(MINUS_1_DBFS)
          + ","
          + AudioEncodeOptions.resample(rate);
    }
  }
}
