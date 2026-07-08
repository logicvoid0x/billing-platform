package com.logicvoid0x.billing.inferencesim.emitter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Without a broker (tests only, {@code usage.emitter.enabled=false}): logs usage, sends nothing.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "usage.emitter.enabled", havingValue = "false")
public class DisabledEmitterConfiguration {
  private static final Logger log = LoggerFactory.getLogger(DisabledEmitterConfiguration.class);

  /** Logs each usage instead of sending it. */
  @Bean
  UsageSink loggingUsageSink() {
    return usage -> log.warn("usage emitter disabled; not sending usage of {}", usage.requestId());
  }
}
