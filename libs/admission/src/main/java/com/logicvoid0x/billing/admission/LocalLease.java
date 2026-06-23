package com.logicvoid0x.billing.admission;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A gateway host's lease on one budget: credit the shard granted in advance (about 2 s of host
 * burn, 2 s TTL), spent in-process with a compare-and-set so the hot path never blocks. The lease
 * never spends more than was granted, under any concurrency; an expired lease spends nothing.
 */
public final class LocalLease {
  private final String budgetId;
  private final AtomicLong remaining = new AtomicLong();
  private volatile long expiresAtMillis = Long.MIN_VALUE;
  private volatile long epoch;

  /** An empty lease for {@code budgetId}. */
  public LocalLease(String budgetId) {
    this.budgetId = budgetId;
  }

  /** The budget. */
  public String budgetId() {
    return budgetId;
  }

  /**
   * Installs a new grant, replacing the old one.
   *
   * @return the unused part of the replaced lease, to report back to the shard
   */
  public synchronized long install(long granted, long grantEpoch, long expiresAt) {
    requireNonNegative(granted);
    long unused = remaining.getAndSet(granted);
    this.epoch = grantEpoch;
    this.expiresAtMillis = expiresAt;
    return Math.max(0, unused);
  }

  /** Spends {@code amount} if the lease is live and has enough left; all or nothing. */
  public boolean tryConsume(long amount, long nowMillis) {
    requireNonNegative(amount);
    if (nowMillis >= expiresAtMillis) {
      return false;
    }
    while (true) {
      long left = remaining.get();
      if (left < amount) {
        return false;
      }
      if (remaining.compareAndSet(left, left - amount)) {
        return true;
      }
    }
  }

  /** Gives {@code amount} back to a live lease (a settle below the hold); false if expired. */
  public boolean refund(long amount, long nowMillis) {
    requireNonNegative(amount);
    if (nowMillis >= expiresAtMillis) {
      return false;
    }
    remaining.addAndGet(amount);
    return true;
  }

  /** Credit left on the lease. */
  public long remaining() {
    return remaining.get();
  }

  /** Epoch (fencing token) of the grant. */
  public long epoch() {
    return epoch;
  }

  private static void requireNonNegative(long amount) {
    if (amount < 0) {
      throw new IllegalArgumentException("amounts must be >= 0");
    }
  }
}
