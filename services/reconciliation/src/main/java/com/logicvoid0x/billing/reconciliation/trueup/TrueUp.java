package com.logicvoid0x.billing.reconciliation.trueup;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;

/**
 * The hourly credit true-up (design seam 4: "Rated amount minus recognized usage, per
 * account-hour", key (org, hour, rating_version), "Posted once per rating version"). The gap
 * between what the rated ledger says the hour cost (every version up to this one) and the usage the
 * balance ledger recognized for it (rollups plus earlier true-ups) is posted as one signed TRUE_UP.
 * A later re-rating is a new version, so its correction is one more true-up, never an edit. Long
 * cents only.
 */
public final class TrueUp {
  /** The rated ledger's total for an (org, hour), summed over versions up to {@code version}. */
  @FunctionalInterface
  public interface RatedCents {
    /** Rated cents of the org's hour, all versions up to and including {@code version}. */
    long upTo(String orgId, Instant hour, int version);
  }

  /** The balance ledger's recognized usage for an (org, hour): rollups plus earlier true-ups. */
  @FunctionalInterface
  public interface RecognizedCents {
    /** Recognized cents of the org's hour. */
    long of(String orgId, Instant hour);
  }

  /** Posts a TRUE_UP to the balance ledger, idempotent on the key. */
  @FunctionalInterface
  public interface Poster {
    /**
     * Posts the signed gap.
     *
     * @return true if posted now, false if the key was already posted
     */
    boolean post(String key, String orgId, long gapCents, Instant hour);
  }

  /** A posted true-up. */
  public record Posted(String key, long gapCents) {}

  private final RatedCents rated;
  private final RecognizedCents recognized;
  private final Poster poster;

  /** Creates the true-up. */
  public TrueUp(RatedCents rated, RecognizedCents recognized, Poster poster) {
    this.rated = Objects.requireNonNull(rated, "rated");
    this.recognized = Objects.requireNonNull(recognized, "recognized");
    this.poster = Objects.requireNonNull(poster, "poster");
  }

  /** The key of the true-up for (org, hour, version). */
  public static String key(String orgId, Instant hour, int version) {
    return orgId + ":" + hour.truncatedTo(ChronoUnit.HOURS) + ":" + version;
  }

  /** Trues up one account-hour at {@code version}; empty when there is no gap or it was posted. */
  public Optional<Posted> trueUp(String orgId, Instant hour, int version) {
    Instant h = hour.truncatedTo(ChronoUnit.HOURS);
    long gap = Math.subtractExact(rated.upTo(orgId, h, version), recognized.of(orgId, h));
    if (gap == 0) {
      return Optional.empty();
    }
    String key = key(orgId, h, version);
    return poster.post(key, orgId, gap, h) ? Optional.of(new Posted(key, gap)) : Optional.empty();
  }
}
