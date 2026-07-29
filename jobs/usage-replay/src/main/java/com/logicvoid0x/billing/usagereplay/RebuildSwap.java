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
import java.util.SortedSet;

/**
 * Writes a replay into {@code metering.usage_hourly_rebuild} and swaps it into {@code
 * usage_hourly}. The swap deletes the replayed hours from {@code usage_hourly} and inserts the
 * rebuilt rows in one transaction, so readers see either the old hours or the new ones, never a
 * mix. Replaying the same hours again rebuilds identical rows.
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
        try (PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO metering.usage_hourly_rebuild"
                    + " (org_id, sku_id, event_hour, quantity, lines, replay_id)"
                    + " VALUES (?, ?, ?, ?, ?, ?)")) {
          for (HourlyUsage r : rows) {
            s.setString(1, r.orgId());
            s.setString(2, r.skuId());
            s.setTimestamp(3, Timestamp.from(r.eventHour()));
            s.setLong(4, r.quantity());
            s.setLong(5, r.lines());
            s.setString(6, replayId);
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
