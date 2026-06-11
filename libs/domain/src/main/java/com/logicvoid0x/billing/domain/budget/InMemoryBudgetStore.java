package com.logicvoid0x.billing.domain.budget;

import java.util.Map;

/**
 * {@link BudgetStore} on one in-process {@link BudgetState} behind a lock. For unit tests and local
 * development only: it is not replicated, so a crash loses every hold.
 */
public final class InMemoryBudgetStore implements BudgetStore {
  private final BudgetState state;

  /**
   * Creates an empty store.
   *
   * @param minHold the smallest possible hold; below it a budget is in {@code DENY}
   */
  public InMemoryBudgetStore(long minHold) {
    this.state = new BudgetState(minHold);
  }

  /** Opens a budget with {@code capacity}. */
  public synchronized void open(String budgetId, String orgId, long capacity) {
    state.open(budgetId, orgId, capacity);
  }

  /** Adds capacity to a budget. */
  public synchronized void addCapacity(String budgetId, long delta) {
    state.addCapacity(budgetId, delta);
  }

  /** Moves to a newer epoch. */
  public synchronized void advanceEpoch(long epoch) {
    state.advanceEpoch(epoch);
  }

  /** Reads one budget. */
  public synchronized BudgetBalance balance(String budgetId) {
    return state.balance(budgetId);
  }

  @Override
  public synchronized ReserveResult reserve(
      String requestId, int sequence, Map<String, Long> holds) {
    return state.reserve(requestId, sequence, holds);
  }

  @Override
  public synchronized ReserveResult extend(
      String requestId, int sequence, Map<String, Long> holds) {
    return state.extend(requestId, sequence, holds);
  }

  @Override
  public synchronized SettleResult settle(
      String requestId, int sequence, Map<String, Long> actual) {
    return state.settle(requestId, sequence, actual);
  }

  @Override
  public synchronized LeaseGrant grantLease(
      String hostId, String budgetId, long requested, long epoch) {
    return state.grantLease(hostId, budgetId, requested, epoch);
  }

  @Override
  public synchronized ModeSnapshot modes(String orgId) {
    return state.modes(orgId);
  }
}
