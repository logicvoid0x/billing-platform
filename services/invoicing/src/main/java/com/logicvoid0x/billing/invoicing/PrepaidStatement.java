package com.logicvoid0x.billing.invoicing;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * A prepaid account's period statement (requirement: "rated lines plus credit applied from the
 * balance ledger"). The lines say what the period cost; the credit section says how the balance
 * paid for it. The statement is issued only if both sides agree: usage recognized in the balance
 * ledger equals the rated total (the true-up invariant), and the closing balance equals the opening
 * balance plus top-ups minus usage minus expired grants. Long cents throughout.
 *
 * @param orgId the account
 * @param periodStart the period
 * @param lines rated cents per SKU (invoice lines: F(period total))
 * @param openingCents spendable credit at the period start
 * @param topUpsCents TOP_UPs in the period (payments and grants)
 * @param recognizedCents usage recognized (USAGE_ROLLUP + TRUE_UP) for the period
 * @param expiredCents GRANT_EXPIRY in the period
 * @param closingCents spendable credit at the period end
 */
public record PrepaidStatement(
    String orgId,
    Instant periodStart,
    Map<String, Long> lines,
    long openingCents,
    long topUpsCents,
    long recognizedCents,
    long expiredCents,
    long closingCents) {
  /** Copies the lines in SKU order. */
  public PrepaidStatement {
    Objects.requireNonNull(orgId, "orgId");
    Objects.requireNonNull(periodStart, "periodStart");
    lines = java.util.Collections.unmodifiableMap(new TreeMap<>(lines));
  }

  /** The rated total of the period. */
  public long ratedTotal() {
    long total = 0;
    for (long cents : lines.values()) {
      total = Math.addExact(total, cents);
    }
    return total;
  }

  /** Throws unless the statement balances and agrees with the rated ledger. */
  public PrepaidStatement requireConsistent() {
    if (recognizedCents != ratedTotal()) {
      throw new IllegalStateException(
          "usage recognized "
              + recognizedCents
              + " != rated total "
              + ratedTotal()
              + " (true-up pending)");
    }
    long expected =
        Math.subtractExact(
            Math.subtractExact(Math.addExact(openingCents, topUpsCents), recognizedCents),
            expiredCents);
    if (closingCents != expected) {
      throw new IllegalStateException(
          "closing " + closingCents + " != " + expected + " from the movements");
    }
    return this;
  }
}
