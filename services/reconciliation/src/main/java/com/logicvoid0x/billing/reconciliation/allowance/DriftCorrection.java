package com.logicvoid0x.billing.reconciliation.allowance;

import java.time.Instant;

/**
 * A correction of a usage-allowance window counter towards metered usage.
 *
 * @param budgetId the window instance
 * @param delta usage units to add to the shard counter (negative to lower it)
 * @param counterUnits the shard's counter at the check
 * @param meteredUnits metered usage units for the same window instance
 * @param checkedHour the sealed hour the check ran for
 */
public record DriftCorrection(
    String budgetId, long delta, long counterUnits, long meteredUnits, Instant checkedHour) {
  /** Business key: one correction per window instance per checked hour. */
  public String idempotencyKey() {
    return "allowance_drift:" + budgetId + ":" + checkedHour;
  }
}
