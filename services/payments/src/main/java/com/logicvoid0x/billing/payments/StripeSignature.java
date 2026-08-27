package com.logicvoid0x.billing.payments;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies the {@code Stripe-Signature} header of a webhook: {@code t=<unix seconds>,v1=<hex
 * HMAC-SHA256 of "t.payload" with the endpoint secret>}, possibly with several {@code v1} entries
 * during secret rotation. The timestamp must be within the tolerance, so a captured request cannot
 * be replayed later, and signatures are compared in constant time.
 */
public final class StripeSignature {
  /** Stripe's default tolerance. */
  public static final Duration DEFAULT_TOLERANCE = Duration.ofMinutes(5);

  /** A webhook that failed verification. */
  public static final class InvalidSignatureException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    InvalidSignatureException(String message) {
      super(message);
    }
  }

  private final byte[] secret;
  private final Duration tolerance;

  /** Creates the verifier for an endpoint secret ({@code whsec_...}). */
  public StripeSignature(String endpointSecret, Duration tolerance) {
    this.secret =
        Objects.requireNonNull(endpointSecret, "endpointSecret").getBytes(StandardCharsets.UTF_8);
    this.tolerance = Objects.requireNonNull(tolerance, "tolerance");
  }

  /** Throws unless {@code header} signs {@code payload} within the tolerance of {@code now}. */
  public void verify(String payload, String header, Instant now) {
    if (header == null || header.isBlank()) {
      throw new InvalidSignatureException("missing Stripe-Signature header");
    }
    long timestamp = -1;
    java.util.List<String> signatures = new java.util.ArrayList<>();
    for (String part : header.split(",", -1)) {
      int eq = part.indexOf('=');
      if (eq < 0) {
        continue;
      }
      String key = part.substring(0, eq).trim();
      String value = part.substring(eq + 1).trim();
      if (key.equals("t")) {
        try {
          timestamp = Long.parseLong(value);
        } catch (NumberFormatException e) {
          throw new InvalidSignatureException("bad timestamp in Stripe-Signature");
        }
      } else if (key.equals("v1")) {
        signatures.add(value);
      }
    }
    if (timestamp < 0 || signatures.isEmpty()) {
      throw new InvalidSignatureException("Stripe-Signature needs t= and v1=");
    }
    if (Duration.between(Instant.ofEpochSecond(timestamp), now).abs().compareTo(tolerance) > 0) {
      throw new InvalidSignatureException("timestamp outside the tolerance");
    }
    byte[] expected = sign(timestamp, payload);
    for (String s : signatures) {
      byte[] given;
      try {
        given = HexFormat.of().parseHex(s);
      } catch (IllegalArgumentException e) {
        continue;
      }
      if (MessageDigest.isEqual(expected, given)) {
        return;
      }
    }
    throw new InvalidSignatureException("no matching v1 signature");
  }

  /** The v1 signature of {@code payload} at {@code timestamp} (also used by tests). */
  public String header(String payload, long timestamp) {
    return "t=" + timestamp + ",v1=" + HexFormat.of().formatHex(sign(timestamp, payload));
  }

  private byte[] sign(long timestamp, String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException | InvalidKeyException e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }
}
