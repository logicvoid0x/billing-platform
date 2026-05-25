package com.logicvoid0x.billing.domain;

/** How the gateway admits requests for a budget; persisted by {@link #code()}, never by name. */
public enum Mode {
  /** Runway over 60 s: admit in-process from a gateway lease. */
  LEASE("lease"),
  /** Runway under 60 s, small or idle accounts, or a lease miss: reserve on the regional shard. */
  DIRECT("direct"),
  /** Spendable credit below the smallest possible hold: cached reject. */
  DENY("deny");

  private final String code;

  Mode(String code) {
    this.code = code;
  }

  /** Permanent code used in storage and on the wire. */
  public String code() {
    return code;
  }

  /** Returns the mode with this code. */
  public static Mode fromCode(String code) {
    for (Mode m : values()) {
      if (m.code.equals(code)) {
        return m;
      }
    }
    throw new IllegalArgumentException("unknown mode code: " + code);
  }
}
