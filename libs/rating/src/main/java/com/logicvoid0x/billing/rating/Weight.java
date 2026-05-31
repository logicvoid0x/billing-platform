package com.logicvoid0x.billing.rating;

import com.logicvoid0x.billing.money.Cents.Rate;
import java.util.Objects;

/**
 * Allowance weight-book row: usage units per quantity for a SKU, as an integer ratio. For example
 * {@code Rate(5, 1)} is 5 usage units per token and {@code Rate(1, 10)} is one unit per 10 tokens.
 * The same half-even, once-per-running-total rounding as money applies.
 *
 * @param skuId the SKU
 * @param unitsPer usage units ({@code cents} field) per {@code perUnits} quantity
 * @param effective when the weight applies
 */
public record Weight(String skuId, Rate unitsPer, EffectivePeriod effective) {
  /** Validates the weight. */
  public Weight {
    Strings.require("skuId", skuId);
    Objects.requireNonNull(unitsPer, "unitsPer");
    Objects.requireNonNull(effective, "effective");
  }
}
