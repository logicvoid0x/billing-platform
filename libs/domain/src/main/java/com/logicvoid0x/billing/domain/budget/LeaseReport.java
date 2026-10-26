package com.logicvoid0x.billing.domain.budget;

import java.util.Objects;

/**
 * The shard's answer to a gateway's lease report (open question Q5).
 *
 * @param status what happened
 * @param released credit made available again, in the budget's unit (0 unless reported)
 */
public record LeaseReport(Status status, long released) {
  /** Validates the result. */
  public LeaseReport {
    Objects.requireNonNull(status, "status");
    if (released < 0) {
      throw new IllegalArgumentException("released must be >= 0");
    }
  }

  /** Outcome of a report; codes are permanent strings. */
  public enum Status {
    /** The lease was closed now (or was closed by this same report before: a replay). */
    REPORTED("reported"),
    /** No such lease on this host and budget, or it expired too long ago. */
    UNKNOWN_LEASE("unknown_lease");

    private final String code;

    Status(String code) {
      this.code = code;
    }

    /** The permanent code. */
    public String code() {
      return code;
    }

    /** The status with {@code code}. */
    public static Status fromCode(String code) {
      for (Status s : values()) {
        if (s.code.equals(code)) {
          return s;
        }
      }
      throw new IllegalArgumentException("unknown lease report status: " + code);
    }
  }
}
