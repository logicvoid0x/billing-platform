package com.logicvoid0x.billing.admission;

import java.util.List;

/**
 * An admission decision.
 *
 * @param admitted whether the request may run
 * @param path how it was admitted, or {@link Path#REFUSED}
 * @param budgets the budgets holding for it (the group that admitted)
 * @param sequence the shard sequence used for a direct reserve (the group's index)
 * @param reason why it was refused: {@code deny}, {@code insufficient}, {@code unknown_budget},
 *     {@code shard_unavailable}, {@code no_budgets}; empty when admitted
 */
public record Decision(
    boolean admitted, Path path, List<String> budgets, int sequence, String reason) {
  /** How a request was admitted. */
  public enum Path {
    /** In-process from local leases. */
    LEASE,
    /** Reserved on the regional shard. */
    DIRECT,
    /** The shard was unavailable and every budget fails open within its allowance. */
    FAIL_OPEN,
    /** Not admitted. */
    REFUSED
  }

  /** Copies the list. */
  public Decision {
    budgets = List.copyOf(budgets);
  }

  static Decision refused(String reason) {
    return new Decision(false, Path.REFUSED, List.of(), -1, reason);
  }
}
