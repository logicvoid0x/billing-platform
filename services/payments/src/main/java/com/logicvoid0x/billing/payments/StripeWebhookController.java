package com.logicvoid0x.billing.payments;

import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /webhooks/stripe} (the Stripe CLI forwards here in the local stack). The raw body is
 * verified as received: 400 on a bad signature or a malformed top-up (Stripe keeps retrying for
 * days, so a fixed bug can still be replayed), 200 otherwise, including replays. Behind {@code
 * payments.enabled}: the top-up sink needs a balance-ledger API (open question Q10).
 */
@RestController
@ConditionalOnProperty(name = "payments.enabled", havingValue = "true")
public class StripeWebhookController {
  private final StripeWebhook webhook;
  private final Clock clock = Clock.systemUTC();

  /** Creates the controller. */
  public StripeWebhookController(StripeWebhook webhook) {
    this.webhook = webhook;
  }

  /** One webhook delivery. */
  @PostMapping("/webhooks/stripe")
  public ResponseEntity<String> stripe(
      @RequestBody String payload,
      @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
    try {
      return ResponseEntity.ok(webhook.handle(payload, signature, clock.instant()).name());
    } catch (StripeSignature.InvalidSignatureException | IllegalArgumentException e) {
      return ResponseEntity.badRequest().body(e.getMessage());
    }
  }

  /** Wiring of the webhook. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnProperty(name = "payments.enabled", havingValue = "true")
  static class Wiring {
    @Bean
    StripeWebhook stripeWebhook(@Value("${payments.stripe.webhook-secret}") String secret) {
      return new StripeWebhook(
          new StripeSignature(secret, StripeSignature.DEFAULT_TOLERANCE), topUps());
    }

    private static StripeWebhook.TopUps topUps() {
      throw new IllegalStateException(
          "payments.enabled needs a balance-ledger top-up API (open question Q10)");
    }
  }
}
