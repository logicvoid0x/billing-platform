package com.logicvoid0x.billing.domain.budget;

/**
 * Settled usage of one budget in one rollup window, sent to the home balance ledger as a {@code
 * USAGE_ROLLUP} (about every 5 s per active account). The amount is negative when a late settle
 * corrected an auto-settled hold downwards.
 *
 * @param orgId the org
 * @param budgetId the budget
 * @param region the region of this shard
 * @param amount settled usage in the budget's unit since the previous window
 * @param windowStartMillis end of the previous rollup window (exclusive start), epoch millis
 * @param windowEndMillis end of this window, epoch millis
 */
public record Rollup(
    String orgId,
    String budgetId,
    String region,
    long amount,
    long windowStartMillis,
    long windowEndMillis) {
  /**
   * Business key of the ledger write: org, region, budget and window end. A retried rollup of the
   * same window carries the same key, so the ledger applies it once.
   */
  public String idempotencyKey() {
    return orgId + ":" + region + ":" + budgetId + ":" + windowEndMillis;
  }
}
