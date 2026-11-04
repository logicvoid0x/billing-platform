package com.logicvoid0x.billing.usageview;

import com.logicvoid0x.billing.domain.BudgetUnit;
import com.logicvoid0x.billing.domain.Window;
import com.logicvoid0x.billing.domain.budget.BudgetBalance;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What the usage page shows for one budget (requirement: "Live balance, holds and window usage
 * (percent used, resets_at) from the shard and ledger"). Percent used is integer basis points
 * (10,000 = 100%), floored, so no floating point touches amounts. Spent includes what is held,
 * because held credit is not available.
 *
 * @param budgetId the budget
 * @param unit cents or usage units
 * @param capacity the cap (or the credit granted to the shard for a balance)
 * @param spent settled usage in the instance
 * @param held outstanding holds and leases
 * @param available capacity - spent - held (may be negative within the overdraft bound)
 * @param usedBasisPoints (spent + held) / capacity, in basis points, floored; 10,000 when capacity
 *     is 0
 * @param resetsAt when the window instance resets, if it does
 */
public record BudgetView(
    String budgetId,
    BudgetUnit unit,
    long capacity,
    long spent,
    long held,
    long available,
    long usedBasisPoints,
    Optional<Instant> resetsAt) {
  /** Validates the view. */
  public BudgetView {
    Objects.requireNonNull(budgetId, "budgetId");
    Objects.requireNonNull(unit, "unit");
    Objects.requireNonNull(resetsAt, "resetsAt");
  }

  /** The view of a shard balance at {@code now}. */
  public static BudgetView of(
      BudgetBalance b, BudgetUnit unit, Window window, Instant instanceStart, Instant now) {
    long held = Math.addExact(b.held(), b.leased());
    long used = Math.addExact(b.spent(), held);
    long bp =
        b.capacity() <= 0
            ? 10_000
            : Math.floorDiv(Math.multiplyExact(Math.max(used, 0), 10_000L), b.capacity());
    return new BudgetView(
        b.budgetId(),
        unit,
        b.capacity(),
        b.spent(),
        held,
        b.available(),
        bp,
        window.resetsAt(now, instanceStart));
  }
}
