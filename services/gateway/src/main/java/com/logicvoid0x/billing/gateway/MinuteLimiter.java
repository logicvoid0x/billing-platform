package com.logicvoid0x.billing.gateway;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-API-key requests-per-minute limiter stub (plan: "runs a per-minute limiter stub"): a fixed
 * one-minute window per key, in memory on one gateway host.
 */
public final class MinuteLimiter {
  private final long perMinute;
  private final Clock clock;
  private final Map<String, long[]> windows = new ConcurrentHashMap<>();
  private final AtomicLong rejected = new AtomicLong();

  /** Creates the limiter. */
  public MinuteLimiter(long perMinute, Clock clock) {
    if (perMinute < 1) {
      throw new IllegalArgumentException("perMinute must be >= 1");
    }
    this.perMinute = perMinute;
    this.clock = clock;
  }

  /** Counts one request for {@code apiKeyId}; false when over the limit this minute. */
  public boolean tryAcquire(String apiKeyId) {
    long minute = clock.millis() / 60_000;
    long[] w = windows.computeIfAbsent(apiKeyId, k -> new long[] {minute, 0});
    synchronized (w) {
      if (w[0] != minute) {
        w[0] = minute;
        w[1] = 0;
      }
      if (w[1] >= perMinute) {
        rejected.incrementAndGet();
        return false;
      }
      w[1]++;
      return true;
    }
  }

  /** Requests refused so far. */
  public long rejected() {
    return rejected.get();
  }
}
