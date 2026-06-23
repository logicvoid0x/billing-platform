package com.logicvoid0x.billing.admission;

import com.logicvoid0x.billing.domain.BudgetKind;
import java.util.ArrayList;
import java.util.List;

/**
 * Orders a request's budgets into priority groups (design: "plan allowances first, then money
 * budgets if the customer enabled extra usage"). A group admits only if every budget in it does;
 * the controller tries groups in order.
 */
public final class BudgetResolver {
  /**
   * The groups to try, in order.
   *
   * @param budgets every budget that applies to the request
   * @param extraUsage whether money budgets may be used once allowances are exhausted
   */
  public List<List<BudgetRef>> groups(List<BudgetRef> budgets, boolean extraUsage) {
    List<BudgetRef> allowances = new ArrayList<>();
    List<BudgetRef> money = new ArrayList<>();
    for (BudgetRef b : budgets) {
      (b.kind() == BudgetKind.ALLOWANCE ? allowances : money).add(b);
    }
    List<List<BudgetRef>> groups = new ArrayList<>();
    if (!allowances.isEmpty()) {
      groups.add(List.copyOf(allowances));
    }
    // An org with no allowances pays as it goes; with allowances, money needs extra usage on.
    if (!money.isEmpty() && (allowances.isEmpty() || extraUsage)) {
      groups.add(List.copyOf(money));
    }
    return List.copyOf(groups);
  }
}
