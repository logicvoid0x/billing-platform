package com.logicvoid0x.billing.balanceledger;

/**
 * Balance-ledger entry types (design: "Balance ledger entries"). Each type has fixed debit and
 * credit account kinds; {@code TRUE_UP} goes either way between customer credit and usage
 * recognized. {@link #code()} is the permanent stored value, never {@code name()}.
 */
public enum EntryType {
  /** Customer pays; debit cash clearing, credit customer credit. Key: payment ID. */
  TOP_UP("TOP_UP", AccountKind.CASH_CLEARING, AccountKind.CUSTOMER_CREDIT),
  /** Home shard moves credit to a region; debit customer credit, credit regional escrow. */
  ESCROW_GRANT("ESCROW_GRANT", AccountKind.CUSTOMER_CREDIT, AccountKind.ESCROW),
  /** A region returns idle escrow; debit regional escrow, credit customer credit. */
  ESCROW_RETURN("ESCROW_RETURN", AccountKind.ESCROW, AccountKind.CUSTOMER_CREDIT),
  /** Regional usage recognized; debit regional escrow, credit usage recognized. */
  USAGE_ROLLUP("USAGE_ROLLUP", AccountKind.ESCROW, AccountKind.USAGE_RECOGNIZED),
  /** Hourly gap to the rated ledger; customer credit to usage recognized (or back). */
  TRUE_UP("TRUE_UP", AccountKind.CUSTOMER_CREDIT, AccountKind.USAGE_RECOGNIZED),
  /** A credit grant expires; debit customer credit, credit breakage revenue. */
  GRANT_EXPIRY("GRANT_EXPIRY", AccountKind.CUSTOMER_CREDIT, AccountKind.BREAKAGE_REVENUE);

  private final String code;
  private final AccountKind debit;
  private final AccountKind credit;

  EntryType(String code, AccountKind debit, AccountKind credit) {
    this.code = code;
    this.debit = debit;
    this.credit = credit;
  }

  /** The permanent stored code. */
  public String code() {
    return code;
  }

  /** The account kind debited (for TRUE_UP: when the rated amount exceeds recognized usage). */
  public AccountKind debit() {
    return debit;
  }

  /** The account kind credited (for TRUE_UP: when the rated amount exceeds recognized usage). */
  public AccountKind credit() {
    return credit;
  }
}
