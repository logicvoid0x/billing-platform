package com.logicvoid0x.billing.domain.budget;

/**
 * Outcome of a reserve or extend.
 *
 * @param admitted whether every hold was taken
 * @param reason why not, or {@link Reason#NONE} when admitted
 * @param refusedBudgetId the budget that refused, or empty when admitted
 */
public record ReserveResult(boolean admitted, Reason reason, String refusedBudgetId) {
  /** Why a reserve was refused; persisted by {@link #code()}. */
  public enum Reason {
    /** Admitted. */
    NONE("none"),
    /** A budget has less available than the hold. */
    INSUFFICIENT("insufficient"),
    /** A budget is not on this shard. */
    UNKNOWN_BUDGET("unknown_budget"),
    /** Extend for a request that has no open reservation. */
    NO_RESERVATION("no_reservation");

    private final String code;

    Reason(String code) {
      this.code = code;
    }

    /** Permanent code, as in {@code ReserveResponse.reason}. */
    public String code() {
      return code;
    }

    /** Returns the reason with this code. */
    public static Reason fromCode(String code) {
      for (Reason r : values()) {
        if (r.code.equals(code)) {
          return r;
        }
      }
      throw new IllegalArgumentException("unknown reason code: " + code);
    }
  }

  static ReserveResult ok() {
    return new ReserveResult(true, Reason.NONE, "");
  }

  static ReserveResult refused(Reason reason, String budgetId) {
    return new ReserveResult(false, reason, budgetId);
  }
}
