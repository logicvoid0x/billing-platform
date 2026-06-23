package com.logicvoid0x.billing.admission;

import com.logicvoid0x.billing.domain.budget.BudgetStore;
import com.logicvoid0x.billing.domain.budget.ReserveResult;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.IntFunction;

/**
 * Keeps a streaming response funded one chunk ahead. The admission hold funds chunk 0; once 75% of
 * the current chunk has streamed, the guard funds the next chunk. If that is refused, the stream
 * runs to the end of what is already funded and then stops with reason {@code budget_exhausted} (or
 * {@code shard_unavailable}), so overdraft is bounded by one chunk per request.
 *
 * <p>Holds for chunk {@code i} come from a caller function (for money: {@code Cents.hold} on the
 * running output total, never rounded per chunk). Each funding call uses sequence {@code
 * startSequence + i}, so a retried call is idempotent on the shard. Called by one streaming thread.
 */
public final class StreamBudgetGuard {
  /** Stop reason when the next chunk could not be held. */
  public static final String BUDGET_EXHAUSTED = "budget_exhausted";

  /** Stop reason when the shard could not be reached to fund the next chunk. */
  public static final String SHARD_UNAVAILABLE = "shard_unavailable";

  /** Funds one more chunk; returns empty on success, else the stop reason. */
  private interface Funding {
    Optional<String> fund(int chunk, Map<String, Long> holds);
  }

  private final Funding funding;
  private final long chunkTokens;
  private final IntFunction<Map<String, Long>> holdsForChunk;
  private long streamed;
  private int fundedChunks = 1;
  private boolean nextRequested;
  private int attempts;
  private String unfundedReason;
  private boolean stopped;

  private StreamBudgetGuard(
      Funding funding, long chunkTokens, IntFunction<Map<String, Long>> holdsForChunk) {
    if (chunkTokens <= 0) {
      throw new IllegalArgumentException("chunkTokens must be > 0");
    }
    this.funding = funding;
    this.chunkTokens = chunkTokens;
    this.holdsForChunk = Objects.requireNonNull(holdsForChunk, "holdsForChunk");
  }

  /** A guard for a request admitted {@code DIRECT}: each next chunk extends the reservation. */
  public static StreamBudgetGuard direct(
      BudgetStore store,
      String requestId,
      int startSequence,
      long chunkTokens,
      IntFunction<Map<String, Long>> holdsForChunk) {
    Objects.requireNonNull(store, "store");
    return new StreamBudgetGuard(
        (chunk, holds) -> call(() -> store.extend(requestId, startSequence + chunk, holds)),
        chunkTokens,
        holdsForChunk);
  }

  /**
   * A guard for a request admitted from local leases: next chunks come from the leases (all or
   * nothing); once they run dry, the guard opens a reservation on the shard and extends it.
   */
  public static StreamBudgetGuard leased(
      Map<String, LocalLease> leases,
      BudgetStore store,
      String requestId,
      int startSequence,
      long chunkTokens,
      IntFunction<Map<String, Long>> holdsForChunk,
      Clock clock) {
    Objects.requireNonNull(store, "store");
    Objects.requireNonNull(clock, "clock");
    Map<String, LocalLease> byBudget = Map.copyOf(leases);
    boolean[] reservedOnShard = {false};
    return new StreamBudgetGuard(
        (chunk, holds) -> {
          if (!reservedOnShard[0] && consumeAll(byBudget, holds, clock.millis())) {
            return Optional.empty();
          }
          int sequence = startSequence + chunk;
          Optional<String> r =
              call(() ->
                  reservedOnShard[0]
                      ? store.extend(requestId, sequence, holds)
                      : store.reserve(requestId, sequence, holds));
          if (r.isEmpty()) {
            reservedOnShard[0] = true;
          }
          return r;
        },
        chunkTokens,
        holdsForChunk);
  }

  private static boolean consumeAll(
      Map<String, LocalLease> leases, Map<String, Long> holds, long now) {
    List<Map.Entry<String, Long>> taken = new ArrayList<>();
    for (Map.Entry<String, Long> e : holds.entrySet()) {
      LocalLease lease = leases.get(e.getKey());
      if (lease == null || !lease.tryConsume(e.getValue(), now)) {
        taken.forEach(t -> leases.get(t.getKey()).refund(t.getValue(), now));
        return false;
      }
      taken.add(e);
    }
    return true;
  }

  private static Optional<String> call(java.util.function.Supplier<ReserveResult> reserve) {
    try {
      return reserve.get().admitted() ? Optional.empty() : Optional.of(BUDGET_EXHAUSTED);
    } catch (IllegalArgumentException bug) {
      throw bug;
    } catch (RuntimeException shardDown) {
      return Optional.of(SHARD_UNAVAILABLE);
    }
  }

  /**
   * Records {@code tokens} just delivered. Returns the stop reason once the stream has used all it
   * was funded for and the next chunk could not be held; empty means keep streaming.
   */
  public Optional<String> onTokens(int tokens) {
    if (tokens < 0) {
      throw new IllegalArgumentException("tokens must be >= 0");
    }
    if (stopped) {
      return Optional.of(unfundedReason);
    }
    streamed += tokens;
    long funded = fundedTokens();
    // Take the next chunk's hold at 75% of the current chunk.
    if (unfundedReason == null
        && !nextRequested
        && streamed * 4 >= (funded - chunkTokens) * 4 + chunkTokens * 3) {
      nextRequested = true;
      attempts++;
      Optional<String> refused = funding.fund(fundedChunks, holdsForChunk.apply(fundedChunks));
      if (refused.isPresent()) {
        unfundedReason = refused.get();
      } else {
        fundedChunks++;
        nextRequested = false;
      }
    }
    if (unfundedReason != null && streamed >= fundedTokens()) {
      stopped = true;
      return Optional.of(unfundedReason);
    }
    return Optional.empty();
  }

  /** Output tokens covered by holds so far. */
  public long fundedTokens() {
    return fundedChunks * chunkTokens;
  }

  /** Output tokens streamed so far. */
  public long streamedTokens() {
    return streamed;
  }

  /** Why the stream was stopped, once it has been. */
  public Optional<String> stopReason() {
    return stopped ? Optional.of(unfundedReason) : Optional.empty();
  }

  /** How many times the guard asked for the next chunk. */
  public int fundingAttempts() {
    return attempts;
  }
}
