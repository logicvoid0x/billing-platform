package com.logicvoid0x.billing.inferencesim.emitter;

import com.logicvoid0x.billing.domain.RequestUsage;

/** Where an inference host sends the usage of each request that delivered output. */
@FunctionalInterface
public interface UsageSink {
  /**
   * Hands off one request's usage. Never blocks on a broker outage for longer than the producer's
   * metadata wait, and never drops usage silently.
   */
  void emit(RequestUsage usage);
}
