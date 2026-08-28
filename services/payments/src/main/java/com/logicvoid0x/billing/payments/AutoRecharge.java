package com.logicvoid0x.billing.payments;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto-recharge for prepaid accounts (design: "Spendable credit falls below the customer's
 * threshold: TOP_UP when the async charge succeeds (key: org ID + recharge sequence)"; "None
 * needed: if the charge fails, the account spends down to $0").
 *
 * <ul>
 *   <li>At most one charge per org is in flight; checks while it runs do nothing.
 *   <li>Each attempt has the next recharge sequence. Its key {@code org:recharge:<seq>} is both the
 *       payment provider's idempotency key and the TOP_UP key, so a retried charge or a replayed
 *       success posts once.
 *   <li>A failed charge posts nothing and leaves enforcement untouched. The next attempt waits
 *       {@code retryAfter} and uses a new sequence.
 * </ul>
 *
 * <p>The sequence must survive restarts, so a crash cannot reuse a key for a different charge;
 * {@link Sequences} is the persistence point.
 */
public final class AutoRecharge {
  /** Charges the customer's saved payment method (Stripe off-session PaymentIntent). */
  @FunctionalInterface
  public interface Charger {
    /** Charges {@code cents}; completes true on success, false on a declined charge. */
    CompletableFuture<Boolean> charge(String idempotencyKey, String orgId, long cents);
  }

  /** Durable recharge sequences per org. */
  @FunctionalInterface
  public interface Sequences {
    /** Allocates the next sequence for {@code orgId} (1, 2, ...), durably. */
    long next(String orgId);
  }

  /** An org's recharge settings, in cents. */
  public record Settings(long thresholdCents, long amountCents) {
    /** Validates the settings. */
    public Settings {
      if (thresholdCents < 0 || amountCents <= 0) {
        throw new IllegalArgumentException("threshold must be >= 0 and amount > 0");
      }
    }
  }

  /** What a check did. */
  public enum Outcome {
    /** Spendable credit is at or above the threshold. */
    NOT_NEEDED,
    /** A charge for this org is already running. */
    IN_FLIGHT,
    /** The last charge failed recently; waiting before the next attempt. */
    BACKING_OFF,
    /** A charge was started. */
    STARTED
  }

  private record State(boolean inFlight, Instant retryAt) {}

  private final Charger charger;
  private final StripeWebhook.TopUps topUps;
  private final Sequences sequences;
  private final Duration retryAfter;
  private final Map<String, State> states = new ConcurrentHashMap<>();

  /** Creates the auto-recharger. */
  public AutoRecharge(
      Charger charger, StripeWebhook.TopUps topUps, Sequences sequences, Duration retryAfter) {
    this.charger = Objects.requireNonNull(charger, "charger");
    this.topUps = Objects.requireNonNull(topUps, "topUps");
    this.sequences = Objects.requireNonNull(sequences, "sequences");
    this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter");
  }

  /** The key of an org's recharge attempt. */
  public static String key(String orgId, long sequence) {
    return orgId + ":recharge:" + sequence;
  }

  /**
   * Checks one org after its spendable credit changed.
   *
   * @return what happened; with STARTED, the charge completes asynchronously
   */
  public Outcome check(String orgId, long spendableCents, Settings settings, Instant now) {
    if (spendableCents >= settings.thresholdCents()) {
      return Outcome.NOT_NEEDED;
    }
    Outcome[] outcome = new Outcome[1];
    states.compute(
        orgId,
        (k, s) -> {
          if (s != null && s.inFlight()) {
            outcome[0] = Outcome.IN_FLIGHT;
            return s;
          }
          if (s != null && s.retryAt() != null && now.isBefore(s.retryAt())) {
            outcome[0] = Outcome.BACKING_OFF;
            return s;
          }
          outcome[0] = Outcome.STARTED;
          return new State(true, null);
        });
    if (outcome[0] != Outcome.STARTED) {
      return outcome[0];
    }
    String key = key(orgId, sequences.next(orgId));
    var unused =
        charger
            .charge(key, orgId, settings.amountCents())
            .handle((ok, error) -> {
              boolean success = error == null && Boolean.TRUE.equals(ok);
              if (success) {
                topUps.topUp(key, orgId, settings.amountCents(), now);
              }
              states.put(orgId, new State(false, success ? null : now.plus(retryAfter)));
              return null;
            });
    return Outcome.STARTED;
  }
}
