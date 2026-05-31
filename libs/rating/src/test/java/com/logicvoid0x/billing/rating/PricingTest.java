package com.logicvoid0x.billing.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.money.Cents;
import com.logicvoid0x.billing.money.Cents.Rate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PricingTest {
  private static final long B = 1_000_000_000L;

  /** $15/M up to 1B tokens, then $12/M. */
  private static final Pricing TIERED =
      Pricing.graduated(
          List.of(
              new Pricing.Tier(B, new Rate(1500, 1_000_000)),
              new Pricing.Tier(Long.MAX_VALUE, new Rate(1200, 1_000_000))));

  @Test
  void flatPricingIsForTotal() {
    Pricing flat = Pricing.flat(new Rate(1500, 1_000_000));
    assertThat(flat.totalCents(1_000)).isEqualTo(Cents.forTotal(1_000, new Rate(1500, 1_000_000)));
    assertThat(flat.flatRate()).contains(new Rate(1500, 1_000_000));
  }

  @Test
  void graduatedTiersChargeEachPortionAtItsRate() {
    assertThat(TIERED.totalCents(B)).isEqualTo(1_500_000);
    assertThat(TIERED.totalCents(B + 1_000_000)).isEqualTo(1_501_200);
    // 30M arriving at 990M: 10M at $15/M + 20M at $12/M = 15,000 + 24,000 cents.
    assertThat(TIERED.charge(990_000_000, 1_020_000_000)).isEqualTo(39_000);
    assertThat(TIERED.flatRate()).isEmpty();
  }

  @Test
  void graduatedTotalIsRoundedOnceAcrossTiers() {
    // Two tiers each contributing half a cent: rounded once, 1.0 cent, not 0 + 0 or 1 + 1.
    Pricing p =
        Pricing.graduated(
            List.of(
                new Pricing.Tier(1, new Rate(1, 2)),
                new Pricing.Tier(Long.MAX_VALUE, new Rate(1, 2))));
    assertThat(p.totalCents(2)).isEqualTo(1);
  }

  @Test
  void tiersMustBeOrderedOpenEndedAndShareTheirPerUnits() {
    Rate r = new Rate(1, 1);
    assertThatThrownBy(() -> Pricing.graduated(List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> Pricing.graduated(List.of(new Pricing.Tier(10, r), new Pricing.Tier(5, r))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Pricing.graduated(List.of(new Pricing.Tier(10, r))))
        .as("last tier must be open-ended")
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() ->
            Pricing.graduated(
                List.of(
                    new Pricing.Tier(10, new Rate(1, 1)),
                    new Pricing.Tier(Long.MAX_VALUE, new Rate(1, 10)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("perUnits");
  }

  @Test
  void discountAppliesToEveryTier() {
    Pricing discounted = TIERED.discounted(2000);
    assertThat(discounted.totalCents(B)).isEqualTo(1_200_000);
    assertThat(Pricing.flat(new Rate(1500, 1_000_000)).discounted(2000).flatRate())
        .contains(new Rate(1200, 1_000_000));
  }

  @Test
  void chargeRejectsAShrinkingTotal() {
    assertThatThrownBy(() -> TIERED.charge(10, 5)).isInstanceOf(IllegalArgumentException.class);
  }
}
