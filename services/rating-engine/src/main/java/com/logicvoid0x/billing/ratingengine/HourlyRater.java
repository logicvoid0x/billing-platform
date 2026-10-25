package com.logicvoid0x.billing.ratingengine;

import com.logicvoid0x.billing.rating.RatingLibrary;
import com.logicvoid0x.billing.ratingengine.PeriodPricer.Priced;
import com.logicvoid0x.billing.ratingengine.PeriodPricer.Usage;
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
import java.util.HashMap;
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
 * <p>Amounts never round per row: {@link PeriodPricer} charges each (org, SKU) in hour order on its
 * running period quantity (calendar month, UTC), so a period's rows telescope to the period total
 * rounded once. With a commitment the COMMIT path draws it down instead (Q8). A new hour continues
 * from each earlier hour's latest version, so it also follows a re-rating. Rating is idempotent on
 * the ledger key.
 */
public final class HourlyRater {
  /** Rating library in effect at an hour; deployed, it is {@code HttpCatalogClient::at} (Q7). */
  @FunctionalInterface
  public interface Libraries {
    /** The library for {@code hour}. */
    RatingLibrary at(Instant hour);
  }

  /**
   * An hour is sealed at hour + 1 h + 1 h allowed lateness + 5 min out-of-orderness, the same rule
   * as the replay guard (jobs:usage-replay ReplayGuard.sealedAt). After that, usage_hourly for the
   * hour only changes through a replay, which is re-rated ({@link Rerater}).
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

  /**
   * Rates every sealed, not yet rated hour. A group that cannot be rated in order is reported in
   * {@link Result#blocked} and does not stop the other groups.
   */
  public Result rateSealedHours(Instant now) throws SQLException {
    Map<String, List<Usage>> byGroup = new LinkedHashMap<>();
    Map<String, String> orgOf = new HashMap<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "SELECT u.org_id, u.sku_id, u.event_hour, u.quantity FROM metering.usage_hourly u"
                    + " WHERE u.event_hour <= ? AND NOT EXISTS (SELECT 1 FROM rating.rated_ledger r"
                    + " WHERE r.org_id = u.org_id AND r.sku_id = u.sku_id"
                    + " AND r.event_hour = u.event_hour)"
                    + " ORDER BY u.org_id, u.event_hour, u.sku_id")) {
      s.setTimestamp(1, Timestamp.from(now.minus(SEAL_DELAY)));
      try (ResultSet rs = s.executeQuery()) {
        while (rs.next()) {
          String org = rs.getString(1);
          Instant hour = rs.getTimestamp(3).toInstant();
          String key = org + "|" + periodStart(hour);
          orgOf.put(key, org);
          byGroup
              .computeIfAbsent(key, k -> new ArrayList<>())
              .add(new Usage(rs.getString(2), hour, rs.getLong(4)));
        }
      }
    }
    int written = 0;
    List<String> blocked = new ArrayList<>();
    for (Map.Entry<String, List<Usage>> group : byGroup.entrySet()) {
      try {
        written += rateGroup(orgOf.get(group.getKey()), group.getValue());
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

  private int rateGroup(String org, List<Usage> usages) throws SQLException {
    Instant first = usages.stream().map(Usage::hour).min(Instant::compareTo).orElseThrow();
    Instant period = periodStart(first);
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        Ledger.lock(c, org, period);
        requireInOrder(c, org, period, first);
        List<Priced> priced =
            PeriodPricer.price(
                org,
                usages,
                Ledger.latestQuantities(c, org, period),
                Ledger.commitment(c, org, period),
                Ledger.drawnDown(c, org, period),
                libraries);
        int written = 0;
        for (Priced p : priced) {
          written +=
              Ledger.insert(
                  c,
                  org,
                  period,
                  FIRST_VERSION,
                  "RATED",
                  p,
                  p.amountCents(),
                  p.drawdownCents(),
                  ratingCodeVersion);
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
   * Refuses to rate an hour earlier than an hour already in the ledger for the same (org, period):
   * running totals and commitment drawdown would no longer be in order.
   */
  private static void requireInOrder(Connection c, String org, Instant period, Instant firstHour)
      throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT count(*) FROM rating.rated_ledger WHERE org_id = ? AND period_start = ?"
                + " AND event_hour > ?")) {
      s.setString(1, org);
      s.setTimestamp(2, Timestamp.from(period));
      s.setTimestamp(3, Timestamp.from(firstHour));
      try (ResultSet rs = s.executeQuery()) {
        rs.next();
        if (rs.getLong(1) > 0) {
          throw new IllegalStateException(
              "hour "
                  + firstHour
                  + " arrived after later hours of the period were rated;"
                  + " re-rate the period instead (Rerater)");
        }
      }
    }
  }
}
