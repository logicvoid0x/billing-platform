package com.logicvoid0x.billing.inferencesim.emitter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The host's usage path, enabled by default and disabled only for tests that run without a broker
 * ({@code usage.emitter.enabled=false}, see {@link DisabledEmitterConfiguration}).
 *
 * <ul>
 *   <li>Usage goes to {@code usage.raw} through its own producer and disk spool.
 *   <li>Control totals go to {@code usage.control} through a separate producer and spool, every
 *       {@code usage.control.publish-every} (1 minute).
 *   <li>Both spools are drained every {@code usage.spool.drain-every}. Their depths are the gauge
 *       {@code usage.spool.depth} with tag {@code spool=usage|control} (design: page if non-empty
 *       for over 10 minutes).
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "usage.emitter.enabled", havingValue = "true", matchIfMissing = true)
public class EmitterConfiguration {
  private static final Logger log = LoggerFactory.getLogger(EmitterConfiguration.class);

  @Value("${usage.emitter.bootstrap-servers:localhost:19092}")
  private String bootstrapServers;

  @Value("${inference-sim.host-id:sim-host-1}")
  private String hostId;

  @Value("${usage.spool.drain-batch:500}")
  private int drainBatch;

  /** Producer to usage.raw. */
  @Bean(destroyMethod = "close")
  UsageEmitter usageEmitter() {
    return UsageEmitter.create(bootstrapServers, "usage-emitter-" + hostId, UsageEmitter.TOPIC);
  }

  /** Separate producer to usage.control. */
  @Bean(destroyMethod = "close")
  UsageEmitter controlEmitter() {
    return UsageEmitter.create(
        bootstrapServers, "control-emitter-" + hostId, ControlTotalsPublisher.TOPIC);
  }

  /** Disk spool for usage. */
  @Bean(destroyMethod = "close")
  UsageSpool usageSpool(@Value("${usage.spool.dir:data/usage-spool}") Path dir) {
    return UsageSpool.open(dir);
  }

  /** Separate disk spool for control totals. */
  @Bean(destroyMethod = "close")
  UsageSpool controlSpool(@Value("${usage.control.spool-dir:data/control-spool}") Path dir) {
    return UsageSpool.open(dir);
  }

  /** Usage: Kafka first, spool on failure. */
  @Bean(destroyMethod = "close")
  SpoolingSender usageSender(
      @Qualifier("usageEmitter") UsageEmitter emitter,
      @Qualifier("usageSpool") UsageSpool spool,
      MeterRegistry registry) {
    return gauged(
        new SpoolingSender(
            emitter, spool, HostUsageSink::keyOf, drainBatch, UsageEmitter.DELIVERY_TIMEOUT),
        "usage",
        registry);
  }

  /** Control totals: Kafka first, spool on failure. */
  @Bean(destroyMethod = "close")
  SpoolingSender controlSender(
      @Qualifier("controlEmitter") UsageEmitter emitter,
      @Qualifier("controlSpool") UsageSpool spool,
      MeterRegistry registry) {
    return gauged(
        new SpoolingSender(
            emitter,
            spool,
            ControlTotalsPublisher::keyOf,
            drainBatch,
            UsageEmitter.DELIVERY_TIMEOUT),
        "control",
        registry);
  }

  private static SpoolingSender gauged(SpoolingSender s, String spool, MeterRegistry registry) {
    Gauge.builder("usage.spool.depth", s, SpoolingSender::spoolDepth)
        .description("records waiting in the host's disk spool")
        .tag("spool", spool)
        .register(registry);
    return s;
  }

  /** What this host emitted, for the control totals. */
  @Bean
  ControlTotalsRecorder controlTotalsRecorder() {
    return new ControlTotalsRecorder();
  }

  /** The usage sink the simulator emits to. */
  @Bean
  UsageSink hostUsageSink(
      @Qualifier("usageSender") SpoolingSender usageSender, ControlTotalsRecorder recorder) {
    return new HostUsageSink(usageSender, recorder);
  }

  /** Publishes control totals; a last publish on shutdown sends what is left. */
  @Bean(destroyMethod = "publish")
  ControlTotalsPublisher controlTotalsPublisher(
      ControlTotalsRecorder recorder,
      @Qualifier("controlSender") SpoolingSender controlSender,
      @Value("${usage.control.spool-dir:data/control-spool}") Path stateDir) {
    return new ControlTotalsPublisher(hostId, recorder, controlSender, Clock.systemUTC(), stateDir);
  }

  /** Drains both spools and publishes control totals on schedule. */
  @Bean(destroyMethod = "shutdownNow")
  ScheduledExecutorService usageSchedules(
      @Qualifier("usageSender") SpoolingSender usageSender,
      @Qualifier("controlSender") SpoolingSender controlSender,
      ControlTotalsPublisher publisher,
      @Value("${usage.spool.drain-every:5s}") Duration drainEvery,
      @Value("${usage.control.publish-every:1m}") Duration publishEvery) {
    ScheduledExecutorService s =
        Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("usage-schedules").daemon().factory());
    var unusedDrain =
        s.scheduleWithFixedDelay(
            () -> {
              drain("usage", usageSender);
              drain("control", controlSender);
            },
            drainEvery.toMillis(),
            drainEvery.toMillis(),
            TimeUnit.MILLISECONDS);
    var unusedPublish =
        s.scheduleAtFixedRate(
            () -> {
              try {
                publisher.publish();
              } catch (RuntimeException e) {
                log.error("publishing control totals failed", e);
              }
            },
            publishEvery.toMillis(),
            publishEvery.toMillis(),
            TimeUnit.MILLISECONDS);
    return s;
  }

  private static void drain(String name, SpoolingSender sender) {
    try {
      int n = sender.drain();
      if (n > 0) {
        log.info("drained {} spooled {} records; {} left", n, name, sender.spoolDepth());
      }
    } catch (RuntimeException e) {
      log.error("{} spool drain failed", name, e);
    }
  }
}
