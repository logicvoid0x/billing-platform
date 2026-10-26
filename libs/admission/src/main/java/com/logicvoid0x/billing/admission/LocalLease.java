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
  private long leaseId;
  private long granted;

  /** An empty lease for {@code budgetId}. */
  public LocalLease(String budgetId) {
    this.budgetId = budgetId;
  }

  /** The budget. */
  public String budgetId() {
    return budgetId;
  }

  /**
   * Installs a new grant without a lease id, replacing the old one.
   *
   * @return the unused part of the replaced lease
   */
  public long install(long granted, long grantEpoch, long expiresAt) {
    return install(granted, grantEpoch, expiresAt, 0).unused();
  }

  /**
   * Installs grant {@code newLeaseId}, replacing the old one.
   *
   * @return the replaced lease, to report back to the shard
   */
  public synchronized Closed install(
      long newGranted, long grantEpoch, long expiresAt, long newLeaseId) {
    requireNonNegative(newGranted);
    long left = remaining.getAndSet(newGranted);
    Closed closed = closed(left);
    this.epoch = grantEpoch;
    this.expiresAtMillis = expiresAt;
    this.leaseId = newLeaseId;
    this.granted = newGranted;
    return closed;
  }

  /**
   * Ends the lease (host shutdown): nothing more can be spent from it.
   *
   * @return the lease as it stood, to report back to the shard
   */
  public synchronized Closed close() {
    expiresAtMillis = Long.MIN_VALUE;
    Closed closed = closed(remaining.getAndSet(0));
    leaseId = 0;
    granted = 0;
    return closed;
  }

  private Closed closed(long left) {
    long unused = Math.min(Math.max(0, left), granted);
    return new Closed(leaseId, granted - unused, unused);
  }

  /**
   * A lease taken off this host: what was spent from it and what was not.
   *
   * @param leaseId the shard's lease id; 0 for none (nothing to report)
   * @param spent credit spent from the lease
   * @param unused credit left on it
   */
  public record Closed(long leaseId, long spent, long unused) {}

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
