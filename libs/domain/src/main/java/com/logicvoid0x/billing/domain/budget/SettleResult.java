package com.logicvoid0x.billing.domain.budget;

import java.util.Map;

/**
 * Outcome of a settle.
 *
 * @param status what happened
 * @param released budgetId to the part of the hold released back (0 when actual exceeded it)
 */
public record SettleResult(Status status, Map<String, Long> released) {
  /** Settle outcomes; persisted by {@link #code()}. */
  public enum Status {
    /** Charged and released. */
    SETTLED("settled"),
    /** The reservation was already settled under another sequence; nothing changed. */
    ALREADY_SETTLED("already_settled"),
    /** No reservation for this request on this shard. */
    NO_RESERVATION("no_reservation");

    private final String code;

    Status(String code) {
      this.code = code;
    }

    /** Permanent code. */
    public String code() {
      return code;
    }

    /** Returns the status with this code. */
    public static Status fromCode(String code) {
      for (Status s : values()) {
        if (s.code.equals(code)) {
          return s;
        }
      }
      throw new IllegalArgumentException("unknown settle status code: " + code);
    }
  }

  /** Copies the map. */
  public SettleResult {
    released = Map.copyOf(released);
  }
}
