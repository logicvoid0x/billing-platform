package com.logicvoid0x.billing.gateway;

import com.logicvoid0x.billing.admission.BudgetRef;
import com.logicvoid0x.billing.domain.BudgetUnit;
import com.logicvoid0x.billing.domain.MeteredLine;
import com.logicvoid0x.billing.domain.RequestUsage;
import com.logicvoid0x.billing.rating.RatingLibrary;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prices holds and settlements on this gateway host with the shared rating library.
 *
 * <p>Money is never rounded per request: the host keeps a running quantity per (org, SKU, month)
 * and charges each settled request as {@code F(after) - F(before)}, so a host's charges telescope.
 * Drift against the rated ledger is closed by the hourly true-up. A hold is the charge for the
 * worst case on top of the current running total, plus 1 cent per SKU, because concurrent requests
 * move the running total ({@code Cents.hold}). Allowance budgets are held and settled in usage
 * units from the weight book.
 */
final class UsagePricer {
  private final Map<String, Long> running = new ConcurrentHashMap<>();

  /** Holds per budget for {@code usage} (worst case) on top of the running totals. */
  Map<String, Long> holds(RatingLibrary rating, RequestUsage usage, List<BudgetRef> budgets) {
    Instant hour = usage.eventTime().truncatedTo(ChronoUnit.HOURS);
    long cents = 0;
    long units = 0;
    for (MeteredLine line : rating.resolve(usage, usage.eventTime())) {
      long before = running.getOrDefault(key(usage.orgId(), line.skuId(), hour), 0L);
      long charge =
          rating.chargeCents(
              usage.orgId(), line.skuId(), before, Math.addExact(before, line.quantity()), hour);
      cents = Math.addExact(cents, Math.addExact(charge, 1));
      if (needs(budgets, BudgetUnit.USAGE_UNITS)) {
        units = Math.addExact(units, rating.usageUnits(line.skuId(), line.quantity(), hour));
      }
    }
    return perBudget(budgets, cents, units);
  }

  /** Settles {@code usage}: advances the running totals and returns the actual per budget. */
  Map<String, Long> settle(RatingLibrary rating, RequestUsage usage, List<BudgetRef> budgets) {
    Instant hour = usage.eventTime().truncatedTo(ChronoUnit.HOURS);
    long cents = 0;
    long units = 0;
    for (MeteredLine line : rating.resolve(usage, usage.eventTime())) {
      long[] before = new long[1];
      running.compute(
          key(usage.orgId(), line.skuId(), hour),
          (k, v) -> {
            before[0] = v == null ? 0 : v;
            return Math.addExact(before[0], line.quantity());
          });
      cents =
          Math.addExact(
              cents,
              rating.chargeCents(
                  usage.orgId(),
                  line.skuId(),
                  before[0],
                  Math.addExact(before[0], line.quantity()),
                  hour));
      if (needs(budgets, BudgetUnit.USAGE_UNITS)) {
        units = Math.addExact(units, rating.usageUnits(line.skuId(), line.quantity(), hour));
      }
    }
    return perBudget(budgets, cents, units);
  }

  /** Cents settled for {@code actual} (sum of the money budgets' amount, counted once). */
  static long settledCents(Map<String, Long> actual, List<BudgetRef> budgets) {
    for (BudgetRef b : budgets) {
      if (b.kind().unit() == BudgetUnit.CENTS) {
        return actual.getOrDefault(b.budgetId(), 0L);
      }
    }
    return 0;
  }

  /** Only price in a unit some budget of the request is counted in. */
  private static boolean needs(List<BudgetRef> budgets, BudgetUnit unit) {
    return budgets.stream().anyMatch(b -> b.kind().unit() == unit);
  }

  private static Map<String, Long> perBudget(List<BudgetRef> budgets, long cents, long units) {
    Map<String, Long> out = new HashMap<>();
    for (BudgetRef b : budgets) {
      out.put(b.budgetId(), b.kind().unit() == BudgetUnit.CENTS ? cents : units);
    }
    return out;
  }

  private static String key(String org, String sku, Instant hour) {
    return org + "|" + sku + "|" + YearMonth.from(hour.atOffset(ZoneOffset.UTC));
  }
}
