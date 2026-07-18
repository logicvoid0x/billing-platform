package com.logicvoid0x.billing.usageaggregator;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * An increment to {@code usage_hourly} for one (org, SKU, event hour): the quantity and line count
 * added since the previous firing of that hour's window. Increments for the same key sum to the
 * hour's total, so the sink can apply them as additions.
 *
 * @param orgId the org
 * @param skuId the SKU
 * @param eventHour start of the event-time hour
 * @param quantity quantity added (in the SKU's meter units)
 * @param lines metered lines added
 */
public record HourlyUsage(String orgId, String skuId, Instant eventHour, long quantity, long lines)
    implements Serializable {
  /** Validates the increment. */
  public HourlyUsage {
    Objects.requireNonNull(orgId, "orgId");
    Objects.requireNonNull(skuId, "skuId");
    Objects.requireNonNull(eventHour, "eventHour");
    if (quantity < 0 || lines < 0) {
      throw new IllegalArgumentException("increments are never negative");
    }
  }

  /** The business key of the row this increment applies to. */
  public String key() {
    return orgId + "|" + skuId + "|" + eventHour;
  }
}
