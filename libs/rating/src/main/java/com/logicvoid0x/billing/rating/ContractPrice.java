package com.logicvoid0x.billing.rating;

import java.util.Objects;

/**
 * A contract override: the price an org pays for a SKU instead of the list price. Discounts are
 * applied once, when the contract price is created, and must give whole-cent rates.
 *
 * @param orgId the org
 * @param skuId the SKU
 * @param pricing the contracted pricing
 * @param effective hour-aligned effective period
 */
public record ContractPrice(
    String orgId, String skuId, Pricing pricing, EffectivePeriod effective) {
  /** Validates the override. */
  public ContractPrice {
    Strings.require("orgId", orgId);
    Strings.require("skuId", skuId);
    Objects.requireNonNull(pricing, "pricing");
    ListPrice.requireHourAligned(effective);
  }

  /** A contract price that is {@code list} with a basis-point discount (2000 = 20% off). */
  public static ContractPrice discount(
      String orgId, String skuId, Pricing list, int basisPoints, EffectivePeriod effective) {
    return new ContractPrice(orgId, skuId, list.discounted(basisPoints), effective);
  }
}
