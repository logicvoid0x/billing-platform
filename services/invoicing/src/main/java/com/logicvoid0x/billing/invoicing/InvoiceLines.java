package com.logicvoid0x.billing.invoicing;

import com.logicvoid0x.billing.rating.RatingLibrary;
import java.time.Instant;

/**
 * Invoice line amounts (requirement: "Each line's amount is Cents.forTotal(period quantity) (or
 * F(total) for tiers), so it is rounded once, half-even, in whole cents; the hourly increments in
 * the rated ledger must sum to it exactly").
 */
public final class InvoiceLines {
  private InvoiceLines() {}

  /**
   * One (org, SKU, period) line: the pricing in effect at {@code pricingHour} applied once to the
   * period quantity.
   */
  public static long amount(
      RatingLibrary rating, String orgId, String skuId, long periodQuantity, Instant pricingHour) {
    return rating.pricing(orgId, skuId, pricingHour).totalCents(periodQuantity);
  }

  /**
   * The close check: the line must equal the sum of the rated ledger's hourly increments.
   *
   * @throws IllegalStateException with both amounts if they differ (a lost or duplicated increment)
   */
  public static long requireEqualsRated(long lineCents, long ratedIncrementsCents) {
    if (lineCents != ratedIncrementsCents) {
      throw new IllegalStateException(
          "invoice line " + lineCents + " != rated increments " + ratedIncrementsCents + " cents");
    }
    return lineCents;
  }
}
