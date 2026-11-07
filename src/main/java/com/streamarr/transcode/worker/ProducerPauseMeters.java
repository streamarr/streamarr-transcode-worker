package com.streamarr.transcode.worker;

import com.streamarr.transcode.engine.ProducerPauses;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.NonNull;

/**
 * The worker's meters of seek priority's pauses and hold-back's holds (ADR 0044). They carry no job
 * attempt or stream session tag, so their series stay few whatever the worker runs.
 */
final class ProducerPauseMeters {

  private ProducerPauseMeters() {}

  static void bind(@NonNull MeterRegistry registry, @NonNull ProducerPauses pauses) {
    FunctionCounter.builder(
            "streamarr.worker.seek_yield_pauses", pauses, ProducerPauses::seekPauses)
        .description("Pauses for a seek that stopped, or kept stopped, a producer's FFmpeg")
        .register(registry);
    FunctionCounter.builder(
            "streamarr.worker.seek_yield_resumes", pauses, ProducerPauses::seekResumes)
        .description("Pauses for a seek that ended")
        .register(registry);
    FunctionCounter.builder("streamarr.worker.hold_backs", pauses, ProducerPauses::holds)
        .description("Holds that stopped, or kept stopped, a producer's FFmpeg")
        .register(registry);
    FunctionCounter.builder(
            "streamarr.worker.hold_back_releases", pauses, ProducerPauses::holdReleases)
        .description("Holds that ended")
        .register(registry);
    Gauge.builder("streamarr.worker.paused_producers", pauses, ProducerPauses::stoppedProducers)
        .description("Producers whose FFmpeg a seek's pause or a hold stops now")
        .register(registry);
  }
}
