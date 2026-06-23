package com.logicvoid0x.billing.admission;

import com.logicvoid0x.billing.domain.BudgetKind;
import com.logicvoid0x.billing.domain.FailPolicy;
import java.util.Objects;

/**
 * A budget that applies to a request.
 *
 * @param budgetId the budget instance ID
 * @param kind balance, spend limit or allowance
 * @param failPolicy what to do if the shard cannot answer
 */
public record BudgetRef(String budgetId, BudgetKind kind, FailPolicy failPolicy) {
  /** Validates the reference. */
  public BudgetRef {
    Objects.requireNonNull(budgetId, "budgetId");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(failPolicy, "failPolicy");
  }
}
