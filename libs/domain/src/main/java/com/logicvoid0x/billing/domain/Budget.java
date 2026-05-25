package com.logicvoid0x.billing.domain;

import java.util.Objects;

/**
 * A budget instance: a prepaid balance, a spend limit or a usage allowance.
 *
 * @param budgetId unique per period or window instance, see {@link BudgetIds}
 * @param orgId the org whose shard holds this budget
 * @param kind balance, spend limit or allowance
 * @param cap the cap in {@link #unit()} ({@code long} cents or usage units); 0 for a balance
 * @param window how the budget resets
 * @param failPolicy what to do when the shard is unavailable
 * @param version incremented on every change; the idempotency key for budget changes
 */
public record Budget(
    String budgetId,
    String orgId,
    BudgetKind kind,
    long cap,
    Window window,
    FailPolicy failPolicy,
    long version) {
  /** Validates the budget. */
  public Budget {
    Ids.require("budgetId", budgetId);
    Ids.requireSegment("orgId", orgId);
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(window, "window");
    Objects.requireNonNull(failPolicy, "failPolicy");
    Ids.requireNonNegative("cap", cap);
    Ids.requireNonNegative("version", version);
  }

  /** The unit the cap and every amount on this budget count in. */
  public BudgetUnit unit() {
    return kind.unit();
  }
}
