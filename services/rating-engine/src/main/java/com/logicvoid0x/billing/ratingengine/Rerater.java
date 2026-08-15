package com.logicvoid0x.billing.ratingengine;

import com.logicvoid0x.billing.ratingengine.Ledger.Posted;
import com.logicvoid0x.billing.ratingengine.PeriodPricer.Priced;
import com.logicvoid0x.billing.ratingengine.PeriodPricer.Usage;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.sql.DataSource;

/**
 * Re-rates an (org, billing period) (design: "Re-rating never overwrites: a new rating_version is a
 * new key, and the difference is posted as an adjustment").
 *
 * <p>The period's sealed hours are priced again from scratch with the current usage and catalog,
 * for example after a backdated contract or a replay. Every hour whose amount, drawdown or quantity
 * changed gets one ADJUSTMENT row in the new version, carrying the difference from everything
 * posted for it so far. Unchanged hours get no row, and hours that disappeared get a reversal.
 * Afterwards the ledger summed over versions equals a from-scratch rating, so {@link #trueUp} is
 * zero.
 */
public final class Rerater {
  private final DataSource dataSource;
  private final HourlyRater.Libraries libraries;
  private final String ratingCodeVersion;

  /** Creates the re-rater. */
  public Rerater(DataSource dataSource, HourlyRater.Libraries libraries, String ratingCodeVersion) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.libraries = Objects.requireNonNull(libraries, "libraries");
    this.ratingCodeVersion = Objects.requireNonNull(ratingCodeVersion, "ratingCodeVersion");
  }

  /**
   * Outcome of a re-rating.
   *
   * @param version the rating version the adjustments were posted under
   * @param adjustments adjustment rows written
   * @param differenceCents sum of the adjustments
   */
  public record Result(int version, int adjustments, long differenceCents) {}

  /** Re-rates the period that starts at {@code periodStart}, using hours sealed at {@code now}. */
  public Result rerate(String org, Instant periodStart, Instant now) throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try {
        Ledger.lock(c, org, periodStart);
        List<Priced> priced = reprice(c, org, periodStart, now);
        Map<String, Posted> posted = Ledger.posted(c, org, periodStart);
        int version = Ledger.maxVersion(c, org, periodStart) + 1;
        int adjustments = 0;
        long difference = 0;
        for (Priced p : priced) {
          Posted before = posted.remove(Ledger.key(p.usage().skuId(), p.usage().hour()));
          long diff = p.amountCents() - (before == null ? 0 : before.amountCents());
          long drawdownDiff = p.drawdownCents() - (before == null ? 0 : before.drawdownCents());
          boolean quantityChanged =
              before == null || before.latestQuantity() != p.usage().quantity();
          if (diff != 0 || drawdownDiff != 0 || quantityChanged) {
            adjustments +=
                Ledger.insert(
                    c,
                    org,
                    periodStart,
                    version,
                    "ADJUSTMENT",
                    p,
                    diff,
                    drawdownDiff,
                    ratingCodeVersion);
            difference = Math.addExact(difference, diff);
          }
        }
        // Hours posted before but gone from usage now (for example removed by a replay).
        for (Map.Entry<String, Posted> gone : posted.entrySet()) {
          Posted before = gone.getValue();
          if (before.amountCents() == 0
              && before.drawdownCents() == 0
              && before.latestQuantity() == 0) {
            continue;
          }
          String key = gone.getKey();
          String sku = key.substring(0, key.indexOf('|'));
          Instant hour = Instant.parse(key.substring(key.indexOf('|') + 1));
          Priced reversal =
              new Priced(new Usage(sku, hour, 0), 0, 0, 0, "TIERED", libraries.at(hour).version());
          adjustments +=
              Ledger.insert(
                  c,
                  org,
                  periodStart,
                  version,
                  "ADJUSTMENT",
                  reversal,
                  -before.amountCents(),
                  -before.drawdownCents(),
                  ratingCodeVersion);
          difference = Math.subtractExact(difference, before.amountCents());
        }
        c.commit();
        return new Result(version, adjustments, difference);
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    }
  }

  /**
   * The tier true-up at close: per SKU, cents posted (all versions) minus a from-scratch rating of
   * the period. Only non-zero SKUs are returned; design: "Zero for every customer".
   */
  public Map<String, Long> trueUp(String org, Instant periodStart, Instant now)
      throws SQLException {
    try (Connection c = dataSource.getConnection()) {
      Map<String, Long> diff = new TreeMap<>();
      Map<String, Long> postedBySku = new HashMap<>();
      Ledger.posted(c, org, periodStart)
          .forEach((k, p) ->
              postedBySku.merge(k.substring(0, k.indexOf('|')), p.amountCents(), Math::addExact));
      Map<String, Long> repricedBySku = new HashMap<>();
      for (Priced p : reprice(c, org, periodStart, now)) {
        repricedBySku.merge(p.usage().skuId(), p.amountCents(), Math::addExact);
      }
      postedBySku.forEach((sku, cents) -> {
        long d = cents - repricedBySku.getOrDefault(sku, 0L);
        if (d != 0) {
          diff.put(sku, d);
        }
      });
      repricedBySku.forEach((sku, cents) -> {
        if (!postedBySku.containsKey(sku) && cents != 0) {
          diff.put(sku, -cents);
        }
      });
      return diff;
    }
  }

  /** The period's sealed hours priced from scratch. */
  private List<Priced> reprice(Connection c, String org, Instant periodStart, Instant now)
      throws SQLException {
    Instant periodEnd = periodStart.atOffset(ZoneOffset.UTC).plusMonths(1).toInstant();
    List<Usage> usages = new ArrayList<>();
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT sku_id, event_hour, quantity FROM metering.usage_hourly WHERE org_id = ?"
                + " AND event_hour >= ? AND event_hour < ? AND event_hour <= ?")) {
      s.setString(1, org);
      s.setTimestamp(2, Timestamp.from(periodStart));
      s.setTimestamp(3, Timestamp.from(periodEnd));
      s.setTimestamp(4, Timestamp.from(now.minus(HourlyRater.SEAL_DELAY)));
      try (ResultSet rs = s.executeQuery()) {
        while (rs.next()) {
          usages.add(new Usage(rs.getString(1), rs.getTimestamp(2).toInstant(), rs.getLong(3)));
        }
      }
    }
    return PeriodPricer.price(
        org, usages, Map.of(), Ledger.commitment(c, org, periodStart), 0, libraries);
  }
}
