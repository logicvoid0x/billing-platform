package com.logicvoid0x.billing.domain;

/** The unit a budget counts in; persisted by {@link #code()}, never by name. */
public enum BudgetUnit {
  /** US cents ({@code long}); see docs/decisions/0002-money-in-cents.md. */
  CENTS("cents"),
  /** Weighted usage units from the allowance weight book ({@code long}). */
  USAGE_UNITS("usage_units");

  private final String code;

  BudgetUnit(String code) {
    this.code = code;
  }

  /** Permanent code used in storage and on the wire. */
  public String code() {
    return code;
  }

  /** Returns the unit with this code. */
  public static BudgetUnit fromCode(String code) {
    for (BudgetUnit u : values()) {
      if (u.code.equals(code)) {
        return u;
      }
    }
    throw new IllegalArgumentException("unknown budget unit code: " + code);
  }
}
