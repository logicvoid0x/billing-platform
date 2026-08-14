package com.logicvoid0x.billing.ratingengine;

import com.logicvoid0x.billing.money.Cents;
import com.logicvoid0x.billing.rating.Pricing;
import com.logicvoid0x.billing.rating.RatingLibrary;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import javax.sql.DataSource;

/**
 * Rates sealed hours of {@code metering.usage_hourly} into the append-only {@code
 * rating.rated_ledger} as {@code rating_version} 1 (design: "For each (org, SKU, hour) row, the
 * rating engine looks up the price in effect at that hour ... and appends a rated row recording the
 * price book, contract and rating-code versions").
 *
 * <p>Amounts never round per row. Within a billing period (calendar month, UTC) each (org, SKU) is
 * charged in hour order on its running period quantity, so a period's rows telescope to the period
 * total rounded once, half-even. There are two pricing paths:
 *
 * <ul>
 *   <li>{@code TIERED} (default): {@code F(after) - F(before)} with the SKU's pricing, graduated
 *       tiers included.
 *   <li>{@code COMMIT}, when the org has a {@code rating.commitment} for the period: the commitment
 *       replaces tier state. Usage is charged at a flat rate (the first tier's for graduated
 *       pricing) and draws the commitment down in (hour, SKU) order; {@code drawdown_cents} is the
 *       covered part and the rest is overage. Assumption, see open-questions Q8.
 * </ul>
 *
 * <p>Rating is idempotent on the ledger key, so a re-run writes nothing new.
 */
public final class HourlyRater {
  /** Rating library in effect at an hour (catalog access is open question Q7). */
  @FunctionalInterface
  public interface Libraries {
    /** The library for {@code hour}. */
    RatingLibrary at(Instant hour);
  }

  /**
   * An hour is sealed at hour + 1 h + 1 h allowed lateness + 5 min out-of-orderness, the same rule
   * as the replay guard (jobs:usage-replay ReplayGuard.sealedAt). After that, usage_hourly for the
   * hour only changes through a replay, which is re-rated (P7.3).
   */
  public static final Duration SEAL_DELAY = Duration.ofMinutes(125);

  /** The first rating of an hour. */
  public static final int FIRST_VERSION = 1;

  private final DataSource dataSource;
  private final Libraries libraries;
  private final String ratingCodeVersion;

  /** Creates the rater. */
  public HourlyRater(DataSource dataSource, Libraries libraries, String ratingCodeVersion) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.libraries = Objects.requireNonNull(libraries, "libraries");
    this.ratingCodeVersion = Objects.requireNonNull(ratingCodeVersion, "ratingCodeVersion");
  }

  /**
   * Outcome of one rating run.
   *
   * @param written rated rows written now
   * @param blocked (org|period) groups not rated, each with the reason; they need a re-rating
   */
  public record Result(int written, List<String> blocked) {
    /** Copies the list. */
    public Result {
      blocked = List.copyOf(blocked);
    }
  }

  /** One usage_hourly row waiting to be rated. */
  private record Unrated(String orgId, String skuId, Instant hour, long quantity) {}

  /**
   * Rates every sealed, not yet rated hour. A group that cannot be rated in order is reported in
   * {@link Result#blocked} and does not stop the other groups.
   */
  public Result rateSealedHours(Instant now) throws SQLException {
    Instant sealedBefore = now.minus(SEAL_DELAY);
    Map<String, List<Unrated>> byGroup = new LinkedHashMap<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "SELECT u.org_id, u.sku_id, u.event_hour, u.quantity FROM metering.usage_hourly u"
                    + " WHERE u.event_hour <= ? AND NOT EXISTS (SELECT 1 FROM rating.rated_ledger r"
                    + " WHERE r.org_id = u.org_id AND r.sku_id = u.sku_id"
                    + " AND r.event_hour = u.event_hour)"
                    + " ORDER BY u.org_id, u.event_hour, u.sku_id")) {
      s.setTimestamp(1, Timestamp.from(sealedBefore));
      try (ResultSet rs = s.executeQuery()) {
        while (rs.next()) {
          Unrated u =
              new Unrated(
                  rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getLong(4));
          byGroup
              .computeIfAbsent(u.orgId() + "|" + periodStart(u.hour()), k -> new ArrayList<>())
              .add(u);
        }
      }
    }
    int written = 0;
    List<String> blocked = new ArrayList<>();
    for (Map.Entry<String, List<Unrated>> group : byGroup.entrySet()) {
      try {
        written += rateGroup(group.getValue());
      } catch (IllegalStateException e) {
        blocked.add(group.getKey() + ": " + e.getMessage());
      }
    }
    return new Result(written, blocked);
  }

  /** Start of the billing period (calendar month, UTC) that contains {@code hour}. */
  public static Instant periodStart(Instant hour) {
    return YearMonth.from(hour.atOffset(ZoneOffset.UTC))
        .atDay(1)
        .atStartOfDay()
        .toInstant(ZoneOffset.UTC);
  }

  /** Rates the unrated hours of one (org, period), in (hour, SKU) order, in one transaction. */
  private int rateGroup(List<Unrated> rows) throws SQLException {
    rows.sort(Comparator.comparing(Unrated::hour).thenComparing(Unrated::skuId));
    Unrated first = rows.get(0);
    String org = first.orgId();
    Instant period = periodStart(first.hour());
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        requireInOrder(c, org, period, first.hour());
        OptionalLong commitment = commitment(c, org, period);
        long remaining =
            commitment.isPresent()
                ? Math.subtractExact(commitment.getAsLong(), drawnDown(c, org, period))
                : 0;
        Map<String, Long> totals = new HashMap<>();
        int written = 0;
        try (PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO rating.rated_ledger (org_id, sku_id, event_hour, rating_version,"
                    + " entry_type, quantity, period_start, total_before, amount_cents,"
                    + " catalog_version, rating_code_version, pricing_path, drawdown_cents)"
                    + " VALUES (?, ?, ?, ?, 'RATED', ?, ?, ?, ?, ?, ?, ?, ?)"
                    + " ON CONFLICT DO NOTHING")) {
          for (Unrated u : rows) {
            Long known = totals.get(u.skuId());
            long before = known != null ? known : ratedQuantity(c, org, u.skuId(), period);
            long after = Math.addExact(before, u.quantity());
            totals.put(u.skuId(), after);
            RatingLibrary library = libraries.at(u.hour());
            long cents;
            long drawdown = 0;
            if (commitment.isPresent()) {
              cents =
                  Cents.charge(
                      before, after, commitRate(library.pricing(org, u.skuId(), u.hour())));
              drawdown = Math.min(cents, Math.max(remaining, 0));
              remaining = Math.subtractExact(remaining, drawdown);
            } else {
              cents = library.chargeCents(org, u.skuId(), before, after, u.hour());
            }
            s.setString(1, org);
            s.setString(2, u.skuId());
            s.setTimestamp(3, Timestamp.from(u.hour()));
            s.setInt(4, FIRST_VERSION);
            s.setLong(5, u.quantity());
            s.setTimestamp(6, Timestamp.from(period));
            s.setLong(7, before);
            s.setLong(8, cents);
            s.setString(9, library.version());
            s.setString(10, ratingCodeVersion);
            s.setString(11, commitment.isPresent() ? "COMMIT" : "TIERED");
            s.setLong(12, drawdown);
            written += s.executeUpdate();
          }
        }
        c.commit();
        return written;
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    }
  }

  /** The flat rate a commitment charges at: the price's rate, or its first tier's (Q8). */
  static Cents.Rate commitRate(Pricing pricing) {
    return switch (pricing) {
      case Pricing.Flat f -> f.rate();
      case Pricing.Graduated g -> g.tiers().get(0).rate();
    };
  }

  /**
   * Refuses to rate an hour earlier than an hour already rated in the same (org, period): running
   * totals and commitment drawdown would no longer be in order.
   */
  private static void requireInOrder(Connection c, String org, Instant period, Instant firstHour)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT count(*) FROM rating.rated_ledger WHERE org_id = ? AND period_start = ?"
                + " AND rating_version = ? AND entry_type = 'RATED' AND event_hour > ?")) {
      s.setString(1, org);
      s.setTimestamp(2, Timestamp.from(period));
      s.setInt(3, FIRST_VERSION);
      s.setTimestamp(4, Timestamp.from(firstHour));
      try (ResultSet rs = s.executeQuery()) {
        rs.next();
        if (rs.getLong(1) > 0) {
          throw new IllegalStateException(
              "hour "
                  + firstHour
                  + " arrived after later hours of the period were rated;"
                  + " re-rate the period instead (P7.3)");
        }
      }
    }
  }

  private static long ratedQuantity(Connection c, String org, String sku, Instant period)
      throws SQLException {
    return sum(
        c,
        "SELECT coalesce(sum(quantity), 0) FROM rating.rated_ledger WHERE org_id = ? AND sku_id = ?"
            + " AND period_start = ? AND rating_version = ? AND entry_type = 'RATED'",
        org,
        sku,
        period);
  }

  /** Commitment already drawn down in the period by rated rows. */
  private static long drawnDown(Connection c, String org, Instant period) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT coalesce(sum(drawdown_cents), 0) FROM rating.rated_ledger WHERE org_id = ?"
                + " AND period_start = ? AND rating_version = ? AND entry_type = 'RATED'")) {
      s.setString(1, org);
      s.setTimestamp(2, Timestamp.from(period));
      s.setInt(3, FIRST_VERSION);
      try (ResultSet rs = s.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static long sum(Connection c, String sql, String org, String sku, Instant period)
      throws SQLException {
    try (PreparedStatement s = c.prepareStatement(sql)) {
      s.setString(1, org);
      s.setString(2, sku);
      s.setTimestamp(3, Timestamp.from(period));
      s.setInt(4, FIRST_VERSION);
      try (ResultSet rs = s.executeQuery()) {
        rs.next();
        return rs.getLong(1);
      }
    }
  }

  private static OptionalLong commitment(Connection c, String org, Instant period)
      throws SQLException {
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
}
