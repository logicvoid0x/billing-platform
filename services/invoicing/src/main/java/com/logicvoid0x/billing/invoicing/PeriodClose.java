package com.logicvoid0x.billing.invoicing;

import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Billing-period close (design: "The period freezes at close + 24 h; anything later becomes a
 * prior-period adjustment on the next invoice"). Periods are calendar months in UTC, like the
 * rating engine's running totals.
 */
public final class PeriodClose {
  /** How long after its end a period still takes corrections in place. */
  public static final Duration FREEZE_AFTER = Duration.ofHours(24);

  private PeriodClose() {}

  /** Start of the period that contains {@code t}. */
  public static Instant periodStart(Instant t) {
    return YearMonth.from(t.atOffset(ZoneOffset.UTC))
        .atDay(1)
        .atStartOfDay()
        .toInstant(ZoneOffset.UTC);
  }

  /** End (exclusive) of the period that starts at {@code start}. */
  public static Instant periodEnd(Instant start) {
    return start.atOffset(ZoneOffset.UTC).plusMonths(1).toInstant();
  }

  /** True once the period that starts at {@code start} is frozen at {@code now}. */
  public static boolean frozen(Instant start, Instant now) {
    return !now.isBefore(periodEnd(start).plus(FREEZE_AFTER));
  }

  /**
   * Where a correction for the period starting at {@code usagePeriod}, found at {@code now}, is
   * invoiced.
   *
   * @param usagePeriod the period the corrected usage belongs to
   * @param amountCents the correction (signed: a re-rating can lower an amount)
   */
  public record Placement(
      Instant usagePeriod,
      Instant invoicePeriod,
      boolean priorPeriodAdjustment,
      long amountCents) {}

  /** Places a correction: in its own period until it freezes, else on the next invoice to issue. */
  public static Placement place(Instant usagePeriod, long amountCents, Instant now) {
    Objects.requireNonNull(usagePeriod, "usagePeriod");
    Instant start = periodStart(usagePeriod);
    if (!frozen(start, now)) {
      return new Placement(start, start, false, amountCents);
    }
    // The next invoice to be issued: the previous period if it is still inside its 24 h, else the
    // current one.
    Instant open = periodStart(now);
    Instant previous = periodStart(open.minusSeconds(1));
    if (previous.isAfter(start) && !frozen(previous, now)) {
      open = previous;
    }
    return new Placement(start, open, true, amountCents);
  }
}
