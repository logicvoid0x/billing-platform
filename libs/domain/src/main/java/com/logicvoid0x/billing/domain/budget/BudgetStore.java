package com.logicvoid0x.billing.domain.budget;

import java.util.Map;

/**
 * The regional budget shard as the gateway sees it: Ratis in production, in-memory in tests.
 * Amounts are {@code long} in each budget's unit (cents or usage units). Every mutation is
 * idempotent on {@code requestId} + {@code sequence}: a replay returns the first result and changes
 * nothing.
 */
public interface BudgetStore {
  /** Holds every amount in {@code holds}, or none of them. */
  ReserveResult reserve(String requestId, int sequence, Map<String, Long> holds);

  /** Adds holds to an existing reservation (the next streaming chunk), all or nothing. */
  ReserveResult extend(String requestId, int sequence, Map<String, Long> holds);

  /** Charges {@code actual} per budget and releases the rest of the hold. */
  SettleResult settle(String requestId, int sequence, Map<String, Long> actual);

  /** Grants a gateway host a lease of up to {@code requested}; stale epochs get nothing. */
  LeaseGrant grantLease(String hostId, String budgetId, long requested, long epoch);

  /**
   * Closes lease {@code leaseId} of {@code hostId} on {@code budgetId}: {@code spent} is charged
   * and {@code unused} released. They must add up to the granted amount. Idempotent on host, budget
   * and lease id; a lease left unreported is charged in full at its TTL, and a later report
   * corrects that charge.
   */
  LeaseReport reportLease(String hostId, String budgetId, long leaseId, long spent, long unused);

  /** Current mode of each budget of the org. */
  ModeSnapshot modes(String orgId);
}
