package com.logicvoid0x.billing.ratingengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.logicvoid0x.billing.rating.CatalogRatingLibrary;
import com.logicvoid0x.billing.rating.ContractPrice;
import com.logicvoid0x.billing.rating.EffectivePeriod;
import com.logicvoid0x.billing.rating.RatingCatalog;
import com.logicvoid0x.billing.rating.RatingLibrary;
import com.logicvoid0x.billing.rating.golden.GoldenCases;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Phase 7 "Done when": re-rating a period after a backdated contract change posts only the
 * difference, and the tier true-up at close is zero for every test customer.
 */
@Testcontainers
class RerateTest {
  @Container static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18.6");

  private static final Instant NOV = Instant.parse("2026-11-01T00:00:00Z");
  private static final Instant H10 = Instant.parse("2026-11-01T10:00:00Z");
  private static final Instant H11 = Instant.parse("2026-11-01T11:00:00Z");
  private static final Instant H12 = Instant.parse("2026-11-01T12:00:00Z");
  private static final Instant NOW = Instant.parse("2026-11-03T00:00:00Z");

  /** The golden catalog as first rated. */
  private static final RatingLibrary BEFORE =
      new CatalogRatingLibrary(GoldenCases.catalog(), "rating-1");

  /** The same catalog plus a contract for org_bd, backdated to the start of every period. */
  private static final RatingLibrary AFTER =
      new CatalogRatingLibrary(withBackdatedContract(), "rating-1");

  private static PGSimpleDataSource ds;

  private static RatingCatalog withBackdatedContract() {
    RatingCatalog g = GoldenCases.catalog();
    List<ContractPrice> contracts = new ArrayList<>(g.contractPrices());
    contracts.add(
        ContractPrice.discount(
            "org_bd",
            "sku_out",
            g.listPrices().get(0).pricing(),
            2000,
            EffectivePeriod.from(GoldenCases.V1)));
    return new RatingCatalog(
        "golden-2", g.skus(), g.bandRules(), g.listPrices(), contracts, g.weights());
  }

  @BeforeAll
  static void schema() throws SQLException {
    ds = new PGSimpleDataSource();
    ds.setUrl(PG.getJdbcUrl());
    ds.setUser(PG.getUsername());
    ds.setPassword(PG.getPassword());
    RatingMigrations.migrate(ds);
    exec(
        "CREATE SCHEMA IF NOT EXISTS metering; CREATE TABLE IF NOT EXISTS metering.usage_hourly ("
            + " org_id text NOT NULL, sku_id text NOT NULL, event_hour timestamptz NOT NULL,"
            + " quantity bigint NOT NULL, lines bigint NOT NULL,"
            + " PRIMARY KEY (org_id, sku_id, event_hour))");
  }

  private static void exec(String sql) throws SQLException {
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement()) {
      s.execute(sql);
    }
  }

  private static void usage(String org, String sku, Instant hour, long quantity)
      throws SQLException {
    exec(
        "INSERT INTO metering.usage_hourly VALUES ('"
            + org
            + "', '"
            + sku
            + "', '"
            + hour
            + "', "
            + quantity
            + ", 1)");
  }

  private static List<String> rows(String org) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Connection c = ds.getConnection();
        Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT rating_version, entry_type, sku_id, event_hour, total_before, quantity,"
                    + " amount_cents FROM rating.rated_ledger WHERE org_id = '"
                    + org
                    + "' ORDER BY rating_version, event_hour, sku_id")) {
      while (rs.next()) {
        out.add(
            "v"
                + rs.getInt(1)
                + " "
                + rs.getString(2)
                + " "
                + rs.getString(3)
                + " "
                + rs.getTimestamp(4).toInstant().toString().substring(11, 13)
                + " "
                + rs.getLong(5)
                + "+"
                + rs.getLong(6)
                + " "
                + rs.getLong(7));
      }
    }
    return out;
  }

  @Test
  void aBackdatedContractPostsOnlyTheDifference() throws Exception {
    usage("org_bd", "sku_out", H10, 10_000_000);
    usage("org_bd", "sku_out", H11, 30_000_000);
    usage("org_bd", "sku_tiered", H10, 5_000_000);
    new HourlyRater(ds, h -> BEFORE, "rating-1").rateSealedHours(NOW);

    Rerater rerater = new Rerater(ds, h -> AFTER, "rating-1");
    Rerater.Result r = rerater.rerate("org_bd", NOV, NOW);
    assertThat(r).isEqualTo(new Rerater.Result(2, 2, -12_000));
    assertThat(rows("org_bd"))
        .containsExactly(
            "v1 RATED sku_out 10 0+10000000 15000",
            "v1 RATED sku_tiered 10 0+5000000 7500",
            "v1 RATED sku_out 11 10000000+30000000 45000",
            "v2 ADJUSTMENT sku_out 10 0+10000000 -3000", // now 12,000 at 20% off
            "v2 ADJUSTMENT sku_out 11 10000000+30000000 -9000"); // now 36,000

    assertThat(rerater.rerate("org_bd", NOV, NOW).adjustments())
        .as("nothing left to adjust")
        .isZero();
    assertThat(rerater.trueUp("org_bd", NOV, NOW)).as("true-up at close").isEmpty();
  }

  @Test
  void aReplayedHourIsAdjustedAndLaterHoursContinueFromTheCorrectedTotal() throws Exception {
    usage("org_rp", "sku_tiered", H10, 990_000_000);
    usage("org_rp", "sku_tiered", H11, 30_000_000);
    HourlyRater rater = new HourlyRater(ds, h -> BEFORE, "rating-1");
    rater.rateSealedHours(NOW);
    // A replay corrected hour 10 (it had been double counted): 990M -> 495M.
    exec(
        "UPDATE metering.usage_hourly SET quantity = 495000000 WHERE org_id = 'org_rp'"
            + " AND event_hour = '"
            + H10
            + "'");
    Rerater rerater = new Rerater(ds, h -> BEFORE, "rating-1");
    rerater.rerate("org_rp", NOV, NOW);
    // Hour 10: 742,500 (was 1,485,000). Hour 11 now sits below the tier boundary: 45,000 (was
    // 39,000).
    assertThat(rows("org_rp"))
        .contains(
            "v2 ADJUSTMENT sku_tiered 10 0+495000000 -742500",
            "v2 ADJUSTMENT sku_tiered 11 495000000+30000000 6000");

    usage("org_rp", "sku_tiered", H12, 1_000_000);
    rater.rateSealedHours(NOW);
    assertThat(rows("org_rp")).contains("v1 RATED sku_tiered 12 525000000+1000000 1500");
    assertThat(rerater.trueUp("org_rp", NOV, NOW)).isEmpty();
  }

  @Test
  void theTierTrueUpAtCloseIsZeroForEveryTestCustomer() throws Exception {
    long[][] hours = {
      {400_000_000, 400_000_000, 400_000_000}, // crosses 1B inside the third hour
      {999_999_999, 1, 7},
      {1, 2, 3},
    };
    exec(
        "INSERT INTO rating.commitment (org_id, period_start, commit_cents, contract_version)"
            + " VALUES ('org_tu_commit', '"
            + NOV
            + "', 100000, 1)");
    List<String> orgs = List.of("org_tu_0", "org_tu_1", "org_tu_2", "org_tu_commit");
    for (int o = 0; o < orgs.size(); o++) {
      long[] q = hours[Math.min(o, 2)];
      usage(orgs.get(o), "sku_tiered", H10, q[0]);
      usage(orgs.get(o), "sku_tiered", H11, q[1]);
      usage(orgs.get(o), "sku_out", H11, 123_456_789);
      usage(orgs.get(o), "sku_tiered", H12, q[2]);
    }
    new HourlyRater(ds, h -> BEFORE, "rating-1").rateSealedHours(NOW);
    Rerater rerater = new Rerater(ds, h -> BEFORE, "rating-1");
    for (String org : orgs) {
      assertThat(rerater.trueUp(org, NOV, NOW)).as(org).isEmpty();
    }
  }
}
