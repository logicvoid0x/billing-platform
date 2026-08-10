package com.logicvoid0x.billing.ratingengine;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Rates sealed hours of {@code metering.usage_hourly} into the append-only {@code
 * rating.rated_ledger} as {@code rating_version} 1 (design: "For each (org, SKU, hour) row, the
 * rating engine looks up the price in effect at that hour ... and appends a rated row recording the
 * price book, contract and rating-code versions").
 *
 * <p>Amounts never round per row. Within a billing period (calendar month, UTC) the hours of an
 * (org, SKU) are charged in order on the running period quantity: {@code chargeCents(before,
 * after)} = {@code F(after) - F(before)}. A period's rows therefore telescope to {@code F(period
 * total)}, rounded once, half-even. Rating is idempotent on the ledger key, so a re-run writes
 * nothing new.
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

  /** One usage_hourly row waiting to be rated. */
  private record Unrated(String orgId, String skuId, Instant hour, long quantity) {}

  /**
   * Outcome of one rating run.
   *
   * @param written rated rows written now
   * @param blocked (org|SKU|period) groups not rated, each with the reason; they need a re-rating
   */
  public record Result(int written, List<String> blocked) {
    /** Copies the list. */
    public Result {
      blocked = List.copyOf(blocked);
    }
  }

  /**
   * Rates every sealed, not yet rated hour. A group that cannot be rated in order is reported in
   * {@link Result#blocked} and does not stop the other groups.
   */
  public Result rateSealedHours(Instant now) throws SQLException {
    Instant sealedBefore = now.minus(SEAL_DELAY);
    Map<String, List<Unrated>> byPeriod = new LinkedHashMap<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "SELECT u.org_id, u.sku_id, u.event_hour, u.quantity FROM metering.usage_hourly u"
                    + " WHERE u.event_hour <= ? AND NOT EXISTS (SELECT 1 FROM rating.rated_ledger r"
                    + " WHERE r.org_id = u.org_id AND r.sku_id = u.sku_id"
                    + " AND r.event_hour = u.event_hour)"
                    + " ORDER BY u.org_id, u.sku_id, u.event_hour")) {
      s.setTimestamp(1, Timestamp.from(sealedBefore));
      try (ResultSet rs = s.executeQuery()) {
        while (rs.next()) {
          Unrated u =
              new Unrated(
                  rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant(), rs.getLong(4));
          byPeriod.computeIfAbsent(groupKey(u), k -> new ArrayList<>()).add(u);
        }
      }
    }
    int written = 0;
    List<String> blocked = new ArrayList<>();
    for (Map.Entry<String, List<Unrated>> group : byPeriod.entrySet()) {
      try {
        written += ratePeriod(group.getValue());
      } catch (IllegalStateException e) {
        blocked.add(group.getKey() + ": " + e.getMessage());
      }
    }
    return new Result(written, blocked);
  }

  private static String groupKey(Unrated u) {
    return u.orgId() + "|" + u.skuId() + "|" + periodStart(u.hour());
  }

  /** Start of the billing period (calendar month, UTC) that contains {@code hour}. */
  public static Instant periodStart(Instant hour) {
    return YearMonth.from(hour.atOffset(ZoneOffset.UTC))
        .atDay(1)
        .atStartOfDay()
        .toInstant(ZoneOffset.UTC);
  }

  /** Rates the unrated hours of one (org, SKU, period), in hour order, in one transaction. */
  private int ratePeriod(List<Unrated> hours) throws SQLException {
    Unrated first = hours.get(0);
    Instant period = periodStart(first.hour());
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        long before = ratedQuantityBefore(c, first, period);
        int written = 0;
        try (PreparedStatement s =
            c.prepareStatement(
                "INSERT INTO rating.rated_ledger (org_id, sku_id, event_hour, rating_version,"
                    + " entry_type, quantity, period_start, total_before, amount_cents,"
                    + " catalog_version, rating_code_version)"
                    + " VALUES (?, ?, ?, ?, 'RATED', ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
          for (Unrated u : hours) {
            long after = Math.addExact(before, u.quantity());
            RatingLibrary library = libraries.at(u.hour());
            long cents = library.chargeCents(u.orgId(), u.skuId(), before, after, u.hour());
            s.setString(1, u.orgId());
            s.setString(2, u.skuId());
            s.setTimestamp(3, Timestamp.from(u.hour()));
            s.setInt(4, FIRST_VERSION);
            s.setLong(5, u.quantity());
            s.setTimestamp(6, Timestamp.from(period));
            s.setLong(7, before);
            s.setLong(8, cents);
            s.setString(9, library.version());
            s.setString(10, ratingCodeVersion);
            written += s.executeUpdate();
            before = after;
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

  /**
   * The rated quantity of the period before {@code first}. Refuses to rate an hour that is earlier
   * than an hour already rated in the same period: its running total would no longer telescope.
   */
  private static long ratedQuantityBefore(Connection c, Unrated first, Instant period)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT coalesce(sum(quantity) FILTER (WHERE event_hour < ?), 0),"
                + " count(*) FILTER (WHERE event_hour > ?)"
                + " FROM rating.rated_ledger WHERE org_id = ? AND sku_id = ? AND period_start = ?"
                + " AND rating_version = ? AND entry_type = 'RATED'")) {
      s.setTimestamp(1, Timestamp.from(first.hour()));
      s.setTimestamp(2, Timestamp.from(first.hour()));
      s.setString(3, first.orgId());
      s.setString(4, first.skuId());
      s.setTimestamp(5, Timestamp.from(period));
      s.setInt(6, FIRST_VERSION);
      try (ResultSet rs = s.executeQuery()) {
        rs.next();
        if (rs.getLong(2) > 0) {
          throw new IllegalStateException(
              "hour "
                  + first.hour()
                  + " of "
                  + first.orgId()
                  + "/"
                  + first.skuId()
                  + " arrived after later hours were rated; re-rate the period instead (P7.3)");
        }
        return rs.getLong(1);
      }
    }
  }
}
