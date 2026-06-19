package com.logicvoid0x.billing.budgetshard;

import com.logicvoid0x.billing.domain.budget.Rollup;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Periodic shard maintenance, run every {@link #INTERVAL_MILLIS} by the shard leader: a tick
 * (auto-settles stale holds and re-evaluates modes), then a rollup of the 5 s window that just
 * closed, handed to {@code sink} (the home-ledger client posting {@code USAGE_ROLLUP}). Window ends
 * are aligned to 5 s, so a retried run after a crash drains the same window and the ledger dedupes
 * it on {@link Rollup#idempotencyKey()}.
 */
public final class ShardMaintenance implements Runnable {
  /** Rollup interval from the design: every 5 s per active account. */
  public static final long INTERVAL_MILLIS = 5_000;

  /** The store operations maintenance needs. */
  public interface Store {
    /** Ticks the store at its current time. */
    void tick();

    /** Drains the rollup window ending at {@code windowEndMillis}. */
    List<Rollup> rollup(String region, long windowEndMillis);
  }

  private final Store store;
  private final String region;
  private final Clock clock;
  private final Consumer<List<Rollup>> sink;

  /**
   * Creates the task.
   *
   * @param store the shard
   * @param region this shard's region
   * @param clock time used to pick the closed window
   * @param sink receives each non-empty batch of rollups
   */
  public ShardMaintenance(Store store, String region, Clock clock, Consumer<List<Rollup>> sink) {
    this.store = Objects.requireNonNull(store, "store");
    this.region = Objects.requireNonNull(region, "region");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.sink = Objects.requireNonNull(sink, "sink");
  }

  /** End of the most recently closed 5 s window at {@code nowMillis}. */
  public static long closedWindowEnd(long nowMillis) {
    return Math.floorDiv(nowMillis, INTERVAL_MILLIS) * INTERVAL_MILLIS;
  }

  @Override
  public void run() {
    store.tick();
    List<Rollup> rollups = store.rollup(region, closedWindowEnd(clock.millis()));
    if (!rollups.isEmpty()) {
      sink.accept(rollups);
    }
  }
}
