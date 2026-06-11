package com.logicvoid0x.billing.domain.budget;

/**
 * A budget on the shard, as read.
 *
 * @param budgetId the budget
 * @param orgId its org
 * @param capacity credit available to this shard (escrow for a balance, the cap for a limit)
 * @param spent settled spend
 * @param held open holds
 * @param leased outstanding gateway leases
 */
public record BudgetBalance(
    String budgetId, String orgId, long capacity, long spent, long held, long leased) {
  /** Capacity minus spend, holds and leases; negative after a bounded overdraft. */
  public long available() {
    return capacity - spent - held - leased;
  }
}
