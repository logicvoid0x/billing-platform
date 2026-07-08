package com.logicvoid0x.billing.inferencesim.emitter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The host's usage sink: Kafka with a disk spool by default. Disabled only for tests that run
 * without a broker. Spool depth is the gauge {@code usage.spool.depth} (design: page if non-empty
 * for over 10 minutes).
 */
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
    return UsageEmitter.create(bootstrapServers, "usage-emitter-" + hostId, UsageEmitter.TOPIC);
  }

  /** The disk spool. */
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(
      name = "usage.emitter.enabled",
      havingValue = "true",
      matchIfMissing = true)
  UsageSpool usageSpool(@Value("${usage.spool.dir:data/usage-spool}") Path dir) {
    return UsageSpool.open(dir);
  }

  /** Kafka first, spool on failure; drained on a schedule. */
  @Bean(destroyMethod = "close")
  @ConditionalOnProperty(
      name = "usage.emitter.enabled",
      havingValue = "true",
      matchIfMissing = true)
  SpoolingUsageSink spoolingUsageSink(
      UsageEmitter emitter,
      UsageSpool spool,
      MeterRegistry registry,
      @Value("${usage.spool.drain-batch:500}") int drainBatch) {
    SpoolingUsageSink sink =
        new SpoolingUsageSink(emitter, spool, drainBatch, UsageEmitter.DELIVERY_TIMEOUT);
    Gauge.builder("usage.spool.depth", sink, SpoolingUsageSink::spoolDepth)
        .description("usage records waiting in the host's disk spool")
        .register(registry);
    return sink;
  }

  /** Drains the spool every {@code usage.spool.drain-every}. */
  @Bean(destroyMethod = "shutdownNow")
  @ConditionalOnProperty(
      name = "usage.emitter.enabled",
      havingValue = "true",
      matchIfMissing = true)
  ScheduledExecutorService usageSpoolDrainer(
      SpoolingUsageSink sink, @Value("${usage.spool.drain-every:5s}") Duration every) {
    ScheduledExecutorService s =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("usage-spool-drain").daemon().factory());
    var unused =
        s.scheduleWithFixedDelay(
            () -> {
              try {
                int n = sink.drain();
                if (n > 0) {
                  log.info("drained {} spooled usage records; {} left", n, sink.spoolDepth());
                }
              } catch (RuntimeException e) {
                log.error("usage spool drain failed", e);
              }
            },
            every.toMillis(),
            every.toMillis(),
            TimeUnit.MILLISECONDS);
    return s;
  }

  /** Without a broker (tests only): logs each usage instead of sending it. */
  @Bean
  @ConditionalOnProperty(name = "usage.emitter.enabled", havingValue = "false")
  UsageSink loggingUsageSink() {
    return usage -> log.warn("usage emitter disabled; not sending usage of {}", usage.requestId());
  }
}
