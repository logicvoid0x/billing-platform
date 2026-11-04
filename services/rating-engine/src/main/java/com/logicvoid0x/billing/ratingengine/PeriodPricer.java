package com.logicvoid0x.billing.ratingengine;

import com.logicvoid0x.billing.money.Cents;
import com.logicvoid0x.billing.rating.Pricing;
import com.logicvoid0x.billing.rating.RatingLibrary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Prices the hours of one (org, billing period) in (hour, SKU) order on per-SKU running totals: the
 * shared core of first rating ({@link HourlyRater}) and re-rating ({@link Rerater}).
 *
 * <ul>
 *   <li>Without a commitment: {@code F(after) - F(before)} with the SKU's pricing (path TIERED).
 *   <li>With one: a flat rate (first tier for graduated pricing), drawing the remaining commitment
 *       down (path COMMIT; design Q8).
 * </ul>
 */
final class PeriodPricer {
  private PeriodPricer() {}

  /** An hour of usage of one SKU. */
  record Usage(String skuId, Instant hour, long quantity) {}

  /** A priced hour. */
  record Priced(
      Usage usage,
      long totalBefore,
      long amountCents,
      long drawdownCents,
      String path,
      String catalogVersion) {}

  /**
   * Prices {@code usages}.
   *
   * @param totalsBefore running quantity per SKU before the first usage (missing = 0)
   * @param commitment the period's commitment in cents, if any
   * @param drawnDown commitment already drawn down before the first usage
   */
  static List<Priced> price(
      String orgId,
      List<Usage> usages,
      Map<String, Long> totalsBefore,
      OptionalLong commitment,
      long drawnDown,
      HourlyRater.Libraries libraries) {
    List<Usage> ordered = new ArrayList<>(usages);
    ordered.sort(Comparator.comparing(Usage::hour).thenComparing(Usage::skuId));
    Map<String, Long> totals = new HashMap<>(totalsBefore);
    long remaining =
        commitment.isPresent() ? Math.subtractExact(commitment.getAsLong(), drawnDown) : 0;
    List<Priced> out = new ArrayList<>(ordered.size());
    for (Usage u : ordered) {
      long before = totals.getOrDefault(u.skuId(), 0L);
      long after = Math.addExact(before, u.quantity());
      totals.put(u.skuId(), after);
      RatingLibrary library = libraries.at(u.hour());
      if (commitment.isPresent()) {
        long cents =
            Cents.charge(before, after, commitRate(library.pricing(orgId, u.skuId(), u.hour())));
        long drawdown = Math.min(cents, Math.max(remaining, 0));
        remaining = Math.subtractExact(remaining, drawdown);
        out.add(new Priced(u, before, cents, drawdown, "COMMIT", library.version()));
      } else {
        long cents = library.chargeCents(orgId, u.skuId(), before, after, u.hour());
        out.add(new Priced(u, before, cents, 0, "TIERED", library.version()));
      }
    }
    return out;
  }

  /** The flat rate a commitment charges at: the price's rate, or its first tier's (Q8). */
  static Cents.Rate commitRate(Pricing pricing) {
    return switch (pricing) {
      case Pricing.Flat f -> f.rate();
      case Pricing.Graduated g -> g.tiers().get(0).rate();
    };
  }
}
