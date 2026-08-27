package com.logicvoid0x.billing.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StripeWebhookTest {
  private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
  private final StripeSignature signature =
      new StripeSignature("whsec_test", Duration.ofMinutes(5));

  /** An idempotent ledger stand-in: one balance per org, one post per key. */
  private final Map<String, Long> posted = new HashMap<>();

  private final Map<String, Long> balance = new HashMap<>();
  private final StripeWebhook webhook =
      new StripeWebhook(
          signature,
          (key, org, cents, at) -> {
            if (posted.putIfAbsent(key, cents) != null) {
              return false;
            }
            balance.merge(org, cents, Long::sum);
            return true;
          });

  private static String succeeded(String id, String org, long cents, String currency) {
    return "{\"id\":\"evt_1\",\"type\":\"payment_intent.succeeded\",\"created\":"
        + NOW.getEpochSecond()
        + ",\"data\":{\"object\":{\"id\":\""
        + id
        + "\",\"amount_received\":"
        + cents
        + ",\"currency\":\""
        + currency
        + "\",\"metadata\":{\"org_id\":\""
        + org
        + "\"}}}}";
  }

  private String signed(String payload) {
    return signature.header(payload, NOW.getEpochSecond());
  }

  @Test
  void aSucceededPaymentTopsUpOnceAndReplaysChangeNothing() {
    String payload = succeeded("pi_1", "org_acme", 2_500, "usd");
    assertThat(webhook.handle(payload, signed(payload), NOW))
        .isEqualTo(StripeWebhook.Outcome.TOPPED_UP);
    assertThat(webhook.handle(payload, signed(payload), NOW.plusSeconds(60)))
        .as("Stripe retry")
        .isEqualTo(StripeWebhook.Outcome.DUPLICATE);
    assertThat(balance).containsExactly(Map.entry("org_acme", 2_500L));
    assertThat(posted).containsOnlyKeys("stripe:pi_1");
  }

  @Test
  void badSignaturesAreRejectedBeforeAnythingIsPosted() {
    String payload = succeeded("pi_2", "org_acme", 100, "usd");
    assertThatThrownBy(() -> webhook.handle(payload, "t=1,v1=00", NOW))
        .isInstanceOf(StripeSignature.InvalidSignatureException.class);
    assertThatThrownBy(() -> webhook.handle(payload + " ", signed(payload), NOW))
        .as("payload altered")
        .isInstanceOf(StripeSignature.InvalidSignatureException.class);
    assertThatThrownBy(() -> webhook.handle(payload, signed(payload), NOW.plusSeconds(301)))
        .as("outside the 5-minute tolerance")
        .hasMessageContaining("tolerance");
    assertThatThrownBy(() -> webhook.handle(payload, null, NOW))
        .isInstanceOf(StripeSignature.InvalidSignatureException.class);
    assertThat(posted).isEmpty();
  }

  @Test
  void rotatedSecretsMayCarrySeveralSignatures() {
    String payload = succeeded("pi_3", "org_rot", 700, "usd");
    String header = signed(payload) + ",v1=" + "ab".repeat(32);
    assertThat(webhook.handle(payload, header, NOW)).isEqualTo(StripeWebhook.Outcome.TOPPED_UP);
  }

  @Test
  void otherEventsAreIgnoredAndMalformedTopUpsRejected() {
    String other = "{\"type\":\"customer.created\",\"data\":{\"object\":{}}}";
    assertThat(webhook.handle(other, signed(other), NOW)).isEqualTo(StripeWebhook.Outcome.IGNORED);
    String eur = succeeded("pi_4", "org_eu", 100, "eur");
    assertThatThrownBy(() -> webhook.handle(eur, signed(eur), NOW)).hasMessageContaining("USD");
    String noOrg = succeeded("pi_5", "", 100, "usd");
    assertThatThrownBy(() -> webhook.handle(noOrg, signed(noOrg), NOW))
        .hasMessageContaining("org_id");
    assertThat(posted).isEmpty();
  }
}
