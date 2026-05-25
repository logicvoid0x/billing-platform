package com.logicvoid0x.billing.domain;

/**
 * What the gateway does for a budget when the shard cannot answer: fail open up to the fail-open
 * allowance {@code F_a}, or fail closed.
 *
 * @param isOpen whether admission continues during an outage
 * @param failOpenAllowanceCents {@code F_a} in the budget's unit (cents for money budgets); 0 when
 *     closed
 */
public record FailPolicy(boolean isOpen, long failOpenAllowanceCents) {
  private static final FailPolicy CLOSED = new FailPolicy(false, 0);

  /** Validates the policy. */
  public FailPolicy {
    Ids.requireNonNegative("failOpenAllowanceCents", failOpenAllowanceCents);
    if (!isOpen && failOpenAllowanceCents != 0) {
      throw new IllegalArgumentException("a closed policy has no fail-open allowance");
    }
  }

  /** Fail open up to {@code allowance} (F_a). */
  public static FailPolicy open(long allowance) {
    return new FailPolicy(true, allowance);
  }

  /** Fail closed. */
  public static FailPolicy closed() {
    return CLOSED;
  }

  /** Permanent code: {@code open} or {@code closed}. */
  public String code() {
    return isOpen ? "open" : "closed";
  }
}
