package com.logicvoid0x.billing.ratingengine;

import com.logicvoid0x.billing.ratingengine.PeriodPricer.Priced;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;

/**
 * Reads and appends of {@code rating.rated_ledger} shared by rating and re-rating. The latest
 * version of an (org, SKU, hour) describes the hour (quantity, running total); amounts and drawdown
 * are the sum over every version.
 */
final class Ledger {
  private Ledger() {}

  /** Serialises rating and re-rating of one (org, period) until the transaction ends. */
  static void lock(Connection c, String org, Instant period) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(? || '|' || ?, 0))")) {
      s.setString(1, org);
      s.setString(2, period.toString());
      s.execute();
    }
  }

  /** Posted totals of one (SKU, hour): amounts and drawdown summed over every version. */
  record Posted(long amountCents, long drawdownCents, long latestQuantity) {}

  /** Running quantity per SKU of the period: each hour's latest version, summed. */
  static Map<String, Long> latestQuantities(Connection c, String org, Instant period)
      throws SQLException {
    Map<String, Long> out = new HashMap<>();
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT sku_id, sum(quantity) FROM (SELECT DISTINCT ON (sku_id, event_hour) sku_id,"
                + " quantity FROM rating.rated_ledger WHERE org_id = ? AND period_start = ?"
                + " ORDER BY sku_id, event_hour, rating_version DESC) t GROUP BY sku_id")) {
      s.setString(1, org);
      s.setTimestamp(2, Timestamp.from(period));
      try (ResultSet rs = s.executeQuery()) {
        while (rs.next()) {
          out.put(rs.getString(1), rs.getLong(2));
        }
      }
    }
    return out;
  }

  /** Every posted (SKU, hour) of the period, keyed {@code sku|hour}. */
  static Map<String, Posted> posted(Connection c, String org, Instant period) throws SQLException {
    Map<String, Posted> out = new HashMap<>();
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT sku_id, event_hour, sum(amount_cents), sum(drawdown_cents),"
                + " (array_agg(quantity ORDER BY rating_version DESC))[1]"
                + " FROM rating.rated_ledger WHERE org_id = ? AND period_start = ?"
                + " GROUP BY sku_id, event_hour")) {
      s.setString(1, org);
      s.setTimestamp(2, Timestamp.from(period));
      try (ResultSet rs = s.executeQuery()) {
        while (rs.next()) {
          out.put(
              key(rs.getString(1), rs.getTimestamp(2).toInstant()),
              new Posted(rs.getLong(3), rs.getLong(4), rs.getLong(5)));
        }
      }
    }
    return out;
  }

  static String key(String sku, Instant hour) {
    return sku + "|" + hour;
  }

  /** The highest rating version posted in the period, or 0. */
  static int maxVersion(Connection c, String org, Instant period) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT coalesce(max(rating_version), 0) FROM rating.rated_ledger"
                + " WHERE org_id = ? AND period_start = ?")) {
      s.setString(1, org);
      s.setTimestamp(2, Timestamp.from(period));
      try (ResultSet rs = s.executeQuery()) {
        rs.next();
        return rs.getInt(1);
      }
    }
  }

  /** Commitment drawn down in the period, over every version. */
  static long drawnDown(Connection c, String org, Instant period) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT coalesce(sum(drawdown_cents), 0) FROM rating.rated_ledger"
                + " WHERE org_id = ? AND period_start = ?")) {
      s.setString(1, org);
      s.setTimestamp(2, Timestamp.from(period));
      try (ResultSet rs = s.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  /** The org's commitment for the period, if any. */
  static OptionalLong commitment(Connection c, String org, Instant period) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT commit_cents FROM rating.commitment WHERE org_id = ? AND period_start = ?")) {
      s.setString(1, org);
      s.setTimestamp(2, Timestamp.from(period));
      try (ResultSet rs = s.executeQuery()) {
        return rs.next() ? OptionalLong.of(rs.getLong(1)) : OptionalLong.empty();
      }
    }
  }

  /** Appends one row; idempotent on the ledger key. Returns rows written (0 or 1). */
  static int insert(
      Connection c,
      String org,
      Instant period,
      int version,
      String entryType,
      Priced p,
      long amountCents,
      long drawdownCents,
      String ratingCodeVersion)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "INSERT INTO rating.rated_ledger (org_id, sku_id, event_hour, rating_version,"
                + " entry_type, quantity, period_start, total_before, amount_cents,"
                + " catalog_version, rating_code_version, pricing_path, drawdown_cents)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
      s.setString(1, org);
      s.setString(2, p.usage().skuId());
      s.setTimestamp(3, Timestamp.from(p.usage().hour()));
      s.setInt(4, version);
      s.setString(5, entryType);
      s.setLong(6, p.usage().quantity());
      s.setTimestamp(7, Timestamp.from(period));
      s.setLong(8, p.totalBefore());
      s.setLong(9, amountCents);
      s.setString(10, p.catalogVersion());
      s.setString(11, ratingCodeVersion);
      s.setString(12, p.path());
      s.setLong(13, drawdownCents);
      return s.executeUpdate();
    }
  }
}
