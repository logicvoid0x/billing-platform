package com.logicvoid0x.billing.domain;

/** The three budget kinds of the design; persisted by {@link #code()}, never by name. */
public enum BudgetKind {
  /** Prepaid balance: top-ups minus debits minus outstanding escrow. */
  BALANCE("balance", BudgetUnit.CENTS),
  /** Spend limit (org, workspace, API key): cap minus spend in the period. */
  SPEND_LIMIT("spend_limit", BudgetUnit.CENTS),
  /** Usage allowance (user or org pool): cap minus usage in the current window. */
  ALLOWANCE("allowance", BudgetUnit.USAGE_UNITS);

  private final String code;
  private final BudgetUnit unit;

  BudgetKind(String code, BudgetUnit unit) {
    this.code = code;
    this.unit = unit;
  }

  /** Permanent code used in storage and on the wire. */
  public String code() {
    return code;
  }

  /** The unit budgets of this kind count in. */
  public BudgetUnit unit() {
    return unit;
  }

  /** Returns the kind with this code. */
  public static BudgetKind fromCode(String code) {
    for (BudgetKind k : values()) {
      if (k.code.equals(code)) {
        return k;
      }
    }
    throw new IllegalArgumentException("unknown budget kind code: " + code);
  }
}
