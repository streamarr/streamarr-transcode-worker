package com.streamarr.transcode.worker;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class WorkerActuatorConfiguration {

  @Bean
  HealthIndicator workerSessionHealthIndicator(TranscodeWorker worker) {
    return () -> worker.hasAcceptedSession() ? Health.up().build() : Health.down().build();
  }

  // An autoscaler reads this to add workers before copies are refused.
  @Bean
  MeterBinder copyFullnessGauge(TranscodeWorker worker) {
    return registry ->
        Gauge.builder("streamarr.worker.copy_fullness", worker, TranscodeWorker::copyFullness)
            .description(
                "The share of the copies that the worker's heap holds that run now; 1 when it"
                    + " holds none")
            .register(registry);
  }
}
