package com.logicvoid0x.billing.payments;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
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
 * days, so a fixed bug can still be replayed), 200 otherwise, including replays. A failed top-up
 * post answers 500, so Stripe retries. Behind {@code payments.enabled}: it needs the webhook secret
 * and the balance ledger ({@code payments.ledger-url}).
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
    StripeWebhook stripeWebhook(
        @Value("${payments.stripe.webhook-secret}") String secret,
        @Value("${payments.ledger-url}") String ledgerUrl) {
      if (secret.isBlank()) {
        throw new IllegalStateException("payments.enabled needs payments.stripe.webhook-secret");
      }
      return new StripeWebhook(
          new StripeSignature(secret, StripeSignature.DEFAULT_TOLERANCE),
          new HttpTopUps(URI.create(ledgerUrl), Duration.ofSeconds(5)));
    }
  }
}
