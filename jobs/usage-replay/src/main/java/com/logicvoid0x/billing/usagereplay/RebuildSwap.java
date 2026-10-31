package com.logicvoid0x.billing.usagereplay;

import com.logicvoid0x.billing.usageaggregator.HourlyUsage;
import com.logicvoid0x.billing.usageaggregator.sink.Jdbc;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeMap;

/**
 * Writes a replay into {@code metering.usage_hourly_rebuild} and swaps it into {@code
 * usage_hourly}; the same hours of {@code usage_hourly_by_key} are replaced too (Q12 b). The swap
 * deletes the replayed hours from {@code usage_hourly} and inserts the rebuilt rows in one
 * transaction, so readers see either the old hours or the new ones, never a mix. Replaying the same
 * hours again rebuilds identical rows.
 */
public final class RebuildSwap {
  private RebuildSwap() {}

  /**
   * Replaces the rebuild rows of {@code hours} with {@code rows} (all of which must fall in those
   * hours), then swaps those hours into {@code usage_hourly}, all in one transaction.
   *
   * @return rows now in usage_hourly for the replayed hours
   */
  public static int replace(
      Jdbc jdbc, String replayId, SortedSet<Instant> hours, Collection<HourlyUsage> rows)
      throws SQLException {
    for (HourlyUsage r : rows) {
      if (!hours.contains(r.eventHour())) {
        throw new IllegalArgumentException("row outside the replayed hours: " + r);
      }
    }
    try (Connection c = jdbc.connect()) {
      c.setAutoCommit(false);
      try {
        Array hourArray =
            c.createArrayOf("timestamptz", hours.stream().map(Timestamp::from).toArray());
        exec(c, "DELETE FROM metering.usage_hourly_rebuild WHERE event_hour = ANY (?)", hourArray);
        // usage_hourly is per (org, SKU, hour): sum the rows over workspace and key.
        Map<String, long[]> bySku = new TreeMap<>();
        for (HourlyUsage r : rows) {
          long[] t =
              bySku.computeIfAbsent(
                  r.orgId() + "\u0000" + r.skuId() + "\u0000" + r.eventHour(), k -> new long[2]);
          t[0] = Math.addExact(t[0], r.quantity());
          t[1] = Math.addExact(t[1], r.lines());
        }
        try (PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO metering.usage_hourly_rebuild"
                    + " (org_id, sku_id, event_hour, quantity, lines, replay_id)"
                    + " VALUES (?, ?, ?, ?, ?, ?)")) {
          for (Map.Entry<String, long[]> e : bySku.entrySet()) {
            String[] k = e.getKey().split("\u0000", -1);
            s.setString(1, k[0]);
            s.setString(2, k[1]);
            s.setTimestamp(3, Timestamp.from(Instant.parse(k[2])));
            s.setLong(4, e.getValue()[0]);
            s.setLong(5, e.getValue()[1]);
            s.setString(6, replayId);
            s.addBatch();
          }
          s.executeBatch();
        }
        // The usage view's rollup is replaced for the same hours in the same transaction (Q12 b).
        exec(c, "DELETE FROM metering.usage_hourly_by_key WHERE event_hour = ANY (?)", hourArray);
        try (PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO metering.usage_hourly_by_key (org_id, workspace_id, api_key_id,"
                    + " sku_id, event_hour, quantity, lines) VALUES (?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT (org_id, workspace_id, api_key_id, sku_id, event_hour)"
                    + " DO UPDATE SET quantity = usage_hourly_by_key.quantity + EXCLUDED.quantity,"
                    + " lines = usage_hourly_by_key.lines + EXCLUDED.lines")) {
          for (HourlyUsage r : rows) {
            s.setString(1, r.orgId());
            s.setString(2, r.workspaceId());
            s.setString(3, r.apiKeyId());
            s.setString(4, r.skuId());
            s.setTimestamp(5, Timestamp.from(r.eventHour()));
            s.setLong(6, r.quantity());
            s.setLong(7, r.lines());
            s.addBatch();
          }
          s.executeBatch();
        }
        exec(c, "DELETE FROM metering.usage_hourly WHERE event_hour = ANY (?)", hourArray);
        int swapped =
            exec(
                c,
                "INSERT INTO metering.usage_hourly (org_id, sku_id, event_hour, quantity, lines)"
                    + " SELECT org_id, sku_id, event_hour, quantity, lines"
                    + " FROM metering.usage_hourly_rebuild WHERE event_hour = ANY (?)",
                hourArray);
        c.commit();
        return swapped;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    }
  }

  private static int exec(Connection c, String sql, Array hours) throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      s.setArray(1, hours);
      return s.executeUpdate();
    }
  }
}
