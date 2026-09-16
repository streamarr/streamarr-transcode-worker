package com.streamarr.transcode.worker;

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
}
