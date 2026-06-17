package com.logicvoid0x.billing.domain.budget;

import java.time.Clock;
import java.util.Map;
import java.util.Objects;

/**
 * {@link BudgetStore} on one in-process {@link BudgetState} behind a lock. For unit tests and local
 * development only: it is not replicated, so a crash loses every hold. Each write observes the
 * store's clock first, as the Ratis store stamps time on each command.
 */
public final class InMemoryBudgetStore implements BudgetStore {
  private final BudgetState state;
  private final Clock clock;

  /**
   * Creates an empty store on the UTC system clock.
   *
   * @param minHold the smallest possible hold; below it a budget is in {@code DENY}
   */
  public InMemoryBudgetStore(long minHold) {
    this(minHold, Clock.systemUTC());
  }

  /**
   * Creates an empty store.
   *
   * @param minHold the smallest possible hold; below it a budget is in {@code DENY}
   * @param clock time for burn rates and modes
   */
  public InMemoryBudgetStore(long minHold, Clock clock) {
    this.state = new BudgetState(minHold);
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /** Opens a budget with {@code capacity}. */
  public synchronized void open(String budgetId, String orgId, long capacity) {
    now();
    state.open(budgetId, orgId, capacity);
  }

  /** Adds capacity to a budget. */
  public synchronized void addCapacity(String budgetId, long delta) {
    now();
    state.addCapacity(budgetId, delta);
  }

  /** Moves to a newer epoch. */
  public synchronized void advanceEpoch(long epoch) {
    state.advanceEpoch(epoch);
  }

  /** Re-evaluates every mode at the current time. */
  public synchronized void tick() {
    state.tick(clock.millis());
  }

  /** Reads one budget. */
  public synchronized BudgetBalance balance(String budgetId) {
    return state.balance(budgetId);
  }

  @Override
  public synchronized ReserveResult reserve(
      String requestId, int sequence, Map<String, Long> holds) {
    now();
    return state.reserve(requestId, sequence, holds);
  }

  @Override
  public synchronized ReserveResult extend(
      String requestId, int sequence, Map<String, Long> holds) {
    now();
    return state.extend(requestId, sequence, holds);
  }

  @Override
  public synchronized SettleResult settle(
      String requestId, int sequence, Map<String, Long> actual) {
    now();
    return state.settle(requestId, sequence, actual);
  }

  @Override
  public synchronized LeaseGrant grantLease(
      String hostId, String budgetId, long requested, long epoch) {
    now();
    return state.grantLease(hostId, budgetId, requested, epoch);
  }

  @Override
  public synchronized ModeSnapshot modes(String orgId) {
    return state.modes(orgId);
  }

  private void now() {
    state.observeTime(clock.millis());
  }
}
