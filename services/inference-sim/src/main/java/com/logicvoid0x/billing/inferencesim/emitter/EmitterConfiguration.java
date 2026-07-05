package com.logicvoid0x.billing.inferencesim.emitter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The host's usage sink: Kafka by default; disabled only for tests that run without a broker. */
@Configuration(proxyBeanMethods = false)
public class EmitterConfiguration {
  private static final Logger log = LoggerFactory.getLogger(EmitterConfiguration.class);

  /** Kafka emitter to usage.raw. */
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(
      name = "usage.emitter.enabled",
      havingValue = "true",
      matchIfMissing = true)
  UsageEmitter usageEmitter(
      @Value("${usage.emitter.bootstrap-servers:localhost:19092}") String bootstrapServers,
      @Value("${inference-sim.host-id:sim-host-1}") String hostId) {
    return UsageEmitter.create(
        bootstrapServers,
        "usage-emitter-" + hostId,
        // Replaced by the disk spool in P4.3; until then a failed send is logged, never silent.
        (usage, error) ->
            log.error(
                "usage NOT delivered for request {}: {}", usage.requestId(), error.toString()));
  }

  /** Without a broker (tests only): logs each usage instead of sending it. */
  @Bean
  @ConditionalOnProperty(name = "usage.emitter.enabled", havingValue = "false")
  UsageSink loggingUsageSink() {
    return usage -> log.warn("usage emitter disabled; not sending usage of {}", usage.requestId());
  }
}
