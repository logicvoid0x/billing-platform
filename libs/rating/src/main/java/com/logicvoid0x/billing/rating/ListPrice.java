package com.logicvoid0x.billing.rating;

import java.util.Objects;

/**
 * A list price for a SKU from the price book, effective on hour boundaries.
 *
 * @param skuId the SKU
 * @param pricing flat or graduated
 * @param effective hour-aligned effective period
 */
public record ListPrice(String skuId, Pricing pricing, EffectivePeriod effective) {
  /** Validates the price. */
  public ListPrice {
    Strings.require("skuId", skuId);
    Objects.requireNonNull(pricing, "pricing");
    requireHourAligned(effective);
  }

  static void requireHourAligned(EffectivePeriod effective) {
    Objects.requireNonNull(effective, "effective");
    if (!effective.isHourAligned()) {
      throw new IllegalArgumentException("price periods must start and end on an hour boundary");
    }
  }
}
