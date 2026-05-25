package com.logicvoid0x.billing.domain;

import java.time.Instant;

/**
 * Budget IDs from the design's data model, e.g. {@code org_42:balance}, {@code ws_7:limit:2026-11},
 * {@code user_9:5h:<start>}. Each period or window instance is a new ID.
 */
public final class BudgetIds {
  private BudgetIds() {}

  /** The prepaid balance of an org. */
  public static String balance(String orgId) {
    return Ids.requireSegment("orgId", orgId) + ":balance";
  }

  /** A spend limit of an org, workspace or API key for one period, e.g. {@code 2026-11}. */
  public static String spendLimit(String ownerId, String period) {
    return Ids.requireSegment("ownerId", ownerId)
        + ":limit:"
        + Ids.requireSegment("period", period);
  }

  /** One instance of a usage-allowance window, named by its kind (e.g. {@code 5h}) and start. */
  public static String window(String ownerId, String windowName, Instant instanceStart) {
    return Ids.requireSegment("ownerId", ownerId)
        + ":"
        + Ids.requireSegment("windowName", windowName)
        + ":"
        + instanceStart;
  }
}
