package com.logicvoid0x.billing.admission;

import java.util.List;
import java.util.Objects;

/**
 * A request to admit.
 *
 * @param requestId minted by the gateway; the reservation ID
 * @param orgId the org (its budgets share one shard)
 * @param budgets every budget that applies
 * @param extraUsage whether money budgets may be used once allowances are exhausted
 */
public record AdmissionRequest(
    String requestId, String orgId, List<BudgetRef> budgets, boolean extraUsage) {
  /** Validates and copies. */
  public AdmissionRequest {
    Objects.requireNonNull(requestId, "requestId");
    Objects.requireNonNull(orgId, "orgId");
    budgets = List.copyOf(budgets);
  }
}
