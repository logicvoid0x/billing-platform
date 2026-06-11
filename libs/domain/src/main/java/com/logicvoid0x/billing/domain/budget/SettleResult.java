package com.logicvoid0x.billing.domain.budget;

import java.util.Map;

/**
 * Outcome of a settle.
 *
 * @param status what happened
 * @param released budgetId to the part of the hold released back (0 when actual exceeded it)
 */
public record SettleResult(Status status, Map<String, Long> released) {
  /** Settle outcomes. */
  public enum Status {
    /** Charged and released. */
    SETTLED,
    /** The reservation was already settled under another sequence; nothing changed. */
    ALREADY_SETTLED,
    /** No reservation for this request on this shard. */
    NO_RESERVATION
  }

  /** Copies the map. */
  public SettleResult {
    released = Map.copyOf(released);
  }
}
