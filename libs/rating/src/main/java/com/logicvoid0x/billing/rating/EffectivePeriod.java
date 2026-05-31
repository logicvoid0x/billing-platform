package com.logicvoid0x.billing.rating;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * A half-open effective period {@code [from, until)}; {@code until == null} means open-ended.
 * Catalog rows are never edited: a change is a new row with a new period.
 *
 * @param from first instant the row applies, inclusive
 * @param until first instant the row no longer applies, exclusive; {@code null} if open-ended
 */
public record EffectivePeriod(Instant from, Instant until) {
  /** Validates the period. */
  public EffectivePeriod {
    Objects.requireNonNull(from, "from");
    if (until != null && !until.isAfter(from)) {
      throw new IllegalArgumentException("until must be after from");
    }
  }

  /** Open-ended from {@code from}. */
  public static EffectivePeriod from(Instant from) {
    return new EffectivePeriod(from, null);
  }

  /** {@code [from, until)}. */
  public static EffectivePeriod between(Instant from, Instant until) {
    return new EffectivePeriod(from, Objects.requireNonNull(until, "until"));
  }

  /** Whether {@code at} falls in the period. */
  public boolean contains(Instant at) {
    return !at.isBefore(from) && (until == null || at.isBefore(until));
  }

  /** Whether the two periods share any instant. */
  public boolean overlaps(EffectivePeriod other) {
    boolean thisEndsBefore = until != null && !until.isAfter(other.from);
    boolean otherEndsBefore = other.until != null && !other.until.isAfter(from);
    return !thisEndsBefore && !otherEndsBefore;
  }

  /** Whether both ends fall on hour boundaries (required for prices: one price per hour row). */
  public boolean isHourAligned() {
    return isHour(from) && (until == null || isHour(until));
  }

  static boolean isHour(Instant t) {
    return t.truncatedTo(ChronoUnit.HOURS).equals(t);
  }
}
