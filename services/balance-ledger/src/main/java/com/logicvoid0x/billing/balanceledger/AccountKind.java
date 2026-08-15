package com.logicvoid0x.billing.balanceledger;

/** Balance-ledger account kinds. {@link #code()} is the permanent stored value. */
public enum AccountKind {
  /** Money received, not yet applied. */
  CASH_CLEARING("CASH_CLEARING", false),
  /** The customer's spendable credit (a liability: credit-normal). */
  CUSTOMER_CREDIT("CUSTOMER_CREDIT", false),
  /** Credit escrowed to one region's gateways (credit-normal). */
  ESCROW("ESCROW", true),
  /** Usage recognized against the customer's credit (revenue: credit-normal). */
  USAGE_RECOGNIZED("USAGE_RECOGNIZED", false),
  /** Expired grants (revenue: credit-normal). */
  BREAKAGE_REVENUE("BREAKAGE_REVENUE", false);

  private final String code;
  private final boolean regional;

  AccountKind(String code, boolean regional) {
    this.code = code;
    this.regional = regional;
  }

  /** The permanent stored code. */
  public String code() {
    return code;
  }

  /** The kind stored as {@code code}. */
  public static AccountKind ofCode(String code) {
    for (AccountKind k : values()) {
      if (k.code.equals(code)) {
        return k;
      }
    }
    throw new IllegalArgumentException("unknown account kind " + code);
  }

  /** True if accounts of this kind are per region. */
  public boolean regional() {
    return regional;
  }

  /** The account ID of this kind for {@code org} (and {@code region} if regional). */
  public String accountId(String org, String region) {
    if (regional) {
      if (region == null || region.isBlank()) {
        throw new IllegalArgumentException(code + " needs a region");
      }
      return org + ":" + code + ":" + region;
    }
    return org + ":" + code;
  }
}
