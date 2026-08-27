package com.logicvoid0x.billing.payments;

import java.time.Instant;
import java.util.Objects;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * The Stripe webhook (design: "the payment webhook writes a TOP_UP", "idempotent on payment ID").
 * The signature is verified first. A {@code payment_intent.succeeded} in USD credits the org named
 * in its metadata ({@code org_id}) with {@code amount_received} cents, keyed by the payment intent
 * ID. Stripe retries webhooks, so a replay is acknowledged and changes nothing. Other event types
 * are acknowledged and ignored.
 */
public final class StripeWebhook {
  /** Where top-ups are posted: the home balance ledger, idempotent on the key. */
  @FunctionalInterface
  public interface TopUps {
    /**
     * Posts a top-up.
     *
     * @return true if posted now, false if this key was already posted
     */
    boolean topUp(String key, String orgId, long cents, Instant at);
  }

  /** What the webhook did with an event. */
  public enum Outcome {
    /** A top-up was posted. */
    TOPPED_UP,
    /** The event was already applied (a Stripe retry). */
    DUPLICATE,
    /** Not a top-up event. */
    IGNORED
  }

  private static final ObjectMapper JSON = JsonMapper.builder().build();

  private final StripeSignature signature;
  private final TopUps topUps;

  /** Creates the webhook. */
  public StripeWebhook(StripeSignature signature, TopUps topUps) {
    this.signature = Objects.requireNonNull(signature, "signature");
    this.topUps = Objects.requireNonNull(topUps, "topUps");
  }

  /** The ledger key of a Stripe payment. */
  public static String key(String paymentIntentId) {
    return "stripe:" + paymentIntentId;
  }

  /**
   * Handles one delivery.
   *
   * @throws StripeSignature.InvalidSignatureException if the signature does not verify
   * @throws IllegalArgumentException if a top-up event is malformed
   */
  public Outcome handle(String payload, String signatureHeader, Instant now) {
    signature.verify(payload, signatureHeader, now);
    JsonNode event = JSON.readTree(payload);
    if (!"payment_intent.succeeded".equals(event.path("type").asString(""))) {
      return Outcome.IGNORED;
    }
    JsonNode intent = event.path("data").path("object");
    String id = intent.path("id").asString("");
    String org = intent.path("metadata").path("org_id").asString("");
    String currency = intent.path("currency").asString("");
    if (id.isBlank() || org.isBlank()) {
      throw new IllegalArgumentException("payment_intent.succeeded without id or metadata.org_id");
    }
    if (!currency.equals("usd")) {
      throw new IllegalArgumentException("only USD top-ups are supported, got " + currency);
    }
    JsonNode amount = intent.path("amount_received");
    if (!amount.isIntegralNumber() || amount.asLong() <= 0) {
      throw new IllegalArgumentException("amount_received must be a positive integer of cents");
    }
    Instant at = Instant.ofEpochSecond(event.path("created").asLong(now.getEpochSecond()));
    return topUps.topUp(key(id), org, amount.asLong(), at) ? Outcome.TOPPED_UP : Outcome.DUPLICATE;
  }
}
