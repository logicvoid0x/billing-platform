package com.logicvoid0x.billing.usageaggregator;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

/**
 * An increment to the hourly usage of one (org, workspace, API key, SKU, event hour): the quantity
 * and line count added since the previous firing of that hour's window. Increments for the same key
 * sum to the hour's total, so the sink can apply them as additions. {@code usage_hourly} adds them
 * per (org, SKU, hour); {@code usage_hourly_by_key} keeps workspace and key for the usage view
 * (design Q12, option b).
 *
 * @param orgId the org
 * @param workspaceId the workspace
 * @param apiKeyId the API key
 * @param skuId the SKU
 * @param eventHour start of the event-time hour
 * @param quantity quantity added (in the SKU's meter units)
 * @param lines metered lines added
 */
public record HourlyUsage(
    String orgId,
    String workspaceId,
    String apiKeyId,
    String skuId,
    Instant eventHour,
    long quantity,
    long lines)
    implements Serializable {
  /** Validates the increment. */
  public HourlyUsage {
    Objects.requireNonNull(orgId, "orgId");
    Objects.requireNonNull(workspaceId, "workspaceId");
    Objects.requireNonNull(apiKeyId, "apiKeyId");
    Objects.requireNonNull(skuId, "skuId");
    Objects.requireNonNull(eventHour, "eventHour");
    if (quantity < 0 || lines < 0) {
      throw new IllegalArgumentException("increments are never negative");
    }
  }

  /** The business key of the by-key row this increment applies to. */
  public String key() {
    return key(orgId, workspaceId, apiKeyId, skuId) + "\u0000" + eventHour;
  }

  /** Window key of an (org, workspace, key, SKU); fields are separated by NUL. */
  public static String key(String orgId, String workspaceId, String apiKeyId, String skuId) {
    return orgId + "\u0000" + workspaceId + "\u0000" + apiKeyId + "\u0000" + skuId;
  }

  /** This increment plus {@code other} (same key). */
  public HourlyUsage plus(HourlyUsage other) {
    if (!key().equals(other.key())) {
      throw new IllegalArgumentException("different keys: " + this + " + " + other);
    }
    return new HourlyUsage(
        orgId,
        workspaceId,
        apiKeyId,
        skuId,
        eventHour,
        Math.addExact(quantity, other.quantity),
        Math.addExact(lines, other.lines));
  }
}
