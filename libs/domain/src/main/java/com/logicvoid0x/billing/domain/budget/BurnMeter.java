package com.logicvoid0x.billing.domain.budget;

import java.util.Map;
import java.util.TreeMap;

/**
 * Sliding-window spend rate in epoch-aligned buckets. Deterministic: time is passed in by the
 * caller (on the Raft command), never read from a clock, so every replica computes the same burn.
 * Not thread-safe.
 */
public final class BurnMeter {
  private final long windowMillis;
  private final long bucketMillis;
  private final TreeMap<Long, Long> buckets = new TreeMap<>();

  /**
   * Creates a meter.
   *
   * @param windowMillis window length, a positive multiple of {@code bucketMillis}
   * @param bucketMillis bucket size, positive
   */
  public BurnMeter(long windowMillis, long bucketMillis) {
    if (bucketMillis <= 0 || windowMillis <= 0 || windowMillis % bucketMillis != 0) {
      throw new IllegalArgumentException("window must be a positive multiple of the bucket");
    }
    this.windowMillis = windowMillis;
    this.bucketMillis = bucketMillis;
  }

  /** Records {@code amount} spent at {@code atMillis}. */
  public void record(long amount, long atMillis) {
    if (amount < 0) {
      throw new IllegalArgumentException("amount must be >= 0");
    }
    if (amount == 0) {
      return;
    }
    buckets.merge(bucketOf(atMillis), amount, Math::addExact);
    // Forget buckets that can no longer be inside any window ending at or after the newest one.
    long oldestUseful = buckets.lastKey() - windowMillis + bucketMillis;
    buckets.headMap(oldestUseful, false).clear();
  }

  /** Spend per second over the window that ends in the bucket containing {@code nowMillis}. */
  public long perSecond(long nowMillis) {
    long newest = bucketOf(nowMillis);
    long oldest = newest - windowMillis + bucketMillis;
    long sum = 0;
    for (long v : buckets.subMap(oldest, true, newest, true).values()) {
      sum = Math.addExact(sum, v);
    }
    return sum / (windowMillis / 1_000);
  }

  /** The buckets, for snapshots: bucket start (epoch millis) to amount. */
  public Map<Long, Long> buckets() {
    return new TreeMap<>(buckets);
  }

  /** Restores buckets written by {@link #buckets()}. */
  public void restore(Map<Long, Long> saved) {
    buckets.clear();
    buckets.putAll(saved);
  }

  private long bucketOf(long atMillis) {
    return Math.floorDiv(atMillis, bucketMillis) * bucketMillis;
  }
}
