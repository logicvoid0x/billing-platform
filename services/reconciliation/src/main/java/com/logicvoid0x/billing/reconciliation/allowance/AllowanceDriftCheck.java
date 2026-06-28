package com.logicvoid0x.billing.reconciliation.allowance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

/**
 * Hourly allowance reconciliation (design: "window counters vs metered usage in units; correct only
 * when drift exceeds about 1%"). The shard's counters are fast estimates; metered usage units
 * (usage_hourly through the weight book) are the truth. A counter drifting more than the threshold
 * is moved to the metered value; small drift is left alone because it costs compute, not cash.
 * Integer math only: drift is over the threshold when {@code |counter - metered| * 10,000 >
 * thresholdBasisPoints * metered}.
 */
public final class AllowanceDriftCheck {
  /** Writes one correction to the shard; returns false if its key was already applied. */
  @FunctionalInterface
  public interface Adjuster {
    boolean adjust(String budgetId, long delta, String key);
  }

  private final int thresholdBasisPoints;

  /**
   * Creates the check.
   *
   * @param thresholdBasisPoints drift tolerated before correcting (100 = 1%)
   */
  public AllowanceDriftCheck(int thresholdBasisPoints) {
    if (thresholdBasisPoints < 0) {
      throw new IllegalArgumentException("threshold must be >= 0 basis points");
    }
    this.thresholdBasisPoints = thresholdBasisPoints;
  }

  /** The design's 1% threshold. */
  public static AllowanceDriftCheck standard() {
    return new AllowanceDriftCheck(100);
  }

  /**
   * Corrections for one checked hour, ordered by budget ID.
   *
   * @param counters shard counter per window instance, in usage units
   * @param metered metered usage units per window instance
   * @param checkedHour the sealed hour this check covers; part of each correction's key
   */
  public List<DriftCorrection> corrections(
      Map<String, Long> counters, Map<String, Long> metered, Instant checkedHour) {
    Objects.requireNonNull(checkedHour, "checkedHour");
    TreeSet<String> budgets = new TreeSet<>(counters.keySet());
    budgets.addAll(metered.keySet());
    List<DriftCorrection> out = new ArrayList<>();
    for (String budgetId : budgets) {
      long counter = counters.getOrDefault(budgetId, 0L);
      long truth = metered.getOrDefault(budgetId, 0L);
      if (exceedsThreshold(counter, truth)) {
        out.add(
            new DriftCorrection(
                budgetId, Math.subtractExact(truth, counter), counter, truth, checkedHour));
      }
    }
    return List.copyOf(out);
  }

  private boolean exceedsThreshold(long counter, long truth) {
    long drift = Math.abs(Math.subtractExact(counter, truth));
    if (drift == 0) {
      return false;
    }
    return Math.multiplyExact(drift, 10_000L)
        > Math.multiplyExact((long) thresholdBasisPoints, truth);
  }

  /** Applies corrections idempotently; returns how many were applied now (not replays). */
  public static int apply(List<DriftCorrection> corrections, Adjuster adjuster) {
    int applied = 0;
    for (DriftCorrection c : corrections) {
      if (adjuster.adjust(c.budgetId(), c.delta(), c.idempotencyKey())) {
        applied++;
      }
    }
    return applied;
  }
}
