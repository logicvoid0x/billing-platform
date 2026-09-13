package com.logicvoid0x.billing.reconciliation.stripe;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StripeChecksTest {
  @Test
  void invoiceLinesMustEqualTheRatedLedgerPerSku() {
    assertThat(
            StripeChecks.invoiceMismatches(Map.of("sku_out", 48_000L), Map.of("sku_out", 48_000L)))
        .isEmpty();
    assertThat(
            StripeChecks.invoiceMismatches(
                Map.of("sku_out", 48_000L, "sku_in", 10L),
                Map.of("sku_out", 38_400L, "sku_cache", 5L)))
        .containsExactly(
            "sku_cache: invoiced 0 rated 5",
            "sku_in: invoiced 10 rated 0",
            "sku_out: invoiced 48000 rated 38400");
  }

  @Test
  void everyPaymentHasItsTopUpAndNothingElse() {
    assertThat(
            StripeChecks.paymentMismatches(
                Map.of("pi_1", 2_500L, "pi_2", 100L, "pi_3", 700L),
                Map.of("pi_1", 2_500L, "pi_3", 600L, "pi_9", 50L)))
        .containsExactly(
            "pi_2: payment 100 without a TOP_UP (webhook lost; replay it)",
            "pi_3: payment 700 but TOP_UP 600",
            "pi_9: TOP_UP 50 without a Stripe payment");
  }
}
