package com.logicvoid0x.billing.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logicvoid0x.billing.rating.CatalogRatingLibrary;
import com.logicvoid0x.billing.rating.RatingLibrary;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Size;
import org.junit.jupiter.api.Test;

class InvoiceLinesProperties {
  private static final RatingLibrary GOLDEN =
      new CatalogRatingLibrary(GoldenCases.catalog(), "inv");

  /** Hourly increments charged on the running total (as the rater does) sum to the line exactly. */
  @Property(tries = 300)
  void hourlyIncrementsSumToTheLineRoundedOnce(
      @ForAll @Size(min = 1, max = 60) List<@LongRange(min = 0, max = 200_000_000) Long> hours,
      @ForAll("skus") String sku) {
    String org = sku.equals("sku_out") ? "org_acme" : "org_tier"; // contract price and tiers
    long total = 0;
    long increments = 0;
    for (long q : hours) {
      increments += GOLDEN.chargeCents(org, sku, total, total + q, GoldenCases.HOUR);
      total += q;
    }
    long line = InvoiceLines.amount(GOLDEN, org, sku, total, GoldenCases.HOUR);
    assertThat(InvoiceLines.requireEqualsRated(line, increments)).isEqualTo(increments);
  }

  @net.jqwik.api.Provide
  net.jqwik.api.Arbitrary<String> skus() {
    return net.jqwik.api.Arbitraries.of("sku_out", "sku_tiered");
  }

  @Test
  void theGoldenLinesAndAMismatchIsRefused() {
    assertThat(InvoiceLines.amount(GOLDEN, "org_acme", "sku_out", 40_000_000, GoldenCases.HOUR))
        .isEqualTo(48_000);
    assertThat(
            InvoiceLines.amount(GOLDEN, "org_tier", "sku_tiered", 1_020_000_000, GoldenCases.HOUR))
        .isEqualTo(1_524_000);
    assertThatThrownBy(() -> InvoiceLines.requireEqualsRated(48_000, 47_999))
        .hasMessageContaining("48000 != rated increments 47999");
  }
}
