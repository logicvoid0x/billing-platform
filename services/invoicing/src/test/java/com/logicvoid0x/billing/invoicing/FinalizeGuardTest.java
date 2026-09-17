package com.logicvoid0x.billing.invoicing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class FinalizeGuardTest {
  private static final Map<String, Long> RATED = Map.of("k_out", 48_000L, "k_adj", -9_600L);

  @Test
  void aMatchingInvoiceMayBeFinalized() {
    FinalizeGuard.requireFinalizable(new FinalizeGuard.StripeInvoice(RATED, 38_400), RATED);
    assertThat(FinalizeGuard.differences(new FinalizeGuard.StripeInvoice(RATED, 38_400), RATED))
        .isEmpty();
  }

  @Test
  void anyLineOrSubtotalDifferenceBlocksFinalizing() {
    FinalizeGuard.StripeInvoice off =
        new FinalizeGuard.StripeInvoice(Map.of("k_out", 48_001L, "k_extra", 5L), 48_006);
    assertThat(FinalizeGuard.differences(off, RATED))
        .containsExactly(
            "k_adj: missing on the Stripe invoice (expected -9600)",
            "k_extra: on the Stripe invoice (5) but not in the rated ledger",
            "k_out: Stripe 48001 rated 48000",
            "subtotal: Stripe 48006 rated 38400");
    assertThatThrownBy(() -> FinalizeGuard.requireFinalizable(off, RATED))
        .isInstanceOf(IllegalStateException.class);
    assertThat(FinalizeGuard.differences(new FinalizeGuard.StripeInvoice(RATED, 38_401), RATED))
        .as("a subtotal off by one cent")
        .containsExactly("subtotal: Stripe 38401 rated 38400");
  }
}
